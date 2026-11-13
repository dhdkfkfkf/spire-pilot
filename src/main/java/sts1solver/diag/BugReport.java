package sts1solver.diag;

import sts1solver.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One click -> one zip a player can attach to a workshop comment or a GitHub issue.
 *
 * <p>Why a button instead of "send us your log": the interesting failures (a route that did not
 * match the game, a sandbox leak, a freeze) leave nothing a player can describe, but everything in
 * {@code solver.log} + {@code settings.txt} + the current panel lines. Asking for a file path is
 * where reports die; asking for one click is not.
 *
 * <p>Three rules this class keeps:
 *
 * <ul>
 *   <li>Never on the render thread. Packing touches disk and can take a second; the click hands the
 *       work to a daemon thread so a slow disk can never turn into a dropped frame.</li>
 *   <li>Bounded. Only the tail of {@code solver.log} is packed (2 MB), because the file grows to
 *       hundreds of megabytes on a long session and nobody can read that in an issue.</li>
 *   <li>No personal paths. The user's home directory is replaced with {@code %USERPROFILE%} in every
 *       text entry, so a public issue does not leak a Windows account name.</li>
 * </ul>
 */
public final class BugReport {

    /** How much of solver.log is packed. */
    public static final long LOG_TAIL_BYTES = 2L * 1024L * 1024L;

    /** Hard ceiling on the whole bundle; a bigger log means something is wrong with the log. */
    public static final long BUNDLE_LIMIT_BYTES = 8L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static volatile boolean running;
    private static volatile String label = "导出报告";
    private static volatile long labelUntil;

    private BugReport() {
    }

    /** Text for the header button: idle name, progress, or the last result for a few seconds. */
    public static String label() {
        if (running) {
            return "打包中…";
        }
        if (System.currentTimeMillis() < labelUntil) {
            return label;
        }
        return "导出报告";
    }

