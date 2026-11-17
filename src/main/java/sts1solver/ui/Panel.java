package sts1solver.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import sts1solver.util.Log;

/**
 * The clickable solver panel (P1-1c), modelled on the STS2 solver's overlay.
 *
 * <p>Everything the player does lives on the panel: 计算 / 执行一步 / 自动出牌 / 秒数 / 节点 / 节奏 /
 * 药水 / 局外收益 / 收起 / 隐藏. Hotkeys still work, but they are no longer the only way in, and the
 * panel shows the same state the keys act on. B143 (user m10519): 停止 was folded into 计算 (a second
 * click stops the search), and 局外收益 is the on/off switch for out-of-combat pricing.
 *
 * <p>Two things are deliberate:
 *
 * <ul>
 *   <li><b>Hover highlight</b>: a button lights up when the mouse is over it, so a clickable panel
 *       looks clickable. STS2's overlay does the same.</li>
 *   <li><b>Cursor ring drawn last</b>: this overlay renders after the game, which means it can cover
 *       the game's mouse cursor -- reported as "看不清鼠标在哪". Drawing a small ring at the cursor
 *       after everything else keeps the pointer visible without moving the panel behind the game UI,
 *       which a post-render hook cannot do.</li>
 * </ul>
 */
public final class Panel {

    private static volatile int hover = -1;

    /** True while the pointer is over the header's 导出报告 button (1.0.0 feedback request). */
    private static volatile boolean reportHover;
    private static volatile boolean collapsed;
    private static volatile String status = "";
    private static int failures;
    /** Reused for measuring a button label (B144); drawing happens on the render thread only. */
    private static final GlyphLayout FIT = new GlyphLayout();

    /** {x, y, width, height, step} for every clickable action pill drawn last frame. */
    private static final java.util.List<float[]> pillBoxes = new java.util.ArrayList<float[]>();

    /** Scroll offset into the route strip, in game units (0 = show the first turn). */
    private static volatile float scrollPx;
    /** Grip rectangle of the route strip (B33), in game coordinates, from the last frame. */
    private static volatile float[] handleRect;
    private static boolean dragging;
    private static float dragStartY;
    private static float dragStartFactor = 1.0f;

    /**
     * True while the strip follows the current step; any manual scroll turns it off so the player's
     * view is not yanked back once per frame. The "回到当前" pill turns it on again.
     */
    private static volatile boolean followStep = true;
    private static int lastFollowedStep = -1;

    /**
     * Maximum height of the route viewport before the strip scrolls.
     *
     * <p>STS2 shows three 44px turn rows in a 148px viewport; we keep the whole strip in one
     * scrollable box, so the box has to be tall enough to read a few turns without scrolling. 520
     * leaves room for the status lines above on a 768px screen (B32 removed the two hint lines
     * below, and the strip now also shrinks to whatever room is actually left).
     */
    private static final float MAX_ROUTE_H = 520.0f;

    /**
     * The glyph scale is fixed (m20027). Dragging the grip changes how many turns are visible and how
     * much text each turn shows, never the font size: shrinking the letters made a small panel
     * unreadable instead of compact.
     */
    public static final float FIXED_TEXT_SCALE = 1.0f;

    /** Measured pill widths, keyed by text; the CJK font is expensive to lay out every frame. */
    private static final java.util.Map<String, Float> widthCache =
            new java.util.HashMap<String, Float>();

    /** Wrapped header lines, keyed by the joined text plus the fit width (B32). */
    private static final java.util.Map<String, java.util.List<String>> wrapCache =
            new java.util.HashMap<String, java.util.List<String>>();

    /**
     * P2-27: the whole route layout (turn cards, pill boxes, wrapped header lines) cached on a
     * content + geometry signature. RouteLayout.layout measures the text of every pill, so doing it
     * again on every frame was the largest per-frame cost of the overlay -- and the result only
     * changes when the plan, the current step, the drag factor or the panel width changes.
     */
    private static long layoutSig = 1L;
    private static java.util.List<RouteLayout.Placed> layoutPlaced;
    private static java.util.List<String> layoutFitted;
    private static float layoutTotal;
    private static long layoutBuilds;
    private static long layoutHits;

    private Panel() {
    }

    public static void setStatus(String text) {
        status = text == null ? "" : text;
    }

    public static boolean collapsed() {
        return collapsed;
    }

    public static void setCollapsed(boolean value) {
        collapsed = value;
    }

    public static int hover() {
        return hover;
    }

