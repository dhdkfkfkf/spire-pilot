package sts1solver.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sts1solver.util.Log;

/**
 * P2-36 (user m19041): what one realized unit of a run-level reward is worth inside one fight.
 *
 * <p>Same idea as STS2's {@code GrowthValues}: one HP budget per source, credited only for the growth
 * a line has ALREADY realized, and paid for out of the HP that line lost. What changed in m19041 is
 * the unit of each source, and that a source nobody priced falls back to 1 HP instead of 0:
 *
 * <ul>
 *   <li>{@code feed} -- per point of max HP actually gained (default 3, STS2's overlay default).</li>
 *   <li>{@code geneticAlgorithm} -- per play (default 2).</li>
 *   <li>{@code ritualDagger} -- per point of permanent damage gained, read from the deck card's
 *       {@code misc} the engine bumps on a kill (default 0.5, i.e. +3 damage a kill is 1.5 HP).</li>
 *   <li>{@code lessonLearned} -- per card upgraded in the master deck (default 5), which the engine
 *       only does when at least one card can still be upgraded.</li>
 *   <li>{@code alchemize} (炼制药水, card id {@code Venomology}) -- per potion actually obtained,
 *       valued with the same {@link PotionBook} numbers the search uses for potions (default 1x).</li>
 *   <li>gold is NOT priced here any more (B146, user m11490; gated by the switch in user m11767):
 *       one gold is a direct 80-point term inside the solver's score while the panel shows
 *       "局外: 计", and 0 while it shows "局外: 不计", so {@code growth.handOfGreed} is ignored. Hand of Greed and Wish
 *       stay out of the "other" line so their plays are never priced twice.</li>
 *   <li>{@code other} -- per play of any OTHER card the scan in {@link GrowthScan} flags as growth
 *       (default 1). Explicit {@code growth.card.<cardId>=} lines price one card by hand.</li>
 * </ul>
 *
 * <p>Budgets live in {@code settings.txt} ({@code growth.feed=3}, fractions allowed), so nothing needs
 * a rebuild. This is a pricing layer for ordering and pruning only: JUDGMENT.md R10 still holds, the
 * win/loss and the HP of a line come from the real engine.
 */
public final class GrowthBook {

    public static final String FEED = "feed";
    public static final String GENETIC = "geneticAlgorithm";
    public static final String RITUAL = "ritualDagger";
    public static final String LESSON = "lessonLearned";
    public static final String ALCHEMIZE = "alchemize";
    /**
     * B146 (user m11490): the old gold budget key. Gold is now a direct term in the score
     * (1 gold = 80 points, {@code Solver.GOLD_POINTS}), so this key is no longer read; it stays as a
     * constant only so old settings.txt lines are recognisably retired rather than mistaken for a typo.
     */
    public static final String GOLD = "handOfGreed";
    public static final String OTHER = "other";

    /** One budget key per source, in the order used by {@link #DEFAULT_TENTHS}. */
    private static final String[] KEYS =
            {FEED, GENETIC, RITUAL, LESSON, ALCHEMIZE, OTHER};
    /**
     * HP the player is willing to pay per realized unit, in TENTHS (so 0.5 HP per permanent damage
     * fits). Feed 3 / Genetic Algorithm 2 are STS2's overlay defaults; every other source is the
     * "unpriced means 1" rule from m19041.
     */
    private static final int[] DEFAULT_TENTHS = {30, 20, 5, 50, 10, 10};

    /**
     * Cards with their own line above; their plays must not also be paid as "other".
     *
     * <p>B146: HandOfGreed stays in this list even though its gold is no longer credited here -- the
     * gold is priced by the solver's own term, and keeping the card here is what stops the same play
     * from also earning the generic "other" credit.
     */
    private static final String GENETIC_ID = "Genetic Algorithm";
    private static final String ALCHEMIZE_ID = "Venomology";
    private static final String[] OWN_LINE =
            {"Feed", GENETIC_ID, "RitualDagger", "LessonLearned", "HandOfGreed", ALCHEMIZE_ID};

