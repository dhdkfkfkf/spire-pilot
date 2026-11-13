package sts1solver.drive;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.GameActionManager;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sts1solver.reflect.Reflect;

/**
 * B184 (user m01136, a rewrite of the reverted B183): the frame length for the few actions that do
 * their work in STEPS instead of all at once.
 *
 * <p>B181 (user m24423/m24488) set every sandbox frame to {@link Driver}'s 0.05 s because an action
 * is free to change combat state in a later update than its first one -- Hexaghost's
 * {@code BurnIncreaseAction} upgrades the existing burns at {@code duration == 3.0} and creates three
 * new ones only once {@code duration < 1.5}. A 20 s frame skipped that window, so the sandbox fought
 * three burns short. The cost of the honest 0.05 s frame is that EVERY action now needs
 * {@code duration / 0.05} frames: a 3 s action costs 60 updates instead of 1.
 *
 * <p>B183 tried to buy those frames back for all 372 action classes at once (any class without a
 * known window got a 20 s frame) and the fight became unresponsive during a search (user m00787).
 * Rewritten conservatively: only classes whose whole job is "tick the duration down, do one thing
 * when it crosses a point, reset it" get a fast frame; every other class -- including every class we
 * have never looked at -- keeps the 0.05 s frame. Six classes are on that list; a miss costs speed,
 * never state.
 *
 * <p>Rules, all of them readable out of the class bodies:
 * <ul>
 *   <li>the first {@link #FRESH_FRAMES} frames of an action always run at 0.05 s, so a first-frame
 *       {@code duration == 3.0} / {@code == startDuration} comparison still sees its exact value;
 *   <li>the frame then lands just past the NEXT window below the current duration
 *       ({@code duration - stop + EPS}), never blindly past it -- a frame that landed EXACTLY on a
 *       boundary would skip the {@code <} test that owns the window, and {@code tickDuration} marks
 *       the action done as soon as the duration goes negative;
 *   <li>with no window left below the current duration the frame finishes the action
 *       ({@code duration + EPS}), which is what those classes do at the end anyway;
 *   <li>a screen up forces {@link Driver}'s screen frame before this plan is consulted, so a frame
 *       that opens a picker still hands the picker the game's own 1/60 s.
 * </ul>
 *
 * <p>Nothing here reaches a digest, a cost or a score: it only changes how many updates one sandbox
 * action takes. {@code search.frameTiers=off} in settings.txt turns it off without a rebuild.
 */
public final class FramePlan {
    /**
     * Frame length used for the head of every action and as the floor of every planned frame.
     *
     * <p>Equal to {@code Settings.ACTION_DUR_XFAST} (0.05): the shortest duration the game itself
     * gives an action, so a frame this long can never overshoot a window the game would have seen.
     */
    private static final float FRESH = 0.05f;

    /** How many updates of an action still use {@link #FRESH} before the plan takes over. */
    private static final int FRESH_FRAMES = 3;

    /** Distance past a window: enough to satisfy a {@code <} test, small enough to never matter. */
    private static final float EPS = 0.001f;

    /** Same ceiling as {@link Driver}'s fast delta: no single frame may be longer than this. */
    private static final float LIMIT = 20.0f;

    /**
     * The audited classes and the duration values their bodies test on a LATER update.
     *
     * <p>An empty array means "only the crossing of zero does anything" -- the draw-one-card-per-
     * crossing pattern of {@code DrawCardAction:106-121}, {@code FastDrawCardAction:68-71},
     * {@code ScrapeAction:80-95} and {@code RipAndTearAction:44-45}. A landing value of 0 is implicit
     * in the rule, so it is not listed.
     *
     * <p>{@code BurnIncreaseAction:34} creates its three burns only once {@code duration < 1.5}, so
     * 1.5 is listed: a frame that jumped past it would skip the creation.
     *
     * <p>Matched by exact class NAME, not by superclass: a mod subclass that overrides {@code update}
     * is a different body and has not been audited, so it keeps the 0.05 s frame.
     */
    private static final Map<String, float[]> WINDOWS = new HashMap<String, float[]>();

    static {
        WINDOWS.put("com.megacrit.cardcrawl.actions.common.DrawCardAction", new float[0]);
        WINDOWS.put("com.megacrit.cardcrawl.actions.common.FastDrawCardAction", new float[0]);
        WINDOWS.put("com.megacrit.cardcrawl.actions.defect.ScrapeAction", new float[0]);
        WINDOWS.put("com.megacrit.cardcrawl.actions.unique.RipAndTearAction", new float[0]);
        WINDOWS.put("loadout.actions.EasyXCostAction", new float[0]);
        WINDOWS.put("com.megacrit.cardcrawl.actions.unique.BurnIncreaseAction", new float[]{1.5f});
    }

