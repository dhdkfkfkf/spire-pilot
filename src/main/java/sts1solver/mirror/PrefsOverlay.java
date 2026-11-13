package sts1solver.mirror;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-through / write-isolated view of an engine {@code Prefs.data} map.
 *
 * <p>Installing the sandbox used to copy the whole map (about 400 keys per {@code Prefs}, seven of
 * them) on every fork; stack sampling showed that copy at roughly 6% of the whole search. The
 * engine only reads these maps during combat ({@code hasCardBeenSeen}) and writes them when an
 * unlock happens, so an overlay gives the same behaviour for free: reads fall through to the live
 * map (which nothing mutates while the sandbox is installed), writes land in a tiny local map, and
 * the live map is never touched.
 *
 * <p>Iteration has to stay complete, because {@code Prefs.flush()} serialises the whole map with
 * Gson; {@link #entrySet()} therefore materialises base-plus-overrides. That is the one O(n) path
 * and it only runs when the sandbox actually flushes a save file.
 */
public final class PrefsOverlay extends AbstractMap<String, String> {

    private final Map<String, String> base;
    private final Map<String, String> writes = new HashMap<String, String>();
    private final Set<String> removed = new HashSet<String>();

    public PrefsOverlay(Map<String, String> base) {
        this.base = base;
    }

    /** True when {@code data} is already one of our overlays (a nested sandbox install). */
    public static boolean isOverlay(Object data) {
        return data instanceof PrefsOverlay;
    }

    @Override
    public String get(Object key) {
        if (writes.containsKey(key)) {
            return writes.get(key);
        }
        if (removed.contains(key)) {
            return null;
        }
        return base.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        if (writes.containsKey(key)) {
            return true;
        }
        return !removed.contains(key) && base.containsKey(key);
    }

    @Override
    public String put(String key, String value) {
        removed.remove(key);
        return writes.put(key, value);
    }

    @Override
    public String remove(Object key) {
        boolean wasThere = containsKey(key);
        String previous = get(key);
        writes.remove(key);
        if (wasThere) {
            removed.add((String) key);
        }
        return previous;
    }

    @Override
    public void clear() {
        writes.clear();
        removed.addAll(base.keySet());
    }

    @Override
    public int size() {
        return snapshot().size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
        return snapshot().entrySet();
    }

    @Override
    public Set<String> keySet() {
        return snapshot().keySet();
    }

    @Override
    public java.util.Collection<String> values() {
        return snapshot().values();
    }

    /** Merged view: every live key, minus removals, plus sandbox writes. */
    private Map<String, String> snapshot() {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : base.entrySet()) {
            if (!removed.contains(e.getKey())) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        merged.putAll(writes);
        return merged;
    }

    /** For tests and logs: how many keys this overlay added on top of the live map. */
    public int writeCount() {
        return writes.size();
    }

    /** For tests: the keys the sandbox wrote itself (added or overridden). */
    public java.util.List<String> writtenKeys() {
        return new ArrayList<String>(writes.keySet());
    }
}
