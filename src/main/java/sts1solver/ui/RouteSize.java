package sts1solver.ui;

import sts1solver.util.Log;

/**
 * Size of the route strip (B33): the player can drag the grip in its bottom-right corner and the
 * turn cards, pills and text grow or shrink together, like STS2's resizable route panel.
 *
 * <p>The factor is a multiplier on top of the game's UI scale, so a player on a 4K screen who
 * already runs the game scaled can still make the plan bigger or smaller. It is stored in
 * {@code %USERPROFILE%\spire-pilot\settings.txt} as {@code panelScale=}, next to the search
 * budget: the panel stays part of the same small settings contract and needs no reload.
 */
public final class RouteSize {

    public static final float MIN = 0.6f;
    public static final float MAX = 2.0f;
    public static final float DEFAULT = 1.0f;

    private static final String KEY = "panelScale=";

    private static float factor = -1.0f;

    private RouteSize() {
    }

    private static java.io.File file() {
        return new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
    }

    /** Current factor, read from disk once per session. */
    public static synchronized float factor() {
        if (factor < 0.0f) {
            factor = clamp(read());
        }
        return factor;
    }

    /** Changes the factor without touching the disk (used per frame while dragging). */
    public static synchronized void preview(float value) {
        factor = clamp(value);
    }

    /** Writes the current factor to settings.txt (called when the drag ends). */
    public static synchronized void commit() {
        write(factor());
    }

    public static synchronized void reset() {
        factor = DEFAULT;
        write(DEFAULT);
    }

    public static float clamp(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT;
        }
        if (value < MIN) {
            return MIN;
        }
        return value > MAX ? MAX : value;
    }

    private static float read() {
        try {
            java.io.File f = file();
            if (!f.isFile()) {
                return DEFAULT;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(KEY)) {
                        return Float.parseFloat(line.substring(KEY.length()).trim());
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break the panel
        }
        return DEFAULT;
    }

    /** Writes panelScale=, preserving every other line of the file. */
    private static void write(float value) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        boolean saw = false;
        try {
            java.io.File f = file();
            if (f.isFile()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith(KEY)) {
                            out.add(KEY + value);
                            saw = true;
                        } else {
                            out.add(line);
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (!saw) {
                out.add(KEY + value);
            }
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8"));
            try {
                for (int i = 0; i < out.size(); i++) {
                    w.println(out.get(i));
                }
            } finally {
                w.close();
            }
            Log.info("panel: route strip scale=" + value);
        } catch (Throwable t) {
            Log.warn("panel: cannot write settings.txt: " + t);
        }
    }
}
