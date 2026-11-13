package sts1solver.mirror;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import sts1solver.util.Log;

/**
 * Takes the real mouse away from the game for the duration of an install window.
 *
 * <p>Why this exists (B37): the sandbox does not render, but it DOES run the engine's own update
 * code -- {@code AbstractPlayer.update()}, {@code Hitbox.update()}, the hand and every card. Those
 * read {@link InputHelper#mX} / {@link InputHelper#mY} directly, so a simulated update saw the
 * player's real cursor: hovering a card mid-search ran the hover code (cards moved, tooltips
 * appeared) and any code that keys off the cursor thought the sandbox was being pointed at.
 * Input is not combat state -- it belongs to the live session -- so the sandbox must not see it.
 *
 * <p>mX/mY are plain static fields, rewritten by {@code InputHelper.update()} at the start of every
 * live frame. Pushing an off-screen position therefore only affects the simulated updates inside
 * the window, and popping restores the exact pair the live frame had.
 */
public final class InputMute {

    private static final int OFFSCREEN = -100000;

    private static int depth;
    private static int savedX;
    private static int savedY;
    private static boolean rewrote;
    private static int skippedForScreen;
    /** B81 (m22687): a search can skip the cursor tens of thousands of times; log a summary, not a line. */
    private static final long SKIP_LOG_MS = 5000L;
    private static int skippedSinceLog;
    /** How many summary lines were actually written (monotonic; gates read deltas). */
    private static int skipLines;
    private static long lastSkipLogAt;
    private static boolean skipLogged;
    private static String lastScreen = "?";
    private static boolean lastScreenUp;

    private InputMute() {
    }

    /** Points the engine's mouse far off-screen. Nesting is counted, not re-saved. */
    public static void push() {
        try {
            if (depth == 0) {
                savedX = InputHelper.mX;
                savedY = InputHelper.mY;
                rewrote = !screenUp();
                if (rewrote) {
                    InputHelper.mX = OFFSCREEN;
                    InputHelper.mY = OFFSCREEN;
                } else {
                    // B70 (m20313): a slider lerps its handle to InputHelper.mX, so pushing the
                    // cursor off-screen with a screen open dragged the master volume to zero and
                    // the options screen flushed that into the player's STSSound file.
                    ++skippedForScreen;
                    ++skippedSinceLog;
                    noteSkip();
                }
            }
            ++depth;
        } catch (Throwable t) {
            Log.warn("input mute failed: " + t);
        }
    }

    /** Restores the live cursor position once the outermost window closes. */
    public static void pop() {
        try {
            if (depth == 0) {
                return;
            }
            --depth;
            if (depth == 0 && rewrote) {
                InputHelper.mX = savedX;
                InputHelper.mY = savedY;
                rewrote = false;
            }
        } catch (Throwable t) {
            Log.warn("input restore failed: " + t);
        }
    }

    /**
     * True while the game has a screen up (options, deck, reward, a card tooltip...).
     *
     * <p>B70 (m20313): the mute is only safe when nothing is listening for the cursor. The options
     * screen builds {@code Slider}s whose {@code update()} lerps the handle towards
     * {@code InputHelper.mX}, and a release writes the resulting level through
     * {@code Preferences.flush()} -- that is how the player's master volume ended up as 0.0.
     */
    private static boolean screenUp() {
        try {
            lastScreenUp = AbstractDungeon.isScreenUp;
            lastScreen = String.valueOf(AbstractDungeon.screen);
            if (AbstractDungeon.isScreenUp) {
                return true;
            }
            return AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE;
        } catch (Throwable t) {
            lastScreen = "?";
            lastScreenUp = true;
            return true;
        }
    }

    /**
     * B81 (m22687): this used to be one INFO line per install window. A single long search skips the
     * cursor once per simulated install, i.e. 50k+ lines, and every line is flushed on the render
     * thread -- that alone dropped the search to a few dozen world lines per second.
     *
     * <p>Now it is a summary at most once every {@link #SKIP_LOG_MS}, carrying the values that make
     * the message actionable: which screen the engine thinks is open and whether isScreenUp is set.
     */
    private static void noteSkip() {
        long now = System.currentTimeMillis();
        if (skipLogged && now - lastSkipLogAt < SKIP_LOG_MS) {
            return;
        }
        skipLogged = true;
        lastSkipLogAt = now;
        ++skipLines;
        Log.info("input mute: left the cursor alone in " + skippedSinceLog
                + " install window(s) because a screen is open (screen=" + lastScreen
                + " isScreenUp=" + lastScreenUp + "); summarised at most every "
                + (SKIP_LOG_MS / 1000L) + "s");
        skippedSinceLog = 0;
    }

    /** Forgets the summary state so the next skip logs its values again (new search / gate). */
    public static void resetSkipLog() {
        skipLogged = false;
        skippedSinceLog = 0;
        lastSkipLogAt = 0L;
    }

    /** Total summary lines written so far; gates assert this grows once per batch, not per skip. */
    public static int skipLogLines() {
        return skipLines;
    }

    /** The screen the last skip saw, for logs and gates. */
    public static String lastScreenName() {
        return lastScreen;
    }

    /** Whether isScreenUp was set when the last skip looked. */
    public static boolean lastScreenUp() {
        return lastScreenUp;
    }

    /** How many install windows left the cursor alone because a screen was open (B70). */
    public static int skippedForScreen() {
        return skippedForScreen;
    }

    /** True while some install window is holding the mouse off-screen. */
    public static boolean active() {
        return depth > 0;
    }

    /** The position the live frame had when the outermost window opened (tests only). */
    public static int savedX() {
        return savedX;
    }

    public static int savedY() {
        return savedY;
    }
}
