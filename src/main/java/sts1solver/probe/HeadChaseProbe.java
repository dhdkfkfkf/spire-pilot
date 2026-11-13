package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import java.util.ArrayList;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.ModStatics;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.search.Solver;
import sts1solver.util.Log;

/**
 * B138 (user m08865 / m08974): 头追 (CaiNongMod:HeadChase) opens TWO grid screens -- one over the
 * discard pile, one over the draw pile -- and the live fight moved one card of each, while the
 * sandbox digest recorded the fight unchanged (no move at all). The step that diverged did not play
 * 头追 directly: an auto-played 豹指导 (SealGuide) picked a card out of the hand, and THAT card was
 * 头追. So the probe runs both shapes and prints what the sandbox did: the piles, the digest the
 * search would store, and every screen its walk sees.
 */
public final class HeadChaseProbe {

    private static final String HC = "CaiNongMod:HeadChase";
    private static final String HC_CLASS = "CaiNongMod.cards.HeadChase";
    private static final String SG = "CaiNongMod:SealGuide";
    private static final String SG_CLASS = "CaiNongMod.cards.SealGuide";

    private HeadChaseProbe() {
    }

    public static void run() {
        try {
            if (ModStatics.resolve(HC_CLASS) == null) {
                Log.info("HC skipped: " + HC_CLASS + " is not loaded");
                Log.info("AUTOTEST DONE");
                return;
            }
            buildFight();
            direct();
            if (ModStatics.resolve(SG_CLASS) != null) {
                chain();
            } else {
                Log.info("HC chain skipped: " + SG_CLASS + " is not loaded");
            }
        } catch (Throwable t) {
            Log.info("HC probe threw: " + t);
            t.printStackTrace();
        }
        Log.info("AUTOTEST DONE");
    }

    private static void buildFight() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 20261107L;
        spec.maxHealth = 80;
        spec.ascension = 0;
        spec.deck = new String[]{"Strike_R", "Defend_R", "Bash", "Defend_R", "Strike_R"};
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        Driver.drain(4000, 800L);
    }

    /** 头追 played straight from the hand: one action, two answers, one digest. */
    private static void direct() {
        setPiles(new String[]{HC}, new String[]{"Strike_R", "Defend_R"}, new String[]{"Bash", "Defend_R"});
        Log.info("HC direct start: " + piles() + " digest=" + Digest.stable(Digest.ofCurrent()));
        World w = World.captureLive();
        replay(w, "direct", Action.card(0, 0, 0, "", "head chase", "", HC)
                .withChoice("Strike_R|Bash", "Strike|Bash", "", ""));
        Log.info("HC direct walk:");
        for (String line : Solver.screenWalkForTest(w, Action.card(0, 0, 0, "", "head chase", "", HC), 12)) {
            Log.info("HC direct walk " + line);
        }
        walkByHand(w);
    }

    /** The user's shape: 豹指导 asks for a card to play, 头追 is that card, then its two grids. */
    private static void chain() {
        setPiles(new String[]{SG, HC, "Defend_R", "Defend_R"},
                new String[]{"Strike_R", "Defend_R"}, new String[]{"Bash", "Defend_R"});
        Log.info("HC chain start: " + piles() + " digest=" + Digest.stable(Digest.ofCurrent()));
        World w = World.captureLive();
        replay(w, "chain", Action.card(0, 0, 0, "", "seal guide", "", SG)
                .withChoice(HC + "|Strike_R|Bash", "head chase|Strike|Bash", "", ""));
        Log.info("HC chain walk:");
        for (String line : Solver.screenWalkForTest(w, Action.card(0, 0, 0, "", "seal guide", "", SG), 12)) {
            Log.info("HC chain walk " + line);
        }
    }

    private static void setPiles(String[] hand, String[] discard, String[] draw) {
        AbstractDungeon.player.hand.group.clear();
        AbstractDungeon.player.drawPile.group.clear();
        AbstractDungeon.player.discardPile.group.clear();
        AbstractDungeon.player.exhaustPile.group.clear();
        for (int i = 0; i < hand.length; i++) {
            AbstractDungeon.player.hand.group.add(CardLibrary.getCopy(hand[i]));
        }
        for (int i = 0; i < discard.length; i++) {
            AbstractDungeon.player.discardPile.group.add(CardLibrary.getCopy(discard[i]));
        }
        for (int i = 0; i < draw.length; i++) {
            AbstractDungeon.player.drawPile.group.add(CardLibrary.getCopy(draw[i]));
        }
        AbstractDungeon.player.hand.refreshHandLayout();
    }

    private static void replay(World w, String tag, Action a) {
        List<Action> line = new ArrayList<Action>();
        line.add(a);
        List<Solver.Step> steps = Solver.replay(w, line);
        Log.info("HC " + tag + " replay: steps=" + steps.size()
                + " failedAt=" + Solver.lastReplayFailedAt()
                + " failure=" + Solver.lastReplayFailure()
                + " selectionFailure=" + Driver.lastSelectionFailure());
        for (int i = 0; i < steps.size(); i++) {
            Solver.Step st = steps.get(i);
            Log.info("HC " + tag + " step " + (i + 1) + ": turn=" + st.turn + " hp=" + st.hp
                    + " digest=" + st.digest);
        }
    }

    /** The same two answers, one at a time, printing what the screen layer sees in between. */
    private static void walkByHand(World w) {
        World probe = w.fork();
        boolean installed = false;
        Driver.begin();
        try {
            probe.install();
            installed = true;
            Legal.apply(Action.card(0, 0, 0, "", "head chase", "", HC));
            Log.info("HC walk screen 1: " + Driver.selectionState()
                    + " question=[" + Driver.selectionQuestion() + "]"
                    + " candidates=[" + names(Driver.selectionCandidates()) + "]");
            boolean ok1 = Driver.resolveSelectionByNames("Strike_R");
            Log.info("HC walk answer 1 (Strike_R): ok=" + ok1
                    + " failure=" + Driver.lastSelectionFailure()
                    + " next=[" + Driver.selectionQuestion() + "]"
                    + " candidates=[" + names(Driver.selectionCandidates()) + "]"
                    + " " + piles());
            Driver.drain(400, 300L);
            boolean ok2 = Driver.resolveSelectionByNames("Bash");
            Log.info("HC walk answer 2 (Bash): ok=" + ok2 + " failure=" + Driver.lastSelectionFailure());
            Driver.Drain d2 = Driver.drain(600, 400L);
            Log.info("HC walk after answer 2: " + Driver.selectionState()
                    + " settled=" + d2.settled + " steps=" + d2.steps
                    + " " + piles() + " digest=" + Digest.stable(Digest.ofCurrent()));
        } catch (Throwable t) {
            Log.info("HC walk threw: " + t);
            t.printStackTrace();
        } finally {
            if (installed) {
                try {
                    probe.uninstall();
                } catch (Throwable t) {
                    Log.info("HC walk uninstall threw: " + t);
                }
            }
            Driver.end();
        }
    }

    private static String names(List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            AbstractCard c = cards.get(i);
            if (c == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.name).append('[').append(c.cardID).append(']');
        }
        return sb.toString();
    }

    private static String pile(List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            AbstractCard c = cards.get(i);
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

    private static String piles() {
        return "H=[" + pile(AbstractDungeon.player.hand.group) + "]"
                + " D=[" + pile(AbstractDungeon.player.drawPile.group) + "]"
                + " X=[" + pile(AbstractDungeon.player.discardPile.group) + "]";
    }
}
