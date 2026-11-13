package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B122 (user m02740): CaiNong's 昏睡 (Trance) applies TrancePower, whose atStartOfTurn presses the
 * End Turn button -- the NEXT player turn must end by itself. This probe builds that turn and reports
 * every number the sandbox should have moved: turn counter, HP (the power heals 30% max HP), the power
 * stack, energy and hand.
 */
public final class TranceProbe {

    private static final String TRANCE = "CaiNongMod:Trance";
    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";
    private static final String NOB = "com.megacrit.cardcrawl.monsters.exordium.GremlinNob";

    private TranceProbe() {
    }

    public static void run() {
        AbstractPlayer cainong = registered(CHARACTER);
        if (cainong == null) {
            Log.info("TRANCE probe skipped: " + CHARACTER + " is not registered (mod not loaded)");
            Log.info("AUTOTEST DONE");
            return;
        }
        try {
            afterPlay(cainong);
        } catch (Throwable t) {
            Log.info("TRANCE probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    /** Plays 昏睡, ends the turn the normal way, and watches what the next turn start does. */
    private static void afterPlay(AbstractPlayer cainong) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261104L;
        spec.maxHealth = 80;
        spec.ascension = 4;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[0];
        spec.deck = new String[]{TRANCE, "CaiNongMod:Defend", "CaiNongMod:Defend",
                "CaiNongMod:Strike", "CaiNongMod:Strike", "Strike_R", "Defend_R"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        AbstractDungeon.player.currentHealth = 40;
        World w = World.captureLive();
        w.install();
        Driver.begin();
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            AbstractCard trance = intoHand(TRANCE);
            if (trance == null) {
                Log.info("TRANCE afterPlay: " + TRANCE + " is not in the built deck");
                return;
            }
            AbstractMonster m = monster();
            Log.info("TRANCE before: turn=" + GameActionManager.turn + " hp=" + hp()
                    + " energy=" + Driver.energy() + " hand=" + AbstractDungeon.player.hand.size()
                    + " playable=" + trance.canUse(AbstractDungeon.player, m));
            Driver.Drain play = Driver.play(trance, m, 0);
            Log.info("TRANCE after play: hp=" + hp() + " energy=" + Driver.energy()
                    + " powers=[" + powers() + "] turn=" + GameActionManager.turn
                    + " drain=settled:" + play.settled + ",refused:" + play.refused
                    + ",stalled:" + play.stalled + ",steps:" + play.steps);
            int turnBefore = GameActionManager.turn;
            Driver.Drain end = Driver.endTurn();
            Log.info("TRANCE after end turn: turn=" + turnBefore + "->" + GameActionManager.turn
                    + " hp=" + hp() + " energy=" + Driver.energy()
                    + " hand=" + AbstractDungeon.player.hand.size()
                    + " powers=[" + powers() + "]"
                    + " turnHasEnded=" + gam.turnHasEnded
                    + " endTurnQueued=" + (AbstractDungeon.player != null && AbstractDungeon.player.endTurnQueued)
                    + " isEndingTurn=" + (AbstractDungeon.player != null && AbstractDungeon.player.isEndingTurn)
                    + " queues=" + gam.actions.size() + "/" + gam.cardQueue.size()
                    + " drain=settled:" + end.settled + ",timedOut:" + end.timedOut
                    + ",stalled:" + end.stalled + ",turnAdvanced:" + end.turnAdvanced
                    + ",steps:" + end.steps);
            Log.info("TRANCE verdict: turnSkipped=" + (GameActionManager.turn >= turnBefore + 2)
                    + " trancePowerLeft=" + powerCount()
                    + " healed=" + (AbstractDungeon.player.currentHealth > 40)
                    + " digest=" + Digest.stable(Digest.ofCurrent()));
            Driver.Drain again = Driver.drain(2000, 1500L);
            Log.info("TRANCE extra drain: turn=" + GameActionManager.turn + " hp=" + hp()
                    + " energy=" + Driver.energy()
                    + " hand=" + AbstractDungeon.player.hand.size()
                    + " settled=" + again.settled + " stalled=" + again.stalled
                    + " steps=" + again.steps
                    + " digest=" + Digest.stable(Digest.ofCurrent()));
        } finally {
            Driver.end();
            w.readBack();
            w.uninstall();
        }
    }

    private static AbstractPlayer registered(String className) {
        List<AbstractPlayer> all = CardCrawlGame.characterManager.getAllCharacters();
        for (int i = 0; i < all.size(); i++) {
            AbstractPlayer p = all.get(i);
            if (p != null && className.equals(p.getClass().getName())) {
                return p;
            }
        }
        return null;
    }

    private static AbstractCard intoHand(String cardId) {
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
        return null;
    }

    private static AbstractMonster monster() {
        if (AbstractDungeon.getMonsters() == null || AbstractDungeon.getMonsters().monsters.isEmpty()) {
            return null;
        }
        return AbstractDungeon.getMonsters().monsters.get(0);
    }

    private static String hp() {
        return AbstractDungeon.player.currentHealth + "/" + AbstractDungeon.player.maxHealth;
    }

    private static int powerCount() {
        int n = 0;
        for (int i = 0; i < AbstractDungeon.player.powers.size(); i++) {
            AbstractPower p = AbstractDungeon.player.powers.get(i);
            if (p != null && p.getClass().getName().toLowerCase().contains("trance")) {
                n += Math.max(1, p.amount);
            }
        }
        return n;
    }

    private static String powers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.player.powers.size(); i++) {
            AbstractPower p = AbstractDungeon.player.powers.get(i);
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.ID).append(':').append(p.amount);
        }
        return sb.toString();
    }
}
