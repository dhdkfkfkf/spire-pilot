package sts1solver.search;

import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;

/**
 * Two facts about a potion that the search has to agree with the game about.
 *
 * <h2>1. Whether the game will let you drink it</h2>
 *
 * The potion bar is drawn by the real UI, and the real UI asks {@link AbstractPotion#canUse()}
 * before it accepts a click ({@code ui/panels/PotionPopUp.java:166-176, 225-231, 273-276}). That
 * predicate is not cosmetic: {@code FairyPotion.canUse()} returns false unconditionally (the potion
 * is death-triggered), {@code SmokeBomb.canUse()} refuses on a boss or a BackAttack monster, and the
 * four potions that are safe outside combat ({@code BloodPotion}, {@code FruitJuice},
 * {@code EntropicBrew}, plus everything else through {@code AbstractPotion.canUse()}) refuse once
 * {@code actionManager.turnHasEnded} is set. Enumerating such a potion as a legal branch would let a
 * plan ask for something a player cannot do, and the live executor would then drink it anyway
 * (a potion used from code skips the pop-up entirely), which is how a "Fairy in a Bottle" could be
 * burned for 30% healing in the middle of a fight.
 *
 * <h2>2. What spending it costs</h2>
 *
 * A potion is not free to use, so a route that spends one carries an HP-shaped cost. The reference
 * solver prices that cost by tier instead of a flat number
 * ({@code CombatSolver-main src\Search\PotionValuationRegistry.cs:58-70}):
 * {@code SolverWeights.PotionMinimumHpSaved = 9},
 * {@code PotionElevatedValueHpSaved = (9*3+1)/2 = 14},
 * {@code PotionHighValueHpSaved = 18}. We keep the same three numbers so our orderings stay
 * comparable with the reference; the tier lists are ours because the two games have different
 * potions. A potion the table does not know costs the standard 9 HP, which is what this project
 * used before the table existed (it said 10, one more than the reference's floor).
 *
 * <p>Gate S95 pins both halves: the unusable potions have to disappear from {@link Legal} while a
 * normal one stays, and the three tiers have to map to 9/14/18.
 */
public final class PotionBook {

    /** HP of deficit one potion costs when the table has no opinion (reference: 9). */
    public static final int STANDARD_HP = 9;
    /** HP of deficit for a strong-but-ordinary potion (reference: 14). */
    public static final int ELEVATED_HP = 14;
    /** HP of deficit for a potion you would rather keep (reference: 18). */
    public static final int HIGH_HP = 18;

    /**
     * Potions worth keeping: they change the shape of a fight (a free turn, an extra copy, a
     * permanent max HP, a death save) rather than adding one number to it.
     */
    private static final java.util.Set<String> HIGH = keys(
            "Ambrosia", "CultistPotion", "DuplicationPotion", "EntropicBrew", "FairyPotion",
            "FruitJuice", "GamblersBrew", "GhostInAJar");

    /**
     * Potions that are clearly better than a flat damage/block potion but still one-shot effects.
     */
    private static final java.util.Set<String> ELEVATED = keys(
            "AttackPotion", "BlessingOfTheForge", "BottledMiracle", "ColorlessPotion",
            "DistilledChaos", "ElixirPotion", "EssenceOfDarkness", "EssenceOfSteel",
            "FocusPotion", "HeartOfIron", "LiquidMemories", "PoisonPotion", "PotionOfCapacity",
            "PowerPotion", "RegenPotion", "SkillPotion", "SneckoOil", "StancePotion");

    private PotionBook() {
    }

    private static java.util.Set<String> keys(String... ids) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String id : ids) {
            out.add(normalize(id));
        }
        return out;
    }

    /** "Fire Potion" and "FirePotion" have to be the same key: vanilla uses both spellings. */
    private static String normalize(String id) {
        if (id == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c != ' ' && c != '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * True when the real game would let the player drink this potion right now. Never throws: a
     * predicate that fails is treated as "no" (the safe answer for a branch the executor cannot undo).
     */
    public static boolean usable(AbstractPotion p) {
        if (p == null || p instanceof PotionSlot) {
            return false;
        }
        try {
            return p.canUse();
        } catch (Throwable t) {
            return false;
        }
    }

    /** HP of deficit this potion costs when a route spends it. */
    public static int valueHp(AbstractPotion p) {
        return p == null ? STANDARD_HP : valueForId(p.ID);
    }

    public static int valueForId(String id) {
        String key = normalize(id);
        if (HIGH.contains(key)) {
            return HIGH_HP;
        }
        if (ELEVATED.contains(key)) {
            return ELEVATED_HP;
        }
        return STANDARD_HP;
    }

    /**
     * The same lookup from an {@link Action#label}, which carries the potion id in brackets
     * ("Fire Potion[Fire Potion]::Acid Slime"). Used to price a finished plan, where the potion
     * object is gone but the label is not.
     */
    public static int valueForLabel(String label) {
        if (label == null) {
            return STANDARD_HP;
        }
        int open = label.indexOf('[');
        int close = open < 0 ? -1 : label.indexOf(']', open + 1);
        if (open < 0 || close <= open + 1) {
            return STANDARD_HP;
        }
        return valueForId(label.substring(open + 1, close));
    }

    /** "Fire Potion[Fire Potion] (9 HP)" -- for logs and the panel. */
    public static String describe(AbstractPotion p) {
        if (p == null) {
            return "none";
        }
        return p.name + "[" + p.ID + "] (" + valueHp(p) + " HP)";
    }
}
