package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.common.DiscardSpecificCardAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.PoisonPower;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * Silent mechanics (P2-1b): poison ticks and discard triggers.
 *
 * <p>These are the two places a Silent solver silently goes wrong: poison is a start-of-turn
 * damage-over-time (value = stacks, then -1), and several cards only do anything when they are
 * DISCARDED (Tactician: +1 energy, Reflex: draw 2, Eviscerate: cheaper per discard). Both run
 * through the real engine, so what is tested is that our sandbox observes them.
 */
public final class SilentProbe {

    private static final String W = "com.megacrit.cardcrawl.monsters.exordium.JawWorm";

    private static final String[] DECK = {"Strike_G", "Strike_G", "Defend_G",
            "Neutralize", "Survivor", "Prepared", "Deadly Poison", "Catalyst", "Tactician",
            "Reflex", "Eviscerate", "Bouncing Flask"};

    private SilentProbe() {
    }

    public static void run() {
        int ok = 0;
        int failed = 0;
        Object[][] cases = {
                {"poison ticks for its stacks then decays", 1},
                {"catalyst doubles the poison", 2},
                {"tactician pays 1 energy when discarded", 3},
                {"reflex draws 2 when discarded", 4},
                {"eviscerate gets cheaper per discard", 5},
        };
        for (int i = 0; i < cases.length; i++) {
            String name = (String) cases[i][0];
            int kind = ((Integer) cases[i][1]).intValue();
            boolean pass;
            try {
                pass = sandbox(kind);
            } catch (Throwable t) {
                Log.error("SILENT " + name + ": threw " + t, t);
                pass = false;
            }
            Log.info("SILENT " + name + ": " + (pass ? "ok" : "FAIL"));
            if (pass) {
                ok++;
            } else {
                failed++;
            }
        }
        Log.info("SILENT summary: ok=" + ok + " failed=" + failed + " of " + cases.length);
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

    static void build() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 701L;
        spec.maxHealth = 120;
        spec.playerClass = "com.megacrit.cardcrawl.characters.TheSilent";
        spec.deck = DECK;
        spec.monsters = new String[]{W};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
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
        return null;
    }

    /**
     * Discards a card through the REAL gameplay path (Prepared + answered selection).
     *
     * <p>Calling DiscardSpecificCardAction directly would skip triggerOnManualDiscard, i.e. it would
     * test the action, not the game's discard triggers. This drives the same path a player does.
     */
    private static boolean discardWithPrepared(String cardId) {
        AbstractCard prepared = forceIntoHand("Prepared");
        AbstractCard target = forceIntoHand(cardId);
        if (prepared == null || target == null) {
            Log.info("  discard path: missing " + (prepared == null ? "Prepared" : cardId));
            return false;
        }
        Driver.play(prepared, null, 0);
        java.util.List<AbstractCard> chosen = new java.util.ArrayList<AbstractCard>();
        chosen.add(target);
        return Driver.resolveSelection(chosen);
    }

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
            AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(0);
            if (kind == 1) {
                m.addPower(new PoisonPower(m, AbstractDungeon.player, 5));
                int hpBefore = m.currentHealth;
                Driver.endTurn();
                int dealt = hpBefore - m.currentHealth;
                int left = poisonOf(m);
                Log.info("  poison: dealt=" + dealt + " left=" + left);
                pass = dealt == 5 && left == 4;
            } else if (kind == 2) {
                m.addPower(new PoisonPower(m, AbstractDungeon.player, 5));
                AbstractCard catalyst = forceIntoHand("Catalyst");
                if (catalyst != null) {
                    Driver.play(catalyst, m, 0);
                    int left = poisonOf(m);
                    Log.info("  catalyst: poison=" + left);
                    pass = left == 10;
                }
            } else if (kind == 3) {
                AbstractCard tactician = forceIntoHand("Tactician");
                int energyBefore = EnergyPanel.totalCount;
                if (tactician != null) {
                    boolean discarded = discardWithPrepared("Tactician");
                    int energyAfter = EnergyPanel.totalCount;
                    Log.info("  tactician: discarded=" + discarded + " energy " + energyBefore
                            + "->" + energyAfter);
                    pass = discarded && energyAfter == energyBefore + 1;
                }
            } else if (kind == 4) {
                AbstractCard reflex = forceIntoHand("Reflex");
                int handBefore = AbstractDungeon.player.hand.size();
                if (reflex != null) {
                    boolean discarded = discardWithPrepared("Reflex");
                    int handAfter = AbstractDungeon.player.hand.size();
                    Log.info("  reflex: discarded=" + discarded + " hand " + handBefore
                            + "->" + handAfter);
                    // Prepared draws one too, so: -1 discarded +1 draw +2 reflex = +2 from handBefore
                    // Prepared leaves the hand itself: -1 (Prepared) -1 (discard) +1 (draw) +2 (reflex)
                    pass = discarded && handAfter == handBefore + 1;
                }
            } else if (kind == 5) {
                AbstractCard eviscerate = forceIntoHand("Eviscerate");
                AbstractCard any = null;
                if (eviscerate != null) {
                    for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
                        AbstractCard c = AbstractDungeon.player.hand.group.get(i);
                        if (c != null && !"Eviscerate".equals(c.cardID)) {
                            any = c;
                            break;
                        }
                    }
                }
                if (eviscerate != null && any != null) {
                    int costBase = eviscerate.costForTurn;
                    String anyId = any.cardID;
                    boolean discarded = discardWithPrepared(anyId);
                    int costAfter = eviscerate.costForTurn;
                    Log.info("  eviscerate: discarded=" + discarded + " cost " + costBase
                            + "->" + costAfter);
                    pass = discarded && costAfter == costBase - 1;
                }
            }
        } catch (Throwable t) {
            Log.info("  silent sandbox threw " + t);
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
            Log.info("  silent sandbox leaked: live digest changed");
        }
        return pass && clean;
    }

    private static int poisonOf(AbstractMonster m) {
        for (int i = 0; i < m.powers.size(); i++) {
            if (m.powers.get(i) instanceof PoisonPower) {
                return m.powers.get(i).amount;
            }
        }
        return 0;
    }
}