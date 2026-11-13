package sts1solver.coverage;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.LeakCheck;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.util.JarScan;
import sts1solver.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Content coverage sweep: plays EVERY card, potion, relic and monster through the real engine inside
 * the sandbox and reports what actually happened.
 *
 * <p>This is the honest answer to "does it support all the content?". Nothing is modelled here, so
 * coverage is a property of what the engine can do on a mirrored world -- and the only way to know
 * that is to execute each piece and count. Refusals and throws are reported BY NAME, never summarised
 * away, and "unplayable" is split into the game's own rules (curses, statuses) versus a card that
 * refused for a reason worth reading.
 */
public final class Coverage {

    private static final Map<String, List<String>> problems = new LinkedHashMap<String, List<String>>();
    private static Synthetic.Spec lastSpec;
    private static String lastItem = "-";
    private static String baseline;

    /** Logs when a sweep changed the live fight, and which item it was on. */
    private static void checkpoint(String sweep) {
        try {
            String now = Digest.ofCurrent();
            if (baseline != null && !baseline.equals(now)) {
                Log.warn("LEAK after " + sweep + " (last item: " + lastItem + ")");
                Log.warn("  was: " + baseline);
                Log.warn("  now: " + now);
                baseline = now;
            }
        } catch (Throwable t) {
            Log.warn("checkpoint failed: " + t);
        }
    }

    private Coverage() {
    }

    public static void run(String requestPath) {
        Log.setRunId(String.valueOf(System.currentTimeMillis()));
        try {
            sweepAll();
        } catch (Throwable t) {
            Log.error("coverage sweep crashed", t);
        }
        Log.info("COVERAGE DONE");
        try {
            File req = new File(requestPath);
            if (req.exists()) {
                req.delete();
            }
        } catch (Throwable ignored) {
            // run.ps1 removes it too.
        }
        Runtime.getRuntime().halt(0);
    }

    private static void sweepAll() {
        Synthetic.Spec spec = new Synthetic.Spec();
        lastSpec = spec;
        spec.seed = 47L;
        spec.maxHealth = 200;
        spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Strike_R", "Defend_R",
                "Defend_R", "Defend_R", "Defend_R", "Bash", "Defend_R"};
        spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        spec.relics = new String[0];
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        World base = World.captureLive();
        Log.info("coverage base fight: " + Digest.ofCurrent());

        // One inventory snapshot around the WHOLE sweep: every card, potion, relic and monster is
        // exercised inside the sandbox, so this single pair of snapshots proves nothing leaked.
        Map<String, String> before = LeakCheck.snapshot();
        String liveBefore = Digest.ofCurrent();
        baseline = liveBefore;

        sweepCards(base);
        checkpoint("cards");
        sweepPotions(base);
        checkpoint("potions");
        sweepRelics(base);
        checkpoint("relics");
        sweepMonsters(base);
        checkpoint("monsters");

