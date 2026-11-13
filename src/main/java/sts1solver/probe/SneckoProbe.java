package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.ConfusionPower;
import java.util.ArrayList;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B132 (user m06536). "Why do the same cards roll different Snecko costs in the sandbox and in the
 * live game?" This probe answers the mirroring half of that question: it runs the SAME line twice
 * (once with the mirror installed, once on the live game) from the same captured state, with Snecko
 * Eye's ConfusionPower on the player so every draw rolls a cost, and prints the whole hand with its
 * cost/costForTurn plus the cardRandomRng counter after every action. The first row that differs is
 * the first place the sandbox consumed randomness the live game did not (or the other way round).
 */
public final class SneckoProbe {

    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";
    private static final String NOB = "com.megacrit.cardcrawl.monsters.exordium.GremlinNob";
    private static final long SEED = 20261108L;
    private static final int ROUNDS = 6;

    private SneckoProbe() {
    }

    public static void run() {
        AbstractPlayer cainong = registered(CHARACTER);
        if (cainong == null) {
            Log.info("SNECKO probe skipped: " + CHARACTER + " is not registered (mod not loaded)");
            Log.info("AUTOTEST DONE");
            return;
        }
        try {
            determinism(cainong);
            discoveryFrames(cainong);
            discoveryCompare(cainong);
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
                Log.info("SNECKO sandbox[" + i + "]: " + sandbox.get(i));
            }
            for (int i = 0; i < live.size(); i++) {
                Log.info("SNECKO live[" + i + "]: " + live.get(i));
            }
            Log.info("SNECKO first difference: " + (diff < 0 ? "none" : String.valueOf(diff)));
        } catch (Throwable t) {
            Log.info("SNECKO probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    /**
     * B132. The mirror can only be faithful if the sandbox is deterministic: replaying the same line
     * from the same captured root must roll the same costs every time. If two independent forks do
     * not agree, every plan the search prints carries cost assumptions that cannot be reproduced, and
     * a plan can be published with costs the live game never rolls. This compares two forks of ONE
     * root, so any difference is the sandbox's own nondeterminism, not live vs sandbox.
     */
    private static void determinism(AbstractPlayer cainong) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = SEED;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[0];
        spec.deck = new String[]{"CaiNongMod:Defend", "CaiNongMod:Defend", "CaiNongMod:Strike",
                "CaiNongMod:Strike", "Strike_R", "Strike_R", "Defend_R", "Defend_R", "Bash", "Burn"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
        AbstractDungeon.player.addPower(new ConfusionPower(AbstractDungeon.player));
        Driver.begin();
        World root = World.captureLive();
        List<List<String>> passes = new ArrayList<List<String>>();
        for (int pass = 0; pass < 2; pass++) {
            World w = root.fork();
            List<String> rows = new ArrayList<String>();
            w.install();
            Driver.begin();
            try {
                for (int i = 0; i < 2; i++) {
                    rows.add(playRow(i));
                    rows.add(endRow(i));
                }
            } finally {
                Driver.end();
                w.uninstall();
            }
            passes.add(rows);
        }
        List<String> a = passes.get(0);
        List<String> b = passes.get(1);
        int diff = -1;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) {
                diff = i;
                break;
            }
        }
        for (int i = 0; i < a.size(); i++) {
            Log.info("SNECKO determinism pass0[" + i + "]: " + a.get(i));
        }
        for (int i = 0; i < b.size(); i++) {
            Log.info("SNECKO determinism pass1[" + i + "]: " + b.get(i));
        }
        Log.info("SNECKO determinism first difference: " + (diff < 0 ? "none" : String.valueOf(diff)));
    }

