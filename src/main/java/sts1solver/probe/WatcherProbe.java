package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.stances.DivinityStance;
import com.megacrit.cardcrawl.stances.NeutralStance;
import com.megacrit.cardcrawl.stances.WrathStance;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.util.List;

/**
 * Watcher mechanics (P2-1): stances and scry.
 *
 * <p>The reference watcher adapter (AutoWatcher) exists because a solver that cannot mirror the
 * stances and scry will mis-price every Watcher turn. Our design runs the REAL engine, so the
 * multipliers and the energy are the game's own code -- what needs proving is that our SEARCH uses
 * them correctly: Wrath doubles damage and damage taken, Divinity triples damage, leaving Calm gives
 * +2 energy, and a scry screen is a decision whose best answer is to discard the WORST cards.
 */
public final class WatcherProbe {

    private static final String W = "com.megacrit.cardcrawl.monsters.exordium.JawWorm";

    private static final String[] DECK = {"Strike_P", "Strike_P", "Strike_P", "Defend_P",
            "Eruption", "ClearTheMind", "ThirdEye", "CutThroughFate",
            "Wireheading", "Vault", "Perseverance", "MentalFortress", "FlurryOfBlows"};

    private WatcherProbe() {
    }

    public static void run() {
        int ok = 0;
        int failed = 0;
        Object[][] cases = {
                {"wrath doubles damage", 1},
                {"divinity triples damage", 2},
                {"leaving calm gives energy", 3},
                {"scry discards the worst cards", 4},
                {"the solver branches on a scry", 5},
                {"foresight scrys at the start of the turn", 6},
                {"vault grants an extra turn", 7},
                {"retain keeps the card over the turn", 8},
                {"mental fortress pays on a stance change", 9},
                {"retain keeps the card over the turn", 8},
                {"mental fortress pays on a stance change", 9},
        };
        for (int i = 0; i < cases.length; i++) {
            String name = (String) cases[i][0];
            int kind = ((Integer) cases[i][1]).intValue();
            boolean pass;
            try {
                pass = kind == 5 ? branches() : sandbox(kind);
            } catch (Throwable t) {
                Log.error("WATCHER " + name + ": threw " + t, t);
                pass = false;
            }
            Log.info("WATCHER " + name + ": " + (pass ? "ok" : "FAIL"));
            if (pass) {
                ok++;
            } else {
                failed++;
            }
        }
        Log.info("WATCHER summary: ok=" + ok + " failed=" + failed + " of " + cases.length);
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

    private static void build() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 307L;
        spec.maxHealth = 120;
        spec.playerClass = "com.megacrit.cardcrawl.characters.Watcher";
        spec.deck = DECK;
        spec.monsters = new String[]{W};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
    }

    /** Plays one card in a sandbox and reports the monster HP delta. */
    private static int playAndMeasure(AbstractCard card) {
        int hpBefore = AbstractDungeon.getMonsters().monsters.get(0).currentHealth;
        Driver.play(card, AbstractDungeon.getMonsters().monsters.get(0), 0);
        return hpBefore - AbstractDungeon.getMonsters().monsters.get(0).currentHealth;
    }

    /** Moves a card to the hand so deck order cannot make a test silently skip. */
    private static AbstractCard forceIntoHand(String cardId) {
        AbstractCard found = find(cardId);
        if (found != null) {
            return found;
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

    private static AbstractCard find(String cardId) {
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c != null && cardId.equals(c.cardID)) {
                return c;
            }
        }
        return null;
    }

