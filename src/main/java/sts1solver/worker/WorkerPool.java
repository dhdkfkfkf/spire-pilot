package sts1solver.worker;

import com.badlogic.gdx.Gdx;

import sts1solver.mirror.World;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A pre-warmed worker process (P1-2h stage B): one game launch serves many requests.
 *
 * <p>Stage D measured the cost that matters: a worker's answer is right, but a second game process
 * needs ~10-12 s before it can answer at all, so a one-shot worker only pays off for searches that
 * run for minutes. This class removes that ceiling by keeping the process alive after boot: it warms
 * the engine once, announces itself with a ready file, then pulls requests from a shared directory
 * and writes results back. Cold start is paid once per launch instead of once per task.
 *
 * <p>What is deliberately NOT here: any orchestration. Starting processes, choosing a worker and
 * comparing results stay in tools\worker-pool.ps1 -- the game process must never schedule itself,
 * and a measurement must never become part of the hot path.
 *
 * <p>Protocol (text files in one exchange directory, so a crash leaves everything inspectable):
 * <pre>
 * queue/task-0001.request   written by the orchestrator (tmp + atomic move, so a visible file is
 *                           complete). Content is the ordinary worker request; "out=" names the
 *                           result file in this same directory.
 * task-0001.request.claim   created by the worker with an ATOMIC_MOVE: rename is the lock, so two
 *                           workers polling the same directory can never solve the same task.
 * done/task-0001.request    where a finished (or failed) request is filed, kept for inspection.
 * ready-&lt;tag&gt;               written once warm: pid, warm-up ms, served count.
 * bye-&lt;tag&gt;                 written on a clean shutdown: why and how many tasks were served.
 * stop                      created by the orchestrator: every worker exits after its current task.
 * </pre>
 *
 * <p>The pool loop runs on the game thread (it is the thread that already owns the engine and the
 * show); one task can freeze rendering for the length of its solve, exactly as the one-shot worker
 * did. Requests are handled one per frame so the window still gets frames between tasks.
 */
public final class WorkerPool {

    /** Exchange directory name inside the solver home directory. */
    public static final String POOL_DIR = "pool";
    public static final String DONE_DIR = "done";
    public static final String READY_PREFIX = "ready-";
    public static final String BYE_PREFIX = "bye-";
    public static final String STOP_FILE = "stop";
    public static final String REQUEST_SUFFIX = ".request";
    public static final String CLAIM_SUFFIX = ".claim";
    /** Idle exit: a pool with no work must not hold a game process forever. */
    public static final long DEFAULT_IDLE_MS = 900000L;

    private static boolean active;
    private static File exchange;
    private static long idleMs = DEFAULT_IDLE_MS;
    private static long lastWorkMs;
    private static int served;
    private static long warmMs;

    private WorkerPool() {
    }

    /** True once {@link #start()} was called: the frame hook then owns the loop. */
    public static boolean active() {
        return active;
    }

    /** Number of tasks served by this process (0 for any process that never started a pool). */
    public static synchronized int served() {
        return served;
    }

    /** Warm-up duration of this process in milliseconds (0 until it is warm). */
    public static synchronized long warmMillis() {
        return warmMs;
    }

    public static synchronized File root() {
        return exchange == null ? new File(Log.dir(), POOL_DIR) : exchange;
    }

    /** Instance tag used in the ready/bye file names; the main process is "main". */
    public static String tag() {
        String inst = Log.instance();
        return inst.isEmpty() ? "main" : inst;
    }

    public static File readyFile(File root) {
        return new File(root, READY_PREFIX + tag());
    }

    public static File byeFile(File root) {
        return new File(root, BYE_PREFIX + tag());
    }

    public static File stopFile(File root) {
        return new File(root, STOP_FILE);
    }

    public static File doneDir(File root) {
        return new File(root, DONE_DIR);
    }

    /**
     * Starts the pool in this process: warm the engine, announce readiness, then let
     * {@link #tick()} pull work. The idle timeout comes from the startup request
     * ({@code idleSeconds=}), so a bench can ask for a short-lived pool.
     */
    public static synchronized void start() {
        if (active) {
            return;
        }
        exchange = new File(Log.dir(), POOL_DIR);
        idleMs = DEFAULT_IDLE_MS;
        // The startup file's FIRST LINE is the mode ("worker-pool"), so the one-shot request parser
        // (which demands "worker") rightly rejects it; read only the keys this mode cares about.
        try {
            File request = new File(Log.pathFor("autotest.request"));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(request), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("idleSeconds=")) {
                        idleMs = Math.max(1L, Long.parseLong(
                                line.substring("idleSeconds=".length()).trim())) * 1000L;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            Log.warn("pool: cannot read the startup request: " + t);
        }
        if (!exchange.exists()) {
            exchange.mkdirs();
        }
        doneDir(exchange).mkdirs();
        new File(byeFile(exchange).getAbsolutePath()).delete();
        warmMs = warmUp();
        markReady(exchange, warmMs);
        lastWorkMs = System.currentTimeMillis();
        active = true;
        Log.info("pool: ready (pid=" + pid() + " warm=" + warmMs + "ms idle=" + idleMs + "ms dir="
                + exchange.getAbsolutePath() + ")");
    }

    /** One frame of the pool: stop check, then at most one task. */
    public static synchronized void tick() {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        if (stopRequested(root())) {
            shutdown("stop");
            return;
        }
        File result = serveOnce(root());
        if (result != null) {
            served++;
            lastWorkMs = now;
            markReady(root(), warmMs);
            return;
        }
        if (served > 0 && now - lastWorkMs >= idleMs) {
            shutdown("idle");
        }
    }

