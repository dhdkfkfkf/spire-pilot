package sts1solver.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * What the route strip shows at a given size (P2-39, STS2's resizable overlay).
 *
 * <p>Dragging the grip changes how MUCH is shown, never how big the letters are (m20027: "字体大小不变，减少内容").
 * The first version of this class scaled the glyphs with the panel instead, which made a small panel
 * cramped and a big one read like a poster; the player asked for a fixed font and a small panel that
 * scrolls one turn at a time. So there is no text scale any more — only the row count:
 *
 * <ul>
 *   <li>{@link #visibleRows(float)} — how many turn rows the strip shows at that size;</li>
 *   <li>{@link #level(float)} — how much of the plan to show: FULL keeps everything, MEDIUM drops
 *       the per-target HP tail, COMPACT keeps only the card and its target.</li>
 * </ul>
 *
 * <p>Pure functions only (no game classes beyond {@link RouteRow}), so a headless gate can prove the
 * thresholds and the shortening without a window.
 */
public final class PanelDetail {

    /** Everything: pill label, target HP, turn stats and every header line. */
    public static final int FULL = 2;
    /** No per-target HP tail; turn stats and every header line stay. */
    public static final int MEDIUM = 1;
    /** Card and target only, no turn stats, two header lines. */
    public static final int COMPACT = 0;

    /** Below this strip factor the strip drops the target HP tail. */
    public static final float MEDIUM_BELOW = 0.95f;
    /** Below this strip factor the strip keeps only the card and its target. */
    public static final float COMPACT_BELOW = 0.72f;

    /** Fewest turn rows a strip ever shows (the smallest panel: one turn, scroll for the next). */
    public static final int ROWS_MIN = 1;
    /** Most turn rows a strip ever shows (a full-size panel). */
    public static final int ROWS_MAX = 5;
    /** At or above this factor the strip shows four rows instead of three. */
    public static final float ROWS_4_ABOVE = 1.30f;
    /** At or above this factor the strip shows five rows. */
    public static final float ROWS_5_ABOVE = 1.65f;

    /** Header lines that still make sense when there is no room for anything else. */
    private static final String[] IMPORTANT = {
            "\u6b63\u5728\u6267\u884c", "\u9009\u724c\u4e2d", "\u7b49\u5f85\u7ed3\u7b97", "\u5f15\u64ce\u5f02\u5e38",
            "\u53ef\u8d62", "\u672a\u627e\u5230", "\u6240\u6709\u8def\u7ebf", "\u53ea\u80fd\u9003\u8dd1",
            "\u65e0\u4f24", "\u6389\u8840", "\u91cd\u7b97", "\u505c\u6b62"};

    private PanelDetail() {
    }

    /** Detail level for a strip factor (the same number the grip drags). */
    public static int level(float strip) {
        if (strip < COMPACT_BELOW) {
            return COMPACT;
        }
        return strip < MEDIUM_BELOW ? MEDIUM : FULL;
    }

    public static String name(int level) {
        return level == COMPACT ? "compact" : (level == MEDIUM ? "medium" : "full");
    }

    /**
     * Turn rows the strip shows at a grip factor. The font is the same at every size (m20027), so the
     * size of the window decides how many turns are readable before scrolling: the smallest panel
     * shows one turn (下滚一行看一行, as asked), the largest shows five.
     */
    public static int visibleRows(float factor) {
        if (Float.isNaN(factor) || factor <= 0.0f) {
            return 3;
        }
        if (factor < COMPACT_BELOW) {
            return ROWS_MIN;
        }
        if (factor < MEDIUM_BELOW) {
            return 2;
        }
        if (factor < ROWS_4_ABOVE) {
            return 3;
        }
        return factor < ROWS_5_ABOVE ? 4 : ROWS_MAX;
    }

    /** Whether the turn stats column (HP / energy / block) fits at this level. */
    public static boolean showStats(int level) {
        return level != COMPACT;
    }

    /** Whether the per-target HP tail ("\u654c 9/15") fits at this level. */
    public static boolean showTargetHp(int level) {
        return level == FULL;
    }

    /** How many header lines are drawn: everything, or a short summary. */
    public static int headerCap(int level) {
        return level == COMPACT ? 2 : (level == MEDIUM ? 5 : Integer.MAX_VALUE);
    }

    /**
     * The header lines that survive at this level. The first line is the title/status and is always
     * kept; the rest are filtered by {@link #IMPORTANT} so a narrow panel never shows bookkeeping.
     */
    public static List<String> headerLines(List<String> lines, int level) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<String>();
        }
        if (level == FULL) {
            return lines;
        }
        List<String> out = new ArrayList<String>();
        out.add(lines.get(0));
        int cap = headerCap(level);
        for (int i = 1; i < lines.size() && out.size() < cap; i++) {
            if (important(lines.get(i))) {
                out.add(lines.get(i));
            }
        }
        // A compact strip that only matched the title still says what the search found: fall back to
        // the last line, which is the plan headline (or the "no plan yet" hint).
        if (out.size() == 1 && lines.size() > 1) {
            out.add(lines.get(lines.size() - 1));
        }
        return out;
    }

    private static boolean important(String line) {
        if (line == null) {
            return false;
        }
        for (int i = 0; i < IMPORTANT.length; i++) {
            if (line.contains(IMPORTANT[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rows the strip draws at this level. Pill order and step indices are preserved, so a click
     * still maps to the same plan step; only the text loses detail.
     */
    public static List<RouteRow> rows(List<RouteRow> rows, int level) {
        if (rows == null || rows.isEmpty() || level == FULL) {
            return rows;
        }
        List<RouteRow> out = new ArrayList<RouteRow>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            RouteRow row = rows.get(r);
            RouteRow copy = new RouteRow(level == COMPACT ? compactTurn(row.label) : row.label,
                    showStats(level) ? row.stats : "");
            for (int i = 0; i < row.pills.size(); i++) {
                String pill = row.pills.get(i);
                if (!showTargetHp(level)) {
                    pill = dropTargetHp(pill);
                }
                if (level == COMPACT) {
                    pill = compactPill(pill);
                }
                copy.add(pill, row.steps.get(i));
            }
            out.add(copy);
        }
        return out;
    }

    /** "\u7b2c 1 \u56de\u5408" -> "\u7b2c1\u56de\u5408". */
    public static String compactTurn(String label) {
        if (label == null) {
            return "";
        }
        return label.replace(" ", "");
    }

    /** Removes the "  \u654c 9/15" tail that buildRoute appends after a pill label. */
    public static String dropTargetHp(String pill) {
        if (pill == null) {
            return "";
        }
        int at = pill.indexOf("  \u654c ");
        return at > 0 ? pill.substring(0, at) : pill;
    }

    /**
     * The shortest form that still identifies the action: "\u6253\u51fb \u2192 \u8671\u866b\uff08\u5de6\u8d771\uff09" becomes
     * "\u6253\u51fb\u2192\u8671\u866b1", and "\u7ed3\u675f\u56de\u5408  \u9884\u8ba1 -2 HP" becomes "\u7ed3\u675f\u56de\u5408 -2HP".
     */
    public static String compactPill(String pill) {
        if (pill == null) {
            return "";
        }
        String s = dropTargetHp(pill);
        s = s.replace(" \u2192 ", "\u2192");
        s = s.replace("  \u9884\u8ba1 ", " ");
        s = s.replace(" HP", "HP");
        s = shortenLeftCount(s);
        return s;
    }

    /** "\u8671\u866b\uff08\u5de6\u8d771\uff09" -> "\u8671\u866b1": the parenthesised left-to-right index is the informative part. */
    private static String shortenLeftCount(String s) {
        String open = "\uff08\u5de6\u8d77";
        int at = s.indexOf(open);
        while (at >= 0) {
            int close = s.indexOf('\uff09', at);
            if (close < 0) {
                break;
            }
            String num = s.substring(at + open.length(), close);
            s = s.substring(0, at) + num + s.substring(close + 1);
            at = s.indexOf(open, at + num.length());
        }
        return s;
    }
}