    /** Per-frame input: hover state, clicks, and the plan-row clicks the text list owns. */
    public static void update() {
        try {
            float scale = Settings.scale;
            float mx = InputHelper.mX;
            float my = InputHelper.mY;
            // P2-10: while the overlay is hidden only the "show me again" tab is clickable, so the
            // panel can never become a one-way door that only a hotkey can reopen.
            boolean shown = sts1solver.ui.Hud.visible();
            // The report button lives in the header band, above the grid: hit-test it first so a
            // click there can never also be read as a grid button.
            reportHover = shown && PanelLayout.hitTestReport(mx, my, Settings.WIDTH, Settings.HEIGHT,
                    scale, collapsed);
            hover = shown
                    ? PanelLayout.hitTest(mx, my, Settings.WIDTH, Settings.HEIGHT, scale, collapsed)
                    : PanelLayout.hitTestHidden(mx, my, Settings.WIDTH, Settings.HEIGHT, scale);
            if (InputHelper.justClickedLeft && reportHover) {
                InputHelper.justClickedLeft = false;
                sts1solver.diag.BugReport.export();
                return;
            }
            if (InputHelper.justClickedLeft && hover >= 0) {
                InputHelper.justClickedLeft = false;
                activate(shown ? PanelLayout.IDS[hover] : "hide");
            }
            if (!shown) {
                return;
            }
            dragGrip(scale);
            scrollTick(scale);
            if (InputHelper.justClickedLeft) {
                clickPill();
            }
            sts1solver.ui.Hud.handleClick();
        } catch (Throwable t) {
            if (failures++ == 0) {
                Log.error("panel update failed", t);
            }
        }
    }

    /**
     * Resizes the route strip while the player drags the grip in its bottom-right corner (B33).
     *
     * <p>The grip is drawn by {@link #drawPlanText}; this only consumes the click when the pointer
     * is on it, so a drag never also triggers a pill or a button.
     */
    private static void dragGrip(float scale) {
        try {
            if (!dragging && InputHelper.justClickedLeft && insideRect(handleRect)) {
                dragging = true;
                dragStartY = InputHelper.mY;
                dragStartFactor = RouteSize.factor();
                InputHelper.justClickedLeft = false;
                return;
            }
            if (!dragging) {
                return;
            }
            float dy = dragStartY - InputHelper.mY;
            float span = Math.max(80.0f, 220.0f * scale);
            RouteSize.preview(dragStartFactor * (1.0f + dy / span));
            if (!InputHelper.isMouseDown) {
                dragging = false;
                RouteSize.commit();
            }
        } catch (Throwable t) {
            dragging = false;
        }
    }

    private static boolean insideRect(float[] r) {
        if (r == null) {
            return false;
        }
        float mx = InputHelper.mX;
        float my = InputHelper.mY;
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    /** Gate hook: current strip size factor. */
    public static float stripScale() {
        return RouteSize.factor();
    }

    /** Gate hook: the drag grip rectangle {x, y, w, h}, or null before the first frame. */
    public static float[] gripRect() {
        float[] r = handleRect;
        return r == null ? null : new float[]{r[0], r[1], r[2], r[3]};
    }

    /** Gate hook: true while the grip is being dragged. */
    public static boolean draggingGrip() {
        return dragging;
    }

    /** Wheel scrolling over the route strip. Called once per frame. */
    private static void scrollTick(float scale) {
        try {
            float dy = 0.0f;
            if (InputHelper.scrolledUp) {
                dy -= rowUnit(scale);
                InputHelper.scrolledUp = false;
            }
            if (InputHelper.scrolledDown) {
                dy += rowUnit(scale);
                InputHelper.scrolledDown = false;
            }
            if (dy != 0.0f) {
                scrollBy(dy);
            }
        } catch (Throwable t) {
            // a missing wheel flag must never break the frame
        }
    }

    /** Scrolls the strip by {@code dy} game units; a manual scroll stops the auto-follow. */
    public static void scrollBy(float dy) {
        scrollPx = Math.max(0.0f, scrollPx + dy);
        followStep = false;
    }

    /** Keyboard scrolling (one turn row at a time, m20027). */
    public static void scrollRows(int rows) {
        scrollBy(rows * rowUnit(Settings.scale));
    }

    /** Height of one turn row plus the air under it, in game units (m20027). */
    public static float rowUnit(float scale) {
        return (RouteLayout.PILL_H + 2.0f * RouteLayout.CARD_PAD_Y + RouteLayout.TURN_GAP) * scale;
    }

    /** Jumps back to the row holding the current step and resumes following it. */
    public static void scrollToCurrent() {
        followStep = true;
    }

    public static float scrollOffset() {
        return scrollPx;
    }

    public static boolean following() {
        return followStep;
    }

    /** Number of clickable pills drawn last frame (gate hook). */
    public static int pillCount() {
        return pillBoxes.size();
    }

    /** Step of a drawn pill, or -1 (gate hook: what a click would execute). */
    public static int pillStep(int index) {
        if (index < 0 || index >= pillBoxes.size()) {
            return -1;
        }
        return (int) pillBoxes.get(index)[4];
    }

    private static void clickPill() {
        if (demoActive() || pillBoxes.isEmpty()) {
            return;
        }
        float mx = InputHelper.mX;
        float my = InputHelper.mY;
        for (int i = pillBoxes.size() - 1; i >= 0; i--) {
            float[] b = pillBoxes.get(i);
            if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
                int stepIndex = (int) b[4];
                if (stepIndex == -2) {
                    // the "back to the current step" band
                    scrollToCurrent();
                    InputHelper.justClickedLeft = false;
                } else if (sts1solver.Gameplay.requestStep(stepIndex)) {
                    InputHelper.justClickedLeft = false;
                }
                return;
            }
        }
    }

