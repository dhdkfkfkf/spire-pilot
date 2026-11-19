package sts1solver;

import basemod.BaseMod;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import sts1solver.probe.Probe;
import sts1solver.util.Log;

import java.io.File;

/**
 * Mod entry point.
 *
 * <p>Two modes share one binary:
 *
 * <ul>
 *   <li><b>Interactive</b>: the solver panel in the top-right corner, driven entirely by the mouse --
 *       计算 / 自动 开/关 / 半自动-全自动 / 秒数 / 节点 / 节奏 / 药水 / 局外收益 / 收起 / 隐藏
 *       (see {@link sts1solver.ui.PanelLayout}). That panel is the only way to operate the mod: the
 *       F5–F12 key branches were deleted on 2026-11-13 (m03455), so no key press can start a search,
 *       change the budget or run the probe suite. Arrow keys still scroll the route strip.</li>
 *   <li><b>Harness</b>: if {@code autotest.request} exists in the mod's own directory at startup,
 *       the self-check runs automatically at the main menu and the process exits when it finishes.
 *       That is what makes "build → launch → read result" a command instead of a manual session,
 *       and it is why the suite can be run after every change.</li>
 * </ul>
 */
@SpireInitializer
public final class SolverMod implements PostInitializeSubscriber, PostUpdateSubscriber {

    private static final int CHECK_FRAMES = 30;
    /** A frame that spends more than this inside our own hook is worth a log line (B52). */
    private static final long SLOW_FRAME_US = 25000L;

    private static long lastSlowLogAt;
    private int frames;
    private boolean autoTestStarted;

    /**
     * Debug surface (the autotest request) is opt-in per machine. The F5–F12 keys are gone (m03455).
     *
     * <p>A request file makes the mod fight a synthetic battle at the main menu, which can freeze a
     * player's game, so the marker file `debug.keys` (written by tools/run.ps1, never present on a
     * player's install) is what separates "the author measuring" from "someone playing".
     */
    private static boolean debugSurface;
    private boolean autoTestDisabled;

    public static void initialize() {
        BaseMod.subscribe(new SolverMod());
    }

    public static String dir() {
        return System.getProperty("user.home") + File.separator + "spire-pilot";
    }

    /** True when the author's debug marker exists; the bug-report bundle records it. */
    public static boolean debugSurface() {
        return debugSurface;
    }

    // ---- built-in texture guard (1.0.5) ------------------------------------------------------
    /**
     * The texture guard patches are applied by ModTheSpire when it scans this jar, not by us: this
     * only proves each patch class actually loaded, so "the game crashed on an orb again" can be
     * told apart from "the patch never made it into the jar".
     */
    private static void logGuardPatches() {
        String[] names = {
                "sts1solver.guard.patches.SpriteBatchSwitchPatch"
        };
        int ok = 0;
        for (int i = 0; i < names.length; i++) {
            try {
                Class.forName(names[i]);
                ++ok;
            } catch (Throwable t) {
                Log.warn("guard: " + names[i] + " did not load: " + t);
            }
        }
        Log.info("guard: loaded " + ok + "/" + names.length + " texture patches (global switchTexture)");
    }

    /**
     * Logs the content surface once at startup: mod combat hooks and known mod profiles.
     *
     * <p>Every mod is accepted (m11229), so this line is information, not a gate: it says what is
     * listening, which is what a bug report needs, and it is checkable from the log alone.
     */
    private static void logContentSurface() {
        try {
            int count = sts1solver.modsupport.ModHooks.combatHookCount();
            java.util.List<String> hooks = sts1solver.modsupport.ModHooks.combatHookNames(8);
            Log.info("content surface: " + count + " mod combat hook(s)"
                    + (count == 0 ? "" : " " + hooks));
            java.util.List<sts1solver.modsupport.ModProfile> profiles =
                    sts1solver.modsupport.ModProfile.all();
            Log.info("content surface: " + profiles.size() + " integrated mod profile(s)");
            java.util.List<String> hookClasses =
                    sts1solver.modsupport.ModHooks.combatHookClasses();
            Log.info("content surface: accepted (no whitelist); hook profiles="
                    + sts1solver.modsupport.ModProfile.covering(hookClasses));
        } catch (Throwable t) {
            Log.warn("content surface: cannot inspect: " + t);
        }
    }

