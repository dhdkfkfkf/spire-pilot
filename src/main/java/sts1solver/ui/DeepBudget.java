package sts1solver.ui;

/**
 * Effective deep-search budget (B16-followup).
 *
 * <p>Boss fights are the one place where a 60 second search is simply not enough: the Slime Boss
 * splits, the health pool is far larger, and the search has to model several turns past the kill.
 * A20 Slime Boss on a starting deck measured 38 splits and a 33-step deepest line, so the plan
 * stopped mid-fight at the default budget. Doubling seconds and nodes for boss rooms only keeps
 * normal fights fast -- the setting in settings.txt stays the user's number, this is applied on top
 * of it, and the panel/log say so.
 *
 * <p>Kept free of game types except for the one predicate, so the arithmetic is gate-testable.
 */
public final class DeepBudget {

    /** Boss rooms get twice the configured seconds. */
    public static final int BOSS_SECONDS_FACTOR = 2;
    /** Boss rooms get twice the configured node cap. */
    public static final int BOSS_NODES_FACTOR = 2;
    /** Never search less than this, whatever settings.txt says. */
    public static final long MIN_SECONDS = 10L;
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
     * Budget in ms for the given configured seconds in a room of this kind.
     *
     * <p>{@code settingsSeconds <= 0} means unlimited: the panel's 秒数 button starts at 不限 and the
     * player can still step it to a concrete value. Unlimited ignores the Boss factor, because
     * doubling "no limit" is still "no limit".
     */
    public static long budgetMs(long settingsSeconds, boolean boss) {
        if (settingsSeconds <= 0L) {
            return UNLIMITED;
        }
        long seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, settingsSeconds));
        if (boss) {
            seconds = Math.min(MAX_SECONDS, seconds * BOSS_SECONDS_FACTOR);
        }
        return seconds * 1000L;
    }

    /** Node cap for the given configured cap in a room of this kind. */
    public static int nodes(int settingsNodes, boolean boss) {
        int nodes = Math.max(0, Math.min(MAX_NODES, settingsNodes));
        if (boss) {
            nodes = (int) Math.min((long) MAX_NODES, (long) nodes * BOSS_NODES_FACTOR);
        }
        return nodes;
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
