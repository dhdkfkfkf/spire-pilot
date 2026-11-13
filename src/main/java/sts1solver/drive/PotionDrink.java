package sts1solver.drive;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import sts1solver.util.Log;

/**
 * Drinking a potion the way the game drinks it.
 *
 * <p>{@code PotionPopUp.updateInput()} does four things in one branch: add the floor to the run
 * metrics, call {@code potion.use(target)}, tell every relic ({@code r.onUsePotion()}) and finally
 * {@code AbstractDungeon.topPanel.destroyPotion(slot)} -- which is just
 * {@code player.potions.set(slot, new PotionSlot(slot))}. Calling {@code potion.use()} alone, as the
 * live autopilot did, applies the effect but leaves the bottle in the bar: the plan's prediction and
 * the real bar then disagree, and the player sees "the potion worked but was not consumed"
 * (m17821, Thorn Potion).
 *
 * <p>Toy Ornithopter is the only vanilla relic with {@code onUsePotion()} (heal 5), so skipping the
 * relic pass also made the search under-estimate the value of every potion by 5 HP.
 *
 * <p>Metrics are only touched for a real drink ({@code live}); the sandbox drinks dozens of potions
 * per search and must not write the player's run statistics.
 */
public final class PotionDrink {

    private PotionDrink() {
    }

    /**
     * Applies the effect, the relic pass and the consumption, in the game's order.
     *
     * @return the slot that was cleared, or -1 when nothing could be cleared
     */
    public static int drink(int slot, AbstractPotion potion, AbstractMonster target, boolean live) {
        if (potion == null) {
            return -1;
        }
        int clear = slot >= 0 ? slot : potion.slot;
        if (live) {
            try {
                if (CardCrawlGame.metricData != null && CardCrawlGame.metricData.potions_floor_usage != null) {
                    CardCrawlGame.metricData.potions_floor_usage.add(AbstractDungeon.floorNum);
                }
            } catch (Throwable ignored) {
                // metrics are cosmetic; never let them stop a drink
            }
        }
        potion.use(target);
        try {
            if (AbstractDungeon.player != null && AbstractDungeon.player.relics != null) {
                for (AbstractRelic relic : AbstractDungeon.player.relics) {
                    if (relic == null) {
                        continue;
                    }
                    try {
                        relic.onUsePotion();
                    } catch (Throwable error) {
                        Log.warn("onUsePotion failed for " + relic.name + ": " + error);
                    }
                }
            }
        } catch (Throwable error) {
            Log.warn("the relic pass after a potion failed: " + error);
        }
        if (clear < 0) {
            return -1;
        }
        try {
            AbstractDungeon.topPanel.destroyPotion(clear);
        } catch (Throwable error) {
            if (AbstractDungeon.player != null && AbstractDungeon.player.potions != null
                    && clear < AbstractDungeon.player.potions.size()) {
                AbstractDungeon.player.potions.set(clear, new PotionSlot(clear));
            }
        }
        return clear;
    }
}