    /**
     * Warms the engine once: builds a small fight and runs a tiny real search.
     *
     * <p>Cold start is mostly the game itself, but the first solve in a fresh process also pays for
     * class loading (cards, relics, monsters, powers) and JIT. Paying that before the ready file
     * exists is what makes the first real task as fast as the fifth.
     */
    public static long warmUp() {
        long t0 = System.currentTimeMillis();
        try {
            Worker.Request r = new Worker.Request();
            r.seed = 4242L;
            r.budgetMs = 150L;
            r.beam = 8;
            r.layers = 10;
            r.deck = new String[]{"Strike_R", "Strike_R", "Strike_R", "Defend_R", "Bash"};
            r.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
            Worker.build(r);
            Solver.solve(World.captureLive(), 8, 10, 150L);
        } catch (Throwable t) {
            Log.warn("pool: warm-up skipped: " + t);
        }
        return System.currentTimeMillis() - t0;
    }

    /**
     * Claims the oldest queued request and solves it. Returns the result file, or null when the
     * queue is empty. This is the whole unit of pool work, usable without the game loop.
     */
    public static File serveOnce(File root) {
        File claimed = claim(root);
        if (claimed == null) {
            return null;
        }
        File result = null;
        try {
            result = Worker.run(claimed);
            return result;
        } finally {
            archive(root, claimed, result != null);
        }
    }

    /**
     * Atomically takes the oldest request out of the queue.
     *
     * <p>The rename is the lock: the request disappears under a name only this worker knows, so two
     * processes polling the same directory cannot both solve it. A request that is still being
     * written is never seen either, because the orchestrator writes tmp and moves.
     */
    public static File claim(File root) {
        List<File> queue = queued(root);
        long now = System.currentTimeMillis();
        for (int i = 0; i < queue.size(); i++) {
            File request = queue.get(i);
            File claimed = new File(request.getAbsolutePath() + CLAIM_SUFFIX);
            try {
                Files.move(request.toPath(), claimed.toPath(), StandardCopyOption.ATOMIC_MOVE);
                Log.info("pool: claimed " + request.getName() + " after " + (now - request.lastModified())
                        + "ms in the queue");
                return claimed;
            } catch (Throwable t) {
                // Another worker won the race, or the file vanished: keep looking.
            }
        }
        return null;
    }

    /** Request files visible in the queue, oldest first (a name tiebreak keeps the order stable). */
    public static List<File> queued(File root) {
        List<File> out = new ArrayList<File>();
        File[] files = root == null ? null : root.listFiles();
        if (files == null) {
            return out;
        }
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && f.getName().endsWith(REQUEST_SUFFIX)) {
                out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long d = a.lastModified() - b.lastModified();
                if (d != 0) {
                    return d < 0 ? -1 : 1;
                }
                return a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    /** True when the orchestrator asked every worker to exit. */
    public static boolean stopRequested(File root) {
        return root != null && stopFile(root).isFile();
    }

    /** Writes/refreshes this worker's ready file: who it is and what it has done. */
    public static void markReady(File root, long warm) {
        try {
            root.mkdirs();
            File tmp = new File(readyFile(root).getAbsolutePath() + ".tmp");
            PrintWriter w = new PrintWriter(tmp, "UTF-8");
            try {
                w.println("tag=" + tag());
                w.println("pid=" + pid());
                w.println("warmMs=" + warm);
                w.println("served=" + served());
                w.println("queue=" + queued(root).size());
                w.println("at=" + System.currentTimeMillis());
            } finally {
                w.close();
            }
            Files.move(tmp.toPath(), readyFile(root).toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Log.warn("pool: cannot write the ready file: " + t);
        }
    }

    /** Files a finished request under done/ (kept for inspection, named with its outcome). */
    public static void archive(File root, File claimed, boolean ok) {
        try {
            File done = doneDir(root);
            done.mkdirs();
            String name = claimed.getName().substring(0,
                    claimed.getName().length() - CLAIM_SUFFIX.length());
            File target = new File(done, (ok ? "" : "failed-") + name);
            Files.move(claimed.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Log.warn("pool: cannot archive " + claimed + ": " + t);
            claimed.delete();
        }
    }

    /**
     * Clean shutdown: leave a bye file, then ask the game to exit. The orchestrator treats a bye
     * file as the difference between "finished its work" and "had to be killed".
     */
    public static synchronized void shutdown(String reason) {
        active = false;
        try {
            File tmp = new File(byeFile(root()).getAbsolutePath() + ".tmp");
            PrintWriter w = new PrintWriter(tmp, "UTF-8");
            try {
                w.println("tag=" + tag());
                w.println("pid=" + pid());
                w.println("reason=" + reason);
                w.println("served=" + served);
                w.println("at=" + System.currentTimeMillis());
            } finally {
                w.close();
            }
            Files.move(tmp.toPath(), byeFile(root()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Log.warn("pool: cannot write the bye file: " + t);
        }
        Log.info("pool: shutting down (" + reason + ", served=" + served + ")");
        try {
            Gdx.app.exit();
        } catch (Throwable t) {
            Log.warn("pool: cannot exit: " + t);
        }
    }

    private static long pid() {
        try {
            return Long.parseLong(java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getName().split("@")[0]);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** Reads a small text file; used by the gate to check the ready file. */
    public static String textOf(File file) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
