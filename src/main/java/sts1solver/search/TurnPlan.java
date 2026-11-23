package sts1solver.search;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 1.0.6 (C1): this turn's own bound, used to ORDER the candidates of one decision.
 *
 * <p>Two cheap, honest questions are answered from the state the search is already standing on:
 * <ul>
 *   <li><b>Can this turn kill?</b> a 0/1 knapsack over the hand with the turn's energy as the
 *       budget (each physical card at most once, multi-hit cards priced by their hit total). If the
 *       best reachable damage covers every living monster's HP plus block, a killing line EXISTS and
 *       its cards are tried first.</li>
 *   <li><b>Must this turn block?</b> the sum of the living monsters' attack intents against the
 *       player's HP plus the block already held. When the incoming damage alone would kill, the
 *       cards that can prevent it are tried before anything else.</li>
 * </ul>
 *
 * <p>Order only -- no candidate is ever dropped here. A wrong estimate can therefore cost time,
 * never an answer: every branch the engine offered is still expanded, exactly in the old set. The
 * hard pruning of "cannot kill and cannot survive" stays behind {@link Limits#dominance()} and the
 * parked-cut, where dropping a candidate is already an accepted, measured risk.
 *
 * <p>Why this is not a rollout: a rollout needs a cheap simulator and this project's one step is
 * an engine tick (docs/JUDGMENT.md rejects rollout re-ranking on measured data). The knapsack reads
 * numbers the hand already carries and costs microseconds, so it can run on every decision point of
 * every layer without moving the budget.
 */
public final class TurnPlan {

    private static final int MAX_ENERGY = 12;

    /** True when the knapsack found a damage total that covers the whole board this turn. */
    static volatile boolean lastLethal;
    /** True when the monsters' intents alone would kill the player before any block is played. */
    static volatile boolean lastMustDefend;
    /** Damage the knapsack proved reachable with the current hand and energy. */
    static volatile int lastReachableDamage;
    /** Attack damage the living monsters are aiming at the player this turn. */
    static volatile int lastIncoming;

    /** 1.7.2: how many annotate() calls fell back to the engine's order (see skippedForTest). */
    static volatile int skipped;

    /** 1.7.2: how many non-empty candidate lists reached annotate() (guards a vacuous gate). */
    static volatile int annotateCalls;

    private static volatile String notice;

    private TurnPlan() {
    }

    /** The one line the log gets the first time a plan changes the order, or null. */
    static String takeNotice() {
        String n = notice;
        notice = null;
        return n;
    }

    /** Forget the last search's notice (a new search may log its own once). */
    public static void reset() {
        notice = null;
        lastLethal = false;
        lastMustDefend = false;
        lastReachableDamage = 0;
        lastIncoming = 0;
        skipped = 0;
        annotateCalls = 0;
    }

    /**
     * 1.7.2 test hook: how many times {@link #annotate(List)} gave up on a search. It used to be
     * every search (the in-place sort threw on the generator's unmodifiable view); a gate reads
     * this so the ordering cannot go silently dead again.
     */
    public static int skippedForTest() {
        return skipped;
    }

    /**
     * 1.7.2 test hook: how many candidate lists actually reached the ordering. A gate that only
     * asserts "no skip" would pass on a search that never annotates at all.
     */
    public static int annotateCallsForTest() {
        return annotateCalls;
    }

    /**
     * Tag every action with its class (attack / skill / power / potion / end / sel / other) and,
     * when this turn has a plan, put the cards of that plan in front. Must be called with the world
     * installed, because it reads the hand and the monsters.
     */
    public static void annotate(List<Action> acts) {
        if (acts == null || acts.isEmpty()) {
            return;
        }
        annotateCalls++;
        try {
            classify(acts);
            if (Limits.turnPlan()) {
                order(acts);
            }
        } catch (Throwable t) {
            // An unreadable hand must not cost a decision: keep the engine's own order.
            skipped++;
            Log.warnEvery("turn-plan", 30000L, "turn plan skipped: " + t);
        }
    }

    /** Only the class tags, for the paths that must not re-order (selection answers). */
    public static void classify(List<Action> acts) {
        if (acts == null) {
            return;
        }
        try {
            List<AbstractCard> hand = hand();
            for (int i = 0; i < acts.size(); i++) {
                Action a = acts.get(i);
                if (a == null) {
                    continue;
                }
                if (a.isCard() && hand != null && a.handIndex >= 0 && a.handIndex < hand.size()) {
                    a.actionClass = classOf(hand.get(a.handIndex));
                } else if (a.isPotion()) {
                    a.actionClass = "potion";
                } else if (a.isEndTurn()) {
                    a.actionClass = "end";
                } else if (a.isSelection()) {
                    a.actionClass = "sel";
                } else if (a.isFuse()) {
                    // 字修's two actions are free card-manipulation, not "other": the beam keeps the
                    // best node of every class, and folding them into "other" makes them compete
                    // with 结束回合 for the single slot.
                    a.actionClass = "fuse";
                } else if (a.isSplit()) {
                    a.actionClass = "split";
                } else {
                    a.actionClass = "other";
                }
            }
        } catch (Throwable ignored) {
            // Leave the empty tags behind; diversity then treats everything as "other".
        }
    }

    // ------------------------------------------------------------------ the plan

    private static void order(List<Action> acts) {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null) {
            return;
        }
        List<AbstractCard> hand = hand();
        if (hand == null) {
            return;
        }
        int energy = p.energy == null ? 0 : Math.max(0, p.energy.energy);
        int hp = p.currentHealth;
        int held = p.currentBlock;

        int monsterHp = 0;
        int monsterBlock = 0;
        int incoming = 0;
        try {
            List<AbstractMonster> ms = AbstractDungeon.getMonsters() == null
                    ? null : AbstractDungeon.getMonsters().monsters;
            if (ms != null) {
                for (int i = 0; i < ms.size(); i++) {
                    AbstractMonster m = ms.get(i);
                    if (m == null || m.isDeadOrEscaped()) {
                        continue;
                    }
                    monsterHp += Math.max(0, m.currentHealth);
                    monsterBlock += Math.max(0, m.currentBlock);
                    int d = m.getIntentDmg();
                    if (d > 0) {
                        incoming += d;
                    }
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        lastIncoming = incoming;

        // One item per PHYSICAL card: the same attack offered on three targets is still one card
        // and can be played once, so the damage bound must not count it three times.
        List<Action> items = new ArrayList<Action>();
        IdentityHashMap<Action, int[]> metrics = new IdentityHashMap<Action, int[]>();
        java.util.HashSet<Integer> touched = new java.util.HashSet<Integer>();
        for (int i = 0; i < acts.size(); i++) {
            Action a = acts.get(i);
            if (a == null || !a.isCard() || a.handIndex < 0 || a.handIndex >= hand.size()) {
                continue;
            }
            AbstractCard c = hand.get(a.handIndex);
            int dmg = damageOf(c);
            int blk = blockOf(c);
            int cost = costOf(c, a);
            metrics.put(a, new int[]{dmg, blk, cost});
            if (dmg > 0 && touched.add(a.handIndex)) {
                items.add(a);
            }
        }

        int reachable = knapsack(items, metrics, energy);
        lastReachableDamage = reachable;
        boolean lethal = monsterHp > 0 && reachable >= monsterHp + monsterBlock;
        boolean defend = incoming > hp + held;
        lastLethal = lethal;
        lastMustDefend = defend;
        if (!lethal && !defend) {
            return;
        }

        // The killing subset, expressed as hand indices: a card offered on several targets is the
        // same physical card, so every candidate playing one of these is part of the plan.
        java.util.Set<Integer> chosenHand = new java.util.HashSet<Integer>();
        if (lethal) {
            boolean[] picked = choose(items, metrics, energy);
            for (int i = 0; i < picked.length; i++) {
                if (picked[i]) {
                    chosenHand.add(Integer.valueOf(items.get(i).handIndex));
                }
            }
        }
        IdentityHashMap<Action, Integer> tier = new IdentityHashMap<Action, Integer>();
        // A killing turn comes first even when it is also a dying turn: a dead monster deals nothing,
        // so the incoming damage the survival branch is built for has already been removed.
        final boolean damageFirst = lethal;
        for (int i = 0; i < acts.size(); i++) {
            Action a = acts.get(i);
            int[] m = metrics.get(a);
            int t;
            if (a.isSelection()) {
                t = 4;
            } else if (a.isEndTurn()) {
                t = 0;
            } else if (lethal) {
                // Lethal first: the cards of the killing subset, then any other attacker.
                t = chosenHand.contains(Integer.valueOf(a.handIndex)) ? 3
                        : (m != null && m[0] > 0 ? 2 : (a.isPotion() ? 1 : 0));
            } else {
                // Survival first: a card that blocks is the only thing that changes the equation.
                t = m != null && m[1] > 0 ? 3 : (a.isPotion() ? 2 : 1);
            }
            tier.put(a, Integer.valueOf(t));
        }
        final IdentityHashMap<Action, Integer> tiers = tier;
        final IdentityHashMap<Action, int[]> met = metrics;
        Collections.sort(acts, new Comparator<Action>() {
            @Override
            public int compare(Action a, Action b) {
                int ta = tiers.containsKey(a) ? tiers.get(a).intValue() : 0;
                int tb = tiers.containsKey(b) ? tiers.get(b).intValue() : 0;
                if (ta != tb) {
                    return tb - ta;
                }
                int[] ma = met.get(a);
                int[] mb = met.get(b);
                int va = ma == null ? 0 : (damageFirst ? ma[0] : ma[1]);
                int vb = mb == null ? 0 : (damageFirst ? mb[0] : mb[1]);
                return vb - va;
            }
        });
        if (notice == null) {
            notice = "turn plan: lethal=" + lethal + " defend=" + defend
                    + " damage=" + reachable + "/" + (monsterHp + monsterBlock)
                    + " incoming=" + incoming + " hp=" + hp + "+" + held
                    + " energy=" + energy;
        }
    }

    // ------------------------------------------------------------------ knapsack

    /** Best damage a subset of {@code items} can pay for with {@code energy} or less. */
    private static int knapsack(List<Action> items, IdentityHashMap<Action, int[]> metrics,
                                int energy) {
        int cap = Math.min(MAX_ENERGY, Math.max(0, energy));
        int[] best = new int[cap + 1];
        for (int i = 0; i < items.size(); i++) {
            int[] m = metrics.get(items.get(i));
            if (m == null || m[0] <= 0 || m[2] > cap) {
                continue;
            }
            int cost = Math.max(0, m[2]);
            for (int e = cap; e >= cost; e--) {
                int v = best[e - cost] + m[0];
                if (v > best[e]) {
                    best[e] = v;
                }
            }
        }
        return best[cap];
    }

    /**
     * The subset the knapsack picked, as a flag per candidate index (a card offered on three
     * targets flags all three, because any of them plays that same card).
     */
    private static boolean[] choose(List<Action> items, IdentityHashMap<Action, int[]> metrics,
                                    int energy) {
        int cap = Math.min(MAX_ENERGY, Math.max(0, energy));
        int n = items.size();
        int[][] dp = new int[n + 1][cap + 1];
        for (int i = 1; i <= n; i++) {
            int[] m = metrics.get(items.get(i - 1));
            boolean affordable = m != null && m[2] <= cap;
            int cost = affordable ? Math.max(0, m[2]) : cap;
            int dmg = affordable ? Math.max(0, m[0]) : 0;
            for (int e = 0; e <= cap; e++) {
                int keep = dp[i - 1][e];
                int take = e >= cost ? dp[i - 1][e - cost] + dmg : -1;
                dp[i][e] = Math.max(keep, take);
            }
        }
        boolean[] pickedItems = new boolean[n];
        int e = cap;
        for (int i = n; i >= 1; i--) {
            int[] m = metrics.get(items.get(i - 1));
            boolean affordable = m != null && m[2] <= cap;
            int cost = affordable ? Math.max(0, m[2]) : cap;
            int dmg = affordable ? Math.max(0, m[0]) : 0;
            if (affordable && e >= cost && dp[i][e] == dp[i - 1][e - cost] + dmg && dmg > 0) {
                pickedItems[i - 1] = true;
                e -= cost;
            }
        }
        // Map the chosen physical cards back onto every candidate that plays them.
        java.util.HashSet<Integer> handIdx = new java.util.HashSet<Integer>();
        for (int i = 0; i < n; i++) {
            if (pickedItems[i]) {
                handIdx.add(Integer.valueOf(items.get(i).handIndex));
            }
        }
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            out[i] = handIdx.contains(Integer.valueOf(items.get(i).handIndex));
        }
        return out;
    }

    // ------------------------------------------------------------------ card numbers

    private static List<AbstractCard> hand() {
        try {
            return AbstractDungeon.player == null || AbstractDungeon.player.hand == null
                    ? null : AbstractDungeon.player.hand.group;
        } catch (Throwable t) {
            return null;
        }
    }

    static String classOf(AbstractCard c) {
        if (c == null) {
            return "other";
        }
        switch (c.type) {
            case ATTACK:
                return "attack";
            case SKILL:
                return "skill";
            case POWER:
                return "power";
            default:
                return "other";
        }
    }

    /** Damage this card can put on the board, hits included; 0 for a card without damage. */
    static int damageOf(AbstractCard c) {
        if (c == null) {
            return 0;
        }
        int d = Math.max(c.damage, c.baseDamage);
        int[] multi = c.multiDamage;
        if (multi != null && multi.length > 1) {
            int sum = 0;
            for (int i = 0; i < multi.length; i++) {
                sum += Math.max(0, multi[i]);
            }
            if (sum > d) {
                d = sum;
            }
        }
        return Math.max(0, d);
    }

    /** Block this card can grant; 0 for a card without block. */
    static int blockOf(AbstractCard c) {
        if (c == null) {
            return 0;
        }
        return Math.max(0, Math.max(c.block, c.baseBlock));
    }

    /** What playing this card costs this turn; an X-cost card is charged a single point. */
    static int costOf(AbstractCard c, Action a) {
        if (c == null) {
            return 0;
        }
        if (a != null && a.energyOnUse > 0) {
            return a.energyOnUse;
        }
        if (c.costForTurn >= 0) {
            return c.costForTurn;
        }
        return Math.max(1, c.cost);
    }
}
