package sts1solver.search;

import java.util.ArrayList;
import java.util.List;
import sts1solver.mirror.Digest;
import sts1solver.mirror.RenderState;
import sts1solver.mirror.World;
import sts1solver.util.Log;

/**
 * P3-A (user m07004): solve the board the plan is about to walk into, before it gets there.
 *
 * <p>The search is a single-threaded pipeline by construction -- {@code World.install()} swaps
 * JVM-global statics, so two searches cannot run at once ({@code DESIGN.md}, "Deliberate
 * non-goals"). What CAN overlap is a search and the autopilot's own waiting: when the plan has
 * only a step or two left, the board those steps lead to is already known, and the solver will be
 * asked to solve exactly that board a moment later.
 *
 * <p>So: replay the tail of the plan into a fork ({@link Solver#replayKeepingEnd}), keep the end
 * board, and start a normal {@link QuickSession} on it. Slices are advanced from the pace-wait
 * frames, which is dead time the player already accepts.
 *
 * <p>Adoption is anchored on the digest, not on trust: the prefetched answer is only used when the
 * LIVE board hashes to the same thing the sandbox predicted. Anything else -- a different card, a
 * monster that moved, a potion -- throws the prediction away and the caller solves from the live
 * board exactly as before. That is the whole point: the replay check moves from "verify
 * afterwards" to "anchor beforehand".
 *
 * <p>Every entry point here is allowed to fail and leave nothing behind. The live path never waits
 * for this class.
 */
public final class Prefetch {

    /** Tail length worth predicting. Past this the plan is long enough to just play it. */
    public static final int TAIL_MAX_STEPS = 2;

    /** The predicted end board. Owned here; dropped the moment it stops being useful. */
    private static World end;
    /** Digest of {@link #end}, read on the sandbox after the tail replay. */
    private static String endDigest;
    /** RNG position of {@link #end}, read on the sandbox (diagnostic + stricter matching). */
    private static String endRng;
    /** The search running on the predicted board, or null. */
    private static QuickSession session;
    /** Static colours before the prediction started, so its slices cannot leave the screen recoloured. */
    private static RenderState.StaticColors staticsBefore;
    /** Labels of the tail being predicted, in order. Used to notice that the plan moved on. */
    private static List<String> tailLabels;

    /** A finished answer waiting for its board to become live. */
    private static Solver.Result ready;
    private static String readyDigest;
    private static String readyRng;

    private static int started;
    private static int adopted;
    private static int discarded;

    private Prefetch() {
    }

    /** True while a prediction is being searched. */
    public static boolean busy() {
        return session != null;
    }

    /** True when an answer is waiting for its board. */
    public static boolean hasReady() {
        return ready != null;
    }

    /**
     * Replay {@code tail} into a fork, keep the end board and start searching it.
     *
     * <p>Returns false -- having dropped whatever was there -- when the walk does not complete.
     * The caller then does nothing: the live path is unchanged.
     */
    public static boolean begin(World liveRoot, List<Action> tail, long budgetMs, boolean narrowDeep) {
        drop();
        if (liveRoot == null || tail == null || tail.isEmpty()) {
            return false;
        }
        try {
            staticsBefore = RenderState.captureStaticColorsOnly();
            Solver.ReplayEnd walk = Solver.replayKeepingEnd(liveRoot, tail);
            if (!walk.complete || walk.end == null || walk.endDigest.isEmpty()) {
                discarded++;
                Log.info("prefetch: the tail did not replay, nothing predicted | steps="
                        + tail.size() + " failedAt=" + Solver.lastReplayFailedAt()
                        + " why=" + Solver.lastReplayFailure());
                drop();
                return false;
            }
            end = walk.end;
            endDigest = walk.endDigest;
            endRng = walk.endRng;
            tailLabels = labelsOf(tail);
            session = new QuickSession(end, budgetMs, narrowDeep);
            started++;
            Log.info("prefetch: searching the board " + tail.size() + " step(s) ahead, budget="
                    + budgetMs + "ms | digest=" + Digest.stable(endDigest));
            return true;
        } catch (Throwable t) {
            Log.warn("prefetch: could not start (" + t + ")");
            drop();
            return false;
        }
    }

