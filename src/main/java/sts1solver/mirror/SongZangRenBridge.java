package sts1solver.mirror;

import sts1solver.util.Log;

import java.lang.reflect.Method;

/**
 * B200 P2: the one link the search cannot provide by itself. 送葬人 skips its counter choice while a
 * mirror is installed, so the sandbox never opens the GRID and cannot branch on it. The mod exposes
 * a default-off capability; Spire Pilot pushes it before every search and nothing else sets it.
 *
 * <p>The bridge is deliberately reflective: no compile-time dependency on the mod, no-op for older
 * versions, and the live game keeps working when the setter is absent. The flag is pushed on every
 * search (not only on change) so a mod that resets it per combat still finds it armed; a setter that
 * exists but throws is dropped and retried next search, never propagated.
 */
public final class SongZangRenBridge {

    private static final String COMPAT = "songzangren.compat.SpirePilotCompat";
    private static Method setter;
    private static boolean resolved;
    private static Boolean pushed;
    private static boolean absentLogged;
    private static boolean failureLogged;

    // 2026-11-21, the author's second contract: 送葬人's pending settle work lives in the mod's own
    // queue, not in a power, so the digest could not tell two boards apart that differ only in it.
    // Both probes are read every digest / every settle poll, so an absent interface (no mod, or an
    // older author version) resolves once and then answers null without another lookup.
    private static Method workProbe;
    private static Method depthProbe;
    private static boolean queueResolved;
    private static boolean queueFailureLogged;
    private static boolean queueAbsentLogged;

    private SongZangRenBridge() {
    }

    public static void push() {
        boolean on = RolloutChoices.enabled();
        Method m = setter();
        if (m == null) {
            logAbsentOnce();
            return;
        }
        try {
            m.invoke(null, Boolean.valueOf(on));
            if (pushed == null || pushed.booleanValue() != on) {
                Log.info("songzangren bridge: mirror choices " + (on ? "on" : "off"));
            }
            pushed = Boolean.valueOf(on);
            failureLogged = false;
        } catch (Throwable t) {
            // A capability bit must never take a search or the live game down. Forget the method so
            // the next search re-resolves it, and say once what the author's setter did wrong.
            setter = null;
            resolved = false;
            pushed = null;
            if (!failureLogged) {
                failureLogged = true;
                Log.warn("songzangren bridge: setMirrorChoices(" + on + ") failed: " + t);
            }
        }
    }

    /**
     * The author's queue probe: true while the mod still has settle work queued. Null when the mod
     * or the interface is absent -- callers must treat null as "cannot tell", never as false.
     */
    public static Boolean hasPendingWork() {
        resolveQueueProbes();
        if (workProbe == null) {
            return null;
        }
        try {
            Object v = workProbe.invoke(null);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            dropQueueProbes("hasPendingSettleWork() failed: " + t);
            return null;
        }
    }

    /**
     * The author's depth probe: how many effects are still queued. Null when absent. This is what
     * the digest carries, so two boards that differ only in that queue no longer merge.
     */
    public static Integer pendingDepth() {
        resolveQueueProbes();
        if (depthProbe == null) {
            return null;
        }
        try {
            Object v = depthProbe.invoke(null);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            dropQueueProbes("pendingSettleDepth() failed: " + t);
            return null;
        }
    }

    private static void resolveQueueProbes() {
        if (queueResolved) {
            return;
        }
        queueResolved = true;
        try {
            Class<?> compat = Class.forName(COMPAT);
            try {
                workProbe = compat.getMethod("hasPendingSettleWork");
                workProbe.setAccessible(true);
            } catch (Throwable ignored) {
                workProbe = null;
            }
            try {
                depthProbe = compat.getMethod("pendingSettleDepth");
                depthProbe.setAccessible(true);
            } catch (Throwable ignored) {
                depthProbe = null;
            }
            if ((workProbe != null || depthProbe != null) && !queueAbsentLogged) {
                queueAbsentLogged = true;
                Log.info("songzangren bridge: pending-settle probes ready; the digest sees the queue");
            }
        } catch (Throwable t) {
            workProbe = null;
            depthProbe = null;
        }
    }

    private static void dropQueueProbes(String why) {
        workProbe = null;
        depthProbe = null;
        queueResolved = false;
        if (!queueFailureLogged) {
            queueFailureLogged = true;
            Log.warn("songzangren bridge: " + why);
        }
    }

    private static Method setter() {
        if (resolved) {
            return setter;
        }
        resolved = true;
        try {
            Class<?> compat = Class.forName(COMPAT);
            try {
                setter = compat.getMethod("setMirrorChoices", boolean.class);
            } catch (NoSuchMethodException primitiveMiss) {
                setter = compat.getMethod("setMirrorChoices", Boolean.class);
            }
            setter.setAccessible(true);
        } catch (Throwable t) {
            setter = null;
        }
        return setter;
    }

    private static void logAbsentOnce() {
        if (!absentLogged) {
            absentLogged = true;
            Log.info("songzangren bridge: no mirrorChoices capability; B200 live fallback stays");
        }
    }

    public static String stateForTest() {
        return pushed == null ? "unset" : (pushed.booleanValue() ? "on" : "off");
    }

    public static void resetForTest() {
        setter = null;
        resolved = false;
        pushed = null;
        absentLogged = false;
        failureLogged = false;
        workProbe = null;
        depthProbe = null;
        queueResolved = false;
        queueFailureLogged = false;
        queueAbsentLogged = false;
    }
}
