package sts1solver.mirror;

import com.megacrit.cardcrawl.core.CardCrawlGame;

/**
 * B45: a simulated fight must not be heard, and it must not leak a music track into the real game.
 *
 * <p>Two separate faults made this class necessary. First, a boss arms its theme from
 * {@code usePreBattleAction}/{@code changeState("Activate")} (Hexaghost.java:253-256), which calls
 * {@code unsilenceBGM(); playPrecachedTempBgm();} and therefore plays on the TEMP track; silencing
 * only the main track (B44) left the boss theme audible. Second, every synthetic fight that arms a
 * track appends a {@code TempMusic} to the live {@code MusicMaster.tempTrack} and nothing removes it
 * -- a probe suite had accumulated 507 of them, each holding a playing gdx Music handle.
 *
 * <p>{@code CardCrawlGame.music} is a shell static in Statics (only the reference is restored), so a
 * sandbox write reaches the real mixer. Rather than mirror the object, this class brackets the
 * window: it records how many tracks exist and which ones are silenced, then on exit it disposes
 * every track the window created and puts back any silence flag the simulation flipped. New tracks
 * are dropped at the end of each node, i.e. within a millisecond of being created, and both
 * {@code MainMusic} (4s) and {@code TempMusic} (0.25s) fade in, so nothing ever becomes audible.
 */
public final class MusicMute {
    private static boolean restore;
    private static Object master;
    private static int[] before = new int[2];
    private static boolean[][] saved = new boolean[2][];
    private static int dropped;
    private static int lastDropped;

    private MusicMute() {
    }

    /**
     * Brackets a simulated window.
     *
     * @param restoreOnEnd true for an in-game search: put the player's own music state back; false
     *                     for a probe instance: leave the mixer silent.
     */
    public static void begin(boolean restoreOnEnd) {
        // Not re-entrant on purpose: one window is open at a time, and a caller that threw before
        // calling end() must not poison the next window. begin() therefore always starts a fresh
        // frame from the CURRENT track list.
        restore = restoreOnEnd;
        dropped = 0;
        master = CardCrawlGame.music;
        saved = new boolean[][]{new boolean[0], new boolean[0]};
        if (master == null) {
            return;
        }
        for (int k = 0; k < 2; k++) {
            java.util.List<Object> list = list(k);
            if (list == null) {
                before[k] = 0;
                saved[k] = new boolean[0];
                continue;
            }
            before[k] = list.size();
            boolean[] flags = new boolean[list.size()];
            for (int i = 0; i < flags.length; i++) {
                flags[i] = isSilenced(list.get(i));
            }
            saved[k] = flags;
        }
    }

    public static void end() {
        lastDropped = dropped;
        try {
            if (master == null) {
                return;
            }
            dropAdded();
            if (restore) {
                putBackSilence();
            } else {
                silenceAll();
            }
        } finally {
            master = null;
            saved = new boolean[][]{new boolean[0], new boolean[0]};
        }
    }

    /** Tracks disposed by the most recent completed window (diagnostics for the gate). */
    public static int lastDropped() {
        return lastDropped;
    }

    public static int aliveTracks() {
        int n = 0;
        Object m = CardCrawlGame.music;
        Object keep = master;
        master = m;
        try {
            for (int k = 0; k < 2; k++) {
                java.util.List<Object> list = list(k);
                if (list != null) {
                    n += list.size();
                }
            }
        } finally {
            master = keep;
        }
        return n;
    }

    private static void dropAdded() {
        for (int k = 0; k < 2; k++) {
            java.util.List<Object> list = list(k);
            if (list == null) {
                continue;
            }
            int floor = before[k];
            for (int i = list.size() - 1; i >= floor && i >= 0; i--) {
                Object track = list.remove(i);
                dropped++;
                kill(track);
            }
        }
    }

    private static void kill(Object track) {
        try {
            track.getClass().getMethod("kill").invoke(track);
        } catch (Throwable ignored) {
            // A track without kill() is still out of the list, which is what stops the sound.
        }
    }

    /** Silence both live tracks now, without a window. Used by a probe instance that must stay quiet. */
    public static void silenceLive() {
        silenceAll();
    }

    private static void silenceAll() {
        try {
            com.megacrit.cardcrawl.audio.MusicMaster m = com.megacrit.cardcrawl.core.CardCrawlGame.music;
            if (m == null) {
                return;
            }
            m.silenceBGMInstantly();
            m.silenceTempBgmInstantly();
        } catch (Throwable ignored) {
            // Sound is cosmetic: never fail a search over it.
        }
    }

    private static void putBackSilence() {
        for (int k = 0; k < 2; k++) {
            java.util.List<Object> list = list(k);
            if (list == null) {
                continue;
            }
            int upto = Math.min(before[k], list.size());
            for (int i = 0; i < upto; i++) {
                Object track = list.get(i);
                if (saved[k].length > i && saved[k][i] && !isSilenced(track)) {
                    try {
                        track.getClass().getMethod("silenceInstantly").invoke(track);
                    } catch (Throwable ignored) {
                        // A track that refuses to silence is a cosmetic miss; music isolation must
                        // never take the game down.
                    }
                }
            }
        }
    }

    private static boolean isSilenced(Object track) {
        try {
            return track.getClass().getField("isSilenced").getBoolean(track);
        } catch (Throwable t) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> list(int k) {
        if (master == null) {
            return null;
        }
        try {
            // Declared on MusicMaster, not on the runtime class: a stand-in subclass (ExternalMute)
            // has no such field, and reflection on the class of the object would just throw.
            java.lang.reflect.Field f = com.megacrit.cardcrawl.audio.MusicMaster.class
                    .getDeclaredField(k == 0 ? "mainTrack" : "tempTrack");
            f.setAccessible(true);
            Object v = f.get(master);
            return v instanceof java.util.List ? (java.util.List<Object>) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
