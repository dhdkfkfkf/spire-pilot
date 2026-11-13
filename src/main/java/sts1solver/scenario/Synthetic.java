package sts1solver.scenario;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.characters.Ironclad;
import com.megacrit.cardcrawl.core.OverlayMenu;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.rooms.MonsterRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a real combat from nothing, inside the running game process.
 *
 * <p>Why this exists: the search's correctness can only be measured against the real engine, and a
 * test that needs a human to walk into a fight cannot run often enough to catch regressions. Every
 * piece below is the game's OWN object and the game's OWN opening sequence -- there is no model of a
 * card here, only the game's cards.
 *
 * <p>The opening sequence mirrors what {@code AbstractDungeon.nextRoomTransition} does when the
 * player walks into a monster room:
 * <ol>
 *   <li>{@code MonsterRoom} already puts the room in {@code COMBAT} phase (its constructor does).</li>
 *   <li>{@code MonsterGroup.init()} rolls the monsters' opening intents (consumes aiRng).</li>
 *   <li>{@code AbstractPlayer.preBattlePrep()} copies the master deck into the draw pile, resets
 *       energy and per-combat counters, and clears the action manager (which sets turn = 1).</li>
 *   <li>{@code AbstractRoom.update()} with {@code waitTimer} elapsed runs the real combat-start
 *       block: energy grant, opening draw of {@code gameHandSize}, relic and power turn-start hooks.</li>
 * </ol>
 */
public final class Synthetic {

    private Synthetic() {
    }

    public interface Step {
        void note(String what, Throwable failure);
    }

    public static void quiet(Step step) {
        step.note("", null);
    }

    /** A step sink that ignores failures (the builder throws its own, more useful exception). */
    public static final class SyntheticStep implements Step {
        public void note(String what, Throwable failure) {
            if (failure != null) {
                Log.warn("synthetic build step '" + what + "' failed: " + failure);
            }
        }
    }

    /** A deck + encounter description, so a scenario can be written down as data. */
    public static final class Spec {
        public long seed = 1L;
        public String[] deck = {
                "Strike_R", "Strike_R", "Strike_R", "Strike_R", "Strike_R",
                "Defend_R", "Defend_R", "Defend_R", "Defend_R", "Bash",
        };
        public String[] monsters = {"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        public String[] relics = {"Burning Blood"};
        public int maxHealth = 80;
        public int ascension = 0;
        /** Fully-qualified AbstractPlayer subclass; null means the Ironclad. */
        public String playerClass;

        /**
         * The {@code PlayerClass} value the character was registered with. Mod characters take
         * {@code (String name, PlayerClass cls)} in their only constructor and only they know which
         * enum constant they belong to, so the caller hands over the enum the game already uses for
         * that character (a registered instance's {@code chosenClass}).
         */
        public AbstractPlayer.PlayerClass playerClassEnum;

        /** When set, the monster group is built by the game's own encounter spawner instead. */
        public String encounterKey;
        /** Keep the character's own starting relics instead of the spec's list. */
        public boolean keepStartingRelics;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("seed=").append(seed).append(" hp=").append(maxHealth)
                    .append(" deck=").append(java.util.Arrays.toString(deck))
                    .append(" monsters=").append(java.util.Arrays.toString(monsters))
                    .append(" relics=").append(java.util.Arrays.toString(relics));
            return sb.toString();
        }
    }

