package sts1solver.search;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import sts1solver.drive.Driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Candidate actions at a decision point, read from the INSTALLED world.
 *
 * <p>Legality is delegated to the game: {@code AbstractCard.canUse} already knows about energy,
 * target requirements and every per-card restriction. Re-implementing those checks here is how a
 * solver starts recommending plays the game refuses.
 */
public final class Legal {

    private Legal() {
    }

    public static List<Action> actions() {
        List<Action> out = new ArrayList<Action>();
        try {
            // B125 (m03996 #2): a decided fight has no decisions. The game itself skips the turn
            // increment in that state (getNextAction: turnHasEnded && areMonstersBasicallyDead),
            // so an end turn there is a no-op that still LOOKS like a branch; the search used to
            // chain those and the panel showed several end turns inside one turn row.
            if (Driver.combatOver()) {
                return out;
            }
            List<AbstractMonster> alive = Driver.aliveMonsters();
            List<AbstractCard> hand = AbstractDungeon.player.hand.group;
            Set<String> seen = new LinkedHashSet<String>();
            for (int i = 0; i < hand.size(); i++) {
                AbstractCard c = hand.get(i);
                if (c == null) {
                    continue;
                }
                // Two copies of the same card in hand are interchangeable for the SEARCH: playing
                // either leaves the same state, and the digest would collapse them anyway -- after
                // paying for a fork and an engine drain. Collapsing them here removes that duplicate
                // work; cards that only LOOK identical (upgrade level, grown numbers) keep a distinct
                // signature.
                // The dedupe key is the card's full print, not a hand-rolled tuple: the old one
                // omitted `misc`, so two copies that differ only in a counter (Blood for Blood's
                // cost, Rampage's damage, most mod cards) collapsed into ONE branch and the search
                // never even considered the other copy. Two cards with the same print really are
                // interchangeable for the search, so collapsing them is still correct.
                String sig = sts1solver.mirror.CardPrint.of(c);
                if (Driver.needsEnemyTarget(c)) {
                    // m16717-2: three Sentries must not all read "-> 哨卫". The rank is display only;
                    // the action keeps its target index, which is what the engine resolves.
                    List<AbstractMonster> whole = MonsterLabels.battle();
                    for (int k = 0; k < alive.size(); k++) {
                        if (Driver.playerCanUse(c, AbstractDungeon.player, alive.get(k))) {
                            int ti = indexOf(alive.get(k));
                            String key = sig + ">" + ti;
                            if (seen.add(key)) {
                                String targetName = MonsterLabels.of(alive.get(k), whole);
                                out.add(Action.card(i, ti, Driver.energyFor(c, AbstractDungeon.player),
                                        c.name + " -> " + targetName, c.name,
                                        targetName, c.cardID, uuidOf(c))
                                        .withCardPrint(sts1solver.mirror.CardPrint.of(c)));
                            }
                        }
                    }
                } else if (Driver.playerCanUse(c, AbstractDungeon.player, null)) {
                    String key = sig + ">self";
                    if (seen.add(key)) {
                        out.add(Action.card(i, -1, Driver.energyFor(c, AbstractDungeon.player),
                                c.name, c.name, "", c.cardID, uuidOf(c))
                                .withCardPrint(sts1solver.mirror.CardPrint.of(c)));
                    }
                }
            }
            // Potions: worth a branch only when the fight is still going and the slot holds a real
            // potion (PotionSlot is the empty placeholder).
            //
            // The whole bar can be switched off (settings.txt search.potions=ban, P6-12): the same
            // fight is then solved twice -- with and without the belt -- which is the only way to see
            // what the potions are actually worth in the plan.
            for (int s = 0; sts1solver.search.Limits.potionsOffered()
                    && s < AbstractDungeon.player.potions.size(); s++) {
                AbstractPotion p = AbstractDungeon.player.potions.get(s);
                if (p == null || p instanceof PotionSlot) {
                    continue;
                }
                // The real UI asks canUse() before it accepts a potion click (PotionBook), and
                // drinking from code skips that pop-up. A potion the game refuses -- Fairy in a
                // Bottle (always), Smoke Bomb against a boss, anything after the turn ended -- must
                // therefore not be a branch at all.
                if (!PotionBook.usable(p)) {
                    continue;
                }
                // Escape is a POLICY, not a potion that happens to be in the belt: when the policy
                // denies leaving, the Smoke Bomb must not even be enumerated.
                if ("SmokeBomb".equals(p.ID) && !EscapePolicy.offersEscape()) {
                    continue;
                }
                // A potion that needs a target is a DECISION, not a constant: every living monster
                // is a candidate, exactly like a card's target. (The reference solver enumerates all
                // targets and all choices rather than defaulting to the first enemy.)
                // m16202-4: the panel shows the potion the way the game names it ("火焰药水"), not
                // "...[Fire Potion]". The engine id travels in the action's id field instead, which
                // is what prices a finished plan.
                String label = p.name;
                if (p.targetRequired && !alive.isEmpty()) {
                    List<AbstractMonster> whole = MonsterLabels.battle();
                    for (int k = 0; k < alive.size(); k++) {
                        int ti = indexOf(alive.get(k));
                        out.add(Action.potion(s, ti, label + "::" + MonsterLabels.of(alive.get(k), whole),
                                p.ID));
                    }
                } else {
                    out.add(Action.potion(s, -1, label + "::", p.ID));
                }
            }
            out.add(Action.endTurn());
        } catch (Throwable t) {
            // An unreadable decision point yields no candidates; the caller treats that as terminal.
        }
        return out;
    }

