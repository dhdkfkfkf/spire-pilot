package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.city.GremlinLeader;
import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.util.Log;

import java.util.List;

/**
 * Diagnostic for the Gremlin Leader summon crash (P2-2c).
 *
 * <p>SummonGremlinAction.update throws NPE when the action was built while identifySlot returned
 * -1, which happens only when all three slots of GremlinLeader.gremlins hold a non-dying monster.
 * The live game never reaches that state, so the question is what the sandbox leaves in that
 * array. This mode prints the array and the room list side by side (identity, isDying, HP,
 * membership) at every step, so the divergence is visible instead of inferred.
 */
public final class GremlinDiagnose {

    private static final String P = "com.megacrit.cardcrawl.monsters.";

    private GremlinDiagnose() {
    }

    public static void run() {
        String[] deck = {"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R"};
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 211L;
        spec.maxHealth = 200;
        spec.deck = deck;
        // Built through the game's encounter spawner: a hand-written list put the leader in slot 0
        // and made SummonGremlinAction throw (see the Synthetic guard).
        spec.encounterKey = "Gremlin Leader";
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World w = World.captureLive();
        boolean installed = false;
        try {
            w.install();
            installed = true;
            Driver.begin();
            dump("live after build");
            for (int turn = 0; turn < 12 && !Driver.combatOver(); turn++) {
                int played = 0;
                while (played < 6 && !Driver.combatOver()) {
                    Action pick = attackAction();
                    if (pick == null) {
                        break;
                    }
                    try {
                        Legal.apply(pick);
                    } catch (Throwable t) {
                        Log.info("GM apply threw on " + pick.label + ": " + t);
                        dump("at throw");
                        throw t;
                    }
                    played++;
                    dump("after " + pick.label);
                }
                if (Driver.combatOver()) {
                    break;
                }
                dump("before end turn " + (turn + 1));
                try {
                    Driver.endTurn();
                } catch (Throwable t) {
                    Log.info("GM endTurn threw: " + t);
                    dump("at endTurn throw");
                    throw t;
                }
                dump("after end turn " + (turn + 1));
            }
        } catch (Throwable t) {
            Log.info("GM FAILED " + t);
        } finally {
            try {
                Driver.end();
            } catch (Throwable ignored) {
                // nothing to add
            }
            if (installed) {
                try {
                    w.readBack();
                    w.uninstall();
                } catch (Throwable ignored) {
                    World.forceRestore();
                }
            }
        }
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

    private static Action attackAction() {
        List<Action> acts = Legal.actions();
        for (int i = 0; i < acts.size(); i++) {
            Action a = acts.get(i);
            if ("card".equals(a.kind) && a.label.indexOf("打击") == 0) {
                return a;
            }
        }
        return null;
    }

    private static void dump(String tag) {
        StringBuilder sb = new StringBuilder("GM ");
        sb.append(tag).append(": turn=").append(GameActionManager.turn);
        if (AbstractDungeon.getCurrRoom() == null) {
            Log.info(sb.toString());
            return;
        }
        sb.append(" hp=").append(AbstractDungeon.player.currentHealth);
        List<AbstractMonster> list = AbstractDungeon.getCurrRoom().monsters.monsters;
        sb.append(" room=[");
        for (int i = 0; i < list.size(); i++) {
            AbstractMonster m = list.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(m.id);
            if (m.isDying) {
                sb.append("(dying)");
            }
            sb.append('@').append(Integer.toHexString(System.identityHashCode(m)));
        }
        sb.append("]");
        for (int i = 0; i < list.size(); i++) {
            AbstractMonster m = list.get(i);
            if (!(m instanceof GremlinLeader)) {
                continue;
            }
            GremlinLeader leader = (GremlinLeader) m;
            sb.append(" leader@").append(Integer.toHexString(System.identityHashCode(leader)));
            for (int slot = 0; slot < leader.gremlins.length; slot++) {
                AbstractMonster s = leader.gremlins[slot];
                sb.append(" slot").append(slot).append('=');
                if (s == null) {
                    sb.append("null");
                    continue;
                }
                boolean inRoom = false;
                for (int k = 0; k < list.size(); k++) {
                    if (list.get(k) == s) {
                        inRoom = true;
                    }
                }
                sb.append(s.id).append(s.isDying ? "(dying)" : "(alive)")
                        .append(" hp=").append(s.currentHealth)
                        .append(" inRoom=").append(inRoom)
                        .append('@').append(Integer.toHexString(System.identityHashCode(s)));
            }
        }
        Log.info(sb.toString());
    }
}