    public static AbstractPlayer build(Spec spec, Step step) {
        // The main-menu state must be empty enough to host a synthetic fight. A live run is refused
        // rather than clobbered: mutating a real run's statics is exactly the class of accident this
        // design exists to prevent.
        // Compared by name, not by enum constant: if a future game version renames the mode, a
        // harmless "not in a run" must not turn into a NoSuchFieldError at class-load time.
        String mode = com.megacrit.cardcrawl.core.CardCrawlGame.mode == null
                ? "null" : com.megacrit.cardcrawl.core.CardCrawlGame.mode.name();
        if (mode.equals("GAMEPLAY") || mode.equals("DUNGEON_TRANSITION")) {
            throw new IllegalStateException("refusing to build a synthetic combat while a run is live");
        }

        // B45: a probe instance must never be heard. Open the music window before any monster exists,
        // because a boss arms its theme from usePreBattleAction.
        sts1solver.mirror.MusicMute.begin(false);

        step.note("dungeon statics", null);
        initDungeonStatics();

        step.note("dungeon scalars", null);
        applyScalars(spec);

        step.note("rng streams", null);
        resetRng(spec);

        step.note("player", null);
        AbstractPlayer player = spec.playerClass == null ? newIronclad("Solver")
                : newPlayer(spec.playerClass, "Solver", spec.playerClassEnum);
        AbstractDungeon.player = player;
        player.maxHealth = spec.maxHealth;
        player.currentHealth = spec.maxHealth;
        player.masterHandSize = 5;
        player.gameHandSize = 5;

        // RUN-LEVEL SHELL. A real combat is inside a run: CardCrawlGame.dungeon is non-null, the top
        // panel exists, and lastCombatMetricKey is a String. Combat code touches all three (the
        // exhaustion path calls dungeon.checkForPactAchievement(), DaggerSpray calls
        // MonsterGroup.shouldFlipVfx(), and the hand-select screen calls topPanel.unhoverHitboxes()).
        // Without the shell those are NullPointerExceptions that say nothing about the card -- the
        // coverage sweep found all three, which is exactly what it is for.
        step.note("run-level statics", null);
        installRunShell(spec);

        step.note("starting relics", null);
        if (!spec.keepStartingRelics) {
            player.relics.clear();
        }
        for (String id : spec.keepStartingRelics ? new String[0] : spec.relics) {
            try {
                AbstractCard unused = null;
                com.megacrit.cardcrawl.relics.AbstractRelic r =
                        com.megacrit.cardcrawl.helpers.RelicLibrary.getRelic(id);
                if (!com.megacrit.cardcrawl.helpers.RelicLibrary.isARelic(id)) {
                    // getRelic() never returns null: an unknown key silently becomes a Circlet, so
                    // a typo in a spec used to look like "the relic is there and does nothing".
                    Log.warn("relic " + id + " is not a known relic id (getRelic would hand out a Circlet)");
                }
                if (r != null) {
                    // instantObtain adds the relic to the player itself; adding it again here would
                    // make every relic fire twice.
                    r.instantObtain(player, player.relics.size(), false);
                }
            } catch (Throwable t) {
                Log.warn("relic " + id + " unavailable: " + t);
            }
        }

        step.note("master deck", null);
        player.masterDeck.clear();
        for (String id : spec.deck) {
            AbstractCard c = CardLibrary.getCard(id);
            if (c == null) {
                throw new IllegalStateException("unknown card id " + id);
            }
            player.masterDeck.addToTop(c.makeCopy());
        }

        step.note("room", null);
        AbstractDungeon.currMapNode = new MapRoomNode(0, 5);
        MonsterRoom room = new MonsterRoom();
        AbstractDungeon.currMapNode.room = room;

        step.note("monsters", null);
        List<AbstractMonster> ms = new ArrayList<AbstractMonster>();
        if (spec.encounterKey != null) {
            // Build the group exactly the way the game's encounter spawner does. This is the
            // difference between "our synthetic group order" and "the real fight": some monsters
            // (GremlinLeader takes monsters.get(0)/(1) as its minion slots) only work when the
            // encounter order is the game's.
            room.monsters = com.megacrit.cardcrawl.helpers.MonsterHelper.getEncounter(
                    spec.encounterKey);
            if (room.monsters == null) {
                throw new IllegalStateException("unknown encounter " + spec.encounterKey);
            }
            ms.addAll(room.monsters.monsters);
        } else {
            for (String cn : spec.monsters) {
                ms.add(newMonster(cn));
            }
            room.monsters = new MonsterGroup(ms.toArray(new AbstractMonster[ms.size()]));
        }

        step.note("action manager", null);
        AbstractDungeon.actionManager = new GameActionManager();

        step.note("overlay", null);
        AbstractDungeon.overlayMenu = new OverlayMenu(player);

        step.note("monster init (rolls opening intents)", null);
        room.monsters.init();
        // The game runs this when an encounter actually spawns: it is where GremlinLeader records its
        // minion slots, where Hexaghost splits its body, and so on. Without it a later summon action
        // dereferences a slot the encounter never filled -- an NPE that used to look like "all search
        // branches died".
        for (int i = 0; i < ms.size(); i++) {
            try {
                ms.get(i).usePreBattleAction();
            } catch (Throwable t) {
                step.note("usePreBattleAction failed for " + ms.get(i).name, t);
            }
        }
        verifyMinionSlots(ms);
        // B44: no synthetic fight may make a sound. A boss's usePreBattleAction is where it arms its
        // music (Hexaghost.java:254 unsilenceBGM + :256 playPrecachedTempBgm), and CardCrawlGame.music
        // is a shared singleton we do NOT own (Statics keeps it as a shell static, i.e. only the
        // reference is restored), so a probe or a live-spec rebuild used to un-silence and play BGM on
        // the real game object. Silence immediately after the pre-battle block and before combat
        // starts; normal play never comes through here.
        silenceBgm(step);

        step.note("preBattlePrep", null);
        player.preBattlePrep();

        step.note("combat-start block", null);
        AbstractRoomCompat.startCombat(room, player);
        // Close the music window: dispose every track this build armed and leave the mixer silent.
        sts1solver.mirror.MusicMute.end();
        return player;
    }