    /**
     * B162 (user m16041): the decision to make when a selection screen is ALREADY waiting.
     *
     * <p>Every selection screen used to be a sub-choice of the action that opened it, so the search
     * could only answer screens its own line had just opened. The player can be looking at a screen
     * the search never opened -- a Gambling Chip on turn one, a relic or a mod card that asks before
     * the first action -- and pressing 计算 there answered nothing at all ("a screen is open"). The
     * answer is a real decision, so it is enumerated like one: every option the engine offers becomes
     * a step, and the state after each answer is the state the search continues from.
     *
     * <p>The options come from {@link Driver#selectionOptions(int)}, the same generator the in-step
     * walk uses, so a "discard any number" screen offers every set here for the same reason it does
     * there (B161).
     */
    public static List<Action> selectionActions() {
        List<Action> out = new ArrayList<Action>();
        Driver.SelectionKind kind = Driver.selectionKind();
        if (kind == Driver.SelectionKind.NONE) {
            return out;
        }
        int cap = Math.max(1, Limits.choiceBranches(kind, 1));
        // B163 (user m16041): standing in front of a hand picker the player can also REORDER -- click a
        // card, click it again, and it goes to the END of the hand. The order is engine state (唐
        // plays a uniformly random hand card, end-of-turn discards run in hand order), so the search
        // gets a few such answers next to the plain ones instead of assuming order never matters.
        // Bounded on purpose: an order has too many permutations to enumerate, and the plain answers
        // are the ones that usually win.
        int variants = kind == Driver.SelectionKind.HAND
                ? Math.min(REORDER_BRANCHES, Math.max(0, cap / 4)) : 0;
        List<List<AbstractCard>> options = Driver.selectionOptions(Math.max(1, cap - variants));
        for (int i = 0; i < options.size(); i++) {
            List<AbstractCard> pick = options.get(i);
            out.add(Action.selection(Driver.namesOf(pick), Driver.displayNamesOf(pick),
                    Driver.uuidsOf(pick), Driver.printsOf(pick), kind.name()));
        }
        if (variants > 0) {
            addReorderVariants(out, options, variants, kind);
        }
        return out;
    }

    /** B163: how many "move one card to the back" answers a hand picker gets, at most. */
    private static final int REORDER_BRANCHES = 4;

