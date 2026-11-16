package sts1solver.mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sound.sampled.Clip;
import sts1solver.util.Log;

/**
 * B187 (user m01082/m01098): a sandbox window must not run a mod's audio at all.
 *
 * <p>B185 (this class, first version) let the window run mod audio and then closed the clips the
 * window left in a watched static. That closed the "same tune stacking up" symptom, but the window
 * was still really executing {@code AudioSystem.getClip()}/{@code Line.open}, and its cleanup could
 * write an already-closed live clip back into the mod field. The next window then called
 * {@code stop()} on it, YuAi's {@code stop()} goes straight to {@code flush()}, and DirectSound died
 * with an access violation inside {@code DirectAudioDevice.nFlush} (hs_err_pid17280, 2026-11-15
 * 21:22:02).
 *
 * <p>The current contract is the reverse of B185's: at {@link #begin()} every watched static is
 * replaced with a {@link SandboxClip} stand-in, so the mod's own {@code play()} early-outs
 * ({@code currentClip != null}) and not a single line is opened inside the window (acceptance G1),
 * and the window's {@code stop()/pause()} only ever reach the stand-in (G2/I2). At {@link #end()}
 * the recorded live reference is written back, but only after {@code isOpen()} says the line is
 * still alive; a reference that really died while the window was open is replaced with
 * {@code null} instead of being handed back as a dead line.
 *
 * <p>A bare release (the mod's {@code LineListener} sets the field to {@code null} on the audio
 * thread) is now restored as well: B169's "release stays released" gave the mod a field that no
 * longer matched the live game, and the window boundary must be invisible. Only a live clip that is
 * really closed is treated as gone.
 *
 * <p>Boundaries, stated honestly:
 * <ul>
 * <li>Only STATIC fields whose declared type is {@code Clip} are watched. A clip in an instance
 * field, a collection or an array is missed, and so is a field declared {@code Object}.</li>
 * <li>A {@code final} clip static cannot be swapped; the window can still call {@code stop()} on
 * it, which is detected ({@link #liveStopsForTest()}) but cannot be undone from here.</li>
 * <li>A clip the sandbox creates and drops without ever assigning it to a watched field is
 * invisible.</li>
 * <li>A window that never ends leaves the stand-in in place; the next {@code begin()} says so,
 * treats the live value as unknown and clears the field at {@code end()} rather than looping the
 * stand-in into the real game.</li>
 * </ul>
 */
public final class AudioQuiet {

    private AudioQuiet() {
    }

    /** How many field names a log line carries before it says "+N more". */
    private static final int NAME_LIMIT = 8;

    private static final class Snap {
        /** The live reference the field held at install; {@code null} if it was empty or stale. */
        final Object value;
        /** Whether the live clip was running at install: used to detect a sandbox stop(). */
        final boolean running;
        /** Whether the live clip was open at install: used to explain a dead reference. */
        final boolean open;

        Snap(Object value, boolean running, boolean open) {
            this.value = value;
            this.running = running;
            this.open = open;
        }
    }

    private static List<Field> all;
    private static final List<Field> testFields = new ArrayList<Field>();
    private static final Map<Field, Snap> snaps = new IdentityHashMap<Field, Snap>();
    private static final Set<String> named = new LinkedHashSet<String>();
    private static final List<String> closedForTest = new ArrayList<String>();
    private static final List<String> blind = new ArrayList<String>();
    private static int windows;
    private static int swapped;
    private static int stale;
    private static int closed;
    private static int cleared;
    private static int putBack;
    private static int released;
    private static int closedSnapshot;
    private static int liveStops;

    // ------------------------------------------------------------------ window hooks