    /**
     * B44/B45: see the call site -- a synthetic fight must never leave music playing.
     *
     * <p>Two tracks matter, and silencing only the first one is not enough: a boss arms its theme
     * through {@code playPrecachedTempBgm()} (Hexaghost.java:256), which plays on the TEMP track and
     * hence silences the main track itself. {@code silenceBGMInstantly()} only walks mainTrack, so a
     * probe used to end up with the boss theme audibly playing on the real MusicMaster object.
     */
    private static void silenceBgm(Synthetic.Step step) {
        try {
            com.megacrit.cardcrawl.audio.MusicMaster music = com.megacrit.cardcrawl.core.CardCrawlGame.music;
            if (music == null) {
                step.note("silence bgm skipped (no MusicMaster yet)", null);
                return;
            }
            music.silenceBGMInstantly();
            music.silenceTempBgmInstantly();
            step.note("bgm silenced (main + temp tracks)", null);
            sts1solver.util.Log.info("probe: bgm silenced (main + temp tracks)");
        } catch (Throwable t) {
            step.note("silence bgm failed", t);
            sts1solver.util.Log.warn("probe: could not silence bgm: " + t);
        }
    }

    /**
     * Refuses to build a group the game itself could never spawn.
     *
     * <p>GremlinLeader records its minion slots in usePreBattleAction() from monsters.get(0) and
     * get(1). If a caller lists the leader first, slot 0 ends up pointing at the leader itself.
     * The leader is never dying, so once the two real gremlins are dead every slot looks occupied:
     * the next SummonGremlinAction finds no free slot, leaves its monster null and throws on the
     * first frame of update(). The search used to lose those branches silently, which made a
     * winnable fight look unsolvable.
     *
     * <p>That state cannot exist in the real game, so it must not be searched either. Callers that
     * want the real fight should set {@code spec.encounterKey = "Gremlin Leader"} and let
     * MonsterHelper spawn the group in the game's own order.
     */
    private static void verifyMinionSlots(List<AbstractMonster> ms) {
        for (int i = 0; i < ms.size(); i++) {
            AbstractMonster m = ms.get(i);
            if (!(m instanceof com.megacrit.cardcrawl.monsters.city.GremlinLeader)) {
                continue;
            }
            AbstractMonster[] slots =
                    ((com.megacrit.cardcrawl.monsters.city.GremlinLeader) m).gremlins;
            if (slots == null) {
                continue;
            }
            for (int s = 0; s < slots.length; s++) {
                if (slots[s] == m) {
                    throw new IllegalStateException("GremlinLeader minion slot " + s
                            + " points at the leader itself: the game can never spawn this fight."
                            + " Build it in encounter order instead, e.g."
                            + " spec.encounterKey = \"Gremlin Leader\".");
                }
            }
        }
    }

