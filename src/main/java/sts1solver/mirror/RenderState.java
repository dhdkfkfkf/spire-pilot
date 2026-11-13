package sts1solver.mirror;

import com.badlogic.gdx.graphics.Color;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.reflect.Reflect;
import sts1solver.util.JarScan;
import sts1solver.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the card renderer reads, captured so it can be compared and REPAIRED around a solve.
 *
 * <p>Motivation, in the user's words: "cards turn black after F8". A mirror that is wrong is not
 * supposed to be able to do that -- the live cards are cloned, not touched -- so either something is
 * aliased that should not be, or a shared {@link Color} was mutated in place. Both are silent: no
 * digest covers them, because the combat digest records numbers, not render state.
 *
 * <p>Rather than guess, the solve is wrapped:
 *
 * <pre>
 *   Snap before = RenderState.capture();
 *   ... search ...
 *   List&lt;String&gt; diffs = RenderState.diff(before, RenderState.capture());
 *   if (!diffs.isEmpty()) { log every one; RenderState.repair(before); }
 * </pre>
 *
 * <p>So the next occurrence both names the field and undoes the damage. The static-colour half is
 * also mirrored in {@link Statics} (COLOR kind), which is the actual prevention.
 */
public final class RenderState {

    private static final class Cell {
        final Object target;
        final Field field;
        final float[] rgba;
        final float value;
        final boolean isColor;

        Cell(Object target, Field field, float[] rgba, float value, boolean isColor) {
            this.target = target;
            this.field = field;
            this.rgba = rgba;
            this.value = value;
            this.isColor = isColor;
        }
    }

    public static final class Snap {
        final Map<String, Cell> cells = new LinkedHashMap<String, Cell>();
        final Map<String, String> values = new LinkedHashMap<String, String>();

        public int size() {
            return cells.size();
        }
    }

    private static List<Field> staticColors;
    private static final Field[] CARD_COLORS_CACHE_UNUSED = null;

    private RenderState() {
    }

    // ------------------------------------------------------------------ capture

    public static Snap capture() {
        Snap snap = new Snap();
        captureStaticColors(snap);
        captureCards(snap);
        captureCreatureTints(snap);
        return snap;
    }

