package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.Statics;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Why does a plan that ends a turn from inside a card (CaiNongMod's BideTime, "按兵不动") fail to
 * replay on step 8 of 15?
 *
 * <p>The user's log (m02901) showed the search finding a win whose later steps press End Turn from
 * inside a CARD, and the replay of that same line stopping with a two-card hand (the retained cards
 * only): the turn had ended but the next one had not started. This probe answers the mechanism
 * question with numbers instead of theory:
 *
 * <ol>
 *   <li>does one card that presses End Turn actually reach the next turn (turn++, energy refill, a
 *       drawn hand) in the sandbox;</li>
 *   <li>does the mod's own {@code isUseAttack} flag (an attack was already played this turn) make
 *       BideTime unplayable, the way the real game's {@code canUse} does;</li>
 *   <li>does a search that uses the card produce a line the same sandbox can replay.</li>
 * </ol>
 */
public final class BideProbe {

    /** CaiNongMod's "press End Turn" card and the ids its starter deck uses. */
    private static final String BIDE = "CaiNongMod:BideTime";
    private static final String STRIKE = "CaiNongMod:Strike";
    private static final String DEFEND = "CaiNongMod:Defend";
    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";
    private static final String NOB = "com.megacrit.cardcrawl.monsters.exordium.GremlinNob";
    /** The mod's card class, read directly for its static flag (not mirrored through our own tables). */
    private static final String BIDE_CLASS = "CaiNongMod.cards.BideTime";
    /**
     * The A3 experiment runs an UNLIMITED search (minutes), so it is off unless the mechanism
     * question needs it. Flip to true to reproduce "A3 promote: cannot rebuild a deferred path".
     */
    private static final boolean RUN_PROMOTE = false;

    private BideProbe() {
    }

    public static void run() {
        AbstractPlayer cainong = registered(CHARACTER);
        if (cainong == null) {
            Log.info("BIDE probe skipped: " + CHARACTER + " is not registered (mod not loaded)");
            finish();
            return;
        }
        Log.info("BIDE probe: player=" + cainong.getClass().getName()
                + " cls=" + cainong.chosenClass + " hp=" + cainong.maxHealth);
        try {
            isolated(cainong);
        } catch (Throwable t) {
            Log.info("BIDE isolated threw: " + t);
        }
        try {
            afterAttack(cainong);
        } catch (Throwable t) {
            Log.info("BIDE afterAttack threw: " + t);
        }
        try {
            flagAcrossFork(cainong);
        } catch (Throwable t) {
            Log.info("BIDE flagAcrossFork threw: " + t);
        }
        try {
            searchThenReplay(cainong);
        } catch (Throwable t) {
            Log.info("BIDE search threw: " + t);
        }
        try {
            userBattle(cainong);
        } catch (Throwable t) {
            Log.info("BIDE userBattle threw: " + t);
        }
        try {
            applyStyle(cainong);
        } catch (Throwable t) {
            Log.info("BIDE applyStyle threw: " + t);
        }
        try {
            slicedPortfolio(cainong);
        } catch (Throwable t) {
            Log.info("BIDE slicedPortfolio threw: " + t);
        }
        if (RUN_PROMOTE) {
            try {
                promotePhase(cainong);
            } catch (Throwable t) {
                Log.info("BIDE promotePhase threw: " + t);
            }
        }
        finish();
    }

    // ------------------------------------------------------------------ 1: one card, one turn

    /** Plays BideTime alone and reports whether the sandbox really reached the next turn. */
    private static void isolated(AbstractPlayer cainong) {
        World w = buildWorld(cainong);
        w.install();
        Driver.begin();
        try {
            AbstractCard bide = intoHand(BIDE);
            if (bide == null) {
                Log.info("BIDE isolated: " + BIDE + " is not in the built deck");
                return;
            }
            GameActionManager gam = AbstractDungeon.actionManager;
            int master = AbstractDungeon.player.energy.energyMaster;
            int t0 = GameActionManager.turn;
            int e0 = Driver.energy();
            int h0 = AbstractDungeon.player.hand.size();
            Log.info("BIDE isolated before: turn=" + t0 + " energy=" + e0 + "/" + master
                    + " hand=" + h0 + " flag=" + flag() + " bidePower=" + powerCount()
                    + " playable=" + bide.canUse(AbstractDungeon.player, monster()));
            Driver.Drain d = Driver.play(bide, monster(), 0);
            Log.info("BIDE isolated after: turn=" + t0 + "->" + GameActionManager.turn
                    + " energy=" + e0 + "->" + Driver.energy() + "/" + master
                    + " hand=" + h0 + "->" + AbstractDungeon.player.hand.size()
                    + " turnHasEnded=" + gam.turnHasEnded
                    + " isEndingTurn=" + (AbstractDungeon.player != null && AbstractDungeon.player.isEndingTurn)
                    + " queues=" + gam.actions.size() + "/" + gam.cardQueue.size() + "/" + gam.monsterQueue.size()
                    + " flag=" + flag() + " bidePower=" + powerCount()
                    + " over=" + Driver.combatOver()
                    + " drain=settled:" + d.settled + ",stalled:" + d.stalled
                    + ",timedOut:" + d.timedOut + ",selection:" + d.awaitingSelection
                    + ",steps:" + d.steps);
            boolean nextTurn = GameActionManager.turn == t0 + 1;
            // BideTimePower itself grants [E] next turn, so "refilled" is >= master, not ==.
            boolean refilled = Driver.energy() >= master;
            boolean drew = AbstractDungeon.player.hand.size() > 0;
            Log.info("BIDE isolated verdict: nextTurn=" + nextTurn + " refilled=" + refilled
                    + " drew=" + drew + " -> " + (nextTurn && refilled && drew ? "ok" : "BROKEN"));
        } finally {
            Driver.end();
            w.readBack();
            w.uninstall();
        }
    }

    // ------------------------------------------------------------------ 2: the mod's own flag

    /**
     * The mod's {@code canUse} reads a static {@code isUseAttack} flag that an ATTACK played this
     * turn sets. If the sandbox lets the search play BideTime in a turn that already played an
     * attack, the two runs disagree about what is legal -- and the plan cannot be replayed.
     */
    private static void afterAttack(AbstractPlayer cainong) {
        World w = buildWorld(cainong);
        w.install();
        Driver.begin();
        try {
            AbstractCard bide = intoHand(BIDE);
            if (bide == null) {
                Log.info("BIDE afterAttack: " + BIDE + " is not in the built deck");
                return;
            }
            Log.info("BIDE afterAttack before: flag=" + flag()
                    + " bidePlayable=" + bide.canUse(AbstractDungeon.player, monster())
                    + " offered=" + offered(BIDE));
            Action strike = null;
            for (Action a : Legal.actions()) {
                if (STRIKE.equals(a.cardId)) {
                    strike = a;
                    break;
                }
            }
            if (strike == null) {
                Log.info("BIDE afterAttack: no " + STRIKE + " branch was offered (hand="
                        + handNames() + ")");
                return;
            }
            Legal.apply(strike);
            Log.info("BIDE afterAttack after: attack=" + strike.label
                    + " flag=" + flag()
                    + " bidePlayable=" + bide.canUse(AbstractDungeon.player, monster())
                    + " offered=" + offered(BIDE)
                    + " legal=" + countLegal() + " hand=" + handNames());
        } finally {
            Driver.end();
            w.readBack();
            w.uninstall();
        }
    }

    // ------------------------------------------------------------------ 2b: the flag inside the mirror

    /**
     * Does the mod's {@code isUseAttack} flag travel WITH a world, or is it a shared global?
     *
     * <p>The deferred-path failures keep refusing BideTime with an empty cantUseMessage, i.e. the
     * mod's own {@code canUse} said no -- but the search had offered that very card in the same
     * state. That is only possible if the static the mod reads is not part of the world: it is
     * whatever the last installed window left behind. This walks one world through install ->
     * attack -> readBack -> uninstall -> reinstall -> fork and prints the flag at each stop, plus
     * whether the mirror table holds the field at all.
     */
    private static void flagAcrossFork(AbstractPlayer cainong) {
        String key = BIDE_CLASS + "#isUseAttack";
        Log.info("BIDE mirror: field key=" + key + " mirrored=" + mirrored(key) + " live flag=" + flag());
        // B116: the sweep must see the field even though MTS gives this class no code source.
        Log.info("BIDE sweep: keys=" + sts1solver.mirror.ModStatics.keys().size()
                + " resolveBideClass=" + (sts1solver.mirror.ModStatics.resolve(BIDE_CLASS) != null));
        World w = buildWorld(cainong);
        w.install();
        Driver.begin();
        try {
            AbstractCard bide = intoHand(BIDE);
            Log.info("BIDE flag t0 installed: flag=" + flag() + " playable=" + playable(bide)
                    + " offered=" + offered(BIDE));
            Action strike = null;
            for (Action a : Legal.actions()) {
                if (STRIKE.equals(a.cardId)) {
                    strike = a;
                    break;
                }
            }
            if (strike == null) {
                Log.info("BIDE flag: no " + STRIKE + " branch (hand=" + handNames() + ")");
                return;
            }
            Legal.apply(strike);
            Log.info("BIDE flag t1 after " + strike.label + ": flag=" + flag()
                    + " playable=" + playable(bide) + " offered=" + offered(BIDE));
            w.readBack();
            Log.info("BIDE flag t2 readBack (still installed): flag=" + flag());
        } finally {
            Driver.end();
            w.uninstall();
        }
        Log.info("BIDE flag t3 uninstalled: flag=" + flag());
        w.install();
        try {
            Log.info("BIDE flag t4 same world reinstalled: flag=" + flag()
                    + " playable=" + playable(handCard(BIDE)));
            World child = w.fork();
            child.install();
            try {
                Log.info("BIDE flag t5 fork of that world: flag=" + flag()
                        + " playable=" + playable(handCard(BIDE)));
            } finally {
                child.uninstall();
            }
        } finally {
            w.uninstall();
        }
    }

    /** Whether the mirror table carries {@code owner#field}. */
    private static boolean mirrored(String key) {
        try {
            Statics.Spec[] specs = Statics.specs();
            for (int i = 0; i < specs.length; i++) {
                if ((specs[i].owner.getName() + '#' + specs[i].name).equals(key)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static String playable(AbstractCard c) {
        if (c == null) {
            return "no-card";
        }
        try {
            return String.valueOf(c.canUse(AbstractDungeon.player, monster()));
        } catch (Throwable t) {
            return "threw:" + t;
        }
    }

    /** Only the hand: unlike {@link #intoHand}, this never moves a card. */
    private static AbstractCard handCard(String cardId) {
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c != null && cardId.equals(c.cardID)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 3: search, then replay

    /** The user's symptom: a line the search reported cannot be replayed by the same sandbox. */
    private static void searchThenReplay(AbstractPlayer cainong) {
        World w = buildWorld(cainong);
        Solver.Result r = Solver.solvePortfolio(w, 8000L);
        List<Action> actions = new ArrayList<Action>();
        if (r.plan != null) {
            for (int i = 0; i < r.plan.size(); i++) {
                actions.add(r.plan.get(i).action);
            }
        }
        Log.info("BIDE search: win=" + r.win + " steps=" + actions.size()
                + " hpLoss=" + r.hpLoss + " line=" + line(actions) + " " + r.summary());
        if (actions.isEmpty()) {
            return;
        }
        List<Solver.Step> replayed = Solver.replay(w, actions);
        Log.info("BIDE replay: " + replayed.size() + "/" + actions.size()
                + (replayed.size() == actions.size() ? " (whole line)"
                        : " (DIVERGED at step " + replayed.size() + " on " + actions.get(replayed.size()).label + ")"));
        // A second identical search from the same world: if the two lines differ, the sandbox is
        // not a function of its input (a shared static, a live RNG, a latch left standing).
        Solver.Result twice = Solver.solvePortfolio(w, 8000L);
        List<Action> again = new ArrayList<Action>();
        if (twice.plan != null) {
            for (int i = 0; i < twice.plan.size(); i++) {
                again.add(twice.plan.get(i).action);
            }
        }
        Log.info("BIDE repeat: win=" + twice.win + " steps=" + again.size()
                + " same=" + line(actions).equals(line(again)));
    }

    // ------------------------------------------------------------------ 4: the user's own fight

    /**
     * The fight the user's log (m02901) came from, rebuilt from its own digest line: CaiNong,
     * ascension 4, 58/80 HP, Empty Cage + Golden Idol, GremlinNob, and that twelve-card deck. The
     * search then gets a live-sized budget, and the line it finds is replayed two ways:
     *
     * <ul>
     *   <li>continuously -- one fork, all steps applied in place (what {@code Solver.replay} does);</li>
     *   <li>one fork per step (what {@code Solver.materialize} does when it promotes a deferred path).</li>
     * </ul>
     *
     * If the two disagree, the bug is in the promote path, not in the card.
     */
    private static void userBattle(AbstractPlayer cainong) {
        String[] deck = {"CaiNongMod:SuoKai", "CaiNongMod:Defend", "CaiNongMod:Strike",
                "CaiNongMod:Statuette", "CaiNongMod:PaiDianDian", "CaiNongMod:Strike",
                "CaiNongMod:Strike", "CaiNongMod:TangLaugh", "CaiNongMod:Defend",
                "CaiNongMod:BideTime", "CaiNongMod:AllTheSame", "CaiNongMod:Defend"};
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261103L;
        spec.maxHealth = 80;
        spec.ascension = 4;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[]{"Empty Cage", "Golden Idol"};
        spec.deck = deck;
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        AbstractDungeon.player.currentHealth = 58;
        upgradeEvery(BIDE);
        World w = World.captureLive();
        Solver.Result r = Solver.solvePortfolio(w, 20000L);
        List<Action> actions = new ArrayList<Action>();
        if (r.plan != null) {
            for (int i = 0; i < r.plan.size(); i++) {
                actions.add(r.plan.get(i).action);
            }
        }
        Log.info("BIDE user: win=" + r.win + " steps=" + actions.size() + " hpLoss=" + r.hpLoss
                + " " + r.summary());
        Log.info("BIDE user line: " + line(actions));
        if (actions.isEmpty()) {
            return;
        }
        int continuous = Solver.replay(w, actions).size();
        int perStep = perStepReplay(w, actions);
        Log.info("BIDE user replay: continuous=" + continuous + "/" + actions.size()
                + " perStep=" + perStep + "/" + actions.size()
                + (continuous == perStep ? " agree" : " DISAGREE"));
    }

    // ------------------------------------------- 5a: the path the PANEL actually runs

    /**
     * The panel does not run {@link Solver#solvePortfolio}: it drives a {@link Solver.PortfolioSession}
     * in 40ms slices, and that session runs the four beam profiles back to back. The parked-candidate
     * pass ("A3") has its own wall-clock ceiling stored in a STATIC that is reset once per search, so
     * this is where "kept=2000 promoted=0" with plenty of budget comes from -- member 1 spends the
     * ceiling and members 2..4 inherit an expired phase. Reproducing the sliced path is the only way
     * to see it; a blocking search never does.
     */
    private static void slicedPortfolio(AbstractPlayer cainong) {
        World w = userWorld(cainong);
        Solver.resetGuards();
        Solver.PortfolioSession ps = new Solver.PortfolioSession(w, 20000L, 0);
        int slices = 0;
        long t0 = System.currentTimeMillis();
        while (!ps.finished() && slices < 5000) {
            ps.advance(40L);
            slices++;
        }
        Solver.Result r = ps.result();
        Log.info("BIDE sliced: finished=" + ps.finished() + " slices=" + slices + " ms="
                + (System.currentTimeMillis() - t0) + (r == null ? " (no result)" : ""));
        if (r == null) {
            return;
        }
        Log.info("BIDE sliced numbers: deferred=" + r.deferred + " promoted=" + r.promoted
                + " earlier=" + r.promotedEarlier + " frontierExhausted=" + r.frontierExhausted
                + " cutShort=" + r.cutShort + " win=" + r.win + " why=" + r.deferredWhy);
        Log.info("BIDE sliced headline: " + sts1solver.search.PlanText.headline(r));
    }

    // ------------------------------------------------------------------ 5b: which walk is the search's?

    /**
     * The search and the replay apply an action with the same call, but not with the same amount of
     * engine time: {@code Solver.expand} pumps up to 24 idle frames before it answers a screen (it has
     * to, to discover whether a screen opens at all), while {@code Solver.materialize} /
     * {@code Solver.replay} only pump when the plan says a screen opens. If those two walks can end
     * on different states, then a line found by one cannot be rebuilt by the other -- which is exactly
     * "the search found a win this same sandbox cannot replay".
     *
     * <p>So: walk the same plan twice, once without idle frames and once with, and compare the digest
     * after every step.
     */
    private static void applyStyle(AbstractPlayer cainong) {
        World w = userWorld(cainong);
        Solver.Result r = Solver.solvePortfolio(w, 20000L);
        List<Action> actions = actionsOf(r);
        if (actions.isEmpty()) {
            Log.info("BIDE style: no line to walk");
            return;
        }
        List<String> plain = walk(w, actions, 0);
        List<String> pumped = walk(w, actions, 24);
        int first = -1;
        int n = Math.min(plain.size(), pumped.size());
        for (int i = 0; i < n; i++) {
            if (!plain.get(i).equals(pumped.get(i))) {
                first = i;
                break;
            }
        }
        Log.info("BIDE style: steps=" + actions.size() + " plain=" + plain.size()
                + " pumped=" + pumped.size() + " firstDiff=" + first);
        if (first >= 0) {
            Log.info("BIDE style step " + first + " action=" + actions.get(first).label);
            Log.info("BIDE style  plain = " + plain.get(first));
            Log.info("BIDE style  pumped= " + pumped.get(first));
        }
    }

    /** The plan of a result as actions ("" when the search found nothing). */
    private static List<Action> actionsOf(Solver.Result r) {
        List<Action> out = new ArrayList<Action>();
        if (r != null && r.plan != null) {
            for (int i = 0; i < r.plan.size(); i++) {
                out.add(r.plan.get(i).action);
            }
        }
        return out;
    }

    /** One digest per step, applying the plan the way a replay does, with N idle frames per step. */
    private static List<String> walk(World root, List<Action> actions, int idleFrames) {
        List<String> out = new ArrayList<String>();
        World cur = root;
        Driver.begin();
        try {
            for (int i = 0; i < actions.size(); i++) {
                Action action = actions.get(i);
                World next = cur.fork();
                boolean installed = false;
                try {
                    next.install();
                    installed = true;
                    Legal.apply(action);
                    if (idleFrames > 0) {
                        Driver.pumpUntilSelection(idleFrames);
                    }
                    Solver.answerForReplay(action);
                    next.trimPresentation();
                    next.readBack();
                    out.add(Digest.stable(Digest.ofCurrent()));
                } catch (Throwable t) {
                    Log.info("BIDE style walk stopped at step " + i + " (" + action.label + "): " + t);
                    break;
                } finally {
                    if (installed) {
                        next.uninstall();
                    } else {
                        World.forceRestore();
                    }
                }
                cur = next;
            }
        } finally {
            Driver.end();
        }
        return out;
    }

    // ------------------------------------------------------------------ 5: A3 promotion itself

    /**
     * The live panel runs the quick search with an UNLIMITED budget ("已用 Ns（不限）") and stops when
     * the frontier dries up, which is exactly when A3 starts re-running parked candidates. The
     * blocking solve below takes the same route, so the promote failure the user's log shows
     * ("A3 promote: cannot rebuild a deferred path (19 actions)...") becomes reproducible offline.
     */
    private static void promotePhase(AbstractPlayer cainong) {
        World w = userWorld(cainong);
        Solver.Result r = Solver.solvePortfolio(w, 1000000000L);
        Log.info("BIDE unlimited: win=" + r.win + " steps=" + (r.plan == null ? -1 : r.plan.size())
                + " requested=" + r.planRequested + " verified=" + r.planVerified
                + " failedAt=" + r.planFailedAt + " failedOn=" + r.planFailedOn
                + " deferred=" + r.deferred + " promoted=" + r.promoted + " " + r.summary());
    }

    /** The user's battle from its digest, without running anything on it. */
    private static World userWorld(AbstractPlayer cainong) {
        String[] deck = {"CaiNongMod:SuoKai", "CaiNongMod:Defend", "CaiNongMod:Strike",
                "CaiNongMod:Statuette", "CaiNongMod:PaiDianDian", "CaiNongMod:Strike",
                "CaiNongMod:Strike", "CaiNongMod:TangLaugh", "CaiNongMod:Defend",
                "CaiNongMod:BideTime", "CaiNongMod:AllTheSame", "CaiNongMod:Defend"};
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261103L;
        spec.maxHealth = 80;
        spec.ascension = 4;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[]{"Empty Cage", "Golden Idol"};
        spec.deck = deck;
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        AbstractDungeon.player.currentHealth = 58;
        upgradeEvery(BIDE);
        return World.captureLive();
    }

    /**
     * The exact walk {@code Solver.materialize} performs: a fresh fork per step, applied with the
     * previous step's world installed, then read back and uninstalled.
     */
    private static int perStepReplay(World root, List<Action> actions) {
        int done = 0;
        World cur = root;
        Driver.begin();
        try {
            for (int i = 0; i < actions.size(); i++) {
                Action action = actions.get(i);
                World next = cur.fork();
                boolean installed = false;
                try {
                    next.install();
                    installed = true;
                    Legal.apply(action);
                    Solver.answerForReplay(action);
                    next.trimPresentation();
                    next.readBack();
                    done++;
                } catch (Throwable t) {
                    Log.info("BIDE perStep stopped at step " + i + " on " + action.label + ": " + t);
                    break;
                } finally {
                    if (installed) {
                        next.uninstall();
                    } else {
                        World.forceRestore();
                    }
                }
                cur = next;
            }
        } finally {
            Driver.end();
        }
        return done;
    }

    /** The fights come from a digest that showed an upgraded copy ("CaiNongMod:BideTime+"). */
    private static void upgradeEvery(String cardId) {
        upgradePile(AbstractDungeon.player.hand, cardId);
        upgradePile(AbstractDungeon.player.drawPile, cardId);
        upgradePile(AbstractDungeon.player.discardPile, cardId);
    }

    private static void upgradePile(com.megacrit.cardcrawl.cards.CardGroup g, String cardId) {
        if (g == null) {
            return;
        }
        for (int i = 0; i < g.size(); i++) {
            AbstractCard c = g.group.get(i);
            if (c != null && cardId.equals(c.cardID) && !c.upgraded) {
                c.upgrade();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static World buildWorld(AbstractPlayer cainong) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261102L;
        spec.maxHealth = 80;
        spec.ascension = 4;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[0];
        spec.deck = new String[]{BIDE, DEFEND, DEFEND, STRIKE, STRIKE, DEFEND,
                "Strike_R", "Defend_R", "CaiNongMod:SuoKai", "CaiNongMod:TangLaugh"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        return World.captureLive();
    }

    private static AbstractPlayer registered(String className) {
        List<AbstractPlayer> all = CardCrawlGame.characterManager.getAllCharacters();
        for (int i = 0; i < all.size(); i++) {
            AbstractPlayer p = all.get(i);
            if (p != null && className.equals(p.getClass().getName())) {
                return p;
            }
        }
        return null;
    }

    /** Moves a card of this id into the hand so deck order cannot make the probe skip. */
    private static AbstractCard intoHand(String cardId) {
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c != null && cardId.equals(c.cardID)) {
                return c;
            }
        }
        for (int i = 0; i < AbstractDungeon.player.drawPile.size(); i++) {
            AbstractCard c = AbstractDungeon.player.drawPile.group.get(i);
            if (c != null && cardId.equals(c.cardID)) {
                AbstractDungeon.player.drawPile.group.remove(i);
                AbstractDungeon.player.hand.addToTop(c);
                return c;
            }
        }
        for (int i = 0; i < AbstractDungeon.player.discardPile.size(); i++) {
            AbstractCard c = AbstractDungeon.player.discardPile.group.get(i);
            if (c != null && cardId.equals(c.cardID)) {
                AbstractDungeon.player.discardPile.group.remove(i);
                AbstractDungeon.player.hand.addToTop(c);
                return c;
            }
        }
        return null;
    }

    private static AbstractMonster monster() {
        if (AbstractDungeon.getMonsters() == null || AbstractDungeon.getMonsters().monsters.isEmpty()) {
            return null;
        }
        return AbstractDungeon.getMonsters().monsters.get(0);
    }

    /** The mod's own static flag, read from the class itself. */
    private static String flag() {
        try {
            Class<?> c = Class.forName(BIDE_CLASS);
            java.lang.reflect.Field f = c.getDeclaredField("isUseAttack");
            f.setAccessible(true);
            return String.valueOf(f.getBoolean(null));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int powerCount() {
        int n = 0;
        if (AbstractDungeon.player == null || AbstractDungeon.player.powers == null) {
            return 0;
        }
        for (int i = 0; i < AbstractDungeon.player.powers.size(); i++) {
            AbstractPower p = AbstractDungeon.player.powers.get(i);
            if (p != null && p.getClass().getName().toLowerCase().contains("bidetime")) {
                n++;
            }
        }
        return n;
    }

    private static boolean offered(String cardId) {
        for (Action a : Legal.actions()) {
            if (cardId.equals(a.cardId)) {
                return true;
            }
        }
        return false;
    }

    private static int countLegal() {
        int n = 0;
        for (Action ignored : Legal.actions()) {
            n++;
        }
        return n;
    }

    private static String handNames() {
        StringBuilder sb = new StringBuilder();
        if (AbstractDungeon.player == null || AbstractDungeon.player.hand == null) {
            return "-";
        }
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.cardID);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String line(List<Action> actions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(actions.get(i).label);
        }
        return sb.toString();
    }

    private static void finish() {
        Log.info("AUTOTEST RESULT passed=1 failed=0");
        Log.info("AUTOTEST DONE");
        try {
            java.io.File req = new java.io.File(sts1solver.SolverMod.requestPath());
            if (req.exists()) {
                req.delete();
            }
        } catch (Throwable ignored) {
            // run.ps1 removes it too
        }
    }
}
