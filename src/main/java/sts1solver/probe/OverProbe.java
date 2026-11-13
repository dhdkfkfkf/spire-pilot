package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * B125 (user m03996): the fight is over, so a turn has no decisions. This probe prints what the
 * search can still do from a decided state and what a plan looks like when the killing blow lands
 * during the ENEMY turn (the thorns case) -- the shape the player reported as "4 结束回合 in turn 3".
 */
public final class OverProbe {

    private OverProbe() {
    }

    public static void run() {
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 20261105L;
            spec.maxHealth = 80;
            spec.ascension = 0;
            spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Strike_R",
                    "Defend_R", "Defend_R", "Defend_R"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            Driver.drain(4000, 800L);
            World root = World.captureLive();
            boolean installed = false;
            try {
                root.install();
                installed = true;
                // 40 thorns + 25 block: the worm kills itself on our turn-2 attack, so the winning
                // blow lands inside the enemy turn -- exactly the reported shape.
                AbstractDungeon.player.addPower(
                        new com.megacrit.cardcrawl.powers.ThornsPower(AbstractDungeon.player, 40));
                root.readBack();
            } finally {
                if (installed) {
                    try {
                        root.uninstall();
                    } catch (Throwable t) {
                        World.forceRestore();
                    }
                }
            }
            Solver.Session s = new Solver.Session(root, 24, 96, 60000L, 0, 0);
            long deadline = System.currentTimeMillis() + 45000L;
            while (!s.finished() && System.currentTimeMillis() < deadline) {
                s.advance(20L);
            }
            Solver.Result r = s.result();
            Log.info("OVER finished=" + s.finished() + " nodes=" + s.nodes()
                    + " win=" + (r != null && r.win) + " reason=" + (r == null ? "-" : r.reason)
                    + " hpLoss=" + (r == null ? -1 : r.hpLoss)
                    + " verified=" + (r != null && r.planVerified)
                    + " steps=" + (r == null ? -1 : r.plan.size()));
            if (r != null) {
                for (int i = 0; i < r.plan.size(); i++) {
                    Solver.Step st = r.plan.get(i);
                    Log.info("OVER plan " + i + ": turn=" + st.turn + " hp=" + st.hp
                            + " block=" + st.block + " endTurn=" + st.action.isEndTurn()
                            + " label=" + st.action.label
                            + " digest=" + (st.digest == null ? "-" : st.digest.substring(0, Math.min(60, st.digest.length()))));
                }
            }
            // What is still offered once the fight is decided?
            World probe = root.fork();
            boolean in2 = false;
            try {
                probe.install();
                in2 = true;
                Log.info("OVER live check: turn=" + GameActionManager.turn
                        + " combatOver=" + Driver.combatOver());
                java.util.List<sts1solver.search.Action> acts = Legal.actions();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < acts.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(acts.get(i).isEndTurn() ? "<end turn>" : acts.get(i).label);
                }
                Log.info("OVER candidates from the root of the search: " + acts.size() + " [" + sb + "]");
                // Replay the found plan and watch the turn counter on every end turn.
                Driver.begin();
                World w = root.fork();
                boolean in3 = false;
                try {
                    w.install();
                    in3 = true;
                    World ended = null;
                    for (int i = 0; i < r.plan.size(); i++) {
                        Solver.Step st = r.plan.get(i);
                        int before = GameActionManager.turn;
                        Legal.applyOrRefuse(st.action);
                        Solver.answerForReplay(st.action);
                        Log.info("OVER walk " + i + ": turn " + before + " -> " + GameActionManager.turn
                                + " combatOver=" + Driver.combatOver()
                                + " hp=" + AbstractDungeon.player.currentHealth
                                + " digest=" + Digest.stable(Digest.ofCurrent()));
                        if (ended == null && Driver.combatOver()) {
                            ended = World.captureLive();
                        }
                    }
                    if (ended != null) {
                        World e2 = ended.fork();
                        boolean in4 = false;
                        try {
                            e2.install();
                            in4 = true;
                            Log.info("OVER ended state: turn=" + GameActionManager.turn
                                    + " combatOver=" + Driver.combatOver());
                            java.util.List<sts1solver.search.Action> al = Legal.actions();
                            StringBuilder sb2 = new StringBuilder();
                            for (int i = 0; i < al.size(); i++) {
                                if (i > 0) {
                                    sb2.append(", ");
                                }
                                sb2.append(al.get(i).isEndTurn() ? "<end turn>" : al.get(i).label);
                            }
                            Log.info("OVER ended candidates: " + al.size() + " [" + sb2 + "]");
                            for (int k = 0; k < 4; k++) {
                                int tb = GameActionManager.turn;
                                Driver.endTurn();
                                Log.info("OVER ended endTurn " + k + ": turn " + tb + " -> "
                                        + GameActionManager.turn + " combatOver=" + Driver.combatOver()
                                        + " hp=" + AbstractDungeon.player.currentHealth
                                        + " digest=" + Digest.stable(Digest.ofCurrent()));
                            }
                        } finally {
                            if (in4) {
                                e2.uninstall();
                            }
                        }
                        Solver.Session s2 = new Solver.Session(ended, 24, 96, 20000L, 0, 0);
                        long dl = System.currentTimeMillis() + 20000L;
                        while (!s2.finished() && System.currentTimeMillis() < dl) {
                            s2.advance(20L);
                        }
                        Solver.Result r2 = s2.result();
                        Log.info("OVER search over a decided fight: finished=" + s2.finished()
                                + " nodes=" + s2.nodes() + " win=" + (r2 != null && r2.win)
                                + " steps=" + (r2 == null ? -1 : r2.plan.size()));
                        if (r2 != null) {
                            for (int i = 0; i < r2.plan.size(); i++) {
                                Solver.Step st = r2.plan.get(i);
                                Log.info("OVER decided plan " + i + ": turn=" + st.turn
                                        + " endTurn=" + st.action.isEndTurn() + " label=" + st.action.label);
                            }
                        }
                    }
                } finally {
                    if (in3) {
                        w.uninstall();
                    }
                    Driver.end();
                }
            } finally {
                if (in2) {
                    probe.uninstall();
                }
            }
        } catch (Throwable t) {
            Log.info("OVER probe threw: " + t);
        }
        Log.info("AUTOTEST DONE");
    }
}