    /**
     * Called from {@link World#install()} AFTER {@link StaticWatch#afterInstall()}: the watch must
     * snapshot the LIVE clip, never the stand-in, or it would write the stand-in into the real game
     * when the window ends. Then every watched field is parked on the stand-in.
     */
    public static synchronized void begin() {
        ++windows;
        if (!snaps.isEmpty()) {
            // A previous window never ended (a leaked world). Put its references back before this
            // one starts, or the leak would be attributed to the new window.
            Log.warnEvery("audio-quiet-stacked", 5000L,
                    "audio quiet: a previous window never ended; restoring what it left behind");
            end();
        }
        List<Field> fields = all();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            Object v;
            try {
                v = f.get(null);
            } catch (Throwable t) {
                Log.warnEvery("audio-quiet-snap-" + key(f), 30000L,
                        "audio quiet: cannot read " + key(f) + ": " + t);
                continue;
            }
            if (SandboxClip.isSandbox(v)) {
                ++stale;
                // The live value is unknowable: the field has held the stand-in since a window that
                // never ended. Clearing it at end() is the only honest choice.
                snaps.put(f, new Snap(null, false, false));
                Log.warnEvery("audio-quiet-stale-" + key(f), 30000L, "audio quiet: " + key(f)
                        + " still holds the stand-in left by a window that never ended; the live"
                        + " reference is unknown and the field will be cleared instead of restored");
                continue;
            }
            boolean clip = v instanceof Clip;
            snaps.put(f, new Snap(v, clip && running(v), clip && open(v)));
            if (Modifier.isFinal(f.getModifiers())) {
                if (clip) {
                    Log.warnEvery("audio-quiet-final-" + key(f), 30000L, "audio quiet: " + key(f)
                            + " is final and holds a live clip, so the window cannot swap in the"
                            + " stand-in; only a stop() on that clip itself is left to be detected");
                }
                continue;
            }
            if (clip) {
                SandboxClip.adoptFormat((Clip) v);
            }
            try {
                // Also parked over an empty field: a mod that sees null would open a real line on
                // its next play() inside the window, which is exactly what G1 forbids.
                f.set(null, SandboxClip.shared());
                ++swapped;
            } catch (Throwable t) {
                Log.warnEvery("audio-quiet-swap-" + key(f), 30000L,
                        "audio quiet: cannot swap in the stand-in for " + key(f) + ": " + t);
            }
        }
    }

    /**
     * Called from {@link World#uninstall()} BEFORE {@link StaticWatch#beforeUninstall()}: the watch
     * must compare against the live reference, not the stand-in.
     */
    public static synchronized void end() {
        if (snaps.isEmpty()) {
            return;
        }
        try {
            // Every value the live game held: a window may alias one of them into a second field,
            // and that object must never be closed.
            Set<Object> liveValues = Collections.newSetFromMap(
                    new IdentityHashMap<Object, Boolean>());
            for (Snap s : snaps.values()) {
                if (s.value != null) {
                    liveValues.add(s.value);
                }
            }
            Set<Object> closedNow = Collections.newSetFromMap(
                    new IdentityHashMap<Object, Boolean>());
            List<String> closedNames = new ArrayList<String>();
            List<String> restoredNames = new ArrayList<String>();
            for (Map.Entry<Field, Snap> e : snaps.entrySet()) {
                Field f = e.getKey();
                Snap s = e.getValue();
                Object now;
                try {
                    now = f.get(null);
                } catch (Throwable t) {
                    Log.warnEvery("audio-quiet-read-" + key(f), 30000L,
                            "audio quiet: cannot compare " + key(f) + ": " + t);
                    continue;
                }
                if (now != s.value && now instanceof Clip && !SandboxClip.isSandbox(now)
                        && !liveValues.contains(now) && closedNow.add(now)) {
                    // A line the window opened itself: nobody else can ever close it.
                    closeQuietly((Clip) now, f);
                    ++closed;
                    closedNames.add(key(f));
                    if (closedForTest.size() < 64) {
                        closedForTest.add(key(f));
                    }
                }
                if (now == s.value) {
                    // The reference did not move. On a final field the window may still have called
                    // stop() on the live clip through it, which no field compare can see.
                    if (s.running && !running(now)) {
                        ++liveStops;
                        Log.warnEvery("audio-quiet-live-stop-" + key(f), 30000L,
                                "audio quiet: the sandbox stopped the LIVE clip behind " + key(f)
                                        + " without reassigning the field; the live track cannot be"
                                        + " resumed from here (B187 boundary).");
                    }
                    continue;
                }
                if (now == null && s.value != null) {
                    // B169 used to leave this released. A window boundary must be invisible, and the
                    // mod's LineListener only fires for a clip that really stopped -- the field it
                    // cleared is the live one, so it is put back (or dropped if the line died).
                    ++released;
                    Log.warnEvery("audio-quiet-release-" + key(f), 30000L, "audio quiet: " + key(f)
                            + " was released (set to null) while a window was open; the live"
                            + " reference is put back (B187, overturns B169).");
                }
                Object back = s.value;
                if (back instanceof Clip && !open((Clip) back)) {
                    ++closedSnapshot;
                    Log.warnEvery("audio-quiet-dead-" + key(f), 30000L, "audio quiet: the live clip"
                            + " behind " + key(f) + " was closed while the window was open; writing"
                            + " null instead of putting a dead line back (B187).");
                    back = null;
                }
                if (Modifier.isFinal(f.getModifiers())) {
                    Log.warnEvery("audio-quiet-final-" + key(f), 30000L, "audio quiet: " + key(f)
                            + " is final, so the reference the window left cannot be put back");
                    continue;
                }
                try {
                    if (back == null) {
                        if (now != null) {
                            f.set(null, null);
                            ++cleared;
                        }
                    } else {
                        f.set(null, back);
                        ++putBack;
                        restoredNames.add(key(f));
                    }
                } catch (Throwable t) {
                    Log.warnEvery("audio-quiet-put-" + key(f), 30000L,
                            "audio quiet: cannot put the live value of " + key(f) + " back: " + t);
                }
            }
            snaps.clear();
            if (!closedNames.isEmpty() || !restoredNames.isEmpty()) {
                Log.warnEvery("audio-quiet-window", 30000L, "audio quiet: window closed"
                        + (closedNames.isEmpty() ? "" : " " + closedNames.size()
                                + " sandbox clip(s)")
                        + (restoredNames.isEmpty() ? "" : " and restored "
                                + restoredNames.size() + " live reference(s)")
                        + ": " + summarize(closedNames.isEmpty() ? restoredNames : closedNames));
            }
        } finally {
            snaps.clear();
        }
    }

    /** One line for the gates / the search summary. */
    public static synchronized String report() {
        return "audio quiet: " + all().size() + " mod clip static(s), " + windows
                + " window(s), " + swapped + " stand-in swap(s), " + closed
                + " sandbox clip(s) closed, " + putBack + " live reference(s) put back"
                + (cleared == 0 ? "" : ", " + cleared + " field(s) cleared")
                + (released == 0 ? "" : ", " + released + " release(s) restored")
                + (closedSnapshot == 0 ? "" : ", " + closedSnapshot + " closed live clip(s) dropped")
                + (stale == 0 ? "" : ", " + stale + " stale stand-in(s)")
                + (liveStops == 0 ? "" : ", " + liveStops + " live stop(s) detected");
    }

    // ------------------------------------------------------------------ close

    private static void closeQuietly(Clip clip, Field f) {
        // Every call is guarded on its own AND on isOpen(): a stop()/flush() on a line whose native
        // peer is already gone is what crashed the JVM in B187. A close() is still attempted even
        // when isOpen() says false, because it is the call that releases the Java-side resources.
        try {
            if (clip.isOpen()) {
                clip.stop();
            }
        } catch (Throwable ignored) {
            // a line that was never opened has nothing to stop
        }
        try {
            if (clip.isOpen()) {
                clip.flush();
            }
        } catch (Throwable ignored) {
            // not every clip can be flushed; close still frees it
        }
        try {
            clip.close();
        } catch (Throwable t) {
            Log.warnEvery("audio-quiet-close-" + key(f), 30000L,
                    "audio quiet: cannot close the sandbox clip in " + key(f) + ": " + t);
        }
        if (named.add(key(f))) {
            Log.warn("audio quiet: " + key(f) + " pointed at a clip the window created; it was"
                    + " closed. The stand-in keeps every later window from opening one (B187).");
        }
    }

    private static boolean running(Object v) {
        if (!(v instanceof Clip)) {
            return false;
        }
        try {
            return ((Clip) v).isRunning();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean open(Object v) {
        if (!(v instanceof Clip)) {
            return false;
        }
        try {
            return ((Clip) v).isOpen();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String key(Field f) {
        return f.getDeclaringClass().getName() + '#' + f.getName();
    }

    private static String summarize(List<String> names) {
        if (names.size() <= NAME_LIMIT) {
            return names.toString();
        }
        return names.subList(0, NAME_LIMIT) + " and " + (names.size() - NAME_LIMIT) + " more";
    }

    private static List<Field> all() {
        if (all != null) {
            return all;
        }
        List<Field> out = new ArrayList<Field>();
        List<Field> candidates;
        try {
            candidates = ModStatics.watchCandidates();
        } catch (Throwable t) {
            candidates = new ArrayList<Field>();
        }
        for (int i = 0; i < candidates.size(); i++) {
            Field f = candidates.get(i);
            // Declared type only: initialising every mod class to look for a live Clip is exactly
            // what ModStatics avoids doing on a sweep.
            if (!Clip.class.isAssignableFrom(f.getType())) {
                continue;
            }
            // The measured field is PRIVATE static (YuAiMusic#currentClip): without setAccessible the
            // read throws IllegalAccessException and the whole watch silently protects nothing.
            // Drop what cannot be opened and say so -- a blind field must never look like a guarded
            // one (found the hard way in the S255 -Mods YuAiBloodMusic run, 2026-11-15).
            try {
                f.setAccessible(true);
                out.add(f);
            } catch (Throwable t) {
                blind.add(key(f) + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        out.addAll(testFields);
        all = out;
        if (!blind.isEmpty()) {
            Log.warnEvery("audio-quiet-blind", 30000L, "audio quiet: " + blind.size()
                    + " clip static(s) cannot be opened for reflection and are NOT guarded: " + blind);
        }
        if (!out.isEmpty()) {
            List<String> keys = new ArrayList<String>();
            for (int i = 0; i < out.size(); i++) {
                keys.add(key(out.get(i)));
            }
            Log.info("audio quiet: " + out.size() + " mod static(s) hold a javax.sound clip and are"
                    + " replaced by a stand-in per window (B187): " + keys);
        }
        return out;
    }

    // ------------------------------------------------------------------ gates

    /** Registers a field the gate controls (B187 gate S255). */
    public static synchronized void watchForTest(Field f) {
        testFields.add(f);
        all = null;
    }

    /** Whether a watched field can really be read: a discovered-but-locked field guards nothing. */
    public static synchronized boolean readableForTest(String key) {
        List<Field> fields = all();
        for (int i = 0; i < fields.size(); i++) {
            if (key.equals(key(fields.get(i)))) {
                try {
                    fields.get(i).get(null);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Fields discovered but not reflection-accessible in this run (B185). */
    public static synchronized List<String> blindForTest() {
        if (all == null) {
            all();
        }
        return new ArrayList<String>(blind);
    }

    public static synchronized List<String> watchedForTest() {
        List<Field> fields = all();
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < fields.size(); i++) {
            out.add(key(fields.get(i)));
        }
        return out;
    }

    public static synchronized int swappedForTest() {
        return swapped;
    }

    public static synchronized int closedForTest() {
        return closed;
    }

    public static synchronized int clearedForTest() {
        return cleared;
    }

    public static synchronized int putBackForTest() {
        return putBack;
    }

    public static synchronized int releasedForTest() {
        return released;
    }

    public static synchronized int closedSnapshotForTest() {
        return closedSnapshot;
    }

    public static synchronized int staleForTest() {
        return stale;
    }

    public static synchronized int liveStopsForTest() {
        return liveStops;
    }

    public static synchronized void resetForTest() {
        all = null;
        testFields.clear();
        snaps.clear();
        named.clear();
        closedForTest.clear();
        blind.clear();
        windows = 0;
        swapped = 0;
        stale = 0;
        closed = 0;
        cleared = 0;
        putBack = 0;
        released = 0;
        closedSnapshot = 0;
        liveStops = 0;
    }
}