    /**
     * Advance the prediction by one slice.
     *
     * <p>Cheap and safe to call every frame: it does nothing unless a prediction is in flight. The
     * predicted board is deliberately NOT invalidated when the live board changes -- the live board
     * changing is the whole point, and {@link #take(String)} is the gate that decides whether it
     * changed into what was predicted.
     */
    public static void tick(long sliceMs) {
        if (session == null) {
            return;
        }
        try {
            boolean done = session.advance(Math.max(1L, sliceMs));
            repairStatics();
            if (!done) {
                return;
            }
            Solver.Result r = session.result();
            String digest = endDigest;
            String rng = endRng;
            session = null;
            end = null;
            endDigest = null;
            endRng = null;
            if (r == null || r.plan == null || r.plan.isEmpty()) {
                discarded++;
                Log.info("prefetch: finished without a line, nothing to adopt");
                return;
            }
            ready = r;
            readyDigest = digest;
            readyRng = rng;
            Log.info("prefetch: ready, steps=" + r.plan.size() + " win=" + r.win
                    + " (waiting for the board) | digest=" + Digest.stable(readyDigest));
        } catch (Throwable t) {
            Log.warn("prefetch: slice failed (" + t + ")");
            drop();
        }
    }

    /**
     * Whether the plan still leads where the prediction said.
     *
     * <p>{@code remaining} is what the plan has left. It shrinks as the autopilot plays, and every
     * intermediate value is a PREFIX of the tail that was predicted, so comparing against the head
     * of the recorded tail is enough. A plan that was replaced (drift, a new search) fails this at
     * once and the prediction is dropped.
     */
    public static boolean stillApplies(List<Action> remaining) {
        if (tailLabels == null) {
            return false;
        }
        int n = remaining == null ? 0 : remaining.size();
        if (n > tailLabels.size()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            Action a = remaining.get(i);
            String label = a == null ? "" : a.label;
            if (!tailLabels.get(i).equals(label)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Take the finished answer if it belongs to the live board.
     *
     * <p>{@code liveDigest} is the digest the caller just read from the live game. A null return
     * means "solve it yourself", which is the normal path and costs nothing extra.
     */
    public static Solver.Result take(String liveDigest) {
        if (ready == null) {
            return null;
        }
        String live = Digest.stable(liveDigest);
        if (!live.equals(Digest.stable(readyDigest))) {
            Log.info("prefetch: answer does not fit the live board, discarded | predicted="
                    + Digest.stable(readyDigest) + " live=" + live
                    + (readyRng.isEmpty() ? "" : " rng=" + readyRng));
            drop();
            return null;
        }
        Solver.Result r = ready;
        adopted++;
        Log.info("prefetch: adopted, steps=" + r.plan.size() + " win=" + r.win
                + " saved a whole search | digest=" + live);
        drop();
        return r;
    }

    /** Throw the prediction away. Safe to call at any time, including from the live path. */
    public static void drop() {
        session = null;
        end = null;
        endDigest = null;
        endRng = null;
        staticsBefore = null;
        tailLabels = null;
        ready = null;
        readyDigest = null;
        readyRng = null;
    }

    /** B59 parity: a slice that recolours statics must put them back before the frame draws. */
    private static void repairStatics() {
        if (staticsBefore == null) {
            return;
        }
        try {
            if (!RenderState.diffStatics(staticsBefore).isEmpty()) {
                int fixed = RenderState.repairStatics(staticsBefore);
                Log.warn("prefetch: the search recoloured the screen, repaired " + fixed
                        + " static colour(s)");
            }
        } catch (Throwable t) {
            Log.warn("prefetch: static repair failed (" + t + ")");
        }
    }

    private static List<String> labelsOf(List<Action> actions) {
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            out.add(a == null ? "" : a.label);
        }
        return out;
    }

    public static int startedForTest() {
        return started;
    }

    public static int adoptedForTest() {
        return adopted;
    }

    public static int discardedForTest() {
        return discarded;
    }

    public static String describeForTest() {
        return "busy=" + busy() + " ready=" + hasReady()
                + " started=" + started + " adopted=" + adopted + " discarded=" + discarded;
    }

    /** The digest the ready answer was computed for. Gates need it to prove the adoption gate. */
    public static String predictedDigestForTest() {
        return readyDigest;
    }

    public static void resetForTest() {
        drop();
        started = 0;
        adopted = 0;
        discarded = 0;
    }

    /** Copy of a plan tail as bare actions, which is what the replay takes. */
    public static List<Action> actionsOf(List<Solver.Step> steps, int from) {
        ArrayList<Action> out = new ArrayList<Action>();
        if (steps == null) {
            return out;
        }
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            Solver.Step s = steps.get(i);
            if (s != null && s.action != null) {
                out.add(s.action);
            }
        }
        return out;
    }
}
