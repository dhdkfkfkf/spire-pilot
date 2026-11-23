package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
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
 * <p>Two things must be mirrored before a simulated fuse/split is safe:
 * <ul>
 *   <li>{@code FusionTracker.splitsThisTurn} / {@code fusionsThisTurn} -- ordinary mod statics, the
 *       generated sweep already mirrors them;</li>
 *   <li>{@code FusionTracker.FRESH} (墨迹未干) -- a {@code static final} identity SET, which the
 *       scalar sweep by definition does not touch. It is listed in {@link Statics#MOD_CONTAINERS},
 *       because a sandbox that wrote into the live set would make the player's freshly fused card
 *       unsplittable for the rest of the real turn. {@link #isolated()} refuses to plan either
 *       action when that row did not resolve.</li>
 * </ul>
 */
public final class ZixiuFusion {

    private static final String BRIDGE = "zixiu.compat.PilotBridge";
    private static final String HAND_ACTIONS = "zixiu.ui.HandActions";
    private static final String CHARACTER = "zixiu.character.ZixiuCharacter";
    /** Owner#field keys that must be in the mirror before a simulated fuse/split is trustworthy. */
    private static final String[] REQUIRED_KEYS = {
            "zixiu.core.FusionTracker#FRESH",
            "zixiu.core.FusionTracker#splitsThisTurn",
            "zixiu.core.FusionTracker#fusionsThisTurn",
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
    private static Method executeFusion;
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
            // The ambiguous case (口+木 -> 呆/杏/困/束) has no single glyph to resolve with. The
            // mod's own entry point consumes the materials and opens its word chooser, which is a
            // card-reward screen the executor already knows how to answer as a separate step.
            executeFusion = Class.forName(HAND_ACTIONS).getMethod("executeFusion", java.util.List.class);
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
        executeFusion = null;
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
            if (a.glyph != null && !a.glyph.isEmpty()) {
                if (!fusionResults(cards).contains(a.glyph)) {
                    return false;
                }
                resolveFusion.invoke(null, cards, a.glyph);
                return true;
            }
            executeFusion.invoke(null, cards);
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
