package sts1solver.util;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which side of the fence a class really belongs to.
 *
 * <p>Package names lie in both directions. ArlequinAndante registers its merchant character inside
 * {@code com.megacrit.cardcrawl.characters}, so "the name says megacrit" cannot mean "base game";
 * and ModTheSpire re-defines {@code Ironclad}/{@code TheSilent}/{@code Defect} through its own
 * patching class loader, so "the code source is not the game jar" cannot mean "mod" either -- those
 * three are patched vanilla classes, not mod content.
 *
 * <p>The honest test is therefore: <em>does the base game jar ship a class with this name?</em> A
 * name the game ships is game code no matter which loader produced it; anything else came from a mod.
 */
public final class Provenance {

    private static File gameJar;
    private static boolean gameTried;
    private static Set<String> gameNames;
    private static boolean warned;

    private Provenance() {
    }

    /** The base game jar ({@code desktop-*.jar}), or null when it cannot be located. */
    public static synchronized File gameJar() {
        if (!gameTried) {
            gameTried = true;
            gameJar = findGameJar();
            if (gameJar == null && !warned) {
                warned = true;
                Log.warn("cannot locate the base game jar; mod/game ownership falls back to code source only");
            }
        }
        return gameJar;
    }

    /**
     * True when {@code c} is base game code: either the class name is shipped by the game jar (which
     * also covers classes ModTheSpire re-defines while patching them), or the class has no code
     * source at all (generated at runtime by a patch agent).
     */
    public static boolean isGame(Class<?> c) {
        if (c == null) {
            return true;
        }
        if (shipsInGame(c.getName())) {
            return true;
        }
        return sourceOf(c) == null;
    }

    /**
     * Same test for a class name. A name the game jar does not ship and that cannot even be loaded
     * is <em>not</em> game code: nothing proves it is, and the callers use this to decide what to
     * treat as foreign mod content (a mod hook, a mod card). Returning {@code true} here made every
     * name that does not exist on this install -- including a stranger's hook subscriber, which is
     * exactly what the mod-hook list is supposed to count -- look like vanilla.
     */
    public static boolean isGameName(String name) {
        if (name == null || name.length() == 0) {
            return true;
        }
        if (shipsInGame(name)) {
            return true;
        }
        try {
            return sourceOf(Class.forName(name, false, Provenance.class.getClassLoader())) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Does the base game jar contain {@code dottedName}?
     *
     * <p>Public because a jar sweep already knows where a class came from: while iterating the
     * entries of a mod jar, "is this name also shipped by the game?" is the only question left, and
     * asking {@link #isGame(Class)} there is wrong -- a class MTS redefined has no code source, and
     * the "no code source means game" rule then silently hides that mod's statics from the mirror
     * (B116: {@code CaiNongMod.cards.BideTime#isUseAttack} was skipped that way).
     *
     * <p>Unknown jar means false, so a missing {@code desktop-*.jar} cannot misclassify mod code.
     */
    public static synchronized boolean shipsInGame(String dottedName) {
        Set<String> names = gameNames();
        if (names == null) {
            return false;
        }
        return names.contains(dottedName);
    }

    private static synchronized Set<String> gameNames() {
        if (gameNames != null) {
            return gameNames;
        }
        File jar = gameJar();
        if (jar == null) {
            return null;
        }
        Set<String> names = new HashSet<String>();
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String path = en.nextElement().getName();
                if (path.endsWith(".class")) {
                    names.add(path.substring(0, path.length() - 6).replace('/', '.'));
                }
            }
        } catch (Throwable t) {
            Log.warn("cannot list the base game jar: " + t);
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                    // closing a read-only jar cannot lose data
                }
            }
        }
        gameNames = names;
        return names;
    }

    private static File findGameJar() {
        File fromClass = asFile(gameSourceOf(com.megacrit.cardcrawl.dungeons.AbstractDungeon.class));
        if (isDesktopJar(fromClass)) {
            return fromClass;
        }
        String dir = System.getProperty("user.dir", ".");
        File[] files = new File(dir).listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (isDesktopJar(files[i])) {
                    return files[i];
                }
            }
        }
        return null;
    }

    private static boolean isDesktopJar(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        String n = f.getName();
        return n.toLowerCase().startsWith("desktop-") && n.toLowerCase().endsWith(".jar");
    }

    private static URL gameSourceOf(Class<?> c) {
        try {
            CodeSource cs = c.getProtectionDomain() == null ? null : c.getProtectionDomain().getCodeSource();
            return cs == null ? null : cs.getLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    private static URL sourceOf(Class<?> c) {
        return gameSourceOf(c);
    }

    private static File asFile(URL url) {
        if (url == null) {
            return null;
        }
        try {
            return new File(url.toURI());
        } catch (Throwable t) {
            String s = url.getPath();
            return s == null || s.length() == 0 ? null : new File(s);
        }
    }
}
