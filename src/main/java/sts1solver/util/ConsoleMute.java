package sts1solver.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Stops log4j's CONSOLE appender from writing while a sandboxed world is installed.
 *
 * <p>Why this matters more than it looks: ModTheSpire installs a Swing console
 * ({@code MessageConsole$ConsoleOutputStream}) as {@code System.out}, and every flush runs
 * {@code Document.insertString} plus a full relayout of the text component ON THE CALLING THREAD.
 * log4j's ConsoleAppender captured that stream at startup, so the engine's own per-action log lines
 * -- which are pure noise during a simulation -- pay that relayout cost inside every simulated
 * update. A stack sample of one long fight put 17% of samples inside the console insert at the start
 * and 52% waiting on its document lock by the end; detaching the appender took the measured cost from
 * ~100us/update back to ~5us/update.
 *
 * <p>Replacing {@code System.out} does NOT work (log4j wraps the stream at configuration time), and
 * removing/adding the appender per install would reconfigure the logger thousands of times per second,
 * so this attaches ONE filter to the console appenders and flips a boolean. The log FILE appender is
 * left alone, so evidence lines are unaffected.
 */
public final class ConsoleMute {

    private static volatile boolean muted;
    private static volatile boolean filterAttached;
    private static String status = "not installed";
    private static int depth;

    private ConsoleMute() {
    }

    public static boolean isMuted() {
        return muted;
    }

    public static String status() {
        return status;
    }

    /**
     * Nests: a world install can sit inside a probe's own mute window. Cheap (a boolean write), so it
     * can be called on every install / uninstall cycle.
     */
    public static synchronized void push() {
        if (!filterAttached) {
            attachFilter();
        }
        depth++;
        muted = true;
    }

    public static synchronized void pop() {
        depth = Math.max(0, depth - 1);
        muted = depth > 0;
    }

    /** Direct toggle for probes that drive the window themselves. */
    public static void mute() {
        if (!filterAttached) {
            attachFilter();
        }
        muted = true;
    }

    public static void unmute() {
        muted = false;
    }

    public static synchronized void reset() {
        depth = 0;
        muted = false;
    }

    private static synchronized void attachFilter() {
        try {
            Class<?> lm = Class.forName("org.apache.logging.log4j.LogManager");
            Object ctx = lm.getMethod("getContext", boolean.class).invoke(null, Boolean.FALSE);
            Class<?> lctx = Class.forName("org.apache.logging.log4j.core.LoggerContext");
            Class<?> conf = Class.forName("org.apache.logging.log4j.core.config.Configuration");
            Object cfg = lctx.getMethod("getConfiguration").invoke(ctx);
            Map<?, ?> appenders = (Map<?, ?>) conf.getMethod("getAppenders").invoke(cfg);
            Class<?> filterType = Class.forName("org.apache.logging.log4j.core.Filter");
            Object filter = newConsoleFilter(filterType);
            int attached = 0;
            for (Map.Entry<?, ?> e : appenders.entrySet()) {
                Object appender = e.getValue();
                if (!appender.getClass().getName().toLowerCase().contains("console")) {
                    continue;
                }
                try {
                    appender.getClass().getMethod("addFilter", filterType).invoke(appender, filter);
                    attached++;
                } catch (NoSuchMethodException ignored) {
                    // Older appenders expose addFilter through AppenderControl instead.
                }
            }
            filterAttached = attached > 0;
            status = "filter on " + attached + " console appender(s)";
        } catch (Throwable t) {
            status = "failed: " + t;
        }
    }

    /**
     * A java.lang.reflect.Proxy, not a compiled Filter: the mod must not pin the game's log4j 2.x
     * minor version. DENY drops the event; NEUTRAL lets it through.
     */
    private static Object newConsoleFilter(Class<?> filterType) {
        return Proxy.newProxyInstance(filterType.getClassLoader(), new Class<?>[]{filterType},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("filter".equals(n)) {
                            return enumValue(filterType, "Result", muted ? "DENY" : "NEUTRAL");
                        }
                        if ("getOnMatch".equals(n) || "getOnMismatch".equals(n)) {
                            return enumValue(filterType, "Result", "NEUTRAL");
                        }
                        if ("getState".equals(n)) {
                            return enumValue(filterType, "State", "INITIALIZED");
                        }
                        if ("isStarted".equals(n)) {
                            return Boolean.TRUE;
                        }
                        Class<?> r = method.getReturnType();
                        if (r == Boolean.TYPE) {
                            return Boolean.FALSE;
                        }
                        if (r == Integer.TYPE) {
                            return Integer.valueOf(0);
                        }
                        return null;
                    }
                });
    }

    /** Cached: the filter runs for every log event, so a Class.forName here is not affordable. */
    private static Object deny;
    private static Object neutral;
    private static Object initialized;

    private static Object enumValue(Class<?> outer, String inner, String constant) {
        // fast path for the three constants the proxy actually returns
        if ("Result".equals(inner)) {
            if ("DENY".equals(constant) && deny != null) {
                return deny;
            }
            if ("NEUTRAL".equals(constant) && neutral != null) {
                return neutral;
            }
        } else if ("State".equals(inner) && initialized != null) {
            return initialized;
        }
        try {
            Class<?> e = Class.forName(outer.getName() + "$" + inner);
            Object[] all = e.getEnumConstants();
            Object found = null;
            for (Object c : all) {
                if (String.valueOf(c).equals(constant)) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                found = all.length > 0 ? all[0] : null;
            }
            if ("Result".equals(inner)) {
                if ("DENY".equals(constant)) {
                    deny = found;
                } else if ("NEUTRAL".equals(constant)) {
                    neutral = found;
                }
            } else if ("State".equals(inner)) {
                initialized = found;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }
}
