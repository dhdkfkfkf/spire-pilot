package sts1solver.probe;

import com.megacrit.cardcrawl.actions.defect.ChannelAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.actions.defect.EvokeOrbAction;
import com.megacrit.cardcrawl.actions.defect.TriggerEndOfTurnOrbsAction;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.orbs.Dark;
import com.megacrit.cardcrawl.orbs.Frost;
import com.megacrit.cardcrawl.orbs.Lightning;
import com.megacrit.cardcrawl.orbs.Plasma;
import com.megacrit.cardcrawl.powers.FocusPower;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * Defect mechanics (P2-1c): orbs, focus and the slot limit.
 *
 * <p>Orbs are passive/evoke state machines attached to the player; a solver that mis-observes them
 * prices every Defect turn wrong. Everything here runs through the real engine: channel an orb, tick
 * the end-of-turn passives, evoke, and compare the numbers the game actually produces.
 */
public final class DefectProbe {

    private static final String W = "com.megacrit.cardcrawl.monsters.exordium.JawWorm";

    private DefectProbe() {
    }

    public static void run() {
        int ok = 0;
        int failed = 0;
        Object[][] cases = {
                {"lightning passive hits 3 at end of turn", 1},
                {"lightning evoke hits 8 and removes the orb", 2},
                {"frost passive blocks 2, evoke blocks 5", 3},
                {"focus adds to both passive and evoke", 4},

                {"dark accumulates damage and evokes it", 6},
                {"the fourth orb overflows the three slots", 7},
                {"tick trace: lightning passive lands", 8},
        };
        for (int i = 0; i < cases.length; i++) {
            String name = (String) cases[i][0];
            int kind = ((Integer) cases[i][1]).intValue();
            boolean pass;
            try {
                pass = sandbox(kind);
            } catch (Throwable t) {
                Log.error("DEFECT " + name + ": threw " + t, t);
                pass = false;
            }
            Log.info("DEFECT " + name + ": " + (pass ? "ok" : "FAIL"));
            if (pass) {
                ok++;
            } else {
                failed++;
            }
        }
        Log.info("DEFECT summary: ok=" + ok + " failed=" + failed + " of " + cases.length);
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
        spec.seed = 801L;
        spec.maxHealth = 120;
        spec.playerClass = "com.megacrit.cardcrawl.characters.Defect";
        spec.deck = new String[]{"Zap", "Dualcast", "Ball Lightning", "Cold Snap", "Defragment",
                "Darkness", "Fusion", "Loop"};
        spec.monsters = new String[]{W};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
    }

    /** Channels an orb the way the search does: by playing the card that channels it. */
    private static boolean channelByCard(String cardId) {
        AbstractCard card = forceIntoHand(cardId);
        if (card == null) {
            Log.info("  channel: " + cardId + " not in hand");
            return false;
        }
        Driver.play(card, AbstractDungeon.getMonsters().monsters.get(0), 0);
        return orbCount() > 0;
    }

    private static AbstractCard forceIntoHand(String cardId) {
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

    private static String orbNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.player.orbs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AbstractOrb o = AbstractDungeon.player.orbs.get(i);
            sb.append(o == null ? "null" : o.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String orbEvoke() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.player.orbs.size(); i++) {
            AbstractOrb o = AbstractDungeon.player.orbs.get(i);
            if (o != null) {
                sb.append(o.getClass().getSimpleName()).append(':').append(o.evokeAmount).append('/')
                        .append(o.passiveAmount).append(' ');
            }
        }
        return sb.toString();
    }

    private static int orbCount() {
        int n = 0;
        for (int i = 0; i < AbstractDungeon.player.orbs.size(); i++) {
            AbstractOrb o = AbstractDungeon.player.orbs.get(i);
            if (o != null && !(o instanceof com.megacrit.cardcrawl.orbs.EmptyOrbSlot)) {
                n++;
            }
        }
        return n;
    }

