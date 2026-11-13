package sts1solver.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * B47: a freeze inside our own code must leave a trail.
 *
 * <p>The first live report of "the game stops responding when I press auto-play" could only be
 * pinned down to "somewhere after this log line", because every candidate culprit (captureLive,
 * install, readBack, a synchronous sandbox turn, a mod hook) runs on the render thread and none of
 * them logs while it runs. A frozen frame therefore looks exactly like a quiet one.
 *
 * <p>This class is the cheap answer: the frame loop calls {@link #beat()} once per frame, and a
 * daemon thread notices when the beat stops. When it does it writes every thread stack to
 * {@code hang-<timestamp>.txt} in the solver directory and puts the main thread frames into the
 * normal log, so a single log tail names the method we are stuck in.
 *
 * <p>It never changes behaviour: no interrupts, no killing, no recovery. A dump is evidence, and the
 * process keeps running exactly as it would have. Dumps are capped ({@link #MAX_DUMPS} per process,
 * at most one per minute) so a permanently stuck instance does not fill the disk.
 */
public final class Watchdog {

    /** A frame that takes longer than this is reported. 4 s is far above any legitimate frame. */
    private static final long STALL_MS = 4000L;
    private static final long MIN_GAP_MS = 60000L;
    private static final int MAX_DUMPS = 5;
    private static final int MAX_FRAMES_PER_THREAD = 60;
    private static final int MAX_FRAMES_IN_LOG = 14;

    /**
     * B48: re-sample the stuck thread so one stack can be told apart from a moving one.
     *
     * <p>B54: one sample every 200 ms for 4 s, sixteen frames each. The first version took eight
     * samples of three frames and still could not answer the only question that matters for a
     * freeze: was the loop ours, or was the driver / GC / the disk holding the thread? Sixteen
     * frames are enough to see whether the work inside our frame is progressing, 4 s covers the
     * whole stall, and every sample records the GC counters and the heap so "a long GC" can be
     * confirmed or ruled out instead of guessed.
     */
    private static final int SAMPLES = 20;
    private static final long SAMPLE_GAP_MS = 200L;
    private static final int FRAMES_PER_SAMPLE = 16;

    private static volatile long beat = System.currentTimeMillis();
    private static volatile boolean started;
    private static volatile boolean armed = true;
    private static volatile int dumps;
    private static volatile long lastDumpAt;
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS");

    private Watchdog() {
    }

    /** Called once per rendered frame. Cheap on purpose: one volatile write. */
    public static void beat() {
        beat = System.currentTimeMillis();
    }

    public static void setArmed(boolean on) {
        armed = on;
        beat = System.currentTimeMillis();
    }

    public static boolean isArmed() {
        return armed;
    }

    public static int dumpCount() {
        return dumps;
    }

    /** Starts the daemon once. Safe to call from the initialisation hook. */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        beat = System.currentTimeMillis();
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "sts1-watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            Log.info("watchdog: armed (stall>=" + STALL_MS + "ms, up to " + MAX_DUMPS + " dumps per process)");
        } catch (Throwable t) {
            started = false;
            Log.warn("watchdog: could not start the monitor thread: " + t);
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                if (!armed || backgrounded()) {
                    // A paused window legitimately stops rendering. Do not call that a freeze.
                    beat = now;
                    continue;
                }
                long idle = now - beat;
                if (idle < STALL_MS) {
                    continue;
                }
                if (dumps >= MAX_DUMPS || now - lastDumpAt < MIN_GAP_MS) {
                    continue;
                }
                dump(idle);
            } catch (Throwable ignored) {
                // A monitor that throws must never take the game down with it.
            }
        }
    }

    private static boolean backgrounded() {
        try {
            return com.megacrit.cardcrawl.core.Settings.isBackgrounded;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Writes every thread stack to a file and the main thread frames to the log.
     *
     * @return the dump path, or null when nothing was written.
     */
    public static synchronized String dump(long idleMs) {
        lastDumpAt = System.currentTimeMillis();
        dumps++;
        String name = "hang-" + STAMP.format(new Date()) + ".txt";
        String path = Log.pathFor(name);
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        Thread stuck = stuckThread(all);
        // B48: taken BEFORE the dump is written, so the samples describe the frame that is stuck
        // rather than a thread that has moved on while a multi-megabyte file was being flushed.
        String[] sampling = sample(stuck);
        PrintWriter w = null;
        try {
            w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(path), false), StandardCharsets.UTF_8));
            w.println("# sts1-solver hang dump " + CLOCK.format(new Date()));
            w.println("reason  : the frame loop has not returned for " + idleMs + " ms");
            w.println("dump    : " + dumps + " of at most " + MAX_DUMPS + " for this process");
            w.println("state   : " + stateLine());
            w.println("phase   : " + phase());
            w.println("gc      : " + gcLine());
            w.println("threads : " + all.size());
            w.println("sampling: " + sampling[0]);
            w.println();
            w.println(sampling[1]);
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                StackTraceElement[] st = e.getValue();
                w.println("--- " + t.getName() + (t == stuck ? " (the thread that stopped beating)" : ""));
                w.println("    state=" + t.getState() + " daemon=" + t.isDaemon() + " priority=" + t.getPriority());
                int n = Math.min(st.length, MAX_FRAMES_PER_THREAD);
                for (int i = 0; i < n; i++) {
                    w.println("    at " + st[i]);
                }
                if (st.length > n) {
                    w.println("    ... " + (st.length - n) + " more frame(s)");
                }
                w.println();
            }
        } catch (Throwable t) {
            Log.warn("watchdog: writing " + path + " failed: " + t);
        } finally {
            if (w != null) {
                w.close();
            }
        }
        Log.warn("watchdog: no frame for " + idleMs + " ms -- the render thread is stuck. State: " + stateLine());
        Log.warn("watchdog: " + sampling[0]);
        Log.warn("watchdog: phase=" + phase() + " | " + gcLine());
        Log.warn("watchdog: full thread dump written to " + path);
        // The first frames of the render thread also go to the normal log, so a plain "tail -100"
        // names the method without opening a second file.
        try {
            if (stuck != null) {
                StackTraceElement[] st = all.get(stuck);
                int n = Math.min(st.length, MAX_FRAMES_IN_LOG);
                for (int i = 0; i < n; i++) {
                    Log.warn("watchdog:   " + stuck.getName() + " at " + st[i]);
                }
            }
        } catch (Throwable ignored) {
            // The dump is the last thing a stuck game can produce; whatever is readable is still
            // returned instead of throwing on top of a freeze.
        }
        return path;
    }

    /**
     * B48: one stack is not enough to tell "our own loop" from "the outside world went away".
     *
     * <p>The first dump stopped the render thread in {@code GL11.nglBindTexture}, called from our own
     * dotted line. That reads as a bug in our loop -- and it also reads as a driver stall, because a
     * blocking GL call lands in exactly the same frame: the driver blocks until the GPU catches up,
     * so a mountain of queued sprites looks identical to a mountain of queued loop iterations.
     *
     * <p>Re-sampling the same thread separates the two. A stack that is byte-identical every time is
     * an iteration of ours that never finishes; a stack that moves between samples (or that stays in
     * native code while nothing else on that thread does work) is the outside world, and no amount of
     * fixing our drawing loop would have prevented it.
     *
     * @return {verdict, detail block}
     */
    private static String[] sample(Thread stuck) {
        if (stuck == null) {
            return new String[]{"no thread to sample", ""};
        }
        StringBuilder detail = new StringBuilder();
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<String>();
        java.util.LinkedHashMap<String, Integer> seen = new java.util.LinkedHashMap<String, Integer>();
        StringBuilder tops = new StringBuilder();
        long gc0 = gcCollections();
        long gcMs0 = gcMillis();
        long started = System.currentTimeMillis();
        int taken = 0;
        for (int i = 0; i < SAMPLES; i++) {
            StackTraceElement[] st;
            try {
                st = stuck.getStackTrace();
            } catch (Throwable t) {
                break;
            }
            taken++;
            String top = st.length == 0 ? "-" : String.valueOf(st[0]);
            distinct.add(top);
            if (i > 0) {
                tops.append(" / ");
            }
            tops.append(top);
            String sig = frames(st);
            Integer had = seen.get(sig);
            seen.put(sig, Integer.valueOf(had == null ? 1 : had.intValue() + 1));
            detail.append("    sample ").append(i + 1).append(": ")
                    .append("gc=+").append(gcCollections() - gc0).append('/')
                    .append(gcMillis() - gcMs0).append("ms ")
                    .append("heap=").append(heapUsedMb()).append('/').append(heapMaxMb()).append("MB ")
                    .append("at ").append(top).append('\n');
            int n = Math.min(st.length, FRAMES_PER_SAMPLE);
            for (int k = 1; k < n; k++) {
                detail.append("        at ").append(st[k]).append('\n');
            }
            if (i + 1 < SAMPLES) {
                try {
                    Thread.sleep(SAMPLE_GAP_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        long span = System.currentTimeMillis() - started;
        String verdict;
        if (taken == 0) {
            verdict = "no sample could be taken";
        } else if (distinct.size() <= 1) {
            verdict = "the same stack in all " + taken + " samples over " + span + " ms -- a loop of ours ("
                    + tops + ")";
        } else {
            verdict = "the stack moved (" + distinct.size() + " distinct tops in " + taken + " samples over "
                    + span + " ms: " + tops + ") -- not a single loop of ours";
        }
        String hot = null;
        int hotCount = 0;
        for (java.util.Map.Entry<String, Integer> en : seen.entrySet()) {
            if (en.getValue().intValue() > hotCount) {
                hot = en.getKey();
                hotCount = en.getValue().intValue();
            }
        }
        StringBuilder out = new StringBuilder(detail);
        out.append("    most repeated stack (").append(hotCount).append(" of ").append(taken)
                .append(" samples):\n");
        out.append(hot == null ? "        -\n" : hot + "\n");
        out.append("    gc during the window: +").append(gcCollections() - gc0).append(" collection(s), +")
                .append(gcMillis() - gcMs0).append(" ms; heap ").append(heapUsedMb()).append('/')
                .append(heapMaxMb()).append(" MB\n");
        return new String[]{verdict, out.toString()};
    }

    /** Top {@link #FRAMES_PER_SAMPLE} frames, one per line, so identical stacks can be counted. */
    private static String frames(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(st.length, FRAMES_PER_SAMPLE);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("        at ").append(st[i]);
        }
        return sb.toString();
    }

    public static long gcCollections() {
        try {
            long n = 0L;
            for (java.lang.management.GarbageCollectorMXBean b
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (b.getCollectionCount() > 0L) {
                    n += b.getCollectionCount();
                }
            }
            return n;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static long gcMillis() {
        try {
            long n = 0L;
            for (java.lang.management.GarbageCollectorMXBean b
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (b.getCollectionTime() > 0L) {
                    n += b.getCollectionTime();
                }
            }
            return n;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static long heapUsedMb() {
        try {
            Runtime r = Runtime.getRuntime();
            return (r.totalMemory() - r.freeMemory()) / (1024L * 1024L);
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static long heapMaxMb() {
        try {
            return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** One line for the slow-frame log: is the machine collecting garbage while we work? */
    public static String gcLine() {
        return "gc=" + gcCollections() + "/" + gcMillis() + "ms heap=" + heapUsedMb() + "/" + heapMaxMb() + "MB";
    }

    /** Where the game is: startup / menu / a run's room and screen. */
    public static String phase() {
        try {
            Object mode = com.megacrit.cardcrawl.core.CardCrawlGame.mode;
            String m = mode == null ? "startup" : String.valueOf(mode);
            if (com.megacrit.cardcrawl.core.CardCrawlGame.dungeon == null) {
                return m + " (no run)";
            }
            String screen = com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen == null
                    ? "?" : String.valueOf(com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen);
            String room = "-";
            try {
                com.megacrit.cardcrawl.rooms.AbstractRoom r
                        = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
                if (r != null) {
                    room = r.getClass().getSimpleName();
                }
            } catch (Throwable ignored) {
                // Phase label for a log line: an unreadable room must not break the sampler.
            }
            return m + " screen=" + screen + " room=" + room;
        } catch (Throwable t) {
            return "phase unavailable: " + t;
        }
    }

    /** The render thread is the one that stopped calling beat(); pick it out of the dump. */
    private static Thread stuckThread(Map<Thread, StackTraceElement[]> all) {
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            StackTraceElement[] st = e.getValue();
            if (st.length == 0) {
                continue;
            }
            String top = st[0].getClassName();
            if (top.startsWith("com.megacrit") || top.startsWith("sts1solver")) {
                return e.getKey();
            }
        }
        for (Thread t : all.keySet()) {
            if ("LWJGL Application".equals(t.getName()) || "main".equals(t.getName())) {
                return t;
            }
        }
        return null;
    }

    private static String stateLine() {
        try {
            return sts1solver.Gameplay.watchdogState();
        } catch (Throwable t) {
            return "state unavailable: " + t;
        }
    }
}
