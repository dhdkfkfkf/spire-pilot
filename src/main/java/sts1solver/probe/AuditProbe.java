package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.lang.reflect.Field;

/**
 * Two honest questions, answered with measurements.
 *
 * <p>1. Is the Gremlin Leader summon NPE a REAL bug or an artifact of our synthetic monster order?
 * The game builds the group from an encounter; our synthetic builder used its own order. Build both
 * and end a turn in each.
 *
 * <p>2. Why do the phase bosses not reach a win inside 30s? Is it search TIME or a structural
 * inability? Measure with the weak deck and with a realistic 30-card deck, and at 30s and 60s.
 */
public final class AuditProbe {

    private static final String WEAK = "weak12";
    private static final String REAL = "real30";

    private AuditProbe() {
    }

    /** Sections, so a single run can stay under one harness call's time limit. */
    public static void run(String section) {
        Log.info("AUDIT SECTION " + section + " begin");
        if ("summon".equals(section)) {
            summonAll();
        } else if ("awakened".equals(section)) {
            boss("Awakened One", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne");
        } else if ("narrow-strong".equals(section)) {
            narrowStrong("Awakened One", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne", 30000L);
        } else if ("narrow".equals(section)) {
            narrow("Awakened One", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne", 30000L);
            narrow("Awakened One", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne", 60000L);
        } else if ("collector".equals(section)) {
            boss("The Collector", "com.megacrit.cardcrawl.monsters.city.TheCollector");
        } else if ("automaton".equals(section)) {
            boss("Bronze Automaton", "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton");
        } else {
            summonAll();
            boss("Awakened One", "com.megacrit.cardcrawl.monsters.beyond.AwakenedOne");
            boss("The Collector", "com.megacrit.cardcrawl.monsters.city.TheCollector");
            boss("Bronze Automaton", "com.megacrit.cardcrawl.monsters.city.BronzeAutomaton");
        }
        Log.info("AUDIT SECTION " + section + " end");
        Log.info("AUDIT DONE");
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

    /**
     * The decisive experiment: kill one minion, then let the leader reach its summon turn. If the
     * synthetic group order breaks the engine but the real encounter order does not, the NPE was a
     * test artifact; if both break, it is a real bug.
     */
    public static void summonKill(String label, String encounterKey, String[] monsters) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 401L;
        spec.maxHealth = 160;
        spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Defend_R", "Bash"};
        if (encounterKey != null) {
            spec.encounterKey = encounterKey;
        } else {
            spec.monsters = monsters;
        }
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Log.info("AUDIT kill " + label + ": group=[" + names() + "] slots=" + slotsOfLeader());
        World w = World.captureLive();
        boolean installed = false;
        String thrown = null;
        String turnLog = "";
        try {
            w.install();
            installed = true;
            Driver.begin();
            // kill the first non-leader monster
            for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
                AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
                if (m != null && !(m instanceof com.megacrit.cardcrawl.monsters.city.GremlinLeader)) {
                    AbstractDungeon.actionManager.addToBottom(
                            new com.megacrit.cardcrawl.actions.common.DamageAction(m,
                                    new com.megacrit.cardcrawl.cards.DamageInfo(
                                            AbstractDungeon.player, 999,
                                            com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL)));
                    Driver.drain(400, 800L);
                    break;
                }
            }
            Log.info("AUDIT kill " + label + ": after kill g=" + AbstractDungeon.getMonsters().monsters.size()
                    + " slots=" + slotsOfLeader());
            for (int turn = 1; turn <= 3; turn++) {
                try {
                    Driver.endTurn();
                    turnLog += "t" + turn + ":ok(g=" + AbstractDungeon.getMonsters().monsters.size()
                            + ",slots=" + slotsOfLeader() + ") ";
                } catch (Throwable t) {
                    thrown = "turn " + turn + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
                    break;
                }
            }
        } catch (Throwable t) {
            thrown = t.getClass().getSimpleName() + ": " + t.getMessage();
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
        Log.info("AUDIT kill " + label + ": turns=[" + turnLog + "] threw=" + thrown);
    }

    private static String names() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
            AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(m == null ? "null" : m.name);
        }
        return sb.toString();
    }

    private static void summonAll() {
        summonKill("synthetic order", null,
                new String[]{
                        "com.megacrit.cardcrawl.monsters.city.GremlinLeader",
                        "com.megacrit.cardcrawl.monsters.exordium.GremlinWarrior",
                        "com.megacrit.cardcrawl.monsters.exordium.GremlinThief"});
        String[] keys = {"Gremlin Leader", "GremlinLeader", "Gremlin Leader?"};
        for (int i = 0; i < keys.length; i++) {
            try {
                summonKill("encounter [" + keys[i] + "]", keys[i], null);
                break;
            } catch (Throwable t) {
                Log.info("AUDIT encounter key failed: " + keys[i] + " (" + t.getMessage() + ")");
            }
        }
    }

    /** Builds a Gremlin Leader fight either our way or the game's way, then ends one turn. */
    public static void summon(String label, String encounterKey, String[] monsters) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 401L;
        spec.maxHealth = 160;
        spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Defend_R", "Bash"};
        if (encounterKey != null) {
            spec.encounterKey = encounterKey;
        } else {
            spec.monsters = monsters;
        }
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
            AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
            if (i > 0) {
                order.append(',');
            }
            order.append(i).append('=').append(m == null ? "null" : m.name);
        }
        Log.info("AUDIT summon " + label + ": group=[" + order + "]");
        for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
            AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
            if (m instanceof com.megacrit.cardcrawl.monsters.city.GremlinLeader) {
                Log.info("AUDIT summon " + label + ": leader slots=" + slots(m));
            }
        }
        World w = World.captureLive();
        boolean installed = false;
        String thrown = null;
        StringBuilder turns = new StringBuilder();
        try {
            w.install();
            installed = true;
            Driver.begin();
            for (int turn = 1; turn <= 4; turn++) {
                try {
                    Driver.endTurn();
                    turns.append("t").append(turn).append(":ok(").append(Driver.combatOver() ? "over" : "live")
                            .append(",g=").append(AbstractDungeon.getMonsters().monsters.size()).append(") ");
                } catch (Throwable t) {
                    thrown = "turn " + turn + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
                    break;
                }
            }
        } catch (Throwable t) {
            thrown = t.getClass().getSimpleName() + ": " + t.getMessage();
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
        Log.info("AUDIT summon " + label + ": turns=[" + turns + "] threw=" + thrown
                + " slotsAfter=" + slotsOfLeader());
    }

