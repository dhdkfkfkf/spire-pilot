package sts1solver.mirror;

import java.io.File;

/**
 * Where a sandbox writes the game's preference files.
 *
 * <p>{@code UnlockTracker.markCardAsSeen/markRelicAsSeen/markBossAsSeen} do not just change memory:
 * they {@code putInteger} into a {@link com.megacrit.cardcrawl.helpers.Prefs} and {@code flush()},
 * which hands {@code (filepath, json)} to a background saver thread. A simulated card that shows an
 * unseen card therefore writes the player's real save file -- "I played this in the analysis view"
 * lands on disk.
 *
 * <p>The write path is a single String field ({@code Prefs.filepath}), so the sandbox redirects it
 * instead of duplicating the object: same {@code Prefs} identity, same call sites, a path under a
 * directory the game never reads.
 *
 * <p>Important: the path is RELATIVE, exactly like the game's own ({@code preferences/STSSeenCards}).
 * The engine's saver does {@code Gdx.files.getLocalStoragePath() + filepath}, so an absolute path
 * would be concatenated onto that prefix and land somewhere unexpected (the first version of this
 * class did exactly that and the flushed file vanished). A sibling directory under
 * {@code preferences} keeps the game's own writer, layout and validation, and touches nothing the
 * game loads.
 */
public final class SandboxPrefs {

    /** Directory (relative to the game's localStorage path) that only the solver writes. */
    private static final String SUBDIR = "sts1solver-sandbox";

    private SandboxPrefs() {
    }

    /** The value stored in {@code Prefs.filepath} while a sandbox is installed. */
    public static String relative(String name) {
        return "preferences" + File.separator + SUBDIR + File.separator + name;
    }

    /** Where the engine's saver really puts that relative path (for the gate, not for the engine). */
    public static String resolved(String name) {
        String base;
        try {
            base = com.badlogic.gdx.Gdx.files.getLocalStoragePath();
        } catch (Throwable t) {
            base = "";
        }
        return new File(base + relative(name)).getAbsolutePath();
    }

    /**
     * B110: the real filepath behind a sandbox redirect, or null when it is not one.
     *
     * <p>Used to RECOVER: if a leaked window left the live player's save target redirected, the
     * player's own file must be pointed back before the next save, or the game writes the run into
     * the sandbox directory and the real file stays stale.
     */
    public static String originalOf(String filepath) {
        if (!owns(filepath)) {
            return null;
        }
        String p = filepath.replace('\\', '/');
        String marker = "preferences/" + SUBDIR + "/";
        String name = p.substring(marker.length());
        if (name.startsWith("player-")) {
            name = name.substring("player-".length());
        }
        return "preferences/" + name;
    }

    /** True when a filepath value is a sandbox redirect (used by the gate). */
    public static boolean owns(String filepath) {
        return filepath != null
                && filepath.replace('\\', '/').startsWith("preferences/" + SUBDIR + "/");
    }
}
