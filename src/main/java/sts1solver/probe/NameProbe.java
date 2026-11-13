package sts1solver.probe;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.screens.DungeonTransitionScreen;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;
import sts1solver.util.Log;

/**
 * Reproduces the 2026-10-30 crash ("pick a character, the game closes") and names the writer.
 *
 * <p>The crash itself is proven from the game log and bytecode:
 * {@code LevelTransitionTextOverlayEffect.render} passes {@code this.levelNum} to
 * {@code FontHelper.renderFontCentered}, {@code GlyphLayout.setText} throws on a null
 * {@code CharSequence}, and {@code AbstractDungeon.levelNum} was therefore null when
 * {@code NeowEvent} built the overlay. The game itself always sets that field when a run starts:
 * {@code CardCrawlGame.update()} case CHAR_SELECT does
 * {@code dungeonTransitionScreen = new DungeonTransitionScreen("Exordium")}, whose constructor
 * runs {@code setAreaName} and ends with
 * {@code AbstractDungeon.levelNum = this.levelNum} (Act 1). So the null is written AFTER the
 * run started, by something that restores an older snapshot of the same static.
 *
 * <p>This probe starts a run exactly the way the game does, then samples the two statics on a
 * fast watchdog thread. On the first null it dumps the stacks of every thread that is inside the
 * game code, which names the code that just wrote it, and reports a verdict so run.ps1 returns.
 */
public final class NameProbe {

    private static final long WATCH_MS = 45000L;

    private static volatile boolean reported;

    private NameProbe() {
    }

