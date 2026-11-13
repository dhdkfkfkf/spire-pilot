package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Focused probe for card-choice screens (mode "choice").
 *
 * <p>Iterating on the confirm path through the full 118-check suite costs over a minute per attempt;
 * this runs the same scenario in a few seconds and prints what the screen looked like at each step.
 */
public final class SelectionProbe {

    private SelectionProbe() {
    }

    public static void run() {
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 11L;
            spec.maxHealth = 80;
            spec.deck = new String[]{"Prepared", "Strike_R", "Strike_R", "Strike_R", "Strike_R"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            Log.info("CHOICE base: " + Digest.ofCurrent());

            AbstractCard prepared = null;
            for (int i = 0; i < AbstractDungeon.player.hand.group.size(); i++) {
                AbstractCard c = AbstractDungeon.player.hand.group.get(i);
                if (c != null && "Prepared".equals(c.cardID)) {
                    prepared = c;
                }
            }
            if (prepared == null) {
                Log.info("CHOICE no Prepared in hand: "
                        + cardNames(AbstractDungeon.player.hand.group));
                return;
            }
            Driver.begin();
            try {
                // Mirror the gate's sequence: install a fork, then apply through Legal.
                sts1solver.mirror.World fork = sts1solver.mirror.World.captureLive();
                boolean installed = false;
                try {
                    fork.install();
                    installed = true;
                    sts1solver.search.Action act = sts1solver.search.Action.card(
                            AbstractDungeon.player.hand.group.indexOf(prepared), -1, 0,
                            "Prepared", "Prepared", "");
                    Driver.Drain dr = sts1solver.search.Legal.apply(act);
                    Log.info("GATE drain: awaiting=" + dr.awaitingSelection + " stalled=" + dr.stalled
                            + " timedOut=" + dr.timedOut + " steps=" + dr.steps

                            + " hand=" + AbstractDungeon.player.hand.size()
                            + " actions=" + AbstractDungeon.actionManager.actions.size());
                    Log.info("GATE kind=" + Driver.selectionKind()
                            + " awaiting=" + Driver.isAwaitingSelection()
                            + " screen=" + AbstractDungeon.screen
                            + " candidates=" + cardNames(Driver.selectionCandidates())
                            + " options=" + Driver.selectionOptions(2).size()
                            + " actions=" + AbstractDungeon.actionManager.actions.size());
                    List<AbstractCard> opts = Driver.selectionCandidates();
                    List<AbstractCard> choice = new ArrayList<AbstractCard>();
                    if (!opts.isEmpty()) {
                        choice.add(opts.get(0));
                    }
                    Log.info("GATE resolve=" + Driver.resolveSelection(choice));
                } finally {
                    if (installed) {
                        try {
                            fork.readBack();
                            fork.uninstall();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                Log.info("CHOICE hand before: " + AbstractDungeon.player.hand.size()
                        + " discard=" + AbstractDungeon.player.discardPile.size());
                Driver.play(prepared, null, 0);
                Log.info("CHOICE after play: kind=" + Driver.selectionKind()
                        + " awaiting=" + Driver.isAwaitingSelection()
                        + " hand=" + AbstractDungeon.player.hand.size()
                        + " discard=" + AbstractDungeon.player.discardPile.size()
                        + " candidates=" + cardNames(Driver.selectionCandidates()));
                Log.info("CHOICE queue after play: actions="
                        + AbstractDungeon.actionManager.actions.size()
                        + " cardQueue=" + AbstractDungeon.actionManager.cardQueue.size()
                        + " screen=" + AbstractDungeon.screen
                        + " selected=" + AbstractDungeon.handCardSelectScreen.selectedCards.size()
                        + " numToSelect=" + AbstractDungeon.handCardSelectScreen.numCardsToSelect
                        + " wereCardsRetrieved=" + AbstractDungeon.handCardSelectScreen.wereCardsRetrieved);
                Log.info("CHOICE queued actions: " + actionNames());
                List<AbstractCard> candidates = Driver.selectionCandidates();
                List<AbstractCard> chosen = new ArrayList<AbstractCard>();
                if (!candidates.isEmpty()) {
                    chosen.add(candidates.get(0));
                }
                boolean closed = Driver.resolveSelection(chosen);
                Log.info("CHOICE resolved=" + closed + " kind=" + Driver.selectionKind()
                        + " hand=" + AbstractDungeon.player.hand.size()
                        + " discard=" + AbstractDungeon.player.discardPile.size());
                Log.info("CHOICE queue after drain: actions="
                        + AbstractDungeon.actionManager.actions.size()
                        + " selected=" + AbstractDungeon.handCardSelectScreen.selectedCards.size()
                        + " wereCardsRetrieved=" + AbstractDungeon.handCardSelectScreen.wereCardsRetrieved);
                Log.info("CHOICE after drain: hand=" + AbstractDungeon.player.hand.size()
                        + " discard=" + AbstractDungeon.player.discardPile.size()
                        + " discardIDs=" + cardNames(AbstractDungeon.player.discardPile.group));
                Driver.endTurn();
                Log.info("CHOICE after endTurn: t=" + com.megacrit.cardcrawl.actions.GameActionManager.turn
                        + " hand=" + AbstractDungeon.player.hand.size()
                        + " discard=" + AbstractDungeon.player.discardPile.size()
                        + " over=" + Driver.combatOver());
                Log.info("CHOICE result: " + Digest.ofCurrent());
            } finally {
                Driver.end();
            }
        } catch (Throwable t) {
            Log.error("CHOICE probe failed", t);
        }
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

    private static String actionNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.actionManager.actions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(AbstractDungeon.actionManager.actions.get(i).getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String cardNames(List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cards.get(i) == null ? "null" : cards.get(i).cardID);
        }
        return sb.toString();
    }
}