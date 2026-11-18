package sts1solver.ui;

/**
 * Pure geometry for the clickable solver panel (P1-1c).
 *
 * <p>One source of truth: the renderer and the click handler both ask this class where a button is,
 * so a button can never be drawn in one place and clicked in another. Kept free of game classes so a
 * headless test can prove the layout (disjoint buttons, correct hit tests, collapsed hides them).
 *
 * <p>1.1.0 (user m01372): the panel is no longer a 2 x 5 grid of identical buttons. It is four bands
 * stacked top to bottom -- <b>action</b> (one full-width primary + a two-up row), <b>params</b> (five
 * chips in two rows), <b>status</b> (three rows drawn by the renderer), and a <b>foot</b> (scale
 * label + 收起 / 隐藏). The band heights below are the only place those numbers live; every rect is
 * derived from them, so the panel background, the buttons and the hit tests can never drift apart.
 *
 * <p>The button <em>ids</em> did not change: only where each one sits. "solve" is the primary,
 * "step"/"auto" are the two-up row, "seconds"/"nodes"/"pace"/"potion"/"growth" are chips, and
 * "collapse"/"hide" are the foot links. Keeping the ids stable keeps every keyboard branch, the
 * settings keys and the gate that lists them working unchanged.
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
     *
     * <p>1.1.0: the param labels lost their verbose tails. A chip is read at a glance next to its
     * value ("秒数 12s"), not as a sentence; the value is what changes and the value is what the
     * player is looking for.
     */
    public static final String[] LABELS = {
            "计算", "执行一步", "自动出牌", "秒数", "节点", "节奏", "药水", "收益",
            "收起", "隐藏"};

    /** Left/right inset of every band, in game units before scale. */
    private static final float PAD = 10.0f;
    /**
     * Panel width.
     *
     * <p>300 could not hold a primary action plus five params without clipping; 440 was what the
     * chips needed once their values were printed in full ("节奏 450ms" used to lose its tail in
     * the two-up row at 400). 1.1.0 (user m06251 "面板左右距离变短") pulls it back to 360: a
     * three-up chip is still 108 units, which fits "节奏 450ms" with room, and the panel stops
     * eating the right third of the play area.
     */
    private static final float WIDTH = 360.0f;

    /**
     * The line box of {@code FontHelper.topPanelInfoFont} at tier 1.0, in game units.
     *
     * <p>1.1.0 (user m03103 "Y 轴太挤了，都压一块了"): libGDX's {@code font.draw(sb, s, x, y)} takes
     * the <em>top</em> of the line, and the glyphs hang downward from it for one full line box. The
     * previous bands were 16-30 units tall while every baseline was written as {@code y + h*0.62},
     * so a 26 unit line started two thirds down its band and then ran 26 units further down -- 10 to
     * 20 units past the band's bottom edge, straight into the band below it. That is what "压一块"
     * was. Every band below is now at least one line box plus padding, and the renderer centres the
     * line inside its band instead of guessing at 0.62.
     */
    public static final float LINE_H = 26.0f;

    /** One title line. */
    private static final float HEADER_H = 32.0f;
    /**
     * The status block: row 1 (percentage + promise), the progress bar, row 2 (the four numbers).
     *
     * <p>1.1.0 (user m04872 "y轴太长了，不好用，浪费空间"): the third band -- the running pass's
     * phase, "窄深搜 beam=10 layers=3" -- is gone. It was 26 units of jargon that no decision ever
     * turned on, and the two things a player actually needs from it are already on row 1: the
     * "无进展" state word and its amber tone say the search has stalled, and the promise says what
     * it is aiming at. The phase still goes to solver.log and the bug report.
     *
     * <p>1.1.0 (user m06251 "进度条改回去了...这里这个就只留折叠时进度条作用"): the bar left this
     * block. It now lives only on the collapsed tab, where it is the single thing that says "the
     * search is still moving" without the player opening anything. With the bar gone the block is
     * two text rows and three 4 unit pads: 4 + 26 + 4 + 26 + 4 = 64.
     *
     * <p>m19272 (user): the bar comes back, as a grid of pixels rather than one solid rectangle,
     * and it gets its own 14 unit band between row 2 and the block's bottom pad:
     * {@code 4 + 26 + 4 + 26 + 4 + 14 + 4 = 82}. It is a separate band, not an overlay, because the
     * numbers above it are what the player reads first and a bar drawn through them would make both
     * unreadable.
     */
    private static final float STATUS_H = 157.0f;
    /**
     * The pixel field inside the expanded status block (m19272, m19340).
     *
     * <p>The bar was moved OUT of this block by m06251, on the argument that the numbers below it
     * already said where the search stood. The player asked for it back as a field of pixels, so it
     * comes back with its own band: {@code 4 + 26 + 4 + 26 + 4 + 89 + 4 = 157}.
     *
     * <p>m19340 (user "正方形切成NXN小格，按进度点亮"): the field is a SQUARE cut into N x N cells. Ten
     * cells at an 8 unit edge with 1 unit seams make an 89 unit square -- a readable pixel block
     * that still leaves the numbers room, rather than a stripe the width of the panel.
     */
    private static final int STATUS_BAR_N = 10;
    private static final float STATUS_BAR_EDGE = 8.0f;
    private static final float STATUS_BAR_GAP = 1.0f;
    /** The field's side: N cells plus the N-1 seams between them. */
    private static final float STATUS_BAR_SIDE =
            STATUS_BAR_N * STATUS_BAR_EDGE + (STATUS_BAR_N - 1) * STATUS_BAR_GAP;
    /** The 4 unit pad the status block uses between its bands, reused for the field's own inset. */
    private static final float STATUS_PAD = 4.0f;
    /**
     * The primary action carries the largest label, so it gets a line box plus a wide bezel.
     *
     * <p>1.1.0 (user m06251 "把计算和自动出牌按钮改大"): 36 became 48. The panel got narrower at
     * the same time, so the action rows are the one place where the freed width turns into height:
     * these are the two buttons a player presses mid-fight and they were the smallest targets on
     * the panel.
     */
    private static final float PRIMARY_H = 48.0f;
    private static final float ROW2_H = 44.0f;
    /** A chip holds a caption and a value on one line, so it is a line box plus a thin bezel. */
    private static final float CHIP_H = 28.0f;
    private static final float CHIP_GAP = 8.0f;
    private static final float CHIPS_H = 2.0f * CHIP_H + CHIP_GAP;
    /**
     * m02372: 收起 / 隐藏 used to be 28 units of bare text -- links, not buttons, and the smallest
     * targets on the panel. They are now drawn by the same {@code drawButton} as everything else and
     * sized like the two-up row, so the foot band is a real button row instead of a caption line.
     */
    private static final float FOOT_H = ROW2_H;
    /** Between two different bands. */
    private static final float BLOCK_GAP = 8.0f;
    /** Between two controls that belong to the same band. */
    private static final float INNER_GAP = 8.0f;

    /** Band offsets measured down from the panel's top edge. */
    private static final float STATUS_OFF = HEADER_H + BLOCK_GAP;
    private static final float PRIMARY_OFF = STATUS_OFF + STATUS_H + BLOCK_GAP;
    private static final float ROW2_OFF = PRIMARY_OFF + PRIMARY_H + INNER_GAP;
    /**
     * 1.1.0 (user m04872 "y轴太长了"): the "参数" caption band is gone. It was 30 units of vertical
     * space -- a 22 unit band plus an 8 unit gap -- spent on the single word 参数, above five chips
     * whose own captions ("秒数" "节点") already say what they are. Dropping it moves the chips up
     * against the action rows, where they read as one block with them.
     */
    private static final float CHIPS_OFF = ROW2_OFF + ROW2_H + BLOCK_GAP;
    private static final float FOOT_OFF = CHIPS_OFF + CHIPS_H + INNER_GAP;

    /** Width of one of the two buttons in the two-up row. */
    private static final float HALF_W = (WIDTH - 2.0f * PAD - INNER_GAP) / 2.0f;
    /** Width of one chip in the first (three-up) chip row. */
    private static final float CHIP3_W = (WIDTH - 2.0f * PAD - 2.0f * CHIP_GAP) / 3.0f;
    /** Width of one chip in the second (two-up) chip row. */
    private static final float CHIP2_W = (WIDTH - 2.0f * PAD - CHIP_GAP) / 2.0f;
    /**
     * Width of one foot button (收起 / 隐藏).
     *
     * <p>m02372: these were 44 units of text, half the width of 执行一步 and a third of its height,
     * which made them read as captions rather than as the two controls that shrink or hide the
     * whole overlay. They are now a two-up row of the same width as the action row above them.
     */
    private static final float FOOT_W = HALF_W;
    /**
     * The collapsed tab's geometry (1.1.0).
     *
     * <p>Two things changed together here, both from user m06251 ("这里这个就只留折叠时进度条作用，同时设计
     * 让计算和自动出牌不会被折叠，只有隐藏时它们才消失"):
     *
     * <ul>
     *   <li>The progress bar moved out of the expanded status block and onto this tab. Collapsed,
     *       the bar is the one thing that says "the search is still moving" without the player
     *       opening anything, so it earns its place here and only here.</li>
     *   <li>The tab is no longer a single small pill. 计算 and 自动出牌 stay drawn and stay
     *       clickable while collapsed -- they are the two things a player reaches for mid-fight,
     *       and hiding them behind an expand click was pure friction. Only 隐藏 (the game's own
     *       toggle) takes the whole overlay away.</li>
     * </ul>
     *
     * <p>So the collapsed panel is a stack of three bands: the title strip, the bar, and the
     * action row. The chips, the step button and the foot links are the only things collapse
     * takes away.
     */
    private static final float COLLAPSED_TAB_H = 28.0f;
    private static final float COLLAPSED_BAR_H = 6.0f;
    private static final float COLLAPSED_ACTION_H = 44.0f;
    /** 28 tab + 6 pad + 6 bar + 8 pad + 44 actions. */
    private static final float COLLAPSED_H =
            COLLAPSED_TAB_H + 6.0f + COLLAPSED_BAR_H + 8.0f + COLLAPSED_ACTION_H;

    /** First chip index in {@link #IDS}. */
    private static final int CHIP_FIRST = 3;
    /** Number of chips. */
    private static final int CHIP_COUNT = 5;
    /**
     * The buttons that stay live while the panel is collapsed (1.1.0, user m06251): 计算 and
     * 自动出牌. Order matters -- slot 0 draws and hit-tests on the left.
     */
    private static final int[] COLLAPSED_ACTIONS = {0, 2};

    private PanelLayout() {
    }

    public static float width(float scale) {
        return WIDTH * scale;
    }

    /** The panel's inner left/right inset, exposed so the renderer never re-derives it. */
    public static float pad(float scale) {
        return PAD * scale;
    }

    public static float x0(float screenWidth, float scale) {
        return screenWidth - width(scale) - 16.0f * scale;
    }

    /**
     * How far below the screen's top edge the panel starts (user m03534: "我希望往下一点，现在挡住了药水和
     * 遗物").
     *
     * <p>The game's own top bar is {@code TopPanel.TOPBAR_H}: 128 game units on desktop, 164 on mobile,
     * both multiplied by {@code Settings.scale}. The potion row and the relic bar are drawn inside it.
     * The old 96 unit inset put the panel's first band, and the route strip's first text line (which
     * used to start at a flat {@code HEIGHT - 28}), straight through those icons. The panel now starts
     * 8 units below the bar, and {@link Panel#drawPlanText} uses this same figure so the strip and the
     * panel cannot drift apart again.
     */
    public static final float TOPBAR_H = 128.0f;
    /** Breathing room between the game's top bar and the panel's first band. */
    private static final float TOP_GAP = 8.0f;

    /** The screen-space distance from the top edge down to the panel's top edge. */
    public static float topInset(float scale) {
        return (TOPBAR_H + TOP_GAP) * scale;
    }

    public static float y0(float screenHeight, float scale) {
        return screenHeight - topInset(scale);
    }

    /**
     * The "导出报告" button in the header band (player feedback request, 1.0.0).
     *
     * <p>1.1.0: it is an icon-sized square at the right end of the header instead of an 84 px pill,
     * so the header reads as a title bar rather than a third row of buttons.
     */
    public static float[] reportRect(float screenWidth, float screenHeight, float scale) {
        float w = 22.0f * scale;
        float h = 22.0f * scale;
        return new float[]{x0(screenWidth, scale) + width(scale) - PAD * scale - w,
                y0(screenHeight, scale) - HEADER_H * scale + (HEADER_H * scale - h) * 0.5f, w, h};
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

    /** Title band at the very top of the panel. */
    public static float headerHeight(float scale) {
        return HEADER_H * scale;
    }

    /** Height of the structured status block the renderer paints (three rows + a progress bar). */
    public static float statusHeight(float scale) {
        return STATUS_H * scale;
    }

    /** Height of one button in the two-up row. */
    public static float buttonHeight(float scale) {
        return ROW2_H * scale;
    }

    /** Width of one button in the two-up row. */
    public static float buttonWidth(float scale) {
        return HALF_W * scale;
    }

    /** Between two bands. */
    public static float gap(float scale) {
        return BLOCK_GAP * scale;
    }

    /** Height of one param chip. */
    public static float chipHeight(float scale) {
        return CHIP_H * scale;
    }

    /** Column 0/1 for the two-up row: "auto" (index 2) is left, "step" (index 1) is right. */
    public static int columnOf(int index) {
        return index == 1 ? 1 : 0;
    }

    public static int rowOf(int index) {
        return index <= 2 ? 0 : (index - CHIP_FIRST) / 3 + 1;
    }

    /** True when the index is one of the five param chips. */
    public static boolean isChip(int index) {
        return index >= CHIP_FIRST && index < CHIP_FIRST + CHIP_COUNT;
    }

    /** The full-width primary action ("solve"), which is also the collapsed tab's target. */
    public static float[] primaryRect(float screenWidth, float screenHeight, float scale) {
        return new float[]{x0(screenWidth, scale) + PAD * scale,
                y0(screenHeight, scale) - (PRIMARY_OFF + PRIMARY_H) * scale,
                (WIDTH - 2.0f * PAD) * scale, PRIMARY_H * scale};
    }

    /** One of the two buttons in the two-up row, by index (1 = 执行一步, 2 = 自动出牌). */
    public static float[] row2Rect(int index, float screenWidth, float screenHeight, float scale) {
        float x = x0(screenWidth, scale) + PAD * scale
                + columnOf(index) * (HALF_W + INNER_GAP) * scale;
        float y = y0(screenHeight, scale) - (ROW2_OFF + ROW2_H) * scale;
        return new float[]{x, y, HALF_W * scale, ROW2_H * scale};
    }

    /** One param chip, by chip slot 0..4 (秒数 / 节点 / 节奏 / 药水 / 收益). */
    public static float[] chipRect(int slot, float screenWidth, float screenHeight, float scale) {
        boolean firstRow = slot < 3;
        float w = firstRow ? CHIP3_W : CHIP2_W;
        int col = firstRow ? slot : slot - 3;
        float x = x0(screenWidth, scale) + PAD * scale + col * (w + CHIP_GAP) * scale;
        float rowTop = firstRow
                ? CHIPS_OFF : CHIPS_OFF + CHIP_H + CHIP_GAP;
        float y = y0(screenHeight, scale) - (rowTop + CHIP_H) * scale;
        return new float[]{x, y, w * scale, CHIP_H * scale};
    }

    /** The status block rect {x, y, w, h} the renderer paints the three rows into. */
    public static float[] statusRect(float screenWidth, float screenHeight, float scale) {
        return new float[]{x0(screenWidth, scale) + PAD * scale,
                y0(screenHeight, scale) - (STATUS_OFF + STATUS_H) * scale,
                (WIDTH - 2.0f * PAD) * scale, STATUS_H * scale};
    }

    /**
     * The pixel field's rect inside the expanded status block (m19272, m19340).
     *
     * <p>m19340 (user "正方形切成NXN小格"): the field is a SQUARE, so its width and height are both
     * {@link #STATUS_BAR_SIDE}. It is centred horizontally in the block and sits on the block's
     * bottom band, {@code STATUS_PAD} above the block's bottom edge. Centring matters: the block is
     * 340 units wide and the field is 89, so a left-aligned field would look like a stray swatch
     * rather than the block's own progress readout.
     */
    public static float[] statusBarRect(float screenWidth, float screenHeight, float scale) {
        float[] r = statusRect(screenWidth, screenHeight, scale);
        float side = STATUS_BAR_SIDE * scale;
        return new float[]{r[0] + (r[2] - side) * 0.5f, r[1] + STATUS_PAD * scale, side, side};
    }

    /** How many cells the field is cut into along one side; the grid is N x N. */
    public static int statusBarCells() {
        return STATUS_BAR_N;
    }

    /** One cell's edge in virtual units, before the panel scale is applied. */
    public static float statusBarCellEdge() {
        return STATUS_BAR_EDGE;
    }

    /** The seam between two cells, in virtual units. */
    public static float statusBarCellGap() {
        return STATUS_BAR_GAP;
    }

    /** A foot button (收起 / 隐藏). {@code right} true picks 隐藏, which sits at the right edge. */
    public static float[] footRect(boolean right, float screenWidth, float screenHeight,
                                   float scale) {
        float x = x0(screenWidth, scale) + width(scale) - PAD * scale - FOOT_W * scale;
        if (!right) {
            x -= (FOOT_W + INNER_GAP) * scale;
        }
        return new float[]{x, y0(screenHeight, scale) - (FOOT_OFF + FOOT_H) * scale,
                FOOT_W * scale, FOOT_H * scale};
    }

    /** Button rect as {x, y, width, height} in game coordinates. */
    public static float[] rect(int index, float screenWidth, float screenHeight, float scale) {
        if (index == 0) {
            return primaryRect(screenWidth, screenHeight, scale);
        }
        if (index == 1 || index == 2) {
            return row2Rect(index, screenWidth, screenHeight, scale);
        }
        if (isChip(index)) {
            return chipRect(index - CHIP_FIRST, screenWidth, screenHeight, scale);
        }
        return footRect(index == indexOf("hide"), screenWidth, screenHeight, scale);
    }

    public static float panelHeight(float scale) {
        return (FOOT_OFF + FOOT_H) * scale;
    }

    /**
     * Index of the button under (mx, my), or -1.
     *
     * <p>Collapsed panels expose the tab plus the two action buttons (1.1.0, user m06251): 计算 and
     * the mode-specific action stay live while collapsed, so a click on either of them means what it
     * says instead of silently expanding the panel. That second button is 自动出牌 in 全自动 and
     * 执行一步 in 半自动. Everything else -- chips, the mode switch, the foot buttons -- is
     * gone while collapsed, and a click there hits nothing.
     */
    public static int hitTest(float mx, float my, float screenWidth, float screenHeight, float scale,
                              boolean collapsed) {
        if (collapsed) {
            int[] actions = COLLAPSED_ACTIONS;
            for (int i = 0; i < actions.length; i++) {
                float[] r = collapsedActionRect(i, screenWidth, screenHeight, scale);
                if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                    return actions[i];
                }
            }
            float[] r = collapsedTabRect(screenWidth, screenHeight, scale);
            return inside(mx, my, r[0], r[1], r[2], r[3]) ? indexOf("collapse") : -1;
        }
        for (int i = 0; i < IDS.length; i++) {
            float[] r = rect(i, screenWidth, screenHeight, scale);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The whole collapsed panel: tab, bar, action row.
     *
     * <p>1.1.0 (user m06251): it is no longer a small pill. It carries the live figure, the
     * progress bar and the two action buttons, so it is the panel's full width.
     */
    public static float[] collapsedRect(float screenWidth, float screenHeight, float scale) {
        return new float[]{x0(screenWidth, scale), y0(screenHeight, scale) - COLLAPSED_H * scale,
                width(scale), COLLAPSED_H * scale};
    }

    /** The title strip of the collapsed panel ("▶ 求解器 62%"). Clicking it expands. */
    public static float[] collapsedTabRect(float screenWidth, float screenHeight, float scale) {
        return new float[]{x0(screenWidth, scale),
                y0(screenHeight, scale) - COLLAPSED_TAB_H * scale,
                width(scale), COLLAPSED_TAB_H * scale};
    }

    /**
     * The progress bar of the collapsed panel. It moved here from the expanded status block
     * (user m06251 "这里这个就只留折叠时进度条作用"): collapsed, this bar is the only thing that
     * says the search is still moving.
     */
    public static float[] collapsedBarRect(float screenWidth, float screenHeight, float scale) {
        float off = COLLAPSED_TAB_H + 6.0f;
        return new float[]{x0(screenWidth, scale) + PAD * scale,
                y0(screenHeight, scale) - (off + COLLAPSED_BAR_H) * scale,
                (WIDTH - 2.0f * PAD) * scale, COLLAPSED_BAR_H * scale};
    }

    /** One of the two action buttons that survive collapse: slot 0 is 计算, slot 1 is the mode-specific
     *  action (自动出牌 in 全自动, 执行一步 in 半自动). */
    public static float[] collapsedActionRect(int slot, float screenWidth, float screenHeight,
                                              float scale) {
        float x = x0(screenWidth, scale) + PAD * scale
                + slot * (HALF_W + INNER_GAP) * scale;
        return new float[]{x, y0(screenHeight, scale) - COLLAPSED_H * scale,
                HALF_W * scale, COLLAPSED_ACTION_H * scale};
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
        float h = collapsed ? COLLAPSED_H * scale : panelHeight(scale);
        float y = y0(screenHeight, scale) - h;
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
