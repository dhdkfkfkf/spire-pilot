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
 * B200 switch: may 自动出牌 solve a picker that opened outside the planned line?
 *
 * <p>Default is ON (user decision 2026-11-21). When a mod opens a GRID/HAND/REWARD screen during a
 * turn the plan did not model -- 送葬人's counter trigger is the reported case -- the autopilot used
 * to stop and wait for a manual pick. The switch makes it take a short search rooted at the open
 * screen, adopt the answer only when that screen and its offers still match, and continue. A failed
 * search still falls back to the old "请你选" stop, so the player never loses the screen.
 *
 * <p>{@code settings.txt} key: {@code screenAssist=off} disables it. Missing key means ON.
 */
public final class ScreenAssist {

    static final String KEY = "screenAssist=";
    private static final long CACHE_MS = 1000L;

    /** Test seam: non-null overrides the file. */
    private static Boolean pinned;
    private static boolean cached = true;
    private static boolean cachedLoaded;
    private static long cachedAt;

    private ScreenAssist() {
    }

    public static boolean enabled() {
        if (pinned != null) {
            return pinned.booleanValue();
        }
        refresh();
        return cached;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (cachedLoaded && now - cachedAt < CACHE_MS) {
            return;
        }
        boolean on = true;
        try {
            File f = file();
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(KEY)) {
                            on = parse(line.substring(KEY.length()));
                        }
                    }
                } finally {
                    r.close();
                }
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break a search
        }
        cached = on;
        cachedLoaded = true;
        cachedAt = now;
    }

    private static boolean parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return true;
        }
        if ("off".equals(v) || "no".equals(v) || "false".equals(v)
                || "0".equals(v) || "\u5173".equals(v)) {
            return false;
        }
        if ("on".equals(v) || "yes".equals(v) || "true".equals(v)
                || "1".equals(v) || "\u5f00".equals(v)) {
            return true;
        }
        // An explicit but unrecognised value is more likely a failed attempt to turn the
        // irreversible auto-pick off; fail closed.
        return false;
    }

    /** Writes the key, preserving every other line. Takes effect immediately. */
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
            cached = on;
            cachedLoaded = true;
            cachedAt = System.currentTimeMillis();
            pinned = null;
            Log.info("screen assist: " + label());
        } catch (Throwable t) {
            Log.warn("screen assist: cannot write settings.txt: " + t);
        }
    }

    public static String label() {
        return enabled() ? "开（计划外选牌自动现场求解）" : "关（计划外选牌停下让你选）";
    }

    /** Test seam: pin the switch; {@code null} returns to reading settings.txt. */
    public static void setForTest(Boolean on) {
        pinned = on;
        cachedLoaded = false;
        cachedAt = 0L;
    }

    private static File file() {
        return new File(sts1solver.SolverMod.dir(), "settings.txt");
    }
}
