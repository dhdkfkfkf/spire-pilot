package sts1solver.mirror;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.megacrit.cardcrawl.audio.MusicMaster;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.integrations.PublisherIntegration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import sts1solver.util.Log;

/**
 * B47: a simulated fight must not reach anything outside this process.
 *
 * <p>The live report that forced this class: pressing auto-play froze the game, and the game log
 * showed the search had simulated the player dying 154 times. Each simulated death runs the real
 * {@code DeathScreen} constructor ({@code AbstractPlayer.java:1465-1484}), and that constructor does
 * a lot more than build a screen:
 *
 * <ul>
 *   <li>{@code submitDefeatMetrics(m)} posts the run to a metrics URL over HTTP. With 19 mods
 *       installed, 25 fake "Lagavulin killed you on floor 6" runs reached the uploader;</li>
 *   <li>{@code StatsScreen.incrementDeath} and {@code incrementFurthestAscent} write the real
 *       character save through {@code Prefs.flush()} (queue peak 1796 writes in one session);</li>
 *   <li>{@code SaveAndContinue.deleteSave(player)} deletes {@code saves/<CLASS>.autosave}, i.e. the
 *       current run save;</li>
 *   <li>{@code pubInteg.setStat(...)} and {@code uploadToSteamLeaderboards()} talk to the store;</li>
 *   <li>{@code CardCrawlGame.music.dispose()} kills the LIVE music tracks and
 *       {@code playTempBgmInstantly} starts the death stinger on the real mixer.</li>
 * </ul>
 *
 * <p>None of that is part of the fight, and none of it can be undone afterwards (a disposed track, an
 * uploaded result and a deleted save file are gone). So instead of cleaning up, the window swaps the
 * four external sinks for inert stand-ins:
 *
 * <ul>
 *   <li>{@code Gdx.net} -- the HTTP client behind the metrics upload;</li>
 *   <li>{@code Gdx.files} -- every {@code local("saves/...")} and {@code local("runs/...")} access is
 *       redirected into a sandbox directory, so a simulated death cannot delete the run save and a
 *       simulated fight cannot append to the run history;</li>
 *   <li>{@code CardCrawlGame.publisherIntegration} -- Steam stats and leaderboards;</li>
 *   <li>{@code CardCrawlGame.music} -- a silent stand-in, so {@code dispose()} cannot kill the
 *       player music and no death stinger is armed.</li>
 * </ul>
 *
 * <p>Everything is restored by identity in {@link #end()}. Nothing here changes what the simulation
 * computes; it only changes what it is allowed to touch (JUDGMENT R13).
 */
public final class ExternalMute {

    private static int depth;
    private static Net realNet;
    private static Files realFiles;
    private static Object realPublisher;
    private static Object realMusic;
    private static int httpCalls;
    private static int saveTouches;
    /** B136 (m07926): run-history writes go to the sandbox too; counted apart from saves. */
    private static int runTouches;
    private static int statCalls;
    private static int musicCalls;
    private static MutedMusic muted;
    private static int musicBuilds;
    private static float savedMaster;
    private static float savedMusic;
    private static float savedSound;

    private ExternalMute() {
    }

