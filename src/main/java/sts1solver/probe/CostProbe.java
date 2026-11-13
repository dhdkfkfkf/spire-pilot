package sts1solver.probe;

import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Measures what one search node costs: engine updates, where a drain update spends its time
 * (the engine's action pump vs the room object pump), presentation share, fork vs apply.
 *
 * <p>P1-2c/P1-2d exist because the same search costs ~1ms/node in an ordinary fight but 6-11ms/node
 * against the phase bosses. Comparing the same deck across fights isolates the fight itself from the
 * deck size, which is the first thing to rule out.
 */
public final class CostProbe {

    /** The realistic 30-card deck AuditProbe uses, so these numbers line up with the audit table. */
    private static final String[] REAL_DECK = {"Strike_R", "Strike_R", "Strike_R", "Strike_R",
            "Strike_R", "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R",
            "Inflame", "Metallicize", "ShrugItOff", "PommelStrike", "Cleave",
            "HeavyBlade", "Uppercut", "TrueGrit", "Thunderclap", "BodySlam",
            "Flex", "Anger", "SecondWind", "SeverSoul", "GhostlyArmor",
            "IronWave", "Clothesline", "Bloodletting"};

    private CostProbe() {
    }

    public static void run(String which) {
        if ("all".equals(which)) {
            fight("JawWorm", "com.megacrit.cardcrawl.monsters.exordium.JawWorm", 8000L);
            fight("SlimeBoss", "com.megacrit.cardcrawl.monsters.exordium.SlimeBoss", 15000L);
            fight("AwakenedOne", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne", 15000L);
            fight("TheCollector", "com.megacrit.cardcrawl.monsters.city.TheCollector", 15000L);
            fight("BronzeAutomaton", "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton", 15000L);
            done();
            return;
        }
        if ("fork".equals(which)) {
            // Cloner A/B: the same captured fight forked under both paths in one JVM.
            forkBench("JawWorm", "com.megacrit.cardcrawl.monsters.exordium.JawWorm");
            forkBench("TheCollector", "com.megacrit.cardcrawl.monsters.city.TheCollector");
            forkBench("BronzeAutomaton", "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton");
            done();
            return;
        }
        if ("long".equals(which)) {
            // One fight, a long budget, sampled every three seconds: does the cost drift upward
            // inside a single fight, or only across fights?
            fight("BronzeAutomaton-long", "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton",
                    60000L);
            done();
            return;
        }
        if ("repeat".equals(which)) {
            // The SAME fight three times: if it gets slower each time, the growth is per-fight state
            // in this JVM, not anything about a particular fight.
            fight("JawWorm#1", "com.megacrit.cardcrawl.monsters.exordium.JawWorm", 8000L);
            fight("JawWorm#2", "com.megacrit.cardcrawl.monsters.exordium.JawWorm", 8000L);
            fight("JawWorm#3", "com.megacrit.cardcrawl.monsters.exordium.JawWorm", 8000L);
            done();
            return;
        }
        if ("reverse".equals(which)) {
            // Same five fights, opposite order: whichever fight is slow here is slow because of the
            // fights that ran BEFORE it, not because of anything it does itself.
            fight("BronzeAutomaton", "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton", 15000L);
            fight("TheCollector", "com.megacrit.cardcrawl.monsters.city.TheCollector", 15000L);
            fight("AwakenedOne", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne", 15000L);
            fight("SlimeBoss", "com.megacrit.cardcrawl.monsters.exordium.SlimeBoss", 15000L);
            fight("JawWorm", "com.megacrit.cardcrawl.monsters.exordium.JawWorm", 8000L);
            done();
            return;
        }
        String monster;
        String label;
        if ("normal".equals(which)) {
            monster = "com.megacrit.cardcrawl.monsters.exordium.JawWorm";
            label = "JawWorm";
        } else if ("awakened".equals(which)) {
            monster = "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne";
            label = "AwakenedOne";
        } else if ("collector".equals(which)) {
            monster = "com.megacrit.cardcrawl.monsters.city.TheCollector";
            label = "TheCollector";
        } else if ("automaton".equals(which)) {
            monster = "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton";
            label = "BronzeAutomaton";
        } else if ("slime".equals(which)) {
            monster = "com.megacrit.cardcrawl.monsters.exordium.SlimeBoss";
            label = "SlimeBoss";
        } else {
            monster = which;
            label = which.substring(which.lastIndexOf('.') + 1);
        }
        fight(label, monster, 20000L);
        done();
    }

    /**
     * Controlled A/B of the fork cost alone: one captured fight, then the same {@link World#fork()}
     * run under four combinations of the two optimisations, in the SAME JVM, alternating rounds, so
     * JIT state, heap state and which fight it is all cancel out. Comparing ms across runs cannot do
     * this: the search visits a different number of nodes every run, so ms/solve is not a measurement
     * of the cloner at all.
     */
    public static void forkBench(String label, String monster) {
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (int i = 0; i < REAL_DECK.length; i++) {
            if (com.megacrit.cardcrawl.helpers.CardLibrary.getCard(REAL_DECK[i]) != null) {
                kept.add(REAL_DECK[i]);
            }
        }
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 501L;
        spec.maxHealth = 200;
        spec.deck = kept.toArray(new String[kept.size()]);
        spec.monsters = new String[]{monster};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World live = World.captureLive();
        boolean quiet = !"1".equals(System.getProperty("sts1solver.cost.console"));
        if (quiet) {
            sts1solver.util.ConsoleMute.push();
        }
        try {
            for (int i = 0; i < 40; i++) {
                forkSink = live.fork();
            }
            String[] names = {"all-old", "policy-new", "array-new", "field-new", "all-new"};
            boolean[] pLegacy = {true, false, true, true, false};
            boolean[] aLegacy = {true, true, false, true, false};
            boolean[] fLegacy = {true, true, true, false, false};
            long[][] samples = new long[names.length][3];
            for (int round = 0; round < 3; round++) {
                for (int v = 0; v < names.length; v++) {
                    samples[v][round] = forkRun(live, pLegacy[v], aLegacy[v], fLegacy[v]);
                }
            }
            StringBuilder line = new StringBuilder("FORK " + label + " (" + monster + "):");
            double baseUs = 0.0;
            for (int v = 0; v < names.length; v++) {
                java.util.Arrays.sort(samples[v]);
                double us = usPerFork(samples[v][samples[v].length / 2]);
                if (v == 0) {
                    baseUs = us;
                }
                line.append(' ').append(names[v]).append('=').append(round(us)).append("us");
                if (v > 0) {
                    line.append('(').append(round(baseUs / us)).append("x)");
                }
            }
            Log.info(line.toString());
            Log.info("FORK " + label + ": rawUs both-old=" + rawUs(samples[0])
                    + " policy-new=" + rawUs(samples[1])
                    + " array-new=" + rawUs(samples[2])
                    + " field-new=" + rawUs(samples[3]) + " all-new=" + rawUs(samples[4]));
        } finally {
            sts1solver.mirror.ClonePolicy.legacy = false;
            sts1solver.mirror.Cloner.arrayLegacy = false;
            sts1solver.mirror.Cloner.fieldLegacy = false;
            if (quiet) {
                sts1solver.util.ConsoleMute.pop();
            }
        }
    }

    /** Keeps the last fork reachable so the JIT cannot delete the loop as dead code. */
    private static Object forkSink;

    private static final int FORK_ITERATIONS = 120;
    private static final int FORK_WARMUP = 20;

    private static long forkRun(World live, boolean policyLegacy, boolean arrayLegacy,
            boolean fieldLegacy) {
        sts1solver.mirror.ClonePolicy.legacy = policyLegacy;
        sts1solver.mirror.Cloner.arrayLegacy = arrayLegacy;
        sts1solver.mirror.Cloner.fieldLegacy = fieldLegacy;
        World last = null;
        for (int i = 0; i < FORK_WARMUP; i++) {
            last = live.fork();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < FORK_ITERATIONS; i++) {
            last = live.fork();
        }
        long dt = System.nanoTime() - t0;
        forkSink = last;
        return dt;
    }

    private static double usPerFork(long nanos) {
        return nanos / 1000.0 / FORK_ITERATIONS;
    }

    private static String rawUs(long[] nanos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nanos.length; i++) {
            sb.append(i == 0 ? "" : " ").append(round(usPerFork(nanos[i])));
        }
        return sb.toString();
    }

    private static void fight(String label, String monster, long budgetMs) {
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (int i = 0; i < REAL_DECK.length; i++) {
            if (com.megacrit.cardcrawl.helpers.CardLibrary.getCard(REAL_DECK[i]) != null) {
                kept.add(REAL_DECK[i]);
            }
        }
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 501L;
        spec.maxHealth = 200;
        spec.deck = kept.toArray(new String[kept.size()]);
        spec.monsters = new String[]{monster};
        long calibNanos = calib();
        Solver.clearExpandFailures();
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.measureCost = true;
        Driver.resetCostCounters();
        long gcCount0 = gcCount();
        long gcMs0 = gcMs();
        final long t0 = System.currentTimeMillis();
        Ticker sampler = sampler(label, t0);
        StackSampler stacks = new StackSampler();
        stacks.start();
        // The MTS console is a Swing text component fed by the same stream the log appender writes
        // through; leaving it attached makes a long run progressively slower for reasons that have
        // nothing to do with the engine. -Dsts1solver.cost.console=1 keeps it, to show the effect.
        boolean quiet = !"1".equals(System.getProperty("sts1solver.cost.console"));
        if (quiet) {
            sts1solver.util.ConsoleMute.push();
        }
        Solver.Result r = Solver.solvePortfolio(World.captureLive(), budgetMs);
        long ms = System.currentTimeMillis() - t0;
        if (quiet) {
            sts1solver.util.ConsoleMute.pop();
        }
        stacks.stopAndReport(label);
        sampler.stop = true;
        try {
            sampler.thread.join(500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        int nodes = Math.max(1, r.nodes);
        long updateMs = Driver.updateNanos / 1000000L;
        long pumpMs = Driver.pumpNanos / 1000000L;
        Log.info("COST " + label + ": deck=" + kept.size() + " " + r.summary()
                + " nodes=" + r.nodes + " ms=" + ms
                + " msPerNode=" + round(ms * 1.0 / nodes)
                + " updatesPerNode=" + round(Driver.updates * 1.0 / nodes)
                + " failures=[" + Solver.expandFailures() + "]");
        Log.info("COST " + label + ": updates=" + Driver.updates
                + " wasted=" + Driver.wastedUpdates
                + " wastedShare=" + pct(Driver.wastedUpdates, Driver.updates) + "%"
                + " actionsSeen=" + Driver.actionsSeen
                + " presentationShare=" + pct(Driver.presentationActions, Driver.actionsSeen) + "%");
        Log.info("COST " + label + ": updateMs=" + updateMs + " pumpMs=" + pumpMs
                + " pumpShareOfUpdateTime=" + pct(pumpMs, updateMs) + "%"
                + " usPerUpdate=" + round(1000.0 * updateMs / Math.max(1L, Driver.updates))
                + " updatesPerNode=" + round(Driver.updates * 1.0 / nodes));
        Log.info("COST " + label + ": visitors=" + monsters() + " powers=" + powers());
        Log.info("COST " + label + ": cpuCalib=" + (calibNanos / 1000000L) + "ms for "
                + CALIB_ITERATIONS + " iterations consoleMute=" + sts1solver.util.ConsoleMute.status());
        Log.info("COST " + label + ": globals " + globals());
        Log.info("COST " + label + ": staticLists " + staticLists());
        Log.info("COST " + label + ": gcRuns=" + (gcCount() - gcCount0)
                + " gcMs=" + (gcMs() - gcMs0)
                + " gcShareOfWall=" + pct(gcMs() - gcMs0, ms) + "%");
        Log.info("COST " + label + ": " + actionBreakdown(updateMs));
        Log.info("COST " + label + ": " + Solver.profileLine());
    }

    /**
     * The ten action classes that own the most update time. Everything a drain update does happens
     * inside the action that is currently in flight, so this is the whole per-update cost.
     */
    private static String actionBreakdown(long updateMs) {
        java.util.List<java.util.Map.Entry<String, long[]>> all =
                new java.util.ArrayList<java.util.Map.Entry<String, long[]>>(
                        Driver.actionNanos.entrySet());
        java.util.Collections.sort(all,
                new java.util.Comparator<java.util.Map.Entry<String, long[]>>() {
                    public int compare(java.util.Map.Entry<String, long[]> a,
                            java.util.Map.Entry<String, long[]> b) {
                        return Long.compare(b.getValue()[0], a.getValue()[0]);
                    }
                });
        StringBuilder sb = new StringBuilder("actions:");
        long total = Math.max(1L, Driver.updateNanos);
        for (int i = 0; i < all.size() && i < 10; i++) {
            long[] v = all.get(i).getValue();
            sb.append(' ').append(all.get(i).getKey())
                    .append('=').append(v[0] / 1000000L).append("ms/").append(v[1]).append('x')
                    .append('(').append(pct(v[0], total)).append("%)");
        }
        return sb.toString();
    }

    /** How many monsters and powers the expensive fights carry: they are what every update walks. */
    private static String monsters() {
        try {
            int n = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.size();
            return String.valueOf(n);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String powers() {
        try {
            int n = com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.powers.size();
            java.util.List<com.megacrit.cardcrawl.monsters.AbstractMonster> ms =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < ms.size(); i++) {
                if (ms.get(i) != null) {
                    n += ((com.megacrit.cardcrawl.monsters.AbstractMonster) ms.get(i)).powers.size();
                }
            }
            return String.valueOf(n);
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * One running sampler, with its OWN stop flag. A static flag was wrong: the next fight cleared it,
     * which woke every earlier fight's sampler back up, and each of those calls System.gc() and walks
     * 64MB every three seconds -- so the later fights paid for all the earlier samplers. That was the
     * "the same fight gets slower as the audit runs" effect.
     */
    private static final class Ticker {
        volatile boolean stop;
        Thread thread;
    }

    /**
     * Prints the running per-update cost every three seconds, so a drift WITHIN one fight is visible
     * instead of being averaged away into a single number at the end.
     */
    private static Ticker sampler(final String label, final long t0) {
        final Ticker ticker = new Ticker();
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!ticker.stop) {
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (ticker.stop) {
                        return;
                    }
                    long updates = Driver.updates;
                    Log.info("COST " + label + ": t=" + ((System.currentTimeMillis() - t0) / 1000L)
                            + "s updates=" + updates
                            + " usPerUpdate=" + round(1000.0 * (Driver.updateNanos / 1000000L)
                                    / Math.max(1L, updates))
                            + " usedMb=" + usedMb() + " liveMb=" + liveMb()
                            + " memCalib=" + memCalib() + "ms"
                            + " threads=" + threadCount()
                            + " cpuPct=" + cpuPct(t0));
                }
            }
        }, "cost-sampler");
        t.setDaemon(true);
        t.start();
        ticker.thread = t;
        return ticker;
    }

    /**
     * How much CPU the whole process is burning relative to wall time. If the per-update cost rises
     * while this falls, the search is BLOCKED rather than doing more work.
     */
    private static int cpuPct(long t0) {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method cand : os.getClass().getMethods()) {
                if ("getProcessCpuTime".equals(cand.getName())) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                return -1;
            }
            long nanos = ((Long) m.invoke(os)).longValue();
            long wallMs = Math.max(1L, System.currentTimeMillis() - t0);
            return (int) (nanos / 1000000L * 100L / wallMs);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int threadCount() {
        java.lang.management.ThreadMXBean tb = java.lang.management.ManagementFactory.getThreadMXBean();
        return tb.getThreadCount();
    }

    private static final long[] MEM = new long[8 * 1024 * 1024];

    /**
     * A loop over a 64MB array: unlike the arithmetic calibration it touches memory, so it reacts
     * to memory-bandwidth contention. If this drifts along with usPerUpdate, the machine's memory
     * path is the variable; if it stays flat, the drift is engine-specific.
     */
    private static long memCalib() {
        long start = System.nanoTime();
        long x = 0L;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < MEM.length; i++) {
                x += MEM[i] + i;
            }
        }
        SINK = x;
        return (System.nanoTime() - start) / 1000000L;
    }

    private static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    /** Used memory after a full collection: what is actually still reachable. */
    private static long liveMb() {
        System.gc();
        try {
            Thread.sleep(60L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return usedMb();
    }

    /**
     * A fixed arithmetic loop, run before each fight. If this slows down too, the machine is the
     * variable (turbo, thermal throttling, other processes) and not the engine state.
     */
    private static long calib() {
        long start = System.nanoTime();
        long x = 0L;
        for (long i = 0L; i < CALIB_ITERATIONS; i++) {
            x += i * 2654435761L;
            x ^= x >>> 13;
        }
        SINK = x;
        return System.nanoTime() - start;
    }

    private static final long CALIB_ITERATIONS = 40000000L;
    private static volatile long SINK;

    /**
     * Every static collection on AbstractDungeon and CardCrawlGame, by size. A list that grows with
     * the number of fights built in this process is the accumulation being looked for.
     */
    private static String staticLists() {
        StringBuilder sb = new StringBuilder();
        appendLists(com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, sb);
        appendLists(com.megacrit.cardcrawl.core.CardCrawlGame.class, sb);
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void appendLists(Class<?> type, StringBuilder sb) {
        java.lang.reflect.Field[] fields = type.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            java.lang.reflect.Field fd = fields[i];
            if (!java.lang.reflect.Modifier.isStatic(fd.getModifiers())) {
                continue;
            }
            try {
                fd.setAccessible(true);
                Object v = fd.get(null);
                int n = -1;
                if (v instanceof java.util.Collection) {
                    n = ((java.util.Collection<?>) v).size();
                } else if (v instanceof java.util.Map) {
                    n = ((java.util.Map<?, ?>) v).size();
                }
                if (n > 0) {
                    sb.append(fd.getName()).append('=').append(n).append(' ');
                }
            } catch (Throwable ignored) {
                // a field we cannot read is not the answer
            }
        }
    }

    /**
     * The per-update cost of the SAME fight depends on how many fights ran before it in this JVM
     * (BronzeAutomaton: 59us first, 163us fifth; JawWorm: 46us first, 290us fifth). Something is
     * accumulating across fights, so name the candidate lists instead of guessing.
     */
    private static String globals() {
        try {
            return "effectList=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList.size()
                    + " effectsQueue=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.size()
                    + " topLevel=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.topLevelEffects.size()
                    + " cardQueue=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.cardQueue.size()
                    + " actions=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.actions.size()
                    + " playedThisCombat="
                    + com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.cardsPlayedThisCombat.size()
                    + " playedThisTurn="
                    + com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.cardsPlayedThisTurn.size()
                    + " hand=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand.size()
                    + " monsters=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.size()
                    + " heapMb=" + ((Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()) >> 20);
        } catch (Throwable t) {
            return "globals failed: " + t;
        }
    }

    /** Garbage collection is the standing suspect for "every action got 5x slower". */
    private static long gcCount() {
        long n = 0;
        java.util.List<java.lang.management.GarbageCollectorMXBean> beans =
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < beans.size(); i++) {
            long c = beans.get(i).getCollectionCount();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }

    private static long gcMs() {
        long n = 0;
        java.util.List<java.lang.management.GarbageCollectorMXBean> beans =
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < beans.size(); i++) {
            long c = beans.get(i).getCollectionTime();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }

    private static long pct(long part, long whole) {
        return whole == 0 ? 0 : (100L * part / whole);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void done() {
        Log.info("AUTOTEST RESULT passed=1 failed=0");
        Log.info("AUTOTEST DONE");
        try {
            java.io.File req = new java.io.File(sts1solver.SolverMod.requestPath());
            if (req.exists()) {
                req.delete();
            }
        } catch (Throwable ignored) {
            // run.ps1 removes it too
        }
    }
}
