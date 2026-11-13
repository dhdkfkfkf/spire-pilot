package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B124 (user m03444). The mirror gives the sandbox a private copy of every RNG stream. If that copy
 * is faithful, a line of play consumes exactly the same numbers in the sandbox and in the live game,
 * so the sandbox predicts the live state exactly -- including what Tang plays, which cards it takes,
 * and every draw and shuffle after it. This probe runs the SAME line twice (once installed, once
 * live) from the same captured state and prints the RNG counters and the digest after every step:
 * the first step whose counters differ is the first step where the sandbox consumed randomness the
 * live game did not (or the other way round).
 */
public final class LockstepProbe {

    private static final String TANG = "CaiNongMod.Powers.TangPower";
    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";
    private static final String NOB = "com.megacrit.cardcrawl.monsters.exordium.GremlinNob";
    private static final long SEED = 20261107L;
    private static final int STEPS = 4;

    private LockstepProbe() {
    }

    public static void run() {
        AbstractPlayer cainong = registered(CHARACTER);
        if (cainong == null) {
            Log.info("LOCKSTEP probe skipped: " + CHARACTER + " is not registered (mod not loaded)");
            Log.info("AUTOTEST DONE");
            return;
        }
        try {
            List<String> live = runCase(cainong, false);
            List<String> sandbox = runCase(cainong, true);
            int diff = -1;
            for (int i = 0; i < Math.min(live.size(), sandbox.size()); i++) {
                if (!live.get(i).equals(sandbox.get(i))) {
                    diff = i;
                    break;
                }
            }
            for (int i = 0; i < sandbox.size(); i++) {
                Log.info("LOCKSTEP sandbox[" + i + "]: " + sandbox.get(i));
            }
            for (int i = 0; i < live.size(); i++) {
                Log.info("LOCKSTEP live[" + i + "]: " + live.get(i));
            }
            Log.info("LOCKSTEP first difference: " + (diff < 0 ? "none" : String.valueOf(diff)));
        } catch (Throwable t) {
            Log.info("LOCKSTEP probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    private static List<String> runCase(AbstractPlayer cainong, boolean sandbox) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = SEED;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[0];
        spec.deck = new String[]{"CaiNongMod:Defend", "CaiNongMod:Defend", "CaiNongMod:Strike",
                "CaiNongMod:Strike", "Strike_R", "Defend_R", "Defend_R", "Burn", "Burn"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
        if (!addTang()) {
            return new ArrayList<String>();
        }
        Driver.begin();
        World w = World.captureLive();
        List<String> rows = new ArrayList<String>();
        if (sandbox) {
            w.install();
            Driver.begin();
            try {
                for (int i = 0; i < STEPS; i++) {
                    rows.add(step(i));
                }
            } finally {
                Driver.end();
                w.uninstall();
            }
        } else {
            for (int i = 0; i < STEPS; i++) {
                rows.add(step(i));
            }
        }
        return rows;
    }

    private static String step(int index) {
        String before = counters();
        Driver.Drain end = Driver.endTurn();
        return "#" + index + " before=" + before + " after=" + counters()
                + " settled=" + end.settled + " turnAdvanced=" + end.turnAdvanced
                + " hp=" + AbstractDungeon.player.currentHealth + " turn=" + GameActionManager.turn
                + " hand=" + handNames()
                + " digest=" + Digest.stable(Digest.ofCurrent());
    }

    private static String counters() {
        return "card=" + counter(AbstractDungeon.cardRandomRng)
                + ",shuffle=" + counter(AbstractDungeon.shuffleRng)
                + ",ai=" + counter(AbstractDungeon.aiRng)
                + ",monster=" + counter(AbstractDungeon.monsterRng)
                + ",misc=" + counter(AbstractDungeon.miscRng);
    }

    private static int counter(com.megacrit.cardcrawl.random.Random r) {
        return r == null ? -1 : r.counter;
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

    private static boolean addTang() {
        try {
            Class<?> type = Class.forName(TANG);
            Constructor<?> ctor = type.getConstructor(AbstractCreature.class, int.class);
            AbstractPower power = (AbstractPower) ctor.newInstance(AbstractDungeon.player, 3);
            AbstractDungeon.player.addPower(power);
            return true;
        } catch (Throwable t) {
            Log.info("LOCKSTEP probe cannot build the power: " + t);
            return false;
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
}
