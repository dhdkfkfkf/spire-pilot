package sts1solver.mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import sts1solver.drive.Driver;
import sts1solver.util.Log;

/**
 * B109 stage 2 (policy A, user m04965): decide a candidate field by EXPERIMENT, not by its type.
 *
 * <p>A field descriptor cannot tell combat state from a registry: the same {@code static final
 * ArrayList} may be "the cards this card put aside" (must be per-fork) or "every card the mod
 * registered" (must stay shared). So the only sound question is behavioural: <em>does isolating this
 * field change the outcome of the same action?</em>
 *
 * <p>For every candidate the sandbox was actually seen writing ({@link StaticWatch#dirtyFields()}):
 * run one probe action (end the turn) with the field shared, register the field, run the same probe
 * again, and keep the registration only when the two results differ. No difference means isolation
 * would buy nothing, so the field stays shared -- that is the cheap policy the user picked.
 *
 * <p>Cost: two probes per candidate, and only when a candidate exists (usually none). A probe is one
 * sandboxed end-turn, a few milliseconds.
 */
public final class StaticCalibrate {

    private StaticCalibrate() {
    }

    private static volatile int lastCandidates;
    private static volatile int lastRegistered;
    private static volatile String lastReport = "static calibration: not run";

    // ---- stage 3: probation ----------------------------------------------------------------
    /*
     * A field registered by stage 2 is on probation until a search has run with it. Stage 3 does not
     * ask "does this field matter" (stage 2 answered that) -- it asks "does isolating it break the
     * simulation". The evidence is the mirror's own parity counter: every promoted line is rebuilt
     * and compared with the state it was parked on (B101); a field that breaks identity semantics
     * (a registry isolated as if it were combat state) makes those rebuilds stop matching.
     *
     * One mismatch after registration is enough to revert: reverting returns to the old behaviour
     * (shared), which is the status quo, while keeping a bad registration would silently corrupt
     * every later search. Reverted fields are blacklisted for this process.
     */
    private static final java.util.List<Field> probation = new ArrayList<Field>();
    private static final java.util.Map<String, Integer> probationMismatch =
            new java.util.HashMap<String, Integer>();
    private static final java.util.Set<String> blacklist = new java.util.HashSet<String>();

