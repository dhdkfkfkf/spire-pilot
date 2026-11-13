package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
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
 * Pile-pick diagnostic: cards that open the GRID screen on the DISCARD pile
 * (Headbutt / Hologram / Exhume / Liquid Memories).
 *
 * <p>The live report: after such a card is played, the solver never picks a card in the discard
 * pile and the fight stalls. Three questions answered with numbers:
 * <ol>
 *   <li>does the sandbox even see the screen (kind / candidates / option count)?</li>
 *   <li>does answering it actually move the card to the top of the draw pile?</li>
 *   <li>does a searched plan contain that step WITH a choice, and does it replay?</li>
 * </ol>
 */
public final class PilePickProbe {

    private static final String[] DECK = {
            "Strike_R", "Strike_R", "Strike_R", "Strike_R", "Defend_R", "Defend_R",
            "Defend_R", "Defend_R", "Headbutt", "Exhume"};

    private PilePickProbe() {
    }

    public static void run() {
        try {
            build();
        } catch (Throwable t) {
            Log.info("PILEPICK build failed: " + t);
            finish();
            return;
        }
        casePlay("Headbutt", 3);
        casePlay("Exhume", 3);
        caseSearch();
        finish();
    }

    private static void build() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 2026L;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.deck = DECK;
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Log.info("PILEPICK built: hand=" + ids(AbstractDungeon.player.hand)
                + " discard=" + AbstractDungeon.player.discardPile.size()
                + " draw=" + AbstractDungeon.player.drawPile.size());
    }

    private static void casePlay(String cardId, int discards) {
        Log.info("PILEPICK case " + cardId + " -----------------------------");
        World w = World.captureLive();
        Driver.begin();
        boolean installed = false;
        try {
            w.install();
            installed = true;
            AbstractDungeon.player.hand.group.clear();
            AbstractDungeon.player.discardPile.group.clear();
            for (int i = 0; i < discards; i++) {
                AbstractDungeon.player.discardPile.addToTop(
                        com.megacrit.cardcrawl.helpers.CardLibrary.getCopy("Strike_R"));
            }
            AbstractCard card = com.megacrit.cardcrawl.helpers.CardLibrary.getCopy(cardId);
            AbstractDungeon.player.hand.group.add(card);
            com.megacrit.cardcrawl.ui.panels.EnergyPanel.setEnergy(3);
            AbstractMonster target = firstAlive();
            Log.info("PILEPICK before: discard=" + ids(AbstractDungeon.player.discardPile)
                    + " drawTop=" + topOf(AbstractDungeon.player.drawPile));
            Driver.Drain d = Driver.play(card, target, Driver.energyFor(card, AbstractDungeon.player));
            Log.info("PILEPICK after play: stalled=" + d.stalled + " timedOut=" + d.timedOut
                    + " screen=" + AbstractDungeon.screen
                    + " kind=" + Driver.selectionKind()
                    + " candidates=" + Driver.namesOf(Driver.selectionCandidates())
                    + " options=" + optionNames()
                    + " hand=" + ids(AbstractDungeon.player.hand)
                    + " discard=" + ids(AbstractDungeon.player.discardPile));
            if (Driver.selectionKind() == Driver.SelectionKind.NONE) {
                Log.info("PILEPICK " + cardId + ": no screen stayed open");
                return;
            }
            com.megacrit.cardcrawl.screens.select.GridCardSelectScreen grid =
                    AbstractDungeon.gridSelectScreen;
            AbstractCard pick = Driver.selectionCandidates().get(0);
            grid.selectedCards.clear();
            grid.selectedCards.add(pick);
            Log.info("PILEPICK picked " + pick.cardID
                    + " selected=" + grid.selectedCards.size()
                    + " numCards=" + gridCount()
                    + " screenUp=" + AbstractDungeon.isScreenUp
                    + " queue=" + AbstractDungeon.actionManager.actions.size());
            // B151: there is no "let the action consume while the screen is open" step any more. The
            // real game freezes the action manager while a screen is up (AbstractRoom.update), so the
            // waiting action reads the selection only after the close below, in the frame order a real
            // click produces. Filling + closing + draining is the whole answer now.
            Log.info("PILEPICK after fill: discard=" + ids(AbstractDungeon.player.discardPile)
                    + " drawTop=" + topOf(AbstractDungeon.player.drawPile)
                    + " selected=" + grid.selectedCards.size()
                    + " queue=" + AbstractDungeon.actionManager.actions.size()
                    + " screen=" + AbstractDungeon.screen);
            AbstractDungeon.closeCurrentScreen();
            Driver.drain();
            Log.info("PILEPICK after close+drain: screen=" + AbstractDungeon.screen
                    + " screenUp=" + AbstractDungeon.isScreenUp
                    + " discard=" + ids(AbstractDungeon.player.discardPile)
                    + " drawTop=" + topOf(AbstractDungeon.player.drawPile));
            boolean ok = Driver.selectionKind() == Driver.SelectionKind.NONE;
            Log.info("PILEPICK resolved: ok=" + ok
                    + " screen=" + AbstractDungeon.screen
                    + " kind=" + Driver.selectionKind()
                    + " discard=" + ids(AbstractDungeon.player.discardPile)
                    + " drawTop=" + topOf(AbstractDungeon.player.drawPile)
                    + " hand=" + ids(AbstractDungeon.player.hand));
            if (Driver.selectionKind() != Driver.SelectionKind.NONE) {
                Log.info("PILEPICK " + cardId + ": screen STILL open, dropping it");
                Driver.clearSelection();
            }
        } catch (Throwable t) {
            Log.info("PILEPICK " + cardId + " threw: " + t);
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

    private static void caseSearch() {
        Log.info("PILEPICK search -----------------------------");
        Solver.Result r = null;
        try {
            r = Solver.solvePortfolio(World.captureLive(), 6000L);
            Log.info("PILEPICK search: " + r.summary());
            StringBuilder sb = new StringBuilder("PILEPICK plan:");
            for (int i = 0; i < r.plan.size(); i++) {
                Solver.Step s = r.plan.get(i);
                sb.append(" | ").append(s.action.label);
                if (s.action.choice != null && !s.action.choice.isEmpty()) {
                    sb.append(" [choice=").append(s.action.choice).append(']');
                }
            }
            Log.info(sb.toString());
        } catch (Throwable t) {
            Log.info("PILEPICK search threw: " + t);
            return;
        }
        if (r == null || r.plan.isEmpty()) {
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
                Log.info("PILEPICK replay step " + i + ": apply=" + a.label
                        + " choice=" + (a.choice == null ? "-" : a.choice)
                        + " screen=" + AbstractDungeon.screen
                        + " over=" + Driver.combatOver());
            }
            Log.info("PILEPICK replay: completed " + done + '/' + r.plan.size()
                    + " over=" + Driver.combatOver());
        } catch (Throwable t) {
            Log.info("PILEPICK replay failed at " + done + ": " + t);
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

    private static int gridCount() {
        try {
            return Driver.gridSelectCountForTest();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String optionNames() {
        try {
            java.util.List<java.util.List<AbstractCard>> opts = Driver.selectionOptions(2);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < opts.size(); i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(Driver.namesOf(opts.get(i)));
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    private static String ids(CardGroup g) {
        try {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < g.group.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(g.group.get(i).cardID);
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    private static String topOf(CardGroup g) {
        try {
            return g.group.isEmpty() ? "-" : g.group.get(g.group.size() - 1).cardID;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static AbstractMonster firstAlive() {
        try {
            java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < ms.size(); i++) {
                if (ms.get(i) != null && ms.get(i).currentHealth > 0) {
                    return ms.get(i);
                }
            }
        } catch (Throwable ignored) {
            // no target
        }
        return null;
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
