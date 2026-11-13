package sts1solver.search;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

/**
 * What the solver is allowed to do about LEAVING a fight instead of winning it.
 *
 * <p>The reference solver treats escaping as an explicit policy (its theft encounters have a
 * Deny / LetEscape switch), not as a side effect of a scoring weight. The same choice matters here:
 * a Smoke Bomb line saves HP but costs the potion and the fight's rewards, so it must never be
 * something the search picks by accident.
 *
 * <p>Default is {@link Mode#ONLY_IF_LETHAL}: fleeing is offered when the alternative is dying, and
 * never ranked above a win.
 *
 * <p>B129 (user m05700): leaving a fight <em>forfeits that fight's loot</em>, so the escape terminal
 * is charged for it instead of being handed a flat bonus. Before this the terminal paid
 * {@code +200000} (100 HP) for setting {@code room.smoked}, which made escaping the best move in any
 * fight where it did not immediately win: the search then drank a second potion and still reported a
 * win. The charge is modelled in the same HP currency as every other weight (1 HP = 2000 points):
 * <ul>
 *   <li>card reward: 2 HP for a normal fight, 10 HP for a boss fight (user m05700);</li>
 *   <li>gold: the mid-point of the game's own combat roll (10-20 normal, 25-35 elite, 100 +-5 boss,
 *       and the ascension-13 boss cut to 75%) at 25 gold = 1 HP, the same gold rate GrowthBook uses.</li>
 * </ul>
 * Because a win is worth +10,000,000 and dying is -50,000,000, subtracting a few thousand points here
 * can never make fleeing beat a win, and can never make dying look better than fleeing: survival still
 * dominates every growth reward.
 */
public final class EscapePolicy {

    public enum Mode {
        /** Never offer or prefer leaving the fight. */
        DENY("禁止"),
        /** Only when no surviving line exists. */
        ONLY_IF_LETHAL("仅必死"),
        /** Always a candidate; the search decides. */
        ALLOW("允许");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** Card reward is worth 2 HP when a normal fight is left behind (user m05700). */
    public static final int CARD_HP_NORMAL = 2;
    /** Card reward is worth 10 HP when a boss fight is left behind (user m05700). */
    public static final int CARD_HP_BOSS = 10;
    /**
     * B146: gold is priced at {@link Solver#GOLD_POINTS} points a coin, so 25 gold is one HP. Kept as
     * whole gold per HP (25) so the escape cost reads as "how much gold a fight is worth", but the
     * points come from the solver's single gold rate -- the two can never drift apart.
     */
    public static final int GOLD_PER_HP = 25;

    private static Mode mode = Mode.ONLY_IF_LETHAL;

    private EscapePolicy() {
    }

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode m) {
        mode = m == null ? Mode.ONLY_IF_LETHAL : m;
    }

    /** F6: DENY -> ONLY_IF_LETHAL -> ALLOW -> DENY. */
    public static Mode next() {
        switch (mode) {
            case DENY:
                mode = Mode.ONLY_IF_LETHAL;
                break;
            case ONLY_IF_LETHAL:
                mode = Mode.ALLOW;
                break;
            default:
                mode = Mode.DENY;
                break;
        }
        return mode;
    }

    /** May an escape action (Smoke Bomb) be enumerated at all? */
    public static boolean offersEscape() {
        return mode != Mode.DENY;
    }

    /** Is a reported "escaped" result allowed to be presented as the recommended line? */
    public static boolean mayRecommendEscape() {
        return mode != Mode.DENY;
    }

    /**
     * B129: points charged to a terminal where the fight was left instead of won, i.e. the loot that
     * leaving throws away. Subtracted (not added) when a node reports {@code escaped}.
     */
    public static int forfeitPoints() {
        if (mode == Mode.DENY) {
            return 0;
        }
        AbstractRoom room = null;
        int ascension = 0;
        try {
            room = AbstractDungeon.getCurrRoom();
            ascension = AbstractDungeon.ascensionLevel;
        }
        catch (Throwable ignored) {
            room = null;
        }
        boolean boss = room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
        boolean elite = room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomElite;
        return forfeitPointsFor(boss, elite, ascension, expectedGold(boss, elite, ascension));
    }

    /** Pure form of {@link #forfeitPoints()} so the gate can check the numbers without a room. */
    public static int forfeitPointsFor(boolean boss, boolean elite, int ascension, int gold) {
        int cardHp = boss ? CARD_HP_BOSS : CARD_HP_NORMAL;
        int cardPoints = cardHp * StrategicDeficit.POINTS_PER_HP;
        // B146: gold follows the panel's "局外" switch (user m11767), so the loot a fight is worth is
        // priced with the same rate the search uses -- zero when the player switched gains off.
        int goldPoints = gold * Solver.goldRate();
        return cardPoints + goldPoints;
    }

    /**
     * Mid-point of the game's own combat gold roll (AbstractRoom.update queues the reward):
     * boss 100 +-5 with the ascension-13 cut to 75%, elite 25-35, normal 10-20. The exact roll is
     * made by the live reward screen, which the sandbox never runs, so the expected value is used.
     */
    public static int expectedGold(boolean boss, boolean elite, int ascension) {
        if (boss) {
            int gold = 100;
            return ascension >= 13 ? Math.round(gold * 0.75f) : gold;
        }
        return elite ? 30 : 15;
    }
}
