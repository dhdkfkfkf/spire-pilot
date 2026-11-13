/*
 * A test double for a mod action that asks the player TWICE before it does anything.
 *
 * CaiNongMod's HeadChase plays ExchangeCardsOfTwoPilesAction, which opens a grid pick on the
 * discard pile, then one on the draw pile, and only swaps the two cards after the second answer
 * (build/Exchange.java:41-95). The core gates do not load mods, so B86 is pinned with this copy of
 * the same shape: 0.1s window, two screens, one exchange at the end. It is never part of the
 * search -- only the probe adds it to the action manager.
 */
package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

public final class TwoScreenProbeAction
extends AbstractGameAction {
    private final AbstractPlayer p;
    private int chooseTimes;
    private AbstractCard first;
    private AbstractCard second;

    public TwoScreenProbeAction(AbstractPlayer p) {
        this.p = p;
        this.duration = Settings.ACTION_DUR_FASTER;
        this.actionType = ActionType.CARD_MANIPULATION;
        this.chooseTimes = 0;
    }

    @Override
    public void update() {
        if (AbstractDungeon.getCurrRoom() == null
                || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT) {
            this.isDone = true;
            return;
        }
        // Same first-frame switch the mod action uses: ask, count, tick, come back.
        if (this.duration == Settings.ACTION_DUR_FASTER) {
            switch (this.chooseTimes) {
                case 0: {
                    AbstractDungeon.gridSelectScreen.open(this.p.discardPile, 1, "pick one", false, false, false, false);
                    ++this.chooseTimes;
                    this.tickDuration();
                    return;
                }
                case 1: {
                    AbstractDungeon.gridSelectScreen.open(this.p.drawPile, 1, "pick one", false, false, false, false);
                    ++this.chooseTimes;
                    this.tickDuration();
                    return;
                }
                default: {
                    return;
                }
            }
        }
        if (!AbstractDungeon.gridSelectScreen.selectedCards.isEmpty()) {
            AbstractCard chosen = AbstractDungeon.gridSelectScreen.selectedCards.get(0);
            if (this.chooseTimes == 1) {
                this.first = chosen;
            } else {
                this.second = chosen;
            }
            AbstractDungeon.gridSelectScreen.selectedCards.clear();
        }
        if (this.chooseTimes < 2) {
            this.duration = Settings.ACTION_DUR_FASTER;
            return;
        }
        if (this.first != null && this.second != null) {
            this.p.discardPile.removeCard(this.first);
            this.p.hand.moveToDeck(this.first, true);
            this.p.drawPile.removeCard(this.second);
            this.p.hand.moveToDiscardPile(this.second);
        }
        this.isDone = true;
    }
}
