/*
 * P7 / B188 (user m00001, 2026-11-16): the potion-tiered portfolio.
 */
package sts1solver.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import sts1solver.mirror.World;
import sts1solver.util.Log;

/**
 * P7 / B188: run the portfolio once per "potion tier".
 *
 * <p>The problem this solves: the beam ranks nodes by {@link Solver#score}, which charges the full
 * strategic price of a potion the moment it is drunk (9 HP of a dexterity potion is 18000 points).
 * A line that holds the potion therefore sits 18000 points above every line that drank it, and the
 * "drink first, then play Ghostly Armor" line is squeezed out of the beam long before its +2 block
 * per turn has paid that price back. The live evidence is in docs/PLAN-P7.md section 1: the plan
 * drank the dexterity potion at step 11, one step after the attack it should have cushioned.
 *
 * <p>What this class does instead:
 * <ul>
 *   <li>tier {@code i} may drink at most {@code i} potions (tier 0 is the untouched, potion-free
 *       baseline); inside a tier the potion price is left out of the node score
 *       ({@link Solver.Session#deferPotionCost}), so a drink is no longer a cliff;</li>
 *   <li>every tier is a fresh {@link Solver.Session} over the same root, so the tiers do not share
 *       a frontier and a wide tier cannot inherit the narrow one's potion-free pruning;</li>
 *   <li>the tiers are merged with {@link Solver#better}, i.e. on the same net-HP ruler the panel
 *       prints, which does charge the potion price exactly once; ties go to the line that drank
 *       fewer potions;</li>
 *   <li>budget: tier 0 gets 40% of the job, the remaining 60% is split geometrically over the
 *       higher tiers (2^(K-i) normalised), so "one potion" gets more room than "all potions";</li>
 *   <li>a higher tier also runs a wider beam (default +25% per tier, {@code search.potionBeamStep})
 *       and a proportionally larger parked list / promotion quota, because every potion adds a
 *       drink/keep fork at every node.</li>
 * </ul>
 *
 * <p>P7b (user m00331, PLAN-P7 section 12): the execution unit is a CELL = (beam rung, potion tier).
 * The default order is rung-major -- the narrow beam first runs 0, 1, ... K potions, then the next
 * beam does the same -- because a beam profile is the strongest lever on the answer, so the player
 * should see the narrow, cheap profiles' whole potion story before the expensive wide beams start.
 * Each cell is one fresh session with a single beam (no in-place widening), its own potion ceiling
 * and its own slice of the budget ({@code rungShare * potionShare}). {@code search.potionOrder=tier}
 * restores the pre-P7b layout (one session per tier, the whole beam ladder inside it).
 *
 * <p>Rollback: {@code search.potionTiers=off} in settings.txt makes this class a thin wrapper around
 * a single untiered session (limit = every potion, price charged on every node), which is the
 * pre-P7 behaviour.
 */
public final class PotionTieredSession {
    /**
     * P7c (user m00172): what the game actually reports for this run -- drinkable potions in the
     * belt and the belt's slot capacity. Kept for the log/hint; the tier ceiling itself is computed
     * by {@link #tierCount(int, int, long, int)}.
     */
    private final int usablePotions;
    private final int beltSlots;

    private final World root;
    /** Beams narrow -> wide, the same ladder the untiered portfolio runs. */
    private final int[] ladder;
    private final int layers;
    private final long budgetMs;
    private final int maxNodes;
    /** Potion-use ceiling per tier; a single MAX_VALUE entry means "untiered / all potions". */
    private final int[] limits;
    /** Fraction of the job budget per potion tier. */
    private final double[] shares;
    /** P7b: fraction of the job budget per beam rung, normalised over {@link #ladder}. */
    private final double[] rungShares;
    /** P7b: one session per (rung, tier) cell instead of one session per tier. */
    private final boolean cellMode;

