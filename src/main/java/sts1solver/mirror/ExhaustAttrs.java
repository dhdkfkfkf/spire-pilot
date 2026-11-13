package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.util.Log;

/**
 * B76 (m21391): the live game resets a card's per-turn attributes one second after it lands in the
 * exhaust pile -- {@code ExhaustCardEffect.update()} calls {@code AbstractCard.resetAttributes()}
 * when its animation ends. That effect is a renderer object, so {@link World#trimPresentation()}
 * throws it away before it ever runs, and a card the sandbox exhausted kept the cost it had for that
 * turn (for example {@code Shockwave(0)} from Corruption) for the rest of the fight.
 *
 * <p>That is a real state difference (Exhume hands the card back with the wrong cost), and it also
 * looked like a plan drift: the search predicted {@code Shockwave(0)} in the exhaust pile while the
 * live game had already reset it, so the plan was thrown away on a card that was never played.
 *
 * <p>{@link #sync()} applies the end state of that animation wherever the sandbox drops its
 * presentation objects. It only touches cards whose cost was changed for the turn, so it is a
 * couple of int compares per exhausted card.
 */
public final class ExhaustAttrs {
    private static int seen = -1;
    private static int resets;
    private static String last = "";

    private ExhaustAttrs() {
    }

    /**
     * Starts a sandbox window: cards exhausted before it are stale and get reset too. The counters are
     * cumulative for the process on purpose -- logging is capped below, so a long search cannot fill
     * the log with one line per node.
     */
    public static void begin() {
        seen = -1;
        sync();
    }

    public static void end() {
        seen = -1;
    }

    /** Resets every exhausted card whose cost was changed for the turn (no-op while unchanged). */
    public static void sync() {
        CardGroup pile = pile();
        if (pile == null || pile.group == null) {
            return;
        }
        int n = pile.group.size();
        if (n == seen) {
            return;
        }
        for (int i = 0; i < n; i++) {
            AbstractCard c = pile.group.get(i);
            if (c == null) {
                continue;
            }
            if (c.costForTurn == c.cost && !c.isCostModifiedForTurn) {
                continue;
            }
            int cost = c.cost;
            int perTurn = c.costForTurn;
            try {
                c.resetAttributes();
            } catch (Throwable t) {
                continue;
            }
            ++resets;
            last = c.cardID + " (cost " + cost + ", was " + perTurn + " for that turn)";
            if (resets <= 3) {
                Log.info("exhaust attrs: reset " + last
                        + " -- the live game does this in ExhaustCardEffect, which never runs here");
            }
        }
        seen = n;
    }

    public static int resets() {
        return resets;
    }

    public static String lastReset() {
        return last;
    }

    public static String describe() {
        return "resets=" + resets + " last=[" + last + "]";
    }

    private static CardGroup pile() {
        try {
            return AbstractDungeon.player == null ? null : AbstractDungeon.player.exhaustPile;
        } catch (Throwable t) {
            return null;
        }
    }
}