    private static boolean demoActive() {
        return System.currentTimeMillis() < Hud.demoUntil;
    }

    /** Runs one panel action. Public so a headless test can drive it. */
    public static void activate(String id) {
        if ("solve".equals(id)) {
            if (sts1solver.Gameplay.quickSearching()) {
                sts1solver.Gameplay.stopQuickSolve();
            } else {
                sts1solver.Gameplay.startQuickSolve();
            }
        } else if ("hide".equals(id)) {
            sts1solver.ui.Hud.toggle();
        } else if ("step".equals(id)) {
            sts1solver.Gameplay.executeStep();
        } else if ("auto".equals(id)) {
            sts1solver.Gameplay.toggleAutopilot();
        } else if ("collapse".equals(id)) {
            collapsed = !collapsed;
        } else if ("seconds".equals(id)) {
            Budget.cycleSeconds();
        } else if ("nodes".equals(id)) {
            Budget.cycleNodes();
        } else if ("pace".equals(id)) {
            AutoPace.cycle();
        } else if ("potion".equals(id)) {
            // P6-12 (user m04782): flip the search's potion policy and save it. The label carries the
            // live state so a measurement run cannot be mistaken for a normal one.
            sts1solver.search.Limits.togglePotions();
        } else if ("growth".equals(id)) {
            // B143 (user m10519): price or ignore out-of-combat gains (gold, max HP, permanent
            // damage, upgrades, potions). Saved in settings.txt; the label carries the live state.
            sts1solver.search.GrowthBook.toggleCounting();
        }
    }

    /** Draws the panel, then the plan text, then the cursor ring (last, so the pointer stays visible). */
    public static void render(SpriteBatch sb, java.util.List<String> planLines) {
        try {
            float scale = Settings.scale;
            boolean wasDrawing = sb.isDrawing();
            Color oldBatch = new Color(sb.getColor());
            BitmapFont font = FontHelper.topPanelInfoFont != null ? FontHelper.topPanelInfoFont
                    : FontHelper.buttonLabelFont;
            if (font == null) {
                return;
            }
            Color oldFont = font.getColor() == null ? Color.WHITE : new Color(font.getColor());
            if (!wasDrawing) {
                sb.begin();
            }
            try {
                float x = PanelLayout.x0(Settings.WIDTH, scale);
                float top = PanelLayout.y0(Settings.HEIGHT, scale);
                float w = PanelLayout.width(scale);
                if (collapsed) {
                    drawButton(sb, font, x, top - 28.0f * scale, 110.0f * scale, 28.0f * scale,
                            "▶ 求解器", false);
                } else {
                    float h = PanelLayout.panelHeight(scale);
                    fill(sb, x, top - h, w, h, 0.06f, 0.07f, 0.09f, 0.80f);
                    stroke(sb, x, top - h, w, h, 0.35f, 0.55f, 0.85f, 0.90f);
                    sb.setColor(Color.WHITE);
                    font.setColor(0.85f, 0.92f, 1.0f, 1.0f);
                    // Title and status live in the header band; the grid starts below it, so the
                    // two can never collide (reported: 求解器和面板上下太挤).
                    font.draw(sb, "尖塔求解器", x + 12.0f * scale, top - 18.0f * scale);
                    float[] rr = PanelLayout.reportRect(Settings.WIDTH, Settings.HEIGHT, scale);
                    drawButton(sb, font, rr[0], rr[1], rr[2], rr[3],
                            sts1solver.diag.BugReport.label(), reportHover);
                    // B32: the status is already the first plan line (lines()[0]); drawing it in the
                    // header band too was the duplicate text the player asked to remove.
                    for (int i = 0; i < PanelLayout.IDS.length; i++) {
                        float[] r = PanelLayout.rect(i, Settings.WIDTH, Settings.HEIGHT, scale);
                        drawButton(sb, font, r[0], r[1], r[2], r[3], labelFor(i), hover == i);
                    }
                }
                drawPlanText(sb, font, planLines, scale);
            } finally {
                font.setColor(oldFont);
                sb.setColor(oldBatch);
                if (!wasDrawing) {
                    sb.end();
                }
            }
            drawCursorRing(sb, wasDrawing);
        } catch (Throwable t) {
            if (failures++ == 0) {
                Log.error("panel render failed", t);
            }
        }
    }

    /**
     * The only thing drawn while the overlay is hidden (P2-10): a tab that brings it back.
     *
     * <p>Before this, hiding the panel was one-way for a mouse-only player -- the F11 hotkey was the
     * only way back. The tab is drawn and hit-tested at {@link PanelLayout#hiddenTabRect}.
     */
    public static void renderHidden(SpriteBatch sb) {
        try {
            float scale = Settings.scale;
            boolean wasDrawing = sb.isDrawing();
            Color oldBatch = new Color(sb.getColor());
            BitmapFont font = FontHelper.topPanelInfoFont != null ? FontHelper.topPanelInfoFont
                    : FontHelper.buttonLabelFont;
            if (font == null) {
                return;
            }
            Color oldFont = font.getColor() == null ? Color.WHITE : new Color(font.getColor());
            if (!wasDrawing) {
                sb.begin();
            }
            try {
                float[] r = PanelLayout.hiddenTabRect(Settings.WIDTH, Settings.HEIGHT, scale);
                drawButton(sb, font, r[0], r[1], r[2], r[3], "显示面板", hover >= 0);
            } finally {
                font.setColor(oldFont);
                sb.setColor(oldBatch);
                if (!wasDrawing) {
                    sb.end();
                }
            }
        } catch (Throwable t) {
            if (failures++ == 0) {
                Log.error("panel hidden-tab render failed", t);
            }
        }
    }

