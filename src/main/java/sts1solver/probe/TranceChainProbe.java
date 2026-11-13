package sts1solver.probe;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction;
import com.megacrit.cardcrawl.actions.utility.NewQueueCardAction;
import com.megacrit.cardcrawl.actions.watcher.PressEndTurnButtonAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.ModStatics;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * B126 (user m04653): a power whose atStartOfTurn presses End Turn (the same call CaiNong's
 * TrancePower makes) must skip the turn it fired in, and N stacks must skip N turns in a row.
 * The probe also checks what the skipped turn still does: draw a hand, and let a post-draw power
 * auto-play a card (唐/TangPower's shape) -- and what happens when the auto-played card is itself
 * 昏睡/Trance, which stacks the power and skips yet another turn.
 */
public final class TranceChainProbe {

    private static final String TRANCE = "CaiNongMod:Trance";
    private static final String CHARACTER = "CaiNongMod.Characters.CaiNong";

    private TranceChainProbe() {
    }

    public static void run() {
        try {
            one("skip1", 1, false);
            one("skip2", 2, false);
            one("skip1+autoplay", 1, true);
            realTang();
        } catch (Throwable t) {
            Log.info("CHAIN probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    private static void one(String tag, int stacks, boolean autoPlay) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261104L;
        spec.maxHealth = 80;
        spec.ascension = 4;
        spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Defend_R", "Defend_R", "Defend_R", "Defend_R", "Bash"};
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World w = World.captureLive();
        w.install();
        Driver.begin();
        try {
            final boolean[] fired = new boolean[1];
            final String[] played = new String[1];
            AbstractDungeon.player.addPower(skipPower(stacks));
            if (autoPlay) {
                AbstractDungeon.player.addPower(autoPlayPower(fired, played));
            }
            int turnBefore = GameActionManager.turn;
            String before = "turn=" + turnBefore + " hand=" + AbstractDungeon.player.hand.size()
                    + " energy=" + Driver.energy() + " powers=[" + powers() + "]";
            Driver.Drain end = Driver.endTurn();
            Log.info("CHAIN " + tag + " before: " + before);
            Log.info("CHAIN " + tag + " after: turn=" + turnBefore + "->" + GameActionManager.turn
                    + " hand=" + AbstractDungeon.player.hand.size()
                    + " energy=" + Driver.energy()
                    + " block=" + AbstractDungeon.player.currentBlock
                    + " powers=[" + powers() + "]"
                    + " endTurnQueued=" + (AbstractDungeon.player != null && AbstractDungeon.player.endTurnQueued)
                    + " isEndingTurn=" + (AbstractDungeon.player != null && AbstractDungeon.player.isEndingTurn)
                    + " settled=" + end.settled + " timedOut=" + end.timedOut
                    + " refused=" + end.refused + " steps=" + end.steps);
            Log.info("CHAIN " + tag + " skipTurns=" + (GameActionManager.turn - turnBefore)
                    + " postDrawFired=" + fired[0]
                    + " autoPlayed=" + (played[0] == null ? "-" : played[0])
                    + " hand=" + handNames()
                    + " digest=" + Digest.stable(Digest.ofCurrent()));
            Driver.Drain again = Driver.drain(2000, 1500L);
            Log.info("CHAIN " + tag + " extraDrain: turn=" + GameActionManager.turn
                    + " hand=" + AbstractDungeon.player.hand.size()
                    + " settled=" + again.settled + " steps=" + again.steps
                    + " postDrawFired=" + fired[0]
                    + " autoPlayed=" + (played[0] == null ? "-" : played[0]));
        } finally {
            Driver.end();
            w.readBack();
            w.uninstall();
        }
    }

    /**
     * The real thing: 昏睡/TrancePower on the player plus 唐/TangPower. With a deck of nothing but
     * 昏睡, the skipped turn draws 5 昏睡 and 唐 auto-plays one of them, so TrancePower is applied
     * again and the NEXT turn is skipped as well. Live must land two turns later, not one.
     */
    private static void realTang() {
        Class<?> charClass = ModStatics.resolve(CHARACTER);
        if (charClass == null) {
            Log.info("CHAIN realTang skipped: " + CHARACTER + " is not loaded");
            return;
        }
        Class<?> tangClass = ModStatics.resolve("CaiNongMod.Powers.TangPower");
        Class<?> tranceClass = ModStatics.resolve("CaiNongMod.Powers.TrancePower");
        if (tangClass == null || tranceClass == null) {
            Log.info("CHAIN realTang skipped: TangPower/TrancePower not loadable");
            return;
        }
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261104L;
        spec.maxHealth = 80;
        spec.ascension = 4;
        try {
            java.util.List<com.megacrit.cardcrawl.characters.AbstractPlayer> all =
                    com.megacrit.cardcrawl.core.CardCrawlGame.characterManager.getAllCharacters();
            for (int i = 0; i < all.size(); i++) {
                com.megacrit.cardcrawl.characters.AbstractPlayer p = all.get(i);
                if (p != null && CHARACTER.equals(p.getClass().getName())) {
                    spec.playerClass = p.getClass().getName();
                    spec.playerClassEnum = p.chosenClass;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.info("CHAIN realTang: could not read the character: " + t);
        }
        if (spec.playerClass == null) {
            Log.info("CHAIN realTang skipped: no registered " + CHARACTER);
            return;
        }
        spec.relics = new String[0];
        spec.deck = new String[]{TRANCE, TRANCE, TRANCE, TRANCE, TRANCE};
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World w = World.captureLive();
        w.install();
        Driver.begin();
        try {
            AbstractPower tang = power(tangClass, 1);
            AbstractPower trance = power(tranceClass, 1);
            if (tang == null || trance == null) {
                Log.info("CHAIN realTang: could not build the powers");
                return;
            }
            AbstractDungeon.player.addPower(tang);
            AbstractDungeon.player.addPower(trance);
            int turnBefore = GameActionManager.turn;
            Log.info("CHAIN realTang before: turn=" + turnBefore
                    + " hand=" + handNames() + " powers=[" + powers() + "]");
            Driver.Drain end = Driver.endTurn();
            Log.info("CHAIN realTang after: turn=" + turnBefore + "->" + GameActionManager.turn
                    + " hand=" + handNames()
                    + " powers=[" + powers() + "]"
                    + " settled=" + end.settled + " timedOut=" + end.timedOut
                    + " refused=" + end.refused + " steps=" + end.steps
                    + " digest=" + Digest.stable(Digest.ofCurrent()));
            Driver.Drain again = Driver.drain(2000, 1500L);
            Log.info("CHAIN realTang extraDrain: turn=" + GameActionManager.turn
                    + " hand=" + AbstractDungeon.player.hand.size()
                    + " settled=" + again.settled + " steps=" + again.steps
                    + " powers=[" + powers() + "]");
        } finally {
            Driver.end();
            w.readBack();
            w.uninstall();
        }
    }

    private static AbstractPower power(Class<?> cls, int amount) {
        try {
            java.lang.reflect.Constructor<?> c = cls.getConstructor(AbstractCreature.class, int.class);
            return (AbstractPower) c.newInstance(AbstractDungeon.player, amount);
        } catch (Throwable t) {
            Log.info("CHAIN could not build " + cls.getName() + ": " + t);
            return null;
        }
    }

    /** The same three lines TrancePower.atStartOfTurn runs, with no card-list special case. */
    private static AbstractPower skipPower(final int stacks) {
        return new AbstractPower() {
            {
                this.owner = AbstractDungeon.player;
                this.ID = "sts1solver:SkipTurnProbe";
                this.name = "skip turn probe";
                this.description = "";
                this.type = AbstractPower.PowerType.DEBUFF;
                this.isTurnBased = true;
                this.img = null;
                this.amount = stacks;
            }

            @Override
            public void atStartOfTurn() {
                addToTop(new PressEndTurnButtonAction());
                --this.amount;
                if (this.amount <= 0) {
                    addToBot(new RemoveSpecificPowerAction(this.owner, this.owner, this.ID));
                }
            }

            @Override
            public void updateDescription() {
                this.description = "";
            }
        };
    }

    /**
     * 唐/TangPower's shape: the hook runs before the queued draw, so it queues an action at the
     * BOTTOM; that action picks a hand card once the draw has run and plays it.
     */
    private static AbstractPower autoPlayPower(final boolean[] fired, final String[] played) {
        return new AbstractPower() {
            {
                this.owner = AbstractDungeon.player;
                this.ID = "sts1solver:AutoPlayProbe";
                this.name = "auto play probe";
                this.description = "";
                this.type = AbstractPower.PowerType.BUFF;
                this.isTurnBased = false;
                this.img = null;
            }

            @Override
            public void atStartOfTurnPostDraw() {
                if (fired[0]) {
                    return;
                }
                fired[0] = true;
                addToBot(new AbstractGameAction() {
                    @Override
                    public void update() {
                        if (!AbstractDungeon.player.hand.isEmpty()) {
                            AbstractCard c = AbstractDungeon.player.hand.group.get(0);
                            played[0] = c.cardID + "/" + c.name;
                            addToTop(new NewQueueCardAction(c, null, false, true));
                        }
                        this.isDone = true;
                    }
                });
            }

            @Override
            public void updateDescription() {
                this.description = "";
            }
        };
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
}
