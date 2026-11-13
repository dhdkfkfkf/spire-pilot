package sts1solver.mirror;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.helpers.Prefs;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import sts1solver.util.Log;

/**
 * Keeps a simulated fight from writing the player's REAL character save file.
 *
 * <p>The engine stores per-character progress (wins, deaths, {@code ENEMY_KILL}, highest floor,
 * ascension unlocks) in a {@link Prefs} owned by the character object: {@code AbstractPlayer.getPrefs()}.
 * {@code CharStat} keeps that same reference and calls {@code incrementEnemySlain()} ->
 * {@code pref.increment(...) + pref.flush()} whenever a monster dies. A sandbox fight therefore
 * bumped the live Vagabond counter from 1898 to 8013 over one afternoon of gate runs, and one run
 * raced the game's own saver mid-write, made {@code SaveHelper} believe the file was corrupt and
 * move it to {@code sendToDevs} (the S48 gate flake on 2026-10-30 13:13).
 *
 * <p>The write path is one String field plus one map, so - exactly like {@link SandboxPrefs} for
 * {@code UnlockTracker} - the sandbox redirects {@code filepath} into its own directory and wraps
 * {@code data} in a {@link PrefsOverlay} (reads fall through, writes stay local). The {@code Prefs}
 * object itself is shared by the cloner (see {@link ClonePolicy}), so one redirect covers the live
 * player and every copy of the fight, and no per-fork map copy is paid.
 *
 * <p>Two scopes: a synthetic player built by {@code Synthetic} is sandbox-only and stays redirected
 * (session scope, never restored), while the LIVE player is muted only for the duration of a
 * {@link World#install()} (install scope, restored by {@link World#uninstall()}).
 */
public final class PlayerPrefs {

    private PlayerPrefs() {
    }

    /** Original filepath and data map of one Prefs object, restored when its scope ends. */
    private static final class Saved {
        final String filepath;
        final Map<String, String> data;

        Saved(String filepath, Map<String, String> data) {
            this.filepath = filepath;
            this.data = data;
        }
    }

    /** Synthetic players: sandbox-only objects, so the redirect lasts as long as the process. */
    private static final Map<Object, Saved> SESSION = new IdentityHashMap<Object, Saved>();

    /** The live player: muted only while a world is installed. */
    private static final Map<Object, Saved> INSTALL = new IdentityHashMap<Object, Saved>();

    public static void muteForSession(AbstractPlayer player) {
        mute(player == null ? null : prefsOf(player), SESSION);
    }

    public static void muteForSession(Prefs pref) {
        mute(pref, SESSION);
    }

    public static void muteForInstall(AbstractPlayer player) {
        mute(player == null ? null : prefsOf(player), INSTALL);
    }

    public static void muteForInstall(Prefs pref) {
        mute(pref, INSTALL);
    }

    /** Puts the live player's own save target back. Called on every uninstall. */
    public static void restoreInstall() {
        for (Map.Entry<Object, Saved> e : INSTALL.entrySet()) {
            Prefs p = (Prefs) e.getKey();
            Saved s = e.getValue();
            try {
                p.data = s.data;
                p.filepath = s.filepath;
                // B110: verify instead of hoping. A redirect that survives here makes the game write
                // the player's own run save into preferences\sts1solver-sandbox and the real file
                // stay stale -- the "my run disappeared" bug.
                if (SandboxPrefs.owns(p.filepath)) {
                    p.filepath = s.filepath;
                    ++redirectLeaks;
                    Log.error("the live save target was still redirected after restore; forced back to "
                            + s.filepath, new IllegalStateException("save target leaked"));
                }
            } catch (Throwable t) {
                Log.warn("restoring a player save target failed: " + t);
            }
        }
        INSTALL.clear();
    }

    /** B110: how many times a restore had to force the real path back (gate/health line). */
    private static volatile int redirectLeaks;

    public static int redirectLeaks() {
        return redirectLeaks;
    }

    /** B110: how many install-scope redirects are still recorded (must be 0 outside a window). */
    public static int installScopeCountForTest() {
        return INSTALL.size();
    }

