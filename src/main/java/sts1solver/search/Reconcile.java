package sts1solver.search;

import sts1solver.util.Log;

/**
 * Reconciliation switch for fast lanes (mirrors the reference solver's FastLaneVerification).
 *
 * <p>A fast lane replaces an expensive general path with a cheap one that is SUPPOSED to give the
 * same answer. "Supposed to" is not evidence, so every such lane gets an opt-in mode that re-runs
 * the expensive path alongside it and throws on the first divergence. The checks are too slow to
 * leave on, so they are enabled per run through {@code -Dsts1solver.verify.fastLanes=1}, read once.
 *
 * <p>Deliberately throws instead of warning: a divergence that only logs gets scrolled away, and a
 * fast lane that silently disagrees with the real path is exactly the bug this protects against.
 */
public final class Reconcile {

    private Reconcile() {
    }

    private static Boolean enabled;

    /** Clears the cached switch (for tests that toggle it). */
    public static synchronized void reset() {
        enabled = null;
    }

    /** Opt-in, read once. */
    public static synchronized boolean enabled() {
        if (enabled == null) {
            boolean on;
            try {
                on = "1".equals(System.getProperty("sts1solver.verify.fastLanes", "0").trim());
            } catch (Throwable t) {
                on = false;
            }
            enabled = Boolean.valueOf(on);
        }
        return enabled.booleanValue();
    }

    /** Two values from the cheap path and the reference path, compared exactly. */
    public static void same(String lane, int fast, int reference) {
        if (fast != reference) {
            String message = "fast lane divergence in " + lane + ": cheap=" + fast
                    + " reference=" + reference;
            Log.error(message, null);
            throw new IllegalStateException(message);
        }
    }

    /** One-line log of a reconciled lane; only called when the switch is on. */
    public static void report(String lane, String cheap, String reference) {
        Log.info("RECONCILE " + lane + " cheap=" + cheap + " reference=" + reference);
    }
}