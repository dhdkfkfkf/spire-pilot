package sts1solver.search;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;

/**
 * G3 (JUDGMENT.md): how many death saves a route burned, as an explicit ranking term.
 *
 * <p>The behaviour was always correct — STS1 runs the save inside {@code AbstractPlayer.damage()},
 * so the player's HP never rests on 0 and a "dying" state never leaks into the search. What was
 * missing is the <em>cost</em>: burning Lizard Tail or a Fairy in a Bottle is a one-per-combat
 * resource, and a route that needed it should rank below a route that did not, even when the second
 * route ended on fewer HP.
 *
 * <p>Both saves are observable after the fact, without touching the damage flow:
 * <ul>
 *   <li><b>Lizard Tail</b> — {@code onTrigger()} calls {@code setCounter(-2)} (javap:
 *       {@code research\javap\com_megacrit_cardcrawl_relics_LizardTail.txt}), so the relic's
 *       {@code counter} is the flag. It starts at -1 and the save only fires while it is -1.</li>
 *   <li><b>Fairy in a Bottle</b> — the potion is destroyed by
 *       {@code AbstractDungeon.topPanel.destroyPotion(...)} in the same branch, and it can never be
 *       drunk by hand ({@code canUse() == false}, gate S54), so a missing FairyPotion <em>is</em> a
 *       death save.</li>
 * </ul>
 *
 * <p>Ordering follows the reference's route-quality tuple: victory, then survival, then fewer
 * death saves, then fewer resources spent. {@link #POINTS} is therefore large enough to outrank any
 * realistic HP deficit (500 HP) while staying well under {@code Solver}'s 10,000,000 win bonus.
 */
public final class DeathSaves {

    /** The relic that saves you once per combat. */
    public static final String LIZARD_TAIL = "Lizard Tail";
    /** The potion that saves you once, from the potion bar. */
    public static final String FAIRY_POTION = "FairyPotion";
    /** Score points per death save; 500 HP, so it outranks any deficit a real fight produces. */
    public static final int POINTS = 1000000;
    /** {@code counter} value Lizard Tail carries once it has fired. */
    public static final int LIZARD_TAIL_SPENT = -2;

    private DeathSaves() {
    }

    /** Fairy in a Bottle still sitting in the potion bar (0 when the player has none). */
    public static int fairiesInBar() {
        try {
            if (AbstractDungeon.player == null || AbstractDungeon.player.potions == null) {
                return 0;
            }
            int count = 0;
            for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
                AbstractPotion potion = (AbstractPotion) AbstractDungeon.player.potions.get(i);
                if (potion != null && FAIRY_POTION.equals(potion.ID)) {
                    count++;
                }
            }
            return count;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    /** True once Lizard Tail has fired this combat (its counter is -2, not the ready -1). */
    public static boolean lizardTailSpent() {
        try {
            if (AbstractDungeon.player == null || AbstractDungeon.player.relics == null) {
                return false;
            }
            for (int i = 0; i < AbstractDungeon.player.relics.size(); i++) {
                AbstractRelic relic = (AbstractRelic) AbstractDungeon.player.relics.get(i);
                if (relic != null && LIZARD_TAIL.equals(relic.relicId) && relic.counter == LIZARD_TAIL_SPENT) {
                    return true;
                }
            }
            return false;
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * Death saves burned so far, given how many Fairy in a Bottle the line started with: every one
     * missing now was drunk by the game's death branch, plus one for a spent Lizard Tail.
     */
    public static int usedSoFar(int fairiesAtStart) {
        int fairiesSpent = Math.max(0, fairiesAtStart - fairiesInBar());
        return fairiesSpent + (lizardTailSpent() ? 1 : 0);
    }

    /** Same as {@link #usedSoFar(int)} but with both observables already read from a branch. */
    public static int used(int fairiesAtStart, int fairiesNow, boolean lizardTailSpent) {
        return Math.max(0, fairiesAtStart - fairiesNow) + (lizardTailSpent ? 1 : 0);
    }

    /** Score penalty; the caller subtracts this. */
    public static int points(int uses) {
        return Math.max(0, uses) * POINTS;
    }
}
