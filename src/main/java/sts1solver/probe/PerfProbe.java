package sts1solver.probe;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Search-performance probe (mode "perf").
 *
 * <p>Runs a small deck and a 30-card deck at a fixed budget and prints nodes plus the phase profile,
 * so a performance change can be judged on measurement instead of on wall-clock noise.
 */
public final class PerfProbe {

    private PerfProbe() {
    }

    public static void run() {
        try {
            measure("small", new String[]{
                    "Strike_R", "Strike_R", "Strike_R", "Defend_R", "Defend_R"}, 5, 3000L);
            measure("thirty", thirtyCardDeck(), 30, 3000L);
            measureMonsters("three", thirtyCardDeck(), 30, 3000L, new String[]{
                    "com.megacrit.cardcrawl.monsters.exordium.JawWorm",
                    "com.megacrit.cardcrawl.monsters.exordium.Cultist",
                    "com.megacrit.cardcrawl.monsters.exordium.LouseNormal"});
            measureMonsters("boss", thirtyCardDeck(), 30, 8000L, new String[]{
                    "com.megacrit.cardcrawl.monsters.exordium.SlimeBoss"});
        } catch (Throwable t) {
            Log.error("PERF probe failed", t);
        }
        Log.info("PERF DONE");
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

    private static void measureMonsters(String name, String[] deck, int deckSize, long budget,
                                       String[] monsters) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 101L;
        spec.maxHealth = 200;
        spec.deck = deck;
        spec.monsters = monsters;
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World root = World.captureLive();
        long started = System.currentTimeMillis();
        Solver.Result r = Solver.solve(root, 32, 96, budget);
        long elapsed = System.currentTimeMillis() - started;
        Log.info("PERF " + name + " deck=" + deckSize + " monsters=" + monsters.length
                + " ms=" + elapsed + " " + r.summary());
        Log.info("PERF " + name + " " + Solver.profileLine());
    }

    private static String[] thirtyCardDeck() {
        String[] deck = new String[30];
        for (int i = 0; i < deck.length; i++) {
            if (i % 5 == 0) {
                deck[i] = "Bash";
            } else if (i % 3 == 0) {
                deck[i] = "Defend_R";
            } else {
                deck[i] = "Strike_R";
            }
        }
        return deck;
    }

    private static void measure(String name, String[] deck, int deckSize, long budget) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 101L;
        spec.maxHealth = 80;
        spec.deck = deck;
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World root = World.captureLive();
        long started = System.currentTimeMillis();
        Solver.Result r = Solver.solve(root, 32, 48, budget);
        long elapsed = System.currentTimeMillis() - started;
        Log.info("PERF " + name + " deck=" + deckSize + " ms=" + elapsed + " " + r.summary());
        Log.info("PERF " + name + " " + Solver.profileLine());
    }
}