    private static final float[] EMPTY = new float[0];

    /** settings.txt key: {@code search.frameTiers=off} restores the plain 0.05 s frame everywhere. */
    private static final String KEY = "search.frameTiers";

    /** {@code AbstractGameAction.duration} -- protected in the shipped jar, hence reflection. */
    private static final Field DURATION = Reflect.find(AbstractGameAction.class, "duration");

    private static Boolean enabledPinned;
    private static Boolean enabledCached;
    private static long enabledAt;
    private static final long CACHE_MS = 1000L;

    /** The action the current plan was built for (identity, so a new instance starts over). */
    private static AbstractGameAction action;
    private static float[] windows = EMPTY;
    private static float start;
    private static int age;
    private static boolean planned;
    private static float delta = FRESH;

    /** Diagnostics, same shape as the other frame counters in {@link Driver}. */
    private static long freshFrames;
    private static long fastFrames;

    private FramePlan() {
    }

    /** Starts one engine update: decides the frame length this action needs, if any. */
    public static void begin(GameActionManager gam) {
        planned = false;
        AbstractGameAction now = gam == null ? null : gam.currentAction;
        if (now == null || now.isDone) {
            action = null;
            return;
        }
        if (!enabled()) {
            action = now;
            return;
        }
        float duration = read(now);
        if (Float.isNaN(duration)) {
            action = now;
            return;
        }
        boolean audited = owns(now.getClass());
        if (now != action) {
            action = now;
            windows = windowsFor(now.getClass());
            start = duration;
            age = 0;
        }
        age++;
        if (!audited) {
            return;
        }
        ++freshFrames;
        if (age <= FRESH_FRAMES) {
            return;
        }
        // A duration that has not moved yet means the action has not been ticked at all (a queue that
        // was rebuilt, a clone); let the game own that frame.
        if (duration >= start) {
            return;
        }
        float stop = 0.0f;
        for (int i = 0; i < windows.length; i++) {
            if (windows[i] <= duration && windows[i] >= stop) {
                stop = windows[i];
            }
        }
        float next = duration - stop + EPS;
        if (next < FRESH) {
            next = FRESH;
        }
        if (next > LIMIT) {
            next = LIMIT;
        }
        delta = next;
        planned = true;
        ++fastFrames;
    }

    /** Ends the update the plan was made for. */
    public static void end() {
        planned = false;
    }

    /** True when {@link #delta()} should be handed to the engine for this update. */
    public static boolean planned() {
        return planned;
    }

    /** The frame length for this update (only meaningful while {@link #planned()}). */
    public static float delta() {
        return delta;
    }

    public static long freshFrames() {
        return freshFrames;
    }

    public static long fastFrames() {
        return fastFrames;
    }

    public static void resetCounters() {
        freshFrames = 0L;
        fastFrames = 0L;
    }

    /** True when this class (by exact name) is one of the audited ones. */
    public static boolean owns(Class<?> c) {
        return c != null && WINDOWS.containsKey(c.getName());
    }

    private static float[] windowsFor(Class<?> c) {
        float[] w = c == null ? null : WINDOWS.get(c.getName());
        return w == null ? EMPTY : w;
    }

    private static float read(AbstractGameAction a) {
        if (DURATION == null) {
            return Float.NaN;
        }
        try {
            return DURATION.getFloat(a);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** Test hook: pin the switch (null = read settings.txt again). */
    public static void setEnabledForTest(Boolean value) {
        enabledPinned = value;
        enabledCached = value;
        enabledAt = 0L;
    }

    /** settings.txt {@code search.frameTiers}, default on; {@code off} leaves the plain 0.05 s. */
    public static boolean enabled() {
        if (enabledPinned != null) {
            return enabledPinned.booleanValue();
        }
        long now = System.currentTimeMillis();
        if (enabledCached != null && now - enabledAt < CACHE_MS) {
            return enabledCached.booleanValue();
        }
        boolean on = true;
        try {
            File f = new File(sts1solver.SolverMod.dir(), "settings.txt");
            if (f.isFile()) {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int eq = line == null ? -1 : line.indexOf('=');
                    if (eq <= 0 || line.trim().startsWith("#")) {
                        continue;
                    }
                    if (!line.substring(0, eq).trim().equals(KEY)) {
                        continue;
                    }
                    String v = line.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
                    on = !("off".equals(v) || "no".equals(v) || "false".equals(v) || "0".equals(v)
                            || "\u5173".equals(v) || "\u5173\u95ed".equals(v));
                }
            }
        } catch (Throwable t) {
            on = true;
        }
        enabledCached = Boolean.valueOf(on);
        enabledAt = now;
        return on;
    }
}
