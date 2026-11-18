package sts1solver.ui;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.util.Log;

/**
 * P2-27: the frame hook only does work while the game is actually showing a fight.
 *
 * <p>Reported (m16717): reading a save or returning to the main menu stalls for seconds. The dumps
 * showed the game itself constructing the main menu (every character re-reads its STSData*.json and
 * its textures) and waiting in nSwapBuffers, so the pause is not a loop of ours -- but we were
 * adding to it: while the game built a menu we still polled the mouse, re-laid out the whole route
 * strip and ticked the search machinery every frame. None of that can do anything useful there, and
 * the panel is not even drawn (Hud already refuses to draw outside GAMEPLAY). So: while the game is
 * not in a live fight, the hook steps aside.
 *
 * <p>The rule is kept as a pure function of (mode name, fading, player present) so a gate can check
 * every combination without driving the real game through a menu transition.
 */
public final class PhaseGate {

    /** Set by the probe so a suite confined to one fight can exercise the frame loop. */
    private static volatile boolean forced;

    private static volatile String lastReason;
    private static volatile long pauses;
    private static volatile long resumes;

    private PhaseGate() {
    }

    /**
     * True when the frame hook may touch game state: a live run, no fade, a player to read.
     *
     * <p>MENU / SPLASH / CHAR_SELECT are obvious. Fading covers both sides of a room change, the save
     * load and the act transition -- the frames where the old fight is gone and the new one is not
     * built yet, which is where the dumps showed seconds of texture and JSON loading.
     */
    public static boolean hotIn(String mode, boolean fading, boolean hasPlayer) {
        if (mode == null || !"GAMEPLAY".equals(mode)) {
            return false;
        }
        if (fading) {
            return false;
        }
        return hasPlayer;
    }

    /** The live answer; cheap enough for every frame (three field reads). */
    public static boolean hot() {
        if (forced) {
            return true;
        }
        String mode;
        try {
            mode = CardCrawlGame.mode == null ? null : CardCrawlGame.mode.name();
        } catch (Throwable t) {
            return false;
        }
        return hotIn(mode, fading(), hasPlayer());
    }

    /**
     * True only while the player is actually inside a fight — the panel's draw and click gate.
     *
     * <p>Reported (m05752): the overlay stayed on screen on the map, the event screens and the rest
     * screen, where it has nothing to say and covers the map itself. {@link #hot()} is the frame-hook
     * gate and stays true across the whole run on purpose (the search has to keep ticking between
     * rooms); this one answers a different question — "is there a fight on screen right now" — so the
     * panel can step out of the way everywhere else.
     *
     * <p>Kept as a pure function of its inputs for the same reason as {@link #hotIn}: a gate can walk
     * every combination without driving the real game onto a map.
     */
    public static boolean fightingIn(String mode, boolean fading, boolean hasPlayer,
                                     boolean inCombatRoom) {
        return hotIn(mode, fading, hasPlayer) && inCombatRoom;
    }

    /** The live answer for {@link #fightingIn}; three field reads and one room read. */
    public static boolean fighting() {
        if (forced) {
            return true;
        }
        String mode;
        try {
            mode = CardCrawlGame.mode == null ? null : CardCrawlGame.mode.name();
        } catch (Throwable t) {
            return false;
        }
        return fightingIn(mode, fading(), hasPlayer(), inCombatRoom());
    }

    private static boolean inCombatRoom() {
        try {
            com.megacrit.cardcrawl.rooms.AbstractRoom room =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
            return room != null
                    && room.phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean fading() {
        try {
            return AbstractDungeon.isFadingIn || AbstractDungeon.isFadingOut;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean hasPlayer() {
        try {
            return AbstractDungeon.player != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** One log line per transition, never one per frame. */
    public static void note(boolean hot) {
        String reason = hot ? "resumed" : pauseReason();
        if (reason.equals(lastReason)) {
            return;
        }
        if (hot) {
            resumes++;
            Log.info("phase gate: resumed (the game is back in a fight)");
        } else {
            pauses++;
            Log.info("phase gate: paused for the hook (" + reason + ")");
        }
        lastReason = reason;
    }

    private static String pauseReason() {
        try {
            String mode = CardCrawlGame.mode == null ? "null" : CardCrawlGame.mode.name();
            String fade = AbstractDungeon.isFadingIn ? "fade-in"
                    : (AbstractDungeon.isFadingOut ? "fade-out" : "no-fade");
            String player = AbstractDungeon.player == null ? "no-player" : "player";
            return "mode=" + mode + " " + fade + " " + player;
        } catch (Throwable t) {
            return "unreadable:" + t;
        }
    }

    public static String describe() {
        return "hot=" + hot() + " pauses=" + pauses + " resumes=" + resumes
                + " last=" + (lastReason == null ? "-" : lastReason) + " forced=" + forced;
    }

    /** Probe hook: the suites run inside one synthetic fight, which is not GAMEPLAY. */
    public static void force(boolean value) {
        forced = value;
    }

    public static long pauses() {
        return pauses;
    }
}
