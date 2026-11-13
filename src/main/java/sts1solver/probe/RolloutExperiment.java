package sts1solver.probe;

import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * A1 experiment (docs/SEARCH-OPTIONS.md §3.5): does one-ply rollout re-ranking pay for itself?
 *
 * <p>The same synthetic fights are solved twice with the SAME budget, once with the rollout
 * re-ranking off and once on, alternating per fight so JIT and heap state cancel out. The numbers
 * that decide the question are: nodes reached, wall time, and whether the on run keeps (or improves)
 * the win and the HP cost. A1 only makes the beam order better, so the honest bar is "same result,
 * fewer nodes or fewer seconds"; if it cannot clear that, it stays off.
 */
public final class RolloutExperiment {

    private static final String[] REAL_DECK = {"Strike_R", "Strike_R", "Strike_R", "Strike_R",
            "Strike_R", "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R",
            "Inflame", "Metallicize", "ShrugItOff", "PommelStrike", "Cleave",
            "HeavyBlade", "Uppercut", "TrueGrit", "Thunderclap", "BodySlam",
            "Flex", "Anger", "SecondWind", "SeverSoul", "GhostlyArmor",
            "IronWave", "Clothesline", "Bloodletting"};

    private RolloutExperiment() {
    }

    public static void run() {
        fight("JawWorm", "com.megacrit.cardcrawl.monsters.exordium.JawWorm", 8000L);
        fight("SlimeBoss", "com.megacrit.cardcrawl.monsters.exordium.SlimeBoss", 15000L);
        fight("TheCollector", "com.megacrit.cardcrawl.monsters.city.TheCollector", 20000L);
        done();
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
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World live = World.captureLive();
        boolean quiet = !"1".equals(System.getProperty("sts1solver.cost.console"));
        if (quiet) {
            sts1solver.util.ConsoleMute.push();
        }
        try {
            for (int round = 0; round < 2; ++round) {
                boolean on = round == 1;
                Solver.setRolloutEnabled(on);
                Solver.resetRolloutStats();
                long t0 = System.currentTimeMillis();
                Solver.Result r = Solver.solvePortfolio(live, budgetMs);
                long ms = System.currentTimeMillis() - t0;
                int nodes = Math.max(1, r.nodes);
                Log.info("ROLLOUT " + label + " rollout=" + (on ? "on" : "off")
                        + " win=" + r.win
                        + " hpLoss=" + r.hpLoss
                        + " potions=" + r.potionsUsed
                        + " nodes=" + r.nodes + "/" + r.totalNodes
                        + " expansions=" + r.expansions + "/" + r.totalExpansions
                        + " ms=" + ms
                        + " msPerNode=" + round(ms * 1.0 / nodes)
                        + " proven=" + r.provenOptimal
                        + " reason=" + r.reason
                        + " probes=" + Solver.rolloutCalls()
                        + " probeMs=" + Solver.rolloutMs());
            }
        }
        finally {
            Solver.setRolloutEnabled(false);
            if (quiet) {
                sts1solver.util.ConsoleMute.pop();
            }
        }
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
        }
        catch (Throwable ignored) {
        }
    }
}
