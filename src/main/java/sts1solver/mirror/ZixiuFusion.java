package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;
import sts1solver.search.Action;
import sts1solver.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 字修 (zixiu) 合字 / 拆字, the two actions that are neither cards nor potions.
 *
 * <p>The mod's own UI is a hand picker that ends in {@code zixiu.ui.HandActions.executeFusion} /
 * {@code executeSplit}. Those write the result through {@code AbstractDungeon.effectList}, which a
 * sandbox never renders -- a card "added" that way would never reach the hand and the search would
 * plan from a board the game can never reach. The author therefore exposes
 * {@code zixiu.compat.PilotBridge}, whose {@code resolveFusion} / {@code resolveSplit} write the
 * hand DIRECTLY and run the same relic/power notifications as the UI. That is the pair used here.
 *
 * <p>Everything is reflective: no compile-time dependency on the mod, and a missing card, a renamed
 * class or an older author version simply answers "not available" instead of failing the search.
 *
 * <p>Three things must be mirrored before a simulated fuse/split is safe:
 * <ul>
 *   <li>{@code FusionTracker.splitsThisTurn} / {@code fusionsThisTurn} -- ordinary mod statics, the
 *       generated sweep already mirrors them;</li>
 *   <li>{@code FusionTracker.FRESH} (墨迹未干) -- a {@code static final} identity SET, which the
 *       scalar sweep by definition does not touch. It is listed in {@link Statics#MOD_CONTAINERS},
 *       because a sandbox that wrote into the live set would make the player's freshly fused card
 *       unsplittable for the rest of the real turn;</li>
 *   <li>{@code DeferredCardInsert.PENDING} -- the static queue a fused word waits in until the mouse
 *       is released. Left shared, every simulated fusion pushed a card into the LIVE queue and the
 *       live PostUpdateSubscriber emptied it into the player's hand (measured 4 -&gt; 112 cards after
 *       one solve).</li>
 * </ul>
 * {@link #isolated()} refuses to plan either action when one of those rows did not resolve.
 */
public final class ZixiuFusion {

    private static final String BRIDGE = "zixiu.compat.PilotBridge";
    private static final String CHARACTER = "zixiu.character.ZixiuCharacter";
    /** Owner#field keys that must be in the mirror before a simulated fuse/split is trustworthy. */
    private static final String[] REQUIRED_KEYS = {
            "zixiu.core.FusionTracker#FRESH",
            "zixiu.core.FusionTracker#splitsThisTurn",
            "zixiu.core.FusionTracker#fusionsThisTurn",
            // The deferred-insert queue: without this row a search pours its fused words straight
            // into the player's hand (4 -> 112 cards measured), so 合/拆 are not planned at all.
            "zixiu.ui.DeferredCardInsert#PENDING",
    };

    private static boolean loaded;
    private static Class<?> characterClass;
    private static Method canFuse;
    private static Method fusionResults;
    private static Method canSplit;
    private static Method splitPartNames;
    private static Method isFresh;
    private static Method splitsThisTurn;
    private static Method fusionsThisTurn;
    private static Method resolveFusion;
    private static Method resolveSplit;
    /** 连书 state (0.3.7), read straight off FusionTracker: the bridge does not expose it. */
    private static java.lang.reflect.Field chainField;
    private static java.lang.reflect.Field lastWordPartsField;
    /**
     * The author's read-only state getters (0.3.7+): chain, the relic gate, 沓's window/charges and
     * 言灵's pointer/refund. Optional -- older builds simply do not have them.
     */
    private static Method chainGetter;
    private static Method chainRelicGetter;
    private static Method taChargesGetter;
    private static Method taWindowGetter;
    private static Method yanlingIndexGetter;
    private static Method yanlingDiscountGetter;
    /** 因 uses the COMBAT fusion count, and the 拆合 rule (mode 1 / 2) changes what resolve* does. */
    private static Method fusionsCombatGetter;
    private static Method fusionModeGetter;
    /** 1.0.12+: tells 未 the solver is about to fill its grid; absent on older builds. */
    private static Method setSolverSelecting;
    /**
     * 1.0.13 state that the hand and piles do not show and that decides the REST of the turn:
     * 前's pair bookkeeping (relic fields), 行's "already reclaimed" marker (power field) and
     * 言灵's "fired this combat" one-shot (mod static). Optional on older builds.
     */
    private static Class<?> qianClass;
    private static java.lang.reflect.Field qianComponent;
    private static java.lang.reflect.Field qianWord;
    private static java.lang.reflect.Field qianDrawn;
    private static Class<?> xingClass;
    private static java.lang.reflect.Field xingSpent;
    private static java.lang.reflect.Field yanlingFired;
    private static Boolean isolated;

    private ZixiuFusion() {
    }

    private static boolean load() {
        if (loaded) {
            return characterClass != null;
        }
        loaded = true;
        try {
            Class<?> bridge = Class.forName(BRIDGE);
            characterClass = Class.forName(CHARACTER);
            canFuse = bridge.getMethod("canFuse", AbstractCard.class);
            fusionResults = bridge.getMethod("fusionResults", java.util.List.class);
            canSplit = bridge.getMethod("canSplit", AbstractCard.class);
            splitPartNames = bridge.getMethod("splitPartNames", AbstractCard.class);
            isFresh = bridge.getMethod("isFresh", AbstractCard.class);
            splitsThisTurn = bridge.getMethod("splitsThisTurn");
            fusionsThisTurn = bridge.getMethod("fusionsThisTurn");
            resolveFusion = bridge.getMethod("resolveFusion", java.util.List.class, String.class);
            resolveSplit = bridge.getMethod("resolveSplit", AbstractCard.class);
            // 连书 (0.3.7+) is OPTIONAL: older builds have neither field, and the bridge must stay
            // usable there. isolated() adds the mirror requirement only when the field exists.
            try {
                Class<?> tracker = Class.forName("zixiu.core.FusionTracker");
                chainField = tracker.getDeclaredField("chain");
                chainField.setAccessible(true);
                lastWordPartsField = tracker.getDeclaredField("lastWordParts");
                lastWordPartsField.setAccessible(true);
            } catch (Throwable ignored) {
                chainField = null;
                lastWordPartsField = null;
            }
            // The author's getters are preferred over the private fields; each is optional so an
            // older bridge still works through the field path.
            chainGetter = optional(bridge, "chain");
            chainRelicGetter = optional(bridge, "chainRelic");
            taChargesGetter = optional(bridge, "taCharges");
            taWindowGetter = optional(bridge, "taWindowOpen");
            yanlingIndexGetter = optional(bridge, "yanlingIndex");
            yanlingDiscountGetter = optional(bridge, "yanlingDiscountPending");
            fusionsCombatGetter = optional(bridge, "fusionsThisCombat");
            fusionModeGetter = optional(bridge, "fusionMode");
            // 1.0.12+: 未 takes a solver-filled grid as soon as it sees it (and closes the screen
            // itself) while this flag is up. Optional: 1.0.11 and older simply do not have it.
            setSolverSelecting = optional(bridge, "setSolverSelecting", boolean.class);
            // 1.0.13: 前's pair bookkeeping lives on the relic, 行's marker on the power, and 言灵's
            // combat one-shot in a mod static. Each is optional; the digest section below simply
            // reads zeros on a build that does not have them.
            try {
                qianClass = Class.forName("zixiu.relics.Qian");
                qianComponent = declared(qianClass, "playedComponent");
                qianWord = declared(qianClass, "playedWord");
                qianDrawn = declared(qianClass, "drawnThisTurn");
            } catch (Throwable ignored) {
                qianClass = null;
                qianComponent = null;
                qianWord = null;
                qianDrawn = null;
            }
            try {
                xingClass = Class.forName("zixiu.powers.XingPower");
                xingSpent = declared(xingClass, "spent");
            } catch (Throwable ignored) {
                xingClass = null;
                xingSpent = null;
            }
            try {
                yanlingFired = declared(Class.forName("zixiu.powers.YanlingPower"),
                        "triggeredThisCombat");
            } catch (Throwable ignored) {
                yanlingFired = null;
            }
            // The ambiguous case (口+木 -> 呆/杏/困/束) is NOT driven through the mod's chooser:
            // executeFusion consumes the materials and hands the result to ChooseWordAction, whose
            // delivery is asynchronous (DeferredCardInsert, a BaseMod PostUpdate subscriber, or a
            // ShowCardAndAddToHandEffect) -- and a sandbox runs neither hook, so the word would
            // vanish. Each glyph is planned as its own fuse action instead; see Legal.addFusions.
            Log.info("zixiu: fusion bridge ready");
        } catch (Throwable t) {
            reset();
            Log.warn("zixiu: fusion bridge unavailable (" + t + ")");
        }
        return characterClass != null;
    }

    private static void reset() {
        characterClass = null;
        canFuse = null;
        fusionResults = null;
        canSplit = null;
        splitPartNames = null;
        isFresh = null;
        splitsThisTurn = null;
        fusionsThisTurn = null;
        resolveFusion = null;
        resolveSplit = null;
        chainField = null;
        lastWordPartsField = null;
        chainGetter = null;
        chainRelicGetter = null;
        taChargesGetter = null;
        taWindowGetter = null;
        yanlingIndexGetter = null;
        yanlingDiscountGetter = null;
        fusionsCombatGetter = null;
        fusionModeGetter = null;
        setSolverSelecting = null;
        qianClass = null;
        qianComponent = null;
        qianWord = null;
        qianDrawn = null;
        xingClass = null;
        xingSpent = null;
        yanlingFired = null;
    }

    /** A static no-arg getter the bridge may or may not have; null when absent. */
    private static Method optional(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The same for a static method with arguments, e.g. {@code setSolverSelecting(boolean)}. */
    private static Method optional(Class<?> owner, String name, Class<?>... args) {
        try {
            return owner.getMethod(name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A declared field, made readable; null when this build does not have it. */
    private static java.lang.reflect.Field declared(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when the mod is loaded and the live player is 字修 (the bridge is character-locked). */
    public static boolean available() {
        if (!load()) {
            return false;
        }
        try {
            return characterClass.isInstance(AbstractDungeon.player);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when 合/拆 may be planned: the bridge exists, the player is 字修, and the three pieces of
     * mod state are actually sandboxed. Without the last condition a simulated split would clear the
     * LIVE fresh set, so the action must disappear instead of being planned unsafely.
     */
    public static boolean enabled() {
        return available() && isolated();
    }

    /**
     * 1.0.12 handshake for 未 (Wei): raise the flag BEFORE filling its draw-pile grid, drop it when
     * the answer is over. While it is up, {@code PlayFromDrawPileAction} takes the filled selection
     * itself and calls {@code closeCurrentScreen()} instead of waiting for {@code screen != GRID}.
     *
     * <p>Without it the two sides deadlock: the action waited for the screen to close, the solver
     * waited for the action to consume the pick, and the player could not click out of the grid by
     * hand either. The mod also clears the flag when it consumes, so a late clear here is harmless;
     * older builds (1.0.11-) have no such method and this is a no-op.
     */
    public static void solverSelecting(boolean on) {
        if (!available() || setSolverSelecting == null) {
            return;
        }
        try {
            setSolverSelecting.invoke(null, on);
        } catch (Throwable t) {
            Log.warn("zixiu: setSolverSelecting(" + on + ") failed: " + t);
        }
    }

    /** Whether the build in this install has the 1.0.12 selection handshake (diagnostics). */
    public static boolean hasSolverSelecting() {
        return load() && setSolverSelecting != null;
    }

    private static boolean isolated() {
        if (isolated == null) {
            boolean ok = true;
            String missing = "";
            try {
                List<String> keys = Statics.mirrorKeys();
                for (int i = 0; i < REQUIRED_KEYS.length; i++) {
                    if (!keys.contains(REQUIRED_KEYS[i])) {
                        ok = false;
                        missing += (missing.isEmpty() ? "" : ", ") + REQUIRED_KEYS[i];
                    }
                }
                // 连书 (0.3.7+): present only in newer builds. When the field exists it MUST be
                // mirrored -- chain decides the next word's damage (2 x chain) -- but an older mod
                // without it simply skips the requirement instead of losing 合/拆.
                if (chainField != null && !keys.contains("zixiu.core.FusionTracker#chain")) {
                    ok = false;
                    missing += (missing.isEmpty() ? "" : ", ") + "zixiu.core.FusionTracker#chain";
                }
                if (lastWordPartsField != null
                        && !keys.contains("zixiu.core.FusionTracker#lastWordParts")) {
                    ok = false;
                    missing += (missing.isEmpty() ? "" : ", ")
                            + "zixiu.core.FusionTracker#lastWordParts";
                }
                // 沓 / 言灵 (0.3.7+): the window and the refund debt both change what a simulated
                // attack is worth, so neither may stay live-shared.
                if (hasTaYanling()) {
                    if (!keys.contains("zixiu.powers.TaPower#windowOpen")) {
                        ok = false;
                        missing += (missing.isEmpty() ? "" : ", ") + "zixiu.powers.TaPower#windowOpen";
                    }
                    if (!keys.contains("zixiu.powers.YanlingPower#discountPending")) {
                        ok = false;
                        missing += (missing.isEmpty() ? "" : ", ")
                                + "zixiu.powers.YanlingPower#discountPending";
                    }
                }
                // 因's combat counter and the 拆合 rule (1.0.0): both are plain statics, but a
                // sandbox that wrote them would change LIVE damage and turn real fuse/split into
                // the other mode.
                if (fusionsCombatGetter != null
                        && !keys.contains("zixiu.core.FusionTracker#fusionsThisCombat")) {
                    ok = false;
                    missing += (missing.isEmpty() ? "" : ", ")
                            + "zixiu.core.FusionTracker#fusionsThisCombat";
                }
                if (fusionModeGetter != null
                        && !keys.contains("zixiu.core.ZixiuConfig#fusionMode")) {
                    ok = false;
                    missing += (missing.isEmpty() ? "" : ", ") + "zixiu.core.ZixiuConfig#fusionMode";
                }
            } catch (Throwable t) {
                ok = false;
                missing = String.valueOf(t);
            }
            if (ok) {
                Log.info("zixiu: fusion state is in the mirror (fresh set + turn counters)");
            } else {
                Log.warn("zixiu: fusion state is NOT in the mirror (" + missing
                        + "); 合/拆 are not planned");
            }
            isolated = Boolean.valueOf(ok);
        }
        return isolated.booleanValue();
    }

    /** Test hook: re-ask the mirror whether the state is isolated. */
    public static void resetForTest() {
        loaded = false;
        isolated = null;
        reset();
    }

    // ------------------------------------------------------------------ the author's contract

    /** True when this card can be a fusion material. */
    public static boolean canFuse(AbstractCard card) {
        if (!load() || card == null) {
            return false;
        }
        try {
            return ((Boolean) canFuse.invoke(null, card)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The glyphs these materials can produce (already filtered to implemented cards), never null. */
    public static List<String> fusionResults(List<AbstractCard> cards) {
        List<String> out = new ArrayList<String>();
        if (!load() || cards == null || cards.size() < 2) {
            return out;
        }
        try {
            Object v = fusionResults.invoke(null, cards);
            if (v instanceof List) {
                List<?> raw = (List<?>) v;
                for (int i = 0; i < raw.size(); i++) {
                    if (raw.get(i) instanceof String) {
                        out.add((String) raw.get(i));
                    }
                }
            }
        } catch (Throwable t) {
            Log.warnEvery("zixiu-fuse-results", 10000L, "zixiu: fusionResults failed: " + t);
        }
        return out;
    }

    /** True when this card can be split right now (the mod's own check, 墨迹未干 included). */
    public static boolean canSplit(AbstractCard card) {
        if (!load() || card == null) {
            return false;
        }
        try {
            return ((Boolean) canSplit.invoke(null, card)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The parts a split produces (enum names such as REN / KOU), empty when it cannot be split. */
    public static List<String> splitPartNames(AbstractCard card) {
        List<String> out = new ArrayList<String>();
        if (!load() || card == null) {
            return out;
        }
        try {
            Object v = splitPartNames.invoke(null, card);
            if (v instanceof List) {
                List<?> raw = (List<?>) v;
                for (int i = 0; i < raw.size(); i++) {
                    if (raw.get(i) instanceof String) {
                        out.add((String) raw.get(i));
                    }
                }
            }
        } catch (Throwable t) {
            Log.warnEvery("zixiu-split-parts", 10000L, "zixiu: splitPartNames failed: " + t);
        }
        return out;
    }

    /** 墨迹未干: fused this turn, so it may not be split until the turn ends. */
    public static boolean isFresh(AbstractCard card) {
        if (!load() || card == null) {
            return false;
        }
        try {
            return ((Boolean) isFresh.invoke(null, card)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int splitsThisTurn() {
        if (!load()) {
            return -1;
        }
        try {
            return ((Number) splitsThisTurn.invoke(null)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 连书: how many consecutive words shared a part with the previous one. Every enemy takes
     * {@code 2 x chain}, so two boards that differ only here score differently -- it belongs in the
     * transposition key. -1 when the mod is absent.
     */
    public static int chain() {
        if (!load()) {
            return -1;
        }
        try {
            if (chainGetter != null) {
                return ((Number) chainGetter.invoke(null)).intValue();
            }
            return chainField == null ? -1 : chainField.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** True when the player holds 连书 (the relic that lets the chain actually build). */
    public static boolean chainRelic() {
        return booleanOf(chainRelicGetter);
    }

    /** 沓's remaining doubling charges (the power's amount; 0 without it). */
    public static int taCharges() {
        if (!load() || taChargesGetter == null) {
            return 0;
        }
        try {
            return ((Number) taChargesGetter.invoke(null)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 沓's doubling window: true while an attack is being resolved with a charge. */
    public static boolean taWindowOpen() {
        return booleanOf(taWindowGetter);
    }

    /** 言灵's pointer 1..9 (the power's amount), or 0 when the power is absent. */
    public static int yanlingIndex() {
        if (!load() || yanlingIndexGetter == null) {
            return 0;
        }
        try {
            return ((Number) yanlingIndexGetter.invoke(null)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 言灵「在」's pending refund: the next card costing 1+ gives 1 energy back. */
    public static boolean yanlingDiscountPending() {
        return booleanOf(yanlingDiscountGetter);
    }

    /** True when this build exposes the 0.3.7 沓/言灵 statics (they need mirroring). */
    public static boolean hasTaYanling() {
        return load() && taWindowGetter != null && yanlingDiscountGetter != null;
    }

    /**
     * 因's damage base: how many words were fused in THIS COMBAT (not this turn). -1 when the
     * build does not have it.
     */
    public static int fusionsThisCombat() {
        if (!load() || fusionsCombatGetter == null) {
            return -1;
        }
        try {
            return ((Number) fusionsCombatGetter.invoke(null)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 拆合规则: 1 = results are ethereal / materials stay; 2 = results stay / materials burn.
     * resolve* already follows it, so the sandbox only has to OWN the value, not branch on it.
     * -1 when the build has no such setting.
     */
    public static int fusionMode() {
        if (!load() || fusionModeGetter == null) {
            return -1;
        }
        try {
            return ((Number) fusionModeGetter.invoke(null)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean booleanOf(Method getter) {
        if (!load() || getter == null) {
            return false;
        }
        try {
            Object v = getter.invoke(null);
            return v instanceof Boolean && ((Boolean) v).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The parts of the LAST word played this turn (the set 连书 compares against), or "" when unset.
     *
     * <p>Reading it needs the field, not a method: the mod replaces the whole set on every word, so
     * the set itself is state that must keep two boards apart.
     */
    public static String lastPartsSignature() {
        if (!load() || lastWordPartsField == null) {
            return "";
        }
        try {
            Object v = lastWordPartsField.get(null);
            if (!(v instanceof java.util.Collection)) {
                return "";
            }
            java.util.Collection<?> parts = (java.util.Collection<?>) v;
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (p == null) {
                    continue;
                }
                sb.append(sb.length() == 0 ? "" : ".").append(String.valueOf(p));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static int fusionsThisTurn() {
        if (!load()) {
            return -1;
        }
        try {
            return ((Number) fusionsThisTurn.invoke(null)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ execution

    /**
     * Executes one fuse step in whatever world is installed.
     *
     * <p>With a glyph the author's synchronous {@code resolveFusion} runs: materials to the exhaust
     * pile, the word straight into the hand, relic/power notifications included. Without one the
     * materials have several results and the mod's own chooser must pick, so the call ends on a
     * card-reward screen and the plan's next step is a {@code sel}.
     *
     * @return false when the materials are no longer the cards the plan named (a stale line)
     */
    public static boolean fuse(Action a) {
        if (a == null || !load()) {
            return false;
        }
        List<AbstractCard> cards = resolve(a.materials, a.materialUuids, a.materialPrints);
        if (cards == null || cards.size() < 2) {
            return false;
        }
        for (int i = 0; i < cards.size(); i++) {
            if (!canFuse(cards.get(i))) {
                return false;
            }
        }
        try {
            // Every planned fuse step names its glyph. A set with several results is planned as one
            // action per glyph, so the search still chooses the word -- without the mod's async
            // chooser delivery, which a sandbox never runs.
            if (a.glyph == null || a.glyph.isEmpty()) {
                return false;
            }
            if (!fusionResults(cards).contains(a.glyph)) {
                return false;
            }
            resolveFusion.invoke(null, cards, a.glyph);
            return true;
        } catch (Throwable t) {
            Log.warn("zixiu: fuse failed: " + t);
            return false;
        }
    }

    /**
     * Executes one split step in whatever world is installed.
     *
     * <p>The card is resolved among the copies that carry the planned identity, preferring one the
     * mod still allows: 墨迹未干 is NOT part of a print, so an old 休 and a 休 fused this turn look
     * identical to {@link CardPrint}. Taking "the first matching copy" could therefore pick the
     * fresh one and refuse a plan step the game would accept.
     */
    public static boolean split(Action a) {
        if (a == null || !load()) {
            return false;
        }
        AbstractCard card = resolveSplittable(a);
        if (card == null) {
            return false;
        }
        try {
            resolveSplit.invoke(null, card);
            return true;
        } catch (Throwable t) {
            Log.warn("zixiu: split failed: " + t);
            return false;
        }
    }

    /**
     * The card a split step means: the strongest identity tier that has ANY match decides, and within
     * it the first copy the mod still lets us split. A tier that matched but has no splittable copy
     * refuses instead of falling through to a different card.
     */
    private static AbstractCard resolveSplittable(Action a) {
        try {
            if (AbstractDungeon.player == null || AbstractDungeon.player.hand == null) {
                return null;
            }
            List<AbstractCard> hand = AbstractDungeon.player.hand.group;
            String print = a.cardPrint == null ? "" : a.cardPrint;
            String uuid = a.cardUuid == null ? "" : a.cardUuid;
            String id = a.cardId == null ? "" : a.cardId;
            AbstractCard[] pick = new AbstractCard[3];
            boolean[] hit = new boolean[3];
            for (int i = 0; i < hand.size(); i++) {
                AbstractCard c = hand.get(i);
                if (c == null) {
                    continue;
                }
                int tier = -1;
                if (!print.isEmpty() && print.equals(CardPrint.of(c))) {
                    tier = 0;
                } else if (!uuid.isEmpty() && c.uuid != null && uuid.equals(c.uuid.toString())) {
                    tier = 1;
                } else if (!id.isEmpty() && id.equals(c.cardID)) {
                    tier = 2;
                }
                if (tier < 0) {
                    continue;
                }
                hit[tier] = true;
                if (pick[tier] == null && canSplit(c)) {
                    pick[tier] = c;
                }
            }
            for (int t = 0; t < 3; t++) {
                if (pick[t] != null) {
                    return pick[t];
                }
                if (hit[t]) {
                    return null;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Finds the physical cards a step names, by print first (the identity that crosses forks), then
     * uuid, then id. Never returns the same card twice; null when any of them is gone.
     */
    private static List<AbstractCard> resolve(String ids, String uuids, String prints) {
        try {
            if (AbstractDungeon.player == null || AbstractDungeon.player.hand == null) {
                return null;
            }
            List<AbstractCard> hand = AbstractDungeon.player.hand.group;
            String[] idList = split(ids);
            String[] uuidList = split(uuids);
            String[] printList = split(prints);
            int size = Math.max(idList.length, Math.max(uuidList.length, printList.length));
            List<AbstractCard> out = new ArrayList<AbstractCard>(size);
            boolean[] used = new boolean[hand.size()];
            for (int i = 0; i < size; i++) {
                String id = at(idList, i);
                String uuid = at(uuidList, i);
                String print = at(printList, i);
                int at = find(hand, used, print, uuid, id);
                if (at < 0) {
                    return null;
                }
                used[at] = true;
                out.add(hand.get(at));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int find(List<AbstractCard> hand, boolean[] used, String print, String uuid,
                            String id) {
        if (print != null && !print.isEmpty()) {
            for (int i = 0; i < hand.size(); i++) {
                AbstractCard c = hand.get(i);
                if (!used[i] && c != null && print.equals(CardPrint.of(c))) {
                    return i;
                }
            }
        }
        if (uuid != null && !uuid.isEmpty()) {
            for (int i = 0; i < hand.size(); i++) {
                AbstractCard c = hand.get(i);
                if (!used[i] && c != null && c.uuid != null && uuid.equals(c.uuid.toString())) {
                    return i;
                }
            }
        }
        if (id != null && !id.isEmpty()) {
            for (int i = 0; i < hand.size(); i++) {
                AbstractCard c = hand.get(i);
                if (!used[i] && c != null && id.equals(c.cardID)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String[] split(String joined) {
        return joined == null || joined.isEmpty() ? new String[0] : joined.split(",", -1);
    }

    private static String at(String[] values, int i) {
        return i < values.length ? values[i] : "";
    }

    // ------------------------------------------------------------------ digest

    /**
     * 1.0.13 前 (Qian): which half of the "one component + one formed word" pair this turn already
     * has. Three bits -- played a component card, played a formed word, already drew. The relic is a
     * COPY in the sandbox, so the bits belong to the position; "q---" when the relic is absent (or
     * this build predates 1.0.13), which keeps old positions mergeable among themselves.
     */
    private static String qianState() {
        if (qianClass == null || qianComponent == null || qianWord == null || qianDrawn == null) {
            return "q---";
        }
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null || p.relics == null) {
            return "q---";
        }
        for (int i = 0; i < p.relics.size(); i++) {
            Object r = p.relics.get(i);
            if (r == null || r.getClass() != qianClass) {
                continue;
            }
            try {
                return "q" + (qianComponent.getBoolean(r) ? '1' : '0')
                        + (qianWord.getBoolean(r) ? '1' : '0')
                        + (qianDrawn.getBoolean(r) ? '1' : '0');
            } catch (Throwable t) {
                return "q---";
            }
        }
        return "q---";
    }

    /** 1.0.13 行: true once the +敏捷 has been reclaimed and the power is on its way out. */
    private static boolean xingSpent() {
        if (xingClass == null || xingSpent == null) {
            return false;
        }
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null || p.powers == null) {
            return false;
        }
        for (int i = 0; i < p.powers.size(); i++) {
            AbstractPower pw = p.powers.get(i);
            if (pw != null && pw.getClass() == xingClass) {
                try {
                    return xingSpent.getBoolean(pw);
                } catch (Throwable t) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 1.0.13 言灵: whether the "once per combat" trigger (only while 九字 is incomplete) already
     * fired. A mod static, so the mirror isolates it; it goes in the key because a position that
     * still owes its one trigger and one that spent it have different futures.
     */
    private static boolean yanlingFiredThisCombat() {
        if (yanlingFired == null) {
            return false;
        }
        try {
            return yanlingFired.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 合/拆 state for the transposition key: the two turn counters plus which hand cards are
     * 墨迹未干, or "" when the mod is not on this character (byte-identical digest there).
     *
     * <p>Without it two boards differing only in "this 木 was fused this turn" (same hand, same HP,
     * different legal splits) shared one key, and the search dropped the line that could still split.
     */
    public static String digest() {
        if (!available()) {
            return "";
        }
        try {
            int fused = fusionsThisTurn();
            int splitCount = splitsThisTurn();
            if (fused < 0 || splitCount < 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder(16);
            sb.append(fused).append('/').append(splitCount).append('/');
            // 连书 (0.3.7+): the next word deals 2 x chain, and the chain depends on which parts the
            // LAST word had. Two boards that differ only there score differently, so both go in.
            int chainNow = chain();
            if (chainNow >= 0) {
                sb.append(chainNow).append('/').append(lastPartsSignature()).append('/');
                // 沓's doubling window, 言灵「在」's refund debt, and the relic that arms the chain.
                // All three change what the NEXT card is worth; '-' means off / absent.
                sb.append(taWindowOpen() ? 'T' : '-')
                        .append(yanlingDiscountPending() ? 'E' : '-')
                        .append(chainRelic() ? 'L' : '-')
                        .append('/');
                // 因's combat counter (damage base) and the 拆合 rule (1 / 2).
                int combatFusions = fusionsThisCombat();
                if (combatFusions >= 0) {
                    sb.append(combatFusions).append('/').append(fusionMode()).append('/');
                }
            }
            // 1.0.13: per-turn relic/power state and the 言灵 one-shot. None of it shows up in the
            // piles; two boards that differ only here play out differently for the rest of the turn.
            sb.append(qianState())
                    .append(yanlingFiredThisCombat() ? 'Y' : '-')
                    .append(xingSpent() ? 'X' : '-')
                    .append('/');
            List<AbstractCard> hand = AbstractDungeon.player.hand.group;
            for (int i = 0; i < hand.size(); i++) {
                sb.append(isFresh(hand.get(i)) ? '1' : '0');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