    /**
     * Writes the bundle off the calling thread and then shows it in Explorer.
     *
     * <p>A second click while a bundle is being written is ignored, not queued: two zips from the
     * same moment carry the same information.
     */
    public static void export() {
        synchronized (LOCK) {
            if (running) {
                return;
            }
            running = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File zip = null;
                try {
                    zip = writeBundle(new File(sts1solver.SolverMod.dir(), "reports"));
                    label = "已导出";
                    Log.info("bug report written: " + zip.getAbsolutePath() + " ("
                            + zip.length() + " bytes)");
                } catch (Throwable e) {
                    label = "导出失败";
                    Log.error("bug report failed", e);
                } finally {
                    running = false;
                    labelUntil = System.currentTimeMillis() + 6000L;
                }
                if (zip != null) {
                    reveal(zip);
                }
            }
        }, "sts1-bug-report");
        t.setDaemon(true);
        t.start();
    }

    /** Synchronous write, used by {@link #export()} and by the headless gate. */
    public static File writeBundle(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File zip = new File(dir, "report-" + stamp + ".zip");
        String home = System.getProperty("user.home", "");
        FileOutputStream fos = new FileOutputStream(zip);
        try {
            ZipOutputStream out = new ZipOutputStream(fos, StandardCharsets.UTF_8);
            try {
                putText(out, "README.txt", readme(), home);
                putText(out, "info.txt", info(), home);
                putText(out, "state.txt", state(), home);
                putText(out, "settings.txt", fileText(new File(dir.getParentFile(), "settings.txt")), home);
                putText(out, "panel.txt", panelText(), home);
                putLogTail(out, "solver.log", new File(dir.getParentFile(), "solver.log"), home);
            } finally {
                out.finish();
                out.close();
            }
        } finally {
            fos.close();
        }
        if (zip.length() > BUNDLE_LIMIT_BYTES) {
            // Do not hand out a bundle that is too big to attach; say so instead of failing silently.
            throw new IOException("bundle too large: " + zip.length() + " bytes");
        }
        return zip;
    }

    private static String readme() {
        return "STS1 战斗求解器 / STS1 Engine Solver - 问题反馈包\n"
                + "=========================================\n\n"
                + "这个压缩包用于定位问题，请连同下面两件事一起发出：\n"
                + "  1. 当时在做什么（第几层、哪个角色、点了哪个按钮）\n"
                + "  2. 期望发生什么、实际发生了什么\n\n"
                + "内容：\n"
                + "  info.txt     版本、Java、系统与内存\n"
                + "  state.txt    当前战斗状态与面板上的路线\n"
                + "  settings.txt 求解器的设置\n"
                + "  solver.log   日志的最后 2 MB\n"
                + "  panel.txt    面板文字（与 state.txt 中的路线一致，便于直接搜索）\n\n"
                + "已做的处理：用户目录被替换为 %USERPROFILE%，不含存档与卡组文件。\n"
                + "反馈：创意工坊留言，或 GitHub Issue。\n";
    }

    private static String info() {
        StringBuilder b = new StringBuilder();
        b.append("generated: ").append(new Date()).append('\n');
        b.append("mod version: ").append(modVersion()).append('\n');
        b.append("java: ").append(System.getProperty("java.version", "?")).append(" (")
                .append(System.getProperty("java.vendor", "?")).append(")\n");
        b.append("os: ").append(System.getProperty("os.name", "?")).append(' ')
                .append(System.getProperty("os.version", "?")).append(" / ")
                .append(System.getProperty("os.arch", "?")).append('\n');
        b.append("locale: ").append(System.getProperty("user.language", "?")).append('_')
                .append(System.getProperty("user.country", "?")).append('\n');
        Runtime rt = Runtime.getRuntime();
        b.append("heap: ").append(rt.totalMemory() / (1024L * 1024L)).append(" MB of ")
                .append(rt.maxMemory() / (1024L * 1024L)).append(" MB max\n");
        b.append("solver dir: ").append(sts1solver.SolverMod.dir()).append('\n');
        b.append("debug surface: ").append(sts1solver.SolverMod.debugSurface() ? "ON" : "off")
                .append('\n');
        return b.toString();
    }

    private static String modVersion() {
        // NOT "/ModTheSpire.json": every mod ships one at its jar root, so the classloader can hand
        // back a different mod's manifest (the first 1.0.0 bundle reported another mod's 5.56.0).
        // The build writes this file from the mod's own ModTheSpire.json, so there is one source.
        InputStream in = null;
        try {
            in = BugReport.class.getResourceAsStream("/sts1-engine-solver.version");
            if (in == null) {
                return "?";
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[256];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "?";
        } finally {
            close(in);
        }
    }

    /** The live fight, reduced to what a reader needs to reproduce it. */
    private static String state() {
        StringBuilder b = new StringBuilder();
        try {
            com.megacrit.cardcrawl.map.MapRoomNode node =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrMapNode();
            b.append("act: ").append(com.megacrit.cardcrawl.dungeons.AbstractDungeon.id)
                    .append(" #").append(com.megacrit.cardcrawl.dungeons.AbstractDungeon.actNum);
            b.append(" floor: ").append(com.megacrit.cardcrawl.dungeons.AbstractDungeon.floorNum);
            b.append(" room: ").append(node == null ? "?" : String.valueOf(node.room));
            b.append("\n");
        } catch (Throwable t) {
            b.append("act: unavailable (").append(t).append(")\n");
        }
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer p =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            if (p != null) {
                b.append("player: ").append(p.chosenClass).append(" hp ").append(p.currentHealth)
                        .append('/').append(p.maxHealth).append(" gold ").append(p.gold)
                        .append(" potions ");
                for (int i = 0; i < p.potions.size(); i++) {
                    b.append(p.potions.get(i) == null ? "-" : p.potions.get(i).name).append(' ');
                }
                b.append('\n');
            }
        } catch (Throwable t) {
            b.append("player: unavailable (").append(t).append(")\n");
        }
        try {
            b.append("phase: ").append("mode=")
                    .append(com.megacrit.cardcrawl.core.Settings.isDebug ? "debug" : "play")
                    .append(" screen=").append(com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen)
                    .append('\n');
            b.append("turn: ").append(com.megacrit.cardcrawl.actions.GameActionManager.turn)
                    .append(" hasPlan: ").append(sts1solver.Gameplay.hasPlan())
                    .append(" planStale: ").append(sts1solver.Gameplay.planIsStale())
                    .append('\n');
        } catch (Throwable t) {
            b.append("phase: unavailable (").append(t).append(")\n");
        }
        b.append("\n--- panel ---\n").append(panelText());
        return b.toString();
    }

    private static String panelText() {
        try {
            List<String> lines = sts1solver.Gameplay.lines();
            if (lines == null) {
                return "(no panel lines)\n";
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                b.append(lines.get(i)).append('\n');
            }
            return b.toString();
        } catch (Throwable t) {
            return "(panel unavailable: " + t + ")\n";
        }
    }

    private static String fileText(File f) {
        if (f == null || !f.isFile()) {
            return "(missing: " + (f == null ? "?" : f.getPath()) + ")\n";
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "(unreadable: " + t + ")\n";
        } finally {
            close(in);
        }
    }

    /**
     * Copies the last {@link #LOG_TAIL_BYTES} of the log.
     *
     * <p>Reads through {@link java.io.RandomAccessFile} so a 300 MB log costs one seek, not a full
     * read; a cut that lands mid-line drops the partial first line so the entry always starts at a
     * timestamp.
     */
    private static void putLogTail(ZipOutputStream out, String name, File log, String home)
            throws IOException {
        if (log == null || !log.isFile()) {
            putText(out, name, "(missing log)\n", home);
            return;
        }
        long len = log.length();
        long start = Math.max(0L, len - LOG_TAIL_BYTES);
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r");
        try {
            raf.seek(start);
            byte[] raw = new byte[(int) Math.min(Integer.MAX_VALUE, len - start)];
            raf.readFully(raw);
            int from = 0;
            if (start > 0L) {
                int nl = 0;
                while (nl < raw.length && raw[nl] != '\n') {
                    nl++;
                }
                from = Math.min(raw.length, nl + 1);
            }
            out.putNextEntry(new ZipEntry(name));
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write("(tail " + (raw.length - from) + " bytes of " + len + ")\n");
            w.write(sanitize(new String(raw, from, raw.length - from, StandardCharsets.UTF_8), home));
            w.flush();
            out.closeEntry();
        } finally {
            raf.close();
        }
    }

    private static void putText(ZipOutputStream out, String name, String text, String home)
            throws IOException {
        out.putNextEntry(new ZipEntry(name));
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(sanitize(text == null ? "" : text, home));
        w.flush();
        out.closeEntry();
    }

    /** Replaces the home directory (and the bare user name) so a public issue carries no identity. */
    static String sanitize(String text, String home) {
        if (text == null) {
            return "";
        }
        String s = text;
        if (home != null && home.length() > 2) {
            s = s.replace(home, "%USERPROFILE%");
            s = s.replace(home.replace('\\', '/'), "%USERPROFILE%");
            int cut = Math.max(home.lastIndexOf('\\'), home.lastIndexOf('/'));
            String user = cut >= 0 ? home.substring(cut + 1) : home;
            if (user.length() >= 3) {
                s = s.replace(user, "%USER%");
            }
        }
        return s;
    }

    private static void reveal(File zip) {
        try {
            String path = zip.getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder("explorer.exe", "/select," + path);
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Throwable t) {
            Log.info("report reveal skipped: " + t);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
