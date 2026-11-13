package sts1solver.util;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;

/**
 * A probe instance must never make a sound.
 *
 * <p>Why this exists: {@code tools/run.ps1} launches a SECOND game process to run the suite. That
 * process reads the player's {@code preferences/STSSound}, and that file may not hold what the player
 * thinks it holds (on this machine it has no "Music Volume" key at all, so the game falls back to
 * its 0.5 default and the test instance is audible no matter how the player muted his own game). A
 * test run that makes noise while the player is reading is a defect in the tool, not in the settings.
 *
 * <p>What it does: once armed, the three volume statics are held at zero and both music tracks are
 * silenced. The volume statics are what every playback path multiplies by
 * ({@code MainMusic.java:150}, {@code TempMusic.java:154/190}, {@code SoundMaster.java:439}), so zeroing
 * them silences music AND sound effects, including tracks that are still fading in.
 *
 * <p>It is armed from the request file, i.e. only in the throwaway process the tools start; a normal
 * game session never arms it. The player's settings file is never written: only the options screen
 * flushes {@code soundPref} ({@code Slider.java:72}), and nothing here touches that map.
 */
public final class SilentInstance {

    private static boolean armed;
    private static float savedMusic = -1.0f;
    private static float savedSound = -1.0f;
    private static float savedMaster = -1.0f;
    private static long ticks;

    private SilentInstance() {
    }

    /** Arm the process-wide mute. Idempotent; the first call records what the player had. */
    public static synchronized void arm(String why) {
        if (armed) {
            return;
        }
        armed = true;
        savedMusic = Settings.MUSIC_VOLUME;
        savedSound = Settings.SOUND_VOLUME;
        savedMaster = Settings.MASTER_VOLUME;
        apply();
        sts1solver.mirror.MusicMute.silenceLive();
        Log.info("silent instance: audio muted for this process (player had music=" + savedMusic
                + " sound=" + savedSound + " master=" + savedMaster + "; why=" + why + ")");
    }

    private static void apply() {
        Settings.MUSIC_VOLUME = 0.0f;
        Settings.SOUND_VOLUME = 0.0f;
        Settings.MASTER_VOLUME = 0.0f;
    }

    /**
     * Called every frame. Re-zeroing the statics is nearly free and covers the paths that restore
     * them (the mirror puts the live values back on uninstall, and the options screen writes them).
     * Silencing the tracks is the expensive part, so it runs once a second: a track armed after boot
     * would otherwise keep the volume it faded in with.
     */
    public static void tick() {
        if (!armed) {
            return;
        }
        apply();
        if (++ticks % 60L == 0L) {
            sts1solver.mirror.MusicMute.silenceLive();
        }
    }

    public static boolean isArmed() {
        return armed;
    }

    /** The music volume the player had before this process muted itself (diagnostics for the gate). */
    public static float savedMusicVolume() {
        return savedMusic;
    }
}