    /** Runs before a search captures its root world (a world captured earlier cannot be installed). */
    public static void beforeSearch() {
        // Stage 3 first: judge the fields the PREVIOUS search ran with, before adding new ones.
        evaluateProbation(sts1solver.search.Solver.promotionMismatchForTest());
        List<Field> candidates;
        try {
            candidates = StaticWatch.dirtyFields();
        } catch (Throwable t) {
            return;
        }
        if (candidates.isEmpty()) {
            lastCandidates = 0;
            lastReport = "static calibration: no candidate";
            return;
        }
        long started = System.currentTimeMillis();
        List<String> kept = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        int probed = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Field f = candidates.get(i);
            String key = f.getDeclaringClass().getName() + '#' + f.getName();
            if (blacklist.contains(key)) {
                // Stage 3: a field that already broke the rebuild parity once is not tried again in
                // this process. Without this the blacklist would be decoration: the field stays
                // dirty, so the next calibration would probe it, register it and revert it forever.
                skipped.add(key + " (blacklisted after a failed probation)");
                continue;
            }
            try {
                Statics.Kind kind = kindOf(f);
                if (kind == null) {
                    skipped.add(key + " (cannot be isolated: " + f.getType().getSimpleName() + ")");
                    continue;
                }
                String shared = probe();
                if (shared == null) {
                    skipped.add(key + " (probe did not settle)");
                    continue;
                }
                if (!Statics.registerDynamic(f, kind)) {
                    skipped.add(key + " (already mirrored)");
                    continue;
                }
                ++probed;
                String isolated = probe();
                if (isolated != null && isolated.equals(shared)) {
                    Statics.unregisterDynamic(f);
                    skipped.add(key + " (isolation changes nothing)");
                    continue;
                }
                if (isolated == null) {
                    Statics.unregisterDynamic(f);
                    skipped.add(key + " (isolated probe did not settle)");
                    continue;
                }
                // Stage 3: the isolation must be deterministic before it is trusted. A second
                // isolated probe that disagrees with the first means the field's contents are not
                // reproducibly clonable (a shared registry, a lazily filled cache): keep it shared.
                String again = probe();
                if (again == null || !again.equals(isolated)) {
                    Statics.unregisterDynamic(f);
                    skipped.add(key + " (isolation is not deterministic)");
                    continue;
                }
                kept.add(key);
                probation.add(f);
                probationMismatch.put(key, Integer.valueOf(
                        sts1solver.search.Solver.promotionMismatchForTest()));
                Log.warn("static calibration: " + key + " changes the result when isolated (" + kind
                        + "); the mirror owns it, probation on (stage 3 watches the rebuild parity)"
                        + " -- it was live-shared and sibling forks leaked into each other");
            } catch (Throwable t) {
                Statics.unregisterDynamic(f);
                skipped.add(key + " (calibration failed: " + t + ")");
            }
        }
        lastCandidates = candidates.size();
        lastRegistered = kept.size();
        lastReport = "static calibration: " + candidates.size() + " candidate(s), " + probed
                + " probed, " + kept.size() + " registered" + (kept.isEmpty() ? "" : " " + kept)
                + (skipped.isEmpty() ? "" : ", skipped " + skipped)
                + " (" + (System.currentTimeMillis() - started) + "ms)";
        Log.info(lastReport);
    }

    /** The mirror kind that can own this field, or null when it cannot be isolated. */
    private static Statics.Kind kindOf(Field f) {
        if (f == null || !Modifier.isStatic(f.getModifiers())) {
            return null;
        }
        Class<?> t = f.getType();
        if (t.isPrimitive() || t.isEnum() || t == String.class) {
            return null;
        }
        boolean container = java.util.Collection.class.isAssignableFrom(t)
                || java.util.Map.class.isAssignableFrom(t) || t.isArray();
        if (Modifier.isFinal(f.getModifiers())) {
            // A static final reference cannot be swapped; only a container can be rewritten in place.
            return container ? Statics.Kind.MUTABLE_CONTAINER : null;
        }
        return container ? Statics.Kind.MUTABLE_CONTAINER : Statics.Kind.CLONE;
    }

    /** One sandboxed end-turn from the current live state, as a stable digest. */
    private static String probe() {
        World w = null;
        boolean installed = false;
        Driver.begin();
        try {
            w = World.captureLive();
            w.install();
            installed = true;
            Driver.endTurn();
            return Digest.stable(Digest.ofCurrent());
        } catch (Throwable t) {
            World.forceRestore();
            return null;
        } finally {
            try {
                if (installed) {
                    w.uninstall();
                }
            } catch (Throwable ignored) {
                World.forceRestore();
            }
            try {
                Driver.end();
            } catch (Throwable ignored) {
                // the driver window is best-effort here; the world is already restored
            }
        }
    }

    /**
     * Stage 3: reverts a probationary registration when the rebuild parity degraded since it was made.
     *
     * @param mismatchNow the mirror's current promotionMismatch counter
     * @return how many fields were reverted and blacklisted
     */
    public static int evaluateProbation(int mismatchNow) {
        if (probation.isEmpty()) {
            return 0;
        }
        int reverted = 0;
        for (int i = probation.size() - 1; i >= 0; i--) {
            Field f = probation.get(i);
            String key = f.getDeclaringClass().getName() + '#' + f.getName();
            Integer at = probationMismatch.get(key);
            boolean broke = at == null || mismatchNow > at.intValue();
            if (!broke) {
                continue;
            }
            Statics.unregisterDynamic(f);
            probation.remove(i);
            probationMismatch.remove(key);
            blacklist.add(key);
            ++reverted;
            Log.warn("static calibration: " + key + " was reverted -- with it in the mirror a rebuilt"
                    + " line no longer matched the parked state (parity " + (at == null ? "?" : at)
                    + " -> " + mismatchNow + "), so isolating it breaks the simulation; it stays"
                    + " shared and is blacklisted for this process");
        }
        if (reverted > 0) {
            lastReport = "static calibration: " + reverted + " probationary field(s) reverted"
                    + " (parity), blacklisted " + blacklist;
        }
        return reverted;
    }

    /** Stage 3 gate hook: put a field on probation without running a search for it. */
    public static void adoptForTest(Field f) {
        if (f == null) {
            return;
        }
        String key = f.getDeclaringClass().getName() + '#' + f.getName();
        probation.add(f);
        probationMismatch.put(key, Integer.valueOf(
                sts1solver.search.Solver.promotionMismatchForTest()));
    }

    /** Stage 3 gate hook: is this field blacklisted? */
    public static boolean isBlacklisted(String key) {
        return blacklist.contains(key);
    }

    /** Stage 3 gate hook: how many fields are on probation right now. */
    public static int probationForTest() {
        return probation.size();
    }

    /** Stage 3 gate hook: the fields reverted and blacklisted in this process. */
    public static java.util.List<String> blacklistForTest() {
        return new ArrayList<String>(blacklist);
    }

    /** Test hook: forget probation state and the blacklist. */
    public static void resetForTest() {
        probation.clear();
        probationMismatch.clear();
        blacklist.clear();
        lastCandidates = 0;
        lastRegistered = 0;
        lastReport = "static calibration: not run";
    }

    /** Last calibration summary (log/panel/gate). */
    public static String report() {
        return lastReport;
    }

    /** How many candidates the last calibration looked at. */
    public static int candidatesForTest() {
        return lastCandidates;
    }

    /** How many fields the last calibration took into the mirror. */
    public static int registeredForTest() {
        return lastRegistered;
    }
}
