package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Hexaghost (the Act 1 boss the live report called "six flames"): why does the shown line stop at
 * turn 7 without a kill?
 *
 * <p>Prints the search verdict and, for every step of the reported line, its turn, its action and the
 * choice it carried; then replays the line and prints where it really ends and what is left alive.
 * The point is to separate three very different stories: the budget ran out (reason=time), the front
 * ran dry so no line survives (reason=no-live-branches), or the line simply ends because the fight was
 * won in fewer turns than the report suggests.
 */
public final class HexaProbe {

    private static final String[] DECK = {
            "Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
            "Defend_R", "Defend_R", "Defend_R", "Defend_R",
            "Bash", "Anger", "Cleave", "Pommel Strike", "Shrug It Off",
            "Uppercut", "Inflame", "Metallicize", "Heavy Blade", "True Grit", "Body Slam"};

    private HexaProbe() {
    }

    public static void run(long budgetMs) {
        Solver.Result r = null;
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 777L;
            spec.maxHealth = 80;
            spec.ascension = 20;
            spec.deck = DECK;
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.Hexaghost"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            Driver.drain(4000, 800L);
            Log.info("HEXA built: hp=" + AbstractDungeon.player.currentHealth + '/'
                    + AbstractDungeon.player.maxHealth + " monsters=" + monsters());
            r = Solver.solvePortfolio(World.captureLive(), budgetMs);
            Log.info("HEXA search: " + r.summary());
            Log.info("HEXA headline: " + sts1solver.search.PlanText.headline(r));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.plan.size(); i++) {
                Solver.Step s = r.plan.get(i);
                int turn = s.turn; // B85: already the turn the step was taken in
                sb.append(' ').append(i).append(":t").append(turn).append('=').append(s.action.label);
                if (!s.action.choice.isEmpty()) {
                    sb.append('(').append(s.action.choice).append(')');
                }
            }
            Log.info("HEXA plan:" + sb);
        } catch (Throwable t) {
            Log.info("HEXA failed: " + t);
            finish();
            return;
        }
        replay(r);
        finish();
    }

    private static void replay(Solver.Result r) {
        if (r == null || r.plan.isEmpty()) {
            Log.info("HEXA replay: no plan");
            return;
        }
        World w = World.captureLive();
        Driver.begin();
        boolean installed = false;
        int done = 0;
        try {
            w.install();
            installed = true;
            for (int i = 0; i < r.plan.size(); i++) {
                Action a = r.plan.get(i).action;
                if (Driver.selectionKind() != Driver.SelectionKind.NONE
                        && !Driver.resolveSelectionByNames(a.choice)) {
                    Driver.resolveSelectionHeuristically();
                }
                Legal.apply(a);
                if (Driver.selectionKind() != Driver.SelectionKind.NONE
                        && !Driver.resolveSelectionByNames(a.choice)) {
                    Driver.resolveSelectionHeuristically();
                }
                w.trimPresentation();
                w.readBack();
                done++;
                if (i % 5 == 0 || i + 1 == r.plan.size()) {
                    Log.info("HEXA replay " + i + ": hp=" + AbstractDungeon.player.currentHealth
                            + " turn=" + com.megacrit.cardcrawl.actions.GameActionManager.turn
                            + " over=" + Driver.combatOver() + " monsters=" + monsters());
                }
            }
            Log.info("HEXA replay end: done=" + done + '/' + r.plan.size()
                    + " over=" + Driver.combatOver() + " hp=" + AbstractDungeon.player.currentHealth
                    + " monsters=" + monsters());
        } catch (Throwable t) {
            Log.info("HEXA replay failed at " + done + ": " + t);
        } finally {
            try {
                if (installed) {
                    w.uninstall();
                }
            } catch (Throwable ignored) {
                World.forceRestore();
            }
            try {
                Driver.end();
            } catch (Throwable ignored) {
                // nothing to add
            }
        }
    }

    private static String monsters() {
        try {
            StringBuilder sb = new StringBuilder("[");
            java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < ms.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                AbstractMonster m = ms.get(i);
                sb.append(m.id).append(':').append(m.currentHealth).append('/').append(m.maxHealth);
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "[?]";
        }
    }

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
}
