/*
 * A test double for a hand picker whose answer is only ever READ (B163).
 *
 * <p>It opens an any-number hand pick (the shape GamblingChipAction uses: open("丢弃", 99, true,
 * true)) and records what the selection held when the action retrieved it, without moving any card.
 * That is what lets a gate assert BOTH halves of a click script: which cards were picked, and what
 * order the hand was left in (the whole point of a reorder answer is the order, not the picks).
 * Never part of the search -- only a gate queues it.
 */
package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

public final class ReorderPickProbeAction
extends AbstractGameAction {
    /** What the picker was holding when the action read it, in selection order ("" = nothing). */
    public static String picked = "";
    /** How many screens this double opened (a gate asserts the answer really reached one). */
    public static int opened;

    private final AbstractPlayer p;
    private boolean isOpen;

    public ReorderPickProbeAction(AbstractPlayer p) {
        this.p = p;
        this.duration = this.startDuration = Settings.ACTION_DUR_FAST;
        this.actionType = AbstractGameAction.ActionType.CARD_MANIPULATION;
    }

    public static void resetForTest() {
        picked = "";
        opened = 0;
    }

    @Override
    public void update() {
        if (AbstractDungeon.getCurrRoom() == null
                || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT) {
            this.isDone = true;
            return;
        }
        if (!this.isOpen) {
            this.isOpen = true;
            if (this.p.hand.isEmpty()) {
                this.isDone = true;
                return;
            }
            ++opened;
            AbstractDungeon.handCardSelectScreen.open("\u6253\u51fa", 99, true, true);
            this.tickDuration();
            return;
        }
        if (!AbstractDungeon.handCardSelectScreen.wereCardsRetrieved) {
            StringBuilder sb = new StringBuilder();
            for (AbstractCard card : AbstractDungeon.handCardSelectScreen.selectedCards.group) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(card.cardID);
            }
            picked = sb.toString();
            AbstractDungeon.handCardSelectScreen.wereCardsRetrieved = true;
        }
        this.tickDuration();
        this.isDone = true;
    }
}