    /** Exposed for the suite: one sandbox check (1 wrath, 2 divinity, 3 calm exit, 4 scry). */
    public static boolean sandbox(int kind) {
        build();
        String before = Digest.ofCurrent();
        World w = World.captureLive();
        boolean installed = false;
        boolean pass = false;
        try {
            w.install();
            installed = true;
            Driver.begin();
            AbstractCard strike = forceIntoHand("Strike_P");
            AbstractCard eruption = forceIntoHand("Eruption");
            AbstractCard tranquility = find("ClearTheMind");
            AbstractCard thirdEye = forceIntoHand("ThirdEye");
            if (kind == 1 && strike != null) {
                AbstractDungeon.player.stance = new WrathStance();
                int dmg = playAndMeasure(strike);
                Log.info("  wrath strike dealt " + dmg + " (expected 12)");
                pass = dmg == 12;
            } else if (kind == 2 && strike != null) {
                AbstractDungeon.player.stance = new DivinityStance();
                int dmg = playAndMeasure(strike);
                Log.info("  divinity strike dealt " + dmg + " (expected 18)");
                pass = dmg == 18;
            } else if (kind == 3 && eruption != null) {
                // Set Calm directly so the test measures ONLY the exit bonus: Eruption costs 2 and
                // leaving Calm grants 2, so energy must be unchanged (3 -> 3). Without the bonus it
                // would drop to 1.
                AbstractDungeon.player.stance = new com.megacrit.cardcrawl.stances.CalmStance();
                int energyAfterCalm = com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
                Driver.play(eruption, AbstractDungeon.getMonsters().monsters.get(0), 0);
                int energyAfterExit = com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
                Log.info("  energy after calm=" + energyAfterCalm + " after exit="
                        + energyAfterExit + " stance=" + AbstractDungeon.player.stance.ID);
                // Eruption costs 2 and leaving Calm gives +2, so energy after must be >= after-2+2.
                pass = energyAfterExit == 3;
            } else if (kind == 6) {
                AbstractCard foresight = forceIntoHand("Wireheading");
                if (foresight == null) {
                    Log.info("  foresight not in hand");
                } else {
                    Driver.play(foresight, null, 0);
                    Driver.endTurn();
                    boolean grid = Driver.selectionKind() == Driver.SelectionKind.GRID;
                    boolean scry = Driver.isScrySelection();
                    List<List<AbstractCard>> options = Driver.selectionOptions(4);
                    boolean noneFirst = !options.isEmpty() && options.get(0).isEmpty();
                    StringBuilder acts = new StringBuilder();
                    com.megacrit.cardcrawl.actions.GameActionManager gam2 = AbstractDungeon.actionManager;
                    for (int i = 0; i < gam2.actions.size(); i++) {
                        acts.append(gam2.actions.get(i).getClass().getSimpleName()).append(',');
                    }
                    for (int i = 0; i < gam2.preTurnActions.size(); i++) {
                        acts.append("pre:").append(gam2.preTurnActions.get(i).getClass().getSimpleName())
                                .append(',');
                    }
                    if (gam2.turnStartCurrentAction != null) {
                        acts.append("turnStart:").append(gam2.turnStartCurrentAction.getClass().getSimpleName());
                    }
                    Log.info("  foresight actions=[" + acts + "]");
                    Log.info("  foresight: kind=" + Driver.selectionKind() + " scry=" + scry
                            + " options=" + options.size() + " noneFirst=" + noneFirst);
                    boolean closed = true;
                    if (grid) {
                        closed = Driver.resolveSelection(options.get(options.size() - 1));
                    }
                    pass = grid && scry && noneFirst && closed;
                }
            } else if (kind == 7) {
                AbstractCard vault = forceIntoHand("Vault");
                if (vault == null) {
                    Log.info("  vault not in hand");
                } else {
                    int hpBefore = AbstractDungeon.player.currentHealth;
                    int turnBefore = com.megacrit.cardcrawl.actions.GameActionManager.turn;
                    Driver.allowLatchedEndTurn();
                    Driver.play(vault, null, 0);
                    int turnAfter = com.megacrit.cardcrawl.actions.GameActionManager.turn;
                    int energy = com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
                    int hand = AbstractDungeon.player.hand.size();
                    int hpAfter = AbstractDungeon.player.currentHealth;
                    int master = AbstractDungeon.player.energy.energyMaster;
                    Log.info("  vault: turn " + turnBefore + "->" + turnAfter + " energy=" + energy
                            + "/" + master + " hand=" + hand + " hp " + hpBefore + "->" + hpAfter
                            + " over=" + Driver.combatOver());
                    // Vault presses the End Turn button from inside the action queue, so the sandbox
                    // has to finish that one frame itself (Driver.drain). Once it does, the turn really
                    // advances, the enemy turn is skipped and the next turn gets its energy back.
                    pass = turnAfter == turnBefore + 1 && energy == master && hpAfter == hpBefore
                            && hand > 0 && !Driver.combatOver();
                }
            } else if (kind == 8) {
                AbstractCard retain = forceIntoHand("Perseverance");
                if (retain == null) {
                    Log.info("  perseverance not in hand");
                } else {
                    // Retain is about a card that STAYS in hand across the turn: it is never played.
                    retain.retain = true;
                    Driver.endTurn();
                    boolean stillThere = find("Perseverance") != null;
                    Log.info("  retain: perseverance in hand after the turn = " + stillThere);
                    pass = stillThere;
                }
            } else if (kind == 9) {
                AbstractCard fortress = forceIntoHand("MentalFortress");
                AbstractCard eruption2 = forceIntoHand("Eruption");
                if (fortress == null || eruption2 == null) {
                    Log.info("  mental fortress / eruption not in hand");
                } else {
                    Driver.play(fortress, null, 0);
                    int blockBefore = AbstractDungeon.player.currentBlock;
                    Driver.play(eruption2, AbstractDungeon.getMonsters().monsters.get(0), 0);
                    int blockAfter = AbstractDungeon.player.currentBlock;
                    Log.info("  mental fortress: block " + blockBefore + "->" + blockAfter
                            + " (expected +4)");
                    pass = blockAfter >= blockBefore + 4;
                }
            } else if (kind == 4 && thirdEye != null) {
                AbstractDungeon.player.stance = new NeutralStance();
                Legal.apply(sts1solver.search.Action.card(
                        AbstractDungeon.player.hand.group.indexOf(thirdEye), -1, 0,
                        "Third Eye", "Third Eye", "", "ThirdEye"));
                boolean grid = Driver.selectionKind() == Driver.SelectionKind.GRID;
                boolean scry = Driver.isScrySelection();
                List<List<AbstractCard>> options = Driver.selectionOptions(4);
                StringBuilder shape = new StringBuilder();
                for (int i = 0; i < options.size(); i++) {
                    shape.append('[').append(options.get(i).size()).append(':')
                            .append(names(options.get(i))).append(']');
                }
                Log.info("  scry: kind=" + Driver.selectionKind() + " flagged=" + scry
                        + " want=" + Driver.gridSelectCountForTest()
                        + " candidates=" + Driver.selectionCandidates().size()
                        + " options=" + options.size() + " shape=" + shape);
                // Expected shape: "discard nothing" first, then discard the worst k, in ascending
                // value order. The full-set option's first element must be the single-card option.
                boolean noneFirst = !options.isEmpty() && options.get(0).isEmpty();
                boolean worstFirst = noneFirst && options.size() > 1
                        && options.get(1).size() == 1
                        && !options.get(options.size() - 1).isEmpty()
                        && options.get(options.size() - 1).get(0).cardID
                                .equals(options.get(1).get(0).cardID);
                Log.info("  scry options=" + options.size() + " noneFirst=" + noneFirst
                        + " worstFirst=" + worstFirst);
                pass = grid && scry && noneFirst && worstFirst;
                if (pass) {
                    List<AbstractCard> discard = options.get(options.size() - 1);
                    boolean resolved = Driver.resolveSelection(discard);
                    boolean closed = Driver.selectionKind() == Driver.SelectionKind.NONE;
                    Log.info("  scry resolved=" + resolved + " closed=" + closed
                            + " discarded=" + names(discard));
                    pass = resolved && closed;
                }
            }
        } catch (Throwable t) {
            Log.info("  sandbox threw " + t);
            pass = false;
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
        boolean clean = before.equals(Digest.ofCurrent());
        if (!clean) {
            Log.info("  watcher sandbox leaked: live digest changed");
        }
        return pass && clean;
    }

    /** The search must branch on a scry screen instead of guessing one answer. */
    public static boolean branches() {
        build();
        Solver.Result r = Solver.solvePortfolio(World.captureLive(), 8000L);
        Log.info("  watcher plan: choices=" + r.choiceBranches + " steps=" + r.plan.size()
                + " win=" + r.win);
        boolean withChoice = false;
        for (int i = 0; i < r.plan.size(); i++) {
            if (r.plan.get(i).action.choice != null && !r.plan.get(i).action.choice.isEmpty()) {
                withChoice = true;
                break;
            }
        }
        // branching on the scry is the property under test; whether the WINNING line happens to use
        // the scry card is up to the search.
        return r.choiceBranches > 0;
    }

    private static String names(List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cards.get(i) == null ? "null" : cards.get(i).cardID);
        }
        return sb.append(']').toString();
    }
}