    /** Cap on one budget; a typo in settings.txt must not dwarf the 10M win term. */
    public static final int MAX_HP = 50;
    private static final int MAX_TENTHS = MAX_HP * 10;
    /** Prefix for a hand-priced card: {@code growth.card.RitualDagger=5} = half an HP per play. */
    public static final String CARD_PREFIX = "growth.card.";

    /**
     * The numbers a growth source is measured from, read once per node. Kept as a small value type
     * so the same call works for the fight's opening state and for a node's state.
     */
    public static final class Facts {
        public final int maxHealth;
        public final int gold;
        public final int upgradedCards;
        public final int ritualDamage;
        public final int potionHp;

        public Facts(int maxHealth, int gold, int upgradedCards, int ritualDamage, int potionHp) {
            this.maxHealth = maxHealth;
            this.gold = gold;
            this.upgradedCards = upgradedCards;
            this.ritualDamage = ritualDamage;
            this.potionHp = potionHp;
        }

        @Override
        public String toString() {
            return "mh=" + maxHealth + " gold=" + gold + " up=" + upgradedCards
                    + " ritual=" + ritualDamage + " potionHp=" + potionHp;
        }
    }

    private GrowthBook() {
    }

    private static java.io.File file() {
        return new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
    }

    private static int[] cached;
    private static Map<String, Integer> cachedCards;
    private static long cachedAt;
    private static final long CACHE_MS = 500L;

    public static synchronized void invalidate() {
        cached = null;
        cachedCards = null;
        counting = null;
        countingPinned = null;
        cachedAt = 0L;
    }

    /** Test hook: pin the budgets (TENTHS) without touching settings.txt (null = read the file). */
    public static void overrideForTest(int[] tenths) {
        if (tenths == null) {
            cached = null;
            cachedCards = null;
            cachedAt = 0L;
            return;
        }
        int[] out = new int[KEYS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = i < tenths.length ? clampTenths(tenths[i]) : 0;
        }
        cached = out;
        cachedCards = new HashMap<String, Integer>();
        cachedAt = Long.MAX_VALUE / 2L;
    }

    /** Current budgets in TENTHS, index-aligned with {@link #KEYS}. */
    public static synchronized int[] budgets() {
        long now = System.currentTimeMillis();
        if (cached != null && cachedAt == Long.MAX_VALUE / 2L) {
            return cached.clone();
        }
        if (cached != null && now - cachedAt < CACHE_MS) {
            return cached;
        }
        int[] out = new int[KEYS.length];
        Map<String, Integer> cards = new HashMap<String, Integer>();
        Map<String, String> lines = readSettings();
        for (int i = 0; i < KEYS.length; i++) {
            String v = lines.get("growth." + KEYS[i]);
            out[i] = v == null ? DEFAULT_TENTHS[i] : parseTenths(v, DEFAULT_TENTHS[i]);
        }
        for (Map.Entry<String, String> e : lines.entrySet()) {
            String k = e.getKey();
            if (k.length() > CARD_PREFIX.length() && k.startsWith(CARD_PREFIX)) {
                int hp = parseTenths(e.getValue(), 0);
                if (hp > 0) {
                    cards.put(k.substring(CARD_PREFIX.length()), hp);
                }
            }
        }
        if (countingPinned == null) {
            String countLine = lines.get("growth." + COUNTING);
            counting = countLine == null ? Boolean.TRUE
                    : Boolean.valueOf(!"0".equals(countLine.trim()) && !"false".equalsIgnoreCase(countLine.trim()));
        }
        cached = out;
        cachedCards = cards;
        cachedAt = now;
        return out;
    }

    /**
     * B143 (user m10519): settings.txt key for "count out-of-combat gains at all".
     *
     * <p>One switch for every source, because they are all out-of-combat value: gold that is only
     * spent in a shop (点播's 50 gold), max HP, permanent damage, deck upgrades and potions. The
     * budgets below say how much each is WORTH; this says whether anything is counted. Off (0) makes
     * {@link #credit} return zero and {@link #enabled} false, so a player who wants a fight-only
     * evaluation gets exactly the fight's HP arithmetic.
     */
    public static final String COUNTING = "count";