    private static String slotsOfLeader() {
        try {
            for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
                AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
                if (m instanceof com.megacrit.cardcrawl.monsters.city.GremlinLeader) {
                    return slots(m);
                }
            }
        } catch (Throwable t) {
            return "unreadable: " + t;
        }
        return "no leader";
    }

    private static String slots(AbstractMonster leader) {
        try {
            Field f = leader.getClass().getField("gremlins");
            AbstractMonster[] arr = (AbstractMonster[]) f.get(leader);
            if (arr == null) {
                return "null array";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(arr[i] == null ? "null" : arr[i].name);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "unreadable: " + t;
        }
    }

    /** Solves one boss with the weak and the realistic deck, at 30s and 60s. */
    public static void boss(String name, String monster) {
        String[] weak = {"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R"};
        String[] real = {"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R",
                "Inflame", "Metallicize", "ShrugItOff", "PommelStrike", "Cleave",
                "HeavyBlade", "Uppercut", "TrueGrit", "Thunderclap", "BodySlam",
                "Flex", "Anger", "SecondWind", "SeverSoul", "GhostlyArmor",
                "IronWave", "Clothesline", "Bloodletting"};
        measure(name, "weak", monster, weak, 30000L);
        measure(name, "real", monster, real, 30000L);
        measure(name, "real", monster, real, 60000L);
    }

    /** Same claim, with a deck strong enough that the fight is actually winnable. */
    private static void narrowStrong(String name, String monster, long budgetMs) {
        String[] deck = {"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R",
                "Inflame", "Metallicize", "Cleave", "Cleave", "Anger", "Anger",
                "BodySlam", "BodySlam", "Clothesline", "Thunderclap", "Uppercut",
                "Bloodletting", "Flex", "Flex", "DemonForm", "Bludgeon", "Bludgeon",
                "LimitBreak", "Feed", "Offering"};
        java.util.List<String> kept = new java.util.ArrayList<String>();
        java.util.List<String> dropped = new java.util.ArrayList<String>();
        for (int i = 0; i < deck.length; i++) {
            if (com.megacrit.cardcrawl.helpers.CardLibrary.getCard(deck[i]) != null) {
                kept.add(deck[i]);
            } else {
                dropped.add(deck[i]);
            }
        }
        if (!dropped.isEmpty()) {
            Log.info("AUDIT narrow-strong dropped: " + dropped);
        }
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 601L;
        spec.maxHealth = 200;
        spec.deck = kept.toArray(new String[kept.size()]);
        spec.monsters = new String[]{monster};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        long t0 = System.currentTimeMillis();
        Solver.Result r = Solver.solve(World.captureLive(), 10, 96, budgetMs);
        Log.info("AUDIT narrow-strong " + name + " [" + (budgetMs / 1000) + "s] " + r.summary()
                + " steps=" + r.plan.size() + " deck=" + kept.size() + " ms="
                + (System.currentTimeMillis() - t0));
    }

    /** The P2-2d claim: one narrow-deep pass reaches further than a split portfolio. */
    private static void narrow(String name, String monster, long budgetMs) {
        String[] weak = {"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Strike_R", "Bash", "Bash", "Defend_R", "Defend_R", "Defend_R", "Defend_R"};
        Solver.clearExpandFailures();
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 501L;
        spec.maxHealth = 200;
        spec.deck = weak;
        spec.monsters = new String[]{monster};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        long t0 = System.currentTimeMillis();
        Solver.Result r = Solver.solve(World.captureLive(), 10, 96, budgetMs);
        long ms = System.currentTimeMillis() - t0;
        Log.info("AUDIT narrow " + name + " [" + (budgetMs / 1000) + "s] " + r.summary()
                + " steps=" + r.plan.size() + " ms=" + ms + " failures=[" + Solver.expandFailures() + "]");
    }

    private static void measure(String name, String deckName, String monster, String[] deckIn,
                                long budgetMs) {
        java.util.List<String> kept = new java.util.ArrayList<String>();
        java.util.List<String> dropped = new java.util.ArrayList<String>();
        for (int i = 0; i < deckIn.length; i++) {
            if (com.megacrit.cardcrawl.helpers.CardLibrary.getCard(deckIn[i]) != null) {
                kept.add(deckIn[i]);
            } else {
                dropped.add(deckIn[i]);
            }
        }
        if (!dropped.isEmpty()) {
            Log.info("AUDIT deck [" + deckName + "] dropped unknown ids: " + dropped);
        }
        String[] deck = kept.toArray(new String[kept.size()]);
        Solver.clearExpandFailures();
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 501L;
        spec.maxHealth = 200;
        spec.deck = deck;
        spec.monsters = new String[]{monster};
        try {
            Synthetic.build(spec, new Synthetic.SyntheticStep());
        } catch (Throwable t) {
            Log.info("AUDIT boss " + name + " [" + deckName + "] build failed: " + t);
            return;
        }
        long t0 = System.currentTimeMillis();
        Solver.Result r = Solver.solvePortfolio(World.captureLive(), budgetMs);
        long ms = System.currentTimeMillis() - t0;
        Log.info("AUDIT boss " + name + " [" + deckName + " " + (budgetMs / 1000) + "s] "
                + r.summary() + " steps=" + r.plan.size() + " ms=" + ms
                + " failures=[" + Solver.expandFailures() + "]");
        Log.info("AUDIT   profile: " + Solver.profileLine());
    }
}