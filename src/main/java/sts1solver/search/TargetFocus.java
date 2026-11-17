package sts1solver.search;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.0.8 (C2): one decision per card, not one per (card x monster).
 *
 * <p>Why this exists: every single-target card is offered once per living monster, so a hand of 12
 * cards against 4 monsters becomes 48 card branches -- and each branch pays for a fork, an install,
 * an engine tick and a read-back. The engine has to offer them (it cannot know which target the
 * player wants), but most of them end in the same place: a Strike on a 40 HP monster and the same
 * Strike on a 42 HP monster are different boards only in which monster lost 6 HP.
 *
 * <p>What is dropped and what is kept, from the state the search is standing on:
 * <ul>
 *   <li><b>kept</b>: every target this card can KILL with its own printed damage (the one place a
 *       target choice really changes the turn),</li>
 *   <li><b>kept</b>: the target with the highest attack intent (the one that must die first),</li>
 *   <li><b>kept</b>: the target with the lowest effective HP (focus fire),</li>
 *   <li><b>kept</b>: every target at all, when ANY target of the group carries a power -- thorns,
 *       buffer, angry, artifact and a mod's own powers all make "the same card on another monster"
 *       a different outcome, and this class must not guess about them.</li>
 * </ul>
 *
 * <p>Order only of the kept branches is untouched: the list keeps its engine order minus the
 * dropped entries, so the rest of the pipeline (turn plan, beam) sees exactly the old shape.
 *
 * <p>{@code search.targetFocus=off} restores the full (card x monster) list. Dropping branches is a
 * real loss when the estimate is wrong, so the first drop of every search is logged once with the
 * counts, and the switch is the rollback.
 */
public final class TargetFocus {

    /** Branches offered this search (accumulated, so the log line says how much was saved). */
    private static int seen;
    /** Branches kept this search. */
    private static int kept;
    private static boolean logged;

    private TargetFocus() {
    }

    /** Forget the last search's counters (a new search may log its own line once). */
    public static void reset() {
        seen = 0;
        kept = 0;
        logged = false;
    }

    /**
     * Collapse the per-target siblings of one card play. Must be called with the sandbox installed:
     * the monsters read here are the copies the candidates were built from.
     */
    public static List<Action> apply(List<Action> in) {
        if (in == null || in.size() < 2 || !Limits.targetFocus()) {
            return in;
        }
        try {
            List<AbstractMonster> ms = monsters();
            List<AbstractCard> hand = hand();
            if (ms == null || hand == null || ms.size() < 2) {
                return in;
            }
            // Group the target siblings of one card. The key is the card's print (upgrade, cost,
            // counters) plus what the play itself asks for, so two different cards never share a
            // group and two prints of the same id never do either.
            LinkedHashMap<String, List<Integer>> groups =
                    new LinkedHashMap<String, List<Integer>>(in.size());
            for (int i = 0; i < in.size(); i++) {
                Action a = in.get(i);
                if (a == null || !a.isCard() || a.targetIndex < 0 || a.targetIndex >= ms.size()
                        || a.handIndex < 0 || a.handIndex >= hand.size()) {
                    continue;
                }
                String key = a.cardPrint + "\u0001e" + a.energyOnUse + "\u0001c" + a.choice;
                List<Integer> g = groups.get(key);
                if (g == null) {
                    g = new ArrayList<Integer>(4);
                    groups.put(key, g);
                }
                g.add(Integer.valueOf(i));
            }
            boolean[] drop = new boolean[in.size()];
            boolean changed = false;
            for (List<Integer> g : groups.values()) {
                if (g.size() < 2) {
                    continue;
                }
                int dmg = TurnPlan.damageOf(hand.get(in.get(g.get(0).intValue()).handIndex));
                int[] threat = new int[g.size()];
                int[] effHp = new int[g.size()];
                boolean special = false;
                int bestThreat = 0;
                int bestHp = 0;
                for (int k = 0; k < g.size(); k++) {
                    AbstractMonster m = ms.get(in.get(g.get(k).intValue()).targetIndex);
                    if (m == null || (m.powers != null && !m.powers.isEmpty())) {
                        special = true;
                        break;
                    }
                    effHp[k] = Math.max(0, m.currentHealth) + Math.max(0, m.currentBlock);
                    threat[k] = Math.max(0, m.getIntentDmg());
                    if (threat[k] > threat[bestThreat]
                            || (threat[k] == threat[bestThreat] && effHp[k] < effHp[bestHp])) {
                        bestThreat = k;
                    }
                    if (effHp[k] < effHp[bestHp]
                            || (effHp[k] == effHp[bestHp] && threat[k] > threat[bestHp])) {
                        bestHp = k;
                    }
                }
                if (special) {
                    continue;
                }
                for (int k = 0; k < g.size(); k++) {
                    int idx = g.get(k).intValue();
                    boolean kill = dmg > 0 && dmg >= effHp[k];
                    if (kill || k == bestThreat || k == bestHp) {
                        continue;
                    }
                    drop[idx] = true;
                    changed = true;
                }
            }
            if (!changed) {
                return in;
            }
            List<Action> out = new ArrayList<Action>(in.size());
            for (int i = 0; i < in.size(); i++) {
                if (!drop[i]) {
                    out.add(in.get(i));
                }
            }
            seen += in.size();
            kept += out.size();
            if (!logged && seen > kept) {
                logged = true;
                Log.info("target focus: kept " + kept + " of " + seen
                        + " branch(es) of this search (one per card x monster before); "
                        + "search.targetFocus=off restores all");
            }
            return out;
        } catch (Throwable t) {
            // An unreadable board must not cost a decision: keep the engine's own list.
            Log.warnEvery("target-focus", 30000L, "target focus skipped: " + t);
            return in;
        }
    }

    private static List<AbstractMonster> monsters() {
        try {
            return AbstractDungeon.getMonsters() == null
                    ? null : AbstractDungeon.getMonsters().monsters;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<AbstractCard> hand() {
        try {
            return AbstractDungeon.player == null || AbstractDungeon.player.hand == null
                    ? null : AbstractDungeon.player.hand.group;
        } catch (Throwable t) {
            return null;
        }
    }
}
