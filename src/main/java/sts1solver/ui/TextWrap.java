package sts1solver.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text fitting for the panel (B32): how wide the box must be, and how to wrap a line that is
 * still too long after that.
 *
 * <p>The panel used a fixed 720-wide box while its header lines (mod hooks, budget, headline) were
 * often longer, so the text ran past the box to the screen edge -- reported as
 * "面板文字左右距离太短". Two rules fix it: the box grows to fit the longest line, and any line
 * that still does not fit is wrapped character by character (CJK has no spaces, so wrapping on
 * spaces would do nothing).
 *
 * <p>Kept free of game classes so a headless gate can prove both rules.
 */
public final class TextWrap {

    private TextWrap() {
    }

    /**
     * Width the panel box needs: at least minWidth, enough for the longest line plus its padding,
     * and never wider than the space left on screen.
     */
    public static float boxWidth(float minWidth, float maxLineWidth, float padX, float available) {
        float wanted = Math.max(minWidth, maxLineWidth + 2.0f * padX);
        if (available > 0.0f && wanted > available) {
            wanted = available;
        }
        return Math.max(0.0f, wanted);
    }

    /** Wraps every line that is wider than maxWidth; lines that fit are copied unchanged. */
    public static List<String> wrap(List<String> lines, float maxWidth, RouteLayout.Measure measure) {
        List<String> out = new ArrayList<String>();
        if (lines == null) {
            return out;
        }
        for (int i = 0; i < lines.size(); i++) {
            wrapOne(lines.get(i), maxWidth, measure, out);
        }
        return out;
    }

    /**
     * The pieces of one line after wrapping, as a fresh list (B33).
     *
     * <p>The route strip needs them: a pill whose label is wider than the strip used to be clamped
     * to the strip width while its text was drawn unclipped, so the label ran off the screen. The
     * pill now grows downwards instead, one text line per piece.
     */
    public static List<String> wrapLine(String line, float maxWidth, RouteLayout.Measure measure) {
        List<String> out = new ArrayList<String>();
        wrapOne(line, maxWidth, measure, out);
        return out;
    }

    /** One line, split into as many pieces as maxWidth needs; an empty line stays one line. */
    public static void wrapOne(String line, float maxWidth, RouteLayout.Measure measure,
                               List<String> out) {
        if (line == null) {
            return;
        }
        if (measure == null || maxWidth <= 0.0f || measure.width(line) <= maxWidth) {
            out.add(line);
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            cur.append(c);
            // Break after the character that overflowed, never before the first one, so a single
            // glyph wider than the line still produces a line instead of spinning forever.
            if (cur.length() > 1 && measure.width(cur.toString()) > maxWidth) {
                cur.setLength(cur.length() - 1);
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
    }
}