    /**
     * B132. The decisive question for "the same cards rolled different numbers": does an action that
     * re-rolls its choices on EVERY update (DiscoveryAction does: it regenerates its three cards at
     * the top of update(), before the duration check) keep rolling while a screen waits for an
     * answer, and for how many frames? The sandbox stops pumping the moment the screen is awaiting an
     * answer, the live game keeps rendering frames -- so if the reroll runs per frame, the live game
     * advances cardRandomRng dozens of times where the sandbox advances it once. This measures rolls
     * per frame and how many frames the action lives.
     */
    private static void discoveryFrames(AbstractPlayer cainong) {
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
        Driver.begin();
        World root = World.captureLive();
        World w = root.fork();
        w.install();
        Driver.begin();
        try {
            AbstractDungeon.player.obtainPotion(new com.megacrit.cardcrawl.potions.PowerPotion());
            int slot = -1;
            for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
                if (AbstractDungeon.player.potions.get(i) != null
                        && !(AbstractDungeon.player.potions.get(i)
                        instanceof com.megacrit.cardcrawl.potions.PotionSlot)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                Log.info("SNECKO discovery: no potion slot");
                return;
            }
            int before = counter(AbstractDungeon.cardRandomRng);
            sts1solver.drive.PotionDrink.drink(slot, AbstractDungeon.player.potions.get(slot), null, false);
            Log.info("SNECKO discovery: drink rolls=" + (counter(AbstractDungeon.cardRandomRng) - before)
                    + " awaiting=" + Driver.isAwaitingSelection());
            for (int f = 1; f <= 60; f++) {
                int c0 = counter(AbstractDungeon.cardRandomRng);
                int n0 = AbstractDungeon.actionManager.actions.size();
                AbstractDungeon.actionManager.update();
                int c1 = counter(AbstractDungeon.cardRandomRng);
                int n1 = AbstractDungeon.actionManager.actions.size();
                Log.info("SNECKO discovery frame[" + f + "]: rolls=" + (c1 - c0)
                        + " counter=" + c0 + "->" + c1 + " actions=" + n0 + "->" + n1
                        + " awaiting=" + Driver.isAwaitingSelection());
                if (n1 == 0) {
                    Log.info("SNECKO discovery: action queue empty after frame " + f
                            + ", total rolls=" + (c1 - before));
                    break;
                }
            }
        } catch (Throwable t) {
            Log.info("SNECKO discovery threw: " + t);
            t.printStackTrace();
        } finally {
            Driver.end();
            w.uninstall();
        }
    }


    /**
     * B132. Runs the SAME Discovery screen twice (live and with the mirror installed) with Snecko
     * Eye's ConfusionPower on the player, printing every cardRandomRng roll: opening the screen,
     * answering it, and letting the waiting action retrieve the card. This is the live-vs-sandbox
     * comparison for "the same cards rolled different numbers": the live game cannot update the
     * action manager while a card screen is up (AbstractDungeon.update only calls room.update for
     * NO_INTERACT/NONE), so both sides must roll exactly once to open and once to retrieve.
     */
    private static void discoveryCompare(AbstractPlayer cainong) {
        List<String> live = discoveryScript(cainong, false);
        List<String> sandbox = discoveryScript(cainong, true);
        int diff = -1;
        for (int i = 0; i < Math.min(live.size(), sandbox.size()); i++) {
            if (!live.get(i).equals(sandbox.get(i))) {
                diff = i;
                break;
            }
        }
        for (int i = 0; i < sandbox.size(); i++) {
            Log.info("SNECKO disc sandbox[" + i + "]: " + sandbox.get(i));
        }
        for (int i = 0; i < live.size(); i++) {
            Log.info("SNECKO disc live[" + i + "]: " + live.get(i));
        }
        Log.info("SNECKO disc first difference: " + (diff < 0 ? "none" : String.valueOf(diff)));
    }

