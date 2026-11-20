package sts1solver.mirror;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable, deterministically ordered registry for mod-owned search-state contributors.
 *
 * <p>Every contributor whose supports() method returns true is invoked. This permits independent
 * contributors to cover disjoint portions of one object; each receives a private writer/tag
 * namespace. A contributor claiming support promises complete coverage of its documented portion.
 * The registry never upgrades a snapshot to exact: callers must establish global coverage.
 */
public final class SearchStateContributorRegistry {
    private static final class Entry {
        final SearchStateContributor contributor;
        final String id;
        final int schemaVersion;

        Entry(SearchStateContributor contributor, String id, int schemaVersion) {
            this.contributor = contributor;
            this.id = id;
            this.schemaVersion = schemaVersion;
        }
    }

    private final List<Entry> entries;

    private SearchStateContributorRegistry(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public static SearchStateContributorRegistry empty() {
        return new SearchStateContributorRegistry(Collections.<Entry>emptyList());
    }

    /**
     * Append every matching contributor in stable id order. Each contributor gets a private writer,
     * so its numeric tags cannot collide with another contributor's tags.
     */
    public int appendFor(Object value, CanonicalStateWriter target, int firstTag,
                         List<String> unknownReasons) {
        if (target == null || unknownReasons == null) {
            throw new IllegalArgumentException("target and unknownReasons are required");
        }
        if (firstTag < 0) {
            throw new IllegalArgumentException("firstTag must be non-negative");
        }
        int tag = firstTag;
        int matched = 0;
        for (Entry entry : entries) {
            final boolean supported;
            try {
                supported = entry.contributor.supports(value);
            } catch (Throwable failure) {
                unknownReasons.add("contributor-supports-failed:" + entry.id + ":"
                        + failure.getClass().getName());
                continue;
            }
            if (!supported) {
                continue;
            }
            matched++;
            CanonicalStateWriter local = new CanonicalStateWriter();
            try {
                entry.contributor.appendCanonicalState(value, local);
                target.putString(tag++, entry.id);
                target.putInt(tag++, entry.schemaVersion);
                target.putCanonical(tag++, local.toByteArray());
            } catch (Throwable failure) {
                unknownReasons.add("contributor-append-failed:" + entry.id + ":"
                        + failure.getClass().getName());
            }
        }
        if (matched == 0) {
            unknownReasons.add("no-search-state-contributor:"
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return tag;
    }

    public int size() {
        return entries.size();
    }

    /** Mutable builder; build() snapshots and closes it against further registration. */
    public static final class Builder {
        private final List<Entry> entries = new ArrayList<Entry>();
        private final Set<String> ids = new HashSet<String>();
        private boolean built;

        public synchronized Builder register(SearchStateContributor contributor) {
            if (built) {
                throw new IllegalStateException("registry builder is already frozen");
            }
            if (contributor == null) {
                throw new IllegalArgumentException("contributor must not be null");
            }
            String id = contributor.id();
            int version = contributor.schemaVersion();
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("contributor id must be non-blank");
            }
            if (version < 1) {
                throw new IllegalArgumentException("contributor schemaVersion must be >= 1: " + id);
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate search-state contributor id: " + id);
            }
            entries.add(new Entry(contributor, id, version));
            return this;
        }

        public synchronized SearchStateContributorRegistry build() {
            if (built) {
                throw new IllegalStateException("registry builder has already been frozen");
            }
            built = true;
            List<Entry> ordered = new ArrayList<Entry>(entries);
            Collections.sort(ordered, new Comparator<Entry>() {
                public int compare(Entry a, Entry b) {
                    int c = a.id.compareTo(b.id);
                    return c != 0 ? c : Integer.compare(a.schemaVersion, b.schemaVersion);
                }
            });
            return new SearchStateContributorRegistry(ordered);
        }
    }
}
