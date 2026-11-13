package sts1solver.mirror;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every mutable static scalar that a loaded mod owns, so the mirror can swap and restore it too.
 *
 * <p>The generated table ({@link GeneratedStaticTable}) was swept from the shipped jar only, which
 * meant a mod's own global state -- {@code public static int someCounter}, a cached list length, a
 * mode flag -- stayed shared between the sandbox and the live game. A simulated card that bumped
 * such a counter left the bump behind. Since m11229 accepts every mod, the sweep has to cover mod
 * jars as well.
 *
 * <p>Where the classes come from: ModTheSpire publishes every loaded mod in
 * {@code com.evacipated.cardcrawl.modthespire.Loader.MODINFOS}, each entry carrying its {@code jarURL}.
 * That is read reflectively so this class never has to link against MTS at compile time, and the
 * jar is then scanned like the game jar is: no initialisation ({@code Class.forName(name,false,...)}),
 * no behaviour, only field descriptors.
 *
 * <p>Boundary, stated honestly: only <b>scalars</b> (primitive, String, enum, Color) are mirrored.
 * A mod static that points at a mutable object (a manager singleton) is a CLONE candidate, not a
 * scalar, and is not handled here -- but combat state that hangs off the player, the monsters, the
 * card piles or an orb list travels with the object graph and is already isolated by the cloner.
 */
public final class ModStatics {

    private static List<Object[]> rows;
    private static List<String> keys = new ArrayList<String>();
    private static String report = "mod statics: not scanned";
    /** Candidates the sweep deliberately does NOT mirror, named so the boundary is visible. */
    private static final List<String> finalContainers = new ArrayList<String>();
    private static final List<String> objectRefs = new ArrayList<String>();
    /**
     * The declared type of each gap entry, kept beside the list rather than inside the entry string:
     * the entry itself has to be exactly {@code owner#field} so it can be compared with the mirror
     * table's keys (a type suffix in the key silently defeated that comparison once, 2026-11-02).
     */
    private static final List<String> finalContainerTypes = new ArrayList<String>();
    private static final List<String> objectRefTypes = new ArrayList<String>();
    /**
     * The same gaps as resolved fields, for {@link StaticWatch} (B105).
     *
     * <p>{@link #finalContainers()} / {@link #objectRefs()} are strings so a report can print them;
     * the watch needs the {@link Field} itself, and resolving a name again here would have to guess a
     * loader. Collected during the same pass, so a new mod is watched the moment it is reported.
     */
    private static final List<Field> watchCandidates = new ArrayList<Field>();

    /** How many unresolved candidates to keep: enough to name the gap, not enough to flood a log. */

    private ModStatics() {
    }

    /** Rows of {Class, fieldName} for {@link Statics#specs()}. */
    public static synchronized List<Object[]> rows() {
        build();
        return rows;
    }

    /** The same rows as {@code owner#field} keys, for the sweep diff and the gates. */
    public static synchronized List<String> keys() {
        build();
        return keys;
    }

    public static synchronized String report() {
        return report(null);
    }