    private static int monsterHp() {
        AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(0);
        return m.currentHealth;
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
            if (kind == 1) {
                channelByCard("Zap");
                int hp = monsterHp();
                AbstractDungeon.actionManager.addToBottom(new TriggerEndOfTurnOrbsAction());
                Driver.drain(400, 800L);
                int dealt = hp - monsterHp();
                Log.info("  lightning passive: dealt=" + dealt + " orbs=" + orbCount());
                pass = dealt == 3 && orbCount() == 1;
            } else if (kind == 2) {
                Log.info("  orbs before channel: size=" + AbstractDungeon.player.orbs.size()
                        + " max=" + AbstractDungeon.player.maxOrbs + " " + orbNames());
                channelByCard("Zap");
                Log.info("  orbs after channel: " + orbNames());
                int hp = monsterHp();
                AbstractDungeon.actionManager.addToBottom(new EvokeOrbAction(1));
                Driver.drain(400, 800L);
                int dealt = hp - monsterHp();
                Log.info("  lightning evoke: dealt=" + dealt + " orbs=" + orbCount()
                        + " layout=" + orbNames()
                        + " queue=" + AbstractDungeon.actionManager.actions.size()
                        + " awaiting=" + Driver.isAwaitingSelection());
                pass = dealt == 8 && orbCount() == 0;
            } else if (kind == 3) {
                channelByCard("Cold Snap");
                int block0 = AbstractDungeon.player.currentBlock;
                AbstractDungeon.actionManager.addToBottom(new TriggerEndOfTurnOrbsAction());
                Driver.drain(400, 800L);
                int block1 = AbstractDungeon.player.currentBlock;
                AbstractDungeon.actionManager.addToBottom(new EvokeOrbAction(1));
                Driver.drain(400, 800L);
                int block2 = AbstractDungeon.player.currentBlock;
                Log.info("  frost: block " + block0 + "->" + block1 + "->" + block2);
                pass = block1 - block0 == 2 && block2 - block1 == 5;
            } else if (kind == 4) {
                AbstractDungeon.player.addPower(new FocusPower(AbstractDungeon.player, 2));
                channelByCard("Zap");
                int hp = monsterHp();
                AbstractDungeon.actionManager.addToBottom(new TriggerEndOfTurnOrbsAction());
                Driver.drain(400, 800L);
                int passive = hp - monsterHp();
                hp = monsterHp();
                AbstractDungeon.actionManager.addToBottom(new EvokeOrbAction(1));
                Driver.drain(400, 800L);
                int evoke = hp - monsterHp();
                Log.info("  focus 2: passive=" + passive + " evoke=" + evoke);
                pass = passive == 5 && evoke == 10;
            } else if (kind == 6) {
                channelByCard("Darkness");
                Log.info("  dark: orbs=" + orbNames() + " evokeAmount=" + orbEvoke());
                int hp = monsterHp();
                AbstractDungeon.actionManager.addToBottom(new TriggerEndOfTurnOrbsAction());
                Driver.drain(400, 800L);
                Log.info("  dark: after passive evokeAmount=" + orbEvoke()
                        + " orbs=" + orbNames());
                AbstractDungeon.actionManager.addToBottom(new EvokeOrbAction(1));
                Driver.drain(400, 800L);
                int dealt = hp - monsterHp();
                Log.info("  dark: one passive then evoke dealt=" + dealt
                        + " orbs=" + orbNames()
                        + " monsterBlock=" + AbstractDungeon.getMonsters().monsters.get(0).currentBlock);
                pass = dealt == 12;
            } else if (kind == 8) {
                // manual tick trace: when does the orb passive actually land?
                channelByCard("Zap");
                int hp0 = monsterHp();
                Log.info("  tick trace: after channel hp=" + hp0 + " orbs=" + orbNames()
                        + " queue=" + AbstractDungeon.actionManager.actions.size());
                AbstractDungeon.actionManager.addToBottom(new TriggerEndOfTurnOrbsAction());
                for (int i = 0; i < 30; i++) {
                    int qBefore = AbstractDungeon.actionManager.actions.size();
                    String head = qBefore == 0 ? "-"
                            : AbstractDungeon.actionManager.actions.get(0).getClass().getSimpleName();
                    String detail = "";
                    if (qBefore > 0) {
                        Object h = AbstractDungeon.actionManager.actions.get(0);
                        try {
                            java.lang.reflect.Field tf = sts1solver.reflect.Reflect.find(h.getClass(), "target");
                            java.lang.reflect.Field inf = sts1solver.reflect.Reflect.find(h.getClass(), "info");
                            Object target = tf == null ? null : sts1solver.reflect.Reflect.get(tf, h);
                            Object info = inf == null ? null : sts1solver.reflect.Reflect.get(inf, h);
                            if (info != null) {
                                java.lang.reflect.Field base = sts1solver.reflect.Reflect.find(info.getClass(), "base");
                                detail = " dmg=" + (base == null ? "?" : sts1solver.reflect.Reflect.get(base, info));
                            }
                            if (target instanceof com.megacrit.cardcrawl.monsters.AbstractMonster) {
                                com.megacrit.cardcrawl.monsters.AbstractMonster tm =
                                        (com.megacrit.cardcrawl.monsters.AbstractMonster) target;
                                detail += " target=" + tm.name + ":" + tm.currentHealth;
                            }
                        } catch (Throwable ignored) {
                            // diagnostics only
                        }
                    }
                    AbstractDungeon.actionManager.update();
                    int qAfter = AbstractDungeon.actionManager.actions.size();
                    int hpNow = monsterHp();
                    Log.info("  tick " + i + ": head=" + head + " queue " + qBefore + "->" + qAfter
                            + " hp=" + hpNow + " orbs=" + orbCount() + detail);
                    if (hpNow < hp0) {
                        Log.info("  tick trace: damage landed at tick " + i);
                        break;
                    }
                }
                pass = monsterHp() < hp0;
            } else if (kind == 7) {
                int hp = monsterHp();
                // Only one Zap exists; channel the rest through the action, which lands one drain later.
                channelByCard("Zap");
                for (int i = 0; i < 3; i++) {
                    AbstractDungeon.actionManager.addToBottom(new ChannelAction(new Lightning()));
                    Driver.drain(400, 800L);
                    Driver.drain(400, 800L);
                }
                int dealt = hp - monsterHp();
                Log.info("  slots: orbs=" + orbCount() + " overflowDamage=" + dealt);
                // the 4th channel auto-evokes the oldest lightning (8 damage)
                pass = orbCount() == 3 && dealt == 8;
            }
        } catch (Throwable t) {
            Log.info("  defect sandbox threw " + t);
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
            Log.info("  defect sandbox leaked: live digest changed");
        }
        return pass && clean;
    }
}