    private static String labelFor(int i) {
        if (PanelLayout.IDS[i].equals("solve") && sts1solver.Gameplay.quickSearching()) {
            return "计算中…（点=停）";
        }
        // "deep" is gone: calculate itself spends the whole budget (B43).
        if (PanelLayout.IDS[i].equals("hide")) {
            return sts1solver.ui.Hud.visible() ? "隐藏" : "显示";
        }
        if (PanelLayout.IDS[i].equals("auto")) {
            if (sts1solver.Gameplay.autopilot()) {
                return "自动: 开";
            }
            if (sts1solver.Gameplay.autoplayArmed()) {
                return "自动: 算完开始";
            }
        }
        if (PanelLayout.IDS[i].equals("seconds")) {
            // 1.0.8 (C6, user): the ladder is 自动(6s) 自动(12s) 自动(18s) 自动(30s) 自动(60s) 无限,
            // so the label is the budget itself -- no second wording can contradict the progress line.
            return "秒数 " + Budget.secondsLabel(Budget.seconds());
        }
        if (PanelLayout.IDS[i].equals("nodes")) {
            return "节点 " + (Budget.nodes() / 1000) + "k";
        }
        if (PanelLayout.IDS[i].equals("pace")) {
            return AutoPace.label();
        }
        if (PanelLayout.IDS[i].equals("potion")) {
            return sts1solver.search.Limits.potionsLabel();
        }
        if (PanelLayout.IDS[i].equals("growth")) {
            return sts1solver.search.GrowthBook.countingLabel();
        }
        return PanelLayout.LABELS[i];
    }

    private static void drawButton(SpriteBatch sb, BitmapFont font, float x, float y, float w, float h,
                                   String label, boolean hovered) {
        if (hovered) {
            fill(sb, x, y, w, h, 0.35f, 0.55f, 0.95f, 0.55f);
            stroke(sb, x, y, w, h, 0.75f, 0.88f, 1.0f, 1.0f);
        } else {
            fill(sb, x, y, w, h, 0.20f, 0.28f, 0.38f, 0.62f);
            stroke(sb, x, y, w, h, 0.40f, 0.55f, 0.70f, 0.75f);
        }
        font.setColor(hovered ? Color.WHITE : new Color(0.85f, 0.90f, 0.96f, 1.0f));
        drawLabel(sb, font, label, x, y, w, h);
    }

    /**
     * B144 (user m11047 item 4): a label wider than its button ran past it, and in the right column
     * past the screen edge -- "局外收益: 不计" lost its tail there ("行/不行看不见"). Measure the label
     * and scale it down to fit the button instead of drawing it off the edge.
     *
     * <p>Only the shrink path touches the font scale, and it restores 1.0 in a finally block, so other
     * panel text sharing FontHelper.topPanelInfoFont is unaffected.
     */
    private static void drawLabel(SpriteBatch sb, BitmapFont font, String label, float x, float y,
                                  float w, float h) {
        float pad = 10.0f * Settings.scale;
        float maxW = w - pad * 2.0f;
        float fit = 1.0f;
        if (label != null && !label.isEmpty() && maxW > 1.0f) {
            FIT.setText(font, label);
            if (FIT.width > maxW) {
                fit = maxW / FIT.width;
            }
        }
        if (fit < 1.0f) {
            font.getData().setScale(fit);
        }
        try {
            font.draw(sb, label, x + pad, y + h * 0.62f);
        } finally {
            if (fit < 1.0f) {
                font.getData().setScale(1.0f);
            }
        }
    }

