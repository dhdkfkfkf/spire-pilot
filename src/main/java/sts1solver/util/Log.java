package sts1solver.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * One append-only log file, because a mod's own diagnostics must survive outside the game log
 * (the game rotates SlayTheSpire.log per launch, and grep-ing a 5 MB log for our lines is slower
 * than reading our own file).
 *
 * <p>The file lives in {@code %USERPROFILE%/sts1-engine-solver/solver.log}. A run of the automated
 * harness is identified by its {@link #runId()} marker lines, so tools/run-selftest.ps1 can find the
 * end of THIS run in a file that accumulates across runs.
 *
 * <p><b>Since B39 the file no longer grows without bound</b> (see {@link LogFiles}):
 *
 * <ul>
 *   <li>on the first write of a process, the previous session's content is moved to
 *       {@code logs/solver-<timestamp>.log}, so the live name always means "this session";</li>
 *   <li>the live file rotates the same way when it reaches {@link LogFiles#MAX_FILE_BYTES};</li>
 *   <li>archives older than a week, beyond 30 files, or beyond 200 MB are deleted on every
 *       cleanup, which runs at most once every five minutes (never on the hot path);</li>
 *   <li>every file starts with a {@code # sts1-solver log v2 utf-8 ...} header, so the encoding is
 *       never a guess again.</li>
 * </ul>
 */
public final class Log {

    private static PrintWriter out;
    private static final java.util.Map<String, Long> throttles =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static String runId = "unset";
    /** Bytes written to the live file since it was opened/rotated (no stat call per line). */
    private static long written;
    /** Last retention pass, so a busy fight does not list the archive directory per line. */
    private static long lastCleanup;
    /** Cached {@code <solver dir>/logs} so the write path does no path work per line (B52). */
    private static File archivesCache;
    private static final long CLEANUP_EVERY_MS = 5L * 60L * 1000L;

    private Log() {
    }

    public static synchronized String dir() {
        return System.getProperty("user.home") + File.separator + "sts1-engine-solver";
    }

    /** Instance tag for worker processes: "" for the main game, "w1" etc. for a worker. */
    public static String instance() {
        try {
            String v = System.getProperty("sts1solver.instance", "").trim();
            return v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** A path in the solver dir, suffixed with the instance tag when one is set. */
    public static String pathFor(String baseName) {
        String inst = instance();
        if (inst.isEmpty()) {
            return dir() + File.separator + baseName;
        }
        int dot = baseName.lastIndexOf('.');
        if (dot < 0) {
            return dir() + File.separator + baseName + "-" + inst;
        }
        return dir() + File.separator + baseName.substring(0, dot) + "-" + inst
                + baseName.substring(dot);
    }

    public static synchronized String path() {
        return pathFor("solver.log");
    }

    public static synchronized void open() {
        if (out != null) {
            return;
        }
        try {
            File d = new File(dir());
            if (!d.exists()) {
                d.mkdirs();
            }
            File archives = LogFiles.logsDir(d);
            archivesCache = archives;
            File base = new File(path());
            // The previous session becomes an archive; the live name restarts clean.
            File archived = LogFiles.archive(base, archives);
            cleanup(archives);
            out = writer(base);
            written = 0L;
            header();
            noteArchive(archived);
        } catch (Throwable t) {
            out = null;
        }
    }

    private static PrintWriter writer(File base) throws Exception {
        return new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(base, true), StandardCharsets.UTF_8), true);
    }

    private static void header() {
        if (out == null) {
            return;
        }
        String line = LogFiles.header(instance(), System.currentTimeMillis(), LogFiles.MAX_FILE_BYTES);
        out.println(line);
        out.flush();
        written += line.length() + 1L;
        lastCleanup = System.currentTimeMillis();
    }

    /**
     * One line saying where the previous session went, or why it could not be moved. Silence is only
     * correct when there was nothing to archive.
     */
    private static void noteArchive(File archived) {
        if (out == null) {
            return;
        }
        String why = LogFiles.lastError;
        if (archived == null && why == null) {
            return;
        }
        String note = STAMP.format(new Date()) + " INFO "
                + (archived == null ? "log archive failed: " + why
                : "log archive: previous session is logs/" + archived.getName()
                        + (why == null ? "" : " (" + why + ")"));
        out.println(note);
        out.flush();
        written += note.length() + 1L;
    }

    /** Deletes expired/overflowing archives. Writes one note about it, into the live file only. */
    private static void cleanup(File archives) {
        lastCleanup = System.currentTimeMillis();
        try {
            int removed = LogFiles.retain(archives, lastCleanup);
            if (removed > 0 && out != null) {
                String note = STAMP.format(new Date()) + " INFO log retention: removed " + removed
                        + " old file(s) from " + archives.getAbsolutePath();
                out.println(note);
                out.flush();
                written += note.length() + 1L;
            }
        } catch (Throwable ignored) {
            // A log directory that cannot be cleaned must never interrupt a fight.
        }
    }

    public static synchronized void setRunId(String id) {
        runId = id;
    }

    public static String runId() {
        return runId;
    }

    public static void info(String msg) {
        write("INFO", msg);
    }

    public static void warn(String msg) {
        write("WARN", msg);
    }

    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        write("ERROR", msg + (t == null ? "" : "\n" + sw));
    }

    /**
     * B82: a hot path (per simulated install, per engine pump) must not write a line per hit.
     * Returns true at most once per {@code windowMs} per key; the caller only logs then.
     *
     * <p>The map is deliberately tiny and unbounded in principle, but keys are compile-time
     * constants at the few call sites that use it.
     */
    public static boolean allow(String key, long windowMs) {
        long now = System.currentTimeMillis();
        Long last = throttles.get(key);
        if (last != null && now - last < windowMs) {
            return false;
        }
        throttles.put(key, now);
        return true;
    }

    /** Logs a warning only if {@link #allow(String, long)} lets this key through. */
    public static void warnEvery(String key, long windowMs, String msg) {
        if (allow(key, windowMs)) {
            warn(msg);
        }
    }

    /** Logs an error only if {@link #allow(String, long)} lets this key through. */
    public static void errorEvery(String key, long windowMs, String msg, Throwable t) {
        if (allow(key, windowMs)) {
            error(msg, t);
        }
    }

    /** Forgets throttling state (gates and a new session start from a clean slate). */
    public static void resetThrottles() {
        throttles.clear();
    }

    public static void check(boolean ok, String what, String detail) {
        write(ok ? "PASS" : "FAIL", what + (detail == null ? "" : " | " + detail));
    }

    private static synchronized void write(String level, String msg) {
        String line = STAMP.format(new Date()) + " " + level + " " + msg;
        // Deliberately NOT mirrored to System.out: ModTheSpire's console is a Swing JTextArea whose
        // insertString relayouts the whole text on the calling thread, so a console line costs orders
        // of magnitude more than appending to our own file (measured: a long fight spent over half its
        // time inside that relayout). solver.log is the channel.
        if (out == null) {
            open();
        }
        if (out == null) {
            return;
        }
        out.println(line);
        out.flush();
        written += line.length() + 1L;
        // B52: this used to build the archives File (and a File for the solver dir) on EVERY line.
        // Cheap-looking, but the log is written from the render thread and the path sits on a disk
        // where a directory lookup is not free. One cached File, no filesystem work per line.
        File archives = archivesCache;
        if (archives == null) {
            archives = LogFiles.logsDir(new File(dir()));
            archivesCache = archives;
        }
        if (System.currentTimeMillis() - lastCleanup > CLEANUP_EVERY_MS) {
            cleanup(archives);
        }
        if (LogFiles.shouldRotate(written, LogFiles.MAX_FILE_BYTES)) {
            rotate(archives);
        }
    }

    /** Recreates the live file empty and puts the previous content into the archive directory. */
    private static void rotate(File archives) {
        File base = new File(path());
        try {
            out.flush();
            out.close();
        } catch (Throwable ignored) {
            // closing a broken writer is not interesting
        }
        out = null;
        File moved = LogFiles.archive(base, archives);
        try {
            out = writer(base);
            written = 0L;
            header();
            String note = STAMP.format(new Date()) + " INFO log rotated: live file reached "
                    + (LogFiles.MAX_FILE_BYTES / (1024L * 1024L)) + "MB"
                    + (moved == null ? " (previous content could not be archived)"
                    : ", previous content is logs/" + moved.getName());
            out.println(note);
            out.flush();
            written += note.length() + 1L;
        } catch (Throwable t) {
            out = null;
        }
    }
}