    /**
     * Every RNG stream, with a distinct seed per stream.
     *
     * <p>Sharing one seed across streams would correlate the draw pile with the monster intents in a
     * way the real game never does, and a search would then be "deterministic" against a distribution
     * that does not exist.
     */
    private static void resetRng(Spec spec) {
        long s = spec.seed;
        AbstractDungeon.monsterRng = new Random(s * 13 + 1);
        AbstractDungeon.mapRng = new Random(s * 13 + 2);
        AbstractDungeon.eventRng = new Random(s * 13 + 3);
        AbstractDungeon.merchantRng = new Random(s * 13 + 4);
        AbstractDungeon.cardRng = new Random(s * 13 + 5);
        AbstractDungeon.treasureRng = new Random(s * 13 + 6);
        AbstractDungeon.relicRng = new Random(s * 13 + 7);
        AbstractDungeon.potionRng = new Random(s * 13 + 8);
        AbstractDungeon.monsterHpRng = new Random(s * 13 + 9);
        AbstractDungeon.aiRng = new Random(s * 13 + 10);
        AbstractDungeon.shuffleRng = new Random(s * 13 + 11);
        AbstractDungeon.cardRandomRng = new Random(s * 13 + 12);
        AbstractDungeon.miscRng = new Random(s * 13 + 13);
    }

    private static void applyScalars(Spec spec) {
        AbstractDungeon.actNum = 1;
        AbstractDungeon.floorNum = 1;
        // Slay the Spire 1 runs ascension 0..20. The reference solver's scenario builder rejects an
        // out-of-range ascension instead of silently building a normal fight (GeneratedScenarioChecks
        // rejects Ascension > 10 for its own game), so a wrong tier can never be mistaken for a pass.
        if (spec.ascension < 0 || spec.ascension > 20) {
            throw new IllegalArgumentException("ascension out of range: " + spec.ascension);
        }
        AbstractDungeon.ascensionLevel = spec.ascension;
        AbstractDungeon.isAscensionMode = spec.ascension > 0;
        AbstractDungeon.floorY = 0.0f;
    }

    /**
     * Gives the sandbox the part of "being in a run" that combat code dereferences.
     *
     * <p>A dungeon object is created for real rather than faked: the game's own {@code Exordium}
     * constructor initialises the card pools, relic list and scene, and combat code calls methods on
     * {@code CardCrawlGame.dungeon}. If the constructor refuses (a future version, or a run context it
     * needs), the failure is logged and the sweep still runs -- the resulting NPEs are then reported
     * with their cause, not hidden.
     */
    private static void installRunShell(Spec spec) {
        if (com.megacrit.cardcrawl.core.CardCrawlGame.dungeon == null) {
            try {
                if (com.megacrit.cardcrawl.core.Settings.seed == null) {
                    com.megacrit.cardcrawl.core.Settings.seed = Long.valueOf(spec.seed);
                }
                com.megacrit.cardcrawl.core.CardCrawlGame.dungeon =
                        new com.megacrit.cardcrawl.dungeons.Exordium(AbstractDungeon.player,
                                new ArrayList<String>());
                // The dungeon constructor resets these; the scenario's values win afterwards.
                applyScalars(spec);
                resetRng(spec);
            } catch (Throwable t) {
                Log.warn("run shell unavailable (cards that need a dungeon will show it): " + t);
            }
        }
        if (AbstractDungeon.topPanel == null) {
            AbstractDungeon.topPanel = new com.megacrit.cardcrawl.ui.panels.TopPanel();
        }
        if (AbstractDungeon.lastCombatMetricKey == null) {
            AbstractDungeon.lastCombatMetricKey = "";
        }
        dropActTransitionOverlays();
    }

