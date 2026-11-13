package sts1solver.mirror;

import sts1solver.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Regenerates and verifies the static-field mirror table by SCANNING THE GAME JAR (P2-4).
 *
 * <p>{@link GeneratedStaticTable} is generated, but the generator script was lost: the list could
 * only drift silently after a game update. This sweeps the shipped classes at runtime (loading them
 * WITHOUT initialization) and reports every mutable static scalar the table does not mirror -- the
 * dangerous direction, because a missing static silently corrupts the live game.
 *
 * <p>Run {@code -Mode genstatics} to print the full list and write it next to the mod; the suite
 * calls {@link #missingCount()} so drift fails a gate instead of a live test.
 */
public final class StaticSweep {

    private StaticSweep() {
    }

    /** Every mutable static scalar/string/enum in the shipped game, as owner#field keys. */
    public static List<String> sweep() {
        List<String> out = new ArrayList<String>();
        File jar = gameJar();
        if (jar == null) {
            Log.warn("static sweep: game jar not found on the classpath");
            addModStatics(out);
            return out;
        }
        Set<String> seen = new LinkedHashSet<String>();
        int classes = 0;
        int loadFailed = 0;
        int fieldsSeen = 0;
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("com/megacrit/cardcrawl/") || !name.endsWith(".class")
                        || name.contains("$")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                classes++;
                Class<?> c;
                try {
                    c = Class.forName(className, false, StaticSweep.class.getClassLoader());
                } catch (Throwable t) {
                    loadFailed++;
                    continue;
                }
                Field[] fields = c.getDeclaredFields();
                fieldsSeen += fields.length;
                for (int i = 0; i < fields.length; i++) {
                    Field f = fields[i];
                    int mods = f.getModifiers();
                    if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
                        continue;
                    }
                    Class<?> type = f.getType();
                    boolean scalar = type.isPrimitive() || type == String.class || type.isEnum()
                            || type == com.badlogic.gdx.graphics.Color.class;
                    if (!scalar) {
                        continue;
                    }
                    String fieldName = f.getName();
                    if (fieldName.contains("VOLUME") || fieldName.contains("MUTED")
                            || fieldName.contains("AUDIO") || fieldName.contains("BGM")
                            || fieldName.contains("SFX")) {
                        continue;
                    }
                    // Deliberate exclusions, same as the original generator: live input state,
                    // Steam callbacks and pure visual effects can never be combat state.
                    String cn = c.getName();
                    if (cn.contains(".helpers.input.") || cn.contains(".integrations.")
                            || cn.contains(".steamInput.") || cn.contains(".vfx.")
                            || cn.contains(".screens.mainMenu.")) {
                        continue;
                    }
                    seen.add(c.getName() + "#" + fieldName);
                }
            }
        } catch (Throwable t) {
            Log.warn("static sweep failed: " + t);
        }
        out.addAll(seen);
        Log.info("static sweep: classes=" + classes + " loadFailed=" + loadFailed
                + " fields=" + fieldsSeen + " mutableScalars=" + out.size());
        addModStatics(out);
        return out;
    }

    /**
     * Mod-owned statics join the sweep (m11229: every mod is accepted). They are mirrored, so they
     * must also be *checked*: a mod static the table refuses to mirror is a real leak, exactly like
     * a game static. {@link ModStatics} applies the same filters, so this only ever adds keys.
     */
    private static void addModStatics(List<String> out) {
        try {
            List<String> mod = ModStatics.keys();
            out.addAll(mod);
            if (!mod.isEmpty()) {
                Log.info("static sweep: " + mod.size() + " mod static(s); " + ModStatics.report());
            }
        } catch (Throwable t) {
            Log.warn("static sweep: mod statics unavailable: " + t);
        }
    }

    /** Keys the mirror installs (generated scalars + shell statics + every static Color). */
    public static Set<String> mirroredKeys() {
        return new LinkedHashSet<String>(Statics.mirrorKeys());
    }

    /** Swept statics the table does NOT mirror: must stay 0. */
    public static List<String> missingFromTable() {
        Set<String> table = mirroredKeys();
        List<String> missing = new ArrayList<String>();
        List<String> swept = sweep();
        for (int i = 0; i < swept.size(); i++) {
            if (!table.contains(swept.get(i))) {
                missing.add(swept.get(i));
            }
        }
        return missing;
    }

    public static int missingCount() {
        return missingFromTable().size();
    }

    /** Harness entry point: write the sweep next to the mod and report the drift. */
    public static void run() {
        List<String> swept = sweep();
        List<String> missing = missingFromTable();
        Log.info("STATICS swept=" + swept.size() + " mirrored=" + mirroredKeys().size()
                + " missing=" + missing.size());
        for (int i = 0; i < missing.size() && i < 40; i++) {
            Log.info("STATICS missing: " + missing.get(i));
        }
        try {
            java.io.File out = new java.io.File(sts1solver.SolverMod.dir(),
                    "static-sweep.txt");
            java.io.PrintWriter w = new java.io.PrintWriter(out, "UTF-8");
            for (int i = 0; i < swept.size(); i++) {
                w.println(swept.get(i));
            }
            w.close();
            Log.info("STATICS wrote " + out);
        } catch (Throwable t) {
            Log.warn("STATICS cannot write the sweep: " + t);
        }
        Log.info("AUTOTEST RESULT passed=" + (missing.isEmpty() ? 1 : 0)
                + " failed=" + (missing.isEmpty() ? 0 : 1));
        Log.info("AUTOTEST DONE");
        try {
            java.io.File req = new java.io.File(sts1solver.SolverMod.requestPath());
            if (req.exists()) {
                req.delete();
            }
        } catch (Throwable ignored) {
            // run.ps1 removes it too
        }
    }

    private static boolean containsGameClass(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry("com/megacrit/cardcrawl/core/CardCrawlGame.class") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File gameJar() {
        // The mod lives in <GameDir>/mods/, so the game jar is one level up. This works no matter
        // how ModTheSpire set the classpath (the game is not always launched as desktop-1.0.jar).
        try {
            java.security.CodeSource cs = StaticSweep.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File modJar = new File(cs.getLocation().toURI());
                File gameDir = modJar.isFile() ? modJar.getParentFile().getParentFile() : modJar;
                File direct = new File(gameDir, "desktop-1.0.jar");
                if (direct.isFile()) {
                    Log.info("static sweep: game jar " + direct);
                    return direct;
                }
                File[] jars = gameDir.listFiles();
                if (jars != null) {
                    for (int i = 0; i < jars.length; i++) {
                        if (!jars[i].isFile() || !jars[i].getName().endsWith(".jar")) {
                            continue;
                        }
                        if (containsGameClass(jars[i])) {
                            Log.info("static sweep: game jar " + jars[i]);
                            return jars[i];
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("static sweep: cannot resolve the game jar from the mod location: " + t);
        }
        // The reliable source: the code location of a game class itself.
        try {
            java.security.CodeSource cs = com.megacrit.cardcrawl.core.CardCrawlGame.class
                    .getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.isFile() && f.getName().endsWith(".jar")) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
            // fall back to the classpath scan
        }
        String cp = System.getProperty("java.class.path", "");
        String[] parts = cp.split(File.pathSeparator);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].endsWith("desktop-1.0.jar") && new File(parts[i]).isFile()) {
                return new File(parts[i]);
            }
        }
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].endsWith("SlayTheSpire.jar") && new File(parts[i]).isFile()) {
                return new File(parts[i]);
            }
        }
        return null;
    }
}