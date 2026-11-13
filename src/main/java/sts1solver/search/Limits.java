package sts1solver.search;

/**
 * P2-58 (user m24721): every cap that shapes the search has three settings -- low, high, unlimited --
 * and all of them live in settings.txt, never in a system property.
 *
 * <p>Why a tier and not a raw number: a raw number is right for one fight and wrong for the next
 * ("discard 3 of 10" wants 120 combinations, "pick 1 of 9" wants 9), and the player is the only one
 * who knows whether the machine has time. Three tiers keep the setting honest and cheap to explain:
 * <pre>
 *   limits=low         # 低：按 STS2 的低档（分支 12/16、组合 8、束宽 45、层数 200）
 *   limits=high        # 高（默认）：分支 28/36、组合 48、束宽 96、层数 400
 *   limits=unlimited   # 无限制：不截断（堆守卫和预算仍在，见 Solver.heapLimit）
 *   limits.beam=low    # 单键覆盖，写同一套档位
 * </pre>
 *
 * <p>The tiers follow the reference solver (CombatSolver-main {@code SolverSettings.cs:180-205}:
 * 12/16, 18/24, 28/36, 42/54 branches) rounded to our two search profiles. Unlimited never means
 * "ignore physics": the heap guard and the node/second budget still apply, so an unlimited setting
 * cannot hang the game -- it only stops us from silently cutting a branch.
 */
public final class Limits {
    /** The three tiers a knob can be set to. */
    public enum Tier { LOW, HIGH, UNLIMITED }

    /** Pile-choice branches (discard-pile / draw-pile picks), STS2 low 12 / high 28. */
    public static final int PILE_LOW = 12;
    public static final int PILE_HIGH = 28;
    /** Hand-choice branches, STS2 low 16 / high 36. */
    public static final int HAND_LOW = 16;
    public static final int HAND_HIGH = 36;
    /** Combinations kept per multi-select screen (3-card discard from 10 = 120 raw). */
    public static final int COMBOS_LOW = 8;
    public static final int COMBOS_HIGH = 48;
    public static final int COMBOS_UNLIMITED = 5000;
    /**
     * Screens one action may open (a HeadChase opens two; a mod card auto-played by a power can
     * chain a hand pick into two grids, and a second auto-played card adds more).
     *
     * <p>B139 (user m08865): this used to be 4/8, and the walk simply stopped when it ran out --
     * leaving the acting action half-done and recording that half-done state as the step's result.
     * A fight that opened five pickers then had an answer chain that stopped at four, and the digest
     * it stored described a state the fight never reached (the two piles were still unswapped).
     */
    public static final int SCREENS_LOW = 4;
    public static final int SCREENS_HIGH = 16;
    public static final int SCREENS_UNLIMITED = 32;
    /** Deferred parking lot (A3). */
    public static final int DEFERRED_LOW = 500;
    public static final int DEFERRED_HIGH = 2000;
    /** Promotions per solve (A3): a floor that never goes down, and an adaptive ceiling. */
    public static final int PROMOTIONS_LOW = 4;
    public static final int PROMOTIONS_HIGH = 6;
    public static final int PROMOTIONS_CAP_LOW = 20;
    public static final int PROMOTIONS_CAP_HIGH = 60;
    public static final int PROMOTIONS_CAP_UNLIMITED = 200;
    /** Beam width. Unlimited stays finite because the heap guard, not this number, is the ceiling. */
    public static final int BEAM_LOW = 45;
    public static final int BEAM_HIGH = 96;
    public static final int BEAM_UNLIMITED = 256;
    /** Search layers before a line counts as "too deep". */
    public static final int LAYERS_LOW = 200;
    public static final int LAYERS_HIGH = 400;
    public static final int LAYERS_UNLIMITED = 4000;

    /** Cache: one settings.txt read per second is plenty for a value the player edits by hand. */
    private static long cachedAt;
    private static java.util.Map<String, String> cached;

    private Limits() {
    }