    public static String requestPath() {
        return sts1solver.util.Log.pathFor("autotest.request");
    }

    @Override
    public void receivePostInitialize() {
        Log.open();
        Log.info("=== spire-pilot loaded (mod dir " + dir() + ") ===");
        logGuardPatches();
        sts1solver.ui.Budget.migrateToUnlimitedBudget();
        debugSurface = new File(dir(), DEBUG_MARKER).exists();
        Log.info("debug surface: " + (debugSurface ? "ON (" + DEBUG_MARKER + " present)" : "off"));
        cleanStaleRequests();
        logContentSurface();
        // tools/run.ps1 writes the request BEFORE launching us, so a probe instance can mute itself
        // before the main menu ever plays. A normal game session has no request file and is untouched.
        if (debugSurface && new File(requestPath()).exists()) {
            sts1solver.util.SilentInstance.arm("probe request present at startup");
        }
        sts1solver.ui.Hud.install();
        // The Mods screen's Config button (B197): default mode vs 兼容实验模式, with both costs
        // written on the panel. A failed registration only loses the screen, never the solver.
        sts1solver.ui.ModConfig.install();
        // B47: if the render thread ever stops returning, leave a thread dump behind instead of a
        // silent freeze. Probe instances run blocking suites on that thread on purpose, so they are
        // disarmed (see the request block below).
        sts1solver.util.Watchdog.start();
        if (debugSurface && new File(requestPath()).exists()) {
            sts1solver.util.Watchdog.setArmed(false);
            // A probe suite runs inside a synthetic fight that is not GAMEPLAY: P2-27 must not
            // gate the frame loop away from it.
            sts1solver.ui.PhaseGate.force(true);
        }
    }

