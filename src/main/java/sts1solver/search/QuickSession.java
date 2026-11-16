package sts1solver.search;

import sts1solver.mirror.World;

/**
 * P2-9: the F8 quick solve as a slice-able job.
 *
 * <p>F8 used to call {@link Solver#solvePortfolio} on the render thread, so the game froze for the
 * whole budget (3-12s). The deep search (Shift+F8) already solved this by advancing a
 * {@link Solver.PortfolioSession} in ~40ms slices between frames; this class gives the quick path
 * the same shape without changing the algorithm:
 *
 * <ul>
 *   <li>ordinary fights: exactly the blocking portfolio, just paced in slices;
 *   <li>expensive fights (boss room or a monster with 200+ max HP): the same narrow-deep pass
 *       {@code Solver.solve(root, 10, 96, budget)} the blocking path ran, then the B171 widening
 *       ladder -- B177 (user m23168) runs the whole ladder inside ONE {@link Solver.Session}, so a
 *       wider rung continues from the layer the narrow rung reached instead of re-expanding the
 *       layers it had already run.
 * </ul>
 *
 * <p>The live world is never touched while a slice runs: each {@link Solver.Session} installs its
 * own copy and uninstalls it again inside {@code microStep}, exactly like the blocking call.
 */
public final class QuickSession {

    /** B171: the first rung, unchanged -- the short pass that gives a usable answer fast. */
    private static final int NARROW_BEAM = 10;

    private final World root;
    private final long budgetMs;
    private final boolean narrowDeep;
    /** B119: not final -- a paused span is handed back instead of being billed to the budget. */
    private long startedAt = System.currentTimeMillis();

    private Solver.PortfolioSession portfolio;
    /* B177 (user m23168, option C): ONE session runs the whole ladder. The narrow rung and every
     * wider rung used to be separate Sessions built from the same root, so each rung re-expanded
     * the layers the previous one had already run. The session now widens in place: same frontier,
     * same layer, same visited table; a wider beam only means the parked lines come back. */
    private PotionTieredSession session;
    private Solver.Result best;
    private int finishedNodes;
    private int finishedExpansions;
    /** B171: the ladder, cached once per job (ascending, first widening rung first). */
    private int[] beams;
    private boolean finished;
    private volatile boolean stopRequested;

    // Heartbeat: when the node counter last moved. A long stall means the engine is stuck inside one
    // settlement (a card or power that loops), which is exactly what the panel must say instead of
    // looking frozen.
    private int lastNodes;
    private long lastProgressAt = this.startedAt;

    public QuickSession(World world, long budgetMs, boolean narrowDeep) {
        this.root = world;
        this.budgetMs = Math.max(200L, budgetMs);
        this.narrowDeep = narrowDeep;
    }

    public void requestStop() {
        this.stopRequested = true;
        Solver.PortfolioSession p = this.portfolio;
        if (p != null) {
            p.requestStop();
        }
        if (this.session != null) {
            this.session.requestStop();
        }
    }

    public boolean finished() {
        return this.finished;
    }

    public Solver.Result result() {
        return this.best;
    }

    public long budgetMs() {
        return this.budgetMs;
    }

    /**
     * B119 (m02060): do not bill the search for time the game spent paused.
     *
     * <p>Every deadline in here is {@code System.currentTimeMillis() - startedAt}: shifting the
     * start (and the heartbeat, or a long pause would read as a stall) forward by the paused span
     * is the whole fix. The running pass gets the same shift.
     */
    public void compensatePause(long ms) {
        if (ms <= 0L) {
            return;
        }
        this.startedAt += ms;
        if (this.lastProgressAt != 0L) {
            this.lastProgressAt += ms;
        }
        if (this.session != null) {
            this.session.compensatePause(ms);
        }
        if (this.portfolio != null) {
            this.portfolio.compensatePause(ms);
        }
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - this.startedAt;
    }

    public int nodes() {
        int n = this.finishedNodes;
        if (this.portfolio != null) {
            n += this.portfolio.nodes();
        }
        if (this.session != null) {
            n += this.session.nodes();
        }
        if (n != this.lastNodes) {
            this.lastNodes = n;
            this.lastProgressAt = System.currentTimeMillis();
        }
        return n;
    }

    /** Harness hook (B177): rungs the single session was given (1 = no widening ladder). */
    public int rungsForTest() {
        return this.session == null ? 0 : this.session.rungs();
    }