    /**
     * null = not read yet, meaning "count" (the historical behaviour). Refreshed with the budgets so
     * one settings.txt read serves both.
     */
    private static Boolean counting;

    /** B152: a test pin that {@link #budgets()} must not overwrite (null = follow settings.txt). */
    private static Boolean countingPinned;

    public static int clamp(int hp) {
        return Math.max(0, Math.min(MAX_HP, hp));
    }

    /** Budget of one source in TENTHS (0 when the key is unknown). */
    public static int budget(String key) {
        int[] b = budgets();
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return b[i];
            }
        }
        return 0;
    }

    public static synchronized Map<String, Integer> cardBudgets() {
        budgets();
        return cachedCards == null ? new HashMap<String, Integer>() : new HashMap<String, Integer>(cachedCards);
    }

    /**
     * Whether out-of-combat gains are priced at all (settings.txt {@code growth.count}), default on.
     *
     * <p>Read with the budget cache, so a plan never re-reads the file between nodes.
     */
    public static synchronized boolean counting() {
        budgets();
        return counting == null || counting.booleanValue();
    }

    /** Flips the switch, writes it to settings.txt and forgets the cache. */
    public static synchronized void toggleCounting() {
        boolean next = !counting();
        writeRaw(COUNTING, next ? "1" : "0");
        counting = Boolean.valueOf(next);
        Log.info("growth: counting out-of-combat gains = " + next);
    }

    /**
     * One panel label, live state included.
     *
     * <p>B144 (user m11047 item 4): shortened from "局外收益: 计 / 不计". The button is 138 game units
     * wide and its text starts 10 in, so the old wording ("局外收益: 不计", six CJK glyphs plus ": ")
     * was wider than the button and ran into the screen edge, hiding the state. "局外: 不计" fits.
     */
    public static String countingLabel() {
        return counting() ? "局外: 计" : "局外: 不计";
    }

    /**
     * Test hook: pin the switch without touching settings.txt (null = read the file again).
     *
     * <p>B152: the pin has to survive the 500 ms budget cache. {@link #budgets()} re-reads the file
     * whenever the cache lapses and used to overwrite {@code counting} from it, so a gate that pinned
     * the switch on was reading the PLAYER's own setting a moment later. Measured: S187 pinned it on,
     * built a synthetic room (which takes longer than the cache window), and then billed the live
     * room 4000 instead of 5200 -- exactly the "with 局外: 不计" number, because the player's
     * settings.txt says growth.count=0. Same failure class as B145, one layer deeper.
     */
    public static synchronized void overrideCountingForTest(Boolean value) {
        countingPinned = value;
        counting = value;
    }

    /** True when at least one source would pay for growth. */
    public static boolean enabled() {
        if (!counting()) {
            return false;
        }
        int[] b = budgets();
        for (int i = 0; i < b.length; i++) {
            if (b[i] > 0) {
                return true;
            }
        }
        if (!cardBudgets().isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * HP-equivalent value of the growth a line has ALREADY realized, measured against the start of the
     * fight. Pure on purpose: the search calls it once per node and the gate pins it with fixed
     * numbers. Fractions round DOWN so the search never buys growth it cannot actually pay for.
     */
    public static int credit(List<Action> path, Facts now, Facts start) {
        if (now == null || start == null || !counting()) {
            return 0;
        }
        int[] t = budgets();
        long tenths = 0;
        // Feed: the max HP it really gained, not how often it was played.
        tenths += (long) Math.max(0, now.maxHealth - start.maxHealth) * t[0];
        // Ritual Dagger: permanent damage the deck card actually carries now.
        tenths += (long) Math.max(0, now.ritualDamage - start.ritualDamage) * t[2];
        // Lesson Learned: master deck cards that really got upgraded.
        tenths += (long) Math.max(0, now.upgradedCards - start.upgradedCards) * t[3];
        int genetic = 0;
        int alchemize = 0;
        int other = 0;
        Map<String, Integer> byCard = cardBudgets();
        if (path != null) {
            for (int i = 0; i < path.size(); i++) {
                Action a = path.get(i);
                if (a == null || a.cardId == null || a.cardId.isEmpty()) {
                    continue;
                }
                Integer hand = byCard.get(a.cardId);
                if (hand != null) {
                    tenths += hand;
                    continue;
                }
                if (GENETIC_ID.equals(a.cardId)) {
                    genetic++;
                } else if (ALCHEMIZE_ID.equals(a.cardId)) {
                    alchemize++;
                } else if (ownLine(a.cardId)) {
                    // credited from the deck / player numbers above, not from the play
                } else if (GrowthScan.isGrowthCard(a.cardId)) {
                    other++;
                }
            }
        }
        tenths += (long) genetic * t[1];
        tenths += (long) other * t[5];
        if (alchemize > 0) {
            // One potion obtained is worth what that potion is worth (PotionBook), scaled by the key.
            tenths += (long) Math.max(0, now.potionHp - start.potionHp) * t[4];
        }
        return (int) Math.max(0L, tenths / 10L);
    }

    private static boolean ownLine(String cardId) {
        for (int i = 0; i < OWN_LINE.length; i++) {
            if (OWN_LINE[i].equals(cardId)) {
                return true;
            }
        }
        return false;
    }

    /** Writes one budget (TENTHS), preserving every other line of settings.txt. */
    public static synchronized void write(String key, int tenths) {
        int v = clampTenths(tenths);
        writeRaw(key, hpText(v));
        Log.info("growth: growth." + key + "=" + hpText(v));
    }

    /**
     * Replaces (or appends) one growth key with the given text, keeping every other line.
     *
     * <p>B143: the counting switch is a flag, not a tenths value, so it cannot go through
     * {@link #write}. Everything else (file handling, invalidate) is shared.
     */
    public static synchronized void writeRaw(String key, String value) {
        String prefix = "growth." + key + "=";
        java.util.List<String> out = new java.util.ArrayList<String>();
        boolean saw = false;
        try {
            if (file().isFile()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(file()), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(prefix)) {
                            out.add(prefix + value);
                            saw = true;
                        } else {
                            out.add(line);
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (!saw) {
                out.add(prefix + value);
            }
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(file()), "UTF-8"));
            try {
                for (int i = 0; i < out.size(); i++) {
                    w.println(out.get(i));
                }
            } finally {
                w.close();
            }
            invalidate();
        } catch (Throwable t) {
            Log.warn("growth: cannot write settings.txt: " + t);
        }
    }

    /** One panel line, "" when every source is off. */
    public static String describe() {
        int[] b = budgets();
        // B146: gold has its own rate in the score now (it follows this same switch), so it is not a
        // budget row here.
        String[] names = {"献祭(狂宴)", "遗传算法", "仪式匕首", "勤学精进", "炼制药水", "其他成长牌"};
        String[] units = {"HP/最大生命", "HP/次", "HP/永久伤害", "HP/升级", "×药水值", "HP/次"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (b[i] <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(names[i]).append(' ').append(hpText(b[i])).append(units[i]);
        }
        Map<String, Integer> byCard = cardBudgets();
        if (!byCard.isEmpty()) {
            java.util.List<String> ids = new java.util.ArrayList<String>(byCard.keySet());
            java.util.Collections.sort(ids);
            for (int i = 0; i < ids.size(); i++) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(ids.get(i)).append(' ').append(hpText(byCard.get(ids.get(i)))).append("HP/次");
            }
        }
        return sb.length() == 0 ? "" : "成长: " + sb;
    }

    /** "3" for whole HP, "0.5" for a fraction -- budgets are stored in tenths. */
    public static String hpText(int tenths) {
        if (tenths % 10 == 0) {
            return Integer.toString(tenths / 10);
        }
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    public static int clampTenths(int tenths) {
        return Math.max(0, Math.min(MAX_TENTHS, tenths));
    }

    private static Map<String, String> readSettings() {
        Map<String, String> out = new HashMap<String, String>();
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

    private static int parseTenths(String text, int fallback) {
        try {
            double v = Double.parseDouble(text.trim());
            return clampTenths((int) Math.round(v * 10.0));
        } catch (Throwable t) {
            return fallback;
        }
    }
}
