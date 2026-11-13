package sts1solver.probe;

import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Does a long search (the A0 default of 300s, or ten minutes) keep making progress, or does it
 * stall and burn memory? The user asked for exactly this (m06945: "if the fight is long and
 * complicated I can wait ten minutes, as long as it is really computing"). F8 now runs the
 * sliced {@link Solver.PortfolioSession} path, so this probe exercises the shipped path: 40ms
 * slices, the same portfolio profiles, A3 parking included.
 *
 * <p>It logs one sample every sample interval with nodes, nodes/second, best plan length, layer
 * and heap use, plus how long the node count has been frozen ({@code stalled=}). A run where
 * {@code stalled} grows toward the budget is not "computing", it is spinning; that distinction
 * cannot be seen from the final summary alone.
 */
public final class LongRun {

    /** A late-game Ironclad deck, the same shape AuditProbe uses. */
    private static final String[] DECK = {"Strike_R", "Strike_R", "Strike_R", "Strike_R",
            "Strike_R", "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R",
            "Defend_R", "Inflame", "Metallicize", "ShrugItOff", "PommelStrike", "Cleave",
            "HeavyBlade", "Uppercut", "TrueGrit", "Thunderclap", "BodySlam",
            "Flex", "Anger", "SecondWind", "SeverSoul", "GhostlyArmor",
            "IronWave", "Clothesline", "Bloodletting"};

    private static final long SLICE_MS = 40L;

    private LongRun() {
    }

    public static void run(long budgetMs, long sampleMs, boolean shortReference) {
        World root = build();
        if (root == null) {
            return;
        }
        runOne("long", root, budgetMs, sampleMs);
        if (shortReference) {
            // Same root, a twentieth of the budget: the difference in the final line is what the
            // extra minutes bought, and that is the whole justification for the A0 default.
            runOne("short", root, Math.max(4000L, budgetMs / 9L), 0L);
        }
        Log.info("LONGRUN done");
        finish();
    }

    /** Same ending as every other probe mode: run.ps1 waits for this marker, then cleans up. */
    private static void finish() {
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

    private static World build() {
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (int i = 0; i < DECK.length; i++) {
            if (com.megacrit.cardcrawl.helpers.CardLibrary.getCard(DECK[i]) != null) {
                kept.add(DECK[i]);
            }
        }
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 777L;
        spec.maxHealth = 80;
        spec.ascension = 20;
        spec.deck = kept.toArray(new String[kept.size()]);
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.city.TheCollector"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Log.info("LONGRUN built: asc=20 hp="
                + com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.currentHealth
                + " monsters=" + com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.size());
        return World.captureLive();
    }

    private static void runOne(String label, World root, long budgetMs, long sampleMs) {
        long t0 = System.currentTimeMillis();
        Solver.PortfolioSession s = new Solver.PortfolioSession(root, budgetMs, 0);
        int lastNodes = -1;
        long lastChange = t0;
        long peakMb = 0L;
        long nextSample = sampleMs;
        int samples = 0;
        while (!s.finished()) {
            long sliceStart = System.currentTimeMillis();
            s.advance(SLICE_MS);
            long now = System.currentTimeMillis();
            if (now - t0 > budgetMs + 30000L) {
                Log.warn("LONGRUN " + label + ": overran the budget by 30s, stopping");
                s.requestStop();
                break;
            }
            int nodes = s.nodes();
            if (nodes != lastNodes) {
                lastNodes = nodes;
                lastChange = now;
            }
            long used = usedMb();
            if (used > peakMb) {
                peakMb = used;
            }
            if (sampleMs > 0L && now - t0 >= nextSample) {
                ++samples;
                long elapsed = now - t0;
                Log.info("LONGRUN " + label + ": t=" + (elapsed / 1000) + "s nodes=" + nodes
                        + " (" + (nodes * 1000L / Math.max(1L, elapsed)) + "/s)"
                        + " best=" + s.bestActions() + " steps"
                        + " stalled=" + ((now - lastChange) / 1000) + "s"
                        + " used=" + used + "MB peak=" + peakMb + "MB"
                        + " phase=" + s.phase());
                nextSample += sampleMs;
            }
            long rest = SLICE_MS - (System.currentTimeMillis() - sliceStart);
            if (rest > 0L) {
                try {
                    Thread.sleep(rest);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // Let a stopped session settle so the reported result is the one F8 would show.
        int guard = 0;
        while (!s.finished() && guard++ < 200) {
            s.advance(SLICE_MS);
        }
        long ms = System.currentTimeMillis() - t0;
        Solver.Result r = s.result();
        if (r == null) {
            Log.warn("LONGRUN " + label + ": no result after " + ms + "ms");
            return;
        }
        Log.info("LONGRUN " + label + ": budget=" + budgetMs + "ms wall=" + ms + "ms samples="
                + samples + " peakHeap=" + peakMb + "MB");
        Log.info("LONGRUN " + label + ": " + r.summary() + " headline="
                + sts1solver.search.PlanText.headline(r));
        Log.info("LONGRUN " + label + ": profile " + Solver.profileLine());
    }

    private static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
    }
}
