package sts1solver.mirror;

import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves that a sandbox session changed nothing outside the mirror.
 *
 * <p>The mirror is a table of what we THINK combat state is. The dangerous failure is the field we
 * did not think of: the simulation writes it, the mirror never swaps it, and the live run is silently
 * corrupted in a way no later test notices. The predecessor project tried to solve this by curating
 * the table by hand and it failed exactly there ({@code EnergyPanel.totalCount}, the field card costs
 * are gated on, was missing).
 *
 * <p>So the check is GENERATED, not curated. {@code static-mutable.txt} is a complete inventory of
 * every mutable static in the shipped game, produced by scanning the jar offline. Before a sandbox
 * session we record every one of them (identity plus container size); afterwards we compare. Any
 * difference is either a hole in the mirror or a failed restore, and the report names it.
 */
public final class LeakCheck {

    private static List<Object[]> entries;
    private static int skipped;
    private static int unresolved;

    private LeakCheck() {
    }

    private static synchronized List<Object[]> entries() {
        if (entries != null) {
            return entries;
        }
        List<Object[]> out = new ArrayList<Object[]>();
        skipped = 0;
        unresolved = 0;
        InputStream in = LeakCheck.class.getResourceAsStream("/static-mutable.txt");
        if (in == null) {
            entries = out;
            return entries;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String current = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!line.startsWith(" ")) {
                    current = line.trim();
                    continue;
                }
                if (current == null) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    skipped++;
                    continue;
                }
                String name = parts[0];
                if (name.startsWith("<") || name.indexOf('<') >= 0) {
                    // The inventory marks a few fields whose real name could not be recovered.
                    skipped++;
                    continue;
                }
                Class<?> owner;
                try {
                    owner = Class.forName(current, false, LeakCheck.class.getClassLoader());
                } catch (Throwable t) {
                    unresolved++;
                    continue;
                }
                Field f = Reflect.find(owner, name);
                if (f == null || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    skipped++;
                    continue;
                }
                // Background machinery is excluded by TYPE, not by name: the game starts and stops
                // its own threads (an observed false positive: CardCrawlGame.sInputDetectThread
                // became null when the Steam input detector exited). A thread or a logger is not
                // combat state, and listing names one by one is the curation that failed before.
                Class<?> t = f.getType();
                if (t == Thread.class || t == ClassLoader.class || t == ThreadLocal.class
                        || t.getName().startsWith("org.apache.logging.log4j.")) {
                    skipped++;
                    continue;
                }
                // Resource caches are excluded by the same rule ClonePolicy uses to share them: the
                // coverage sweep initialises lazily-loaded textures (BiteEffect.top, HealPanelEffect
                // .img, ...) on first use, which is not state and cannot be leaked into a run.
                if (t.getName().startsWith("com.badlogic.gdx.graphics.")
                        || t.getName().startsWith("com.badlogic.gdx.audio.")
                        || t.getName().startsWith("com.badlogic.gdx.files.")
                        || t.getName().startsWith("com.esotericsoftware.spine.")) {
                    skipped++;
                    continue;
                }
                out.add(new Object[]{current + "#" + name, f});
            }
            reader.close();
        } catch (Throwable t) {
            Log.warn("leak inventory could not be read: " + t);
        }
        entries = out;
        return entries;
    }

    /** A snapshot of every inventoried static: identity for references, size for containers. */
    public static Map<String, String> snapshot() {
        List<Object[]> list = entries();
        Map<String, String> out = new HashMap<String, String>(list.size() * 2);
        for (int i = 0; i < list.size(); i++) {
            Object[] e = list.get(i);
            Field f = (Field) e[1];
            try {
                Object v = f.get(null);
                out.put((String) e[0], describe(v));
            } catch (Throwable t) {
                out.put((String) e[0], "unreadable");
            }
        }
        return out;
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        Class<?> c = v.getClass();
        if (c.isPrimitive() || v instanceof Number || v instanceof Boolean
                || v instanceof Character || v instanceof String || v instanceof Enum) {
            return c.getName() + ":" + String.valueOf(v);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(System.identityHashCode(v)));
        if (v instanceof java.util.List) {
            sb.append("/n=").append(((java.util.List<?>) v).size());
        } else if (v instanceof java.util.Map) {
            sb.append("/n=").append(((java.util.Map<?, ?>) v).size());
        } else if (c.isArray()) {
            sb.append("/n=").append(java.lang.reflect.Array.getLength(v));
        }
        return sb.toString();
    }

    /** @return human-readable differences between two snapshots, empty when nothing leaked */
    public static List<String> diff(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (now != null && !now.equals(e.getValue())) {
                out.add(e.getKey() + ": " + e.getValue() + " -> " + now);
            }
        }
        return out;
    }

    public static String inventoryReport() {
        List<Object[]> list = entries();
        return "leak inventory: " + list.size() + " static fields (skipped " + skipped
                + ", unresolved classes " + unresolved + ")";
    }
}