    private long startedAt = System.currentTimeMillis();
    private Solver.Session current;
    private Solver.Result best;
    /** Tier 0's answer: the potion-free baseline the ticket's counterfactual used to replay for. */
    private Solver.Result baseline;
    private int tier;
    /** P7b: beam rung of the running cell (0 outside cell mode). */
    private int rung;
    private int bestTier = -1;
    private int bestRung = -1;
    /**
     * m02751: the ladder of the last session that ran, kept after {@code current} is cleared.
     *
     * <p>{@link #rung()} and {@link #rungs()} used to read straight through {@code current}, and
     * every tier ends by nulling it ({@link #tierDone}, {@link #cellDone}). A caller that asks
     * "which rung did this job run, out of how many" <em>after</em> the job finished -- which is
     * exactly what a gate checking the widening ladder does -- therefore got -1 and 0 back and
     * failed, on a session that had run its ladder correctly. The numbers describe work already
     * done, so they outlive the session that did it.
     */
    private int lastRung = -1;
    private int lastRungs;
    /**
     * m07509: the beam of the last rung that ran, kept for the same reason as {@link #lastRung}.
     *
     * <p>{@link #beamWidth()} answered -1 once {@code current} was cleared, so S246's check
     * "a wider beam is running" read {@code beam=-1} on a job that had in fact reached
     * {@code rung=3} of 4. The rung and its beam are one fact about finished work; they are
     * remembered together or the panel and the gate disagree about the same run.
     */
    private int lastBeam = -1;
    private int nodesDone;
    private int expansionsDone;
    private boolean finished;
    private volatile boolean stopRequested;
    private int tiersRan;
    /** P7b: the running cell's own time budget / node cap (0 = uncapped), for the log and hint. */
    private long cellBudgetMs;
    private int cellNodesCap;

    public PotionTieredSession(World world, int[] ladder, int layers, long budgetMs, int maxNodes) {
        this.root = world;
        this.ladder = ladder == null || ladder.length == 0 ? new int[]{10} : ladder.clone();
        this.layers = layers;
        this.budgetMs = Math.max(300L, budgetMs);
        this.maxNodes = maxNodes;
        if (!Limits.potionTiers()) {
            // Rollback: one tier, no potion ceiling, the price still charged on every node.
            this.limits = new int[]{Integer.MAX_VALUE};
            this.shares = new double[]{1.0};
            this.usablePotions = 0;
            this.beltSlots = 0;
        } else {
            // P7c: the ceiling comes from the game, never from a fixed number of tiers.
            int usable = Solver.potionCountUsable(world);
            int slots = Solver.potionSlots(world);
            int k = tierCount(usable, slots, this.budgetMs, this.ladder.length);
            this.usablePotions = usable;
            this.beltSlots = slots;
            this.limits = new int[k + 1];
            for (int i = 0; i <= k; i++) {
                this.limits[i] = i;
            }
            this.shares = shares(k);
            Log.info("potion tiers: usable=" + usable + " slots=" + slots
                    + " K=" + k + " tiers=" + this.limits.length);
        }
        this.rungShares = rungShares(this.ladder.length);
        this.cellMode = this.limits.length > 1 && Limits.potionOrderBeam();
    }

    /** Tier 0 = 40%, the rest = 60% split as 2^(K-i) normalised (tier 1 gets the most). */
    private static double[] shares(int k) {
        if (k <= 0) {
            return new double[]{1.0};
        }
        double[] out = new double[k + 1];
        out[0] = 0.40;
        double sum = 0.0;
        for (int i = 1; i <= k; i++) {
            out[i] = Math.pow(2.0, k - i);
            sum += out[i];
        }
        for (int i = 1; i <= k; i++) {
            out[i] = 0.60 * out[i] / sum;
        }
        return out;
    }

