package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;
import java.lang.reflect.Constructor;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B124 (user m03444). 唐 (CaiNongMod TangPower) plays "amount" random cards across a turn: one at
 * the start of the turn (atStartOfTurnPostDraw, numOfUseCard = amount - 1) and one more after every
 * card that is used (onAfterUseCard), until the power runs out. The sandbox is supposed to run that
 * for real -- the same code with the same RNG stream -- and the panel is supposed to replay exactly
 * what the run does. This probe runs one identical fight twice, once with the real action manager
 * (live) and once inside the mirror (sandbox), and prints both end states side by side: anything
 * that differs is a hole in the simulation, not in Tang.
 */
public final class TangProbe {

    private static final String TANG = "CaiNongMod.Powers.TangPower";
    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";
    private static final String NOB = "com.megacrit.cardcrawl.monsters.exordium.GremlinNob";
    private static final long SEED = 20261105L;
    private static final int TANG_STACKS = 3;

    private TangProbe() {
    }

    public static void run() {
        AbstractPlayer cainong = registered(CHARACTER);
        if (cainong == null) {
            Log.info("TANG probe skipped: " + CHARACTER + " is not registered (mod not loaded)");
            Log.info("AUTOTEST DONE");
            return;
        }
        try {
            String live = runCase(cainong, false);
            String sandbox = runCase(cainong, true);
            Log.info("TANG live:    " + live);
            Log.info("TANG sandbox: " + sandbox);
            Log.info("TANG same=" + live.equals(sandbox));
        } catch (Throwable t) {
            Log.info("TANG probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    private static String runCase(AbstractPlayer cainong, boolean sandbox) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = SEED;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[0];
        spec.deck = new String[]{"CaiNongMod:Defend", "CaiNongMod:Defend", "CaiNongMod:Strike",
                "CaiNongMod:Strike", "Strike_R", "Defend_R", "Defend_R"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
        if (!addTang()) {
            return "no " + TANG;
        }
        // A pile of playable cards so Tang has something to pick from; hand already holds the draw.
        Driver.begin();
        String before = "turn=" + GameActionManager.turn + " rng=" + rngCounter()
                + " hand=[" + handNames() + "] powers=[" + powers() + "] hp=" + hp()
                + " block=" + AbstractDungeon.player.currentBlock;
        Log.info("TANG before(" + (sandbox ? "sandbox" : "live") + "): " + before);
        World w = World.captureLive();
        String after;
        if (!sandbox) {
            Driver.Drain end = Driver.endTurn();
            after = describe(end);
        } else {
            w.install();
            Driver.begin();
            try {
                Driver.Drain end = Driver.endTurn();
                after = describe(end);
            } finally {
                Driver.end();
                w.readBack();
                w.uninstall();
            }
        }
        return after;
    }

    private static String describe(Driver.Drain end) {
        return "turn=" + GameActionManager.turn + " rng=" + rngCounter()
                + " hand=[" + handNames() + "] played=[" + playedNames() + "]"
                + " powers=[" + powers() + "] hp=" + hp()
                + " block=" + AbstractDungeon.player.currentBlock
                + " energy=" + Driver.energy()
                + " drain=settled:" + end.settled + ",stalled:" + end.stalled
                + ",timedOut:" + end.timedOut + ",turnAdvanced:" + end.turnAdvanced
                + ",steps:" + end.steps
                + " digest=" + Digest.stable(Digest.ofCurrent());
    }

    private static boolean addTang() {
        try {
            Class<?> type = Class.forName(TANG);
            Constructor<?> ctor = type.getConstructor(AbstractCreature.class, int.class);
            AbstractPower power = (AbstractPower) ctor.newInstance(AbstractDungeon.player, TANG_STACKS);
            AbstractDungeon.player.addPower(power);
            return true;
        } catch (Throwable t) {
            Log.info("TANG probe cannot build the power: " + t);
            return false;
        }
    }

    private static int rngCounter() {
        return AbstractDungeon.cardRandomRng == null ? -1 : AbstractDungeon.cardRandomRng.counter;
    }

    private static String handNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.name);
        }
        return sb.toString();
    }

    private static String playedNames() {
        StringBuilder sb = new StringBuilder();
        List<AbstractCard> played = AbstractDungeon.actionManager.cardsPlayedThisTurn;
        for (int i = 0; i < played.size(); i++) {
            AbstractCard c = played.get(i);
            if (c == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.name);
        }
        return sb.toString();
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

    private static String hp() {
        return AbstractDungeon.player.currentHealth + "/" + AbstractDungeon.player.maxHealth;
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
}
