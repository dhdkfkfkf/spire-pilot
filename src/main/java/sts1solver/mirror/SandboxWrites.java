package sts1solver.mirror;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.helpers.AsyncSaver;
import com.megacrit.cardcrawl.helpers.Prefs;
import sts1solver.util.Log;

/**
 * B136 (user m07926 / m08227): a sandbox must not persist anything, and it must not sit in the
 * game's save queue either.
 *
 * <p>What went wrong: {@code SandboxPrefs} redirects a sandbox {@code Prefs.filepath} into
 * {@code preferences/sts1solver-sandbox/}, which kept the CONTENT out of the player's files but not
 * the WRITE. {@code Prefs.flush()} still hands {@code (filepath, json)} to {@code AsyncSaver}, whose
 * single {@code FileSaver} thread is also what writes the player's real run save
 * ({@code SaveAndContinue.save}). A simulated fight reaches {@code UnlockTracker.unlockAchievement}
 * (from {@code ApplyPowerAction}/{@code AbstractCreature.addPower} once the player holds 10+ BUFF
 * powers, from {@code addBlock} at block >= 99, and from each monster slain) and that method's
 * {@code achievementPref.flush()} is UNCONDITIONAL, so every one of those events queued another
 * whole-file write.
 *
 * <p>Measured on the player's machine (2026-11-05): the queue reached 638,475 entries, drained at
 * ~337 per second, i.e. ~30 minutes of backlog; {@code saves/CAI_NONG.autosave} was never written
 * for the whole session (still the previous day's file), and the debug line per dequeue grew the
 * game log 2 MB every 13-16 s, which made log4j rotate (and fail to delete) on the render thread for
 * 13 s at a time. "Saving is slow / not responding" and "loading put me back on the old save" were
 * one story: the player's own save queued behind hundreds of thousands of sandbox writes.
 *
 * <p>The fix is a property of the sandbox, not of any card, achievement or mod: a save whose target
 * is a sandbox redirect is dropped at the two exits the engine has -- {@code Prefs.flush()} (skips
 * the serialization too) and {@code AsyncSaver.save()} (catches anything that queues a write
 * directly). Real saves carry a real path and are untouched.
 */
public final class SandboxWrites {

    private SandboxWrites() {
    }

    private static volatile int dropped;
    private static volatile int logged;

    /** {@code Prefs.flush()} on a redirected target: serialization and the queue are skipped. */
    @SpirePatch(clz = Prefs.class, method = "flush")
    public static final class FlushPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(Prefs __instance) {
            try {
                if (__instance != null && isSandboxTarget(__instance.filepath)) {
                    note(__instance.filepath);
                    return SpireReturn.Return(null);
                }
            } catch (Throwable t) {
                if (logged++ < 4) {
                    Log.warn("sandbox writes: the flush guard failed: " + t);
                }
            }
            return SpireReturn.Continue();
        }
    }

    /** Belt and braces: a writer that queues a sandbox path directly never reaches the queue. */
    @SpirePatch(clz = AsyncSaver.class, method = "save")
    public static final class SavePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(String filepath, String data) {
            try {
                if (isSandboxTarget(filepath)) {
                    note(filepath);
                    return SpireReturn.Return(null);
                }
            } catch (Throwable t) {
                if (logged++ < 4) {
                    Log.warn("sandbox writes: the save guard failed: " + t);
                }
            }
            return SpireReturn.Continue();
        }
    }

    /**
     * Every path the sandbox writes to, not just preferences: {@link ExternalMute} moves
     * {@code saves/...} and {@code runs/...} under the same {@code sts1solver-sandbox/} root, and a
     * simulated autosave or run-history write must not queue either.
     */
    static boolean isSandboxTarget(String filepath) {
        if (filepath == null) {
            return false;
        }
        if (SandboxPrefs.owns(filepath)) {
            return true;
        }
        return filepath.replace('\\', '/').startsWith("sts1solver-sandbox/");
    }

    /**
     * B136 follow-up (m08397): counting was all this needed. The probes that asked WHICH achievement
     * site queued the writes measured it -- with the player holding 10+ BUFF powers every
     * {@code ApplyPowerAction} runs the POWERFUL check and flushes unconditionally (167 drops in 564
     * search nodes, plus one per monster slain). Walking a stack trace on every drop is far too
     * expensive for a per-node path, so the trace was removed again.
     */
    private static void note(String filepath) {
        ++dropped;
        if (logged++ < 3) {
            Log.info("sandbox writes: dropped a sandbox preference save (" + filepath
                    + "); a simulated fight must not touch the player's files or the save queue");
        }
    }

    /** How many sandbox-targeted saves were dropped (gate and health line). */
    public static int dropped() {
        return dropped;
    }

    public static void resetForTest() {
        dropped = 0;
        logged = 0;
    }
}