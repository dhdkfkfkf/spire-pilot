package sts1solver.mirror;

import sts1solver.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B197/B198 switch: may a sandbox window keep BaseMod events to itself?
 *
 * <p>Default is OFF -- exactly the behaviour before 1.6.7. The mirror runs real engine code, so a
 * simulated attack publishes {@code onPlayerDamaged} like the live game does; every mod listener
 * runs and its effect belongs to the prediction. That is the most faithful mode. The price is that
 * a listener written for a live frame can misbehave there: luShi's {@code onAttackAssit} loops
 * forever on a condition the mirror never satisfies, and because the dispatch is synchronous on the
 * render thread the whole game freezes (hang-20261119-132655.txt).
 *
 * <p>Since 1.6.8 the switch has two independent groups (user 2026-11-19): the 15 combat hooks and
 * the 6 non-combat events a window can still reach. Both default to off. Turning a group on makes
 * {@link HookQuiet} return the neutral value for those dispatches inside a window, so no subscriber
 * in that group runs; a freeze caused by a hook in the other group is not prevented. Because a mod's
 * effect can span both groups, any partial choice loses fidelity, not just the all-on mode.
 *
 * <p>{@code settings.txt} keys: {@code compatCombat=on} and {@code compatOther=on}. The pre-1.6.8
 * key {@code compatHooks=on} is still honoured as "both groups on" when a group has no key of its
 * own, so an existing settings file keeps its behaviour.
 *
 * <p>Not a whitelist: mods whose combat logic lives in their cards, relics, potions, monsters or
 * powers are simulated for real in either mode.
 */
public final class CompatMode {

    static final String KEY_COMBAT = "compatCombat=";
    static final String KEY_OTHER = "compatOther=";
    /** 1.6.7 single switch; applies to a group only when that group has no key. */
    private static final String KEY_LEGACY = "compatHooks=";
    private static final long CACHE_MS = 1000L;

    /** Test seam: non-null overrides the file (see Probe S273). */
    private static Boolean pinnedCombat;
    private static Boolean pinnedOther;
    private static boolean cachedCombat;
    private static boolean cachedOther;
    private static boolean cachedLoaded;
    private static long cachedAt;

    private CompatMode() {
    }

    /** True when sandbox windows must not broadcast the 15 combat hooks. */
    public static boolean combat() {
        if (pinnedCombat != null) {
            return pinnedCombat.booleanValue();
        }
        refresh();
        return cachedCombat;
    }

    /** True when sandbox windows must not broadcast the 6 non-combat events. */
    public static boolean other() {
        if (pinnedOther != null) {
            return pinnedOther.booleanValue();
        }
        refresh();
        return cachedOther;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (cachedLoaded && now - cachedAt < CACHE_MS) {
            return;
        }
        boolean combat = false;
        boolean other = false;
        boolean legacy = false;
        boolean sawCombat = false;
        boolean sawOther = false;
        try {
            File f = file();
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(KEY_COMBAT)) {
                            combat = parse(line.substring(KEY_COMBAT.length()));
                            sawCombat = true;
                        } else if (line.startsWith(KEY_OTHER)) {
                            other = parse(line.substring(KEY_OTHER.length()));
                            sawOther = true;
                        } else if (line.startsWith(KEY_LEGACY)) {
                            legacy = parse(line.substring(KEY_LEGACY.length()));
                        }
                    }
                } finally {
                    r.close();
                }
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break a search
        }
        cachedCombat = sawCombat ? combat : legacy;
        cachedOther = sawOther ? other : legacy;
        cachedLoaded = true;
        cachedAt = now;
    }

    private static boolean parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "on".equals(v) || "yes".equals(v) || "true".equals(v)
                || "1".equals(v) || "\u5f00".equals(v);
    }

    /** Writes the combat key, preserving every other line. Takes effect immediately. */
    public static synchronized void writeCombat(boolean on) {
        write(KEY_COMBAT, on);
        cachedCombat = on;
        cachedLoaded = true;
        cachedAt = System.currentTimeMillis();
        Log.info("compat mode: " + label());
    }

    /** Writes the non-combat key, preserving every other line. Takes effect immediately. */
    public static synchronized void writeOther(boolean on) {
        write(KEY_OTHER, on);
        cachedOther = on;
        cachedLoaded = true;
        cachedAt = System.currentTimeMillis();
        Log.info("compat mode: " + label());
    }

    private static void write(String key, boolean on) {
        List<String> out = new ArrayList<String>();
        boolean saw = false;
        try {
            if (file().isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(file()), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(key)) {
                            out.add(key + (on ? "on" : "off"));
                            saw = true;
                        } else {
                            out.add(line);
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (!saw) {
                out.add(key + (on ? "on" : "off"));
            }
            PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file()), "UTF-8"));
            try {
                for (int i = 0; i < out.size(); i++) {
                    w.println(out.get(i));
                }
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.warn("compat mode: cannot write settings.txt: " + t);
        }
    }

    /** Current mode name for the panel and the log. */
    public static String label() {
        boolean c = combat();
        boolean o = other();
        if (!c && !o) {
            return "默认模式";
        }
        if (c && o) {
            return "兼容实验模式（两组都跳过）";
        }
        return c ? "兼容实验模式（只跳过战斗事件）"
                : "兼容实验模式（只跳过战斗之外的事件）";
    }

    /** Test seam: pin both groups; {@code null} returns to reading settings.txt. */
    public static void setForTest(Boolean both) {
        setForTest(both, both);
    }

    /** Test seam: pin each group; {@code null} returns to reading settings.txt. */
    public static void setForTest(Boolean combat, Boolean other) {
        pinnedCombat = combat;
        pinnedOther = other;
        clearCache();
    }

    private static void clearCache() {
        cachedLoaded = false;
        cachedAt = 0L;
    }

    private static File file() {
        return new File(sts1solver.SolverMod.dir(), "settings.txt");
    }
}
