package sts1solver.search;

import sts1solver.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory plan cache, modelled on the reference solver's {@code SolvedRouteCache}.
 *
 * <p>The reference keys its cache on the FULL state plus everything that can change the answer: the
 * game and mod versions, the seed, act, floor, <b>ascension</b>, encounter, modifiers, and its whole
 * policy object (theft policy, beam portfolio switches, ...). The point is that "the same position
 * under the same settings" must produce the same plan, so it can be answered instantly the second
 * time -- pressing F8 twice, replanning after a no-op drift, or reopening the same fight.
 *
 * <p>Ours is deliberately simpler but keeps the same guarantees: the key is the live combat digest
 * (which already covers hand/draw/discard/exhaust, HP/block/energy, powers, relics, monsters and
 * turn/phase) plus ascension, the escape policy, and the content certification. A state that differs
 * in any of those gets a different key and is searched for real.
 */
public final class PlanCache {

    private static final int MAX_ENTRIES = 64;

    private static final class Entry {
        final Solver.Result result;
        final long createdAt;

        Entry(Solver.Result result) {
            this.result = result;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** Insertion-ordered LRU (oldest first). */
    private static final LinkedHashMap<String, Entry> CACHE =
            new LinkedHashMap<String, Entry>(16, 0.75f, false);

    private static int hits;
    private static int misses;

    private PlanCache() {
    }

    /**
     * Key for "this exact position under these exact settings".
     *
     * <p>The combat digest covers the board (hand/draw/discard ORDER, HP/block/energy, powers,
     * relics, orbs, monsters, intents and their move history), but not where the game's random
     * streams stand: two fights with identical hands and piles still play differently the moment a
     * card rolls (TangPower plays a random hand card every turn). {@link sts1solver.mirror.RngStamp}
     * already existed for exactly this key -- its class comment says so -- and was never wired in
     * here, so a plan computed at another random position could be answered from the cache.
     *
     * @param stateDigest live combat digest
     * @param ascension   ascension level (A0 vs A20 are different games)
     * @param policy      escape policy label
     * @param content     content certification line (vanilla vs mod/trusted)
     */
    public static String key(String stateDigest, int ascension, String policy, String content) {
        // B167 (user m18895): the digest carries the action manager's mid-flight fields (ph=/q=/use=/
        // end=), which are not part of the position -- the same board reloaded one frame later
        // differs only there. Keying on the raw digest made the reload a miss and searched the whole
        // plan again. Key on the stable projection instead (Digest.stable is idempotent).
        String stable = stateDigest == null ? "" : sts1solver.mirror.Digest.stable(stateDigest);
        return "s1|" + stable + "|a" + ascension + "|" + policy + "|" + content
                + "|rng=" + sts1solver.mirror.RngStamp.of();
    }

    public static synchronized Solver.Result get(String key) {
        Entry e = CACHE.get(key);
        if (e == null || e.result == null || e.result.plan.isEmpty()) {
            misses++;
            return null;
        }
        hits++;
        Log.info("plan cache: hit (" + key.hashCode() + ") entries=" + CACHE.size()
                + " hits=" + hits + " misses=" + misses);
        return e.result;
    }

    public static synchronized void put(String key, Solver.Result result) {
        if (result == null || result.plan.isEmpty()) {
            return;
        }
        CACHE.put(key, new Entry(result));
        while (CACHE.size() > MAX_ENTRIES) {
            String oldest = CACHE.keySet().iterator().next();
            CACHE.remove(oldest);
        }
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    public static synchronized int size() {
        return CACHE.size();
    }

    public static synchronized int hits() {
        return hits;
    }

    public static synchronized int misses() {
        return misses;
    }

    public static synchronized void resetStats() {
        hits = 0;
        misses = 0;
    }
}
