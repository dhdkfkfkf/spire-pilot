package sts1solver.mirror;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * B200 P2: may the sandbox ask a mod to keep a choice it deliberately suppresses while a mirror is
 * installed? 送葬人's counter trigger is the reported case. This is a capability, not a game-state
 * flag: it is not registered with {@link Statics} and it is not shown in ModConfig.
 *
 * <p>Default is ON. The handshake only takes effect when the mod is present and exposes
 * {@code songzangren.compat.SpirePilotCompat.setMirrorChoices(boolean)}; older mods or no mod mean a
 * no-op and B200's live screen solve stays the fallback.
 *
 * <p>{@code settings.txt} key: {@code rolloutChoices=off} is a hidden kill switch. Missing key means
 * ON; an explicit unrecognised value fails closed.
 */
public final class RolloutChoices {

    static final String KEY = "rolloutChoices=";
    private static final long CACHE_MS = 1000L;

    private static Boolean pinned;
    private static boolean cached = true;
    private static boolean cachedLoaded;
    private static long cachedAt;

    private RolloutChoices() {
    }

    public static boolean enabled() {
        if (pinned != null) {
            return pinned.booleanValue();
        }
        refresh();
        return cached;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (cachedLoaded && now - cachedAt < CACHE_MS) {
            return;
        }
        boolean on = true;
        try {
            File f = file();
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(KEY)) {
                            on = parse(line.substring(KEY.length()));
                        }
                    }
                } finally {
                    r.close();
                }
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break a search
        }
        cached = on;
        cachedLoaded = true;
        cachedAt = now;
    }

    private static boolean parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return true;
        }
        if ("off".equals(v) || "no".equals(v) || "false".equals(v)
                || "0".equals(v) || "\u5173".equals(v)) {
            return false;
        }
        if ("on".equals(v) || "yes".equals(v) || "true".equals(v)
                || "1".equals(v) || "\u5f00".equals(v)) {
            return true;
        }
        return false;
    }

    public static String label() {
        return enabled() ? "开（模拟中应答反击选牌）" : "关（模拟中跳过反击选牌）";
    }

    /** Test seam: pin the switch; {@code null} returns to reading settings.txt. */
    public static void setForTest(Boolean on) {
        pinned = on;
        cachedLoaded = false;
        cachedAt = 0L;
    }

    private static File file() {
        return new File(sts1solver.SolverMod.dir(), "settings.txt");
    }
}
