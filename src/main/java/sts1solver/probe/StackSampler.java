package sts1solver.probe;

import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples the stack of the thread that runs the search and groups the samples into 5-second phases,
 * then reports the FIRST and the LAST phase that collected enough samples.
 *
 * <p>Why phases and not fixed "early/late" windows: the point of the tool is to find cost that grows
 * as a fight goes on, and a fixed late window (18s+) silently reports nothing once a fix makes the
 * fight finish in 7 seconds. Phases compare like with like whatever the wall time turns out to be.
 *
 * <p>Sampling another thread is approximate by design ({@link Thread#getStackTrace()} may catch it
 * between frames); the counts are only used as ratios, sampled the same way in every phase.
 */
final class StackSampler {

    private static final long SAMPLE_EVERY_MS = 15L;
    private static final long PHASE_MS = 5000L;
    private static final long WARMUP_MS = 300L;
    private static final long ENOUGH_SAMPLES = 10L;

    /** One 5-second phase: how many samples landed in it and what they saw. */
    private static final class Phase {
        final Map<String, AtomicLong> leaf = new ConcurrentHashMap<String, AtomicLong>();
        final Map<String, AtomicLong> chain = new ConcurrentHashMap<String, AtomicLong>();
        final AtomicLong samples = new AtomicLong();
    }

    private final Map<Long, Phase> phases = new ConcurrentHashMap<Long, Phase>();
    private volatile boolean running = true;
    private Thread target;
    private long t0;

    void start() {
        target = Thread.currentThread();
        t0 = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            public void run() {
                sampleLoop();
            }
        }, "sts1solver-stacksampler");
        t.setDaemon(true);
        t.start();
    }

    private void sampleLoop() {
        while (running) {
            try {
                Thread.sleep(SAMPLE_EVERY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long elapsed = System.currentTimeMillis() - t0;
            if (elapsed < WARMUP_MS) {
                continue;
            }
            StackTraceElement[] st;
            try {
                st = target.getStackTrace();
            } catch (Throwable t) {
                continue;
            }
            if (st.length == 0) {
                continue;
            }
            long key = elapsed / PHASE_MS;
            Phase p = phases.get(key);
            if (p == null) {
                p = new Phase();
                phases.put(key, p);
            }
            p.samples.incrementAndGet();
            bump(p.leaf, name(st[0]));
            bump(p.chain, chain(st, 14));
        }
    }

    private static void bump(Map<String, AtomicLong> map, String key) {
        AtomicLong n = map.get(key);
        if (n == null) {
            map.put(key, new AtomicLong(1L));
        } else {
            n.incrementAndGet();
        }
    }

    private static String name(StackTraceElement e) {
        String cls = e.getClassName();
        int dot = cls.lastIndexOf('.');
        if (dot >= 0) {
            cls = cls.substring(dot + 1);
        }
        return cls + "." + e.getMethodName();
    }

    private static String chain(StackTraceElement[] st, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth && i < st.length; i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(name(st[i]));
        }
        return sb.toString();
    }

    void stopAndReport(String label) {
        running = false;
        try {
            Thread.sleep(SAMPLE_EVERY_MS * 3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        TreeMap<Long, Phase> sorted = new TreeMap<Long, Phase>(phases);
        List<Long> usable = new ArrayList<Long>();
        for (Map.Entry<Long, Phase> e : sorted.entrySet()) {
            if (e.getValue().samples.get() >= ENOUGH_SAMPLES) {
                usable.add(e.getKey());
            }
        }
        long total = 0L;
        StringBuilder counts = new StringBuilder();
        for (Map.Entry<Long, Phase> e : sorted.entrySet()) {
            long n = e.getValue().samples.get();
            total += n;
            counts.append(' ').append(e.getKey() * PHASE_MS / 1000L).append("s=").append(n);
        }
        Log.info("COST " + label + ": stack phases=" + sorted.size() + " samples=" + total
                + " (phase:counts)" + counts);
        if (usable.isEmpty()) {
            Log.info("COST " + label + ": stack phases: no phase gathered " + ENOUGH_SAMPLES
                    + " samples; nothing to compare");
            return;
        }
        long firstKey = usable.get(0);
        long lastKey = usable.get(usable.size() - 1);
        Phase first = sorted.get(firstKey);
        Phase last = sorted.get(lastKey);
        report(label, "stackEarlyLeaf(" + window(firstKey) + ")", first.leaf, first.samples.get(), null);
        report(label, "stackEarlyChain(" + window(firstKey) + ")", first.chain, first.samples.get(), null);
        if (lastKey == firstKey) {
            return;
        }
        report(label, "stackLateLeaf(" + window(lastKey) + ")", last.leaf, last.samples.get(), first.leaf);
        report(label, "stackLateChain(" + window(lastKey) + ")", last.chain, last.samples.get(), first.chain);
    }

    private static String window(long phase) {
        return (phase * PHASE_MS / 1000L) + "-" + ((phase + 1) * PHASE_MS / 1000L) + "s";
    }

    private static void report(String label, String what, Map<String, AtomicLong> map,
            long samples, Map<String, AtomicLong> other) {
        long denom = Math.max(1L, samples);
        List<Map.Entry<String, AtomicLong>> all = new ArrayList<Map.Entry<String, AtomicLong>>(
                map.entrySet());
        Collections.sort(all, new Comparator<Map.Entry<String, AtomicLong>>() {
            public int compare(Map.Entry<String, AtomicLong> a, Map.Entry<String, AtomicLong> b) {
                return Long.compare(b.getValue().get(), a.getValue().get());
            }
        });
        StringBuilder sb = new StringBuilder(what).append(':');
        for (int i = 0; i < all.size() && i < 12; i++) {
            Map.Entry<String, AtomicLong> en = all.get(i);
            long n = en.getValue().get();
            sb.append(" [").append(n * 100 / denom).append("%]").append(en.getKey());
            if (other != null) {
                AtomicLong before = other.get(en.getKey());
                long b = before == null ? 0L : before.get();
                sb.append('{').append(b == 0 ? "new" : (n * 100 / denom) + " vs " + (b * 100 / denom))
                        .append('}');
            }
        }
        Log.info("COST " + label + ": " + sb);
    }
}