    /**
     * B163 (user m16041): one reorder answer per plain answer, moving the most valuable card that
     * answer does NOT pick to the end of the hand.
     *
     * <p>Moving a card the answer keeps would change the answer itself, so those are skipped. The
     * cap is spent across answers (one variant each) rather than exhausting the first answer, so both
     * "keep nothing" and "keep the best k" get a reorder sibling.
     */
    private static void addReorderVariants(List<Action> out, List<List<AbstractCard>> options,
                                          int variants, Driver.SelectionKind kind) {
        List<AbstractCard> offers = new ArrayList<AbstractCard>(Driver.selectionCandidates());
        Collections.sort(offers, new Comparator<AbstractCard>() {
            public int compare(AbstractCard a, AbstractCard b) {
                return Driver.orderValue(b) - Driver.orderValue(a);
            }
        });
        int added = 0;
        for (int i = 0; i < options.size() && added < variants; i++) {
            List<AbstractCard> pick = options.get(i);
            for (int j = 0; j < offers.size(); j++) {
                AbstractCard c = offers.get(j);
                if (pick.contains(c)) {
                    continue;
                }
                List<AbstractCard> one = new ArrayList<AbstractCard>();
                one.add(c);
                out.add(Action.selectionWithMoves(Driver.namesOf(pick), Driver.displayNamesOf(pick),
                        Driver.uuidsOf(pick), Driver.printsOf(pick), Driver.namesOf(one),
                        Driver.uuidsOf(one), Driver.displayNamesOf(one), kind.name()));
                added++;
                break;
            }
        }
    }

    /**
     * Card IDs that open a card-select screen, so a play can run at the small frame delta that keeps
     * the screen-opening action alive.
     *
     * <p>Without this every card play ran at the small delta (needed only for these cards) and the
     * engine drain dominated the search: measured 1005ms of 1647ms for a 1301-node search. After the
     * split, only prompt plays pay that cost; anything the list misses is learned on first sight.
     */
    private static final java.util.Set<String> PROMPT_IDS = new java.util.HashSet<String>(
            java.util.Arrays.asList(
                    "Warcry", "Dual Wield", "JustLucky", "Dagger Throw", "ThirdEye", "Night Terror",
                    "Setup", "Seek", "Burning Pact", "Acrobatics", "Omniscience", "Secret Technique",
                    "CutThroughFate", "Armaments", "Secret Weapon", "Survivor", "Forethought",
                    "Purity", "Recycle", "Concentrate", "Prepared", "Thinking Ahead",
                    // Discovery and the four "Potion of X" potions open a card-reward style screen.
                    // They must run at the SMALL frame delta: the action that opened the screen is
                    // frozen while it is up, and a 20-unit tick would finish it before the answer
                    // arrives, so the chosen card would never be retrieved.
                    "Discovery", "ForeignInfluence"));

    /** The card's stable identity, "" when it has none (cloned shells keep the uuid). */
    public static String uuidOf(AbstractCard c) {
        return c == null || c.uuid == null ? "" : c.uuid.toString();
    }

    /** True when playing this card may open a card-select screen. */
    public static boolean needsSmallDelta(String cardId) {
        return cardId != null && PROMPT_IDS.contains(cardId);
    }

