package sts1solver.mirror;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import java.util.HashSet;
import java.util.Set;
import sts1solver.util.Log;

/**
 * B112 (user m05182): a gate run must not leave a RUN SAVE in the player's real saves folder.
 *
 * <p>During a suite the game plays a synthetic fight. When the suite ends, ModTheSpire closes the
 * game, and the engine's normal "save the current run on exit" path runs OUTSIDE any sandbox install
 * window -- so {@code ExternalMute}'s redirect is not active and a synthetic Ironclad autosave lands
 * in the player's own {@code saves/} folder. The next launch then offers that bogus run, which is
 * exactly the "another save replaced mine" class of problem B110 was about.
 *
 * <p>The guard records the folder at suite start and deletes whatever appeared afterwards; the
 * player's own run saves are left untouched because they existed before the suite.
 */
public final class SaveGuard {

    private static Set<String> before;
    private static volatile int removed;

    private SaveGuard() {
    }

    /** Records the run saves that belong to the player (called once when a suite starts). */
    public static void begin() {
        try {
            before = new HashSet<String>();
            FileHandle[] files = Gdx.files.local("saves").list();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    continue;
                }
                before.add(files[i].name());
            }
            removed = 0;
            Log.info("save guard: " + before.size() + " pre-existing run save file(s) are protected");
        } catch (Throwable t) {
            before = null;
            Log.warn("save guard: cannot read the saves folder: " + t);
        }
    }

    /** Deletes every run-save file the suite produced. Returns how many were removed. */
    public static int sweep() {
        if (before == null) {
            return 0;
        }
        int n = 0;
        try {
            FileHandle dir = Gdx.files.local("saves");
            FileHandle[] files = dir.list();
            for (int i = 0; i < files.length; i++) {
                FileHandle f = files[i];
                if (f.isDirectory() || before.contains(f.name())) {
                    continue;
                }
                try {
                    f.delete();
                    n++;
                    Log.warn("save guard: removed a run save the suite wrote (" + f.name()
                            + "); it was not there when the suite started");
                } catch (Throwable t) {
                    Log.warn("save guard: cannot remove " + f.name() + ": " + t);
                }
            }
        } catch (Throwable t) {
            Log.warn("save guard sweep failed: " + t);
        }
        removed += n;
        return n;
    }

    /** Total removed in this process (log/gate). */
    public static int removed() {
        return removed;
    }

    /** Test hook (S166): forget the recorded state. */
    public static void resetForTest() {
        before = null;
        removed = 0;
    }
}