    @Override
    public void receivePostUpdate() {
        frames++;
        // One volatile write per frame: the hang watchdog uses this as its heartbeat (B47).
        sts1solver.util.Watchdog.beat();
        // A probe instance stays silent for its whole life, including anything a simulation arms.
        sts1solver.util.SilentInstance.tick();
        // A pool worker owns the frame loop: it pulls queued requests and, once told to stop,
        // exits the process. Nothing interactive runs in that instance, so it returns here.
        if (sts1solver.worker.WorkerPool.active()) {
            sts1solver.worker.WorkerPool.tick();
            return;
        }
        // P2-27: outside a live fight there is nothing for the hook to poll, lay out or search. The
        // dumps that came back from "reading a save is slow" showed the game building its own main
        // menu; our per-frame overlay work was riding along on those stalled frames. Step aside
        // instead (Hud already refuses to draw outside GAMEPLAY).
        if (!sts1solver.ui.PhaseGate.hot()) {
            sts1solver.ui.PhaseGate.note(false);
            // Never leave a sandbox installed across a menu: the copies hold the static screen.
            if (sts1solver.mirror.World.anyInstalled()) {
                sts1solver.mirror.World.forceRestore();
            }
            return;
        }
        sts1solver.ui.PhaseGate.note(true);
        if (frames == 1) {
            // A one-line liveness marker. Without it, "nothing happened" cannot be told apart from
            // "the update hook never ran", which is exactly the ambiguity that cost a debug round.
            Log.info("postUpdate alive");
        }
        // Arrow-key scrolling is sampled EVERY frame: isKeyJustPressed() is true only during the
        // frame the key went down, so a 30-frame cadence would drop almost every press. Only the
        // request-FILE check (disk I/O) stays on the slow cadence.
        checkPanelKeys();
        // B52 (m15336-3): our own per-frame cost, measured. "快速SL 变卡了" cannot be answered from a
        // log that never says how long our hook took, and a 30ms hook is indistinguishable from a
        // stuttering game to the player.
        long hookStartNs = System.nanoTime();
        // The panel polls hover + clicks every frame: a button that reacts only on a slow cadence
        // feels broken, and the hover highlight has to follow the mouse.
        sts1solver.ui.Panel.update();
        long panelMicros = (System.nanoTime() - hookStartNs) / 1000L;
        long tickStartNs = System.nanoTime();
        sts1solver.Gameplay.tick();
        long tickMicros = (System.nanoTime() - tickStartNs) / 1000L;
        long hookMicros = (System.nanoTime() - hookStartNs) / 1000L;
        if (hookMicros > SLOW_FRAME_US) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSlowLogAt > 1000L) {
                lastSlowLogAt = nowMs;
                // B54: a slow hook is only actionable if the log says whether the machine was busy
                // collecting garbage at that moment, and where the game was.
                // B170: the hook is capped at ~40ms of search per frame, so a half-second frame is
                // either a phase that ignores the cap or work outside the search entirely. The split
                // says which one, every time, without a second reproduced round.
                Log.warn("slow frame: our hook took " + (hookMicros / 1000L) + "ms (panel="
                        + (panelMicros / 1000L) + " tick=" + (tickMicros / 1000L)
                        + " [search=" + (sts1solver.Gameplay.lastAdvanceUs / 1000L)
                        + " repair=" + (sts1solver.Gameplay.lastRepairUs / 1000L)
                        + " preview=" + (sts1solver.Gameplay.lastPreviewUs / 1000L)
                        + " hud=" + (sts1solver.Gameplay.lastHudUs / 1000L) + "]) | "
                        + sts1solver.Gameplay.watchdogState()
                        + " | " + sts1solver.util.Watchdog.phase()
                        + " | " + sts1solver.util.Watchdog.gcLine());
            }
        }
        if (frames % CHECK_FRAMES != 0) {
            return;
        }
        if (!debugSurface && new File(requestPath()).exists()) {
            // A player never has a request; one can only appear here if a probe was left behind on a
            // machine that is no longer the author's. Ignore it AND remove it, so it cannot sit in the
            // log for every later launch.
            Log.info("autotest: request ignored and deleted (" + DEBUG_MARKER + " absent)");
            deleteRequest();
            return;
        }
        if (!autoTestDisabled && !autoTestStarted && new File(requestPath()).exists()) {
            autoTestStarted = true;
            // A probe blocks the render thread for minutes by design; that is not a hang.
            sts1solver.util.Watchdog.setArmed(false);
            String mode = readMode();
            sts1solver.util.SilentInstance.arm("probe request (mode=" + mode + ")");
            Log.info("AUTOTEST requested (mode=" + mode + "); starting at main menu");
            if ("coverage".equals(mode)) {
                sts1solver.coverage.Coverage.run(requestPath());
            } else if ("audit-summon".equals(mode)) {
                sts1solver.probe.AuditProbe.run("summon");
            } else if ("audit-narrow-strong".equals(mode)) {
                sts1solver.probe.AuditProbe.run("narrow-strong");
            } else if ("audit-narrow".equals(mode)) {
                sts1solver.probe.AuditProbe.run("narrow");
            } else if ("audit-awakened".equals(mode)) {
                sts1solver.probe.AuditProbe.run("awakened");
            } else if ("audit-collector".equals(mode)) {
                sts1solver.probe.AuditProbe.run("collector");
            } else if ("audit-automaton".equals(mode)) {
                sts1solver.probe.AuditProbe.run("automaton");
            } else if ("bossa20".equals(mode)) {
                sts1solver.probe.BossProbe.run();
            } else if ("killcheck".equals(mode)) {
                sts1solver.probe.KillProbe.run();
            } else if ("bench-prep".equals(mode)) {
                sts1solver.worker.Worker.benchPrep(6000L);
            } else if ("worker".equals(mode)) {
                sts1solver.worker.Worker.run(new java.io.File(requestPath()));
            } else if ("worker-pool".equals(mode)) {
                sts1solver.worker.WorkerPool.start();
            } else if ("growthscan".equals(mode)) {
                sts1solver.search.GrowthScan.run();
            } else if ("genstatics".equals(mode)) {
                sts1solver.mirror.StaticSweep.run();
            } else if ("cost-normal".equals(mode)) {
                sts1solver.probe.CostProbe.run("normal");
            } else if ("cost-awakened".equals(mode)) {
                sts1solver.probe.CostProbe.run("awakened");
            } else if ("cost-collector".equals(mode)) {
                sts1solver.probe.CostProbe.run("collector");
            } else if ("cost-automaton".equals(mode)) {
                sts1solver.probe.CostProbe.run("automaton");
            } else if ("cost-slime".equals(mode)) {
                sts1solver.probe.CostProbe.run("slime");
            } else if ("cost-all".equals(mode)) {
                sts1solver.probe.CostProbe.run("all");
            } else if ("cost-rev".equals(mode)) {
                sts1solver.probe.CostProbe.run("reverse");
            } else if ("cost-fork".equals(mode)) {
                sts1solver.probe.CostProbe.run("fork");
            } else if ("cost-long".equals(mode)) {
                sts1solver.probe.CostProbe.run("long");
            } else if ("cost-repeat".equals(mode)) {
                sts1solver.probe.CostProbe.run("repeat");
            } else if ("longrun".equals(mode)) {
                sts1solver.probe.LongRun.run(180000L, 10000L, true);
            } else if ("rollout".equals(mode)) {
                sts1solver.probe.RolloutExperiment.run();
            } else if ("modchar".equals(mode)) {
                // Does the solver work for a character it has never heard of? Run with -AllMods so the
                // player's own workshop characters are loaded.
                sts1solver.probe.ModCharProbe.run(4000L);
            } else if ("bide".equals(mode)) {
                // Why a plan that pressed End Turn from inside a mod CARD cannot be replayed (m02901).
                sts1solver.probe.BideProbe.run();
            } else if ("tang".equals(mode)) {
                // B124 (m03444): does the sandbox run 唐 for real, with the live RNG stream?
                sts1solver.probe.TangProbe.run();
            } else if ("lockstep".equals(mode)) {
                // B124 (m03444): does the sandbox consume the same random numbers as the live game?
                sts1solver.probe.LockstepProbe.run();
            } else if ("trance".equals(mode)) {
                // B122 (m02740): 昏睡 must end the next turn by itself; the sandbox did not.
                sts1solver.probe.TranceProbe.run();
            } else if ("chain".equals(mode)) {
                // B126 (m04653): 唐 auto-plays 昏睡 -> consecutive skipped turns. Does the sandbox
                // chain the skips and still run the turn start (draw + post-draw powers)?
                sts1solver.probe.TranceChainProbe.run();
            } else if ("hc".equals(mode)) {
                // B138 (m08865): 头追 opens two grid screens that ask the SAME question. The live
                // fight moved one card of each pile; the sandbox digest recorded no move at all.
                sts1solver.probe.HeadChaseProbe.run();
            } else if ("snecko".equals(mode)) {
                // B132 (m06536): with Snecko Eye the same cards rolled different costs in the
                // sandbox and in the live game. Does the mirrored cardRandomRng feed the same rolls?
                sts1solver.probe.SneckoProbe.run();
            } else if ("screenrng".equals(mode)) {
                // B133 (m06893): a potion that opens a card screen must roll the same numbers in the
                // sandbox as in the live game.
                sts1solver.probe.ScreenRngProbe.run();
            } else if ("over".equals(mode)) {
                // B125 (m03996): what does a decided fight still offer, and where do the extra
                // end-turn steps come from?
                sts1solver.probe.OverProbe.run();
            } else if ("over2".equals(mode)) {
                // B125 (m03996): the Looter + SpikeSlime_M fight -- does every plan end turn
                // really start a new turn?
                sts1solver.probe.OverProbe2.run();
            } else if ("pilepick".equals(mode)) {
                sts1solver.probe.PilePickProbe.run();
            } else if ("actionaudit".equals(mode)) {
                sts1solver.probe.ActionAudit.run();
            } else if ("hexa".equals(mode)) {
                sts1solver.probe.HexaProbe.run(Long.getLong("sts1solver.hexaBudgetMs", 120000L));
            } else if ("audit".equals(mode)) {
                sts1solver.probe.AuditProbe.run("all");
            } else if ("defect".equals(mode)) {
                sts1solver.probe.DefectProbe.run();
            } else if ("silent".equals(mode)) {
                sts1solver.probe.SilentProbe.run();
            } else if ("watcher".equals(mode)) {
                sts1solver.probe.WatcherProbe.run();
            } else if ("mechanics-deep".equals(mode)) {
                sts1solver.probe.MechanicProbe.run(30000L);
            } else if ("mech-diagnose".equals(mode)) {
                sts1solver.probe.MechDiagnose.run();
            } else if ("gm-diagnose".equals(mode)) {
                sts1solver.probe.GremlinDiagnose.run();
            } else if ("gang".equals(mode)) {
                sts1solver.probe.GangDiagnose.run();
            } else if ("namecheck".equals(mode)) {
                sts1solver.probe.NameProbe.run();
            } else if ("namecheck-pollute".equals(mode)) {
                sts1solver.probe.NameProbe.runPolluted();
            } else if ("mechanics".equals(mode)) {
                sts1solver.probe.MechanicProbe.run();
            } else if ("monsters".equals(mode)) {
                sts1solver.probe.MonsterProbe.run();
            } else if ("split".equals(mode)) {
                sts1solver.probe.SplitProbe.run();
            } else if ("perf".equals(mode)) {
                sts1solver.probe.PerfProbe.run();
            } else if ("choice".equals(mode)) {
                sts1solver.probe.SelectionProbe.run();
            } else {
                // m17581: "core" is the short suite (only the gates that guard a bug the player met).
                if ("core".equals(mode)) {
                    Probe.setCoreOnly(true);
                } else if (mode.startsWith("gates:")) {
                    // m20027: tools/run.ps1 -Gates S119,S116 -- only the gates a change touched.
                    Probe.setCoreOnly(true);
                    Probe.setOnlyGates(mode.substring("gates:".length()));
                }
                Probe.autoTest();
            }
            // ONE SHOT. The request file is a trigger, not a mode flag: it is deleted as soon as the
            // mode has been dispatched. Leaving it behind made every later launch run a synthetic
            // battle at the main menu (2026-10-30: a stale "killcheck" request fought slimes before
            // the player had picked a character, and that leftover fight sat in the main menu).
            deleteRequest();
        }
    }

    /** Removes the one-shot request file; failure is not fatal (run.ps1 removes it too). */
    private static void deleteRequest() {
        try {
            java.io.File req = new java.io.File(requestPath());
            if (req.exists() && !req.delete()) {
                Log.warn("autotest: could not delete " + req.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.warn("autotest: could not delete the request file: " + t);
        }
    }

    /**
     * Deletes request files that no live launcher owns any more.
     *
     * <p>A launcher (run.ps1, worker-pool.ps1) writes the request seconds before starting the JVM,
     * so a request older than {@link #STALE_REQUEST_MS} was left over by an earlier session and must
     * never fire. Five minutes is far longer than any launcher gap and far shorter than a session.
     */
    private static final long STALE_REQUEST_MS = 300000L;

    /** The marker a machine must carry before any debug surface (probe keys, request file) is live. */
    private static final String DEBUG_MARKER = "debug.keys";

    private static void cleanStaleRequests() {
        try {
            java.io.File dir = new java.io.File(dir());
            java.io.File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < files.length; i++) {
                String name = files[i].getName();
                if (!name.startsWith("autotest") || !name.endsWith(".request")) {
                    continue;
                }
                long age = now - files[i].lastModified();
                if (age <= STALE_REQUEST_MS) {
                    continue;
                }
                if (files[i].delete()) {
                    Log.info("autotest: deleted the stale request " + name
                            + " (age " + (age / 1000L) + "s; no launcher owns it)");
                }
            }
        } catch (Throwable t) {
            Log.warn("autotest: stale request cleanup failed: " + t);
        }
    }

    /**
     * Panel scrolling keys, sampled every frame. No operation keys remain (m03455): a key may move
     * the route strip, never start or stop a search.
     */
    private void checkPanelKeys() {
        // Route strip scrolling: the panel holds the whole plan, so the later turns must be reachable
        // both with the wheel (handled in Panel.update) and with the keyboard for a laptop touchpad.
        if (sts1solver.ui.Hud.visible() && !sts1solver.ui.Panel.collapsed()) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
                sts1solver.ui.Panel.scrollRows(-1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
                sts1solver.ui.Panel.scrollRows(1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP)) {
                sts1solver.ui.Panel.scrollRows(-5);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) {
                sts1solver.ui.Panel.scrollRows(5);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.HOME)) {
                sts1solver.ui.Panel.scrollToCurrent();
            }
        }
        // F5–F12 were removed on 2026-11-13 (m03455): every one of them is a panel button now, and
        // a stray key press must not start a search, change the budget or run the probe suite.
        // The probe suite is still reachable OUTSIDE the game, through tools/run.ps1.
    }

    /** The request file's first line selects the suite; empty means the default self-check. */
    private static String readMode() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(requestPath()), "UTF-8"));
            try {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    public static void disableAutoTest() {
        // Left as a switch for a future interactive session that must not re-enter the harness.
        SolverMod m = null;
        if (m != null) {
            m.autoTestDisabled = true;
        }
    }
}