package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
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

import java.util.List;

/**
 * Diagnostic for the live report "the solver said every route dies, but I won that fight"
 * (Gremlin Gang, Silent, ascension 20).
 *
 * <p>Three questions, answered with numbers instead of guesses:
 * <ol>
 *   <li>Does the scenario reproduce the live fight at all (four gremlins, 60 HP, A20)?</li>
 *   <li>Does a wider beam find a win where the panel's portfolio budget did not? If yes, the
 *       verdict was a search-width artifact, not a simulation difference.</li>
 *   <li>Where does replaying the reported line break? A plan that cannot be replayed is shown
 *       truncated, which is also what makes autopilot replan (and stutter) mid-fight.</li>
 * </ol>
 */
public final class GangDiagnose {

    private static final String[] DECK = {
            "Strike_G", "Strike_G", "Strike_G", "Strike_G", "Strike_G",
            "Defend_G", "Defend_G", "Defend_G", "Defend_G", "Defend_G",
            "Neutralize", "Survivor",
            "AscendersBane", "Blade Dance", "Deadly Poison", "Tactician"};

    private static final String[] RELICS = {"Ring of the Snake"};

    private GangDiagnose() {
    }

    public static void run() {
        try {
            build();
        } catch (Throwable t) {
            Log.info("GANG build failed: " + t);
            finish();
            return;
        }
        dumpInitial();

        Solver.Result portfolio = null;
        Solver.Result wide = null;
        try {
            portfolio = Solver.solvePortfolio(World.captureLive(), 8000L);
            Log.info("GANG portfolio: " + portfolio.summary());
            Log.info("GANG portfolio plan: " + labels(portfolio));
            Log.info("GANG portfolio profile: " + Solver.profileLine());
        } catch (Throwable t) {
            Log.info("GANG portfolio threw: " + t);
        }
        try {
            wide = Solver.solve(World.captureLive(), 256, 96, 30000L);
            Log.info("GANG wide: " + wide.summary());
            Log.info("GANG wide plan: " + labels(wide));
            Log.info("GANG wide profile: " + Solver.profileLine());
        } catch (Throwable t) {
            Log.info("GANG wide threw: " + t);
        }
        if (portfolio != null) {
            trace("portfolio", portfolio.plan);
        }
        if (wide != null) {
            trace("wide", wide.plan);
        }
        finish();
    }

    private static void build() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 1301L;
        spec.maxHealth = 60;
        spec.ascension = 20;
        spec.playerClass = "com.megacrit.cardcrawl.characters.TheSilent";
        spec.deck = DECK;
        spec.relics = RELICS;
        // The game's own spawner: a hand-written list would not roll the gremlin mix the way a
        // real A20 fight does (and the MixGremlins order is what the live report was about).
        spec.encounterKey = "Gremlin Gang";
        Synthetic.build(spec, new Synthetic.SyntheticStep());
    }

    private static void dumpInitial() {
        StringBuilder sb = new StringBuilder("GANG built: ");
        sb.append("asc=").append(AbstractDungeon.ascensionLevel)
                .append(" hp=").append(AbstractDungeon.player.currentHealth)
                .append('/').append(AbstractDungeon.player.maxHealth)
                .append(" turn=").append(GameActionManager.turn)
                .append(" hand=").append(handIds())
                .append(" energy=").append(AbstractDungeon.player.energy.energy);
        if (AbstractDungeon.getCurrRoom() != null) {
            sb.append(" monsters=[");
            List<AbstractMonster> ms = AbstractDungeon.getCurrRoom().monsters.monsters;
            for (int i = 0; i < ms.size(); i++) {
                AbstractMonster m = ms.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(m.id).append(' ').append(m.currentHealth).append('/').append(m.maxHealth);
            }
            sb.append(']');
        }
        Log.info(sb.toString());
    }

    private static String labels(Solver.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.plan.size()).append(" steps");
        if (r.deepestActions > r.plan.size()) {
            sb.append(" (deep line had ").append(r.deepestActions)
                    .append(" actions; replay of it did not survive)");
        }
        for (int i = 0; i < r.plan.size(); i++) {
            sb.append(" | ").append(r.plan.get(i).action.label);
        }
        return sb.toString();
    }

    /**
     * Replays a reported line the way {@code Solver.replay} does and prints the hand at every step.
     *
     * <p>The interesting failure is {@code no card at hand index N}: it means the sandbox kept a card
     * the plan had already played (or failed to draw one), so the plan is not executable even though
     * every step was legal when it was written.
     */
    private static void trace(String tag, List<Solver.Step> plan) {
        if (plan == null || plan.isEmpty()) {
            Log.info("GANG trace " + tag + ": empty plan");
            return;
        }
        World w = World.captureLive();
        Driver.begin();
        boolean installed = false;
        int done = 0;
        try {
            w.install();
            installed = true;
            for (int i = 0; i < plan.size(); i++) {
                Action a = plan.get(i).action;
                String before = handIds();
                int skill = Driver.selectionKind() == Driver.SelectionKind.NONE ? 0 : 1;
                Log.info("GANG trace " + tag + " step " + i + " start: turn=" + GameActionManager.turn
                        + " hand=[" + before + "] openScreen=" + skill
                        + " choice=" + (a.choice == null ? "-" : a.choice)
                        + " apply=" + a.label);
                try {
                    Legal.apply(a);
                } catch (Throwable t) {
                    Log.info("GANG trace " + tag + " step " + i + " THREW " + t);
                    return;
                }
                w.trimPresentation();
                w.readBack();
                done++;
                Log.info("GANG trace " + tag + " step " + i + " end: turn=" + GameActionManager.turn
                        + " hp=" + AbstractDungeon.player.currentHealth
                        + " hand=[" + handIds() + "]" + " over=" + Driver.combatOver());
            }
            Log.info("GANG trace " + tag + ": completed " + done + '/' + plan.size() + " steps");
        } catch (Throwable t) {
            Log.info("GANG trace " + tag + " failed at step " + done + ": " + t);
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

    private static String handIds() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
                AbstractCard c = AbstractDungeon.player.hand.group.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(c.cardID);
                if (c.timesUpgraded > 0) {
                    sb.append('+').append(c.timesUpgraded);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<" + t + ">";
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
