/*
 * A test double for "a queued action waits on a pick, and the work behind that pick is longer than
 * one card play's drain budget".
 *
 * The live bug (B153, user m14038): the answer path closed the pick and drained with the 600 steps /
 * 250 ms a card play gets, then ignored the result. When the pick belonged to an end-of-turn block
 * the queue behind it was a whole monster turn plus the next turn's start, so the step was stored
 * mid-turn -- the plan read turn 3->3 / HP 32 where the live fight went to turn 6 / HP 51.
 *
 * This action opens a pick and, once it is answered, keeps the queue busy for TAIL_TICKS updates:
 * more than one drain budget, still far less than a real end turn. It is never part of the search --
 * only the S207 gate queues it.
 */
package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

public final class LongAnswerProbeAction
extends AbstractGameAction {
    /** Updates the tail holds after the answer: two card-play drain budgets (Driver.drain() = 600). */
    public static final int TAIL_TICKS = 1200;

    /** Updates of the tail that really ran in the current gate (the gate resets it). */
    public static int tailTicks;

    private boolean opened;
    private int ticks;

    public LongAnswerProbeAction() {
        this.duration = this.startDuration = Settings.ACTION_DUR_FAST;
        this.actionType = AbstractGameAction.ActionType.CARD_MANIPULATION;
    }

    @Override
    public void update() {
        if (AbstractDungeon.getCurrRoom() == null
                || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT) {
            this.isDone = true;
            return;
        }
        if (!this.opened) {
            this.opened = true;
            AbstractDungeon.handCardSelectScreen.open("\u6253\u51fa", 1, false, false);
            return;
        }
        if (!AbstractDungeon.handCardSelectScreen.wereCardsRetrieved) {
            if (AbstractDungeon.handCardSelectScreen.selectedCards.group.isEmpty()) {
                return;
            }
            // Exactly what the waiting action does with the answer: take it, stop waiting.
            AbstractDungeon.handCardSelectScreen.selectedCards.group.clear();
            AbstractDungeon.handCardSelectScreen.wereCardsRetrieved = true;
        }
        // No tickDuration() here: it would end the action on its own 0.5 s duration long before the
        // tail is over. The action is done only when the tail is.
        //
        // The block matters: Driver.drain() cuts a queue whose digest stops changing after ~48 frames
        // (it reads as a frozen engine), and a tail that only ticks a counter changes nothing the
        // digest can see. One block per tick makes the queue visibly busy, like a monster turn is.
        ++this.ticks;
        ++tailTicks;
        AbstractDungeon.player.currentBlock += 1;
        if (this.ticks >= TAIL_TICKS) {
            this.isDone = true;
        }
    }
}