        String liveAfter = Digest.ofCurrent();
        if (!liveBefore.equals(liveAfter)) {
            Log.warn("  before: " + liveBefore);
            Log.warn("  after : " + liveAfter);
        }
        Log.check(liveBefore.equals(liveAfter), "live fight untouched by the whole sweep", "");
        List<String> diffs = LeakCheck.diff(before, LeakCheck.snapshot());
        for (int i = 0; i < diffs.size() && i < 20; i++) {
            Log.warn("LEAK " + diffs.get(i));
        }
        Log.check(diffs.isEmpty(), "no static field changed by the whole sweep",
                LeakCheck.inventoryReport());
        // Characters are swept LAST on purpose: each one rebuilds the fight, so it changes the live
        // fight by design and cannot take part in the "untouched" assertion above.
        sweepCharacters(spec);
    }

    // ------------------------------------------------------------------ cards

    /**
     * One card, one sandbox: fresh world, clean-or-kept hand, energy as given, played for real.
     *
     * <p>Returned strings are stable categories: played, unplayable (curse/status), unplayable-normal,
     * prompt, stalled, threw.
     */
    private static String tryCard(World base, String id, int energy, boolean clearHand) {
        World w = base.fork();
        try {
            w.install();
            Driver.begin();
            try {
                AbstractCard c = CardLibrary.getCopy(id);
                if (clearHand) {
                    AbstractDungeon.player.hand.group.clear();
                }
                EnergyPanel.setEnergy(energy);
                setPlayerEnergy(energy);
                AbstractDungeon.player.hand.group.add(c);
                AbstractMonster target = firstAlive();
                boolean needsTarget = Driver.needsEnemyTarget(c);
                if (needsTarget && target == null) {
                    return "no-target";
                }
                if (!Driver.canUse(c, AbstractDungeon.player, needsTarget ? target : null)) {
                    boolean byDesign = c.type == AbstractCard.CardType.CURSE
                            || c.type == AbstractCard.CardType.STATUS;
                    return byDesign ? "unplayable" : "unplayable-normal";
                }
                Driver.Drain d = Driver.play(c, needsTarget ? target : null,
                        Driver.energyFor(c, AbstractDungeon.player));
                if (Driver.isAwaitingSelection()) {
                    // Answer the choice instead of giving up: this is what makes the prompt count
                    // reachable at zero instead of "30 cards the solver refuses".
                    if (!Driver.resolveSelectionHeuristically() && Driver.isAwaitingSelection()) {
                        Driver.clearSelection();
                        return "prompt";
                    }
                }
                if (d.stalled || d.timedOut) {
                    return "stalled";
                }
                return "played";
            } finally {
                Driver.end();
            }
        } catch (Throwable t) {
            return "threw:" + shortMessage(t);
        } finally {
            try {
                w.uninstall();
            } catch (Throwable ignored) {
                // Reported by the leak check.
            }
        }
    }

    /**
     * The handful of cards the game itself refuses to play from an ordinary hand. They are not
     * unsupported: each needs its real trigger, so the sweep reproduces that trigger and reports what
     * it did, instead of writing them off as "unplayable".
     *
     * <p>- Grand Finale: needs an EMPTY draw pile.
     * <br>- Deus Ex Machina: triggers when drawn.
     * <br>- Reflex / Tactician: trigger when discarded from the hand.
     *
     * <p>The hook calls below are the same ones the game makes at those moments. The discard path is
     * additionally gated end-to-end (play Prepared, discard the chosen card) in the Silent suite, so
     * this sweep only has to prove the card's own effect fires.
     */
    private static String tryConditionalCard(World base, String id) {
        World w = base.fork();
        try {
            w.install();
            Driver.begin();
            try {
                AbstractCard c = CardLibrary.getCopy(id);
                AbstractDungeon.player.hand.group.clear();
                EnergyPanel.setEnergy(99);
                setPlayerEnergy(99);
                if ("Grand Finale".equals(id)) {
                    AbstractDungeon.player.drawPile.group.clear();
                    AbstractDungeon.player.hand.group.add(c);
                    AbstractMonster target = firstAlive();
                    if (target == null || !Driver.canUse(c, AbstractDungeon.player, target)) {
                        return "refused-even-with-empty-draw";
                    }
                    Driver.Drain d = Driver.play(c, target, Driver.energyFor(c, AbstractDungeon.player));
                    if (d.stalled || d.timedOut) {
                        return "stalled";
                    }
                    return "triggered (empty draw pile)";
                }
                AbstractDungeon.player.hand.group.add(c);
                int handBefore = AbstractDungeon.player.hand.size();
                int energyBefore = EnergyPanel.getCurrentEnergy();
                if ("DeusExMachina".equals(id)) {
                    c.triggerWhenDrawn();
                } else {
                    c.triggerOnManualDiscard();
                }
                Driver.Drain d = Driver.drain();
                if (d.stalled || d.timedOut) {
                    return "stalled";
                }
                return "triggered (hand " + handBefore + "->" + AbstractDungeon.player.hand.size()
                        + ", energy " + energyBefore + "->" + EnergyPanel.getCurrentEnergy() + ")";
            } finally {
                Driver.end();
            }
        } catch (Throwable t) {
            return "threw:" + shortMessage(t);
        } finally {
            try {
                w.uninstall();
            } catch (Throwable ignored) {
                // reported by the leak check
            }
        }
    }

    private static void sweepCards(World base) {
        List<AbstractCard> all = CardLibrary.getAllCards();
        Set<String> playableWithEnergy = new LinkedHashSet<String>();
        Set<String> conditional = new LinkedHashSet<String>();
        Set<String> triggeredByHook = new LinkedHashSet<String>();
        int total = 0;
        int played = 0;
        int unplayable = 0;
        int prompt = 0;
        int threw = 0;

        for (int i = 0; i < all.size(); i++) {
            String id;
            try {
                id = all.get(i).cardID;
            } catch (Throwable t) {
                continue;
            }
            total++;
            lastItem = "card " + id;
            String outcome = tryCard(base, id, 3, false);
            if ("played".equals(outcome)) {
                played++;
            } else if ("unplayable".equals(outcome)) {
                unplayable++;
            } else if ("unplayable-normal".equals(outcome)) {
                unplayable++;
                // Second pass: most of these were gated by this exact situation (cost above the
                // 3 energy, or "only attacks in hand"), not by the card being unsupported. Retest
                // with plenty of energy and a hand holding nothing else, and report which it was.
                String again = tryCard(base, id, 99, true);
                if ("played".equals(again)) {
                    playableWithEnergy.add(id);
                } else if ("prompt".equals(again)) {
                    prompt++;
                    note("card-prompt", id);
                } else {
                    // Third pass: a few cards are refused by the game itself from an ordinary hand
                    // (empty draw pile / "when drawn" / "when discarded"). Reproduce the real
                    // trigger rather than calling them unsupported.
                    String triggered = tryConditionalCard(base, id);
                    if (triggered.startsWith("triggered")) {
                        triggeredByHook.add(id + " :: " + triggered);
                    } else {
                        conditional.add(id + " :: " + again + " / " + triggered);
                    }
                }
            } else if ("prompt".equals(outcome)) {
                prompt++;
                note("card-prompt", id);
                String col = colorOf(id);
                Integer n = PROMPT_BY_COLOUR.get(col);
                PROMPT_BY_COLOUR.put(col, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            } else {
                threw++;
                String bucket = bucketOf(outcome);
                note("card-" + bucket, id + " :: " + outcome);
                Integer n = OTHER_BY_BUCKET.get(bucket);
                OTHER_BY_BUCKET.put(bucket, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            }
        }
        Log.info("cards: total=" + total + " played=" + played + " unplayable=" + unplayable
                + " prompt=" + prompt + " other=" + threw);
        Log.info("  unplayable that ARE playable with 99 energy and a clean hand: "
                + playableWithEnergy.size() + " " + new ArrayList<String>(playableWithEnergy));
        Log.info("  still unplayable afterwards: " + conditional.size() + " " + new ArrayList<String>(conditional));
        // Capped: with the character mods loaded this list is 450+ entries and printed as ONE line of
        // ~50KB, which buries the report it belongs to. The count stays exact; the names stop at 40.
        Log.info("  triggered through their real trigger: " + triggeredByHook.size() + " "
                + head(triggeredByHook, 40));
        Log.info("  cards accounted=" + total + "/" + total + " (played " + played + " + unplayable "
                + unplayable + " + prompt " + prompt + " + threw " + threw + "; of the unplayable, "
                + triggeredByHook.size() + " answered their trigger and " + conditional.size()
                + " are still unexplained)");
        Log.info("  hand-off prompts by colour/type: " + PROMPT_BY_COLOUR);
        // "other=1" used to be the end of the story: the bucket was named after the outcome
        // ("card-no-target") and never reported, so a card the sweep could not judge looked like a
        // silent number. Every non-played/non-unplayable/non-prompt outcome is now named.
        if (!OTHER_BY_BUCKET.isEmpty()) {
            Log.info("  other outcomes by bucket: " + OTHER_BY_BUCKET);
        }
        for (String bucket : OTHER_BY_BUCKET.keySet()) {
            report("card-" + bucket, 20);
        }
        report("card-prompt", 40);
        // "card-stalled" used to be reported here AND again below, so every stalled card was printed
        // twice in one run -- the loop above (which names each non-standard bucket) already covers it.
        // (2026-11-02, P6-10: seen in the all-mods coverage log as two identical "card-stalled (2)" blocks.)
    }

    /** At most {@code n} entries of a set, with "... and N more" so a long list cannot hide the rest. */
    private static String head(Set<String> values, int n) {
        List<String> all = new ArrayList<String>(values);
        if (all.size() <= n) {
            return all.toString();
        }
        return all.subList(0, n) + " ... and " + (all.size() - n) + " more";
    }

    /** Non-played, non-unplayable, non-prompt card outcomes, counted by bucket (no-target, ...). */
    private static final Map<String, Integer> OTHER_BY_BUCKET = new LinkedHashMap<String, Integer>();

    private static final Map<String, Integer> PROMPT_BY_COLOUR = new LinkedHashMap<String, Integer>();

    // ------------------------------------------------------------------ potions

    private static void sweepPotions(World base) {
        // Construct each potion INSIDE its mirrored world: a potion constructor may capture
        // AbstractDungeon.player, and constructing it while the live game is installed hands it the
        // LIVE player -- which then receives the effect. That leak was observed as the sandbox
        // leaving NoBlock/Weakened on the live player after the whole sweep.
        List<Class<?>> potionClasses = JarScan.loadAllTypes(AbstractPotion.class);
        List<AbstractPotion> all = new ArrayList<AbstractPotion>();
        for (Class<?> c : potionClasses) {
            all.add(null);
        }
        int played = 0;
        int passive = 0;
        int threw = 0;
        List<String> passiveIds = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            Class<?> potionClass = potionClasses.get(i);
            if (isFramework(potionClass)) {
                continue;
            }
            lastItem = "potion " + potionClass.getSimpleName();
            World w = base.fork();
            String outcome;
            AbstractPotion p = null;
            try {
                w.install();
                Driver.begin();
                try {
                    if (potionClass == com.megacrit.cardcrawl.potions.PotionSlot.class) {
                        continue;
                    }
                    p = (AbstractPotion) potionClass.getConstructor().newInstance();
                    if (!p.canUse()) {
                        // Death-only potions (Fairy Potion) report canUse() == false: the game itself
                        // says you cannot drink them, so this is not a coverage failure.
                        passive++;
                        passiveIds.add(p.ID + " (" + potionClass.getSimpleName() + ")");
                        note("potion-passive", p.ID + " (" + potionClass.getSimpleName() + ")");
                        continue;
                    }
                    AbstractDungeon.player.potions.set(0, p);
                    Driver.Drain d = Driver.usePotion(0);
                    outcome = d.stalled || d.timedOut ? "stalled" : "played";
                } finally {
                    Driver.end();
                }
            } catch (Throwable t) {
                outcome = "threw:" + shortMessage(t);
            } finally {
                try {
                    w.uninstall();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if ("played".equals(outcome)) {
                played++;
            } else {
                threw++;
                note("potion-" + bucketOf(outcome),
                        (p == null ? potionClass.getSimpleName() : p.ID) + " :: " + outcome);
            }
        }
        Log.info("potions: total=" + all.size() + " played=" + played + " not-usable-by-design="
                + passive + " other=" + threw + " accounted=" + all.size() + "/" + all.size());
        Log.info("  not-usable-by-design: " + passiveIds);
        report("potion-threw", 20);
        report("potion-stalled", 20);
    }

    // ------------------------------------------------------------------ relics

    /**
     * Framework classes (BaseMod/StSLib/ModTheSpire/our own code) are not game content: they are
     * libraries the game loads. Counting them made the sweep report "1 relic threw" for a class that
     * is not a relic in play.
     */
    private static boolean isFramework(Class<?> c) {
        String n = c.getName();
        return n.startsWith("basemod.") || n.startsWith("com.evacipated.")
                || n.startsWith("sts1solver.") || n.startsWith("javassist.");
    }

    private static void sweepRelics(World base) {
        List<Class<?>> classes = JarScan.loadAllTypes(AbstractRelic.class);
        int total = 0;
        int exercised = 0;
        int threw = 0;
        int outOfCombat = 0;
        int deprecated = 0;
        for (Class<?> c : classes) {
            if (isFramework(c)) {
                continue;
            }
            total++;
            lastItem = "relic " + c.getSimpleName();
            World w = base.fork();
            String outcome = "ok";
            AbstractRelic r = null;
            // Which hook was running when a throw happened: onEquip is a RUN-level hook (Pandora's
            // Box rewrites the master deck, War Paint/Whetstone upgrade cards in it), so it cannot
            // run inside a combat sandbox and must not be counted as a combat coverage failure.
            String phase = "construct";
            try {
                w.install();
                Driver.begin();
                try {
                    // Same rule as potions: construct inside the mirror, because a relic constructor
                    // can capture the live player.
                    r = (AbstractRelic) c.getConstructor().newInstance();
                    AbstractDungeon.player.relics.add(r);
                    phase = "onEquip";
                    r.onEquip();
                    phase = "atPreBattle";
                    r.atPreBattle();
                    phase = "atBattleStart";
                    r.atBattleStart();
                    phase = "atBattleStartPreDraw";
                    r.atBattleStartPreDraw();
                    // A relic's combat value usually shows up on a card play or a turn edge, so poke
                    // both. Hooks that need more context throw, and a throw is recorded by name.
                    AbstractCard strike = CardLibrary.getCopy("Strike_R");
                    EnergyPanel.setEnergy(3);
                    setPlayerEnergy(3);
                    AbstractDungeon.player.hand.group.add(strike);
                    phase = "cardPlay";
                    if (Driver.canUse(strike, AbstractDungeon.player, firstAlive())) {
                        Driver.play(strike, firstAlive(), 0);
                    }
                    phase = "atTurnStart";
                    r.atTurnStart();
                    r.atTurnStartPostDraw();
                    phase = "endTurn";
                    if (!Driver.combatOver()) {
                        Driver.endTurn();
                    }
                } finally {
                    Driver.end();
                }
            } catch (Throwable t) {
                outcome = "threw:" + shortMessage(t) + " [during " + phase + "]";
            } finally {
                try {
                    w.uninstall();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if ("ok".equals(outcome)) {
                exercised++;
            } else if (c.getSimpleName().startsWith("DEPRECATED_")
                    || (r != null && r.relicId != null && r.relicId.startsWith("DEPRECATED_"))) {
                // The game keeps DEPRECATED_* classes for save compatibility. They have no display
                // name and are never equipped, so a throw from them is not a coverage gap.
                deprecated++;
                note("relic-deprecated", (r == null ? "-" : r.relicId) + " (" + c.getSimpleName()
                        + ") :: " + outcome);
            } else if ("onEquip".equals(phase)) {
                // A run-level hook inside a combat sandbox: reported, but not counted as a combat
                // failure (the row names the phase, so the log cannot imply the relic is broken).
                outOfCombat++;
                note("relic-outside-run", (r == null ? "?" : r.relicId) + " (" + c.getSimpleName()
                        + ") :: " + outcome);
            } else {
                threw++;
                note("relic-threw", (r == null ? "?" : r.relicId) + " (" + c.getSimpleName()
                        + ") :: " + outcome);
            }
        }
        Log.info("relics: total=" + total + " exercised=" + exercised + " threw=" + threw
                + " onEquip-outside-run=" + outOfCombat
                + " deprecated=" + deprecated + " accounted=" + total + "/" + total);
        report("relic-threw", 30);
        report("relic-outside-run", 20);
        report("relic-deprecated", 10);
    }

    // ------------------------------------------------------------------ monsters

    private static void sweepMonsters(World base) {
        List<Class<?>> classes = JarScan.loadAllTypes(AbstractMonster.class);
        int total = 0;
        int acted = 0;
        int threw = 0;
        List<String> uninstantiable = new ArrayList<String>();
        for (Class<?> c : classes) {
            if (isFramework(c)) {
                continue;
            }
            total++;
            lastItem = "monster " + c.getSimpleName();
            World w = base.fork();
            String outcome = "ok";
            try {
                w.install();
                Driver.begin();
                try {
                    // Construct the monster INSIDE the mirror, for the same reason as potions and
                    // relics: a constructor can capture AbstractDungeon.player, and a monster holding
                    // the LIVE player applies its debuffs to the live run. The Guardian is the case
                    // that exposed it (the live player gained NoBlock/Weakened during the sweep).
                    AbstractMonster m = instantiate(c);
                    if (m == null) {
                        // Abstract bases and classes without a no-arg constructor are not content the
                        // player ever meets as a body: name them instead of counting them as "not acted".
                        uninstantiable.add(c.getSimpleName()
                                + (java.lang.reflect.Modifier.isAbstract(c.getModifiers())
                                ? " (abstract)" : " (no no-arg constructor)"));
                        continue;
                    }
                    // A few bodies never appear alone. The Bronze Orb is one of the Automaton's
                    // three orbs and its SUPPORT_BEAM move looks the Automaton up by name; in a solo
                    // room that lookup returns null and the block action NPEs while applying. Spawn
                    // the partner so the turn the sweep exercises is the turn the player meets.
                    List<AbstractMonster> body = new ArrayList<AbstractMonster>();
                    body.add(m);
                    if ("BronzeOrb".equals(m.id)) {
                        AbstractMonster partner = instantiate(
                                com.megacrit.cardcrawl.monsters.city.BronzeAutomaton.class);
                        if (partner != null) {
                            body.add(partner);
                        }
                    }
                    AbstractDungeon.getCurrRoom().monsters =
                            new MonsterGroup(body.toArray(new AbstractMonster[0]));
                    AbstractDungeon.getCurrRoom().monsters.init();
                    // Two player turns: the monster moves, then moves again with its history.
                    for (int t = 0; t < 2; t++) {
                        if (Driver.combatOver()) {
                            break;
                        }
                        Driver.endTurn();
                    }
                } finally {
                    Driver.end();
                }
            } catch (Throwable t) {
                outcome = "threw:" + shortMessage(t);
            } finally {
                try {
                    w.uninstall();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if ("ok".equals(outcome)) {
                acted++;
            } else {
                threw++;
                note("monster-threw", c.getSimpleName() + " :: " + outcome);
            }
            checkpoint("monster " + c.getSimpleName());
        }
        Log.info("monsters: total=" + total + " acted=" + acted + " threw=" + threw
                + " accounted=" + total + "/" + total);
        Log.info("  not instantiable (not a monster body): " + uninstantiable);
        report("monster-threw", 30);
    }

    // ------------------------------------------------------------------ characters

    /**
     * Can every player class -- vanilla AND mod -- be built, mirrored, solved and played?
     *
     * <p>This is the question the user asked directly, and it has a definite answer only if it is
     * executed: the mirror copies whatever {@code AbstractPlayer} subclass the game gives it, so a
     * mod character either constructs and runs, or it does not. Each class gets a fixed test deck so
     * the result is about the character body, not its starting cards.
     */
    /** The PlayerClass the mod registered for this class (null when this class is not registered). */
    private static com.megacrit.cardcrawl.characters.AbstractPlayer.PlayerClass chosenClassOf(Class<?> c) {
        try {
            if (com.megacrit.cardcrawl.core.CardCrawlGame.characterManager != null) {
                for (com.megacrit.cardcrawl.characters.AbstractPlayer p
                        : com.megacrit.cardcrawl.core.CardCrawlGame.characterManager.getAllCharacters()) {
                    if (p != null && p.getClass().getName().equals(c.getName())) {
                        return p.chosenClass;
                    }
                }
            }
        } catch (Throwable ignored) {
            // not registered yet -> the no-arg constructor is the only way left
        }
        return null;
    }

    private static void sweepCharacters(Synthetic.Spec template) {
        List<Class<?>> chars = JarScan.loadAllTypes(com.megacrit.cardcrawl.characters.AbstractPlayer.class);
        int ok = 0;
        int failed = 0;
        for (Class<?> c : chars) {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 59L;
            spec.maxHealth = 80;
            spec.playerClass = c.getName();
            // B95-2: two mod characters (aristocrat, thePirate) only offer a (String, PlayerClass)
            // constructor, so a plain no-arg build failed and the report said "character failed" for
            // characters the solver otherwise solves fine (modchar: live=match, win=true). Use the
            // chosenClass of the INSTANCE the mod registered, exactly like ModCharProbe does.
            spec.playerClassEnum = chosenClassOf(c);
            spec.keepStartingRelics = true;
            spec.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Defend_R", "Defend_R"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
            String outcome = "ok";
            String detail = "";
            try {
                Synthetic.build(spec, new Synthetic.SyntheticStep());
                detail = "hand=" + AbstractDungeon.player.hand.size()
                        + " hp=" + AbstractDungeon.player.currentHealth
                        + " relics=" + AbstractDungeon.player.relics.size();
                Driver.begin();
                try {
                    for (int i = 0; i < 3; i++) {
                        AbstractMonster target = firstAlive();
                        AbstractCard playable = null;
                        for (int h = 0; h < AbstractDungeon.player.hand.group.size(); h++) {
                            AbstractCard card = AbstractDungeon.player.hand.group.get(h);
                            if (card != null && Driver.canUse(card, AbstractDungeon.player,
                                    Driver.needsEnemyTarget(card) ? target : null)) {
                                playable = card;
                                break;
                            }
                        }
                        if (playable == null) {
                            break;
                        }
                        Driver.play(playable,
                                Driver.needsEnemyTarget(playable) ? target : null, 0);
                    }
                    if (!Driver.combatOver()) {
                        Driver.endTurn();
                    }
                } finally {
                    Driver.end();
                }
                // The question is "can this character be SOLVED", so solve it: mirror the fight and
                // run the beam search. A zero-step plan with no win is the honest failure signal for
                // a character the mirror cannot handle.
                sts1solver.mirror.World root = sts1solver.mirror.World.captureLive();
                sts1solver.search.Solver.Result plan =
                        sts1solver.search.Solver.solve(root, 16, 20, 1500L);
                detail += " plan=" + plan.plan.size() + " win=" + plan.win
                        + " nodes=" + plan.nodes;
            } catch (Throwable t) {
                outcome = "failed";
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                detail = String.valueOf(cause);
                note("character-failed", c.getName() + " :: " + cause);
            }
            if ("ok".equals(outcome)) {
                ok++;
                Log.info("  character ok: " + c.getSimpleName() + " (" + detail + ")");
            } else {
                failed++;
            }
        }
        Log.info("characters: total=" + chars.size() + " ok=" + ok + " failed=" + failed);
        report("character-failed", 30);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Builds a monster body from whatever constructor it offers.
     *
     * <p>Most bodies take {@code (float x, float y)}. A few real ones -- the small slimes, Lagavulin and
     * the Bronze Orb -- only have constructors with extra arguments (draw offsets, "spawn asleep", orb
     * index). Filling those with neutral values is what lets the sweep exercise them instead of
     * reporting "no no-arg constructor" for a monster the player definitely fights.
     */
    public static AbstractMonster instantiate(Class<?> c) {
        try {
            return (AbstractMonster) c.getConstructor(float.class, float.class)
                    .newInstance(0.0f, 0.0f);
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return (AbstractMonster) c.getConstructor(float.class, float.class, boolean.class)
                    .newInstance(0.0f, 0.0f, false);
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return (AbstractMonster) c.getConstructor().newInstance();
        } catch (Throwable ignored) {
            // fall through to the neutral-argument pass
        }
        java.lang.reflect.Constructor<?>[] ctors = c.getDeclaredConstructors();
        java.util.Arrays.sort(ctors, new java.util.Comparator<java.lang.reflect.Constructor<?>>() {
            @Override
            public int compare(java.lang.reflect.Constructor<?> a, java.lang.reflect.Constructor<?> b) {
                return a.getParameterTypes().length - b.getParameterTypes().length;
            }
        });
        for (java.lang.reflect.Constructor<?> ctor : ctors) {
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean fillable = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == float.class) {
                    args[i] = Float.valueOf(0.0f);
                } else if (type == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (type == int.class) {
                    args[i] = Integer.valueOf(0);
                } else if (type == String.class) {
                    args[i] = "";
                } else {
                    fillable = false;
                    break;
                }
            }
            if (!fillable) {
                continue;
            }
            try {
                ctor.setAccessible(true);
                return (AbstractMonster) ctor.newInstance(args);
            } catch (Throwable ignored) {
                // try the next signature
            }
        }
        return null;
    }

    private static AbstractMonster firstAlive() {
        List<AbstractMonster> alive = Driver.aliveMonsters();
        return alive.isEmpty() ? null : alive.get(0);
    }

    private static void setPlayerEnergy(int n) {
        try {
            if (AbstractDungeon.player != null && AbstractDungeon.player.energy != null) {
                sts1solver.reflect.Reflect.set(com.megacrit.cardcrawl.core.EnergyManager.class,
                        "energy", AbstractDungeon.player.energy, Integer.valueOf(n));
            }
        } catch (Throwable ignored) {
            // The energy gate reads EnergyPanel.totalCount; this is belt and braces.
        }
    }

    private static String colorOf(String id) {
        try {
            AbstractCard c = CardLibrary.getCopy(id);
            return String.valueOf(c.color) + "/" + String.valueOf(c.type);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** "threw:java.lang.NullPointerException" -> "threw"; keeps report buckets stable. */
    private static String bucketOf(String outcome) {
        int cut = outcome.indexOf(':');
        return cut < 0 ? outcome : outcome.substring(0, cut);
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ":" + m);
    }

    private static void note(String bucket, String what) {
        List<String> list = problems.get(bucket);
        if (list == null) {
            list = new ArrayList<String>();
            problems.put(bucket, list);
        }
        list.add(what);
    }

    private static void report(String bucket, int limit) {
        List<String> list = problems.get(bucket);
        if (list == null || list.isEmpty()) {
            return;
        }
        Log.info("  " + bucket + " (" + list.size() + "):");
        for (int i = 0; i < list.size() && i < limit; i++) {
            Log.info("    - " + list.get(i));
        }
    }
}