    /** Harness hook (B177): rung the single session is on (0 = still the narrow rung). */
    public int rungForTest() {
        return this.session == null ? -1 : this.session.rung();
    }

    /** P2-11b: nodes of the pass currently running (the panel shows total AND this pass). */
    public int passNodes() {
        if (this.portfolio != null) {
            return this.portfolio.passNodes();
        }
        return this.session == null ? 0 : this.session.passNodes();
    }

    /**
     * B115 (m00748): the best line this job can show right now, without replaying it.
     *
     * <p>Null until some pass holds a line. Costs one small copy and never touches the live world;
     * the panel calls it at most once per slice.
     */
    public java.util.List<Action> previewPath() {
        java.util.List<Action> path = this.session != null ? this.session.bestPath() : null;
        if (path != null) {
            return path;
        }
        if (this.portfolio != null) {
            path = this.portfolio.previewPath();
            if (path != null) {
                return path;
            }
        }
        if (this.best == null || this.best.plan == null || this.best.plan.isEmpty()) {
            return null;
        }
        java.util.ArrayList<Action> out = new java.util.ArrayList<Action>(this.best.plan.size());
        for (Solver.Step step : this.best.plan) {
            out.add(step.action);
        }
        return out;
    }

    /**
     * B120 (m02336): whether the line {@link #previewPath()} holds already reaches victory. Same
     * order of preference as the path itself; false when nothing is held.
     */
    public boolean previewWin() {
        if (this.session != null && this.session.bestPath() != null) {
            return this.session.bestIsWin();
        }
        if (this.portfolio != null) {
            return this.portfolio.previewWin();
        }
        return this.best != null && this.best.win;
    }

    /**
     * B118 (m01978): expected HP cost of {@link #previewPath()}, in the same order of preference.
     *
     * <p>Comes from the node the winning pass is holding, so it is the search's own number, not a
     * replay: no sandbox work on the render thread. -1 when nothing is held.
     */
    public int previewHpLoss() {
        if (this.session != null && this.session.bestPath() != null) {
            return this.session.bestHpLoss();
        }
        if (this.portfolio != null) {
            int hp = this.portfolio.previewHpLoss();
            if (hp >= 0) {
                return hp;
            }
        }
        return this.best == null ? -1 : this.best.hpLoss;
    }

    /**
     * B173 (user m21300): expected cost of {@link #previewPath()} on the shared ruler (growth,
     * post-combat heal and loot credited), in the same order of preference as the path itself.
     * The panel prints THIS number, so a line the search prefers cannot print a bigger cost than
     * the line it replaced.
     */
    public int previewNetHpLoss() {
        if (this.session != null && this.session.bestPath() != null) {
            return this.session.bestNetHpLoss();
        }
        if (this.portfolio != null) {
            int net = this.portfolio.previewNetHpLoss();
            if (net >= 0) {
                return net;
            }
        }
        return this.best == null ? -1 : Solver.netOf(this.best);
    }

    /**
     * P2-11b: copy the job-wide numbers onto the answer. The panel counts every pass, so a result
     * that only knew its own pass would look like a different (smaller) search than the panel.
     */
    private void stampTotals() {
        if (this.best == null) {
            return;
        }
        this.best.totalNodes = this.finishedNodes
                + (this.portfolio == null ? 0 : this.portfolio.nodes())
                + (this.session == null ? 0 : this.session.nodes());
        this.best.totalExpansions = this.finishedExpansions
                + (this.portfolio == null ? 0 : Math.max(0, this.portfolio.expansions()))
                + (this.session == null ? 0 : Math.max(0, this.session.expansions()));
        this.best.passes = Math.max(1, this.passes());
    }

    /** Expanded states per second so far; 0 while the first slice is still running. */
    public long nodesPerSecond() {
        long ms = this.elapsedMs();
        if (ms < 200L) {
            return 0L;
        }
        return this.nodes() * 1000L / ms;
    }

    /** Milliseconds since the node counter last moved. */
    public long stalledMs() {
        this.nodes();
        return System.currentTimeMillis() - this.lastProgressAt;
    }

    public int bestActions() {
        if (this.best != null) {
            return this.best.plan.size();
        }
        return this.session == null ? 0 : this.session.bestActions();
    }