    /** B110: repair one given Prefs that points into the sandbox (explicit, for a health check). */
    public static int recover(Prefs pref) {
        if (pref == null || !SandboxPrefs.owns(pref.filepath)) {
            return 0;
        }
        String real = SandboxPrefs.originalOf(pref.filepath);
        if (real == null) {
            return 0;
        }
        Log.error("a save target was left in the sandbox (" + pref.filepath
                + "); pointing it back at " + real, new IllegalStateException("save target leaked"));
        pref.filepath = real;
        ++redirectLeaks;
        return 1;
    }

    /**
     * B110: last line of defence -- no live player may keep a sandbox save target.
     *
     * <p>Called at the end of every {@link World#uninstall()} and once at mod start, so a leak from
     * a previous session is repaired before the game writes anything.
     */
    public static int verifyNoLiveRedirect() {
        int fixed = 0;
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer p =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            Prefs pref = p == null ? null : p.getPrefs();
            // A synthetic player built by the sandbox is MEANT to stay redirected for the whole
            // session, and a live window legitimately redirects too: neither is a leak. Repairing
            // those would point a simulated save at the real file (the S66 gates caught exactly that).
            if (pref != null && (SESSION.containsKey(pref) || INSTALL.containsKey(pref))) {
                return 0;
            }
            if (pref != null && SandboxPrefs.owns(pref.filepath)) {
                String real = SandboxPrefs.originalOf(pref.filepath);
                if (real != null) {
                    Log.error("a live save target was left in the sandbox (" + pref.filepath
                            + "); pointing it back at " + real,
                            new IllegalStateException("save target leaked"));
                    pref.filepath = real;
                    ++fixed;
                    ++redirectLeaks;
                }
            }
        } catch (Throwable t) {
            Log.warn("checking the live save target failed: " + t);
        }
        return fixed;
    }

    private static Prefs prefsOf(AbstractPlayer player) {
        try {
            return player.getPrefs();
        } catch (Throwable t) {
            Log.warn("this player exposes no prefs: " + t);
            return null;
        }
    }

    private static void mute(Prefs pref, Map<Object, Saved> scope) {
        if (pref == null) {
            return;
        }
        if (SESSION.containsKey(pref) || INSTALL.containsKey(pref)) {
            return;
        }
        String original = pref.filepath;
        if (SandboxPrefs.owns(original) || PrefsOverlay.isOverlay(pref.data)) {
            return;
        }
        Saved saved = new Saved(original, pref.data);
        scope.put(pref, saved);
        pref.data = original == null ? new HashMap<String, String>() : new PrefsOverlay(saved.data);
        pref.filepath = SandboxPrefs.relative(sandboxName(original));
        // B40: the live player is re-muted on every World.install (once per search node), and the log
        // used to get one "sandbox save:" line per node -- 99% of a 2.5MB log was this one line.
        // The first occurrence per save file carries all the information the rest repeated.
        if (ANNOUNCED.add(sandboxName(original))) {
            Log.info("sandbox save: " + sandboxName(original) + " -> " + pref.filepath);
        }
    }

    /** Save files whose sandbox redirect has already been logged once (B40). */
    private static final java.util.Set<String> ANNOUNCED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** For the gate: how many distinct save redirects have been announced so far. */
    public static int announcedCount() {
        return ANNOUNCED.size();
    }

    /** {@code preferences\STSDataVagabond} -> {@code player-STSDataVagabond}. */
    public static String sandboxName(String filepath) {
        String name = filepath == null ? "player" : filepath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return "player-" + name;
    }

    /** For the gate: the real path a muted Prefs would have written, or its current path. */
    public static String originalFilepath(Prefs pref) {
        if (pref == null) {
            return null;
        }
        Saved s = SESSION.get(pref);
        if (s == null) {
            s = INSTALL.get(pref);
        }
        return s == null ? pref.filepath : s.filepath;
    }

    /** For the gate and logs: how many Prefs objects are redirected right now. */
    public static int mutedCount() {
        return SESSION.size() + INSTALL.size();
    }

    /** True when this exact filepath is one of the sandbox's player-save redirects. */
    public static boolean isPlayerSave(String filepath) {
        return filepath != null && filepath.replace('\\', '/').contains("/player-");
    }
}
