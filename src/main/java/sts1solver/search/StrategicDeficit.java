package sts1solver.search;

/**
 * The single "strategic HP deficit" scalar used to rank routes (JUDGMENT.md G1).
 *
 * <p>The reference solver folds every HP-shaped effect into one number before comparing routes:
 * {@code deficit = hpLost + maxHpLost - recoveredHp - relicHeal - deathSaveHp - growthHpCredit},
 * and only then orders by it (victory first, then lower deficit, then fewer turns). Our score used
 * to add the pieces separately with different weights, which makes two routes incomparable whenever
 * the pieces trade off (1 max HP against 8 HP, say). This class is that one number.
 *
 * <p>Units are HP. Everything the caller knows in score points gets divided by
 * {@link #POINTS_PER_HP} first, so the result cannot drift from the score weights.
 */
public final class StrategicDeficit {

    /** Score points per HP; one HP is worth 2000 in {@link Solver#score}. */
    public static final int POINTS_PER_HP = 2000;
    /** Score points per potion spent (10 HP: a potion is not free to use). */
    public static final int POTION_POINTS = 20000;

    private StrategicDeficit() {
    }

    /** HP lost plus max-HP lost, minus everything that gives HP back. */
    public static int of(int hpLost, int maxHpLost, int recoveredHp, int growthHpCredit,
                         int potionCostHp) {
        return hpLost + maxHpLost - recoveredHp - growthHpCredit + potionCostHp;
    }

    /** Converts a score-point credit (growth, potion replacement) into HP. */
    public static int pointsToHp(int points) {
        return points / POINTS_PER_HP;
    }

    /**
     * Route ordering: a complete victory beats everything; then the lower deficit; then the
     * quicker win. Returns negative when A is better (same convention as the reference).
     */
    public static int compare(boolean winA, int deficitA, int turnsA,
                              boolean winB, int deficitB, int turnsB) {
        if (winA != winB) {
            return winA ? -1 : 1;
        }
        if (deficitA != deficitB) {
            return deficitA < deficitB ? -1 : 1;
        }
        return Integer.compare(turnsA, turnsB);
    }
}