    /**
     * P7c (user m00172): how many potion tiers this run needs, read from the GAME instead of a
     * constant. The belt is not always three slots -- Potion Belt and mods change
     * {@code AbstractPlayer.potionSlots} -- and a fight can put new potions in it (Entropic Brew,
     * Alchemize, mod potions), so the ceiling is this run's slot capacity, not today's potion count.
     * With nothing drinkable at the root there is nothing to tier: the job keeps the single
     * potion-free path (no cell, no log, no hint), exactly as before.
     *
     * <p>The one remaining bound is arithmetic, not a taste: every cell has to keep the 300 ms floor
     * the cell scheduler uses, otherwise the extra tiers would be empty shells.
     */
    static int tierCount(int usablePotions, int potionSlots, long budgetMs, int rungs) {
        if (usablePotions <= 0) {
            return 0;
        }
        int cap = potionSlots > 0 ? Math.max(usablePotions, potionSlots) : usablePotions;
        long floorCells = Math.max(1L, budgetMs / 300L);
        int byBudget = (int)Math.max(1L, floorCells / Math.max(1, rungs)) - 1;
        return Math.max(0, Math.min(cap, byBudget));
    }

    /**
     * P7b: the historical 35/25/25/15 split of the beam ladder, normalised over the ladders actually
     * given. A four-rung ladder therefore keeps its exact old numbers; a shorter one (the quick
     * session's deduped ladder) still spends the whole budget instead of leaving a slice unused.
     */
    private static double[] rungShares(int rungs) {
        int n = Math.max(1, rungs);
        double[] out = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            out[i] = Solver.PORTFOLIO_SHARES[Math.min(i, Solver.PORTFOLIO_SHARES.length - 1)];
            sum += out[i];
        }
        for (int i = 0; i < n; i++) {
            out[i] = out[i] / sum;
        }
        return out;
    }

    /** P7 3.3: base + ceil(base * step * tier / 100), never above the configured top beam. */
    static int tierBeam(int base, int tier) {
        if (tier <= 0 || !Limits.potionBeam()) {
            return base;
        }
        long scaled = base + (long)Math.ceil(base * (double)Limits.potionBeamStep() * tier / 100.0);
        int top = Limits.beam();
        if (top > 0 && scaled > top) {
            scaled = top;
        }
        return (int)Math.max(base, scaled);
    }

    private int limitFor(int i) {
        return this.limits[i];
    }

    private String limitText(int i) {
        return this.limits[i] == Integer.MAX_VALUE ? "all" : String.valueOf(this.limits[i]);
    }

    private int tierNodes() {
        if (this.maxNodes <= 0) {
            return 0;
        }
        return Math.max(200, (int)(this.maxNodes * this.shares[this.tier]));
    }

    /** P7b: cells this job will run (rungs x tiers); 1 in the untiered rollback. */
    int totalCells() {
        return this.limits.length * this.ladder.length;
    }

    /** P7b: flat index of the running cell (rung-major). */
    private int cellIndex() {
        return this.rung * this.limits.length + this.tier;
    }

    /** P7b: step to the next cell, rung-major; false when the ladder is exhausted. */
    private boolean nextCell() {
        this.tier++;
        if (this.tier < this.limits.length) {
            return true;
        }
        this.tier = 0;
        this.rung++;
        return this.rung < this.ladder.length;
    }

    private void startTier() {
        long elapsed = System.currentTimeMillis() - this.startedAt;
        long left = Math.max(300L, this.budgetMs - elapsed);
        long b = Math.max(300L, Math.min((long)(this.shares[this.tier] * this.budgetMs), left));
        int[] beams = new int[this.ladder.length];
        for (int i = 0; i < beams.length; i++) {
            beams[i] = tierBeam(this.ladder[i], this.tier);
        }
        int tierNodes = tierNodes();
        int[] caps = null;
        int firstNodes = 0;
        if (tierNodes > 0) {
            caps = new int[Math.max(0, beams.length - 1)];
            for (int i = 1; i < beams.length; i++) {
                caps[i - 1] = Math.max(200,
                        tierNodes * Solver.PORTFOLIO_SHARES[Math.min(i, Solver.PORTFOLIO_SHARES.length - 1)] / 100);
            }
            firstNodes = Math.max(200, tierNodes * Solver.PORTFOLIO_SHARES[0] / 100);
        }
        long[] deadlines = Solver.rungDeadlines(beams.length, b);
        int[] widen = beams.length <= 1 ? null : Arrays.copyOfRange(beams, 1, beams.length);
        Solver.Session session = new Solver.Session(this.root, beams[0], this.layers, b, firstNodes, 0);
        session.planWidening(widen, deadlines, caps);
        session.limitPotionUses(this.limitFor(this.tier));
        // A single-tier run (no potions in the belt, or the rollback switch) keeps the old ranking
        // exactly: there is nothing to defer and no tier to merge against.
        session.deferPotionCost(this.limits.length > 1);
        int parked = 0;
        int promotions = 0;
        if (this.tier > 0) {
            parked = Math.max(2000, 32 * beams[0]);
            promotions = Math.max(60, beams[0] / 2);
            session.setCaps(parked, promotions);
        }
        // Only a real tier run suppresses the B174 plan-vs-potion-free replay; a single-tier run
        // (no potions, or search.potionTiers=off) keeps the old post-run probe.
        if (this.limits.length > 1) {
            session.markTiered();
        }
        this.current = session;
        this.cellBudgetMs = b;
        this.cellNodesCap = firstNodes;
        this.tiersRan = this.tier + 1;
        Log.info("potion tier: start tier=" + this.tier + "/" + (this.limits.length - 1)
                + " limit=" + this.limitText(this.tier)
                + " beams=" + Arrays.toString(beams)
                + " budgetMs=" + b
                + " firstNodes=" + firstNodes
                + (this.tier > 0 ? " deferredCap=" + parked + " promotions=" + promotions : ""));
    }

    /**
     * P7b: start one (rung, tier) cell. One fresh session, ONE beam (no in-place widening: the
     * point of the cell order is that the next beam is a separate, comparable run), its own potion
     * ceiling, its own budget slice and its own node slice.
     */
    private boolean startCell() {
        long elapsed = System.currentTimeMillis() - this.startedAt;
        long left = this.budgetMs - elapsed;
        if (left <= 300L) {
            this.finishAll("time");
            return false;
        }
        int beam = tierBeam(this.ladder[this.rung], this.tier);
        long wanted = (long)(this.budgetMs * this.rungShares[this.rung] * this.shares[this.tier]);
        long b = Math.max(300L, Math.min(wanted, left));
        int cellNodes = 0;
        if (this.maxNodes > 0) {
            cellNodes = Math.max(200,
                    (int)(this.maxNodes * this.rungShares[this.rung] * this.shares[this.tier]));
        }
        Solver.Session session = new Solver.Session(this.root, beam, this.layers, b, cellNodes, 0);
        // A cell is one beam: no widening ladder inside it.
        session.planWidening(null);
        session.limitPotionUses(this.limitFor(this.tier));
        session.deferPotionCost(true);
        session.markTiered();
        int parked = 0;
        int promotions = 0;
        if (this.tier > 0) {
            parked = Math.max(2000, 32 * beam);
            promotions = Math.max(60, beam / 2);
            session.setCaps(parked, promotions);
        }
        this.current = session;
        this.cellBudgetMs = b;
        this.cellNodesCap = cellNodes;
        this.tiersRan = cellIndex() + 1;
        Log.info("potion cell: start rung=" + (this.rung + 1) + "/" + this.ladder.length
                + " tier=" + (this.tier + 1) + "/" + this.limits.length
                + " limit=" + this.limitText(this.tier)
                + " beam=" + beam
                + " budgetMs=" + b
                + " nodes=" + cellNodes
                + (this.tier > 0 ? " deferredCap=" + parked + " promotions=" + promotions : ""));
        return true;
    }

    private void logTier(Solver.Result r) {
        if (r == null) {
            Log.info("potion tier: done tier=" + this.tier + " no result");
            return;
        }
        Log.info("potion tier: done tier=" + this.tier + "/" + (this.limits.length - 1)
                + " limit=" + this.limitText(this.tier)
                + " win=" + r.win
                + " hp=" + r.hpLoss
                + " net=" + Solver.netOf(r)
                + " potions=" + r.potionsUsed
                + " nodes=" + r.totalNodes
                + " ms=" + r.millis
                + " reason=" + r.reason
                + " proven=" + r.provenOptimal);
        if (this.tier == 0) {
            Log.info("potion tier: baseline tier=0 loss=" + Solver.netOf(r)
                    + " potions=" + r.potionsUsed + " win=" + r.win);
        }
    }

    private void logCell(Solver.Result r) {
        String at = " rung=" + (this.rung + 1) + "/" + this.ladder.length
                + " tier=" + (this.tier + 1) + "/" + this.limits.length
                + " limit=" + this.limitText(this.tier);
        if (r == null) {
            Log.info("potion cell: done" + at + " no result");
            return;
        }
        Log.info("potion cell: done" + at
                + " win=" + r.win
                + " net=" + Solver.netOf(r)
                + " potions=" + r.potionsUsed
                + " nodes=" + r.totalNodes
                + " ms=" + r.millis
                + " reason=" + r.reason
                + " proven=" + r.provenOptimal);
    }

    private void merge(Solver.Result r) {
        if (r == null) {
            return;
        }
        if (this.best == null || better(r, this.best)) {
            this.best = r;
            this.bestTier = this.tier;
            this.bestRung = this.rung;
        }
    }

    /** Net HP first (the panel's ruler); on an exact tie, the line that drank fewer potions wins. */
    private static boolean better(Solver.Result a, Solver.Result b) {
        if (Solver.better(a, b)) {
            return true;
        }
        if (Solver.better(b, a)) {
            return false;
        }
        return a.potionsUsed < b.potionsUsed;
    }

    private void finishAll(String why) {
        if (this.finished) {
            return;
        }
        if (this.best != null) {
            this.best.totalNodes = this.nodesDone;
            this.best.totalExpansions = this.expansionsDone;
            // m07509: use the same definition the panel reads, so the number in the result and the
            // number in passes() cannot drift apart -- tiersRan counts potion ceilings, and a deck
            // with no potions walks the whole widening ladder with tiersRan stuck at 1.
            this.best.passes = this.passes();
        }
        if (this.cellMode) {
            Log.info("potion tiers: cells=" + totalCells() + " ran=" + this.tiersRan
                    + " bestCell=" + (this.best == null ? "-1/-1" : (this.bestRung + 1) + "/" + (this.bestTier + 1))
                    + " why=" + why
                    + " baselineNet=" + (this.baseline == null ? -1 : Solver.netOf(this.baseline))
                    + " bestNet=" + (this.best == null ? -1 : Solver.netOf(this.best))
                    + " bestPotions=" + (this.best == null ? -1 : this.best.potionsUsed));
        } else if (this.limits.length > 1) {
            Log.info("potion tiers: tiers=" + this.limits.length + " ran=" + (this.tier + 1)
                    + " bestTier=" + this.bestTier + " why=" + why
                    + " baselineNet=" + (this.baseline == null ? -1 : Solver.netOf(this.baseline))
                    + " bestNet=" + (this.best == null ? -1 : Solver.netOf(this.best))
                    + " bestPotions=" + (this.best == null ? -1 : this.best.potionsUsed));
        }
        this.finished = true;
    }

    /**
     * m02751: snapshot the ladder numbers before the session that owns them is dropped, so
     * {@link #rung()} / {@link #rungs()} keep answering for a job that has already finished.
     */
    private void rememberRung() {
        if (this.current != null) {
            this.lastRung = this.current.rung();
            this.lastRungs = this.current.rungs();
            this.lastBeam = this.current.beamWidth();
        }
    }

    private void tierDone(Solver.Result r) {
        if (this.tier == 0) {
            this.baseline = r;
        }
        this.merge(r);
        this.logTier(r);
        this.rememberRung();
        this.current = null;
        if (this.stopRequested) {
            this.finishAll("stopped");
            return;
        }
        // A proven-optimal win, or a win that costs nothing on the shared ruler, cannot be improved
        // by drinking more potions; the later tiers would only re-search the same fight.
        if (r != null && r.win && (r.provenOptimal || Solver.netOf(r) <= 0)) {
            this.finishAll("proven");
            return;
        }
        if (this.tier + 1 >= this.limits.length) {
            this.finishAll("tiers");
            return;
        }
        this.tier++;
    }

    private void cellDone(Solver.Result r) {
        if (r != null && this.tier == 0
                && (this.baseline == null || Solver.better(r, this.baseline))) {
            this.baseline = r;
            Log.info("potion tier: baseline tier=0 loss=" + Solver.netOf(this.baseline)
                    + " potions=" + this.baseline.potionsUsed + " win=" + this.baseline.win
                    + " cells=" + (cellIndex() + 1) + "/" + totalCells());
        }
        this.merge(r);
        this.logCell(r);
        this.rememberRung();
        this.current = null;
        if (this.stopRequested) {
            this.finishAll("stopped");
            return;
        }
        if (r != null && r.win && (r.provenOptimal || Solver.netOf(r) <= 0)) {
            this.finishAll("proven");
            return;
        }
        if (!this.nextCell()) {
            this.finishAll("cells");
        }
    }

    public boolean advance(long sliceMs) {
        if (this.finished) {
            return true;
        }
        if (this.current == null) {
            if (this.stopRequested) {
                this.finishAll("stopped");
                return true;
            }
            if (this.cellMode) {
                if (!this.startCell()) {
                    return true;
                }
            } else {
                long left = this.budgetMs - (System.currentTimeMillis() - this.startedAt);
                if (this.tier > 0 && left <= 250L) {
                    this.finishAll("time");
                    return true;
                }
                this.startTier();
            }
        }
        if (this.stopRequested) {
            this.current.requestStop();
        }
        if (!this.current.advance(Math.max(4L, sliceMs))) {
            return false;
        }
        Solver.Result r = this.current.result();
        this.nodesDone += this.current.nodes();
        this.expansionsDone += this.current.expansions();
        if (this.cellMode) {
            this.cellDone(r);
        } else {
            this.tierDone(r);
        }
        return this.finished;
    }

    public boolean finished() {
        return this.finished;
    }

    public Solver.Result result() {
        return this.best;
    }

    public void requestStop() {
        this.stopRequested = true;
        Solver.Session s = this.current;
        if (s != null) {
            s.requestStop();
        }
    }

    /** Total nodes of every tier finished so far plus the running one (panel total). */
    public int nodes() {
        if (this.finished) {
            return this.nodesDone;
        }
        return this.nodesDone + (this.current == null ? 0 : this.current.nodes());
    }

    public int passNodes() {
        return this.current == null ? 0 : this.current.passNodes();
    }

    public int expansions() {
        if (this.finished) {
            return this.expansionsDone;
        }
        return this.expansionsDone + (this.current == null ? 0 : this.current.expansions());
    }

    /**
     * Passes run so far (1-based while running).
     *
     * <p>m07509: a pass is a RUNG, not a potion tier. Since B177/B179 the widening ladder runs
     * inside one session, so {@code tiersRan} counts potion ceilings and stays at 1 on a deck with
     * no potions -- which made {@link sts1solver.search.QuickSession#passes()} answer 1 on a job
     * that had walked all four rungs, and S230 read "a win from the narrow pass does not end the
     * job" as failed on a job that had widened three times. In cell mode one cell is one pass, so
     * the cell counter is still the right number there.
     */
    public int passes() {
        if (this.cellMode) {
            return Math.max(1, this.tiersRan);
        }
        return Math.max(1, rung() + 1);
    }

    public int rung() {
        if (this.cellMode) {
            return this.rung;
        }
        // m02751: a finished job still answers -- see lastRung.
        return this.current != null ? this.current.rung() : this.lastRung;
    }

    public int rungs() {
        if (this.cellMode) {
            return this.ladder.length;
        }
        // m02751: same rule as rung(); the ladder size outlives the session that walked it.
        return this.current != null ? this.current.rungs() : this.lastRungs;
    }

    public int beamWidth() {
        // m07509: same rule as rung(); a finished job still answers with the beam it reached.
        return this.current != null ? this.current.beamWidth() : this.lastBeam;
    }

    public int layersRun() {
        return this.current == null ? 0 : this.current.layersRun();
    }

    public int bestActions() {
        if (this.finished) {
            return this.best == null ? 0 : this.best.plan.size();
        }
        if (this.current != null) {
            return this.current.bestActions();
        }
        return this.best == null ? 0 : this.best.plan.size();
    }

    public List<Action> bestPath() {
        if (this.finished) {
            return null;
        }
        return this.current == null ? null : this.current.bestPath();
    }

    public boolean bestIsWin() {
        if (this.finished) {
            return this.best != null && this.best.win;
        }
        if (this.current != null) {
            return this.current.bestIsWin();
        }
        return this.best != null && this.best.win;
    }

    public int bestHpLoss() {
        if (this.finished) {
            return this.best == null ? -1 : this.best.hpLoss;
        }
        if (this.current != null) {
            int running = this.current.bestHpLoss();
            if (running >= 0) {
                return running;
            }
        }
        return this.best == null ? -1 : this.best.hpLoss;
    }

    public int bestNetHpLoss() {
        if (this.finished) {
            return this.best == null ? -1 : Solver.netOf(this.best);
        }
        if (this.current != null) {
            int running = this.current.bestNetHpLoss();
            if (running >= 0) {
                return running;
            }
        }
        return this.best == null ? -1 : Solver.netOf(this.best);
    }

    /** B115: the line the panel should preview right now. */
    public List<Action> previewPath() {
        List<Action> running = bestPath();
        if (running != null) {
            return running;
        }
        if (this.best == null || this.best.plan == null || this.best.plan.isEmpty()) {
            return null;
        }
        ArrayList<Action> out = new ArrayList<Action>(this.best.plan.size());
        for (Solver.Step step : this.best.plan) {
            out.add(step.action);
        }
        return out;
    }

    public boolean previewWin() {
        return bestIsWin();
    }

    public int previewHpLoss() {
        return bestHpLoss();
    }

    public int previewNetHpLoss() {
        return bestNetHpLoss();
    }

    public void compensatePause(long ms) {
        if (ms > 0L) {
            this.startedAt += ms;
            if (this.current != null) {
                this.current.compensatePause(ms);
            }
        }
    }

    /** 1.0.0 (user m01050): the turn promise the running cell was built with (0 = plan to the end). */
    public int horizonTurns() {
        return this.current == null ? 0 : this.current.horizonTurns();
    }

    /** The promise the running cell started with, so the panel can say it was lowered. */
    public int horizonLimit() {
        return this.current == null ? 0 : this.current.horizonLimit();
    }

    /** Whole turns the running cell has finished past its root. */
    public int turnsFinished() {
        return this.current == null ? 0 : this.current.turnsFinished();
    }

    public String phase() {
        if (this.finished) {
            return "done";
        }
        if (this.current == null) {
            return "准备中";
        }
        String h = hint();
        String body;
        if (this.cellMode) {
            body = "beam=" + this.current.beamWidth() + " layers=" + this.current.layersRun();
        } else if (this.current.rung() > 0) {
            body = "加宽搜 beam=" + this.current.beamWidth() + "（第 "
                    + (this.current.rung() + 1) + "/" + this.current.rungs() + " 档）";
        } else {
            body = "窄搜 beam=" + this.current.beamWidth() + " layers=" + this.current.layersRun();
        }
        return h.isEmpty() ? body : h + " \u00b7 " + body;
    }

    /**
     * P7b (user m00331): the one-line potion-worldline hint the panel and the status line show, e.g.
     * {@code 药水 ≤1 瓶（2/3，槽 3）· beam 28（2/4 档）· 0 瓶基线 净损 15}. Empty outside cell mode, so
     * the untiered run and the tier-order rollback print exactly what they printed before. The
     * {@code 槽 N} part (P7c) is the belt capacity the game reported, which is why the tier ceiling
     * can exceed the vanilla three.
     */
    public String hint() {
        // P7d: the hint is shown for the tier layout too -- with the tier order as the default, the
        // "which potion worldline is running" line would otherwise disappear from the panel. Only
        // the untiered run (one limit: no potions in the belt / search.potionTiers=off) stays silent.
        if (this.limits.length <= 1) {
            return "";
        }
        int t = Math.max(0, Math.min(this.tier, this.limits.length - 1));
        int r = Math.max(0, Math.min(this.rung, this.ladder.length - 1));
        StringBuilder b = new StringBuilder();
        b.append("药水 \u2264").append(this.limitText(t)).append(" 瓶（")
                .append(t + 1).append("/").append(this.limits.length);
        if (this.beltSlots > 0) {
            b.append("，槽 ").append(this.beltSlots);
        }
        b.append("）");
        int beam = this.current != null ? this.current.beamWidth() : tierBeam(this.ladder[r], t);
        b.append(" \u00b7 beam ").append(beam);
        if (this.cellMode) {
            b.append("（").append(r + 1).append("/").append(this.ladder.length).append(" 档）");
        } else if (this.current != null && this.current.rungs() > 1) {
            b.append("（加宽 ").append(this.current.rung() + 1)
                    .append("/").append(this.current.rungs()).append(" 档）");
        }
        if (this.baseline != null) {
            b.append(" \u00b7 0 瓶基线 净损 ").append(Solver.netOf(this.baseline));
        }
        return b.toString();
    }

    /** P7c test hook: the drinkable potions / belt slots the game reported when the job was built. */
    public int usablePotionsForTest() {
        return this.usablePotions;
    }

    /** P7c test hook: belt capacity the game reported (0 = not read / rollback). */
    public int slotsForTest() {
        return this.beltSlots;
    }

    /** P7b test hook: the job runs one session per (rung, tier) cell. */
    public boolean cellMode() {
        return this.cellMode;
    }

    /** P7b: cells this job will run (the untiered rollback reports 1). */
    public int cellsForTest() {
        return this.cellMode ? totalCells() : 1;
    }

    /** P7b: the running cell's potion ceiling (or the running tier's in tier order). */
    public int curLimit() {
        return this.limitFor(Math.max(0, Math.min(this.tier, this.limits.length - 1)));
    }

    /** P7b: the running cell's budget in milliseconds. */
    public long cellBudgetMs() {
        return this.cellBudgetMs;
    }

    /** P7 test hook: how many tiers this job will run (1 = untiered / no potions). */
    public int tiersForTest() {
        return this.limits.length;
    }

    /** P7 test hook: the tier that produced {@link #result()}, or -1 while none finished. */
    public int bestTierForTest() {
        return this.bestTier;
    }

    /** P7b test hook: the rung that produced {@link #result()}, or -1 while none finished. */
    public int bestRungForTest() {
        return this.bestRung;
    }
}
