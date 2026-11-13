package sts1solver.ui;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import sts1solver.util.Log;

/**
 * B59: what the frames look like while a search is running.
 *
 * <p>The 2026-10-31 elite fight froze the screen for 15.2s and the player reported "everything sped
 * up and stuttered after the solve". One slow-frame line per second cannot tell "our 40ms slice",
 * "a garbage collection" and "the driver stopped presenting" apart, so this keeps a per-frame
 * histogram and the GC counters over the search window and 5s after it.
 *
 * <p>Deliberately cheap: one {@link System#nanoTime()} pair and one int bump per frame; the report
 * is built once per second.
 */
public final class FrameStats {

    /** Frame-time histogram in whole milliseconds; the last bucket is "511ms or worse". */
    private static final int MAX_BUCKET = 511;
    private static final int[] HIST = new int[MAX_BUCKET + 1];

    private static long lastFrameNano;
    private static long startNano;
    private static long lastReportNano;
    private static long stopNano;
    private static long frames;
    private static int worstMs;
    private static int busyBucket;
    private static boolean active;
    private static long gcCountAtStart;
    private static long gcMsAtStart;
    private static long gcCountAtReport;
    private static long gcMsAtReport;
    private static List<GarbageCollectorMXBean> gcBeans;

    private FrameStats() {
    }

    /** Called when a solve (quick, deep or a plan replay) starts. */
    public static void startSearch() {
        active = true;
        stopNano = 0L;
        begin();
        Log.info("frame stats: sampling started " + gcNow());
    }

    /** Called when the search is over: keep sampling for {@link #TAIL_MS}, then log the summary. */
    public static void finishSearch() {
        if (!active) {
            return;
        }
        stopNano = System.nanoTime();
        logReport("search");
    }

    private static void begin() {
        java.util.Arrays.fill(HIST, 0);
        frames = 0L;
        worstMs = 0;
        busyBucket = 0;
        startNano = System.nanoTime();
        lastReportNano = startNano;
        lastFrameNano = startNano;
        gcCountAtStart = gcCount();
        gcMsAtStart = gcMs();
        gcCountAtReport = gcCountAtStart;
        gcMsAtReport = gcMsAtStart;
    }

    /** How long the sampler keeps running after the search ends (so the collection is covered). */
    public static final long TAIL_MS = 5000L;

    /** One call per rendered frame. Cheap enough to always call. */
    public static void tick() {
        if (!active) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNano != 0L) {
            int ms = (int) Math.min(MAX_BUCKET, (now - lastFrameNano) / 1000000L);
            HIST[ms]++;
            frames++;
            if (ms > worstMs) {
                worstMs = ms;
            }
            if (ms > 33) {
                busyBucket++;
            }
        }
        lastFrameNano = now;
        if (now - lastReportNano >= 1000000000L) {
            logReport("running");
            lastReportNano = now;
        }
        if (stopNano != 0L && now - stopNano >= TAIL_MS * 1000000L) {
            logReport("final");
            active = false;
        }
    }

    public static boolean active() {
        return active;
    }

    /** The line the logs carry; also the shape the gate asserts. */
    public static String describe() {
        int p50 = percentile(50);
        int p95 = percentile(95);
        long ms = lastFrameNano == 0L ? 0L : (lastFrameNano - startNano) / 1000000L;
        return "frames=" + frames + " over=" + ms + "ms p50=" + p50 + "ms p95=" + p95
                + "ms max=" + worstMs + "ms slow33=" + busyBucket
                + " gc=" + gcCount() + "/" + gcMs() + "ms (+" + (gcCount() - gcCountAtReport)
                + "/" + (gcMs() - gcMsAtReport) + "ms since report)"
                + " heap=" + heapNow() + "/" + Runtime.getRuntime().maxMemory() / (1024L * 1024L) + "MB";
    }

    private static void logReport(String phase) {
        Log.info("frame stats (" + phase + "): " + describe());
        gcCountAtReport = gcCount();
        gcMsAtReport = gcMs();
    }

    private static int percentile(int pct) {
        if (frames == 0L) {
            return 0;
        }
        long want = Math.max(1L, frames * pct / 100L);
        long seen = 0L;
        for (int i = 0; i <= MAX_BUCKET; i++) {
            seen += HIST[i];
            if (seen >= want) {
                return i;
            }
        }
        return MAX_BUCKET;
    }

    private static long heapNow() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024L * 1024L);
    }

    private static List<GarbageCollectorMXBean> beans() {
        if (gcBeans == null) {
            try {
                gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            } catch (Throwable t) {
                gcBeans = java.util.Collections.emptyList();
            }
        }
        return gcBeans;
    }

    public static long gcCount() {
        long n = 0L;
        for (GarbageCollectorMXBean b : beans()) {
            long v = b.getCollectionCount();
            if (v > 0L) {
                n += v;
            }
        }
        return n;
    }

    public static long gcMs() {
        long n = 0L;
        for (GarbageCollectorMXBean b : beans()) {
            long v = b.getCollectionTime();
            if (v > 0L) {
                n += v;
            }
        }
        return n;
    }

    /** B82: one short line for phase transitions and a slow heartbeat, so a heap that grows run
     * by run can be seen without a watchdog dump. */
    public static String heapLine() {
        return "heap=" + heapNow() + "/" + Runtime.getRuntime().maxMemory() / (1024L * 1024L)
                + "MB gc=" + gcCount() + "/" + gcMs() + "ms";
    }

    private static String gcNow() {
        return "gc=" + gcCount() + "/" + gcMs() + "ms heap=" + heapNow() + "MB";
    }

    /** Gate hook: forget the window without touching the counters the game owns. */
    public static void resetForTest() {
        active = false;
        stopNano = 0L;
        frames = 0L;
        worstMs = 0;
        busyBucket = 0;
        java.util.Arrays.fill(HIST, 0);
        startNano = System.nanoTime();
        lastReportNano = startNano;
        lastFrameNano = 0L;
    }

    /** Gate hook: pretend one frame took {@code ms} milliseconds. */
    public static void feedForTest(int ms) {
        if (!active) {
            return;
        }
        int b = Math.max(0, Math.min(MAX_BUCKET, ms));
        HIST[b]++;
        frames++;
        if (b > worstMs) {
            worstMs = b;
        }
        if (b > 33) {
            busyBucket++;
        }
    }
}
