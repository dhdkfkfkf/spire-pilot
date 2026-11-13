package sts1solver.ui;

/**
 * Pure geometry for the clickable solver panel (P1-1c).
 *
 * <p>One source of truth: the renderer and the click handler both ask this class where a button is,
 * so a button can never be drawn in one place and clicked in another. Kept free of game classes so a
 * headless test can prove the layout (disjoint buttons, correct hit tests, collapsed hides them).
 */
public final class PanelLayout {

    /**
     * Button ids, in draw order.
     *
     * <p>Only operations that are not already implied by another one are here. Removed in B43:
     * "deep" (calculate already spends the whole budget and never stops at the first win line),
     * "plan" (running the whole line is what autopilot does), and "scrollUp"/"scrollDown" (the
     * wheel scrolls the route; arrow keys stay as shortcuts). Every remaining operation is reachable
     * with the mouse alone.
     *
     * <p>B143 (user m10519): "stop" is gone as well -- "solve" already stops a running search when
     * clicked (its label says so), so the button only offered a second way to do the same thing.
     * "growth" is new: it flips whether out-of-combat gains (gold, max HP, permanent damage,
     * upgrades, potions) are priced into the score.
     */
    public static final String[] IDS = {
            "solve", "step", "auto", "seconds", "nodes", "pace", "potion", "growth", "collapse",
            "hide"};

    /**
     * Button labels, same order (seconds/nodes/pace/potion/growth/hide/auto are overridden with live state).
     *
     * <p>"pace" is P2-14: autoplay used to run at whatever speed the engine allowed, which measured
     * 1.3 s for a whole fight; the button cycles the per-action delay.
     */
    public static final String[] LABELS = {
            "计算", "执行一步", "自动出牌", "秒数", "节点", "节奏", "药水", "局外收益",
            "收起", "隐藏"};

    private PanelLayout() {
    }

    public static float width(float scale) {
        return 300.0f * scale;
    }

    public static float x0(float screenWidth, float scale) {
        return screenWidth - width(scale) - 18.0f * scale;
    }

    public static float y0(float screenHeight, float scale) {
        return screenHeight - 120.0f * scale;
    }

    /**
     * The "导出报告" button in the header band (player feedback request, 1.0.0).
     *
     * <p>Deliberately NOT a grid entry: the grid is 2 x 5 and a sixth row would push the whole panel
     * down for a button nobody presses twice. The header band already reserves 64 px of empty space
     * to the right of the title.
     */
    public static float[] reportRect(float screenWidth, float screenHeight, float scale) {
        float w = 84.0f * scale;
        float h = 22.0f * scale;
        return new float[]{x0(screenWidth, scale) + width(scale) - w - 8.0f * scale,
                y0(screenHeight, scale) - 26.0f * scale, w, h};
    }

    /** True when the point is on the header's report button; never while collapsed or hidden. */
    public static boolean hitTestReport(float mx, float my, float screenWidth, float screenHeight,
                                        float scale, boolean collapsed) {
        if (collapsed) {
            return false;
        }
        float[] r = reportRect(screenWidth, screenHeight, scale);
        return inside(mx, my, r[0], r[1], r[2], r[3]);
    }

    /** Title + status rows above the button grid; buttons start below this. */
    public static float headerHeight(float scale) {
        return 64.0f * scale;
    }

    public static float buttonWidth(float scale) {
        return 138.0f * scale;
    }

    public static float buttonHeight(float scale) {
        return 34.0f * scale;
    }

    public static float gap(float scale) {
        return 8.0f * scale;
    }

    /** Column 0/1 and row for a button index. */
    public static int columnOf(int index) {
        return index % 2;
    }

    public static int rowOf(int index) {
        return index / 2;
    }

    /** Button rect as {x, y, width, height} in game coordinates. */
    public static float[] rect(int index, float screenWidth, float screenHeight, float scale) {
        float x = x0(screenWidth, scale) + columnOf(index) * (buttonWidth(scale) + gap(scale));
        float y = y0(screenHeight, scale) - headerHeight(scale)
                - (rowOf(index) + 1) * (buttonHeight(scale) + gap(scale));
        return new float[]{x, y, buttonWidth(scale), buttonHeight(scale)};
    }

    public static float panelHeight(float scale) {
        int rows = (IDS.length + 1) / 2;
        return headerHeight(scale) + rows * (buttonHeight(scale) + gap(scale)) + 6.0f * scale;
    }

    /**
     * Index of the button under (mx, my), or -1.
     *
     * <p>Collapsed panels expose one small tab instead of the grid; that tab is reported as the
     * collapse button so a click always means the same thing.
     */
    public static int hitTest(float mx, float my, float screenWidth, float screenHeight, float scale,
                              boolean collapsed) {
        if (collapsed) {
            float w = 110.0f * scale;
            float h = 28.0f * scale;
            float x = x0(screenWidth, scale);
            float y = y0(screenHeight, scale) - h;
            return inside(mx, my, x, y, w, h) ? indexOf("collapse") : -1;
        }
        for (int i = 0; i < IDS.length; i++) {
            float[] r = rect(i, screenWidth, screenHeight, scale);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                return i;
            }
        }
        return -1;
    }

    /** Index of a button id, or -1. */
    public static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** True when the point is inside the panel's background rectangle. */
    public static boolean insidePanel(float mx, float my, float screenWidth, float screenHeight,
                                      float scale, boolean collapsed) {
        float x = x0(screenWidth, scale);
        float y = collapsed
                ? y0(screenHeight, scale) - 28.0f * scale
                : y0(screenHeight, scale) - panelHeight(scale);
        float h = collapsed ? 28.0f * scale : panelHeight(scale);
        return inside(mx, my, x, y, width(scale), h);
    }

    /**
     * The tab drawn while the whole overlay is hidden (F11 / the 隐藏 button).
     *
     * <p>Without it, hiding the panel was a one-way door: the only way back was a hotkey.
     */
    public static float[] hiddenTabRect(float screenWidth, float screenHeight, float scale) {
        float w = 96.0f * scale;
        float h = 28.0f * scale;
        return new float[]{x0(screenWidth, scale) + width(scale) - w, y0(screenHeight, scale) - h, w, h};
    }

    /** Hit test for the "show the panel again" tab; returns the hide button's index or -1. */
    public static int hitTestHidden(float mx, float my, float screenWidth, float screenHeight,
                                    float scale) {
        float[] r = hiddenTabRect(screenWidth, screenHeight, scale);
        return inside(mx, my, r[0], r[1], r[2], r[3]) ? indexOf("hide") : -1;
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}