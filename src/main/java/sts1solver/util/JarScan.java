package sts1solver.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Enumerates the classes the running game can load: the game jar AND every mod jar on the loader
 * chain.
 *
 * <p>Coverage must be measured over the CONTENT THAT EXISTS, not over a list someone typed. The
 * predecessor's coverage tables were hand-maintained, and its notes record the consequence. Reading
 * every jar means a card, relic, potion, monster or character added by a mod is counted without
 * anyone remembering to add it.
 */
public final class JarScan {

    private static List<String> classNames;
    private static List<File> jars;

    private JarScan() {
    }

    public static synchronized List<String> classes() {
        if (classNames != null) {
            return classNames;
        }
        List<String> out = new ArrayList<String>();
        List<File> files = allJars();
        Log.info("jar scan: " + files.size() + " jar(s)");
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            JarFile jar = null;
            try {
                jar = new JarFile(f);
                Enumeration<JarEntry> e = jar.entries();
                while (e.hasMoreElements()) {
                    String n = e.nextElement().getName();
                    if (n.endsWith(".class") && !n.contains("$")) {
                        out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    }
                }
            } catch (Throwable t) {
                Log.warn("jar scan: cannot read " + f + ": " + t);
            } finally {
                if (jar != null) {
                    try {
                        jar.close();
                    } catch (Throwable ignored) {
                        // closing is best effort
                    }
                }
            }
        }
        classNames = out;
        return out;
    }

    /**
     * Every jar this mod can see: the game jar plus every jar on the loader chain (ModTheSpire puts
     * the game, the workshop mods and the local mods there).
     */
    public static synchronized List<File> allJars() {
        if (jars != null) {
            return jars;
        }
        List<File> out = new ArrayList<File>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        try {
            File game = locateJar();
            if (game != null && game.isFile() && seen.add(game.getAbsolutePath())) {
                out.add(game);
            }
        } catch (Throwable ignored) {
            // the game jar is optional; mod jars may still be reachable
        }
        ClassLoader cl = JarScan.class.getClassLoader();
        while (cl != null) {
            if (cl instanceof java.net.URLClassLoader) {
                for (java.net.URL u : ((java.net.URLClassLoader) cl).getURLs()) {
                    try {
                        File f = new File(u.toURI());
                        if (f.isFile() && f.getName().endsWith(".jar")
                                && seen.add(f.getAbsolutePath())) {
                            out.add(f);
                        }
                    } catch (Throwable ignored) {
                        // non-file URLs are not class containers we can scan
                    }
                }
            }
            cl = cl.getParent();
        }
        addMtsMods(out, seen);
        jars = out;
        return out;
    }

    /**
     * Asks ModTheSpire which jars it loaded.
     *
     * <p>The loader chain alone was NOT enough: it reported 4 jars (the game plus ModTheSpire and its
     * two libraries) and none of the workshop mods, so the "coverage" numbers were vanilla-only while
     * claiming to be everything. MTS keeps the real list in Loader.MODINFOS, with a jarURL per mod.
     */
    private static void addMtsMods(List<File> out, java.util.Set<String> seen) {
        try {
            Class<?> loader = Class.forName("com.evacipated.cardcrawl.modthespire.Loader",
                    false, JarScan.class.getClassLoader());
            Object infos = loader.getField("MODINFOS").get(null);
            if (infos instanceof Object[]) {
                Object[] arr = (Object[]) infos;
                for (int i = 0; i < arr.length; i++) {
                    Object info = arr[i];
                    if (info == null) {
                        continue;
                    }
                    try {
                        Object url = info.getClass().getField("jarURL").get(info);
                        if (url instanceof java.net.URL) {
                            File f = new File(((java.net.URL) url).toURI());
                            if (f.isFile() && f.getName().endsWith(".jar")
                                    && seen.add(f.getAbsolutePath())) {
                                out.add(f);
                            }
                        }
                    } catch (Throwable ignored) {
                        // one unreadable mod entry must not stop the scan
                    }
                }
            }
            Object modDir = loader.getField("MOD_DIR").get(null);
            if (modDir instanceof String) {
                File dir = new File((String) modDir);
                File[] jars = dir.listFiles();
                if (jars != null) {
                    for (int i = 0; i < jars.length; i++) {
                        File f = jars[i];
                        if (f.isFile() && f.getName().endsWith(".jar")
                                && seen.add(f.getAbsolutePath())) {
                            out.add(f);
                        }
                    }
                }
            }
            Log.info("jar scan: ModTheSpire reported " + (infos instanceof Object[]
                    ? ((Object[]) infos).length : -1) + " mod(s)");
        } catch (Throwable t) {
            Log.warn("jar scan: cannot read ModTheSpire's mod list: " + t);
        }
    }

    /** Class names under a package prefix, sorted, loadable without running their initialiser. */
    public static List<Class<?>> loadAll(String prefix, Class<?> requiredSuper) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        for (String n : classes()) {
            if (!n.startsWith(prefix)) {
                continue;
            }
            tryAdd(out, n, requiredSuper);
        }
        sort(out);
        return out;
    }

    /** Concrete subtypes of {@code requiredSuper} across EVERY jar, mods included. */
    public static List<Class<?>> loadAllTypes(Class<?> requiredSuper) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        java.util.Set<String> done = new java.util.HashSet<String>();
        for (String n : classes()) {
            if (!done.add(n)) {
                continue;
            }
            tryAdd(out, n, requiredSuper);
        }
        sort(out);
        return out;
    }

    private static void tryAdd(List<Class<?>> out, String name, Class<?> requiredSuper) {
        try {
            Class<?> c = Class.forName(name, false, JarScan.class.getClassLoader());
            if (requiredSuper.isAssignableFrom(c)
                    && !java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                out.add(c);
            }
        } catch (Throwable ignored) {
            // unloadable class = not test content
        }
    }

    private static void sort(List<Class<?>> out) {
        java.util.Collections.sort(out, new java.util.Comparator<Class<?>>() {
            public int compare(Class<?> a, Class<?> b) {
                return a.getName().compareTo(b.getName());
            }
        });
    }

    /**
     * The game jar.
     *
     * <p>The protection domain is the tidy answer and it returns null under ModTheSpire's patched
     * loader, so it falls back to the working directory the game was started in, then to the
     * well-known install path.
     */
    private static File locateJar() {
        try {
            java.security.CodeSource cs = com.megacrit.cardcrawl.dungeons.AbstractDungeon.class
                    .getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.isFile()) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        File local = new File(System.getProperty("user.dir", "."), "desktop-1.0.jar");
        if (local.isFile()) {
            return local;
        }
        return new File("D:\\Steam\\steamapps\\common\\SlayTheSpire\\desktop-1.0.jar");
    }
}