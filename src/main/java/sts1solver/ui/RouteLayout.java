package sts1solver.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the route strip: turn cards, action pills, wrapping and scrolling.
 *
 * <p>Kept free of game classes (the caller supplies a text measurer) so a headless gate can prove
 * what the old list could not do: pills stay inside the panel while wrapping, the turn label column
 * is never covered by a pill, and scrolling is clamped to the real content height. The renderer and
 * the click handler both read this, so a pill can never be drawn in one place and clicked in another.
 *
 * <p>Each turn is one card, like STS2's SolverRouteRow: [label column][pills ...][stats, right
 * aligned]. The stats sit on the first pill line only, so pills get a narrower line there (they wrap
 * onto the full width afterwards).
 */
public final class RouteLayout {

    /** Width of a text, in game units. */
    public interface Measure {
        float width(String text);
    }

    /** A laid-out turn card. y and height are measured DOWNWARD from the top of the strip. */
    public static final class Placed {
        public final RouteRow row;
        public final int rowIndex;
        /** Top of the card, downward from the strip top. */
        public float y;
        public float height;
        /** Left column width reserved for the turn label (0 when the row has no label). */
        public float labelW;
        /** x of the label text, relative to the strip left. */
        public float labelX;
        /** Baseline offset of label and stats, downward from the card top. */
        public float textBaseline;
        /** Right edge available to the stats text, relative to the strip left. */
        public float statsRight;
        /** Measured width of the stats text (0 when empty). */
        public float statsW;
        /**
         * {x, y, width, height, step, textLines} for each pill, y measured downward.
         *
         * <p>textLines is 1 for an ordinary pill; a pill whose label is wider than the strip wraps
         * its text and grows downwards (B33), and the renderer needs the count to space the lines.
         */
        public final List<float[]> pills = new ArrayList<float[]>();

        Placed(RouteRow row, int rowIndex) {
            this.row = row;
            this.rowIndex = rowIndex;
        }
    }

    /**
     * Metrics follow STS2's SolverUiTokens (ActionPillHeight 28 / RouteRowHeight 44 / Sm 8 / Md 12)
     * but are a little larger: our strip is a 720-wide scroll box, not STS2's 148-high view.
     */
    public static final float PILL_H = 30.0f;
    /** Left column inside a card, wide enough for 第 12 回合. */
    public static final float LABEL_W = 104.0f;
    public static final float GAP_X = 10.0f;
    public static final float GAP_Y = 16.0f;
    /** Air between two turn cards (never after the last one). */
    public static final float TURN_GAP = 10.0f;
    public static final float TOP_PAD = 8.0f;
    public static final float CARD_PAD_X = 12.0f;
    public static final float CARD_PAD_Y = 10.0f;
    /** Space between the pills and the right-aligned stats. */
    public static final float STATS_GAP = 18.0f;
    /** Space between the label column and the first pill. */
    public static final float LABEL_GAP = 8.0f;
    public static final float PILL_PAD_X = 28.0f;
    public static final float MIN_PILL_W = 44.0f;
    /** Horizontal air between a pill's border and its text. */
    public static final float PILL_TEXT_PAD_X = 20.0f;
    /** Height of one text line inside a multi-line pill (B33). */
    public static final float PILL_LINE_H = 22.0f;
    /** Vertical air above and below the text of a multi-line pill. */
    public static final float PILL_PAD_Y = 6.0f;

    private RouteLayout() {
    }