    private static List<String> discoveryScript(AbstractPlayer cainong, boolean sandbox) {
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
        AbstractDungeon.player.addPower(new ConfusionPower(AbstractDungeon.player));
        Driver.begin();
        World w = sandbox ? World.captureLive() : null;
        List<String> rows = new ArrayList<String>();
        if (sandbox) {
            w.install();
            Driver.begin();
        }
        try {
            int before = counter(AbstractDungeon.cardRandomRng);
            AbstractDungeon.actionManager.addToTop(
                    new com.megacrit.cardcrawl.actions.unique.DiscoveryAction(
                            AbstractCard.CardType.POWER, 1));
            rows.add("queued counter=" + before);
            for (int f = 0; f < 6; f++) {
                int c0 = counter(AbstractDungeon.cardRandomRng);
                AbstractDungeon.actionManager.update();
                int c1 = counter(AbstractDungeon.cardRandomRng);
                int group = AbstractDungeon.cardRewardScreen == null ? -1
                        : AbstractDungeon.cardRewardScreen.rewardGroup == null ? -2
                        : AbstractDungeon.cardRewardScreen.rewardGroup.size();
                rows.add("open frame" + f + " rolls=" + (c1 - c0) + " counter=" + c1
                        + " screen=" + AbstractDungeon.screen + " group=" + group
                        + " awaiting=" + Driver.isAwaitingSelection()
                        + " actions=" + AbstractDungeon.actionManager.actions.size());
                if (group > 0) {
                    break;
                }
            }
            List<AbstractCard> offers = new ArrayList<AbstractCard>();
            if (AbstractDungeon.cardRewardScreen != null
                    && AbstractDungeon.cardRewardScreen.rewardGroup != null) {
                offers.addAll(AbstractDungeon.cardRewardScreen.rewardGroup);
            }
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < offers.size(); i++) {
                if (i > 0) {
                    ids.append(',');
                }
                ids.append(offers.get(i).cardID);
            }
            rows.add("offers=" + ids);
            boolean ok = offers.isEmpty() ? false : Driver.answerCardReward(offers.get(0));
            rows.add("answered ok=" + ok + " counter=" + counter(AbstractDungeon.cardRandomRng));
            for (int f = 0; f < 8; f++) {
                int c0 = counter(AbstractDungeon.cardRandomRng);
                AbstractDungeon.actionManager.update();
                int c1 = counter(AbstractDungeon.cardRandomRng);
                rows.add("finish frame" + f + " rolls=" + (c1 - c0) + " counter=" + c1
                        + " actions=" + AbstractDungeon.actionManager.actions.size()
                        + " usingCard=" + AbstractDungeon.actionManager.usingCard
                        + " hand=" + handCosts());
                if (AbstractDungeon.actionManager.actions.isEmpty()) {
                    break;
                }
            }
            rows.add("final counter=" + counter(AbstractDungeon.cardRandomRng) + " hand=" + handCosts());
        } catch (Throwable t) {
            Log.info("SNECKO disc threw: " + t);
            t.printStackTrace();
        } finally {
            if (sandbox) {
                Driver.end();
                w.uninstall();
            }
        }
        return rows;
    }

    private static List<String> runCase(AbstractPlayer cainong, boolean sandbox) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = SEED;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.playerClass = cainong.getClass().getName();
        spec.playerClassEnum = cainong.chosenClass;
        spec.relics = new String[]{"Snecko Eye", "Unceasing Top", "Bag of Marbles",
                "Bag of Preparation", "Ceramic Fish", "Bottled Lightning", "White Beast Statue"};
        spec.deck = new String[]{"CaiNongMod:BideTime", "CaiNongMod:BideTime", "CaiNongMod:Defend",
                "CaiNongMod:Defend", "CaiNongMod:Strike", "CaiNongMod:Strike", "Strike_R", "Strike_R",
                "Strike_R", "Defend_R", "Defend_R", "Bash", "Burn"};
        spec.monsters = new String[]{NOB};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
        Driver.begin();
        World w = World.captureLive();
        List<String> rows = new ArrayList<String>();
        if (sandbox) {
            w.install();
            Driver.begin();
            try {
                for (int i = 0; i < ROUNDS; i++) {
                    for (int k = 0; k < 3; k++) {
                        rows.add(playRow(i));
                    }
                    rows.add(endRow(i));
                }
            } finally {
                Driver.end();
                w.uninstall();
            }
        } else {
            for (int i = 0; i < ROUNDS; i++) {
                for (int k = 0; k < 3; k++) {
                    rows.add(playRow(i));
                }
                rows.add(endRow(i));
            }
        }
        return rows;
    }

    private static String playRow(int index) {
        String before = "cardCounter=" + counter(AbstractDungeon.cardRandomRng);
        AbstractCard pick = firstPlayable();
        if (pick == null) {
            return "#" + index + " play=none " + before + " hand=" + handCosts();
        }
        Driver.Drain d = Driver.play(pick, firstMonster(), -1);
        return "#" + index + " play=" + pick.name + "(" + pick.cost + "/" + pick.costForTurn + ")"
                + " refused=" + d.refused + " " + before
                + " after=" + counter(AbstractDungeon.cardRandomRng)
                + " energy=" + energy() + " hand=" + handCosts();
    }

    private static String endRow(int index) {
        String before = "cardCounter=" + counter(AbstractDungeon.cardRandomRng);
        Driver.Drain d = Driver.endTurn();
        return "#" + index + " end" + " " + before
                + " after=" + counter(AbstractDungeon.cardRandomRng)
                + " settled=" + d.settled + " turnAdvanced=" + d.turnAdvanced
                + " turn=" + GameActionManager.turn + " energy=" + energy()
                + " hand=" + handCosts() + " digest=" + Digest.stable(Digest.ofCurrent());
    }

    private static int counter(com.megacrit.cardcrawl.random.Random r) {
        return r == null ? -1 : r.counter;
    }

    private static int energy() {
        try {
            return com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String handCosts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AbstractDungeon.player.hand.size(); i++) {
            AbstractCard c = AbstractDungeon.player.hand.group.get(i);
            if (c == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.name).append(':').append(c.cost).append('/').append(c.costForTurn);
        }
        return sb.toString();
    }

    private static AbstractCard firstPlayable() {
        AbstractPlayer p = AbstractDungeon.player;
        for (int i = 0; i < p.hand.size(); i++) {
            AbstractCard c = p.hand.group.get(i);
            if (c == null) {
                continue;
            }
            try {
                if (c.canUse(p, null)) {
                    return c;
                }
            } catch (Throwable ignored) {
                // an unplayable card cannot be a candidate
            }
        }
        return null;
    }

    private static AbstractMonster firstMonster() {
        List<AbstractMonster> ms = AbstractDungeon.getCurrRoom().monsters.monsters;
        for (int i = 0; i < ms.size(); i++) {
            if (ms.get(i) != null && !ms.get(i).isDeadOrEscaped()) {
                return ms.get(i);
            }
        }
        return null;
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
