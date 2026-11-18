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

    /**
     * P7 (user m00001): whether the portfolio searches the belt in TIERS (drink 0 potions, then 1,
     * ... up to every potion in the bar) instead of one frontier where a line that drank is already
     * 18000 points behind.
     *
     * <p>Default on. {@code search.potionTiers=off} is the rollback switch: the four-rung portfolio
     * then runs exactly as it did before P7 (no tier split, potion cost charged on every node).
     */
    public static boolean potionTiers() {
        return yes("search.potionTiers", true);
    }

    /**
     * P7b (user m00331) / P7d (user m00561): the order the portfolio runs in.
     *
     * <p>{@code tier} (default since P7d): one session per potion tier, the whole beam ladder widened
     * in place inside that session -- tier 0 runs beams 1..4, then tier 1, and so on. This is the
     * fast layout: the search tree is kept while the beam grows, so a tier expands each prefix once.
     *
     * <p>{@code beam}: the P7b cell layout -- one fresh session per (beam rung, potion tier) cell, so
     * every cell re-expands the fight from the root. It is opt-in (and slower: it paid for the A/B
     * comparison, not for a speed win) because the user reported the cell order as "too slow".
     */
    public static boolean potionOrderBeam() {
        String v = read("search.potionOrder", "tier").trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return false;
        }
        return v.equals("beam") || v.equals("cell") || v.equals("cells") || v.startsWith("beam");
    }

    /**
     * P7: whether a higher potion tier runs a proportionally wider beam
     * ({@code tierBeam = base * (1 + step * tier)}), because every potion adds a "drink / keep"
     * fork to every decision. Default on; {@code search.potionBeam=off} pins every tier to its
     * profile's own beam.
     */
    public static boolean potionBeam() {
        return yes("search.potionBeam", true);
    }

    /** P7: percent added per tier by {@link #potionBeam()} (default 25, clamped to 0..100). */
    public static int potionBeamStep() {
        String v = read("search.potionBeamStep", "25").trim();
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(v)));
        } catch (Throwable t) {
            return 25;
        }
    }

    /** Shared parser for the on/off switches (same spellings every other switch accepts). */
    private static boolean yes(String key, boolean fallback) {
        String v = read(key, fallback ? "on" : "off").trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return fallback;
        }
        return !(v.equals("off") || v.equals("no") || v.equals("false") || v.equals("0")
                || v.equals("ban") || v.equals("\u5173") || v.equals("\u7981\u7528"));
    }

    /**
     * 1.0.5 (A2): drop a board that is identical to another board in the same layer except for the
     * player's block, when the other one has at least as much block.
     *
     * <p>Off by default: it is the search's second reason to DISCARD a real candidate (the first is
     * the parking-lot ceiling), and dropping candidates must be proven on a device before it is
     * allowed to change an answer. {@code search.dominance=on} enables it.
     */
    public static boolean dominance() {
        return yes("search.dominance", false);
    }

    /**
     * 1.0.5: merge two card-play candidates whose targets are interchangeable according to the
     * monsters' own visible state (id / HP / block / intent / powers).
     *
     * <p>Off by default: two monsters that look identical can still differ in move history or in
     * what a mod keeps outside those fields, and merging them is a real loss of branches.
     * {@code search.symmetricTargets=on} enables it.
     */
    public static boolean symmetricTargets() {
        return yes("search.symmetricTargets", false);
    }

    /**
     * 1.0.5: show the in-hook frame timings (search / repair / preview / HUD) on the status line
     * while a search is running. Off by default: it is a diagnostic, not a playing aid.
     */
    public static boolean panelTiming() {
        return yes("panel.timing", false);
    }

    /**
     * 1.0.6 (C1): order one decision's candidates by this turn's own bound -- the hand knapsack
     * ("can this turn kill?") and the monsters' attack intents ("must this turn block?"). Order
     * only; nothing is dropped. {@code search.turnPlan=off} restores the engine's order.
     */
    public static boolean turnPlan() {
        return yes("search.turnPlan", true);
    }

    /**
     * 1.0.6 (C1): when the beam cuts a layer, keep the best node of every action class first
     * (attack / skill / power / potion / end) and fill the remaining slots by score. A pure score
     * cut can spend the whole beam on one card type -- six attack lines and no defensive one -- and
     * the node that survives the turn is exactly the one it dropped. {@code search.diversity=off}
     * restores the pure cut.
     */
    public static boolean diversity() {
        return yes("search.diversity", true);
    }

    /**
     * 1.0.1 (user): a fixed cap on how many turns past the current one the beam may expand.
     *
     * <p>Default {@code 0}: walk the fight to the end. The player asked for exactly that -- finish it
     * if the clock allows -- so depth is no longer cut up front. What bounds a too-long wait is the
     * budget, and the staged ladder in {@code Solver.Session} is what turns a short clock into a
     * 3 -> 2 -> 1 turn promise on the way down. A number here is a hard cap the player chose, and the
     * ladder never promises more than it.
     *
     * <p>Probe instances always run at 0: their gates assert what a fight searched to the end proves,
     * and a player-only truncation must not move them.
     */
    public static int horizonTurns() {
        String raw = read("search.horizonTurns", "").trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            int turns = Integer.parseInt(raw);
            return turns < 0 ? 0 : turns;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The turn budgets 全自动 offers, in the order the chip cycles through them. 0 = walk it out.
     *
     * <p>m12550: 4 and 5 joined the list. The chip used to stop at 3, so a player who wanted "the
     * best line over the next five turns" could not ask for it -- and 5 is the row count the strip
     * can show at its largest, so the plan he asked for is exactly what fits on screen.
     */
    public static final int[] HORIZON_CHOICES = {1, 2, 3, 4, 5, 0};

    /** Writes {@code search.horizonTurns}; 0 means "walk the fight to its end". */
    public static void setHorizonTurns(int turns) {
        write("search.horizonTurns", String.valueOf(turns < 0 ? 0 : turns));
    }

    /**
     * 1.1.0 (user m06672): the 回合 chip in 全自动.
     *
     * <p>It used to be reachable only by editing settings.txt, which is not a setting. The values
     * are 1/2/3/走完: a short turn budget is a request for the best play inside those turns, not a
     * promise to win, so the label says the number and nothing more.
     */
    public static int cycleHorizonTurns() {
        int cur = horizonTurns();
        int at = 0;
        for (int i = 0; i < HORIZON_CHOICES.length; i++) {
            if (HORIZON_CHOICES[i] == cur) {
                at = i;
                break;
            }
        }
        int next = HORIZON_CHOICES[(at + 1) % HORIZON_CHOICES.length];
        setHorizonTurns(next);
        return next;
    }

    /** "1" / "2" / "3" / "走完" -- the 回合 chip's value. */
    public static String horizonLabel() {
        int turns = horizonTurns();
        return turns <= 0 ? "走完" : String.valueOf(turns);
    }

    /**
     * 1.0.8 (C2): offer one branch per card play instead of one per (card x living monster).
     *
     * <p>Every single-target card is expanded against every monster, and each branch costs a fork
     * plus an engine tick. The kept targets are the ones that can change the turn: a monster this
     * card can kill, the monster with the highest attack intent, and the weakest one. A group where
     * any target carries a power is left untouched.
     *
     * <p>{@code search.targetFocus=off} restores the full list. On by default because the cost is
     * paid on every decision of every layer and multi-monster fights are the common case; the first
     * drop of each search is logged with the counts it saved.
     */
    public static boolean targetFocus() {
        return yes("search.targetFocus", true);
    }

    /**
     * 1.0.15 (user): true while the player's budget came from 自动. Only that mode may extend its
     * own wait as the sandbox hand grows -- a number the player picked (6s, 12s, ...) is a promise.
     */
    private static volatile boolean autoBudgetMode;

    public static boolean autoBudgetMode() {
        return autoBudgetMode;
    }

    public static void setAutoBudgetMode(boolean on) {
        autoBudgetMode = on;
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