    /**
     * Removes the act-transition text the dungeon constructor queues, because a synthetic battle is
     * not an act transition and that text can crash the real game later.
     *
     * <p>The {@code Exordium} constructor creates the first room, and {@code NeowEvent} queues
     * {@code new LevelTransitionTextOverlayEffect(AbstractDungeon.name, AbstractDungeon.levelNum, true)}
     * for it. At the main menu {@code levelNum} is null -- only a real character-select start
     * (CharacterSelectScreen.updateButtons -> generateSeeds + DungeonTransitionScreen.setAreaName)
     * writes it -- so the overlay holds a null string. It is dormant while the main menu renders
     * (CardCrawlGame renders MainMenuScreen in CHAR_SELECT, not AbstractDungeon), but once the player
     * starts a real run, {@code AbstractDungeon.render} walks the live topLevelEffects, calls
     * {@code FontHelper.renderFontCentered} with that null and the game dies in
     * {@code GlyphLayout.setText} with a NullPointerException at LevelTransitionTextOverlayEffect:57
     * (observed 2026-10-30 16:15, and it is the "选角色开始游戏后闪退" report).
     */
    private static void dropActTransitionOverlays() {
        int dropped = dropActTransitionOverlays(AbstractDungeon.topLevelEffectsQueue)
                + dropActTransitionOverlays(AbstractDungeon.topLevelEffects);
        if (dropped > 0) {
            Log.info("run shell: dropped " + dropped
                    + " act-transition overlay(s) the dungeon constructor queued");
        }
    }

