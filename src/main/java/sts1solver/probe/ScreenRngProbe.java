package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.potions.PowerPotion;
import java.util.ArrayList;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.drive.PotionDrink;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B133 (user m06893). Does a potion that opens a card screen (PowerPotion -> DiscoveryAction, which
 * regenerates its three cards on EVERY update) consume the same number of cardRandomRng rolls in the
 * sandbox as in the live game? The live game cannot advance the action manager while a modal screen
 * is up (AbstractDungeon.update bytecode: the CARD_REWARD case only calls cardRewardScreen.update()
 * and runs the room only when PeekButton.isPeeking), so the only faithful number is "one update to
 * open, one update to retrieve". This probe runs the same potion twice from the same captured state:
 * once on the live game driven by engine frames, once inside the mirror driven by Driver.drain.
 */
public final class ScreenRngProbe {

    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";
    private static final String NOB = "com.megacrit.cardcrawl.monsters.exordium.GremlinNob";
    private static final long SEED = 20261109L;

    private ScreenRngProbe() {
    }

    public static void run() {
        AbstractPlayer cainong = registered(CHARACTER);
        if (cainong == null) {
            Log.info("SCREENRNG probe skipped: " + CHARACTER + " is not registered");
            Log.info("AUTOTEST DONE");
            return;
        }
        try {
            List<String> live = script(cainong, false);
            List<String> sandbox = script(cainong, true);
            int diff = -1;
            for (int i = 0; i < Math.min(live.size(), sandbox.size()); i++) {
                if (!live.get(i).equals(sandbox.get(i))) {
                    diff = i;
                    break;
                }
            }
            for (int i = 0; i < sandbox.size(); i++) {
                Log.info("SCREENRNG sandbox[" + i + "]: " + sandbox.get(i));
            }
            for (int i = 0; i < live.size(); i++) {
                Log.info("SCREENRNG live[" + i + "]: " + live.get(i));
            }
            Log.info("SCREENRNG first difference: " + (diff < 0 ? "none" : String.valueOf(diff)));
        } catch (Throwable t) {
            Log.info("SCREENRNG probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    private static List<String> script(AbstractPlayer cainong, boolean sandbox) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = SEED;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[0];
        spec.deck = new String[]{"CaiNongMod:Defend", "CaiNongMod:Defend", "CaiNongMod:Strike",
                "CaiNongMod:Strike", "Strike_R", "Strike_R", "Defend_R", "Defend_R"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
        AbstractDungeon.player.addPower(new com.megacrit.cardcrawl.powers.ConfusionPower(AbstractDungeon.player));
        AbstractDungeon.player.obtainPotion(new PowerPotion());
        int slot = -1;
        for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
            AbstractPotion p = AbstractDungeon.player.potions.get(i);
            if (p != null && !(p instanceof PotionSlot)) {
                slot = i;
                break;
            }
        }
        Driver.begin();
        World root = World.captureLive();
        List<String> rows = new ArrayList<String>();
        if (slot < 0) {
            rows.add("no potion slot");
            return rows;
        }
        World w = null;
        if (sandbox) {
            w = root.fork();
            w.install();
            Driver.begin();
        }
        try {
            int before = counter();
            AbstractPotion potion = AbstractDungeon.player.potions.get(slot);
            PotionDrink.drink(slot, potion, null, !sandbox);
            rows.add("drink rolls=" + (counter() - before)
                    + " awaiting=" + Driver.isAwaitingSelection()
                    + " screen=" + AbstractDungeon.screen
                    + " actions=" + AbstractDungeon.actionManager.actions.size());
            for (int f = 0; f < 40 && !Driver.isAwaitingSelection(); f++) {
                frame(sandbox);
            }
            rows.add("open rolls=" + (counter() - before)
                    + " awaiting=" + Driver.isAwaitingSelection()
                    + " screen=" + AbstractDungeon.screen
                    + " group=" + groupSize());
            List<AbstractCard> offers = new ArrayList<AbstractCard>();
            if (AbstractDungeon.cardRewardScreen != null
                    && AbstractDungeon.cardRewardScreen.rewardGroup != null) {
                offers.addAll(AbstractDungeon.cardRewardScreen.rewardGroup);
            }
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < offers.size(); i++) {
                if (i > 0) {
                    ids.append(",");
                }
                ids.append(offers.get(i).cardID);
            }
            rows.add("offers=" + ids);
            // What the search does while the screen is open: wait for it, frame by frame.
            int pumpRolls0 = counter();
            Driver.pumpUntilSelection(24);
            rows.add("pump24 rolls=" + (counter() - pumpRolls0)
                    + " screen=" + AbstractDungeon.screen
                    + " up=" + AbstractDungeon.isScreenUp
                    + " roomBlocked=" + Driver.roomBlockedByScreen());
            rows.add("after pump24 offersSame=" + groupSize());
            if (!offers.isEmpty()) {
                boolean ok = Driver.answerCardReward(offers.get(0));
                rows.add("answered ok=" + ok + " rolls=" + (counter() - before));
            }
            for (int f = 0; f < 200; f++) {
                int r0 = counter();
                frame(sandbox);
                if (f < 12) {
                    GameActionManager g0 = AbstractDungeon.actionManager;
                    rows.add("f" + f + " rolls+" + (counter() - r0) + " screen=" + AbstractDungeon.screen
                            + " up=" + AbstractDungeon.isScreenUp
                            + " group=" + groupSize()
                            + " acts=" + (g0 == null ? -1 : g0.actions.size())
                            + " cur=" + (g0 == null || g0.currentAction == null
                                    ? "-" : g0.currentAction.getClass().getSimpleName())
                            + " hand=" + (AbstractDungeon.player == null ? -1
                                    : AbstractDungeon.player.hand.size()));
                }
                GameActionManager gam = AbstractDungeon.actionManager;
                boolean idle = gam != null && gam.actions.isEmpty() && gam.cardQueue.isEmpty()
                        && !gam.usingCard && gam.currentAction == null;
                if (idle && !AbstractDungeon.isScreenUp && f > 0) {
                    break;
                }
            }
            rows.add("finish rolls=" + (counter() - before)
                    + " turn=" + GameActionManager.turn
                    + " hand=" + handCosts()
                    + " pileTop=" + drawTop());
        } catch (Throwable t) {
            rows.add("threw: " + t);
            t.printStackTrace();
        } finally {
            if (sandbox) {
                Driver.end();
                w.uninstall();
            }
        }
        return rows;
    }

    /** One frame of the engine's own schedule: while a modal screen is up and nobody is peeking at the
     *  map, AbstractDungeon.update drives only the screen; the room (and the action manager) runs only
     *  when no such screen is up. */
    private static void frame(boolean sandbox) {
        if (sandbox) {
            Driver.drain(400, 400L);
            return;
        }
        if (AbstractDungeon.isScreenUp && !peeking()) {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NONE
                    || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NO_INTERACT) {
                AbstractDungeon.actionManager.update();
            } else if (AbstractDungeon.cardRewardScreen != null
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
                AbstractDungeon.cardRewardScreen.update();
            }
            return;
        }
        AbstractDungeon.actionManager.update();
    }

