/*
 * A test double for one action that opens several pickers in a row.
 *
 * A power that auto-plays a mod card can hand the player a long chain inside a single step: the
 * auto-played card opens a hand pick, the card picked there opens two grids, and a second
 * auto-played card adds more (that is the fight in user m08865). The core gates do not load mods,
 * so B139 pins the walk's behaviour with this copy of the shape: N grid screens over N different
 * groups, one after the other, all inside one queued action. It is never part of the search --
 * only a gate queues it.
 */
package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

public final class MultiScreenProbeAction
extends AbstractGameAction {
    private final CardGroup[] groups;
    private int index = -1;

    public MultiScreenProbeAction(String[] cardIds) {
        this.duration = this.startDuration = Settings.ACTION_DUR_FASTER;
        this.actionType = AbstractGameAction.ActionType.CARD_MANIPULATION;
        this.groups = new CardGroup[cardIds.length];
        for (int i = 0; i < cardIds.length; i++) {
            CardGroup g = new CardGroup(CardGroup.CardGroupType.UNSPECIFIED);
            g.addToTop(com.megacrit.cardcrawl.helpers.CardLibrary.getCopy(cardIds[i]));
            this.groups[i] = g;
        }
    }

    @Override
    public void update() {
        if (AbstractDungeon.getCurrRoom() == null
                || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT) {
            this.isDone = true;
            return;
        }
        if (this.index < 0) {
            this.index = 0;
            this.open();
            this.tickDuration();
            return;
        }
        if (AbstractDungeon.gridSelectScreen.selectedCards.isEmpty()) {
            this.tickDuration();
            return;
        }
        AbstractDungeon.gridSelectScreen.selectedCards.clear();
        ++this.index;
        if (this.index >= this.groups.length) {
            AbstractDungeon.closeCurrentScreen();
            this.isDone = true;
            return;
        }
        this.open();
        this.tickDuration();
    }

    private void open() {
        AbstractDungeon.gridSelectScreen.open(this.groups[this.index], 1, "screen " + (this.index + 1),
                false, false, false, false);
    }
}