    /**
     * Status text on top, then the route strip: one row per turn, one pill per action.
     *
     * <p>This is the STS2 layout (SolverRouteRow + SolverActionPill): a turn label followed by action
     * pills inside a bordered box, with the whole strip scrollable. The old version printed one text
     * line per action, capped at 16 rows and folded after the current turn, so the player could never
     * read the rest of the plan (reported as "cannot scroll to see the later steps").
     */
    private static void drawPlanText(SpriteBatch sb, BitmapFont font, java.util.List<String> lines,
                                     float scale) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        float pad = 20.0f * scale;
        float x = 22.0f * scale;
        float lineH = 26.0f * scale;
        float top = Settings.HEIGHT - 28.0f * scale;
        RouteLayout.Measure m = measure(font, scale);
        // B36: a strip is only drawn while a plan exists. Gameplay.lines() clears the list in its
        // no-plan branch, but the panel must not depend on that call having run -- the reported
        // "new fight still shows the previous fight\s suggestions" was exactly a live list whose
        // owner had stopped clearing it.
        // B117 (m01814): the search's held line is drawn as the same turn-row strip a finished plan
        // gets, so a running search looks like the panel it will become instead of one text row.
        boolean hasPlanNow = sts1solver.Gameplay.currentPlan() != null
                || sts1solver.Gameplay.previewActive();
        java.util.List<RouteRow> rows = demoActive() || !hasPlanNow
                ? new java.util.ArrayList<RouteRow>() : sts1solver.Gameplay.route();
        // B32: the box is never narrower than its longest line. Before this it was pinned at 720
        // while the mod-hook and budget lines ran past it to the screen edge.
        float maxLine = 0.0f;
        for (int i = 0; i < lines.size(); i++) {
            maxLine = Math.max(maxLine, m.width(lines.get(i)));
        }
        // B33: the route strip may not run underneath the button panel (that is how numbers and
        // labels ended up at (or past) the right edge of the screen), and its whole body scales with
        // the player-dragged grip factor.
        float strip = scale * RouteSize.factor();
        // m20027: the grip no longer scales the contents. The pills are laid out and measured at the
        // panel's own scale and the font stays at its normal size; the size of the strip only decides
        // how many turn rows are visible (PanelDetail.visibleRows) and how much of each turn fits.
        int detail = PanelDetail.level(RouteSize.factor());
        float textScale = FIXED_TEXT_SCALE;
        float baseFont = font.getData().scaleX;
        float available = Settings.WIDTH - x - 12.0f * scale;
        if (!collapsed) {
            float sideLimit = PanelLayout.x0(Settings.WIDTH, scale) - x - 14.0f * scale;
            if (sideLimit > 80.0f * scale) {
                available = Math.min(available, sideLimit);
            }
        }
        float panelW = TextWrap.boxWidth(720.0f * scale, maxLine, pad, available);
        panelW = Math.min(available, Math.max(panelW, Math.min(520.0f * strip, available)));
        float innerW = Math.max(120.0f * scale, panelW - 2.0f * pad);
        // Lines that still do not fit are wrapped instead of being cut off by the screen edge.
        // P2-27: both the wrapping and the pill layout are cached behind one signature, so an idle
        // panel costs a hash of its own text instead of a full text layout of the whole plan.
        long sig = layoutSignature(lines, rows, innerW, scale, scale, demoActive());
        if (sig != layoutSig || layoutPlaced == null) {
            // Measure at the scale the text is actually drawn with, otherwise wrapping is computed
            // for glyphs that are not the ones on screen (that mismatch is what made small panels
            // overflow their pills).
            setFontScale(font, textScale);
            try {
                layoutFitted = fittedLines(PanelDetail.headerLines(lines, detail), innerW,
                        measure(font, textScale));
                layoutPlaced = RouteLayout.layout(PanelDetail.rows(rows, detail), innerW, scale,
                        measure(font, textScale));
            } finally {
                setFontScale(font, baseFont);
            }
            layoutTotal = RouteLayout.totalHeight(layoutPlaced);
            layoutSig = sig;
            layoutBuilds++;
        } else {
            layoutHits++;
        }
        java.util.List<String> fitted = layoutFitted;
        java.util.List<RouteLayout.Placed> placed = layoutPlaced;
        float total = layoutTotal;
        float headerH = fitted.size() * lineH;
        float routeTop = top - headerH - 6.0f * scale;
        // The window shows a whole number of turns: one on the smallest panel, five on the largest.
        int shownRows = PanelDetail.visibleRows(RouteSize.factor());
        float maxViewH = Math.min(shownRows * rowUnit(scale) + 6.0f * scale,
                Math.min(MAX_ROUTE_H * scale, Math.max(0.0f, routeTop - 26.0f * scale)));
        float viewH = Math.min(total, maxViewH);
        int current = sts1solver.Gameplay.currentStep();
        if (followStep && current != lastFollowedStep && !placed.isEmpty()) {
            int r = RouteLayout.rowForCurrentStep(placed, current);
            if (r >= 0) {
                scrollPx = RouteLayout.clampScroll(placed.get(r).y - viewH * 0.35f, total, viewH);
            }
            lastFollowedStep = current;
        }
        scrollPx = RouteLayout.clampScroll(scrollPx, total, viewH);
        pillBoxes.clear();
        float viewBottom = routeTop - viewH;
        float boxTop = top + lineH * 0.5f;
        float boxBottom = viewBottom - 8.0f * scale;
        fill(sb, x, boxBottom, panelW, boxTop - boxBottom,
                0.0f, 0.0f, 0.0f, 0.62f);
        font.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        for (int i = 0; i < fitted.size(); i++) {
            font.draw(sb, fitted.get(i), x + pad, top - i * lineH);
        }
        setFontScale(font, textScale);
        try {
        for (int i = 0; i < placed.size(); i++) {
            RouteLayout.Placed p = placed.get(i);
            float rowTop = routeTop - (p.y - scrollPx);
            // B32: clip instead of skipping the whole card. A half-scrolled card used to be drawn
            // whole, so the first turn's frame stayed on screen above the strip (reported).
            float[] band = RouteLayout.clipBand(rowTop - p.height, p.height, viewBottom, routeTop);
            if (band == null) {
                continue;
            }
            // One card per turn, like STS2's SolverRouteRow: a rounded body, the turn label in its
            // own left column, the numbers right-aligned on the first pill line, pills after them.
            boolean currentTurn = rowHasStep(p, current);
            drawCard(sb, x + pad, band[0], innerW, band[1], currentTurn, scale);
            float baseline = rowTop - p.textBaseline;
            if (baseline <= routeTop - 3.0f * scale && baseline >= viewBottom + 5.0f * scale) {
                font.setColor(currentTurn ? 1.0f : 0.76f, currentTurn ? 0.94f : 0.84f,
                        currentTurn ? 0.72f : 0.96f, 1.0f);
                font.draw(sb, p.row.label, x + pad + p.labelX, baseline);
                if (p.statsW > 0.0f) {
                    font.setColor(0.72f, 0.78f, 0.86f, 1.0f);
                    font.draw(sb, p.row.stats, x + pad + p.statsRight - p.statsW, baseline);
                }
            }
            for (int j = 0; j < p.pills.size(); j++) {
                float[] pill = p.pills.get(j);
                float px = x + pad + pill[0];
                float py = routeTop - (pill[1] - scrollPx) - pill[3];
                if (py < viewBottom || py + pill[3] > routeTop) {
                    continue;
                }
                drawPill(sb, font, px, py, pill[2], pill[3], p.row.pills.get(j),
                        (int) pill[4], (int) pill[5], current, textScale);
                if (!demoActive()) {
                    pillBoxes.add(new float[]{px, py, pill[2], pill[3], pill[4]});
                }
            }
        }
        } finally {
            setFontScale(font, baseFont);
        }
        if (total > 0.0f) {
            drawScrollBar(sb, x + panelW - 13.0f * scale, routeTop, viewH, total, scrollPx, scale);
        }
        drawGrip(sb, x + innerW - 22.0f * scale, viewBottom + 4.0f * scale, 18.0f * scale, scale);
    }

    /**
     * The resize grip in the bottom-right corner of the strip (B33). Dragging it makes the turn
     * cards, pills and their text bigger or smaller; the factor survives a restart via settings.txt.
     */
    private static void drawGrip(SpriteBatch sb, float hx, float hy, float size, float scale) {
        float[] rect = new float[]{hx, hy, size, size};
        boolean hot = insideRect(rect) || dragging;
        fill(sb, hx, hy, size, size, hot ? 0.30f : 0.14f, hot ? 0.42f : 0.20f, hot ? 0.62f : 0.30f,
                0.92f);
        stroke(sb, hx, hy, size, size, 0.50f, 0.66f, 0.90f, 0.95f);
        for (int k = 0; k < 3; k++) {
            fill(sb, hx + size - (k + 1) * 4.0f * scale, hy + (k + 1) * 3.0f * scale,
                    3.0f * scale, 3.0f * scale, 0.78f, 0.88f, 1.0f, 1.0f);
        }
        handleRect = rect;
    }

    /** Caches the wrapped header lines: the same long mod list is re-measured every frame otherwise. */
    private static java.util.List<String> fittedLines(java.util.List<String> lines, float width,
                                                      RouteLayout.Measure m) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            key.append(lines.get(i)).append('\n');
        }
        key.append(width);
        String k = key.toString();
        java.util.List<String> cached = wrapCache.get(k);
        if (cached != null) {
            return cached;
        }
        java.util.List<String> fitted = TextWrap.wrap(lines, width, m);
        if (wrapCache.size() > 32) {
            wrapCache.clear();
        }
        wrapCache.put(k, fitted);
        return fitted;
    }


    /**
     * A 64-bit fingerprint of everything the route layout is built from: the header lines, every
     * turn row (label, numbers, pills, step numbers), and the geometry the caller resolved. Two
     * independent 32-bit mixes are folded into one long so a single collision cannot leave a stale
     * layout on screen.
     */
    private static long layoutSignature(java.util.List<String> lines, java.util.List<RouteRow> rows,
                                        float innerW, float strip, float scale, boolean demo) {
        int a = 17;
        int b = 486187739;
        a = a * 31 + Float.floatToIntBits(innerW);
        b = b * 131 + Float.floatToIntBits(innerW);
        a = a * 31 + Float.floatToIntBits(strip);
        b = b * 131 + Float.floatToIntBits(strip);
        a = a * 31 + Float.floatToIntBits(scale);
        b = b * 131 + Float.floatToIntBits(scale);
        a = a * 31 + (demo ? 1 : 0);
        b = b * 131 + (demo ? 1 : 0);
        a = a * 31 + lines.size();
        b = b * 131 + lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            int h = s == null ? 0 : s.hashCode();
            a = a * 31 + h;
            b = b * 131 + h;
        }
        a = a * 31 + rows.size();
        b = b * 131 + rows.size();
        for (int i = 0; i < rows.size(); i++) {
            RouteRow r = rows.get(i);
            a = a * 31 + r.label.hashCode();
            b = b * 131 + r.label.hashCode();
            a = a * 31 + r.stats.hashCode();
            b = b * 131 + r.stats.hashCode();
            a = a * 31 + r.pills.size();
            b = b * 131 + r.pills.size();
            for (int j = 0; j < r.pills.size(); j++) {
                int h = r.pills.get(j) == null ? 0 : r.pills.get(j).hashCode();
                int s = r.steps.get(j).intValue();
                a = a * 31 + h;
                a = a * 31 + s;
                b = b * 131 + h;
                b = b * 131 + s;
            }
        }
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    /**
     * Drops every text cache. Called when the search is torn down or the run changes: the caches are
     * bounded, but their contents belong to a plan that no longer exists, so they only cost memory.
     */
    public static void clearCaches() {
        widthCache.clear();
        wrapCache.clear();
        layoutPlaced = null;
        layoutFitted = null;
        layoutTotal = 0.0f;
        layoutSig = 1L;
    }

    public static String layoutStats() {
        long n = layoutBuilds + layoutHits;
        long pct = n == 0L ? 0L : (layoutHits * 100L) / n;
        return "builds=" + layoutBuilds + " hits=" + layoutHits + " reuse=" + pct + "%"
                + " lines=" + (layoutFitted == null ? 0 : layoutFitted.size())
                + " rows=" + (layoutPlaced == null ? 0 : layoutPlaced.size());
    }

    public static void resetLayoutStats() {
        layoutBuilds = 0L;
        layoutHits = 0L;
    }

    public static long layoutBuilds() {
        return layoutBuilds;
    }

    public static long layoutHits() {
        return layoutHits;
    }

    /** True when this card owns the pill of the current step (the card is highlighted). */
    private static boolean rowHasStep(RouteLayout.Placed p, int current) {
        if (p == null || current < 0) {
            return false;
        }
        for (int j = 0; j < p.pills.size(); j++) {
            if ((int) p.pills.get(j)[4] == current) {
                return true;
            }
        }
        return false;
    }

    /**
     * The body of one turn card (STS2 draws every turn as its own panel row with a left accent).
     * Keeping the card behind the text also makes a wrapped turn read as one block instead of
     * loose pills, which was the other half of "每个回合的步骤和第几回合挡住了".
     */
    private static void drawCard(SpriteBatch sb, float x, float y, float w, float h,
                                 boolean current, float scale) {
        if (current) {
            fill(sb, x, y, w, h, 0.12f, 0.19f, 0.30f, 0.88f);
            stroke(sb, x, y, w, h, 0.96f, 0.80f, 0.30f, 0.90f);
        } else {
            fill(sb, x, y, w, h, 0.05f, 0.07f, 0.11f, 0.62f);
            stroke(sb, x, y, w, h, 0.33f, 0.40f, 0.50f, 0.50f);
        }
        if (ImageMaster.WHITE_SQUARE_IMG == null) {
            return;
        }
        sb.setColor(current ? new Color(0.98f, 0.82f, 0.30f, 0.95f)
                : new Color(0.42f, 0.56f, 0.80f, 0.75f));
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y + 4.0f * scale, 3.0f * scale,
                Math.max(1.0f, h - 8.0f * scale));
    }

    /**
     * A scrollbar on the right edge of the strip (B30). The strip was already scrollable with the
     * wheel and the 上滚/下滚 buttons, but nothing on screen said how much was below the fold.
     */
    private static void drawScrollBar(SpriteBatch sb, float x, float routeTop, float viewH,
                                      float total, float scrollPx, float scale) {
        float w = 6.0f * scale;
        fill(sb, x, routeTop - viewH, w, viewH, 0.10f, 0.12f, 0.16f, 0.85f);
        if (total <= viewH + 0.5f) {
            return;
        }
        float thumbH = Math.max(24.0f * scale, viewH * viewH / total);
        float max = total - viewH;
        float t = max <= 0.0f ? 0.0f : scrollPx / max;
        float thumbY = routeTop - thumbH - t * (viewH - thumbH);
        fill(sb, x, thumbY, w, thumbH, 0.55f, 0.70f, 0.90f, 0.95f);
    }

    /** One action pill: fill, border, a 3px colour bar and the label (STS2's SolverActionPill). */
    private static void drawPill(SpriteBatch sb, BitmapFont font, float x, float y, float w, float h,
                                 String label, int step, int textLines, int current, float scale) {
        boolean done = step < current;
        boolean isCurrent = step == current;
        boolean hovered = mouseInside(x, y, w, h);
        if (isCurrent) {
            fill(sb, x, y, w, h, 0.24f, 0.44f, 0.72f, 0.92f);
            stroke(sb, x, y, w, h, 0.88f, 0.94f, 1.0f, 1.0f);
        } else if (done) {
            fill(sb, x, y, w, h, 0.13f, 0.15f, 0.19f, 0.88f);
            stroke(sb, x, y, w, h, 0.30f, 0.35f, 0.42f, 0.92f);
        } else {
            fill(sb, x, y, w, h, hovered ? 0.26f : 0.18f, hovered ? 0.36f : 0.25f,
                    hovered ? 0.52f : 0.35f, 0.90f);
            stroke(sb, x, y, w, h, 0.44f, 0.58f, 0.76f, 0.95f);
        }
        float bar = 3.0f * scale;
        sb.setColor(isCurrent ? new Color(0.98f, 0.82f, 0.30f, 1.0f)
                : new Color(0.45f, 0.62f, 0.85f, 0.95f));
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y + 3.0f * scale, bar, Math.max(1.0f, h - 6.0f * scale));
        font.setColor(done ? new Color(0.62f, 0.66f, 0.72f, 1.0f) : Color.WHITE);
        if (textLines <= 1) {
            font.draw(sb, label, x + 10.0f * scale, y + h * 0.67f);
            return;
        }
        // B33: a wrapped pill draws one text line per row; measuring with the same font that draws
        // is what keeps the last line inside the pill instead of past the screen edge.
        float lineH = RouteLayout.PILL_LINE_H * scale;
        float padY = RouteLayout.PILL_PAD_Y * scale;
        java.util.List<String> pieces = wrappedPill(label, w - RouteLayout.PILL_TEXT_PAD_X * scale,
                measure(font, scale));
        for (int i = 0; i < pieces.size() && i < textLines; i++) {
            font.draw(sb, pieces.get(i), x + 10.0f * scale, y + h - padY - i * lineH);
        }
    }

    /** Wrapped pill text, cached per label and width (the strip repaints every frame). */
    private static java.util.List<String> wrappedPill(String label, float width,
                                                      RouteLayout.Measure m) {
        String key = "pill|" + label + "|" + width;
        java.util.List<String> cached = wrapCache.get(key);
        if (cached != null) {
            return cached;
        }
        java.util.List<String> pieces = TextWrap.wrapLine(label, width, m);
        if (wrapCache.size() > 64) {
            wrapCache.clear();
        }
        wrapCache.put(key, pieces);
        return pieces;
    }

    private static boolean mouseInside(float x, float y, float w, float h) {
        try {
            float mx = InputHelper.mX;
            float my = InputHelper.mY;
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sets both axes of the shared font's data scale (P2-39). */
    private static void setFontScale(BitmapFont font, float scale) {
        font.getData().setScale(scale);
    }

    /** The detail level the strip is drawn at right now (P2-39). */
    public static int detailLevel() {
        return PanelDetail.level(RouteSize.factor());
    }

    /** Text measurement with a cache: laying out CJK glyphs every frame is not free. */
    private static RouteLayout.Measure measure(final BitmapFont font, final float scale) {
        return new RouteLayout.Measure() {
            @Override
            public float width(String text) {
                String key = text + "|" + scale;
                Float cached = widthCache.get(key);
                if (cached != null) {
                    return cached;
                }
                float w;
                try {
                    w = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text).width;
                } catch (Throwable t) {
                    w = text.length() * 9.0f * scale;
                }
                if (widthCache.size() > 4000) {
                    // A long session reads a new plan, and a new mouse-over, every few seconds;
                    // without a ceiling this map is the one thing that only ever grows.
                    widthCache.clear();
                }
                widthCache.put(key, w);
                return w;
            }
        };
    }

    /**
     * A ring at the mouse position, drawn after everything.
     *
     * <p>This overlay renders after the game, so it can cover the game's own cursor. The ring makes
     * the pointer visible over the panel without pretending a post-render hook can draw behind the UI.
     */
    private static void drawCursorRing(SpriteBatch sb, boolean wasDrawing) {
        if (ImageMaster.WHITE_SQUARE_IMG == null) {
            return;
        }
        boolean started = false;
        Color old = new Color(sb.getColor());
        try {
            if (!sb.isDrawing()) {
                sb.begin();
                started = true;
            }
            float scale = Settings.scale;
            float size = 3.0f * scale;
            float gap = 9.0f * scale;
            sb.setColor(1.0f, 0.95f, 0.35f, 0.95f);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2.0 * i / 8.0;
                float px = InputHelper.mX + (float) Math.cos(a) * gap;
                float py = InputHelper.mY + (float) Math.sin(a) * gap;
                sb.draw(ImageMaster.WHITE_SQUARE_IMG, px - size * 0.5f, py - size * 0.5f, size, size);
            }
        } catch (Throwable ignored) {
            // a missing ring must never break the frame
        } finally {
            sb.setColor(old);
            if (started) {
                sb.end();
            }
        }
    }

    private static void fill(SpriteBatch sb, float x, float y, float w, float h,
                             float r, float g, float b, float a) {
        if (ImageMaster.WHITE_SQUARE_IMG == null) {
            return;
        }
        sb.setColor(r, g, b, a);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, w, h);
    }

    private static void stroke(SpriteBatch sb, float x, float y, float w, float h,
                               float r, float g, float b, float a) {
        if (ImageMaster.WHITE_SQUARE_IMG == null) {
            return;
        }
        float th = 1.5f * Settings.scale;
        sb.setColor(r, g, b, a);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, w, th);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y + h - th, w, th);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, th, h);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x + w - th, y, th, h);
    }
}