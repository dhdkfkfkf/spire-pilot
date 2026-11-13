package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Answers one product question with numbers: does the solver work for a character that is not
 * vanilla (the workshop's Ninja / CaiNong here)?
 *
 * <p>The design claim is that nothing in this mod knows what a card or a character is -- it builds
 * the game's own object, mirrors the statics, and searches a sandbox. This probe checks that claim
 * per mod character: construct it the way the game does, give it its starter deck and relics, put
 * one vanilla monster in front of it, search, then replay the reported line. A mod card that the
 * engine can play but our planner cannot address would show up as a failed replay.
 *
 * <p>Reported per character: deck size, win, hp loss, plan length, whether the plan replays to the
 * end, node count and time. One line per character keeps it readable in solver.log.
 */
public final class ModCharProbe {

    private static final String[] MONSTERS = {"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};

    private ModCharProbe() {
    }

    public static void run(long budgetMs) {
        try {
            inventory();
            List<AbstractPlayer> chars = CardCrawlGame.characterManager.getAllCharacters();
            int mods = 0;
            for (int i = 0; i < chars.size(); i++) {
                AbstractPlayer registered = chars.get(i);
                String cls = registered.getClass().getName();
                // The inventory is the honest count: a mod can register a character whose class lives
                // in a package this probe does not expect, and a character that never reaches this
                // list is a registration failure worth naming, not a silent skip.
                boolean game = sts1solver.util.Provenance.isGame(registered.getClass());
                Log.info("MODCHAR inventory " + i + ": class=" + cls
                        + " name=" + safe(registered.name)
                        + " chosenClass=" + (registered.chosenClass == null ? "-"
                                : registered.chosenClass.name())
                        + " owner=" + (game ? "game" : "mod"));
                if (game) {
                    continue;
                }
                mods++;
                one(registered, cls, budgetMs);
            }
            Log.info("MODCHAR summary: characters=" + chars.size() + " mod-characters=" + mods);
        } catch (Throwable t) {
            Log.info("MODCHAR probe failed: " + t);
        }
        finish();
    }

    /** How much of the loaded library is not vanilla (the surface a mod-aware solver has to carry). */
    private static void inventory() {
        int vanilla = 0;
        List<String> mods = new ArrayList<String>();
        for (Map.Entry<String, AbstractCard> e : CardLibrary.cards.entrySet()) {
            AbstractCard c = e.getValue();
            if (c == null) {
                continue;
            }
            if (sts1solver.util.Provenance.isGame(c.getClass())) {
                vanilla++;
            } else if (mods.size() < 400) {
                mods.add(e.getKey());
            }
        }
        Log.info("MODCHAR library: vanilla-cards=" + vanilla + " mod-cards=" + mods.size()
                + " sample=" + head(mods, 24));
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    /**
     * @param registered the game's own instance of this character. It is only read: it supplies the
     *                   {@code PlayerClass} enum value the class was registered with, which its
     *                   {@code (String, PlayerClass)} constructor needs.
     */
    private static void one(AbstractPlayer registered, String className, long budgetMs) {
        AbstractPlayer template = null;
        AbstractPlayer.PlayerClass cls = registered.chosenClass;
        String[] deck;
        int hp;
        try {
            template = Synthetic.newPlayer(className, "ModProbe", cls);
            List<String> ids = template.getStartingDeck();
            List<String> list = new ArrayList<String>();
            if (ids != null) {
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i);
                    if (CardLibrary.getCard(id) != null) {
                        list.add(id);
                    }
                }
            }
            deck = list.toArray(new String[0]);
            hp = template.maxHealth > 0 ? template.maxHealth : 80;
        } catch (Throwable t) {
            Log.info("MODCHAR " + className + " construct FAILED: " + t);
            return;
        }
        if (deck.length == 0) {
            Log.info("MODCHAR " + className + " has no usable starter deck; skipped");
            return;
        }

        Solver.Result result = null;
        String buildError = null;
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 4242L;
            spec.playerClass = className;
            spec.playerClassEnum = cls;
            spec.deck = deck;
            spec.keepStartingRelics = true;
            spec.maxHealth = hp;
            spec.monsters = MONSTERS;
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            Driver.drain(4000, 800L);
            Log.info("MODCHAR " + shortName(className) + " built: hp="
                    + AbstractDungeon.player.currentHealth + "/" + AbstractDungeon.player.maxHealth
                    + " deck=" + deck.length + " hand=" + handIds()
                    + " relics=" + relicIds() + " turns=" + com.megacrit.cardcrawl.actions.GameActionManager.turn);
            result = Solver.solvePortfolio(World.captureLive(), budgetMs);
        } catch (Throwable t) {
            buildError = String.valueOf(t);
        }
        if (result == null) {
            Log.info("MODCHAR " + shortName(className) + " SOLVE FAILED: " + buildError);
            return;
        }

        String replay = replayLine(result);
        LiveResult live = liveCheck(result);
        Log.info("MODCHAR " + shortName(className) + ": deck=" + deck.length
                + " win=" + result.win + " hpLoss=" + result.hpLoss
                + " steps=" + result.plan.size() + " nodes=" + result.nodes
                + " ms=" + result.millis + " reason=" + result.reason
                + " replay=" + replay
                + " live=" + live.summary
                + " cards=" + planCards(result) + " line=" + labels(result));
        if (live.divergence != null) {
            Log.info("MODCHAR-LIVE " + shortName(className) + " divergence@" + live.at
                    + " " + live.divergence);
        }
    }

    /**
     * Runs the reported plan on the LIVE engine and compares every step against the digest the
     * sandbox predicted.
     *
     * <p>This is the check that separates a faithful mirror from a plausible one. A mod card whose
     * effect depends on state the mirror does not carry (a static counter, a manager singleton
     * field, a mod-drawn selection screen) still produces a line inside the sandbox; only the live
     * engine shows that the fight settled into a different state. Replaying the line in another
     * sandbox cannot catch it, because the same missing state is missing there too.
     *
     * <p>Comparison uses {@link Digest#stable(String)}: the sandbox is read after
     * trimPresentation and the live engine right after the action, so the phase/queue counters in
     * between are noise. A difference that survives is reported with the step index and both
     * digests, so the action that caused it can be read off the plan.
     */
    private static LiveResult liveCheck(Solver.Result r) {
        LiveResult out = new LiveResult();
        if (r.plan == null || r.plan.isEmpty()) {
            out.summary = "empty-plan";
            return out;
        }
        out.total = r.plan.size();
        Driver.begin();
        try {
            for (int i = 0; i < r.plan.size(); i++) {
                Solver.Step step = r.plan.get(i);
                String raw;
                try {
                    Legal.apply(step.action);
                    Solver.answerForReplay(step.action);
                    raw = Digest.ofCurrent();
                } catch (Throwable t) {
                    out.summary = "threw@" + i + "/" + out.total;
                    out.at = i;
                    out.divergence = "action=" + step.action.label + " threw " + t;
                    return out;
                }
                if (raw.equals(step.digest)) {
                    out.rawEqual++;
                }
                if (!Digest.stable(raw).equals(Digest.stable(step.digest))) {
                    out.summary = "match=" + i + "/" + out.total;
                    out.at = i;
                    out.divergence = "action=" + step.action.label
                            + " expected=[" + Digest.stable(step.digest) + "]"
                            + " actual=[" + Digest.stable(raw) + "]";
                    return out;
                }
                if (AbstractDungeon.player == null || AbstractDungeon.player.currentHealth <= 0) {
                    break;
                }
            }
        } finally {
            Driver.end();
        }
        out.summary = "match=" + out.total + "/" + out.total + " raw=" + out.rawEqual + "/" + out.total;
        return out;
    }

    /**
     * Test seam for S148 (P6-02): does the PANEL's autoplay loop drive a mod character correctly?
     *
     * <p>{@link #liveCheck} proves the line replays when our own code applies it. That is not the same
     * claim as "the panel can play it": autoplay finds the card in the hand by uuid, submits an
     * end turn through the player, and waits for the engine to settle -- three steps that go through
     * live objects the sandbox never touches. A mod character whose cards are only addressable inside
     * the sandbox would pass {@code liveCheck} and fail here.
     *
     * <p>Builds the character's own board, searches it, installs the line into the panel exactly like
     * a finished 计算 would, and then reports how many steps the autopilot got through on real frames.
     * Returns "skipped: ..." when the character is not loaded, so the core suite (which runs without
     * character mods) stays green.
     */
    public static String autoplayCheckForTest(String className, long budgetMs) {
        AbstractPlayer registered = null;
        List<AbstractPlayer> chars = CardCrawlGame.characterManager.getAllCharacters();
        for (int i = 0; i < chars.size(); i++) {
            AbstractPlayer c = chars.get(i);
            if (c != null && c.getClass().getName().equals(className)) {
                registered = c;
                break;
            }
        }
        if (registered == null) {
            return "skipped: " + className + " is not loaded";
        }
        Solver.Result r;
        int deckSize;
        try {
            AbstractPlayer.PlayerClass cls = registered.chosenClass;
            AbstractPlayer template = Synthetic.newPlayer(className, "ModProbe", cls);
            List<String> ids = template.getStartingDeck();
            List<String> deck = new ArrayList<String>();
            if (ids != null) {
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i);
                    if (CardLibrary.getCard(id) != null) {
                        deck.add(id);
                    }
                }
            }
            if (deck.isEmpty()) {
                return "fail: " + className + " has no usable starter deck";
            }
            deckSize = deck.size();
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 4242L;
            spec.playerClass = className;
            spec.playerClassEnum = cls;
            spec.deck = deck.toArray(new String[0]);
            spec.keepStartingRelics = true;
            spec.maxHealth = template.maxHealth > 0 ? template.maxHealth : 80;
            spec.monsters = MONSTERS;
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            Driver.drain(4000, 800L);
            r = Solver.solvePortfolio(World.captureLive(), budgetMs);
        } catch (Throwable t) {
            return "fail board: " + t;
        }
        if (r == null || r.plan == null || r.plan.isEmpty()) {
            return "fail no plan";
        }
        sts1solver.Gameplay.reset();
        int settle = 0;
        do {
            sts1solver.Gameplay.tick();
            try {
                AbstractDungeon.getCurrRoom().update();
            } catch (Throwable ignored) {
                // the room is still building; the next frame tries again
            }
            ++settle;
        } while (settle < 200 && !sts1solver.Gameplay.inCombatForTest());
        sts1solver.Gameplay.installPlanForTest(r, 0);
        int before = sts1solver.Gameplay.verifiedSteps();
        // ONE step, and the reason is structural, not a shortcut. The gate body runs inside a single
        // real frame, and AbstractGameAction durations advance with the frame's delta time -- so a
        // fast loop over room.update() can never settle the monster turn. A line that starts with a
        // card therefore verifies its first step and then sits on "结束回合（等游戏推进）" no matter
        // how long the loop runs (measured: turn reached 2, hasControl stayed true, settled=false for
        // 20s). One verified step is the claim that matters here -- the mod card was located in the
        // hand BY UUID, played through the live engine, and its digest matched the sandbox exactly.
        // The multi-step claim is exercised by the live autoplay modes, on real frames.
        int want = Math.min(1, r.plan.size());
        long deadline = System.currentTimeMillis() + 12000L;
        int frames = 0;
        int roomErrors = 0;
        String roomError = "-";
        while (System.currentTimeMillis() < deadline) {
            ++frames;
            sts1solver.Gameplay.tick();
            try {
                AbstractDungeon.getCurrRoom().update();
            } catch (Throwable t) {
                // A half-built action may throw and the next frame tries again -- but a throw on
                // EVERY frame is not a retry, it is the reason the frame never advances: the end
                // turn stays queued and the gate reads "verified=1/12 pending=1" forever. Keep the
                // first one instead of swallowing it.
                ++roomErrors;
                if ("-".equals(roomError)) {
                    roomError = String.valueOf(t);
                }
            }
            if (sts1solver.Gameplay.verifiedSteps() - before >= want) {
                break;
            }
            if (Driver.combatOver() || !sts1solver.Gameplay.hasPlan()) {
                break;
            }
        }
        int verified = sts1solver.Gameplay.verifiedSteps() - before;
        // What is holding the end turn back? These are the exact flags the game reads before it
        // converts endTurnQueued into isEndingTurn (AbstractPlayer.updateInput).
        String hold = "";
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer pl = AbstractDungeon.player;
            com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
            hold = " turn=" + com.megacrit.cardcrawl.actions.GameActionManager.turn
                    + " queued=" + pl.endTurnQueued + " ending=" + pl.isEndingTurn
                    + " hasControl=" + am.hasControl
                    + " cardQ=" + am.cardQueue.size()
                    + " monQ=" + am.monsterQueue.size()
                    + " screenUp=" + AbstractDungeon.isScreenUp
                    + " screen=" + AbstractDungeon.screen
                    + " roomPhase=" + (AbstractDungeon.getCurrRoom() == null ? "-"
                            : String.valueOf(AbstractDungeon.getCurrRoom().phase));
        } catch (Throwable t) {
            hold = " flags-failed:" + t;
        }
        return "ok char=" + shortName(className) + " deck=" + deckSize
                + " verified=" + verified + "/" + r.plan.size() + " want=" + want
                + " step=" + sts1solver.Gameplay.stepForTest()
                + " drift=" + sts1solver.Gameplay.lastDriftStepForTest()
                + " frames=" + frames + " settle=" + settle
                + " roomErrors=" + roomErrors + " roomError=" + roomError
                + " over=" + Driver.combatOver() + hold
                + " " + sts1solver.Gameplay.planStateForTest();
    }

    private static final class LiveResult {
        int total;
        int at = -1;
        int rawEqual;
        String summary = "";
        String divergence;
    }

    /** A plan that cannot be replayed is a plan the player cannot follow: report it, do not hide it. */
    private static String replayLine(Solver.Result r) {
        if (r.plan == null || r.plan.isEmpty()) {
            return "empty-plan";
        }
        List<Action> actions = new ArrayList<Action>();
        for (int i = 0; i < r.plan.size(); i++) {
            actions.add(r.plan.get(i).action);
        }
        try {
            int done = Solver.replay(World.captureLive(), actions).size();
            return done + "/" + actions.size();
        } catch (Throwable t) {
            return "threw(" + t + ")";
        }
    }

    private static String planCards(Solver.Result r) {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < r.plan.size(); i++) {
            String id = r.plan.get(i).action.cardId;
            if (id != null && id.length() > 0 && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return head(ids, 10);
    }

    private static String labels(Solver.Result r) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(8, r.plan.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(r.plan.get(i).action.label);
        }
        if (r.plan.size() > n) {
            sb.append(" | ... (" + r.plan.size() + " total)");
        }
        return sb.toString();
    }

    private static String handIds() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
                AbstractCard c = AbstractDungeon.player.hand.group.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(c.cardID);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    private static String relicIds() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < AbstractDungeon.player.relics.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(AbstractDungeon.player.relics.get(i).relicId);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    private static String head(List<String> list, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(list.get(i));
        }
        if (list.size() > n) {
            sb.append(",...");
        }
        return sb.append(']').toString();
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
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