    public int layers() {
        if (this.portfolio != null) {
            return 0;
        }
        return this.session == null ? 0 : this.session.layersRun();
    }

    /** Short human description of the current pass, shown under the progress lines. */
    public String phase() {
        if (this.finished) {
            return "done";
        }
        if (!this.narrowDeep) {
            return this.portfolio == null ? "准备中" : this.portfolio.phase();
        }
        if (this.session == null) {
            return "准备中";
        }
        // P7d: the potion-worldline hint now also belongs to the tier layout (the default again),
        // so the panel keeps showing which potion ceiling is running.
        String h = this.session.hint();
        String body;
        if (this.session.cellMode()) {
            // P7b: a cell is a single beam, so the in-place "widened to rung N" text would be wrong.
            return this.session.phase();
        }
        if (this.session.rung() > 0) {
            body = "加宽搜 beam=" + this.session.beamWidth() + "（第 "
                    + (this.session.rung() + 1) + "/" + this.session.rungs() + " 档）";
        } else {
            body = "窄深搜 beam=" + NARROW_BEAM + " layers=" + this.session.layersRun();
        }
        return h.isEmpty() ? body : h + " \u00b7 " + body;
    }

    /**
     * P7b: the potion-worldline hint the status line shows, e.g. {@code 药水 ≤1 瓶（2/3）· beam 28
     * （2/4 档）}. Empty before a session exists and in the tier-order rollback, so nothing is added.
     */
    public String potionHint() {
        if (this.portfolio != null) {
            return this.portfolio.hint();
        }
        return this.session == null ? "" : this.session.hint();
    }

    /**
     * Advance the job by at most {@code sliceMs}. Returns true when the whole job is finished.
     */
    public boolean advance(long sliceMs) {
        if (this.finished) {
            return true;
        }
        if (!this.narrowDeep) {
            if (this.portfolio == null) {
                this.portfolio = new Solver.PortfolioSession(this.root, this.budgetMs, 0);
            }
            if (this.stopRequested) {
                this.portfolio.requestStop();
            }
            if (!this.portfolio.advance(sliceMs)) {
                return false;
            }
            this.best = this.portfolio.result();
            this.finished = true;
            return true;
        }
        if (this.session == null) {
            // B177: one session for the whole ladder. P7 (user m00001): that ladder now repeats once
            // per potion tier, inside PotionTieredSession; the narrow rung keeps its unlimited node
            // count, and each tier still widens in place (Session.widenToNextRung).
            int[] widen = this.widenBeams();
            int[] ladder = new int[widen.length + 1];
            ladder[0] = NARROW_BEAM;
            System.arraycopy(widen, 0, ladder, 1, widen.length);
            this.session = new PotionTieredSession(this.root, ladder, Limits.layers(), this.remaining(), 0);
        }
        if (this.stopRequested) {
            this.session.requestStop();
        }
        if (!this.session.advance(sliceMs)) {
            return false;
        }
        this.best = this.session.result();
        this.stampTotals();
        this.finished = true;
        return true;
    }

    /**
     * B177: how many passes the job has run (1 while the narrow rung is still going). Read by the
     * gate and by the panel; a pass is a rung, and the rungs now share one session.
     */
    public int passes() {
        if (this.portfolio != null) {
            // B179: the portfolio is a ladder inside one session now, so a pass is a rung here too.
            return this.portfolio.passes();
        }
        return this.session == null ? 0 : this.session.passes();
    }

    /**
     * B171: the widening ladder, ascending, strictly wider than the first rung, capped by the
     * configured {@link Limits#beam()} so "unlimited" reaches 256 instead of stopping at 40.
     */
    private int[] widenBeams() {
        if (this.beams != null) {
            return this.beams;
        }
        int top = Math.max(NARROW_BEAM + 1, Limits.beam());
        int[] candidates = new int[] {28, 64, top};
        java.util.Arrays.sort(candidates);
        int[] out = new int[candidates.length];
        int n = 0;
        for (int i = 0; i < candidates.length; ++i) {
            int b = candidates[i];
            if (b <= NARROW_BEAM || (n > 0 && out[n - 1] == b)) {
                continue;
            }
            out[n++] = b;
        }
        this.beams = java.util.Arrays.copyOf(out, n);
        return this.beams;
    }

    private long remaining() {
        return Math.max(300L, this.budgetMs - (System.currentTimeMillis() - this.startedAt));
    }
}