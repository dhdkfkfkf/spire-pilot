package sts1solver.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Archiving and retention for the solver log: the rules that keep one text file from growing
 * forever.
 *
 * <p>Design constraints, all of them learned the hard way:
 *
 * <ul>
 *   <li><b>solver.log must stay the live file.</b> docs/TESTING.md and docs/LOGGING.md tell the
 *       player to grep {@code %USERPROFILE%/sts1-engine-solver/solver.log}, and the harness waits
 *       for AUTOTEST markers there. So rotation never renames the live file away for good: the
 *       previous content moves into {@code logs/} and the live name is recreated empty.</li>
 *   <li><b>Nothing here may throw into the game.</b> Every caller wraps these calls: a broken log
 *       directory must never take a fight down.</li>
 *   <li><b>Retention is by age AND by count AND by size.</b> Age alone lets a noisy week fill the
 *       disk; count alone keeps month-old files forever; size alone can delete every recent file
 *       at once.</li>
 * </ul>
 *
 * <p>Pure file operations, no game state: {@code probe/Probe.java} tests these against a temporary
 * directory (gate S78), so the real log is never involved.
 */
public final class LogFiles {

    /** One live/archived file is capped at 16 MB (a busy suite run writes well under 1 MB). */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    /** Archives older than a week are removed on every cleanup. */
    public static final long MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    /** Never keep more than this many archives. */
    public static final int MAX_ARCHIVES = 30;
    /** Never keep more than this many bytes of archives. */
    public static final long MAX_TOTAL_BYTES = 200L * 1024L * 1024L;

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    /**
     * Why the last {@link #archive(File, File)} had to fall back, or null when the plain rename
     * worked. The harness reads the live file while the game starts (tools/run.ps1 watches for the
     * AUTOTEST marker), and Windows refuses the rename in that window, so the reason is worth a line
     * in the log instead of a silent "it just did not rotate".
     */
    public static volatile String lastError;
    /** Name of the last archive created, or null. */
    public static volatile String lastArchive;

    private LogFiles() {
    }

    /** Where archives live: {@code <solver dir>/logs}. */
    public static File logsDir(File solverDir) {
        return new File(solverDir, "logs");
    }

    /** True when the live file has reached the per-file cap. A cap of 0 disables rotation. */
    public static boolean shouldRotate(long writtenBytes, long cap) {
        return cap > 0L && writtenBytes >= cap;
    }

    /**
     * Moves {@code base} into {@code dir} under a timestamped name, so the live name can be recreated.
     * An empty base file is just deleted (a game that started and never logged leaves no archive).
     *
     * @return the archive file, or null when there was nothing worth keeping
     */
    public static File archive(File base, File dir) {
        lastError = null;
        lastArchive = null;
        if (base == null || !base.isFile()) {
            return null;
        }
        if (base.length() == 0L) {
            base.delete();
            return null;
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            lastError = "cannot create " + dir.getAbsolutePath();
            return null;
        }
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        String stamp;
        synchronized (STAMP) {
            stamp = STAMP.format(new Date(base.lastModified()));
        }
        File target = new File(dir, stem + "-" + stamp + ext);
        int n = 2;
        while (target.exists()) {
            target = new File(dir, stem + "-" + stamp + "-" + n + ext);
            n++;
        }
        try {
            Files.move(base.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            lastArchive = target.getName();
            return target;
        } catch (IOException e) {
            // Windows refuses the rename while another process holds the file open without delete
            // sharing. That happens for real: tools/run.ps1 polls the live log while the game boots.
            // Copy + truncate needs only read/write access and leaves the same result: archive full,
            // live file empty.
            try {
                Files.copy(base.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                new java.io.RandomAccessFile(base, "rw").setLength(0L);
                lastArchive = target.getName();
                lastError = "rename refused (" + reason(e) + "), copied and truncated instead";
                return target;
            } catch (Exception again) {
                lastError = "rename refused (" + reason(e) + "); copy also failed ("
                        + reason(again) + ")";
                return null;
            }
        }
    }

    /**
     * A one-line, ASCII-only version of an OS error. Windows reports "the file is in use" in the
     * platform language, and that text reaches us already decoded with the wrong charset, so quoting
     * it verbatim would put mojibake into a UTF-8 log.
     */
    private static String reason(Exception e) {
        String message = e.getMessage();
        StringBuilder sb = new StringBuilder();
        if (message != null) {
            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                if (c >= 32 && c < 127) {
                    sb.append(c);
                }
            }
        }
        String ascii = sb.toString().trim();
        if (ascii.isEmpty()) {
            ascii = "the file is locked by another process";
        }
        if (ascii.length() > 120) {
            ascii = ascii.substring(0, 120) + "...";
        }
        return ascii;
    }

    /** Archives with the production limits. */
    public static int retain(File dir, long nowMs) {
        return retain(dir, nowMs, MAX_AGE_MS, MAX_ARCHIVES, MAX_TOTAL_BYTES);
    }

    /**
     * Deletes archives that are too old, then the oldest ones until the count and byte caps hold.
     *
     * @return how many files were removed
     */
    public static int retain(File dir, long nowMs, long maxAgeMs, int maxFiles, long maxTotalBytes) {
        File[] listing = dir.listFiles();
        if (listing == null || listing.length == 0) {
            return 0;
        }
        List<File> files = new ArrayList<File>();
        for (File f : listing) {
            if (f.isFile() && f.getName().endsWith(".log")) {
                files.add(f);
            }
        }
        if (files.isEmpty()) {
            return 0;
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                if (x != y) {
                    return x < y ? -1 : 1;
                }
                return a.getName().compareTo(b.getName());
            }
        });
        int removed = 0;
        long total = 0L;
        List<File> keep = new ArrayList<File>();
        for (File f : files) {
            if (maxAgeMs > 0L && nowMs - f.lastModified() > maxAgeMs) {
                if (f.delete()) {
                    removed++;
                }
                continue;
            }
            keep.add(f);
            total += f.length();
        }
        while (!keep.isEmpty() && maxFiles > 0 && keep.size() > maxFiles) {
            File oldest = keep.remove(0);
            total -= oldest.length();
            if (oldest.delete()) {
                removed++;
            }
        }
        while (!keep.isEmpty() && maxTotalBytes > 0L && total > maxTotalBytes) {
            File oldest = keep.remove(0);
            total -= oldest.length();
            if (oldest.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /** First line of every file: which process, which limits, which encoding produced these lines. */
    public static String header(String instance, long nowMs, long capBytes) {
        String stamp;
        synchronized (STAMP) {
            stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date(nowMs));
        }
        return "# sts1-solver log v2 utf-8 started=" + stamp
                + " pid=" + pid()
                + " instance=" + (instance == null || instance.isEmpty() ? "main" : instance)
                + " cap=" + (capBytes / (1024L * 1024L)) + "MB"
                + " retention=" + (MAX_AGE_MS / (24L * 60L * 60L * 1000L)) + "d/"
                + MAX_ARCHIVES + "files/" + (MAX_TOTAL_BYTES / (1024L * 1024L)) + "MB";
    }

    private static String pid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : name;
        } catch (Throwable t) {
            return "?";
        }
    }
}
