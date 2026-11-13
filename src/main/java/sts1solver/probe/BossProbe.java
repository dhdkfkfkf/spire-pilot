package sts1solver.probe;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Boss-with-split diagnostic (B16): does the search plan THROUGH the split at A20?
 *
 * <p>Reported: a big slime shows "no winning route" and the behaviour after the split does not match
 * the real game. This runs the real search on a boss-scale slime and reports whether the search ever
 * saw a split (Solver counts them), how deep the line went, and what happens when the plan is replayed.
 */
public final class BossProbe {

    private BossProbe() {
    }

    public static void run() {
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 5150L;
            spec.maxHealth = 75;
            spec.ascension = 20;
            spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Defend_R",
                    "Defend_R", "Defend_R", "Defend_R", "Bash", "Anger", "Pommel Strike",
                    "Shrug It Off", "Inflame", "Metallicize"};
            spec.relics = new String[]{"Burning Blood"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.SlimeBoss"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            World world = World.captureLive();
            long t0 = System.currentTimeMillis();
            Solver.Result r = Solver.solvePortfolio(world, 8000L);
            long ms = System.currentTimeMillis() - t0;
            Log.info("BOSSA20 result: " + r.summary() + " steps=" + r.plan.size() + " splits="
                    + r.splitStates + " turns=" + r.predictedTurns + " ms=" + ms);
            Log.info("BOSSA20 plan: " + r.planText());
            // replay the line in the real engine and watch the monster group
            World replay = World.captureLive();
            replay.install();
            sts1solver.drive.Driver.begin();
            try {
                int maxMonsters = AbstractDungeon.getMonsters().monsters.size();
                for (int i = 0; i < r.plan.size(); i++) {
                    boolean applied;
                    try {
                        Legal.apply(r.plan.get(i).action);
                        applied = true;
                    } catch (Throwable t) {
                        applied = false;
                    }
                    int now = AbstractDungeon.getMonsters().monsters.size();
                    if (now > maxMonsters) {
                        maxMonsters = now;
                        Log.info("BOSSA20 split at step " + i + ": monsters=" + now);
                    }
                    if (sts1solver.drive.Driver.combatOver()) {
                        Log.info("BOSSA20 replay ended at step " + i + " over=true applied="
                                + applied);
                        break;
                    }
                }
                Log.info("BOSSA20 replay: maxMonsters=" + maxMonsters + " over="
                        + sts1solver.drive.Driver.combatOver() + " hp="
                        + AbstractDungeon.player.currentHealth);
            } finally {
                sts1solver.drive.Driver.end();
                replay.readBack();
                replay.uninstall();
            }
            Log.info("AUTOTEST RESULT passed=1 failed=0");
            Log.info("AUTOTEST DONE");
        } catch (Throwable t) {
            Log.error("BOSSA20 failed", t);
            Log.info("AUTOTEST RESULT passed=0 failed=1");
            Log.info("AUTOTEST DONE");
        }
    }
}