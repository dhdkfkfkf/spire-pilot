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

/**
 * B197 switch: may a sandbox window broadcast combat hooks to other mods?
 *
 * <p>Default is OFF -- exactly the behaviour before 1.6.7. The mirror runs real engine code, so a
 * simulated attack publishes {@code onPlayerDamaged} like the live game does; every mod listener
 * runs and its effect belongs to the prediction. That is the most faithful mode. The price is that
 * a listener written for a live frame can misbehave there: luShi's {@code onAttackAssit} loops
 * forever on a condition the mirror never satisfies, and because the dispatch is synchronous on the
 * render thread the whole game freezes (hang-20261119-132655.txt).
 *
 * <p>ON is the "兼容实验模式" (also {@code compatHooks=on} in settings.txt): {@link HookQuiet}
 * returns the neutral value inside a window and no subscriber runs, so no third-party hook can
 * freeze a search. The cost is fidelity -- effects that live in those hooks are not modelled, so a
 * fight with mod content gets a reference plan, not a guaranteed one.
 *
 * <p>Not a whitelist: the switch is engine-wide, and mods whose combat logic lives in their cards,
 * relics, potions, monsters or powers are simulated for real in either mode.
 */
public final class CompatMode {

    /** settings.txt key; "on" enables the isolation. Any other value (or no line) keeps the default. */
    private static final String KEY = "compatHooks=";
    private static final long CACHE_MS = 1000L;

    /** Test seam: non-null overrides the file (see Probe S273). */
    private static Boolean pinned;
    private static Boolean cached;
    private static long cachedAt;

    private CompatMode() {
    }

    /** True when sandbox windows must not broadcast combat hooks to other mods. */
    public static boolean isolation() {
        if (pinned != null) {
            return pinned.booleanValue();
        }
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) {
            return cached.booleanValue();
        }
        boolean on = false;
        try {
            File f = file();
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(KEY)) {
                            String v = line.substring(KEY.length()).trim().toLowerCase(java.util.Locale.ROOT);
                            on = "on".equals(v) || "yes".equals(v) || "true".equals(v)
                                    || "1".equals(v) || "\u5f00".equals(v);
                        }
                    }
                } finally {
                    r.close();
                }
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break a search
        }
        cached = Boolean.valueOf(on);
        cachedAt = now;
        return on;
    }

    /** Writes the key, preserving every other line of settings.txt. Takes effect immediately. */
    public static synchronized void write(boolean on) {
        List<String> out = new ArrayList<String>();
        boolean saw = false;
        try {
            if (file().isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(file()), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(KEY)) {
                            out.add(KEY + (on ? "on" : "off"));
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
                out.add(KEY + (on ? "on" : "off"));
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
            cached = Boolean.valueOf(on);
            cachedAt = System.currentTimeMillis();
            Log.info("compat mode: " + (on ? "ON (sandbox hooks muted)" : "OFF (default, hooks run)"));
        } catch (Throwable t) {
            Log.warn("compat mode: cannot write settings.txt: " + t);
        }
    }

    /** Current mode name for the panel and the log. */
    public static String label() {
        return isolation() ? "兼容实验模式" : "默认模式";
    }

    /** Test seam: pin the mode; {@code null} returns to reading settings.txt. */
    public static void setForTest(Boolean value) {
        pinned = value;
        cached = value;
        cachedAt = System.currentTimeMillis();
    }

    private static File file() {
        return new File(sts1solver.SolverMod.dir(), "settings.txt");
    }
}