    public static synchronized void begin() {
        if (depth++ > 0) {
            return;
        }
        // B70 (m20313): building a MusicMaster re-reads the player's STSSound and overwrites these
        // statics, so the window snapshots them and puts them back when it closes.
        savedMaster = Settings.MASTER_VOLUME;
        savedMusic = Settings.MUSIC_VOLUME;
        savedSound = Settings.SOUND_VOLUME;
        try {
            realNet = Gdx.net;
            Gdx.net = (Net) Proxy.newProxyInstance(Net.class.getClassLoader(), new Class<?>[]{Net.class}, new NetHandler(realNet));
        } catch (Throwable t) {
            realNet = null;
            Log.warn("external mute: Gdx.net was not replaced: " + t);
        }
        try {
            realFiles = Gdx.files;
            Gdx.files = (Files) Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[]{Files.class}, new FilesHandler(realFiles));
        } catch (Throwable t) {
            realFiles = null;
            Log.warn("external mute: Gdx.files was not replaced: " + t);
        }
        try {
            realPublisher = CardCrawlGame.publisherIntegration;
            CardCrawlGame.publisherIntegration = (PublisherIntegration) Proxy.newProxyInstance(
                    PublisherIntegration.class.getClassLoader(),
                    new Class<?>[]{PublisherIntegration.class},
                    new PublisherHandler());
        } catch (Throwable t) {
            realPublisher = null;
            Log.warn("external mute: publisherIntegration was not replaced: " + t);
        }
        try {
            realMusic = CardCrawlGame.music;
            CardCrawlGame.music = mutedMusic();
        } catch (Throwable t) {
            realMusic = null;
            Log.warn("external mute: music was not replaced: " + t);
        }
    }

    public static synchronized void end() {
        if (depth == 0) {
            return;
        }
        if (--depth > 0) {
            return;
        }
        restoreAll();
    }

    /** How many simulated windows are open right now (0 outside a search). */
    public static synchronized int depth() {
        return depth;
    }

    /**
     * Closes any window that was opened and never closed, and reports it.
     *
     * <p>A missing {@code uninstall()} is the one failure mode of this class that the player would
     * notice: the live game keeps a silent stand-in mixer, so the music stays dead for the rest of
     * the session. Install-time recovery keeps that from happening, and the stack trace names the
     * site that forgot to close, so the real bug stays findable.
     */
    public static synchronized int heal() {
        int was = depth;
        if (was == 0) {
            return 0;
        }
        depth = 0;
        Log.error("external mute: closing " + was + " sandbox window(s) that were left open",
                new IllegalStateException("sandbox window not closed"));
        restoreAll();
        return was;
    }

    /** Puts the four real objects back by identity. */
    private static void restoreAll() {
        try {
            if (realNet != null) {
                Gdx.net = realNet;
            }
        } catch (Throwable t) {
            Log.warn("external mute: restoring Gdx.net failed: " + t);
        }
        try {
            if (realFiles != null) {
                Gdx.files = realFiles;
            }
        } catch (Throwable t) {
            Log.warn("external mute: restoring Gdx.files failed: " + t);
        }
        try {
            if (realPublisher != null) {
                CardCrawlGame.publisherIntegration = (PublisherIntegration) realPublisher;
            }
        } catch (Throwable t) {
            Log.warn("external mute: restoring publisherIntegration failed: " + t);
        }
        try {
            if (realMusic != null) {
                CardCrawlGame.music = (MusicMaster) realMusic;
            }
        } catch (Throwable t) {
            Log.warn("external mute: restoring music failed: " + t);
        }
        try {
            Settings.MASTER_VOLUME = savedMaster;
            Settings.MUSIC_VOLUME = savedMusic;
            Settings.SOUND_VOLUME = savedSound;
        } catch (Throwable t) {
            Log.warn("external mute: restoring the volume levels failed: " + t);
        }
        realNet = null;
        realFiles = null;
        realPublisher = null;
        realMusic = null;
    }

    /**
     * The silent mixer, built once per process (B70, m20313).
     *
     * <p>{@code new MusicMaster()} is not free: its constructor reads {@code preferences/STSSound}
     * and writes {@code Settings.MASTER_VOLUME} / {@code Settings.MUSIC_VOLUME} from it. Building a
     * stand-in on every install therefore put the player's own volume back thousands of times per
     * second -- the game log carried 9000+ "Music Volume:" lines in three seconds, and the audio
     * came back at the file's level in the gaps between windows. The three statics are snapshotted
     * around the single build so that even one build cannot move them.
     */
    private static MutedMusic mutedMusic() {
        if (muted != null) {
            return muted;
        }
        float master = Settings.MASTER_VOLUME;
        float music = Settings.MUSIC_VOLUME;
        float sound = Settings.SOUND_VOLUME;
        try {
            muted = new MutedMusic();
            ++musicBuilds;
            Log.info("external mute: built the silent mixer once for this process (its constructor re-reads the volume settings)");
        }
        finally {
            Settings.MASTER_VOLUME = master;
            Settings.MUSIC_VOLUME = music;
            Settings.SOUND_VOLUME = sound;
        }
        return muted;
    }

    /** How often the silent mixer had to be constructed (B70: once per process). */
    public static int musicBuilds() {
        return musicBuilds;
    }

    /** The three volume statics as {master, music, sound}. */
    public static float[] volumes() {
        return new float[]{Settings.MASTER_VOLUME, Settings.MUSIC_VOLUME, Settings.SOUND_VOLUME};
    }

    public static boolean active() {
        return depth > 0;
    }

    public static int httpCalls() {
        return httpCalls;
    }

    public static int saveTouches() {
        return saveTouches;
    }

    public static int runTouches() {
        return runTouches;
    }

    public static int statCalls() {
        return statCalls;
    }

    public static int musicCalls() {
        return musicCalls;
    }

    public static synchronized void resetCounters() {
        httpCalls = 0;
        saveTouches = 0;
        runTouches = 0;
        statCalls = 0;
        musicCalls = 0;
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * B136 (m07926): the player's saved files the sandbox must not touch. {@code runs/} holds the run
     * history that {@code Metrics.gatherAllDataAndSave} writes and that {@code CharStat} reads back on
     * every character creation, so a simulated fight must not append to it either.
     */
    private static boolean isSandboxPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.replace('\\', '/');
        return p.startsWith("saves/") || p.equals("saves")
                || p.startsWith("runs/") || p.equals("runs");
    }

    /** Every request the simulation would have sent is dropped, and counted. */
    private static final class NetHandler implements InvocationHandler {
        private final Net real;

        NetHandler(Net real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            if ("sendHttpRequest".equals(n)) {
                httpCalls++;
                if (httpCalls == 1) {
                    Log.info("external mute: the first simulated HTTP request was dropped (metrics upload)");
                }
                return null;
            }
            if ("cancelHttpRequest".equals(n)) {
                return null;
            }
            if ("toString".equals(n)) {
                return "ExternalMute.net";
            }
            if ("hashCode".equals(n)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(n)) {
                return proxy == args[0];
            }
            return call(real, m, args);
        }
    }

    /** Save-file access is redirected into the sandbox; everything else is the real file system. */
    private static final class FilesHandler implements InvocationHandler {
        private final Files real;

        FilesHandler(Files real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            if (args != null && args.length > 0 && args[0] instanceof String
                    && ("local".equals(n) || "absolute".equals(n) || "external".equals(n) || "getFileHandle".equals(n))) {
                String path = (String) args[0];
                if (isSandboxPath(path)) {
                    boolean run = path.replace('\\', '/').startsWith("runs");
                    if (run) {
                        if (++runTouches == 1) {
                            Log.info("external mute: a simulated run-history write was redirected (" + path + ")");
                        }
                    } else if (++saveTouches == 1) {
                        Log.info("external mute: a simulated save-file access was redirected (" + path + ")");
                    }
                    Object[] copy = args.clone();
                    copy[0] = "sts1solver-sandbox" + java.io.File.separator + path;
                    return call(real, m, copy);
                }
            }
            if ("toString".equals(n)) {
                return "ExternalMute.files";
            }
            if ("hashCode".equals(n)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(n)) {
                return proxy == args[0];
            }
            return call(real, m, args);
        }
    }

    /** Steam stats, leaderboards and rich presence all become no-ops. */
    private static final class PublisherHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if ("toString".equals(n)) {
                return "ExternalMute.publisher";
            }
            if ("hashCode".equals(n)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(n)) {
                return proxy == args[0];
            }
            statCalls++;
            Class<?> rt = m.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            if (rt == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    /** A mixer that accepts every call and does nothing: no audio is created or destroyed. */
    private static final class MutedMusic extends MusicMaster {
        @Override
        public void update() {
            musicCalls++;
        }

        @Override
        public void updateVolume() {
        }

        @Override
        public void fadeOutTempBGM() {
        }

        @Override
        public void justFadeOutTempBGM() {
        }

        @Override
        public void playTempBGM(String key) {
            musicCalls++;
        }

        @Override
        public void playTempBgmInstantly(String key) {
            musicCalls++;
        }

        @Override
        public void precacheTempBgm(String key) {
        }

        @Override
        public void playPrecachedTempBgm() {
            musicCalls++;
        }

        @Override
        public void playTempBgmInstantly(String key, boolean loop) {
            musicCalls++;
        }

        @Override
        public void changeBGM(String key) {
            musicCalls++;
        }

        @Override
        public void fadeOutBGM() {
        }

        @Override
        public void silenceBGM() {
        }

        @Override
        public void silenceBGMInstantly() {
        }

        @Override
        public void unsilenceBGM() {
        }

        @Override
        public void silenceTempBgmInstantly() {
        }

        @Override
        public void dispose() {
            musicCalls++;
        }

        @Override
        public void fadeAll() {
        }
    }
}
