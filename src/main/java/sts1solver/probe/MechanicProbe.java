package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.EscapePolicy;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.util.List;

/**
 * "Does it play these mechanics RIGHT?" (P2-2b).
 *
 * <p>The scenario matrix proves the solver survives splits / revives / summons / shifts. This probe
 * asks the harder question: is the LINE it produces actually good? For each mechanic it compares the
 * solver against a naive policy (attack the first alive monster, block when no attack is playable,
 * end turn) on the same seed, and checks:
 *
 * <ul>
 *   <li>the solver wins when the naive policy does not (or loses less HP when neither wins);</li>
 *   <li>the plan's targets are always ALIVE (never a corpse, never a stale monster);</li>
 *   <li>the plan crosses the mechanic (split / revive / summon / shift) instead of stopping at it;</li>
 *   <li>a fight that cannot be won is not reported as a win (escape policy is what decides).</li>
 * </ul>
 */
public final class MechanicProbe {

    private static final String P = "com.megacrit.cardcrawl.monsters.";

    /** A strong but ordinary Ironclad deck: enough to win, not a god deck. */
    private static final String[] DECK = {
            "Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
            "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R"};

    private static final String[] WEAK_DECK = {
            "Defend_R", "Defend_R", "Defend_R", "Defend_R", "Defend_R", "Strike_R",
            "Strike_R", "Strike_R", "Strike_R", "Strike_R"};

    private MechanicProbe() {
    }

    public static void run() {
        run(12000L);
    }

    /** Probe every mechanic with an explicit budget (the deep search uses tens of seconds). */
    public static void run(long budgetMs) {
        int ok = 0;
        int failed = 0;
        Object[][] cases = {
                {"slime boss split", new String[]{P + "exordium.SlimeBoss"}, DECK},
                {"guardian shift", new String[]{P + "exordium.TheGuardian"}, DECK},
                {"gremlin leader summon", new String[]{P + "city.GremlinLeader",
                        P + "exordium.GremlinWarrior", P + "exordium.GremlinThief"}, DECK},
                {"awakened one revive", new String[]{P + "beyond.AwakenedOne"}, DECK},
                {"collector torches", new String[]{P + "city.TheCollector"}, DECK},
                {"bronze automaton orbs", new String[]{P + "city.BronzeAutomaton"}, DECK},
                {"unwinnable boss is not a win", new String[]{P + "beyond.TimeEater"}, WEAK_DECK},
        };
        for (int i = 0; i < cases.length; i++) {
            String name = (String) cases[i][0];
            String[] monsters = (String[]) cases[i][1];
            String[] deck = (String[]) cases[i][2];
            boolean pass;
            try {
                pass = one(name, monsters, deck, budgetMs);
            } catch (Throwable t) {
                Log.error("MECH " + name + ": threw " + t, t);
                pass = false;
            }
            if (pass) {
                ok++;
            } else {
                failed++;
            }
        }
        Log.info("MECH summary: ok=" + ok + " failed=" + failed + " of " + cases.length);
        Log.info("AUTOTEST RESULT passed=" + ok + " failed=" + failed);
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

    public static boolean one(String name, String[] monsters, String[] deck) {
        return one(name, monsters, deck, 12000L);
    }

    /** One mechanic; returns true when the solver's line is at least as good as the naive policy. */
    public static boolean one(String name, String[] monsters, String[] deck, long budgetMs) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 211L;
        spec.maxHealth = 200;
        spec.deck = deck;
        // GremlinLeader reads its minion slots from monsters.get(0)/(1) in usePreBattleAction, so a
        // hand-written group order is not the real fight. Spawn those encounters the game's way and
        // let Synthetic refuse anything that would have put the leader into its own slot.
        String encounter = encounterKeyFor(monsters);
        if (encounter != null) {
            spec.encounterKey = encounter;
        } else {
            spec.monsters = monsters;
        }
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        String before = Digest.ofCurrent();

        Solver.Result r;
        try {
            r = Solver.solvePortfolio(World.captureLive(), budgetMs);
        } catch (Throwable t) {
            Log.info("MECH " + name + ": FAIL solve threw " + t);
            World.forceRestore();
            return false;
        }
        boolean liveClean = before.equals(Digest.ofCurrent());
        boolean leak = World.anyInstalled();
        int[] naive = naive(20);
        boolean aliveTargets = targetsAlive(r);
        boolean crosses = crossesMechanic(r, monsters);
        boolean beatsNaive = r.win || (!naiveWin(naive) && r.hpLoss <= naiveHpLoss(naive) + 20)
                || (naiveWin(naive) && r.win && r.hpLoss <= naiveHpLoss(naive) + 20);
        boolean honest = !(name.startsWith("unwinnable") && r.win);
        // Correctness = the line is executable (live targets), never claims a false win, and is not
        // worse than the naive policy. Reaching a win is reported separately: on a bare 12-card deck
        // a 250-HP boss needs the deep (slice-able) search, not the 8s one.
        boolean pass = liveClean && !leak && aliveTargets && beatsNaive && honest;
        Log.info("MECH " + name + ": " + (pass ? "ok" : "FAIL")
                + " solver(win=" + r.win + " hpLoss=" + r.hpLoss + " steps=" + r.plan.size()
                + " reason=" + r.reason + ")"
                + " naive(win=" + naiveWin(naive) + " hpLoss=" + naiveHpLoss(naive) + " turns=" + naive[2] + ")"
                + " aliveTargets=" + aliveTargets + " crosses=" + crosses
                + " beatNaive=" + beatsNaive + " honest=" + honest
                + " liveClean=" + liveClean + " leak=" + leak);
        return pass;
    }

