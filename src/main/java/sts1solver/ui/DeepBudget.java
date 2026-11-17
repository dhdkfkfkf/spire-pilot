package sts1solver.ui;

/**
 * Effective deep-search budget (B16-followup).
 *
 * <p>Boss fights are the one place where a 60 second search can be too little: the Slime Boss
 * splits, the health pool is far larger, and the search has to model several turns past the kill.
 * That is handled by {@link #autoBudgetMs} ("自动"), which is the only mode that scales with the
 * room. A number the player picked is used verbatim -- see {@link #budgetMs}.
 *
 * <p>Kept free of game types except for the one predicate, so the arithmetic is gate-testable.
 */
public final class DeepBudget {

    /** Boss rooms get twice the 自动 seconds. */
    public static final int BOSS_SECONDS_FACTOR = 2;
    /** Never search less than this, whatever settings.txt says: the smallest tier is 6s. */
    public static final long MIN_SECONDS = 6L;
    /** Hard ceiling on seconds, even after the Boss factor. */
    public static final long MAX_SECONDS = 600L;
    /** Hard ceiling on nodes, even after the Boss factor. */
    public static final int MAX_NODES = 1000000;

    /**
     * "No limit" sentinel for a time budget (user m13385 / P2-12).
     *
     * <p>The player asked for the default to be unlimited: as long as the panel keeps showing a
     * moving worldline count the search is making progress, and he is willing to wait. A literal
     * {@code Long.MAX_VALUE} would overflow every "budget * share / 100" inside the search, so this
     * is a large-but-safe number -- 1e9 ms is about 11.5 days, far past any real session, and the
     * search still stops on its own when the frontier runs out, when F8 is pressed, or when the
     * 计算 button is clicked a second time (B143: 停止 is no longer a separate button).
     */
    public static final long UNLIMITED = 1000000000L;

    /** True when this resolved budget means "no time limit". */
    public static boolean unlimited(long ms) {
        return ms >= UNLIMITED;
    }

    private DeepBudget() {
    }

    /**
     * Budget in ms for the seconds the player picked.
     *
     * <p>1.0.15 (user report), the number is the number. A 6s tier used to be lifted to a legacy 10s
     * floor and then doubled in a boss room, so picking 6/12/18/30/60 ran as 20/24/36/60/120. Scene
     * scaling belongs to {@link #autoBudgetMs} only: a fixed tier is a promise, "自动" is the mode
     * that may size itself from the board.
     *
     * <p>{@code settingsSeconds <= 0} means unlimited.
     */
    public static long budgetMs(long settingsSeconds) {
        if (settingsSeconds <= 0L) {
            return UNLIMITED;
        }
        long seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, settingsSeconds));
        return seconds * 1000L;
    }

    /** Node cap in the number the player picked; 0 means no cap. */
    public static int nodes(int settingsNodes) {
        return Math.max(0, Math.min(MAX_NODES, settingsNodes));
    }

    /**
     * 1.0.8 (C4): the budget for the "auto" setting (0 seconds chosen).
     *
     * <p>{@link #budgetMs} floors at {@link #MIN_SECONDS} because a player who explicitly asked for
     * 10 seconds must get 10 seconds. "Auto" is the opposite promise: answer sooner on a big board.
     * So this path allows a smaller floor and applies the Boss factor on top.
     */
    public static long autoBudgetMs(long autoSeconds, boolean boss) {
        long seconds = Math.max(2L, Math.min(MAX_SECONDS, autoSeconds));
        if (boss) {
            seconds = Math.min(MAX_SECONDS, seconds * BOSS_SECONDS_FACTOR);
        }
        return seconds * 1000L;
    }

    /** True when this room is a boss fight. */
    public static boolean isBossRoom(com.megacrit.cardcrawl.rooms.AbstractRoom room) {
        return room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
    }

    /** True when the fight the player is standing in is a boss fight. */
    public static boolean inBossRoom() {
        try {
            return isBossRoom(com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom());
        } catch (Throwable t) {
            // leaving a fight / loading: never let a budget probe throw inside the panel
            return false;
        }
    }
}
