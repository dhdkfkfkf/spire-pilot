package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.vfx.cardManip.ExhaustCardEffect;
import sts1solver.util.Log;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P2-61 (m25727): presentation objects are thrown away at every settle point, but a few of them carry
 * combat state that lands only at the END of their animation. Those have to be settled before the
 * object disappears, otherwise the sandbox keeps a value the live game would have cleared.
 *
 * <p>Audited the whole jar (all 293 {@code AbstractGameEffect} subclasses, see
 * {@code docs/ENGINE-FLOW.md}): exactly one class writes combat state in {@code update()} --
 * {@code com.megacrit.cardcrawl.vfx.cardManip.ExhaustCardEffect}, which at the end of its animation
 * calls {@code AbstractCard.resetAttributes()} (block/damage/magicNumber/costForTurn) and, 0.3s in,
 * sets {@code fadingOut} when the card is not in hand. {@link ExhaustAttrs} covered the exhaust-pile
 * call site only; the other two call sites (a fizzled card in {@code limbo}:
 * {@code GameActionManager.java:260}, {@code :268}, {@code :280}, and
 * {@code callEndTurnEarlySequence} at {@code :376}) were not covered at all.
 *
 * <p>{@link #settle} applies that end state for every queued {@code ExhaustCardEffect} and records
 * every other effect class it drops, once per class, so a game update or a mod that introduces a new
 * state-carrying effect shows up in the log instead of silently skewing the search.
 */
public final class EffectSettle {
    /** Cap on distinct unknown class names logged per process (search runs leave the log alone). */
    private static final int NOTE_CAP = 40;

    private static final Set<String> noted = new HashSet<String>();
    private static int settled;
    private static int notedCount;
    private static String last = "";

    private static Field cardField;
    private static boolean fieldLookupFailed;

    private EffectSettle() {
    }

    /** Applies the terminal state of state-carrying effects, then forgets them. Call before clearing. */
    public static void settle(List<?>... lists) {
        if (lists == null) {
            return;
        }
        for (int i = 0; i < lists.length; i++) {
            settleOne(lists[i]);
        }
    }

    private static void settleOne(List<?> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int n = list.size();
        for (int i = 0; i < n; i++) {
            Object o = list.get(i);
            if (o == null) {
                continue;
            }
            if (o instanceof ExhaustCardEffect) {
                settleExhaust((ExhaustCardEffect) o);
            } else {
                note(o);
            }
        }
    }

    private static void settleExhaust(ExhaustCardEffect effect) {
        AbstractCard card = cardOf(effect);
        if (card == null) {
            return;
        }
        try {
            // Mirrors ExhaustCardEffect.update()'s end state: fadingOut after 0.3s when the card has
            // already left the hand, resetAttributes() at duration < 0 (unconditional).
            if (AbstractDungeon.player != null && AbstractDungeon.player.hand != null
                    && !AbstractDungeon.player.hand.contains(card)) {
                card.fadingOut = true;
            }
            card.resetAttributes();
        } catch (Throwable t) {
            return;
        }
        ++settled;
        last = card.cardID + " (cost " + card.costForTurn + ", block " + card.block + ", dmg " + card.damage + ")";
        if (settled <= 3) {
            Log.info("effect settle: reset " + last
                    + " -- ExhaustCardEffect never runs here (B76/P2-61)");
        }
    }

    private static void note(Object effect) {
        String name = effect.getClass().getName();
        if (!noted.add(name)) {
            return;
        }
        ++notedCount;
        if (noted.size() <= NOTE_CAP) {
            Log.info("effect dropped without terminal state: " + name + " (audited: presentation only)");
        } else if (noted.size() == NOTE_CAP + 1) {
            Log.info("effect dropped without terminal state: further classes are not logged");
        }
    }

    private static AbstractCard cardOf(ExhaustCardEffect effect) {
        if (fieldLookupFailed) {
            return null;
        }
        try {
            if (cardField == null) {
                Field f = ExhaustCardEffect.class.getDeclaredField("c");
                f.setAccessible(true);
                cardField = f;
            }
            Object v = cardField.get(effect);
            return v instanceof AbstractCard ? (AbstractCard) v : null;
        } catch (Throwable t) {
            fieldLookupFailed = true;
            Log.warn("effect settle: cannot read ExhaustCardEffect.c: " + t);
            return null;
        }
    }

    public static int settled() {
        return settled;
    }

    public static int notedCount() {
        return notedCount;
    }

    public static String last() {
        return last;
    }

    public static String describe() {
        return "settled=" + settled + " droppedClasses=" + notedCount + " last=[" + last + "]";
    }

    /** True when this exact class name was recorded while trimming presentation objects. */
    public static boolean wasNoted(String className) {
        return noted.contains(className);
    }

    /** Test hook: forgets the class names noted so far (counters stay cumulative). */
    public static void resetNotedForTest() {
        noted.clear();
        notedCount = 0;
    }
}
