package sts1solver.mirror;

import com.megacrit.cardcrawl.audio.Sfx;
import com.megacrit.cardcrawl.audio.SoundMaster;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * B65: a simulated fight must not be heard. Music already had its own window (MusicMute, B45); this
 * is the sound-effect half, i.e. every play / playAndLoop / stop the engine performs on
 * {@code CardCrawlGame.sound} while a world is installed.
 *
 * <p>Why zeroing {@code Settings.SOUND_VOLUME} (Driver.begin) was not enough: the volume is read at
 * play time, so a simulated loop starts silent -- but the mixer itself is shared. The engine writes
 * into two private collections of the one live {@code SoundMaster}:
 *
 * <ul>
 *   <li>{@code map}: the key -&gt; {@link Sfx} table. A simulated loop creates a playing gdx handle
 *       that nobody stops, and a simulated play may be the first one for that key, which loads an
 *       OpenAL source inside the node (the 4 s {@code OpenALSound.play} stall seen in a hang dump).</li>
 *   <li>{@code fadeOutList}: {@code AbstractScene.fadeOutAmbiance()} (every boss/monster that dies:
 *       Lagavulin, SlimeBoss, Hexaghost, Champ, AwakenedOne, ...) appends an entry; {@code
 *       SoundMaster.update()} then re-applies the volume of that id EVERY FRAME using the CURRENT
 *       {@code Settings.SOUND_VOLUME}. The sandbox's silent loop therefore becomes audible the
 *       moment the live game runs again, plays its ambience for a fraction of a second and fades --
 *       a background sound that "appears out of nowhere, repeatedly, at short intervals" after a
 *       search (the reported symptom).</li>
 * </ul>
 *
 * <p>Both collections are swapped for inert ones for exactly the window: plays, loops, volume
 * adjustments and stops are answered by a silent {@link Sfx} stand-in that never touches
 * {@code Gdx.audio}, and fades are dropped instead of queued. The real table comes back untouched on
 * exit, so the live game keeps its ambience ids and its sounds.
 */
public final class SfxMute {

    /** Answers every play/loop/stop with 0 and never creates a gdx Sound. */
    private static final class Silent extends Sfx {
        private int plays;

        Silent() {
            super("", false);
        }

        @Override
        public long play(float volume) {
            plays++;
            return 0L;
        }

        @Override
        public long play(float volume, float y, float z) {
            plays++;
            return 0L;
        }

        @Override
        public long loop(float volume) {
            plays++;
            return 0L;
        }

        @Override
        public void setVolume(long id, float volume) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void stop(long id) {
        }
    }

    /** Swallows fade-out entries: a queued fade re-applies the LIVE volume to a muted loop. */
    private static final class FadeSink extends ArrayList<Object> {
        @Override
        public boolean add(Object entry) {
            droppedFades++;
            return false;
        }
    }

    /** A map that claims to know every key and hands out the silent stand-in. */
    private static final class Inert extends HashMap<String, Sfx> {
        private final Sfx silent;

        Inert(Sfx silent) {
            this.silent = silent;
        }

        @Override
        public boolean containsKey(Object key) {
            return true;
        }

        @Override
        public Sfx get(Object key) {
            return silent;
        }
    }

    private static boolean active;
    private static Object master;
    private static HashMap<String, Sfx> savedMap;
    private static ArrayList<?> savedFade;
    private static Silent silent;
    private static Inert inert;
    private static int droppedFades;
    private static int lastPlayed;
    private static int lastFades;

    private SfxMute() {
    }

    private static java.lang.reflect.Field mapField;
    private static java.lang.reflect.Field fadeField;

    private static boolean fields() {
        if (mapField != null && fadeField != null) {
            return true;
        }
        try {
            java.lang.reflect.Field m = SoundMaster.class.getDeclaredField("map");
            java.lang.reflect.Field f = SoundMaster.class.getDeclaredField("fadeOutList");
            m.setAccessible(true);
            f.setAccessible(true);
            mapField = m;
            fadeField = f;
            return true;
        } catch (Throwable t) {
            Log.warnEvery("sfx-mute-shape", 5000L,
                    "sfx mute: this SoundMaster has no map/fadeOutList (" + t + ")");
            return false;
        }
    }

    /** Opens the window. Not re-entrant: one sandbox at a time, always from the current state. */
    @SuppressWarnings("unchecked")
    public static void begin() {
        if (active) {
            end();
        }
        master = CardCrawlGame.sound;
        if (master == null || !fields()) {
            master = null;
            return;
        }
        {
            try {
                savedMap = (HashMap<String, Sfx>) mapField.get(master);
                savedFade = (ArrayList<?>) fadeField.get(master);
                if (silent == null) {
                    silent = new Silent();
                }
                if (inert == null) {
                    inert = new Inert(silent);
                }
                inert.clear();
                silent.plays = 0;
                mapField.set(master, inert);
                fadeField.set(master, new FadeSink());
                droppedFades = 0;
                active = true;
            } catch (Throwable t) {
                Log.warnEvery("sfx-mute-swap", 5000L,
                        "sfx mute: could not swap the sound tables (" + t + ")");
                master = null;
            }
        }
    }

    /** Closes the window and puts the live tables back. */
    public static void end() {
        lastPlayed = silent == null ? 0 : silent.plays;
        lastFades = droppedFades;
        try {
            if (master == null) {
                return;
            }
            Object current = CardCrawlGame.sound;
            if (current == master) {
                mapField.set(master, savedMap);
                fadeField.set(master, savedFade);
            } else if (current != null) {
                // The mixer object itself was replaced inside the window (a mod rebuilt it). Put the
                // tables back on whatever object the game holds now, never on our stand-in.
                Log.warnEvery("sfx-mute-changed", 5000L,
                        "sfx mute: CardCrawlGame.sound changed inside the window; restoring the new one");
                mapField.set(current, savedMap);
                fadeField.set(current, savedFade);
            }
        } catch (Throwable t) {
            Log.error("sfx mute: could not restore the sound tables", t);
        } finally {
            master = null;
            savedMap = null;
            savedFade = null;
            active = false;
        }
    }

    /** Closes a window that was left open by a throwing search. Returns 1 when it had to. */
    public static int heal() {
        if (!active) {
            return 0;
        }
        end();
        return 1;
    }

    public static boolean active() {
        return active;
    }

    /** Plays the window absorbed (diagnostics for the gate). */
    public static int lastPlayed() {
        return lastPlayed;
    }

    /** Fade entries the window dropped instead of queueing (diagnostics for the gate). */
    public static int lastFades() {
        return lastFades;
    }

    public static String describe() {
        return "sfx=" + (active ? "muted" : "live") + " played=" + lastPlayed + " fades=" + lastFades;
    }
}