    /**
     * The player and every monster carry a TintEffect; the skeleton is drawn with its color.
     *
     * <p>B37: it was aliased between the sandbox and the live fight (it lives in the vfx package,
     * and every vfx class was shared), so a simulated hit or a mod hook recoloured the creature the
     * player was looking at, and the colour stayed after the search because nothing resets it. The
     * cloner now copies it; this audit names the field and puts it back if anything else ever aliases
     * it again. Only the colour is tracked: positions and animation offsets legitimately move while
     * a search is running, and "restoring" those would fight the live game.
     */
    private static void captureCreatureTints(Snap snap) {
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer p = AbstractDungeon.player;
            if (p != null) {
                captureTint(snap, "creature player", p);
            }
            com.megacrit.cardcrawl.monsters.MonsterGroup group = AbstractDungeon.getMonsters();
            if (group != null && group.monsters != null) {
                for (int i = 0; i < group.monsters.size(); i++) {
                    com.megacrit.cardcrawl.monsters.AbstractMonster m = group.monsters.get(i);
                    if (m != null) {
                        captureTint(snap, "creature monster[" + i + "]#"
                                + Integer.toHexString(System.identityHashCode(m)) + " " + m.id, m);
                    }
                }
            }
        } catch (Throwable t) {
            // A creature that cannot be read is simply not tracked.
        }
    }

    private static void captureTint(Snap snap, String label,
                                    com.megacrit.cardcrawl.core.AbstractCreature c) {
        try {
            if (c.tint != null && c.tint.color != null) {
                putColor(snap, label + ".tint", c.tint.color);
            }
        } catch (Throwable t) {
            // reported by omission
        }
    }

    /** The tracked static Color fields, so {@link Statics} can mirror the same set. */
    public static List<Field> staticColorTargets() {
        return staticColorFields();
    }

    /**
     * Deduplicated by NAME, not by Field identity.
     *
     * <p>The same static field was being collected thousands of times because the mod loader and the
     * game's loader can hand back different {@code Class} objects for one class name, so identity
     * dedupe never matched (observed: "16564 static Color fields" when the real number is 126).
     */
    private static final java.util.Set<String> seen = new java.util.HashSet<String>();

    private static synchronized List<Field> staticColorFields() {
        if (staticColors != null) {
            return staticColors;
        }
        List<Field> out = new ArrayList<Field>();
        try {
            for (Class<?> c : JarScan.loadAll("com.megacrit.cardcrawl.", Object.class)) {
                addColorFields(out, c, true);
            }
            for (Class<?> c : JarScan.loadAll("basemod.", Object.class)) {
                addColorFields(out, c, true);
            }
        } catch (Throwable t) {
            Log.warn("static colour scan failed: " + t);
        }
        staticColors = out;
        Log.info("render state: " + out.size() + " static Color fields tracked (deduplicated by name)");
        return out;
    }

    /**
     * Adds a class's Color fields, once.
     *
     * <p>Walking the hierarchy for EVERY class re-adds every superclass field per subclass: the
     * first version reported "16564 static Color fields tracked" when the real number is 126. That
     * is not just a wrong number -- it made every capture and repair loop 130x longer than needed,
     * and the duplicates were invisible because the label map deduplicated them at the end.
     */
    private static void addColorFields(List<Field> out, Class<?> c, boolean statics) {

        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (f.getType() != Color.class) {
                    continue;
                }
                if (statics != java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String key = f.getDeclaringClass().getName() + "#" + f.getName();
                if (!seen.add(key)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                    seen.remove(key);
                    continue;
                }
                out.add(f);
            }
        }
    }

    private static void captureStaticColors(Snap snap) {
        List<Field> fields = staticColorFields();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            try {
                Object v = f.get(null);
                if (!(v instanceof Color)) {
                    continue;
                }
                String label = "static " + f.getDeclaringClass().getSimpleName() + "." + f.getName();
                putColor(snap, label, (Color) v);
            } catch (Throwable ignored) {
                // A class whose static cannot be read is simply not tracked.
            }
        }
    }

    private static void captureCards(Snap snap) {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null) {
            return;
        }
        capturePile(snap, "hand", p.hand);
        capturePile(snap, "draw", p.drawPile);
        capturePile(snap, "discard", p.discardPile);
        capturePile(snap, "exhaust", p.exhaustPile);
        capturePile(snap, "limbo", p.limbo);
        capturePile(snap, "master", p.masterDeck);
    }

    private static void capturePile(Snap snap, String pile, CardGroup group) {
        if (group == null || group.group == null) {
            return;
        }
        for (int i = 0; i < group.group.size(); i++) {
            AbstractCard card = group.group.get(i);
            if (card == null) {
                continue;
            }
            String base = "card " + pile + "[" + i + "]" + "#" + Integer.toHexString(
                    System.identityHashCode(card)) + " " + card.cardID;
            for (Class<?> k = card.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                Field[] fields;
                try {
                    fields = k.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }
                for (Field f : fields) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (f.getType() == Color.class) {
                        Object v = Reflect.get(f, card);
                        if (v instanceof Color) {
                            putColor(snap, base + "." + f.getName(), (Color) v);
                        }
                    } else if (f.getType() == float.class && isVisualFloat(f.getName())) {
                        putFloat(snap, base + "." + f.getName(), card, f);
                    }
                }
            }
        }
    }

    private static boolean isVisualFloat(String name) {
        return name.equals("transparency") || name.equals("targetTransparency")
                || name.equals("drawScale") || name.equals("targetDrawScale")
                || name.equals("angle") || name.equals("targetAngle");
    }

    private static void putColor(Snap snap, String label, Color c) {
        float[] rgba = new float[]{c.r, c.g, c.b, c.a};
        snap.cells.put(label, new Cell(c, null, rgba, 0.0f, true));
        snap.values.put(label, rgba[0] + "," + rgba[1] + "," + rgba[2] + "," + rgba[3]);
    }

    private static void putFloat(Snap snap, String label, Object target, Field f) {
        Object v = Reflect.get(f, target);
        if (!(v instanceof Float)) {
            return;
        }
        float value = ((Float) v).floatValue();
        snap.cells.put(label, new Cell(target, f, null, value, false));
        snap.values.put(label, String.valueOf(value));
    }

    // ------------------------------------------------------------------ compare / repair

    public static List<String> diff(Snap before, Snap after) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : before.values.entrySet()) {
            String now = after.values.get(e.getKey());
            if (now != null && !now.equals(e.getValue())) {
                out.add(e.getKey() + ": " + e.getValue() + " -> " + now);
            }
        }
        return out;
    }

    /**
     * B59: the 136 static {@link Color}s, held by identity, for a check that is cheap enough to run
     * between search slices.
     *
     * <p>The full {@link Snap} walks every card's colour and scale, which is fine once per search but
     * far too expensive per 40ms slice. The static colours are the half a sandbox search actually
     * writes, so this is the half worth watching while the player is looking at the screen: the
     * damage then lasts a slice instead of the whole search.
     */
    public static final class StaticColors {
        final List<Field> fields = new ArrayList<Field>();
        final List<Color> were = new ArrayList<Color>();
        final List<String> names = new ArrayList<String>();
        final List<float[]> rgba = new ArrayList<float[]>();
    }

    /** Snapshot of the static colours only (identity + rgba), for {@link #diffStatics}. */
    public static StaticColors captureStaticColorsOnly() {
        StaticColors sc = new StaticColors();
        for (Field f : staticColorFields()) {
            try {
                Object v = f.get(null);
                if (!(v instanceof Color)) {
                    continue;
                }
                Color c = (Color) v;
                sc.fields.add(f);
                sc.were.add(c);
                sc.names.add("static " + f.getDeclaringClass().getSimpleName() + "." + f.getName());
                sc.rgba.add(new float[]{c.r, c.g, c.b, c.a});
            } catch (Throwable ignored) {
                // A class whose static cannot be read is simply not tracked.
            }
        }
        return sc;
    }

    /** Names (and new values) of the static colours that changed since the snapshot. */
    public static List<String> diffStatics(StaticColors sc) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < sc.fields.size(); i++) {
            try {
                Object now = sc.fields.get(i).get(null);
                if (now != sc.were.get(i)) {
                    out.add(sc.names.get(i) + ": replaced");
                    continue;
                }
                Color c = (Color) now;
                float[] w = sc.rgba.get(i);
                if (c.r != w[0] || c.g != w[1] || c.b != w[2] || c.a != w[3]) {
                    out.add(sc.names.get(i) + ": " + describeColor(w) + " -> " + describeColor(c));
                }
            } catch (Throwable ignored) {
                // ignore a field that became unreadable
            }
        }
        return out;
    }

    /** Puts the static colours back. @return how many were restored */
    public static int repairStatics(StaticColors sc) {
        int fixed = 0;
        for (int i = 0; i < sc.fields.size(); i++) {
            try {
                Object now = sc.fields.get(i).get(null);
                if (now != sc.were.get(i)) {
                    sc.fields.get(i).set(null, sc.were.get(i));
                    fixed++;
                    continue;
                }
                Color c = (Color) now;
                float[] w = sc.rgba.get(i);
                if (c.r != w[0] || c.g != w[1] || c.b != w[2] || c.a != w[3]) {
                    c.r = w[0];
                    c.g = w[1];
                    c.b = w[2];
                    c.a = w[3];
                    fixed++;
                }
            } catch (Throwable ignored) {
                // Never let a repair attempt break the frame.
            }
        }
        return fixed;
    }

    private static String describeColor(float[] v) {
        return "(" + v[0] + "," + v[1] + "," + v[2] + "," + v[3] + ")";
    }

    private static String describeColor(Color c) {
        return "(" + c.r + "," + c.g + "," + c.b + "," + c.a + ")";
    }

    /** Writes the captured values back. @return how many cells were actually restored */
    public static int repair(Snap before) {
        int fixed = 0;
        for (Map.Entry<String, Cell> e : before.cells.entrySet()) {
            Cell c = e.getValue();
            try {
                if (c.isColor) {
                    boolean changed = c.rgba[0] != ((Color) c.target).r
                            || c.rgba[1] != ((Color) c.target).g
                            || c.rgba[2] != ((Color) c.target).b
                            || c.rgba[3] != ((Color) c.target).a;
                    if (changed) {
                        Color col = (Color) c.target;
                        col.r = c.rgba[0];
                        col.g = c.rgba[1];
                        col.b = c.rgba[2];
                        col.a = c.rgba[3];
                        fixed++;
                    }
                } else {
                    Object now = Reflect.get(c.field, c.target);
                    if (!(now instanceof Float) || ((Float) now).floatValue() != c.value) {
                        c.field.set(c.target, Float.valueOf(c.value));
                        fixed++;
                    }
                }
            } catch (Throwable t) {
                // Never let a repair attempt break the frame.
            }
        }
        return fixed;
    }
}