    /**
     * The same report, with the static-final containers {@code handled} by
     * {@link Statics#MOD_CONTAINERS} taken out of the "left shared" list.
     *
     * <p>Without the filter the line reads "NOT mirrored: 40 ... containers=[...]" right next to the
     * mirror's own line naming containers it does mirror -- two statements that contradict each
     * other, which is exactly the "wrong output" this pass exists to remove.
     */
    public static synchronized String report(Set<String> handled) {
        build();
        List<String> leftContainers = new ArrayList<String>();
        List<String> leftContainerTypes = new ArrayList<String>();
        int handledContainers = 0;
        for (int i = 0; i < finalContainers.size(); i++) {
            String key = finalContainers.get(i);
            if (handled != null && handled.contains(key)) {
                handledContainers++;
            } else {
                leftContainers.add(key);
                leftContainerTypes.add(typeOf(finalContainers, finalContainerTypes, i));
            }
        }
        List<String> leftObjects = new ArrayList<String>();
        List<String> leftObjectTypes = new ArrayList<String>();
        for (int i = 0; i < objectRefs.size(); i++) {
            String key = objectRefs.get(i);
            if (handled == null || !handled.contains(key)) {
                leftObjects.add(key);
                leftObjectTypes.add(typeOf(objectRefs, objectRefTypes, i));
            }
        }
        List<String> otherContainers = new ArrayList<String>();
        List<String> otherContainerTypes = new ArrayList<String>();
        int textContainers = 0;
        int artContainers = 0;
        for (int i = 0; i < leftContainers.size(); i++) {
            String type = leftContainerTypes.get(i);
            int kind = kindOf(leftContainers.get(i), type);
            if (kind == KIND_TEXT) {
                textContainers++;
            } else if (kind == KIND_ART) {
                artContainers++;
            } else {
                otherContainers.add(leftContainers.get(i));
                otherContainerTypes.add(type);
            }
        }
        List<String> otherObjects = new ArrayList<String>();
        List<String> otherObjectTypes = new ArrayList<String>();
        int artObjects = 0;
        for (int i = 0; i < leftObjects.size(); i++) {
            String type = leftObjectTypes.get(i);
            if (kindOf(leftObjects.get(i), type) == KIND_ART) {
                artObjects++;
            } else {
                otherObjects.add(leftObjects.get(i));
                otherObjectTypes.add(type);
            }
        }
        // The player reads this line to learn whether a run is at risk. A raw dump of 225 field names
        // answered that question by volume, not by content: 183 of them are card/power description
        // arrays nobody mutates mid-fight. Text and art are counted; only "other" is listed, because
        // only "other" is a decision.
        return report
                + "; NOT mirrored: " + leftContainers.size()
                + " static-final container(s), " + leftObjects.size() + " static object ref(s)"
                + " (containers: " + textContainers + " text, " + artContainers + " art, "
                + otherContainers.size() + " other; object refs: " + artObjects + " art, "
                + otherObjects.size() + " other)"
                + (otherContainers.isEmpty() ? "" : " other-containers="
                        + display(otherContainers, otherContainerTypes))
                + (otherObjects.isEmpty() ? "" : " other-objects="
                        + display(otherObjects, otherObjectTypes))
                + (handledContainers == 0 ? "" : ", " + handledContainers
                        + " container(s) mirrored by content (see the mirror line above)");
    }

    /**
     * Mod {@code static final} containers (a list/map/set/array) this sweep leaves live-shared.
     *
     * <p>They cannot be classified safely from a field descriptor: the element type is erased, so
     * "a counter table of Integers" and "the registry of every card the mod registered" look
     * identical. Mirroring the second would hand the engine copies of its own singletons. They are
     * therefore reported by name and promoted one at a time to {@link Statics#MOD_CONTAINERS} once
     * a run shows the state is combat state.
     */
    public static synchronized List<String> finalContainers() {
        build();
        return finalContainers;
    }

    /** Mod statics pointing at a mutable object (manager singletons, caches): also not mirrored. */
    public static synchronized List<String> objectRefs() {
        build();
        return objectRefs;
    }

    /**
     * The same two groups as fields, for the cheap half of P2-62 ({@link StaticWatch}): the mirror
     * cannot own these, so they are watched for writes instead.
     *
     * <p>Enum constants, localization objects and textures are left out: they are immutable in
     * practice and watching them would only add work.
     */
    public static synchronized List<Field> watchCandidates() {
        build();
        return watchCandidates;
    }

