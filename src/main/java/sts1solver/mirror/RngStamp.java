package sts1solver.mirror;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.random.Random;

/**
 * B147 (user m12327): where the game's random streams stand, as a string.
 *
 * <p>The sandbox reproduces a fight by cloning these streams (see {@link Statics}: every Megacrit
 * {@link Random} is a clone root), so "the same board" means "the same board AND the same random
 * position". A board digest cannot see that: two fights with identical hands, HP and powers but
 * different {@code cardRandomRng.counter} values will play different cards the moment anything rolls
 * -- 唐 (TangPower) plays a random hand card at the start of every turn, which is exactly how a plan
 * became wrong while every printed state still matched.
 *
 * <p>This class only READS the counters. It is used for three things: the plan cache key (a plan
 * computed at another random position is not the same plan), the step comparison (a step whose
 * counters differ from the plan's is the first place the two worlds parted), and the play trace
 * (each recorded play carries the counters it happened at).
 *
 * <p>The fields are read directly rather than by reflection: they are public statics of
 * {@code AbstractDungeon} and the set has not changed since 2019. An RNG a mod adds somewhere else
 * would not be seen here -- the mod's own random draws still show up through the counters below
 * whenever they go through one of the game's streams, and a mod that keeps its own stream is not
 * mirrorable anyway.
 */
public final class RngStamp {

    private RngStamp() {
    }

    /** The RNGs that move DURING a fight, in a stable order: {@code name=value,name=value,...}. */
    public static String of() {
        StringBuilder sb = new StringBuilder(96);
        append(sb, "cardRandom", AbstractDungeon.cardRandomRng);
        append(sb, "shuffle", AbstractDungeon.shuffleRng);
        append(sb, "ai", AbstractDungeon.aiRng);
        append(sb, "misc", AbstractDungeon.miscRng);
        append(sb, "monster", AbstractDungeon.monsterRng);
        append(sb, "monsterHp", AbstractDungeon.monsterHpRng);
        append(sb, "relic", AbstractDungeon.relicRng);
        append(sb, "potion", AbstractDungeon.potionRng);
        append(sb, "card", AbstractDungeon.cardRng);
        append(sb, "event", AbstractDungeon.eventRng);
        append(sb, "merchant", AbstractDungeon.merchantRng);
        append(sb, "treasure", AbstractDungeon.treasureRng);
        append(sb, "map", AbstractDungeon.mapRng);
        return sb.toString();
    }

    /** The four streams a card play actually rolls on, short enough to print per play. */
    public static String compact() {
        StringBuilder sb = new StringBuilder(32);
        append(sb, "c", AbstractDungeon.cardRandomRng);
        append(sb, "s", AbstractDungeon.shuffleRng);
        append(sb, "a", AbstractDungeon.aiRng);
        append(sb, "m", AbstractDungeon.miscRng);
        append(sb, "h", AbstractDungeon.monsterHpRng);
        return sb.toString();
    }

    /** True when {@code stamp} is unknown (an older plan, a hand-built step) or equal to now. */
    public static boolean same(String stamp) {
        return stamp == null || stamp.isEmpty() || stamp.equals(of());
    }

    private static void append(StringBuilder sb, String name, Random rng) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(name).append('=').append(rng == null ? -1 : rng.counter);
    }
}