    private static boolean peeking() {
        try {
            Class<?> type = Class.forName("com.megacrit.cardcrawl.ui.buttons.PeekButton");
            Object v = type.getField("isPeeking").get(null);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int groupSize() {
        if (AbstractDungeon.cardRewardScreen == null
                || AbstractDungeon.cardRewardScreen.rewardGroup == null) {
            return -1;
        }
        return AbstractDungeon.cardRewardScreen.rewardGroup.size();
    }

    private static int counter() {
        return AbstractDungeon.cardRandomRng == null ? -1 : AbstractDungeon.cardRandomRng.counter;
    }

    private static String handCosts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(c.name).append(":").append(c.cost).append("/").append(c.costForTurn);
        }
        return sb.toString();
    }

    private static String drawTop() {
        if (AbstractDungeon.player.drawPile == null || AbstractDungeon.player.drawPile.size() == 0) {
            return "-";
        }
        AbstractCard c = AbstractDungeon.player.drawPile.group.get(0);
        return c == null ? "-" : c.name + ":" + c.cost + "/" + c.costForTurn;
    }

    private static AbstractPlayer registered(String className) {
        List<AbstractPlayer> all = com.megacrit.cardcrawl.core.CardCrawlGame.characterManager.getAllCharacters();
        for (int i = 0; i < all.size(); i++) {
            AbstractPlayer p = all.get(i);
            if (p != null && className.equals(p.getClass().getName())) {
                return p;
            }
        }
        return null;
    }
}