    /**
     * Cards that open a SCRY screen.
     *
     * <p>Scry's grid asks which cards to DISCARD, so the good answer is to throw away the WORST
     * cards and keep the best on top of the draw pile. The generic grid heuristic does the opposite
     * (it picks the highest-value cards), which would make every scry worse than doing nothing. The
     * reference watcher adapter makes scry a real search branch; this is the hook that makes our
     * branch mean the right thing.
     */
    /**
     * Cards known to press the End Turn button as part of their effect.
     *
     * <p>Since B89 this list is only a hint: {@code Driver.drain} watches the engine's own latch
     * (turnHasEnded / endTurnQueued / isEndingTurn) and ends the turn whenever one appears, so cards
     * that are NOT listed here -- Conclude, Time Maze, any mod card -- still end the turn. Unknown
     * cards used to stay in the un-advanced turn, and the search planned turn-N steps the live game
     * had already left.
     */
    private static final java.util.Set<String> PRESS_END_TURN_CARDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("Vault"));

    private static final java.util.Set<String> SCRY_CARDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("ThirdEye", "CutThroughFate", "JustLucky", "Weave",
                    "Scrawl", "Foresight"));

    /** Card names that opened a selection prompt since the last reset, for the panel's "not expanded" line. */
    private static final java.util.Set<String> PROMPT_CARDS = new java.util.LinkedHashSet<String>();

    public static void clearPromptCards() {
        PROMPT_CARDS.clear();
    }

    public static java.util.List<String> promptCards() {
        return new ArrayList<String>(PROMPT_CARDS);
    }

    /**
     * B116 (m00748): apply an action the caller BELIEVES is legal, and fail loudly when the engine
     * turns it down.
     *
     * <p>The refusal is not a no-op state change. {@code GameActionManager.getNextAction()} answers a
     * card it cannot use by showing a ThoughtBubble and removing the queue item without ever calling
     * {@code useCard}, which leaves {@code usingCard=true}; nothing in the game clears that flag
     * again, so the world is a corpse (every later drain spins on it). A replayed line may never
     * contain such a step: the live game refuses it too (user log: "游戏拒绝打出 按兵不动+").
     *
     * <p>The message says "not-offered" on purpose: that is the marker A3 uses to poison a step it
     * can never replay, instead of rebuilding the same dead path forever.
     */
    public static Driver.Drain applyOrRefuse(Action a) {
        Driver.Drain d = apply(a);
        if (d != null && d.refused) {
            String why = sts1solver.drive.Driver.lastRefusal();
            throw new ReplayException("the line cannot be replayed: " + describe(a)
                    + " -- not-offered: the engine does not allow it (" + (why == null ? "refused" : why) + ")");
        }
        return d;
    }

    /** Applies an action to the world that is currently installed. */
    public static Driver.Drain apply(Action a) {
        // Tell the selection layer that the NEXT screen (if any) is a scry: it discards the chosen
        // cards, so its useful answers are "discard nothing" / "discard the worst k".
        if (a.isCard()) {
            if (a.cardId != null && PRESS_END_TURN_CARDS.contains(a.cardId)) {
                Driver.allowLatchedEndTurn();
            }
            if (a.cardId != null && SCRY_CARDS.contains(a.cardId)) {
                Driver.markScrySelection();
            } else {
                Driver.clearScrySelection();
            }
        }
        if (a.isSelection()) {
            // B162: an answer is applied by filling the picker and closing it, exactly like a click.
            // Nothing is "guessed": the answer names cards, and a screen that offers none of them is
            // a line that cannot be replayed (same marker as a refused card play).
            if (!Driver.isAwaitingSelection()) {
                throw new ReplayException("the line cannot be replayed: " + describe(a)
                        + " -- not-settled: no selection screen is open");
            }
            if (!Driver.resolveSelectionByNames(a.choice, a.choiceDisplay, a.choiceUuids,
                    a.choicePrints, a.moved, a.movedUuids)) {
                String why = Driver.lastSelectionFailure();
                throw new ReplayException("the line cannot be replayed: " + describe(a)
                        + " -- not-offered: the screen does not offer it ("
                        + (why == null || why.isEmpty() ? "refused" : why) + ")");
            }
            Driver.Drain d = new Driver.Drain();
            d.awaitingSelection = Driver.isAwaitingSelection();
            d.settled = !d.awaitingSelection;
            return d;
        }
        // B162 (user m16041): while a screen owns the frame the action manager does not run at all
        // (AbstractRoom.update:254-259), so a card play or an end turn queued here does NOTHING.
        // That is not a refused play: it is a step that cannot be taken yet, and letting it pass
        // silently made the search chain no-ops into a line that looks long and changes no state.
        // A screen that is waiting for an answer never reaches this point -- its answer is the
        // action, handled above -- so anything still up here is a line that cannot be replayed.
        if (AbstractDungeon.isScreenUp) {
            throw new ReplayException("the line cannot be replayed: " + describe(a)
                    + " -- not-settled: a screen is open (" + AbstractDungeon.screen
                    + ") and no answer is waiting; the action manager is frozen");
        }
        if (a.isEndTurn()) {
            return Driver.endTurn();
        }
        if (a.isCard()) {
            AbstractCard card = handCard(a);
            AbstractMonster target = null;
            if (a.targetIndex >= 0) {
                List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
                if (a.targetIndex < ms.size()) {
                    target = ms.get(a.targetIndex);
                }
                if (target == null) {
                    throw new IllegalStateException("no monster at index " + a.targetIndex);
                }
            }
            Driver.Drain d = Driver.play(card, target, a.energyOnUse);
            if (d.awaitingSelection && a.cardName != null && !a.cardName.isEmpty()) {
                PROMPT_CARDS.add(a.cardName);
                PROMPT_IDS.add(card.cardID);
            }
            return d;
        }
        return Driver.usePotion(a.potionSlot, a.targetIndex);
    }

    /**
     * B78 (user m21789): finds the card the plan NAMED, by identity first and position second.
     *
     * <p>The index is how a click is expressed, but between the search and the replay the hand
     * order drifts (a draw, a discard, an exhausted card), so applying "hand index 5" blindly
     * played whatever sat there. That is how a plan said "exhausted 发掘+, then play 发掘+ again":
     * the names came from the explored tree while the positions came from a later replay. The uuid
     * is the stable identity, the name carries the upgrade state ("破灭+" is not "破灭"), and the
     * id is the last resort before the line is declared unreplayable.
     */
    private static AbstractCard handCard(Action a) {
        List<AbstractCard> hand = AbstractDungeon.player.hand.group;
        AbstractCard atIndex = a.handIndex >= 0 && a.handIndex < hand.size() ? hand.get(a.handIndex) : null;
        if (atIndex != null && sameIdentity(atIndex, a)) {
            return atIndex;
        }
        if (a.cardUuid != null && !a.cardUuid.isEmpty()) {
            for (int i = 0; i < hand.size(); ++i) {
                AbstractCard c = hand.get(i);
                if (c != null && c.uuid != null && a.cardUuid.equals(c.uuid.toString())) {
                    return c;
                }
            }
        }
        // B98 (user m02347): a uuid is fork-local, so the state the card CARRIES is the identity
        // that survives. Two copies of one id can differ in ways the id does not carry (a counter in
        // misc, a per-turn cost), and playing the other copy is a different play, not a re-finding.
        if (a.cardPrint != null && !a.cardPrint.isEmpty()) {
            for (int i = 0; i < hand.size(); ++i) {
                AbstractCard c = hand.get(i);
                if (c != null && a.cardPrint.equals(sts1solver.mirror.CardPrint.of(c))) {
                    return c;
                }
            }
            // No card matches the print. That is only safe when the id names ONE card: then the
            // difference is state drift (a cost changed, a counter moved), not ambiguity. When the
            // hand holds several candidates, refuse -- picking one of them is exactly the silent
            // wrong-card play this exists to stop.
            java.util.List<AbstractCard> sameId = new java.util.ArrayList<AbstractCard>();
            for (int i = 0; i < hand.size(); ++i) {
                AbstractCard c = hand.get(i);
                if (c != null && idMatches(c, a) && nameMatches(c, a)) {
                    sameId.add(c);
                }
            }
            if (sameId.size() == 1) {
                sts1solver.util.Log.warnEvery("print-drift-" + a.cardId, 5000L,
                        "execute: " + describe(a) + " does not carry the planned state any more ("
                                + a.cardPrint + "); taking the only copy in hand: "
                                + sts1solver.mirror.CardPrint.of(sameId.get(0)));
                return sameId.get(0);
            }
            if (sameId.size() > 1) {
                throw new ReplayException("the line cannot be replayed: " + describe(a)
                        + " has " + sameId.size() + " indistinguishable copies and none carries the"
                        + " planned state [" + a.cardPrint + "]; hand holds " + printsOf(sameId));
            }
            throw new ReplayException("the line cannot be replayed: " + describe(a)
                    + " (state " + a.cardPrint + ") is not in hand (hand=" + handNames(hand) + ")");
        }
        for (int i = 0; i < hand.size(); ++i) {
            AbstractCard c = hand.get(i);
            if (c != null && nameMatches(c, a) && idMatches(c, a)) {
                return c;
            }
        }
        // The id has no upgrade state, so it can still be the only thing a hand-built action
        // carries (probes, worker replays of older plans). Accept it, but never a card with a
        // different name when the action named one.
        for (int i = 0; i < hand.size(); ++i) {
            AbstractCard c = hand.get(i);
            if (c != null && idMatches(c, a)) {
                return c;
            }
        }
        if ((a.cardId == null || a.cardId.isEmpty()) && (a.cardName == null || a.cardName.isEmpty())) {
            if (atIndex != null) {
                return atIndex;
            }
            throw new ReplayException("the line cannot be replayed: no card at hand index " + a.handIndex
                    + " (hand=" + handNames(hand) + ")");
        }
        throw new ReplayException("the line cannot be replayed: " + describe(a) + " is not in hand (hand="
                + handNames(hand) + ")");
    }

    /** B78: the card a step names, resolved by identity -- exposed so a gate can prove it. */
    public static AbstractCard handCardFor(Action a) {
        return handCard(a);
    }

    private static boolean sameIdentity(AbstractCard c, Action a) {
        if (a.cardPrint != null && !a.cardPrint.isEmpty()) {
            // A name + id match is NOT identity: two copies of one card can carry different state,
            // and accepting the one at the recorded index is how the wrong copy got played (B98).
            return a.cardPrint.equals(sts1solver.mirror.CardPrint.of(c));
        }
        if (a.cardUuid != null && !a.cardUuid.isEmpty()) {
            return c.uuid != null && a.cardUuid.equals(c.uuid.toString());
        }
        return nameMatches(c, a) && idMatches(c, a);
    }

    private static boolean nameMatches(AbstractCard c, Action a) {
        return a.cardName == null || a.cardName.isEmpty() || a.cardName.equals(c.name);
    }

    private static boolean idMatches(AbstractCard c, Action a) {
        return a.cardId == null || a.cardId.isEmpty() || a.cardId.equals(c.cardID);
    }

    private static String describe(Action a) {
        // B189 (user m01083): an end turn carries neither a card name nor an id, so the old
        // fallback printed "the card at hand index -1" -- the line looked like a broken card play
        // while the real step was 结束回合.
        if (a.isEndTurn()) {
            return "结束回合";
        }
        if (a.cardName != null && !a.cardName.isEmpty()) {
            return a.cardName + (a.cardId == null || a.cardId.isEmpty() ? "" : " [" + a.cardId + "]");
        }
        return a.cardId == null || a.cardId.isEmpty() ? "the card at hand index " + a.handIndex : a.cardId;
    }

    private static String printsOf(List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); ++i) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sts1solver.mirror.CardPrint.of(cards.get(i)));
        }
        return sb.toString();
    }

    private static String handNames(List<AbstractCard> hand) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hand.size(); ++i) {
            AbstractCard c = hand.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(c == null ? "?" : c.name);
        }
        return sb.toString();
    }

    private static int indexOf(AbstractMonster m) {
        try {
            return AbstractDungeon.getMonsters().monsters.indexOf(m);
        } catch (Throwable t) {
            return -1;
        }
    }
}