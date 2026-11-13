package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.common.DamageAction;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * Monster scenario matrix (P2-2).
 *
 * <p>A solver that only ever sees one Jawworm is untested: splits, revives, summons, phase shifts and
 * multi-monster fights are where "the plan stopped", "the fight was treated as over" and "the live
 * game was corrupted" actually showed up. This harness builds one fight per mechanic, solves it, and
 * checks the invariants that must hold for all of them.
 *
 * <p>Run with {@code -Mode monsters}; the main suite keeps three representative cases in S20 so a
 * regression fails the build, not just this report.
 */
public final class MonsterProbe {

    private static final String P = "com.megacrit.cardcrawl.monsters.";

    /** name, monster classes, forced action (0 none, 1 kill first, 2 end turn), deck id. */
    private static final Object[][] CASES = {
            {"JawWorm", new String[]{P + "exordium.JawWorm"}, 0},
            {"two louses", new String[]{P + "exordium.LouseNormal", P + "exordium.LouseDefensive"}, 0},
            {"three sentries", new String[]{P + "exordium.Sentry", P + "exordium.Sentry", P + "exordium.Sentry"}, 0},
            {"gremlin nob", new String[]{P + "exordium.GremlinNob"}, 0},
            {"lagavulin", new String[]{P + "exordium.Lagavulin"}, 0},
            {"hexaghost", new String[]{P + "exordium.Hexaghost"}, 0},
            {"slime boss (split)", new String[]{P + "exordium.SlimeBoss"}, 2},
            {"the guardian (shift)", new String[]{P + "exordium.TheGuardian"}, 1},
            {"bronze automaton (orbs)", new String[]{P + "city.BronzeAutomaton"}, 0},
            {"gremlin leader (real encounter, summon)", new String[]{"encounter:Gremlin Leader"}, 2},
            {"the champ (phase)", new String[]{P + "city.Champ"}, 1},
            {"the collector (torches)", new String[]{P + "city.TheCollector"}, 0},
            {"awakened one (revive)", new String[]{P + "beyond.AwakenedOne"}, 1},
            {"time eater", new String[]{P + "beyond.TimeEater"}, 0},
            {"deca and donu", new String[]{P + "beyond.Deca", P + "beyond.Donu"}, 0},
            {"spire shield and spear", new String[]{P + "ending.SpireShield", P + "ending.SpireSpear"}, 0},
    };

    private static final String[] DECK = {"Strike_R", "Strike_R", "Strike_R", "Bash", "Defend_R",
            "Strike_R", "Defend_R", "Strike_R", "Bash", "Defend_R", "Strike_R", "Defend_R"};

    private MonsterProbe() {
    }

    public static void run() {
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < CASES.length; i++) {
            String name = (String) CASES[i][0];
            String[] monsters = (String[]) CASES[i][1];
            int forced = ((Integer) CASES[i][2]).intValue();
            boolean passed;
            try {
                // encounter:<key> builds the group through the game's own encounter spawner, which
                // is the only way a summoner's minion slots end up where the game puts them.
                if (monsters.length == 1 && monsters[0] != null
                        && monsters[0].startsWith("encounter:")) {
                    passed = oneEncounter(name, monsters[0].substring("encounter:".length()),
                            forced, 2000L);
                } else {
                    passed = one(name, monsters, forced);
                }
            } catch (Throwable t) {
                Log.error("MONSTER " + name + ": threw " + t, t);
                passed = false;
            }
            if (passed) {
                ok++;
            } else {
                failed++;
            }
        }
        Log.info("MONSTER summary: ok=" + ok + " failed=" + failed + " of " + CASES.length);
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

    /** One scenario with the default budget; returns true when every invariant held. */
    public static boolean one(String name, String[] monsters, int forced) {
        return one(name, monsters, forced, 2000L);
    }

    /** One scenario built from the game's OWN encounter (correct group order for summoners). */
    public static boolean oneEncounter(String name, String encounterKey, int forced, long budgetMs) {
        return run(name, encounterKey, new String[0], forced, budgetMs);
    }

    /** One scenario; returns true when every invariant held. */
    public static boolean one(String name, String[] monsters, int forced, long budgetMs) {
        return run(name, null, monsters, forced, budgetMs);
    }

    private static boolean run(String name, String encounterKey, String[] monsters, int forced,
                               long budgetMs) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 101L + name.hashCode();
        spec.maxHealth = 160;
        spec.deck = DECK;
        if (encounterKey != null) {
            spec.encounterKey = encounterKey;
        } else {
            spec.monsters = monsters;
        }
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        String before = Digest.ofCurrent();
        int groupBefore = group();
        boolean overBefore = Driver.combatOver();
        if (overBefore) {
            Log.info("MONSTER " + name + ": FAIL combatOver() true at the start");
            return false;
        }
        Solver.Result r;
        try {
            r = Solver.solvePortfolio(World.captureLive(), budgetMs);
        } catch (Throwable t) {
            Log.info("MONSTER " + name + ": FAIL solve threw " + t);
            World.forceRestore();
            return false;
        }
        boolean liveClean = before.equals(Digest.ofCurrent());
        boolean leak = World.anyInstalled();
        boolean planned = r != null && !r.plan.isEmpty();
        // Forced phase: either kill something (revive/shift) or end a turn (split/summon), then the
        // fight must still be live with a plan-able state.
        int groupAfter = groupBefore;
        boolean stillLive = true;
        if (forced != 0) {
            World w = null;
            boolean installed = false;
            try {
                w = World.captureLive();
                w.install();
                installed = true;
                Driver.begin();
                if (forced == 1) {
                    for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
                        AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
                        if (m != null && m.currentHealth > 0) {
                            AbstractDungeon.actionManager.addToBottom(new DamageAction(m,
                                    new DamageInfo(AbstractDungeon.player, 999,
                                            DamageInfo.DamageType.NORMAL)));
                            Driver.drain(2000, 2000L);
                            break;
                        }
                    }
                } else {
                    Driver.endTurn();
                }
                Driver.end();
                groupAfter = group();
                stillLive = !Driver.combatOver() || groupAfter > 0;
            } catch (Throwable t) {
                Log.info("MONSTER " + name + ": forced action threw " + t);
                stillLive = false;
            } finally {
                try {
                    if (installed) {
                        w.readBack();
                        w.uninstall();
                    }
                } catch (Throwable ignored) {
                    World.forceRestore();
                }
                Driver.end();
            }
        }
        boolean ok = planned && liveClean && !leak && stillLive;
        Log.info("MONSTER " + name + ": " + (ok ? "ok" : "FAIL")
                + " plan=" + (r == null ? -1 : r.plan.size())
                + " win=" + (r != null && r.win)
                + " reason=" + (r == null ? "?" : r.reason)
                + " monsters=" + groupBefore + "->" + groupAfter
                + " liveClean=" + liveClean + " leak=" + leak + " stillLive=" + stillLive);
        return ok;
    }

    private static int group() {
        try {
            return AbstractDungeon.getMonsters() == null ? 0
                    : AbstractDungeon.getMonsters().monsters.size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