    private static int dropActTransitionOverlays(
            java.util.List<com.megacrit.cardcrawl.vfx.AbstractGameEffect> list) {
        if (list == null) {
            return 0;
        }
        int dropped = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) instanceof com.megacrit.cardcrawl.vfx.scene.LevelTransitionTextOverlayEffect) {
                list.remove(i);
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Creates the few {@code AbstractDungeon} statics that only a run would normally create.
     *
     * <p>At the main menu these are null (they are built by the dungeon constructor), and combat
     * code touches some of them: the four VFX lists, the two selection screens, the overlay menu and
     * the four fade/gradient colors. This is not a model of anything -- each object is the game's own
     * class, constructed the way the game constructs it. A real combat already has them, so nothing
     * here runs in normal play.
     */
    private static void initDungeonStatics() {
        if (AbstractDungeon.effectList == null) {
            AbstractDungeon.effectList = new ArrayList<com.megacrit.cardcrawl.vfx.AbstractGameEffect>();
        }
        if (AbstractDungeon.effectsQueue == null) {
            AbstractDungeon.effectsQueue = new ArrayList<com.megacrit.cardcrawl.vfx.AbstractGameEffect>();
        }
        if (AbstractDungeon.topLevelEffects == null) {
            AbstractDungeon.topLevelEffects = new ArrayList<com.megacrit.cardcrawl.vfx.AbstractGameEffect>();
        }
        if (AbstractDungeon.topLevelEffectsQueue == null) {
            AbstractDungeon.topLevelEffectsQueue = new ArrayList<com.megacrit.cardcrawl.vfx.AbstractGameEffect>();
        }
        if (AbstractDungeon.gridSelectScreen == null) {
            AbstractDungeon.gridSelectScreen =
                    new com.megacrit.cardcrawl.screens.select.GridCardSelectScreen();
        }
        if (AbstractDungeon.handCardSelectScreen == null) {
            AbstractDungeon.handCardSelectScreen =
                    new com.megacrit.cardcrawl.screens.select.HandCardSelectScreen();
        }
        com.badlogic.gdx.graphics.Color zero = new com.badlogic.gdx.graphics.Color(0.0f, 0.0f, 0.0f, 0.0f);
        if (AbstractDungeon.fadeColor == null) {
            AbstractDungeon.fadeColor = zero;
        }
        if (AbstractDungeon.sourceFadeColor == null) {
            AbstractDungeon.sourceFadeColor = zero;
        }
        if (AbstractDungeon.topGradientColor == null) {
            AbstractDungeon.topGradientColor = zero;
        }
        if (AbstractDungeon.botGradientColor == null) {
            AbstractDungeon.botGradientColor = zero;
        }
        AbstractDungeon.isScreenUp = false;
        AbstractDungeon.screen = AbstractDungeon.CurrentScreen.NONE;
        // B151: null, never NONE -- see Driver.closeGridScreen(). closeCurrentScreen() treats a
        // non-null previousScreen as "go back to that screen" and leaves isScreenUp TRUE, which froze
        // the action manager after every answered grid/hand picker (S196/S198/S199 went red on it).
        AbstractDungeon.previousScreen = null;
        // B133: a fresh fight has no pending combat reward. A leftover list would make the game's own
        // closeCurrentScreen (GRID case) send the screen back to COMBAT_REWARD -- a state a real fight
        // never has -- and the sandbox would then freeze the action manager under a reward screen that
        // does not exist.
        if (AbstractDungeon.combatRewardScreen != null && AbstractDungeon.combatRewardScreen.rewards != null) {
            AbstractDungeon.combatRewardScreen.rewards.clear();
        }
    }

    /**
     * Runs the real combat-start block and waits for it to settle.
     *
     * <p>{@code AbstractRoom.update()} is the game's own path: it sees {@code waitTimer} elapsed,
     * queues the energy grant and the opening draw, and runs the start-of-turn hooks. Calling it
     * rather than replicating the list is the point -- replicas drift.
     */
    private static final class AbstractRoomCompat {
        static void startCombat(MonsterRoom room, AbstractPlayer player) {
            com.megacrit.cardcrawl.rooms.AbstractRoom.waitTimer = 0.1f;
            sts1solver.drive.Driver.begin();
            try {
                for (int i = 0; i < 8; i++) {
                    room.update();
                    GameActionManager gam = AbstractDungeon.actionManager;
                    if (!gam.actions.isEmpty() || !gam.cardQueue.isEmpty()) {
                        sts1solver.drive.Driver.drain(4000, 800L);
                    }
                    if (GameActionManager.turn >= 1 && gam.actions.isEmpty()
                            && powerHash(room) != 0L && EnergyPanel.totalCount > 0
                            && player.hand.size() > 0) {
                        break;
                    }
                }
                sts1solver.drive.Driver.drain(4000, 800L);
            } finally {
                sts1solver.drive.Driver.end();
            }
        }

        private static long powerHash(MonsterRoom room) {
            return room.monsters == null ? 0L : room.monsters.monsters.size();
        }
    }

    /**
     * The Ironclad constructor is package-private in the shipped game, so it is reached
     * reflectively. Its signature is stable across 1.0; only its visibility is not.
     */
    /**
     * Builds ANY player class, vanilla or mod, the same way the game does: the class's own
     * constructor (which loads its art, orb, starting relics and potions), then its starter deck.
     *
     * <p>Mod characters are the honest test of this design: nothing here knows what a character is,
     * it just constructs the game's object and mirrors it.
     */
    public static AbstractPlayer newPlayer(String className, String name) {
        return newPlayer(className, name, null);
    }

    /**
     * Same as {@link #newPlayer(String, String)} but also accepts the character's registered
     * {@code PlayerClass}. Mod characters (BaseMod's {@code CustomPlayer}) declare exactly one
     * constructor, {@code (String name, PlayerClass cls)}; without the enum there is no way to build
     * them, and before this overload two real workshop characters (aristocrat, thePirate) could not be
     * probed at all.
     */
    public static AbstractPlayer newPlayer(String className, String name,
            AbstractPlayer.PlayerClass cls) {
        Class<?> c;
        try {
            c = Class.forName(className, true, Synthetic.class.getClassLoader());
        } catch (Throwable t) {
            throw new IllegalStateException("cannot load player class " + className + ": " + t, t);
        }
        Throwable first = null;
        java.lang.reflect.Constructor<?>[] ctors = c.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            java.lang.reflect.Constructor<?> ctor = ctors[i];
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args;
            if (types.length == 1 && types[0] == String.class) {
                args = new Object[]{name};
            } else if (types.length == 0) {
                args = new Object[0];
            } else if (types.length == 2 && types[0] == String.class
                    && types[1] == AbstractPlayer.PlayerClass.class && cls != null) {
                args = new Object[]{name, cls};
            } else {
                continue;
            }
            try {
                ctor.setAccessible(true);
                AbstractPlayer player = (AbstractPlayer) ctor.newInstance(args);
                try {
                    player.initializeStarterDeck();
                } catch (Throwable t) {
                    Log.warn("starter deck for " + className + " failed: " + t);
                }
                // This player is a sandbox object: its save target must never be the real one (B25).
                sts1solver.mirror.PlayerPrefs.muteForSession(player);
                Log.info("player " + className + " built with ctor(" + signature(types) + ")"
                        + (cls == null ? "" : " cls=" + cls.name()));
                return player;
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (first == null) {
                    first = cause;
                }
            }
        }
        throw new IllegalStateException("cannot construct player " + className + " (ctors="
                + ctors.length + "): " + first, first);
    }

    private static String signature(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }

    public static AbstractPlayer newIronclad(String name) {
        Throwable first = null;
        // Two attempts: the constructor loads textures and the spine skeleton, and one build in the
        // harness saw a single transient failure ("cannot construct Ironclad") that did not recur.
        // A retry keeps that from turning the suite red without hiding a real break: the second
        // failure is thrown with the first cause attached.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                java.lang.reflect.Constructor<Ironclad> c =
                        Ironclad.class.getDeclaredConstructor(String.class);
                c.setAccessible(true);
                Ironclad player = c.newInstance(name);
                sts1solver.mirror.PlayerPrefs.muteForSession(player);
                return player;
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (first == null) {
                    first = cause;
                }
                Log.warn("Ironclad construction attempt " + (attempt + 1) + " failed: " + cause);
            }
        }
        Log.error("cannot construct Ironclad", first);
        throw new IllegalStateException("cannot construct Ironclad: " + first, first);
    }

    /** Instantiates a monster by class name with its (float,float) constructor. */
    public static AbstractMonster newMonster(String className) {
        Class<?> c;
        try {
            c = Class.forName(className);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create monster " + className, t);
        }
        try {
            return (AbstractMonster) c.getConstructor(float.class, float.class)
                    .newInstance(0.0f, 0.0f);
        } catch (Throwable ignored) {
            // bosses often have a no-arg constructor
        }
        try {
            // The small slimes take an extra "amount" argument (poison for the acid one).
            return (AbstractMonster) c.getConstructor(float.class, float.class, int.class)
                    .newInstance(0.0f, 0.0f, 0);
        } catch (Throwable ignored) {
            // fall through to the no-arg forms below
        }
        try {
            return (AbstractMonster) c.getConstructor().newInstance();
        } catch (Throwable ignored) {
            // Some monsters only exist in one mode: Lagavulin(boolean) is the elite/sleeping form.
            // Construct the ordinary variant rather than failing the whole scenario.
            try {
                return (AbstractMonster) c.getConstructor(boolean.class).newInstance(false);
            } catch (Throwable t2) {
                throw new IllegalStateException("cannot create monster " + className, t2);
            }
        }
    }
}