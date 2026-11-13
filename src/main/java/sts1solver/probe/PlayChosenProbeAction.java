/*
 * A test double for a mod action that opens a hand pick and then AUTO-PLAYS the picked card.
 *
 * CaiNongMod's 豹指导 plays PlayCardAction (handCardSelectScreen.open("打出", ...), then
 * NewQueueCardAction(card, true, false, true) with autoplay=true). The core gates do not load mods,
 * so B135 is pinned with this copy of that shape: one pick, then that exact card is played by the
 * fight itself. It is never part of the search -- only a gate queues it.
 */
package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.utility.NewQueueCardAction;
import com.megacrit.cardcrawl.actions.utility.UnlimboAction;
import com.megacrit.cardcrawl.actions.utility.WaitAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

public final class PlayChosenProbeAction
extends AbstractGameAction {
    /** B159: how many cards this double actually played (gates assert the picker was answered). */
    public static int played;

    private final AbstractPlayer p;
    private boolean opened;

    public PlayChosenProbeAction(AbstractPlayer p) {
        this.p = p;
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
            if (this.p.hand.isEmpty()) {
                this.isDone = true;
                return;
            }
            AbstractDungeon.handCardSelectScreen.open("\u6253\u51fa", 1, false, false);
            this.tickDuration();
            return;
        }
        if (!AbstractDungeon.handCardSelectScreen.wereCardsRetrieved) {
            for (AbstractCard card : AbstractDungeon.handCardSelectScreen.selectedCards.group) {
                this.play(card);
            }
            CardCrawlGame.dungeon.checkForPactAchievement();
            AbstractDungeon.handCardSelectScreen.wereCardsRetrieved = true;
        }
        this.tickDuration();
        this.isDone = true;
    }

    private void play(AbstractCard card) {
        ++played;
        this.p.hand.group.remove(card);
        AbstractDungeon.getCurrRoom().souls.remove(card);
        this.p.limbo.group.add(card);
        card.current_y = -200.0f * Settings.scale;
        card.target_x = (float)Settings.WIDTH / 2.0f + 200.0f * Settings.xScale;
        card.target_y = (float)Settings.HEIGHT / 2.0f;
        card.targetAngle = 0.0f;
        card.lighten(false);
        card.drawScale = 0.12f;
        card.targetDrawScale = 0.75f;
        card.applyPowers();
        this.addToTop(new NewQueueCardAction(card, true, false, true));
        this.addToTop(new UnlimboAction(card));
        this.addToTop(new WaitAction(Settings.ACTION_DUR_FASTER));
    }
}