    /** Lays the rows out into a strip panelWidth wide; coordinates measured downward. */
    public static List<Placed> layout(List<RouteRow> rows, float panelWidth, float scale,
                                      Measure measure) {
        List<Placed> out = new ArrayList<Placed>();
        if (rows == null || rows.isEmpty() || panelWidth <= 0.0f) {
            return out;
        }
        float pillH = PILL_H * scale;
        float textLineH = PILL_LINE_H * scale;
        float textPadX = PILL_TEXT_PAD_X * scale;
        float textPadY = PILL_PAD_Y * scale;
        float gapX = GAP_X * scale;
        float gapY = GAP_Y * scale;
        float padX = CARD_PAD_X * scale;
        float padY = CARD_PAD_Y * scale;
        float cardY = TOP_PAD * scale;
        for (int r = 0; r < rows.size(); r++) {
            RouteRow row = rows.get(r);
            Placed placed = new Placed(row, r);
            placed.y = cardY;
            // m20027: the label column is measured, not assumed. LABEL_W (104) is narrower than
            // "第 1 回合" in the game font, so the first pill was drawn on top of the label
            // ("第一回合和第一个行动挡住了"). The column is now at least as wide as the label plus its gap.
            float labelTextW = row.label.isEmpty() || measure == null ? 0.0f : measure.width(row.label);
            placed.labelW = row.label.isEmpty() ? 0.0f
                    : Math.max(LABEL_W * scale, labelTextW + LABEL_GAP * scale);
            placed.labelX = padX;
            placed.textBaseline = padY + pillH * 0.68f;
            placed.statsRight = panelWidth - padX;
            placed.statsW = row.stats.isEmpty() || measure == null
                    ? 0.0f : measure.width(row.stats);
            float contentX = padX + placed.labelW;
            // The stats take room on the first pill line only; later lines use the full width.
            float statsLineW = Math.max(MIN_PILL_W * scale,
                    placed.statsRight - contentX - placed.statsW
                            - (placed.statsW > 0.0f ? STATS_GAP * scale : 0.0f));
            float fullW = Math.max(MIN_PILL_W * scale, placed.statsRight - contentX);
            float rowY = cardY + padY;
            float x = 0.0f;
            float lineW = statsLineW;
            float lineH = 0.0f;
            float contentBottom = rowY + pillH;
            for (int i = 0; i < row.pills.size(); i++) {
                String text = row.pills.get(i);
                float textW = measure == null ? 0.0f : measure.width(text);
                float w = Math.max(MIN_PILL_W * scale, textW + textPadX);
                // B33: a one-line pill wider than the strip used to be clamped to the strip width
                // while its text was drawn unclipped, so the label ran off the screen edge. Now the
                // text wraps inside the pill and the pill grows downwards instead.
                int textLines = 1;
                if (w > fullW && measure != null) {
                    List<String> pieces = TextWrap.wrapLine(text, Math.max(1.0f, fullW - textPadX),
                            measure);
                    float widest = 0.0f;
                    for (int k = 0; k < pieces.size(); k++) {
                        widest = Math.max(widest, measure.width(pieces.get(k)));
                    }
                    w = Math.max(MIN_PILL_W * scale, Math.min(widest + textPadX, fullW));
                    textLines = Math.max(1, pieces.size());
                }
                float h = textLines == 1 ? pillH : textLines * textLineH + 2.0f * textPadY;
                // A pill that does not fit the first line is moved down even when it is the first
                // pill of that line: the first line is narrower because the stats sit on it, and
                // that is exactly how a pill used to end up drawn on top of the numbers.
                boolean statsLine = lineW < fullW;
                if (x + w > lineW && (x > 0.0f || statsLine)) {
                    rowY += lineH + gapY;
                    x = 0.0f;
                    lineW = fullW;
                    lineH = 0.0f;
                }
                placed.pills.add(new float[]{contentX + x, rowY, w, h, row.steps.get(i), textLines});
                x += w + gapX;
                lineH = Math.max(lineH, h);
                contentBottom = Math.max(contentBottom, rowY + lineH);
            }
            placed.height = row.pills.isEmpty() ? pillH + 2.0f * padY
                    : (contentBottom - (cardY + padY)) + 2.0f * padY;
            cardY += placed.height;
            out.add(placed);
            // Air between cards (not after the last one, or the strip would grow an empty band on
            // every repaint and the scrollbar thumb could never reach the end).
            if (r < rows.size() - 1) {
                cardY += TURN_GAP * scale;
            }
        }
        return out;
    }

    /**
     * Clips a rectangle [y, y + h] to the visible band [clipBottom, clipTop] (B32).
     *
     * <p>A card scrolled half out of the strip used to be drawn in full, so its border and label
     * painted over the header text above the strip -- reported as "第一回合框没有消失". Returning
     * {y, height} of the intersection lets the renderer draw the visible part only.
     *
     * @return {y, height}, or null when nothing of the rectangle is inside the band
     */
    public static float[] clipBand(float y, float h, float clipBottom, float clipTop) {
        float lo = Math.max(y, clipBottom);
        float hi = Math.min(y + h, clipTop);
        if (hi - lo <= 0.5f) {
            return null;
        }
        return new float[]{lo, hi - lo};
    }
    /** Total height of a laid-out strip. */
    public static float totalHeight(List<Placed> placed) {
        if (placed == null || placed.isEmpty()) {
            return 0.0f;
        }
        Placed last = placed.get(placed.size() - 1);
        return last.y + last.height;
    }

    /** Keeps a scroll offset inside [0, totalHeight - viewHeight]. */
    public static float clampScroll(float scroll, float totalHeight, float viewHeight) {
        float max = totalHeight - viewHeight;
        if (max <= 0.0f) {
            return 0.0f;
        }
        if (scroll < 0.0f) {
            return 0.0f;
        }
        return scroll > max ? max : scroll;
    }

    /** Row index holding the pill for step, or -1. */
    public static int rowIndexOfStep(List<Placed> placed, int step) {
        if (placed == null || step < 0) {
            return -1;
        }
        for (int i = 0; i < placed.size(); i++) {
            List<float[]> pills = placed.get(i).pills;
            for (int j = 0; j < pills.size(); j++) {
                if ((int) pills.get(j)[4] == step) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Row to scroll to for the current step: the exact row, else the next one, else the last. */
    public static int rowForCurrentStep(List<Placed> placed, int step) {
        if (placed == null || placed.isEmpty() || step < 0) {
            return -1;
        }
        int exact = rowIndexOfStep(placed, step);
        if (exact >= 0) {
            return exact;
        }
        for (int i = 0; i < placed.size(); i++) {
            if (!placed.get(i).pills.isEmpty() && placed.get(i).row.firstStepAtOrAfter(step) >= 0) {
                return i;
            }
        }
        return placed.size() - 1;
    }
}
