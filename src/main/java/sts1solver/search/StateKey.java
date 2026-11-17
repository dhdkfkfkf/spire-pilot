package sts1solver.search;

/**
 * A 128-bit fingerprint of a visited position key.
 *
 * <p>The search keys its visited set and its per-layer merge table on the canonical position key,
 * which is a long descriptive string. Using the string itself meant every lookup compared hundreds
 * of characters and every entry kept a second reference to it. Two independent 64-bit FNV-1a passes
 * (different seeds and step offsets, both finished with a splitmix64 avalanche) give a 128-bit key:
 * the chance of two DIFFERENT boards colliding within one search is about 2^-128 per pair, which is
 * far below the chance of a hardware fault, so the visited set keeps its exact meaning.
 *
 * <p>The string is still kept on the node itself -- logs and the deferred-line diagnostics print it,
 * and the "was this key canonicalised" comparisons stay string based.
 */
public final class StateKey {

    public final long hi;
    public final long lo;

    private StateKey(long hi, long lo) {
        this.hi = hi;
        this.lo = lo;
    }

    public static StateKey of(String key) {
        long h1 = 0xcbf29ce484222325L;
        long h2 = 0x9e3779b97f4a7c15L;
        if (key != null) {
            for (int i = 0; i < key.length(); i++) {
                long c = key.charAt(i);
                h1 = (h1 ^ c) * 0x100000001b3L;
                h2 = (h2 ^ (c + 0x9e37L)) * 0x100000001b3L;
            }
            h1 ^= key.length() * 0x9e3779b97f4a7c15L;
        }
        long hi = mix64(h1 + h2);
        long lo = mix64(h2 ^ (h1 << 1) ^ 0x2545f4914f6cdd1dL);
        return new StateKey(hi, lo);
    }

    /** splitmix64 finalizer: every input bit affects every output bit. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StateKey)) {
            return false;
        }
        StateKey other = (StateKey) o;
        return hi == other.hi && lo == other.lo;
    }

    @Override
    public int hashCode() {
        return (int) (hi ^ (hi >>> 32) ^ lo);
    }

    @Override
    public String toString() {
        return Long.toHexString(hi) + Long.toHexString(lo);
    }
}
