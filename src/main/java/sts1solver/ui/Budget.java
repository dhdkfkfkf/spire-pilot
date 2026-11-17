package sts1solver.ui;

import sts1solver.util.Log;

/**
 * Deep-search budget, changeable in game (P1-4e).
 *
 * <p>The budget lives in {@code %USERPROFILE%\spire-pilot\settings.txt} and is re-read on every
 * deep search, so writing the file is enough -- no restart, no reload hook. This class is the writer
 * and the profile cycle; the reader stays in {@link sts1solver.Gameplay} because the file is the
 * contract between them.
 */
public final class Budget {

    // 600s = the 10-minute ceiling the player asked for ("ten minutes is fine as long as it is
    // really computing"); DeepBudget.MAX_SECONDS clamps anything above it.
    // 0 = 不限 (P2-12, user m13385): the player asked for "no limit by default, I can wait as long as
    // the worldline counter keeps moving", and for the panel to still offer a concrete number. The
    // profiles below are only what the 秒数 / 节点 buttons step through; the file keeps the truth.
    // 1.0.8 (C6/C7, user): the ladder the 秒数 button steps through -- 自动 自动(6s) 自动(12s)
    // 自动(18s) 自动(30s) 自动(60s) 无限. -1 = 自动 (sized from the monsters in front of the player,
    // their HP and their block), a positive number is a hard wall-clock stop (past it the search
    // gives back the best line it has, degrading the planning depth 3 -> 2 -> 1 turn on the way),
    // 0 = 无限.
    private static final int[] SECONDS = {-1, 6, 12, 18, 30, 60, 0};
    private static final int[] NODES = {0, 100000, 200000, 400000, 1000000};
    /** Twelve seconds by default: long enough for a normal fight, short enough to read. */
    private static final int DEFAULT_SECONDS = 12;
    private static final int DEFAULT_NODES = 0;

    /** The one-time upgrade target for an existing settings.txt: unlimited, with a marker. */
    private static final int UNLIMITED_SECONDS = 0;
    private static final int UNLIMITED_NODES = 0;
    private static final String MIGRATED = "unlimitedBudget=1";

    private Budget() {
    }

    private static java.io.File file() {
        return new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
    }

    // Cached reads: the panel describes the budget on EVERY plan refresh, and autopilot refreshes
    // the panel per step. Reading the settings file twice per call was measurable stutter.
    private static int cachedSeconds = -1;
    private static int cachedNodes = -1;
    private static long cachedAt;
    private static final long CACHE_MS = 1000L;

    /** Drops the cache so the next read hits the file (after a write, or a harness change). */
    public static synchronized void invalidate() {
        cachedSeconds = -1;
        cachedNodes = -1;
        cachedAt = 0L;
    }

    /** Current deep-search seconds (default 60). */
    public static synchronized int seconds() {
        refreshCache();
        return cachedSeconds;
    }

    /** Current deep-search node cap (default 120000). */
    public static synchronized int nodes() {
        refreshCache();
        return cachedNodes;
    }

    private static void refreshCache() {
        long now = System.currentTimeMillis();
        if (cachedSeconds >= 0 && now - cachedAt < CACHE_MS) {
            return;
        }
        cachedSeconds = (int) readLong("deepSeconds=", DEFAULT_SECONDS);
        cachedNodes = (int) readLong("deepNodes=", DEFAULT_NODES);
        cachedAt = now;
    }

    /** Next budget profile: one step up the ladder, wrapping. Returns the new value. */
    public static int cycleSeconds() {
        int now = seconds();
        int at = -1;
        for (int i = 0; i < SECONDS.length; i++) {
            if (SECONDS[i] == now) {
                at = i;
                break;
            }
        }
        // Index arithmetic, not "first value greater than now": 自动(-1) sits below every number and
        // 无限(0) sits above them all, so a numeric scan would jump from 60s straight back to 自动.
        int next = SECONDS[(at + 1) % SECONDS.length];
        write(next, nodes());
        return next;
    }

    /** Next node profile: nodes up, wrapping. Returns the new value. */
    public static int cycleNodes() {
        int now = nodes();
        int next = NODES[0];
        for (int i = 0; i < NODES.length; i++) {
            if (NODES[i] > now) {
                next = NODES[i];
                break;
            }
        }
        write(seconds(), next);
        return next;
    }

    /** Writes both keys, preserving every other line of the file. */
    public static void write(int seconds, int nodes) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        boolean sawSeconds = false;
        boolean sawNodes = false;
        try {
            if (file().isFile()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(file()), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("deepSeconds=")) {
                            out.add("deepSeconds=" + seconds);
                            sawSeconds = true;
                        } else if (line.startsWith("deepNodes=")) {
                            out.add("deepNodes=" + nodes);
                            sawNodes = true;
                        } else {
                            out.add(line);
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (!sawSeconds) {
                out.add("deepSeconds=" + seconds);
            }
            if (!sawNodes) {
                out.add("deepNodes=" + nodes);
            }
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(file()), "UTF-8"));
            try {
                for (int i = 0; i < out.size(); i++) {
                    w.println(out.get(i));
                }
            } finally {
                w.close();
            }
            invalidate();
            Log.info("budget: deepSeconds=" + seconds + " deepNodes=" + nodes);
        } catch (Throwable t) {
            Log.warn("budget: cannot write settings.txt: " + t);
        }
    }

    /**
     * Moves an existing settings file up to the full-budget default exactly once.
     *
     * <p>Without the marker this would fight the player: cycling the budget down with the panel's
     * 秒数 button and restarting the game would silently push it back up. With the marker, the bump
     * happens once and every later value is the player's.
     */
    public static synchronized void migrateToUnlimitedBudget() {
        try {
            java.io.File f = file();
            String text = "";
            if (f.isFile()) {
                text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            }
            if (text.contains(MIGRATED)) {
                return;
            }
            int wasSeconds = seconds();
            int wasNodes = nodes();
            int seconds = UNLIMITED_SECONDS;
            int nodes = UNLIMITED_NODES;
            if (seconds != wasSeconds || nodes != wasNodes) {
                write(seconds, nodes);
                Log.info("budget: unlimited by default now (was " + wasSeconds + "s / " + wasNodes
                        + " nodes); the panel still lets you pick a number");
            }
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(f, true), "UTF-8"));
            try {
                w.println(MIGRATED);
            } finally {
                w.close();
            }
            invalidate();
        } catch (Throwable t) {
            Log.warn("budget: migration skipped: " + t);
        }
    }

    /** "自动", "自动(12s)" or "无限" -- the panel and the search progress share this wording. */
    public static String secondsLabel(int seconds) {
        if (seconds < 0) {
            return "自动";
        }
        return seconds == 0 ? "无限" : "自动(" + seconds + "s)";
    }

    /** "不限" or "400000" -- 0 nodes means no node cap at all. */
    public static String nodesLabel(int nodes) {
        return nodes <= 0 ? "不限" : String.valueOf(nodes);
    }

    /** One panel line. */
    public static String describe() {
        return "预算: " + secondsLabel(seconds()) + " / " + nodesLabel(nodes())
                + " 节点（面板「秒数」「节点」按钮切换；不限 = 一直算到分叉跑完或你再点「计算」）";
    }

    private static long readLong(String key, long fallback) {
        try {
            java.io.File f = file();
            if (!f.isFile()) {
                return fallback;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(key)) {
                        return Long.parseLong(line.substring(key.length()).trim());
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break a search
        }
        return fallback;
    }
}