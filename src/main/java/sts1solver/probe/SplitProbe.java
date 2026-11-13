package sts1solver.probe;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Does one search plan THROUGH a Slime Boss split (STS2 style), or only up to it (mode "split")?
 *
 * <p>This is a question about the SEARCH, not about the live replan: if the plan already covers the
 * turns after the split, the player never sees a replan at all. The probe answers it by solving a
 * fight whose boss is at half HP, then replaying the plan in a sandbox and logging the monster group
 * at every step.
 */
public final class SplitProbe {

    private SplitProbe() {
    }

    public static void run() {
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 71L;
            spec.maxHealth = 200;
            spec.deck = new String[]{"Bash", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                    "Defend_R", "Defend_R", "Defend_R", "Strike_R", "Strike_R"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.SlimeBoss"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            AbstractMonster boss = AbstractDungeon.getMonsters().monsters.get(0);
            boss.currentHealth = 70; // at the split threshold
            Log.info("SPLIT start: monsters=" + group() + " bossHp=" + boss.currentHealth
                    + " digest=" + Digest.ofCurrent());

            World root = World.captureLive();
            Solver.Result r = Solver.solvePortfolio(root, 8000L);
            Log.info("SPLIT plan: " + r.summary() + " steps=" + r.plan.size()
                    + " turns=" + r.predictedTurns + " layers=" + r.layersRun);
            for (int i = 0; i < r.plan.size(); i++) {
                Solver.Step s = r.plan.get(i);
                Log.info("SPLIT step " + (i + 1) + ": turn=" + s.turn + " " + s.action.label
                        + " | monsters=" + String.join(",", s.monsterNames));
            }

            // Replay the plan in a sandbox and watch the monster group.
            World replay = World.captureLive();
            boolean installed = false;
            try {
                replay.install();
                installed = true;
                Driver.begin();
                boolean splitSeen = false;
                int lastMonsters = AbstractDungeon.getMonsters().monsters.size();
                for (int i = 0; i < r.plan.size(); i++) {
                    Action a = r.plan.get(i).action;
                    try {
                        Legal.apply(a);
                    } catch (Throwable t) {
                        Log.info("SPLIT replay stopped at step " + (i + 1) + ": " + t);
                        break;
                    }
                    int now = AbstractDungeon.getMonsters().monsters.size();
                    if (now != lastMonsters) {
                        splitSeen = true;
                        Log.info("SPLIT replay: monster group " + lastMonsters + " -> " + now
                                + " at step " + (i + 1) + " (turn " + r.plan.get(i).turn + ")");
                    }
                    lastMonsters = now;
                    if (Driver.combatOver()) {
                        Log.info("SPLIT replay: fight over at step " + (i + 1)
                                + " (turn " + r.plan.get(i).turn + ") monsters=" + group());
                        break;
                    }
                }
                Driver.end();
                Log.info("SPLIT replay: splitSeen=" + splitSeen
                        + " over=" + Driver.combatOver()
                        + " monsters=" + group()
                        + " turn=" + com.megacrit.cardcrawl.actions.GameActionManager.turn
                        + " hp=" + AbstractDungeon.player.currentHealth);
            } finally {
                if (installed) {
                    try {
                        replay.readBack();
                        replay.uninstall();
                    } catch (Throwable ignored) {
                        // isolation gates cover this
                    }
                }
            }
        } catch (Throwable t) {
            Log.error("SPLIT probe failed", t);
        }
        Log.info("SPLIT DONE");
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

    private static String group() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
            AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(m == null ? "null" : m.name + " " + m.currentHealth + "/" + m.maxHealth
                    + (m.halfDead ? "(half)" : ""));
        }
        return sb.toString();
    }
}
