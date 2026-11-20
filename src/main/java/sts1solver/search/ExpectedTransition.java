package sts1solver.search;

import sts1solver.mirror.SearchStateKey;

/**
 * Evidence recorded for one simulated edge. Missing or inexact keys weaken the evidence; they do
 * not silently become a verified transition.
 */
public final class ExpectedTransition {
    public enum DrainStatus {
        SETTLED,
        AWAITING_SELECTION,
        REFUSED,
        TIMED_OUT,
        STALLED,
        NOT_SETTLED,
        ERROR,
        UNKNOWN
    }

    public final SearchStateKey preState;
    public final SearchStateKey postState;
    public final String preRngStamp;
    public final String postRngStamp;
    public final int turnBefore;
    public final int turnAfter;
    public final DrainStatus drainStatus;
    public final String screenFingerprint;
    public final boolean exact;

    public ExpectedTransition(SearchStateKey preState, SearchStateKey postState,
                              String preRngStamp, String postRngStamp,
                              int turnBefore, int turnAfter, DrainStatus drainStatus,
                              String screenFingerprint, boolean exact) {
        this.preState = preState;
        this.postState = postState;
        this.preRngStamp = preRngStamp == null ? "" : preRngStamp;
        this.postRngStamp = postRngStamp == null ? "" : postRngStamp;
        this.turnBefore = turnBefore;
        this.turnAfter = turnAfter;
        this.drainStatus = drainStatus == null ? DrainStatus.UNKNOWN : drainStatus;
        this.screenFingerprint = screenFingerprint == null ? "" : screenFingerprint;
        this.exact = exact && preState != null && postState != null
                && preState.exact && postState.exact
                && this.drainStatus == DrainStatus.SETTLED;
    }

    /** Whether this edge carries enough evidence to claim an exact replay comparison. */
    public boolean isReplayComparable() {
        return exact;
    }
}