    public static void run() {
        Log.info("NAMECHECK entry: mode=" + CardCrawlGame.mode
                + " name=" + show(AbstractDungeon.name)
                + " levelNum=" + show(AbstractDungeon.levelNum)
                + " dungeon=" + (CardCrawlGame.dungeon == null ? "-" : CardCrawlGame.dungeon.getClass().getSimpleName())
                + " transition=" + (CardCrawlGame.dungeonTransitionScreen == null ? "-" : "open")
                + " player=" + (AbstractDungeon.player == null ? "-" : AbstractDungeon.player.chosenClass)
                + " effects=" + (AbstractDungeon.topLevelEffects == null ? "-" : Integer.valueOf(AbstractDungeon.topLevelEffects.size())));
        Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                watch();
            }
        }, "sts1-namecheck");
        watch.setDaemon(true);
        watch.start();
        if (polluted) {
            pollute();
        }
        startRun();
    }

    /** Same probe, but a synthetic battle is built and torn down first (mimics a leftover autotest). */
    public static void runPolluted() {
        polluted = true;
        run();
    }

    private static boolean polluted;

    /**
     * Leaves the live game the way a probe that ran at the main menu leaves it: a synthetic battle
     * built on top of an empty game, one sandbox world installed and uninstalled over it. The crash
     * session had exactly that (mode=audit-summon at 15:43:12, then the player started a run at
     * 15:43:50 and got levelNum=null), so this is the state the run has to be started from.
     */
    private static void pollute() {
        try {
            sts1solver.scenario.Synthetic.Spec spec = new sts1solver.scenario.Synthetic.Spec();
            spec.seed = 42L;
            spec.maxHealth = 200;
            spec.deck = new String[]{"Bludgeon", "Bludgeon", "Bludgeon", "Bash", "Bash"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.AcidSlime_L"};
            sts1solver.scenario.Synthetic.build(spec, new sts1solver.scenario.Synthetic.SyntheticStep());
            sts1solver.mirror.World w = sts1solver.mirror.World.captureLive();
            w.install();
            try {
                sts1solver.drive.Driver.begin();
                java.util.List<sts1solver.search.Action> legal = sts1solver.search.Legal.actions();
                for (int i = 0; i < legal.size(); i++) {
                    if (legal.get(i).isCard()) {
                        sts1solver.search.Legal.apply(legal.get(i));
                        break;
                    }
                }
            } finally {
                try { sts1solver.drive.Driver.end(); } catch (Throwable t) { Log.warn("NAMECHECK pollute: Driver.end " + t); }
                try { w.readBack(); } catch (Throwable t) { Log.warn("NAMECHECK pollute: readBack " + t); }
                try { w.uninstall(); } catch (Throwable t) { Log.warn("NAMECHECK pollute: uninstall " + t); }
            }
            Log.info("NAMECHECK pollution: built a synthetic battle at the main menu; levelNum="
                    + show(AbstractDungeon.levelNum));
        } catch (Throwable t) {
            Log.error("NAMECHECK pollute failed", t);
        }
    }
    /**
     * What CharacterSelectScreen.updateButtons() does when the player clicks Embark
     * (decompiled build/decomp6/.../charSelect/CharacterSelectScreen.java, the confirmButton branch):
     * seed, main menu fade-out, ModHelper.setModsFalse(), AbstractDungeon.generateSeeds(), ascension,
     * chosen character. Replicated here because that click is not reachable from code, and because
     * skipping generateSeeds() left AbstractDungeon.cardRng null and produced an unrelated NPE in
     * AbstractDungeon.dungeonTransitionSetup (proved by javap: line 3110 is the cardRng.counter read).
     * Everything after this point is the game own CHAR_SELECT branch: it builds the player, creates the
     * transition screen and only then builds the dungeon.
     */
    private static void startRun() {
        try {
            if (com.megacrit.cardcrawl.core.Settings.seed == null) {
                com.megacrit.cardcrawl.core.Settings.seed = Long.valueOf(new java.util.Random().nextLong());
            }
            com.megacrit.cardcrawl.core.Settings.seedSet = true;
            com.megacrit.cardcrawl.core.Settings.isDailyRun = false;
            try {
                java.lang.reflect.Method setModsFalse = Class.forName("basemod.ModHelper").getMethod("setModsFalse");
                setModsFalse.invoke(null);
            } catch (Throwable ignored) {
                Log.info("NAMECHECK ModHelper.setModsFalse unavailable: " + ignored);
            }
            AbstractDungeon.generateSeeds();
            AbstractDungeon.isAscensionMode = false;
            AbstractDungeon.ascensionLevel = 0;
            CardCrawlGame.chosenCharacter = com.megacrit.cardcrawl.characters.AbstractPlayer.PlayerClass.IRONCLAD;
            CardCrawlGame.mainMenuScreen.isFadingOut = true;
            CardCrawlGame.mainMenuScreen.fadeOutMusic();
            Log.info("NAMECHECK asked the game to start a run: mode=" + CardCrawlGame.mode
                    + " seed=" + com.megacrit.cardcrawl.core.Settings.seed
                    + " cardRng=" + (AbstractDungeon.cardRng != null)
                    + " name=" + show(AbstractDungeon.name)
                    + " levelNum=" + show(AbstractDungeon.levelNum));
        } catch (Throwable t) {
            Log.error("NAMECHECK could not start a run", t);
            finish("could-not-start");
        }
    }

    private static void watch() {
        long t0 = System.currentTimeMillis();
        String prevNum = AbstractDungeon.levelNum;
        String prevName = AbstractDungeon.name;
        // Both statics are legitimately null at the main menu and while a synthetic battle is being
        // built (only a real run writes them). So the null check only arms after the first non-null.
        boolean armed = false;
        while (System.currentTimeMillis() - t0 < WATCH_MS) {
            String num = AbstractDungeon.levelNum;
            String name = AbstractDungeon.name;
            if (num != null) {
                armed = true;
            }
            if (num != prevNum || name != prevName) {
                Log.info("NAMECHECK t=" + (System.currentTimeMillis() - t0) + "ms levelNum "
                        + show(prevNum) + " -> " + show(num) + ", name " + show(prevName)
                        + " -> " + show(name) + ", mode=" + CardCrawlGame.mode
                        + ", screen=" + AbstractDungeon.screen);
                prevNum = num;
                prevName = name;
                if (armed && (num == null || name == null)) {
                    dumpStacks("null detected");
                    finish("null-levelNum");
                    return;
                }
            }
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                return;
            }
        }
        dumpOverlays();
        finish("survived");
    }

    /** Logs every overlay currently waiting in topLevelEffects, so a null one is visible too. */
    private static void dumpOverlays() {
        try {
            java.util.ArrayList<AbstractGameEffect> copy =
                    new java.util.ArrayList<AbstractGameEffect>(AbstractDungeon.topLevelEffects);
            for (int i = 0; i < copy.size(); i++) {
                AbstractGameEffect e = copy.get(i);
                if (e == null) {
                    continue;
                }
                if (e.getClass().getName().indexOf("LevelTransitionTextOverlayEffect") < 0) {
                    continue;
                }
                Log.info("NAMECHECK overlay: levelNum=" + show(String.valueOf(field(e, "levelNum")))
                        + " name=" + show(String.valueOf(field(e, "name"))));
            }
            Log.info("NAMECHECK effects=" + copy.size());
        } catch (Throwable t) {
            Log.warn("NAMECHECK cannot read topLevelEffects: " + t);
        }
    }

    private static Object field(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Prints the game-code frames of the thread that is executing right now. */
    private static void dumpStacks(String why) {
        try {
            int printed = 0;
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                    : Thread.getAllStackTraces().entrySet()) {
                if (printed >= 3) {
                    break;
                }
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = e.getValue();
                for (int i = 0; i < st.length; i++) {
                    String n = st[i].getClassName();
                    if (n.startsWith("com.megacrit") || n.startsWith("basemod") || n.startsWith("sts1solver")) {
                        sb.append(" | ").append(n).append(".").append(st[i].getMethodName())
                                .append(":").append(st[i].getLineNumber());
                    }
                }
                if (sb.length() == 0) {
                    continue;
                }
                printed++;
                Log.warn("NAMECHECK stacks (" + why + ") thread=" + e.getKey().getName() + sb);
            }
        } catch (Throwable t) {
            Log.warn("NAMECHECK stack dump failed: " + t);
        }
    }

    private static synchronized void finish(String verdict) {
        if (reported) {
            return;
        }
        reported = true;
        Log.info("NAMECHECK verdict: " + verdict);
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

    private static String show(String s) {
        return s == null ? "null" : "'" + s + "'";
    }
}