    /** The master tier: {@code limits=low|high|unlimited}, default HIGH. */
    public static Tier master() {
        return parse(read("limits", "high"), Tier.HIGH);
    }

    /** Tier for one knob: {@code limits.<key>=} wins, else the master tier. */
    public static Tier tier(String key) {
        String raw = read("limits." + key, "");
        return raw.isEmpty() ? master() : parse(raw, master());
    }

    /** Pile-choice branch cap (a discard-pile pick). */
    public static int pileBranches() {
        return pick(tier("pile"), PILE_LOW, PILE_HIGH, Integer.MAX_VALUE);
    }

    /** Hand-choice branch cap. */
    public static int handBranches() {
        return pick(tier("hand"), HAND_LOW, HAND_HIGH, Integer.MAX_VALUE);
    }

    /** How many combinations of a multi-select screen to keep. */
    public static int combos() {
        return pick(tier("combos"), COMBOS_LOW, COMBOS_HIGH, COMBOS_UNLIMITED);
    }

    /** How many selection screens one action may answer. */
    public static int screens() {
        return pick(tier("screens"), SCREENS_LOW, SCREENS_HIGH, SCREENS_UNLIMITED);
    }

    /** Deferred parking lot size (A3). */
    public static int deferred() {
        return pick(tier("deferred"), DEFERRED_LOW, DEFERRED_HIGH, Integer.MAX_VALUE);
    }

    /** Promotions per solve (A3) -- the floor the adaptive limit never goes below. */
    public static int promotions() {
        return pick(tier("promotions"), PROMOTIONS_LOW, PROMOTIONS_HIGH, PROMOTIONS_HIGH);
    }

    /** Ceiling of the adaptive promotion limit. */
    public static int promotionCap() {
        return pick(tier("promotions"), PROMOTIONS_CAP_LOW, PROMOTIONS_CAP_HIGH,
                PROMOTIONS_CAP_UNLIMITED);
    }

    /** Test hook: pin the beam tier without touching settings.txt (null = read the file). */
    private static Integer beamForced;

    /** Test hook: pin / release the beam ceiling (B172 gate S232). */
    public static void forceBeamForTest(Integer beam) {
        beamForced = beam;
    }

    /** Test hook: the pinned beam value, or null when the file decides. */
    public static Integer beamForTest() {
        return beamForced;
    }

    /** Beam width ceiling. */
    public static int beam() {
        if (beamForced != null) {
            return beamForced.intValue();
        }
        return pick(tier("beam"), BEAM_LOW, BEAM_HIGH, BEAM_UNLIMITED);
    }

    /** Search layers. */
    public static int layers() {
        return pick(tier("layers"), LAYERS_LOW, LAYERS_HIGH, LAYERS_UNLIMITED);
    }

    /** Branch cap for one open screen, from the kind the engine reports. */
    public static int choiceBranches(sts1solver.drive.Driver.SelectionKind kind, int floor) {
        int cap;
        if (kind == sts1solver.drive.Driver.SelectionKind.HAND) {
            cap = handBranches();
        } else if (kind == sts1solver.drive.Driver.SelectionKind.REWARD) {
            cap = Integer.MAX_VALUE;
        } else {
            cap = pileBranches();
        }
        return Math.max(Math.max(1, floor), cap);
    }

    /** Human readable tier, for the panel and the log. */
    public static String describe() {
        return "limits=" + master().name().toLowerCase(java.util.Locale.ROOT)
                + " pile=" + pileBranches() + " hand=" + handBranches()
                + " combos=" + combos() + " screens=" + screens();
    }

    /** Test hook: forget the cached settings.txt so the next read re-opens the file. */
    public static void resetCache() {
        cached = null;
        cachedAt = 0L;
    }

    private static int pick(Tier t, int low, int high, int unlimited) {
        if (t == Tier.LOW) {
            return low;
        }
        if (t == Tier.UNLIMITED) {
            return unlimited;
        }
        return high;
    }

