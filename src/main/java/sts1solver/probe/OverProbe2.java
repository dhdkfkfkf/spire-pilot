package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B125 (user m03996): does an end turn in the sandbox really charge the enemy attack, advance the
 * turn, recharge energy and draw? Phase A ends four turns in the reported fight (Looter +
 * SpikeSlime_M). Phase B repeats it with the Tang power (m03996 #1: the turn the plan played
 * 笋干缪哈 had no energy), because a power that plays cards at turn start is the obvious suspect for
 * an end turn that does not finish the new turn.
 */
public final class OverProbe2 {

    private OverProbe2() {
    }

    private static String monsters() {
        StringBuilder sb = new StringBuilder();
        for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(m.id).append(":").append(m.currentHealth).append("/").append(m.maxHealth);
            sb.append(":in=").append(m.intent == null ? "-" : m.intent.name());
            sb.append(":dm=").append(m.getIntentDmg());
            if (m.isDying) {
                sb.append(":D");
            }
            if (m.isEscaping) {
                sb.append(":E");
            }
        }
        return sb.toString();
    }

    private static String state() {
        AbstractPlayer p = AbstractDungeon.player;
        return "turn=" + GameActionManager.turn + " hp=" + p.currentHealth
                + " block=" + p.currentBlock + " energy=" + p.energy.energy
                + " panel=" + com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount
                + " hand=" + p.hand.size() + " draw=" + p.drawPile.size()
                + " discard=" + p.discardPile.size()
                + " usingCard=" + AbstractDungeon.actionManager.usingCard
                + " combatOver=" + Driver.combatOver()
                + " monsters=[" + monsters() + "]";
    }

    private static void build() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261106L;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Defend_R", "Defend_R", "Defend_R", "Defend_R", "Defend_R"};
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.SpikeSlime_M",
                "com.megacrit.cardcrawl.monsters.exordium.Looter"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
    }

    private static void fourEndTurns(String tag, World root) {
        World w = root.fork();
        boolean in = false;
        try {
            w.install();
            in = true;
            for (int k = 0; k < 4; k++) {
                Log.info("OVER2 " + tag + " before end turn " + k + ": " + state());
                Driver.Drain d = Driver.endTurn(1600L);
                Log.info("OVER2 " + tag + " after end turn " + k + " (settled=" + d.settled
                        + " timedOut=" + d.timedOut + " stalled=" + d.stalled
                        + " awaiting=" + d.awaitingSelection
                        + " turnAdvanced=" + d.turnAdvanced + " steps=" + d.steps + "): " + state());
            }
        } finally {
            if (in) {
                w.uninstall();
            }
        }
    }

    public static void run() {
        Driver.begin();
        try {
            build();
            fourEndTurns("A", World.captureLive());
        } catch (Throwable t) {
            Log.info("OVER2 phase A threw: " + t);
            t.printStackTrace();
        }
        try {
            build();
            Class<?> tang = Class.forName("CaiNongMod.Powers.TangPower");
            com.megacrit.cardcrawl.powers.AbstractPower p =
                    (com.megacrit.cardcrawl.powers.AbstractPower) tang.getConstructor(
                            com.megacrit.cardcrawl.core.AbstractCreature.class, int.class)
                            .newInstance(AbstractDungeon.player, Integer.valueOf(5));
            World root = World.captureLive();
            World w0 = root.fork();
            boolean in0 = false;
            try {
                w0.install();
                in0 = true;
                AbstractDungeon.player.addPower(p);
                Log.info("OVER2 B tang installed: " + state());
                w0.readBack();
            } finally {
                if (in0) {
                    w0.uninstall();
                }
            }
            World tangRoot = World.captureLive();
            fourEndTurns("B", tangRoot);
        } catch (Throwable t) {
            Log.info("OVER2 phase B threw: " + t);
            t.printStackTrace();
        } finally {
            Driver.end();
        }
        Log.info("AUTOTEST DONE");
    }
}