    private static void build() {
        if (rows != null) {
            return;
        }
        long started = System.nanoTime();
        List<Object[]> out = new ArrayList<Object[]>();
        List<String> outKeys = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        List<File> jars = modJars();
        int classes = 0;
        int unloadable = 0;
        int vanillaBundled = 0;
        int finalContainerTotal = 0;
        int objectRefTotal = 0;
        int skippedFinal = 0;
        for (int j = 0; j < jars.size(); j++) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(jars.get(j));
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry entry = en.nextElement();
                    String path = entry.getName();
                    if (!path.endsWith(".class")) {
                        continue;
                    }
                    String className = path.substring(0, path.length() - 6).replace('/', '.');
                    // Inner classes DO hold mod state (`Outer$Mode.enabled`, a counter cached in a
                    // nested helper) and used to be skipped whole. Anonymous classes (`Foo$1`) and
                    // lambdas cannot declare the kind of field we mirror, so they stay out.
                    if (className.indexOf('$') >= 0 && !declaresMirrorState(className)) {
                        continue;
                    }
                    if (skipClass(className)) {
                        continue;
                    }
                    Class<?> type = load(className);
                    if (type == null) {
                        unloadable++;
                        continue;
                    }
                    // B116 (user m00748): judge by NAME, not by code source. We are iterating this
                    // mod's own jar entries, so the only question left is whether the base game also
                    // ships the name (an unbundled vanilla class). Provenance.isGame() additionally
                    // treats "no code source" as game code -- true for classes MTS redefines, and it
                    // used to hide real mod state: CaiNongMod.cards.BideTime#isUseAttack (a static
                    // that decides whether the card may be played) never entered the mirror, so every
                    // sandbox window read whichever value the last window left behind.
                    if (sts1solver.util.Provenance.shipsInGame(type.getName())) {
                        // A vanilla class this mod happened to bundle: the base game's own statics,
                        // already covered by the static sweep.
                        vanillaBundled++;
                        continue;
                    }
                    classes++;
                    Field[] fields = type.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        Field f = fields[i];
                        int mods = f.getModifiers();
                        if (!Modifier.isStatic(mods)) {
                            continue;
                        }
                        if (Modifier.isFinal(mods)) {
                            skippedFinal++;
                            if (containerType(f.getType())) {
                                finalContainerTotal++;
                                addGap(finalContainers, finalContainerTypes, className, f);
                                if (watchable(f)) {
                                    watchCandidates.add(f);
                                }
                            }
                            continue;
                        }
                        if (!mutableScalar(f)) {
                            objectRefTotal++;
                            addGap(objectRefs, objectRefTypes, className, f);
                            if (watchable(f)) {
                                watchCandidates.add(f);
                            }
                            continue;
                        }
                        String key = className + '#' + f.getName();
                        if (!seen.add(key)) {
                            continue;
                        }
                        out.add(new Object[]{type, f.getName()});
                        outKeys.add(key);
                    }
                }
            } catch (Throwable t) {
                // A broken jar contributes nothing; the report keeps the count honest.
                unloadable++;
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (Throwable ignored) {
                        // nothing to do
                    }
                }
            }
        }
        rows = out;
        keys = outKeys;
        report = "mod statics: " + out.size() + " field(s) in " + classes + " class(es) from "
                + jars.size() + " jar(s), " + unloadable + " not loadable, "
                + vanillaBundled + " vanilla bundled, "
                + skippedFinal + " final"
                + ", " + finalContainerTotal + " static-final container(s), "
                + objectRefTotal + " static object ref(s)"
                + " (" + ((System.nanoTime() - started) / 1000000L) + "ms)";
    }

    /**
     * The loaded mod jars. Deliberately excludes the framework (MTS/BaseMod/StSLib) and the JDK, whose
     * statics are engine plumbing rather than mod content, and this mod itself.
     */
    private static List<File> modJars() {
        List<File> out = new ArrayList<File>();
        try {
            Class<?> loader = Class.forName("com.evacipated.cardcrawl.modthespire.Loader");
            Object infos = loader.getField("MODINFOS").get(null);
            Object[] array = infos instanceof Object[] ? (Object[]) infos : null;
            if (array == null) {
                return out;
            }
            for (int i = 0; i < array.length; i++) {
                Object info = array[i];
                if (info == null) {
                    continue;
                }
                Object id = readField(info, "ID");
                Object url = readField(info, "jarURL");
                if (url instanceof java.net.URL) {
                    try {
                        File f = new File(((java.net.URL) url).toURI());
                        if (f.isFile() && !framework(String.valueOf(id), f.getName())) {
                            out.add(f);
                        }
                    } catch (Throwable ignored) {
                        // a jar we cannot address is skipped
                    }
                }
            }
        } catch (Throwable ignored) {
            // not running under ModTheSpire (unit tests, standalone probes): no mod statics
        }
        return out;
    }

    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether {@link StaticWatch} should watch this gap field. Enum constants, strings, localization
     * objects and textures are skipped: they are filled once and never written during a fight, and
     * the watch compares contents, so watching them would be pure overhead.
     */
    private static boolean watchable(Field f) {
        Class<?> t = f.getType();
        if (t.isPrimitive() || t.isEnum() || t == String.class) {
            return false;
        }
        String n = t.getName().toLowerCase(java.util.Locale.ROOT);
        return !(n.contains("texture") || n.contains("pixmap") || n.contains("sprite")
                || n.contains("atlas") || n.contains("string"));
    }

    private static boolean framework(String id, String jar) {
        String s = (id + "/" + jar).toLowerCase(java.util.Locale.ROOT);
        // Our own jar is NOT excluded: skipClass() already drops every sts1solver.* class, and the
        // jest fixture package demoMod.* must be scanned so the gate can see mod statics being
        // mirrored on an install that has no third-party mod loaded.
        return s.contains("modthespire") || s.contains("basemod") || s.contains("stslib");
    }

    /**
     * Names that are never mod state. The {@code com.megacrit.cardcrawl.} prefix is deliberately NOT
     * here: mods ship classes in the game's own namespace (ArlequinAndante's MerchantPlayer), and
     * skipping by package name hid their fields from this report. Provenance decides after load.
     */
    private static boolean skipClass(String name) {
        return name.startsWith("com.evacipated.")
                || name.startsWith("basemod.")
                || name.startsWith("javassist.")
                || name.startsWith("com.badlogic.")
                || name.startsWith("org.")
                || name.startsWith("com.codedisaster.")
                || name.startsWith("sts1solver.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("sun.");
    }

    /**
     * Finds a class by name across every loader a mod class can live in, without initialising it.
     *
     * <p>Public for {@link Statics#MOD_CONTAINERS}: a {@code static final} container is invisible to
     * this sweep (mutable scalars only) but still has to be sandboxed, so the mirror names it and
     * resolves it here.
     */
    public static Class<?> resolve(String name) {
        return load(name);
    }

    private static Class<?> load(String name) {
        ClassLoader[] loaders = {ModStatics.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()};
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loaders[i]);
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        return null;
    }

    /**
     * True when an inner class is worth scanning: a named one (`Outer$Helper`) may declare statics,
     * an anonymous one (`Outer$1`) or a lambda (`$$Lambda`) cannot declare the fields we mirror.
     */
    private static boolean declaresMirrorState(String className) {
        int at = className.lastIndexOf('$');
        if (at < 0 || at + 1 >= className.length()) {
            return false;
        }
        if (className.indexOf("$$Lambda") >= 0) {
            return false;
        }
        return !Character.isDigit(className.charAt(at + 1));
    }

    /** A JDK container a {@code static final} field can hold, whose contents could be state. */
    private static boolean containerType(Class<?> t) {
        if (t.isArray()) {
            return !t.getComponentType().isPrimitive();
        }
        return java.util.List.class.isAssignableFrom(t)
                || java.util.Map.class.isAssignableFrom(t)
                || java.util.Set.class.isAssignableFrom(t)
                || java.util.Collection.class.isAssignableFrom(t);
    }

    /**
     * Records one shared field. Uncapped on purpose: the count on the report line is what decides
     * whether a run is at risk, so it has to be the real number, and the printer only lists the
     * "other" bucket (a few dozen at most) rather than every description array.
     */
    private static void addGap(List<String> into, List<String> types, String className, Field f) {
        into.add(className + '#' + f.getName());
        types.add(f.getType().getSimpleName());
    }

    private static String typeOf(List<String> list, List<String> types, int i) {
        return types != null && i < types.size() ? types.get(i) : "?";
    }

    private static final int KIND_TEXT = 0;
    private static final int KIND_ART = 1;
    private static final int KIND_OTHER = 2;

    /**
     * What a shared static-final field most likely holds, judged from its name and declared type.
     *
     * <p>Deliberately a heuristic with an "other" bucket rather than a guess that looks certain:
     * text and art cannot carry combat state (they are never written mid-fight), everything else
     * stays on the list for a human.
     */
    private static int kindOf(String key, String type) {
        String field = key == null ? "" : key.substring(key.indexOf('#') + 1).toUpperCase(java.util.Locale.ROOT);
        String t = type == null ? "" : type;
        if (field.endsWith("DESCRIPTIONS") || field.endsWith("OPTIONS") || field.endsWith("TEXT")
                || field.endsWith("NAMES") || field.endsWith("KEYS") || field.endsWith("STRINGS")
                || field.endsWith("MESSAGES") || field.endsWith("LABELS") || field.endsWith("TIPS")
                || t.startsWith("String")) {
            return KIND_TEXT;
        }
        if (field.contains("TEXTURE") || field.contains("IMG") || field.contains("ICON")
                || field.contains("IMAGE") || field.contains("ATLAS") || field.contains("SPRITE")
                || t.contains("Texture") || t.contains("Pixmap") || t.contains("Sprite")
                || t.contains("Atlas")) {
            return KIND_ART;
        }
        return KIND_OTHER;
    }

    /** {@code owner#field : Type} for a report line, built at print time so the keys stay comparable. */
    private static List<String> display(List<String> keys, List<String> types) {
        List<String> shown = new ArrayList<String>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            shown.add(keys.get(i) + " : " + typeOf(keys, types, i));
        }
        return shown;
    }

    /**
     * Types the mirror can save and restore by value. Object references are excluded on purpose:
     * restoring the reference would not undo whatever was written inside the object.
     */
    private static boolean mutableScalar(Field f) {
        Class<?> t = f.getType();
        if (t.isPrimitive()) {
            return true;
        }
        if (t == String.class || t.isEnum()) {
            return true;
        }
        return "com.badlogic.gdx.graphics.Color".equals(t.getName());
    }
}