    private static Tier parse(String raw, Tier fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.equals("low") || s.equals("\u4f4e")) {
            return Tier.LOW;
        }
        if (s.equals("unlimited") || s.equals("inf") || s.equals("max")
                || s.equals("\u65e0\u9650\u5236")) {
            return Tier.UNLIMITED;
        }
        if (s.equals("high") || s.equals("\u9ad8")) {
            return Tier.HIGH;
        }
        return fallback;
    }

    /** Test hook: pin the potion policy without touching settings.txt (null = read the file). */
    private static Boolean potionsForced;

    /** Test hook: pin / release the potion policy. */
    public static void forcePotionsForTest(Boolean allowed) {
        potionsForced = allowed;
    }

    /**
     * Whether the search may offer potion branches (P6-12 {@code search.potions=allow|ban}).
     *
     * <p>Banning the belt is a MEASUREMENT, not a difficulty: the same fight is solved twice and the
     * difference in HP deficit is what the potions were worth. It is also the sanity check that no
     * line silently depends on a potion the player did not have.
     */
    public static boolean potionsOffered() {
        if (potionsForced != null) {
            return potionsForced.booleanValue();
        }
        String v = read("search.potions", "allow").trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return true;
        }
        return !(v.equals("ban") || v.equals("off") || v.equals("no") || v.equals("false")
                || v.equals("\u7981\u6b62") || v.equals("\u4e0d\u559d"));
    }

    /**
     * Flips the potion policy and SAVES it (P6-12 panel switch, user m04782).
     *
     * <p>Banning the belt is a measurement, not a difficulty: solve the same fight twice and the
     * difference in HP deficit is what the potions were worth. Persisted on purpose -- a switch that
     * forgets itself after a restart is worse than no switch, because the next measurement silently
     * runs under the old policy.
     */
    public static boolean togglePotions() {
        boolean next = !potionsOffered();
        potionsForced = null;
        write("search.potions", next ? "allow" : "ban");
        return next;
    }

    /** The panel label for the potion switch, carrying the live state. */
    public static String potionsLabel() {
        return potionsOffered() ? "\u836f\u6c34: \u5141\u8bb8" : "\u836f\u6c34: \u7981\u7528";
    }

    /** Replaces (or appends) one key in settings.txt, keeping every other line untouched. */
    private static void write(String key, String value) {
        try {
            java.io.File f = new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
            java.util.List<String> out = new java.util.ArrayList<String>();
            boolean replaced = false;
            if (f.isFile()) {
                java.util.List<String> lines =
                        java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int eq = line == null ? -1 : line.indexOf('=');
                    if (!replaced && eq > 0 && !line.trim().startsWith("#")
                            && line.substring(0, eq).trim().equals(key)) {
                        out.add(key + "=" + value);
                        replaced = true;
                    } else {
                        out.add(line == null ? "" : line);
                    }
                }
            }
            if (!replaced) {
                out.add(key + "=" + value);
            }
            java.nio.file.Files.write(f.toPath(), out, java.nio.charset.StandardCharsets.UTF_8);
            resetCache();
        } catch (Throwable t) {
            sts1solver.util.Log.warnEvery("settings-write-" + key, 30000L,
                    "cannot save " + key + " to settings.txt: " + t);
        }
    }

    /** One value out of settings.txt (same file and format every other knob uses). */
    private static String read(String key, String fallback) {
        try {
            java.util.Map<String, String> map = load();
            String v = map.get(key);
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static java.util.Map<String, String> load() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < 1000L) {
            return cached;
        }
        java.util.Map<String, String> map = new java.util.HashMap<String, String>();
        java.io.File f = new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
        if (f.isFile()) {
            java.util.List<String> lines = null;
            try {
                lines = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                lines = null;
            }
            if (lines != null) {
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line == null) {
                        continue;
                    }
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    // A trailing comment is allowed ("limits=high   # 默认就够").
                    int hash = v.indexOf('#');
                    if (hash >= 0) {
                        v = v.substring(0, hash).trim();
                    }
                    map.put(k, v);
                }
            }
        }
        cached = map;
        cachedAt = now;
        return map;
    }
}