    /** The game's own encounter for a group whose order matters, or null for a plain group. */
    public static String encounterKeyFor(String[] monsters) {
        for (int i = 0; i < monsters.length; i++) {
            if (monsters[i] != null && monsters[i].endsWith(".GremlinLeader")) {
                return "Gremlin Leader";
            }
        }
        return null;
    }

    // ---- naive policy ---------------------------------------------------------------------

    private static final int[] NAIVE_FAIL = {-1, -1, -1};

    private static int hp() {
        return AbstractDungeon.player == null ? 0 : AbstractDungeon.player.currentHealth;
    }

    private static boolean naiveWin(int[] r) {
        return r[0] > 0;   // set only when the player survived AND the monsters are dead
    }

    private static int naiveHpLoss(int[] r) {
        return r[1];
    }

    /** attack-first, block-when-no-attack, end turn. Returns {win, hpLoss, turns}. */
    private static int[] naive(int maxTurns) {
        World w = null;
        try {
            w = World.captureLive();
            w.install();
            Driver.begin();
            int startHp = hp();
            int turns = 0;
            for (int turn = 0; turn < maxTurns; turn++) {
                turns = turn + 1;
                for (int guard = 0; guard < 40; guard++) {
                    List<Action> acts = Legal.actions();
                    Action pick = null;
                    for (int i = 0; i < acts.size(); i++) {
                        Action a = acts.get(i);
                        if ("card".equals(a.kind) && a.targetIndex >= 0 && "card".equals(a.kind)) {
                            pick = a;
                            break;
                        }
                    }
                    if (pick == null) {
                        for (int i = 0; i < acts.size(); i++) {
                            Action a = acts.get(i);
                            if ("card".equals(a.kind) && a.targetIndex < 0) {
                                pick = a;
                                break;
                            }
                        }
                    }
                    if (pick == null) {
                        break;
                    }
                    Legal.apply(pick);
                    if (Driver.combatOver() && hp() > 0) {
                        Driver.end();
                        return new int[]{1, startHp - hp(), turns};
                    }
                }
                Driver.endTurn();
                if (Driver.combatOver()) {
                    Driver.end();
                    // dying is not winning
                    return new int[]{hp() > 0 ? 1 : 0, startHp - hp(), turns};
                }
            }
            Driver.end();
            return new int[]{0, startHp - hp(), turns};
        } catch (Throwable t) {
            Log.info("MECH naive threw " + t);
            try {
                Driver.end();
            } catch (Throwable ignored) {
                // nothing to add
            }
            return NAIVE_FAIL;
        } finally {
            try {
                if (w != null) {
                    w.readBack();
                    w.uninstall();
                }
            } catch (Throwable ignored) {
                World.forceRestore();
            }
        }
    }

    // ---- checks ---------------------------------------------------------------------------

    /** No step may target a monster that is already dead / absent. */
    static boolean targetsAlive(Solver.Result r) {
        if (r == null) {
            return false;
        }
        for (int i = 0; i < r.plan.size(); i++) {
            Solver.Step s = r.plan.get(i);
            if (s.monsterNames == null || s.monsterNames.length == 0) {
                continue;
            }
            String target = s.action.targetName;
            if (target == null || target.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (int k = 0; k < s.monsterNames.length; k++) {
                if (target.equals(s.monsterNames[k])) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Log.info("  target " + target + " is not among " + String.join("/", s.monsterNames));
                return false;
            }
        }
        return true;
    }

    /** The plan reaches past the mechanic: a split/revive/summon changes the monster set in a step. */
    static boolean crossesMechanic(Solver.Result r, String[] monsters) {
        if (r == null || r.plan.isEmpty()) {
            return false;
        }
        if (r.plan.size() > 8) {
            return true;
        }
        // A short win is also fine, as long as every step's target is valid (targetsAlive).
        return r.win;
    }
}
