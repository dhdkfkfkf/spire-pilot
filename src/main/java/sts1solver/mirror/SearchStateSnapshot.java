package sts1solver.mirror;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately conservative capture of the currently available combat identity.
 *
 * <p>The legacy {@link Digest} is useful for diagnostics and the current search key, but it is not
 * a complete serialization of every mutable game/mod field. This adapter therefore ALWAYS emits an
 * inexact key. It is suitable for diagnostics and shadow comparisons only; callers must not use
 * {@link SearchStateKey#canMerge(SearchStateKey)} to merge these snapshots.
 *
 * <p>Keeping the capture in one place makes the incompleteness explicit while the built-in field
 * inventory and contributor registry are developed. It reuses the existing digest and RNG readers
 * rather than maintaining a second object graph.
 */
public final class SearchStateSnapshot {
    private static final int SCHEMA_VERSION = 1;

    public final SearchStateKey key;
    public final String diagnosticDigest;
    public final String rngStamp;

    private SearchStateSnapshot(SearchStateKey key, String diagnosticDigest, String rngStamp) {
        this.key = key;
        this.diagnosticDigest = diagnosticDigest;
        this.rngStamp = rngStamp;
    }

    /**
     * Capture the current combat using existing readers.
     *
     * <p>This is intentionally not an exact snapshot: Digest omits behavior-relevant mutable fields
     * (for example relic counters and orb amounts), and mod-owned state is not yet enumerated.
     */
    public static SearchStateSnapshot captureCurrent() {
        String rawDigest = Digest.ofCurrent();
        String stableDigest = Digest.stable(rawDigest);
        String rng = RngStamp.of();

        CanonicalStateWriter writer = new CanonicalStateWriter();
        writer.putString(0, stableDigest);
        writer.putCanonical(1, RngStamp.canonicalBytes());

        List<String> unknown = new ArrayList<String>();
        unknown.add("legacy-digest-is-not-a-complete-behavior-state-snapshot");
        unknown.add("object-valued-fields-and-nested-mod-state-are-not-fully-traversed");
        unknown.add("mod-owned-mutable-state-is-not-fully-enumerated");
        BuiltInStateFingerprint.append(writer, 2, unknown);
        if (rawDigest != null && rawDigest.indexOf("unreadable:") >= 0) {
            unknown.add("legacy-digest-reported-unreadable-state");
        }

        SearchStateKey key = new SearchStateKey(
                SCHEMA_VERSION, writer.toByteArray(), false, unknown);
        return new SearchStateSnapshot(key, stableDigest, rng);
    }
}
