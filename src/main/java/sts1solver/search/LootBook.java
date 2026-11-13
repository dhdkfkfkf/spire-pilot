package sts1solver.search;

import com.megacrit.cardcrawl.relics.AbstractRelic;

/**
 * L4 (user m17196 / m17363): what the post-combat reward screen is worth, in HP.
 *
 * <p>Same shape as {@link GrowthBook}: a handful of {@code loot.*} keys in {@code settings.txt},
 * 500 ms cache, nothing needs a rebuild. Units are HP because every other price in this solver is
 * HP ({@code Solver.POINTS_PER_HP}) and the panel prints HP.
 *
 * <p>Why this exists at all: the reward screen used to be invisible to the search, so "let the
 * Looter run away with 20 gold" and "kill it and get the gold back" produced the SAME score
 * (both only changed {@code player.gold} by the same amount), and the escape terminal's
 * {@link EscapePolicy#forfeitPoints()} was never wired in. The user's ruling (m16960): the reward
 * screen is a constant for ordinary fights, so only the DIFFERENCE matters -- stolen gold coming
 * back, an escape blocking the room's normal gold, a hunting-style extra card group.
 *
 * <p>Defaults are the user's numbers (m17363): cards 2 / 4 / 10 HP for normal / elite / boss, relics
 * 5 / 10 / 15 HP for common / uncommon / rare. Relic pricing is speculative for normal fights and
 * bosses (only the smoke-bomb-on-an-elite case and "normal fight, Muzzle" differ in the base game),
 * but the user asked for all three room types to be covered so no future mod case is unpriced.
 */
public final class LootBook {

    /** HP per card offered, by room type. */
    public static final String CARD_NORMAL = "loot.card.normal";
    public static final String CARD_ELITE = "loot.card.elite";
    public static final String CARD_BOSS = "loot.card.boss";
    /** HP per relic offered, by rarity. */
    public static final String RELIC_COMMON = "loot.relic.common";
    public static final String RELIC_UNCOMMON = "loot.relic.uncommon";
    public static final String RELIC_RARE = "loot.relic.rare";

    private static final String[] KEYS =
            {CARD_NORMAL, CARD_ELITE, CARD_BOSS, RELIC_COMMON, RELIC_UNCOMMON, RELIC_RARE};
    private static final int[] DEFAULTS = {2, 4, 10, 5, 10, 15};

    /** Same clamp as {@link GrowthBook#MAX_HP}: a price nobody can mean is cut, not trusted. */
    public static final int MAX_HP = 50;

    private static int[] cached;
    private static long cachedAt;
    private static final long CACHE_MS = 500L;

    private LootBook() {
    }

    private static java.io.File file() {
        return new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
    }

    public static synchronized void invalidate() {
        cached = null;
        cachedAt = 0L;
    }

    /** Test hook: pin the six prices (order: card normal/elite/boss, relic common/uncommon/rare). */
    public static void overrideForTest(int[] hp) {
        if (hp == null) {
            invalidate();
            return;
        }
        int[] out = new int[KEYS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = i < hp.length ? clamp(hp[i]) : DEFAULTS[i];
        }
        cached = out;
        cachedAt = Long.MAX_VALUE / 2L;
    }

    public static int clamp(int hp) {
        return Math.max(0, Math.min(MAX_HP, hp));
    }

    /** Current prices, index-aligned with {@link #KEYS}. */
    public static synchronized int[] prices() {
        long now = System.currentTimeMillis();
        if (cached != null && cachedAt == Long.MAX_VALUE / 2L) {
            return cached.clone();
        }
        if (cached != null && now - cachedAt < CACHE_MS) {
            return cached;
        }
        int[] out = new int[KEYS.length];
        java.util.Map<String, String> lines = readSettings();
        for (int i = 0; i < KEYS.length; i++) {
            String v = lines.get(KEYS[i]);
            out[i] = v == null ? DEFAULTS[i] : parse(v, DEFAULTS[i]);
        }
        cached = out;
        cachedAt = now;
        return out;
    }

    /** HP one card reward is worth in this room. */
    public static int cardHp(boolean boss, boolean elite) {
        int[] v = prices();
        if (boss) {
            return v[2];
        }
        return elite ? v[1] : v[0];
    }

    /** HP one relic reward is worth, by the engine's own tier of the relic it handed out. */
    public static int relicHp(AbstractRelic.RelicTier tier) {
        int[] v = prices();
        if (tier == null) {
            return v[3];
        }
        switch (tier) {
            case UNCOMMON:
                return v[4];
            case RARE:
            case BOSS:
            case SHOP:
                return v[5];
            default:
                return v[3];
        }
    }

    /**
     * Expected relic value, for the formula fallback only (the engine path reads the real tier).
     * Elite and boss rewards roll a tier first, so the average is the honest estimate.
     */
    public static int relicExpectedHp() {
        int[] v = prices();
        return Math.round((v[3] + v[4] + v[5]) / 3.0f);
    }

    /** One-line summary for the panel/log. */
    public static String describe() {
        int[] v = prices();
        return "卡 " + v[0] + "/" + v[1] + "/" + v[2] + " HP（普通/精英/boss）· 遗物 " + v[3] + "/" + v[4]
                + "/" + v[5] + " HP（普通/罕见/稀有）";
    }

    private static java.util.Map<String, String> readSettings() {
        java.util.Map<String, String> out = new java.util.HashMap<String, String>();
        try {
            java.io.File f = file();
            if (!f.isFile()) {
                return out;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0 || line.startsWith("#")) {
                        continue;
                    }
                    out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break the search
        }
        return out;
    }

    private static int parse(String text, int fallback) {
        try {
            return clamp((int) Math.round(Double.parseDouble(text.trim())));
        } catch (Throwable t) {
            return fallback;
        }
    }
}
