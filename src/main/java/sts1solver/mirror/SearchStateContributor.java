package sts1solver.mirror;

/**
 * Extension point for mutable state owned by a game or mod object that is not covered by the
 * built-in search snapshot.
 *
 * <p>A contributor must write every value that can affect future combat behavior, in a stable
 * field-tag order. Returning support for an object is a promise of complete coverage, not merely
 * recognition of its class. Unknown or partially understood objects must not be claimed as exact.
 */
public interface SearchStateContributor {
    /** Stable, namespaced identifier; do not use a display name or object identity. */
    String id();

    /** Increment when the contributor's canonical field schema changes. */
    int schemaVersion();

    /** Whether this contributor completely understands the supplied object. */
    boolean supports(Object value);

    /** Append the object's complete behavior-relevant state using stable numeric field tags. */
    void appendCanonicalState(Object value, CanonicalStateWriter writer);
}
