package sts1solver.mirror;

import sts1solver.util.Log;

import java.lang.reflect.Method;

/**
 * B200 P2: the one link the search cannot provide by itself. 送葬人 skips its counter choice while a
 * mirror is installed, so the sandbox never opens the GRID and cannot branch on it. The mod exposes
 * a default-off capability; Spire Pilot pushes it before every search and nothing else sets it.
 *
 * <p>The bridge is deliberately reflective: no compile-time dependency on the mod, no-op for older
 * versions, and the live game keeps working when the setter is absent. The flag is pushed on every
 * search (not only on change) so a mod that resets it per combat still finds it armed; a setter that
 * exists but throws is dropped and retried next search, never propagated.
 */
public final class SongZangRenBridge {

    private static final String COMPAT = "songzangren.compat.SpirePilotCompat";
    private static Method setter;
    private static boolean resolved;
    private static Boolean pushed;
    private static boolean absentLogged;
    private static boolean failureLogged;

    private SongZangRenBridge() {
    }

    public static void push() {
        boolean on = RolloutChoices.enabled();
        Method m = setter();
        if (m == null) {
            logAbsentOnce();
            return;
        }
        try {
            m.invoke(null, Boolean.valueOf(on));
            if (pushed == null || pushed.booleanValue() != on) {
                Log.info("songzangren bridge: mirror choices " + (on ? "on" : "off"));
            }
            pushed = Boolean.valueOf(on);
            failureLogged = false;
        } catch (Throwable t) {
            // A capability bit must never take a search or the live game down. Forget the method so
            // the next search re-resolves it, and say once what the author's setter did wrong.
            setter = null;
            resolved = false;
            pushed = null;
            if (!failureLogged) {
                failureLogged = true;
                Log.warn("songzangren bridge: setMirrorChoices(" + on + ") failed: " + t);
            }
        }
    }

    private static Method setter() {
        if (resolved) {
            return setter;
        }
        resolved = true;
        try {
            Class<?> compat = Class.forName(COMPAT);
            try {
                setter = compat.getMethod("setMirrorChoices", boolean.class);
            } catch (NoSuchMethodException primitiveMiss) {
                setter = compat.getMethod("setMirrorChoices", Boolean.class);
            }
            setter.setAccessible(true);
        } catch (Throwable t) {
            setter = null;
        }
        return setter;
    }

    private static void logAbsentOnce() {
        if (!absentLogged) {
            absentLogged = true;
            Log.info("songzangren bridge: no mirrorChoices capability; B200 live fallback stays");
        }
    }

    public static String stateForTest() {
        return pushed == null ? "unset" : (pushed.booleanValue() ? "on" : "off");
    }

    public static void resetForTest() {
        setter = null;
        resolved = false;
        pushed = null;
        absentLogged = false;
        failureLogged = false;
    }
}
