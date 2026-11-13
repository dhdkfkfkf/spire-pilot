package sts1solver.search;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;

/**
 * How much HP the player is guaranteed to get BACK after winning this fight.
 *
 * <p>The reference solver has {@code StrategicHpRecoveryBound}: it certifies that the only healing
 * left is POST-combat healing, from a deliberately tiny closed set of sources, and adds an optimistic
 * bound of that healing to the terminal value. Anything it cannot certify gets no credit.
 *
 * <p>Slay the Spire 1 has the same shape: relics like Burning Blood heal when the fight ends, and the
 * engine does that OUTSIDE the simulated combat. Ignoring it makes the search prefer a line that
 * ends at 60 HP over an equivalent line at 58 HP, even though both are 66 after the fight.
 *
 * <p>B160 (user m15722): the panel used to append "还有没认出来的遗物，可能回更多" whenever any
 * non-vanilla relic was carried. That sentence was a whitelist leaking into the UI: it fired for
 * EVERY unknown relic, healing or not, and the game does not "recognise" relics -- it runs their
 * onVictory. The line now states exactly the three sources it counts and their amounts.
 *
 * <p>Known boundary (documented, not hidden): the count is limited to the vanilla post-combat
 * sources (Burning Blood / Black Blood / Meat on the Bone / Self Repair). A modded relic that heals
 * on victory is not simulated here, because the sandbox stops at "monsters dead" and the engine's
 * onVictory never runs (see docs/SANDBOX-MECHANISMS.md, M24). The bound is capped by the player's
 * missing HP, so it can never claim more than a real heal would.
 */
public final class PostCombatHealing {

    private PostCombatHealing() {
    }

    /** HP the game itself gives back when this fight is won (vanilla sources only; see class doc). */
    public static int bound() {
        try {
            AbstractPlayer p = AbstractDungeon.player;
            if (p == null) {
                return 0;
            }
            int heal = 0;
            // Black Blood replaces Burning Blood; never count both.
            // Match by CLASS name, not by localized display name: the game may be running in any
            // language, and a text match would silently stop working after a translation change.
            boolean blackBlood = has(p, "BlackBlood");
            if (blackBlood) {
                heal += 12;
            } else if (has(p, "BurningBlood")) {
                heal += 6;
            }
            // Meat on the Bone only heals when the player ends the fight below half HP.
            if (has(p, "MeatOnTheBone") && p.currentHealth * 2 < p.maxHealth) {
                heal += 12;
            }
            // m22492: powers heal at victory too, and the simulation stops at "monsters dead", so the
            // engine's onVictory never runs inside the sandbox. RepairPower (the Defect's "Self Repair",
            // +7 / +10 per play, stacking) is the only vanilla power that heals there
            // (build/decomp6/com/megacrit/cardcrawl/powers/RepairPower.java onVictory).
            heal += repairPower(p);
            return Math.min(heal, Math.max(0, p.maxHealth - p.currentHealth));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * One panel line, in the words a player uses.
     *
     * <p>m19237: "战后回血上界" was a term only this codebase knew. What the number means is: after
     * the fight is won the game itself heals you this much (Burning Blood and friends fire outside
     * the simulated combat), so a line that ends at 58 HP is really a 64 HP line. Worded as such.
     */
    public static String describe() {
        int heal = bound();
        if (heal <= 0) {
            return "";
        }
        StringBuilder src = new StringBuilder();
        AbstractPlayer p = AbstractDungeon.player;
        boolean black = has(p, "BlackBlood");
        if (black) {
            src.append("黑血 +12");
        } else if (has(p, "BurningBlood")) {
            src.append("燃烧之血 +6");
        }
        if (has(p, "MeatOnTheBone") && p != null && p.currentHealth * 2 < p.maxHealth) {
            src.append(src.length() == 0 ? "" : "，").append("带骨肉 +12");
        }
        int repair = repairPower(p);
        if (repair > 0) {
            src.append(src.length() == 0 ? "" : "，").append("自我修复 +").append(repair);
        }
        return "打赢后固定回血 +" + heal + "（" + src + "）";
    }

    /** Total amount of RepairPower ("Self Repair") on the player; 0 when it was never played. */
    private static int repairPower(AbstractPlayer p) {
        try {
            if (p == null || p.powers == null) {
                return 0;
            }
            int amount = 0;
            for (int i = 0; i < p.powers.size(); i++) {
                AbstractPower power = p.powers.get(i);
                if (power != null && "RepairPower".equals(power.getClass().getSimpleName())) {
                    amount += Math.max(0, power.amount);
                }
            }
            return amount;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean has(AbstractPlayer p, String relicId) {
        for (int i = 0; i < p.relics.size(); i++) {
            AbstractRelic r = p.relics.get(i);
            if (r != null && relicId.equals(r.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }
}