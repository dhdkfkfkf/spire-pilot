package sts1solver.mirror;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;

/**
 * B141 (user m09905): an ENGINE-SIDE count of how many pickers have been opened, so the driver can
 * tell "this answer was taken and the same action asked its next question" from "this answer was
 * taken and nothing else is waiting".
 *
 * <p>What went wrong: {@code Driver.pumpLiveUntilConsumed()} pumps the action manager until the
 * screen's selection is empty, then the caller closed the screen. A mod action that asks TWO
 * questions reads the first answer on one update and opens the second picker on its NEXT update --
 * which happens INSIDE that pump. Opening a picker clears {@code selectedCards}
 * ({@code GridCardSelectScreen.callOnOpen()}), so the very next consumption test said "taken", the
 * caller closed the picker that had just appeared, and the action then read an empty selection: the
 * card swap never happened, no exception, one screen recorded. The sandbox path tried to notice the
 * switch by comparing a fingerprint of the picker's group, which cannot see the same group opened
 * twice.
 *
 * <p>This class is the fact both paths now share: every picker open bumps a counter. The driver
 * reads it before it fills an answer and again after the pump; a change means a NEW question is on
 * screen, and that screen must be left for the next answer instead of being closed.
 *
 * <p>Only the root overload of each chain is patched (the others delegate), and the hand screen's
 * four-argument open is patched separately because it does not delegate. Cost: one integer bump per
 * picker open -- pickers are rare and never inside the search's per-node loop.
 */
public final class ScreenOpens {

    private ScreenOpens() {
    }

    private static volatile int opened;
    private static volatile String lastKind = "";
    private static volatile int lastGroup;

    /** How many pickers have been opened since the mod loaded (monotonic; compare two reads). */
    public static int count() {
        return opened;
    }

    /** The kind and group of the last picker opened, for log lines ("GRID#1234"). */
    public static String last() {
        return lastKind + "#" + lastGroup;
    }

    static void note(String kind, Object group) {
        ++opened;
        lastKind = kind;
        lastGroup = group == null ? 0 : System.identityHashCode(group);
    }

    /** {@code GridCardSelectScreen.open}: the seven-argument root; the others delegate to it. */
    @SpirePatch(clz = GridCardSelectScreen.class, method = "open",
            paramtypez = {CardGroup.class, int.class, String.class, boolean.class, boolean.class,
                    boolean.class, boolean.class})
    public static final class GridPatch {
        @SpirePostfixPatch
        public static void after(GridCardSelectScreen __instance, CardGroup group, int numCards,
                                 String tipMsg, boolean forUpgrade, boolean forTransform,
                                 boolean canCancel, boolean forPurge) {
            note("GRID", group);
        }
    }

    /** {@code HandCardSelectScreen.open}: the seven-argument root. */
    @SpirePatch(clz = HandCardSelectScreen.class, method = "open",
            paramtypez = {String.class, int.class, boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class})
    public static final class HandPatch {
        @SpirePostfixPatch
        public static void after(HandCardSelectScreen __instance, String msg, int amount,
                                 boolean anyNumber, boolean canPickZero, boolean forTransform,
                                 boolean forUpgrade, boolean upTo) {
            note("HAND", AbstractDungeon.player == null ? null : AbstractDungeon.player.hand);
        }
    }

    /**
     * The hand screen's four-argument {@code open} is a SEPARATE body (it calls {@code prep()} itself
     * instead of delegating), so it needs its own patch or half of the hand pickers go uncounted.
     */
    @SpirePatch(clz = HandCardSelectScreen.class, method = "open",
            paramtypez = {String.class, int.class, boolean.class, boolean.class})
    public static final class HandShortPatch {
        @SpirePostfixPatch
        public static void after(HandCardSelectScreen __instance, String msg, int amount,
                                 boolean anyNumber, boolean canPickZero) {
            note("HAND", AbstractDungeon.player == null ? null : AbstractDungeon.player.hand);
        }
    }
}
