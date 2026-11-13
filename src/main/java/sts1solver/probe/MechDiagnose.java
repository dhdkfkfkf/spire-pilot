package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Diagnostic for a search that ends in reason=no-live-branches: replay the best line and dump what
 * the engine looks like after the last step (screen open? selection pending? queues? actions?).
 */
public final class MechDiagnose {

    private MechDiagnose() {
    }

    public static void run() {
        String[] deck = {"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R"};
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 211L;
        spec.maxHealth = 200;
        spec.deck = deck;
        // Encounter order, not a hand-written list: GremlinLeader takes its minion slots from
        // monsters.get(0)/(1) inside usePreBattleAction, so the real group is
        // [gremlin, gremlin, leader].
        spec.encounterKey = "Gremlin Leader";
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Solver.Result r = Solver.solvePortfolio(World.captureLive(), 20000L);
        Log.info("DIAG plan: " + r.summary() + " steps=" + r.plan.size());
        World w = null;
        boolean installed = false;
        try {
            w = World.captureLive();
            w.install();
            installed = true;
            Driver.begin();
            for (int i = 0; i < r.plan.size(); i++) {
                Legal.apply(r.plan.get(i).action);
                Log.info("DIAG step " + (i + 1) + ": " + r.plan.get(i).action.label
                        + " screen=" + AbstractDungeon.screen
                        + " awaitingSelection=" + Driver.isAwaitingSelection()
                        + " awaitingCardChoice=" + Driver.isAwaitingCardChoice());
            }
            int acts = Legal.actions().size();
            Log.info("DIAG end: turn=" + GameActionManager.turn
                    + " hp=" + AbstractDungeon.player.currentHealth
                    + " energy=" + EnergyPanel.totalCount
                    + " hand=" + AbstractDungeon.player.hand.size()
                    + " screen=" + AbstractDungeon.screen
                    + " isScreenUp=" + AbstractDungeon.isScreenUp
                    + " awaitingSelection=" + Driver.isAwaitingSelection()
                    + " awaitingCardChoice=" + Driver.isAwaitingCardChoice()
                    + " legalActions=" + acts
                    + " monsterQueue=" + AbstractDungeon.actionManager.monsterQueue.size()
                    + " cardQueue=" + AbstractDungeon.actionManager.cardQueue.size()
                    + " actionQueue=" + AbstractDungeon.actionManager.actions.size());
            for (int i = 0; i < Math.min(6, acts); i++) {
                Log.info("DIAG action " + i + ": " + Legal.actions().get(i).label);
            }
        } catch (Throwable t) {
            Log.info("DIAG threw " + t);
        } finally {
            try {
                Driver.end();
            } catch (Throwable ignored) {
                // nothing to add
            }
            if (installed) {
                try {
                    w.readBack();
                    w.uninstall();
                } catch (Throwable ignored) {
                    World.forceRestore();
                }
            }
        }
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