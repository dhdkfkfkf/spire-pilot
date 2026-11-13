package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * B141 test double (user m09905): asks TWO grid questions the way a real mod action does.
 *
 * <p>HeadChase's card swap ({@code CaiNongMod.actions.ExchangeCardsOfTwoPilesAction}) is not two
 * pickers opened at once. It opens the second picker on the update AFTER it read the first answer,
 * and it does not clear {@code selectedCards} when it does. That delay is the whole bug: the driver's
 * pump kept going, the second picker appeared inside the pump, its open cleared the selection, the
 * pump reported "consumed", and the caller closed the picker that had just been created.
 *
 * <p>{@link MultiScreenProbeAction} cannot catch this -- it opens its next screen in the same update
 * that reads the answer, so the old fingerprint test happened to notice. This double copies the
 * timing instead: read the answer, set {@code duration} back to FASTER, return; open the next screen
 * on the following update. Pass the same {@link CardGroup} for both questions to reproduce the
 * same-pile-twice case the fingerprint could never distinguish.
 */
public class TwoScreenPickProbeAction extends AbstractGameAction {

    private final CardGroup firstGroup;
    private final CardGroup secondGroup;

    /** What this action actually read for question 1 and question 2 (null = never read). */
    public final AbstractCard[] picks = new AbstractCard[2];
    /** How many pickers this action has opened so far. */
    public int opened;
    /** True once both answers were read and the swap finished. */
    public boolean finished;

    public TwoScreenPickProbeAction(CardGroup first, CardGroup second) {
        this.firstGroup = first;
        this.secondGroup = second;
        this.duration = Settings.ACTION_DUR_FASTER;
        this.actionType = ActionType.CARD_MANIPULATION;
    }

    @Override
    public void update() {
        if (this.duration == Settings.ACTION_DUR_FASTER) {
            if (opened == 0) {
                AbstractDungeon.gridSelectScreen.open(firstGroup, 1, "choose one to swap", false, false, false, false);
                ++opened;
                tickDuration();
                return;
            }
            if (opened == 1) {
                AbstractDungeon.gridSelectScreen.open(secondGroup, 1, "choose one to swap", false, false, false, false);
                ++opened;
                tickDuration();
                return;
            }
        }
        if (!AbstractDungeon.gridSelectScreen.selectedCards.isEmpty()) {
            AbstractCard card = AbstractDungeon.gridSelectScreen.selectedCards
                    .get(AbstractDungeon.gridSelectScreen.selectedCards.size() - 1);
            if (opened == 1) {
                picks[0] = card;
                // Deliberately no clear(): the real action leaves the selection for the caller.
                this.duration = Settings.ACTION_DUR_FASTER;
                return;
            }
            picks[1] = card;
            finished = true;
            AbstractDungeon.gridSelectScreen.selectedCards.clear();
            AbstractDungeon.closeCurrentScreen();
            this.isDone = true;
            return;
        }
        tickDuration();
    }
}
