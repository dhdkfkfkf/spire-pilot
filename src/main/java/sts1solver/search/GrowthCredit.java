package sts1solver.search;

import java.util.List;

/**
 * How much a permanent-growth play is worth inside a single fight.
 *
 * <p>The reference solver keeps a growth vector (Feed, Genetic Algorithm, Hand of Greed, The Scythe,
 * ...) and a user-authorised budget: "how much HP am I willing to pay for growth". Its default is to
 * pay nothing, and the credit only moves the search when the player turns it on.
 *
 * <p>This class is that policy, sized for Slay the Spire 1: a fixed credit per growth play, OFF by
 * default. It changes no ranking unless switched on, which is the honest default -- growth is a
 * run-level bet, not a fight-level fact.
 */
public final class GrowthCredit {

    /**
     * Cards whose play permanently grows the run. Matched by card id, not display name.
     *
     * <p>B146 (user m11490): Hand of Greed is NOT here. Its permanent gain is gold, and the solver now
     * prices every coin directly at {@link Solver#GOLD_POINTS}, so crediting the play as well would pay
     * the same gold twice whenever the player switches this policy on.
     */
    private static final java.util.Set<String> GROWTH_CARDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("Feed", "Genetic Algorithm", "The Scythe",
                    "RitualDagger", "LessonLearned"));

    /** HP-equivalent value of one growth play. 0 = disabled (the default). */
    private static volatile int creditPerPlay = 0;

    private GrowthCredit() {
    }

    public static int credit() {
        return creditPerPlay;
    }

    public static void setCredit(int value) {
        creditPerPlay = Math.max(0, value);
    }

    public static boolean enabled() {
        return creditPerPlay > 0;
    }

    /** Score contribution for a plan: number of growth plays times the credit. */
    public static int valueOf(List<Action> path) {
        if (!enabled() || path == null) {
            return 0;
        }
        int plays = 0;
        for (int i = 0; i < path.size(); i++) {
            Action a = path.get(i);
            if (a != null && a.cardName != null && GROWTH_CARDS.contains(a.cardId)) {
                plays++;
            }
        }
        return plays * creditPerPlay * 2000;
    }

    /** One panel line, "" when the policy is off. */
    public static String describe() {
        return enabled() ? "成长信用: 每次 +" + creditPerPlay + " HP 等价" : "";
    }
}