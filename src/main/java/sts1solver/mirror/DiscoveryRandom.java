package sts1solver.mirror;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.actions.unique.DiscoveryAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import java.util.ArrayList;

/**
 * B165 (user m17933/m17993): one open-screen action that draws its random cards ONCE per activation
 * instead of once per engine frame.
 *
 * <p>What goes wrong without this. {@code DiscoveryAction.update()} generates its three choices at
 * the TOP of every update, then only USES that list when {@code duration == ACTION_DUR_FAST}
 * (the frame that opens the card-reward screen). Every later update throws the freshly generated
 * list away and keeps showing the three cards from the first frame -- but the draws already
 * happened. The action's duration is {@link Settings#ACTION_DUR_FAST} (0.25 s), so it is ticked
 * once per frame for ~15-16 frames at 60 fps, and every tick costs 3-6 draws on
 * {@code AbstractDungeon.cardRandomRng}. The live game does not usually tick an action while its
 * screen is open, but OUR pump does (it calls {@code actionManager.update()} to let the action take
 * the answer), so live play spent ~42 draws the sandbox never spent: from that point on the two
 * random streams are different streams, and every later "random" card/potion/monster diverges. That
 * is the whole 42-draw offset reported against the power-potion step: the panel is right about the
 * plan, the sandbox is right about its own random stream, and the real fight is on another one.
 *
 * <p>The fix is NOT to model frames and not to branch on frame rate (that branch could never be
 * redeemed live). It makes the redundant draws not happen: only the frame that opens the screen
 * generates, later frames return an empty list that the method then discards exactly as before. All
 * three players of this rule -- live autopilot, a human hand, the sandbox -- run this same patched
 * class, so they share one random stream. Relative to unmodded Slay the Spire the stream changes
 * (it stops wasting draws); that is the deliberate, documented cost of the fix, and it is why the
 * panel marks the steps that used it (see {@code Action#rngNormalized}).
 *
 * <p>Stateless on purpose: a cached list on the action instance would be shared by the live action
 * and the sandbox clone of it (World forks by cloning the action manager's objects too), and the
 * second reader would answer from the first reader's draw. The {@code duration} test is the whole
 * state and it lives in the engine's own field.
 *
 * <p>Cost: two {@code duration} reads per generated list (only on the opening frame), zero on every
 * other frame. No allocation beyond the empty list that a discarding caller was already throwing
 * away. Risk: if a mod's subclass of DiscoveryAction relied on the per-frame re-roll (nothing in the
 * installed mod set does; the list is never read again after the opening frame), it would now see an
 * empty list on those frames -- the same discard path, so no behaviour change is reachable. Rollback
 * is deleting this class' patch methods; the panel flag is cosmetic and can stay.
 */
public final class DiscoveryRandom {

    private DiscoveryRandom() {
    }

    /**
     * How many times the patched generation actually happened since the JVM started. The search
     * diffs it around one action to mark the plan step, and the gate uses it to prove that a second
     * frame draws nothing.
     */
    private static volatile long generations;

    public static long count() {
        return generations;
    }

    public static void resetForTest() {
        generations = 0L;
    }

    /** The opening frame generates; every later frame of the same action gets the discarded-empty list. */
    private static SpireReturn<ArrayList<AbstractCard>> gate(DiscoveryAction action) {
        Object duration = action == null ? null
                : sts1solver.reflect.Reflect.get(DiscoveryAction.class, "duration", action);
        if (!(duration instanceof Float) || ((Float) duration).floatValue() != Settings.ACTION_DUR_FAST) {
            return SpireReturn.Return(new ArrayList<AbstractCard>());
        }
        ++generations;
        return SpireReturn.Continue();
    }

    /** {@code generateCardChoices(CardType)}: the typed branch of {@code DiscoveryAction.update()}. */
    @SpirePatch(clz = DiscoveryAction.class, method = "generateCardChoices",
            paramtypez = {AbstractCard.CardType.class})
    public static final class TypedPatch {
        @SpirePrefixPatch
        public static SpireReturn<ArrayList<AbstractCard>> before(DiscoveryAction __instance,
                                                                  AbstractCard.CardType type) {
            return gate(__instance);
        }
    }

    /** {@code generateColorlessCardChoices()}: the colorless branch (PowerPotion's real path). */
    @SpirePatch(clz = DiscoveryAction.class, method = "generateColorlessCardChoices")
    public static final class ColorlessPatch {
        @SpirePrefixPatch
        public static SpireReturn<ArrayList<AbstractCard>> before(DiscoveryAction __instance) {
            return gate(__instance);
        }
    }
}
