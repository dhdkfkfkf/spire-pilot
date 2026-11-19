package sts1solver.mirror;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/**
 * Immutable identity for a canonical combat-state snapshot.
 *
 * <p>The 128-bit prefix is an indexing aid only. Equality compares the schema version and the full
 * canonical byte sequence, so a hash collision can never merge two different states. A key marked
 * inexact is diagnostic only and must never be used for transposition merging.
 */
public final class SearchStateKey {
    public final int schemaVersion;
    public final long hashHi;
    public final long hashLo;
    public final boolean exact;
    public final List<String> unknownReasons;

    private final byte[] canonicalBytes;
    private final int hashCode;

    public SearchStateKey(int schemaVersion, byte[] canonicalBytes,
                          boolean exact, List<String> unknownReasons) {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (canonicalBytes == null) {
            throw new IllegalArgumentException("canonicalBytes must not be null");
        }
        this.schemaVersion = schemaVersion;
        this.canonicalBytes = canonicalBytes.clone();
        this.exact = exact;
        List<String> reasons = unknownReasons == null
                ? Collections.<String>emptyList() : new ArrayList<String>(unknownReasons);
        this.unknownReasons = Collections.unmodifiableList(reasons);
        if (exact && !reasons.isEmpty()) {
            throw new IllegalArgumentException("An exact key cannot have unknown-state reasons");
        }
        byte[] hash = sha256(this.canonicalBytes);
        this.hashHi = readLong(hash, 0);
        this.hashLo = readLong(hash, 8);
        this.hashCode = 31 * schemaVersion + Arrays.hashCode(this.canonicalBytes);
    }

    /** Full canonical payload, defensively copied. */
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    /** Hash-prefix equality is deliberately insufficient; exact bytes are always compared. */
    public boolean canMerge(SearchStateKey other) {
        return other != null && exact && other.exact && equals(other);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SearchStateKey)) {
            return false;
        }
        SearchStateKey that = (SearchStateKey) other;
        return schemaVersion == that.schemaVersion
                && Arrays.equals(canonicalBytes, that.canonicalBytes);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; ++i) {
            value = (value << 8) | (bytes[offset + i] & 0xffL);
        }
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
