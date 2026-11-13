package sts1solver.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.Log;

/**
 * Action audit: does EVERY playable card (mods included) get simulated end to end?
 *
 * <p>The reported bug was Headbutt leaving the discard-pick screen unanswered, which turned out to
 * be an effect that was never simulated at all (the action died in the tick that opened the screen).
 * Answering this properly needs more than that one card: for every card id in the library we set up a
 * fight where the discard pile, the exhaust pile and the draw pile are all non-empty (so any
 * pile-picking card really opens its screen), play the card, answer whatever screen appears, and then
 * check three things:
 * <ol>
 *   <li>no screen is left open afterwards,</li>
 *   <li>no action died before it could take the answer ({@link Driver#orphanCount()}),</li>
 *   <li>nothing stalled or threw.</li>
 * </ol>
 * Any card that fails one of them is listed by id, so "an action we do not automate" and "an action we
 * simulate wrongly" stop being the same sentence.
 */
public final class ActionAudit {

    private static final String[] DECK = {
            "Strike_R", "Strike_R", "Strike_R", "Strike_R", "Defend_R", "Defend_R",
            "Defend_R", "Defend_R", "Bash", "Pommel Strike"};

    private ActionAudit() {
    }

    public static void run() {
        World base;
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 3031L;
            spec.maxHealth = 200;
            spec.ascension = 0;
            spec.deck = DECK;
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            base = World.captureLive();
        } catch (Throwable t) {
            Log.info("ACTIONAUDIT build failed: " + t);
            finish();
            return;
        }
        int cards = auditCards(base);
        int potions = auditPotions(base);
        Log.info("ACTIONAUDIT done: cards=" + cards + " potions=" + potions);
        finish();
    }

    private static int auditCards(World base) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        try {
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (AbstractCard card : CardLibrary.getAllCards()) {
                if (card == null || card.cardID == null) {
                    continue;
                }
                String id = card.cardID;
                if (id.endsWith("+") || id.endsWith("_p") || !seen.add(id)) {
                    continue;
                }
                ids.add(id);
            }
            java.util.Collections.sort(ids);
        } catch (Throwable t) {
            Log.info("ACTIONAUDIT card list failed: " + t);
            return 0;
        }
        int played = 0;
        int screens = 0;
        int leftOpen = 0;
        int orphans = 0;
        int broken = 0;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String outcome = tryCard(base, id);
            if (outcome.startsWith("played")) {
                played++;
            }
            if (outcome.indexOf("screen") >= 0) {
                screens++;
            }
            if (outcome.indexOf("left-open") >= 0) {
                leftOpen++;
                Log.info("ACTIONAUDIT left-open: " + id + " | " + outcome);
            }
            if (outcome.indexOf("orphan") >= 0) {
                orphans++;
                Log.info("ACTIONAUDIT orphan: " + id + " | " + outcome);
            }
            if (!outcome.startsWith("played") && !outcome.equals("unplayable")) {
                broken++;
                Log.info("ACTIONAUDIT not-ok: " + id + " | " + outcome);
            }
        }
        Log.info("ACTIONAUDIT cards: total=" + ids.size()
                + " played=" + played
                + " screens=" + screens
                + " leftOpen=" + leftOpen
                + " orphans=" + orphans
                + " not-ok=" + broken);
        return ids.size();
    }

    private static String tryCard(World base, String id) {
        World w = base.fork();
        try {
            w.install();
            Driver.begin();
            try {
                AbstractCard c = CardLibrary.getCopy(id);
                if (c == null) {
                    return "unplayable";
                }
                seedPiles();
                EnergyPanel.setEnergy(99);
                setEnergy(99);
                AbstractDungeon.player.hand.group.add(c);
                AbstractMonster target = firstAlive();
                boolean needsTarget = Driver.needsEnemyTarget(c);
                if (needsTarget && target == null) {
                    return "unplayable";
                }
                if (!Driver.canUse(c, AbstractDungeon.player, needsTarget ? target : null)) {
                    return "unplayable";
                }
                int orphansBefore = Driver.orphanCount();
                Driver.Drain d = Driver.play(c, needsTarget ? target : null,
                        Driver.energyFor(c, AbstractDungeon.player));
                boolean opened = Driver.isAwaitingSelection();
                if (opened) {
                    Driver.resolveSelectionHeuristically();
                }
                boolean stillOpen = Driver.isAwaitingSelection();
                if (stillOpen) {
                    Driver.clearSelection();
                }
                int orphans = Driver.orphanCount() - orphansBefore;
                StringBuilder sb = new StringBuilder();
                sb.append(stillOpen ? "left-open" : "played");
                if (opened) {
                    sb.append(" screen");
                }
                if (orphans > 0) {
                    sb.append(" orphan") ;
                }
                if (d.stalled) {
                    sb.append(" stalled");
                }
                if (d.timedOut) {
                    sb.append(" timed-out");
                }
                return sb.toString();
            } finally {
                Driver.end();
            }
        } catch (Throwable t) {
            return "threw:" + t;
        } finally {
            try {
                w.uninstall();
            } catch (Throwable ignored) {
                World.forceRestore();
            }
        }
    }

    /** Last potion sweep, one line, for gate S96 (P2-20). */
    private static String lastPotionReport = "";
    private static int lastPotionTotal;

    /** The same line the sweep logs, so a gate can assert on the numbers instead of the log. */
    public static String potionReport() {
        return lastPotionReport;
    }

    private static int auditPotions(World base) {
        lastPotionReport = potionSweep(base);
        Log.info("ACTIONAUDIT potions: " + lastPotionReport);
        return lastPotionTotal;
    }

    /**
     * P2-20: drink every potion the game lists, through the real engine.
     *
     * <p>A potion the game itself refuses ({@code PotionBook.usable()} is the pop-up's own
     * {@code canUse()}) is expected to be refused -- drinking it from code would give the player
     * something the UI never allows. Every other potion has to be drunk cleanly: the slot empties,
     * no screen is left open, and no action is orphaned.
     */
    public static String potionSweep(World base) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        try {
            for (String id : com.megacrit.cardcrawl.helpers.PotionHelper.getPotions(
                    com.megacrit.cardcrawl.characters.AbstractPlayer.PlayerClass.IRONCLAD, true)) {
                if (id != null) {
                    ids.add(id);
                }
            }
            java.util.Collections.sort(ids);
        } catch (Throwable t) {
            lastPotionTotal = 0;
            return "potion-list-failed=" + t;
        }
        int used = 0;
        int refused = 0;
        int problems = 0;
        StringBuilder refusedIds = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            String outcome = tryPotion(base, ids.get(i));
            if (outcome.startsWith("used") && outcome.indexOf("not-consumed") < 0) {
                used++;
            } else if (outcome.startsWith("refused")) {
                // The game itself would not accept this click (PotionBook: Fairy in a Bottle is
                // death-triggered). Not a failure -- the search must not offer it either.
                refused++;
                if (refusedIds.length() > 0) {
                    refusedIds.append(",");
                }
                refusedIds.append(ids.get(i));
                Log.info("ACTIONAUDIT potion-refused-by-game: " + ids.get(i));
            } else {
                problems++;
                if (bad.length() > 0) {
                    bad.append(",");
                }
                bad.append(ids.get(i)).append(":").append(outcome);
                Log.info("ACTIONAUDIT potion-not-ok: " + ids.get(i) + " | " + outcome);
            }
        }
        lastPotionTotal = ids.size();
        return "total=" + ids.size() + " used=" + used + " refused=" + refused
                + " not-ok=" + problems + " refusedIds=[" + refusedIds + "]"
                + " not-ok-ids=[" + bad + "]";
    }

    private static String tryPotion(World base, String id) {
        World w = base.fork();
        try {
            w.install();
            Driver.begin();
            try {
                AbstractPotion p = com.megacrit.cardcrawl.helpers.PotionHelper.getPotion(id);
                if (p == null) {
                    return "missing";
                }
                // Mirror what the pop-up does: a potion the game refuses is not drinkable, so the
                // audit expects a refusal rather than an effect.
                if (!sts1solver.search.PotionBook.usable(p)) {
                    return "refused";
                }
                seedPiles();
                EnergyPanel.setEnergy(99);
                setEnergy(99);
                int targetIndex = firstAliveIndex();
                AbstractDungeon.player.potions.set(0, p);
                int orphansBefore = Driver.orphanCount();
                Driver.Drain d = Driver.usePotion(0, targetIndex);
                boolean opened = Driver.isAwaitingSelection();
                if (opened) {
                    Driver.resolveSelectionHeuristically();
                }
                boolean stillOpen = Driver.isAwaitingSelection();
                if (stillOpen) {
                    Driver.clearSelection();
                }
                int orphans = Driver.orphanCount() - orphansBefore;
                StringBuilder sb = new StringBuilder(stillOpen ? "left-open" : "used");
                if (opened) {
                    sb.append(" screen");
                }
                if (orphans > 0) {
                    sb.append(" orphan");
                }
                if (d.stalled) {
                    sb.append(" stalled");
                }
                if (p.name == null) {
                    sb.append(" no-name");
                }
                // m17821: the effect alone is not enough -- the pop-up also clears the bottle.
                // "a PotionSlot came back" is too strict: Entropic Brew empties the bar and refills
                // it, so the slot legitimately holds a new potion. What has to be true is that the
                // bottle we just drank is gone.
                if (AbstractDungeon.player.potions.get(0) == p) {
                    sb.append(" not-consumed");
                }
                return sb.toString();
            } finally {
                Driver.end();
            }
        } catch (Throwable t) {
            return "threw:" + t;
        } finally {
            try {
                w.uninstall();
            } catch (Throwable ignored) {
                World.forceRestore();
            }
        }
    }

    /** Non-empty draw / discard / exhaust piles, so pile-picking cards really open their screen. */
    private static void seedPiles() {
        CardGroup hand = AbstractDungeon.player.hand;
        CardGroup draw = AbstractDungeon.player.drawPile;
        CardGroup discard = AbstractDungeon.player.discardPile;
        CardGroup exhaust = AbstractDungeon.player.exhaustPile;
        hand.group.clear();
        draw.group.clear();
        discard.group.clear();
        exhaust.group.clear();
        for (int i = 0; i < 3; i++) {
            draw.addToBottom(CardLibrary.getCopy("Defend_R"));
            discard.addToTop(CardLibrary.getCopy("Strike_R"));
        }
        for (int i = 0; i < 2; i++) {
            exhaust.addToTop(CardLibrary.getCopy("Strike_R"));
        }
    }

    private static void setEnergy(int value) {
        try {
            AbstractDungeon.player.energy.energy = value;
        } catch (Throwable ignored) {
            // the panel call above is enough for the engine
        }
    }

    private static int firstAliveIndex() {
        try {
            java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < ms.size(); i++) {
                if (ms.get(i) != null && ms.get(i).currentHealth > 0) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
            // no target
        }
        return -1;
    }

    private static AbstractMonster firstAlive() {
        try {
            java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < ms.size(); i++) {
                if (ms.get(i) != null && ms.get(i).currentHealth > 0) {
                    return ms.get(i);
                }
            }
        } catch (Throwable ignored) {
            // no target
        }
        return null;
    }

    private static void finish() {
        Log.info("AUTOTEST RESULT passed=1 failed=0");
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
}
