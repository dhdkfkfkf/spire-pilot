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
 * <p>Everything the player does lives on the panel: 计算 / 自动 开/关 / 半自动-全自动 / 秒数 / 节点 /
 * 节奏 / 药水 / 局外收益 / 收起 / 隐藏. Hotkeys still work, but they are no longer the only way in, and the
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
    /**
     * The plan-text gate {@link #render} is given, so that "is there a plan to draw" stays a
     * question the caller can answer without building the answer.
     *
     * <p>1.1.0 (user m04872 "p1有必要"): this used to be the real text. {@code Gameplay} rebuilt the
     * whole status list and pushed it here from 76 call sites, one of them every frame in
     * {@code tickQuick} -- and then {@code render} only asked whether the list was empty. The list
     * was built, copied and thrown away several times a second.
     *
     * <p>m06071: the strip says the full status again, so the words come back -- but they are
     * <em>pulled</em> by {@code drawPlanText} from {@link sts1solver.Gameplay#lines()} once per
     * drawn frame, not pushed from 76 places. {@code Gameplay} stays out of the panel's business,
     * and an idle frame with nothing to draw costs nothing.
     */
    private static final java.util.List<String> PLAN_GATE =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList("plan"));
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
     * The body glyph scale: the size the route strip and the buttons are measured and drawn at.
     *
     * <p>m20027: dragging the grip still changes how many turns are visible and how much of each
     * turn fits, never this number -- shrinking the letters made a small panel unreadable instead of
     * compact.
     *
     * <p>1.1.0 (user m01052 "字号可以改"): the *panel* is no longer limited to one size. It has
     * fixed tiers, so the header, the numbers and the labels are three different sizes instead of
     * one. These are constants, not a function of the grip, so the panel still looks the same at
     * every grip factor.
     *
     * <p>Every tier is at or below 1.0 on purpose -- see {@link #TITLE_SCALE} for why scaling the
     * game's bitmap font up is not allowed.
     */
    public static final float FIXED_TEXT_SCALE = 1.0f;
    /**
     * 1.00x -- the panel title.
     *
     * <p>1.1.0 (user m02752 "面板字体模糊"): no tier may exceed 1.0. {@code FontHelper} builds every
     * font once, at a fixed pixel size, into a texture atlas; drawing it above 1.0 makes the GPU
     * interpolate that atlas upwards, which is exactly what "模糊" was. Only shrinking keeps glyphs
     * sharp, so the title now reads as a title through colour and position, not through size.
     */
    public static final float TITLE_SCALE = 1.0f;
    /**
     * The order the pixel field lights its cells in (m19340).
     *
     * <p>A fixed permutation of 0..99, not a per-frame shuffle: the same progress must light the
     * same cells every time, or a screenshot of the panel could not be explained and two players at
     * the same percentage would see different pictures. It was generated once by shuffling the
     * identity with a seeded RNG and pasting the result; the shape of the table is the contract, the
     * seed is not.
     *
     * <p>Why shuffle at all: filling a square left-to-right, top-to-bottom reads as a progress bar
     * that happens to be square. Lighting cells in scattered order reads as a block of pixels coming
     * alive, which is what the player asked for.
     */
    private static final int[] PIXEL_ORDER = {
            73, 12, 88, 41, 5, 66, 29, 94, 50, 17,
            82, 34, 59, 3, 97, 46, 21, 71, 8, 63,
            38, 90, 25, 55, 14, 79, 44, 1, 68, 31,
            86, 52, 19, 75, 10, 61, 96, 36, 48, 23,
            92, 57, 6, 70, 27, 83, 40, 15, 65, 98,
            33, 77, 2, 54, 89, 20, 47, 72, 11, 60,
            95, 42, 18, 80, 30, 56, 4, 67, 91, 24,
            49, 13, 74, 37, 99, 58, 26, 85, 7, 45,
            69, 32, 93, 16, 62, 0, 51, 87, 39, 22,
            76, 9, 64, 28, 53, 84, 43, 35, 81, 78};
    /** 1.00x -- the one number a row is about (percentage). Never above 1.0; see above. */
    public static final float NUM_SCALE = 1.0f;
    /** 0.86x -- labels and units ("已用", "剩"). */
    public static final float AUX_SCALE = 0.86f;
    /** 0.78x -- the chip captions. */
    public static final float MICRO_SCALE = 0.78f;
    /** The largest tier any draw path may ask for; the gate pins this at 1.0. */
    public static final float MAX_TEXT_SCALE = 1.0f;

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

    /** The non-empty list {@link #render} is handed when a plan exists; see {@link #PLAN_GATE}. */
    public static java.util.List<String> planGate() {
        return PLAN_GATE;
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
            // m05752: nothing is drawn outside a fight, so nothing may be clickable either. Without
            // this the panel's hit boxes stayed live on the map -- an invisible button that still
            // fires is worse than a visible one that covers the screen.
            if (!sts1solver.ui.Hud.isDemo() && !sts1solver.ui.PhaseGate.fighting()) {
                hover = -1;
                reportHover = false;
                return;
            }
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
            // 1.1.0 (user m06672): in 全自动 the primary band is a banner, not a button -- there is no
            // solve for the player to start or stop, the mode does it. Pressing it returns to 半自动,
            // which is the only thing that band can usefully mean there.
            if (sts1solver.Gameplay.fullAuto()) {
                sts1solver.Gameplay.cycleAutoMode();
            } else if (sts1solver.Gameplay.quickSearching()) {
                sts1solver.Gameplay.stopQuickSolve();
            } else {
                sts1solver.Gameplay.startQuickSolve();
            }
        } else if ("hide".equals(id)) {
            sts1solver.ui.Hud.toggle();
        } else if ("step".equals(id)) {
            // The right slot is always the 半自动/全自动 switch. Putting 执行一步 on the same button
            // made 全自动 display a step button it has no use for and made the mode unknown at a
            // glance; the two jobs now have one home each.
            sts1solver.Gameplay.cycleAutoMode();
        } else if ("auto".equals(id)) {
            // The left slot is 自动 开/关 in both modes: it plays the whole plan, and in 全自动 the
            // line is re-solved as it runs. The old one-step button was just the first click of
            // this, and clicking a route pill already walks the plan that far by hand.
            sts1solver.Gameplay.toggleAutopilot();
        } else if ("collapse".equals(id)) {
            collapsed = !collapsed;
        } else if ("seconds".equals(id)) {
            // 1.1.0 (user m06672): the same slot carries the turn budget while 全自动 is on.
            if (sts1solver.Gameplay.fullAuto()) {
                sts1solver.search.Limits.cycleHorizonTurns();
            } else {
                Budget.cycleSeconds();
            }
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
                // m05226 (P1b): one snapshot per frame. The five drawing helpers used to call
                // Gameplay.status() themselves, so a single frame built the status object five
                // times and re-formatted the pass description and the dropped-branch text each
                // time -- neither of which the panel ever displayed.
                sts1solver.Gameplay.Status s = sts1solver.Gameplay.status();
                if (collapsed) {
                    drawCollapsedTab(sb, font, scale, x, top, s);
                } else {
                    drawBody(sb, font, scale, x, top, s);
                }
                drawPlanText(sb, font, planLines, scale, s);
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

    private static void drawButton(SpriteBatch sb, BitmapFont font, float x, float y, float w, float h,
                                   String label, boolean hovered) {
        drawButton(sb, font, x, y, w, h, label, hovered, false);
    }

    /**
     * A secondary button. {@code on} marks a toggle that is currently engaged (自动出牌): it gets the
     * accent tint, so the player can tell "off" from "on" without reading the label.
     */
    private static void drawButton(SpriteBatch sb, BitmapFont font, float x, float y, float w, float h,
                                   String label, boolean hovered, boolean on) {
        if (hovered) {
            fill(sb, x, y, w, h, 0.243f, 0.431f, 0.667f, 0.80f);
            stroke(sb, x, y, w, h, 0.75f, 0.88f, 1.0f, 1.0f);
        } else if (on) {
            fill(sb, x, y, w, h, 0.161f, 0.361f, 0.643f, 0.72f);
            stroke(sb, x, y, w, h, 0.431f, 0.639f, 0.878f, 0.86f);
        } else {
            fill(sb, x, y, w, h, 0.149f, 0.212f, 0.290f, 0.72f);
            stroke(sb, x, y, w, h, 0.431f, 0.588f, 0.769f, 0.42f);
        }
        float pad = 8.0f * Settings.scale;
        float maxW = w - pad * 2.0f;
        float tier = FIXED_TEXT_SCALE;
        float tw = textWidth(font, label, tier);
        float fit = tw > maxW && tw > 0.0f ? maxW / tw : 1.0f;
        text(sb, font, label, x + Math.max(pad, (w - tw * fit) * 0.5f),
                lineTop(y, h, tier * fit, Settings.scale),
                tier * fit, hovered || on ? 1.0f : 0.863f, hovered || on ? 1.0f : 0.910f,
                hovered || on ? 1.0f : 0.965f, 1.0f);
    }

    /**
     * B144 (user m11047 item 4): a label wider than its button ran past it, and in the right column
     * past the screen edge -- "局外收益: 不计" lost its tail there ("行/不行看不见"). Measure the label
     * and scale it down to fit the button instead of drawing it off the edge.
     *
     * <p>Only the shrink path touches the font scale, and it restores whatever the scale was on
     * entry in a finally block, so other panel text sharing FontHelper.topPanelInfoFont is
     * unaffected.
     */
    private static void drawLabel(SpriteBatch sb, BitmapFont font, String label, float x, float y,
                                  float w, float h) {
        float pad = 10.0f * Settings.scale;
        float maxW = w - pad * 2.0f;
        float base = font.getData().scaleX;
        float fit = 1.0f;
        if (label != null && !label.isEmpty() && maxW > 1.0f) {
            FIT.setText(font, label);
            if (FIT.width > maxW) {
                fit = maxW / FIT.width;
            }
        }
        if (fit < 1.0f) {
            font.getData().setScale(base * fit);
        }
        try {
            font.draw(sb, label, x + pad, lineTop(y, h, fit, Settings.scale));
        } finally {
            if (fit < 1.0f) {
                font.getData().setScale(base);
            }
        }
    }

    // ---------------------------------------------------------------- 1.1.0 panel body

    /**
     * Draws one string at a fixed tier and colour, restoring the shared font's scale afterwards.
     *
     * <p>The font is the game's, shared with other overlays, so every caller that changes its size
     * has to put it back. Going through one helper is what keeps that honest.
     */
    private static void text(SpriteBatch sb, BitmapFont font, String s, float x, float baseline,
                             float tier, float r, float g, float b, float a) {
        if (s == null || s.isEmpty()) {
            return;
        }
        float base = font.getData().scaleX;
        font.getData().setScale(tier);
        font.setColor(r, g, b, a);
        font.draw(sb, s, x, baseline);
        font.getData().setScale(base);
    }

    /**
     * The top edge of one line of text centred inside a band.
     *
     * <p>1.1.0 (user m03103 "Y 轴太挤了，都压一块了"): every band used to place its text at
     * {@code y + h * 0.62}, a fraction of the <em>band</em> rather than of the <em>line</em>. libGDX
     * draws a string from its top edge downward for one full line box, so in a 16-30 unit band that
     * 26 unit box started two thirds of the way down and then ran 10-20 units past the band's bottom
     * edge -- straight through the band below it. Centring the line box instead makes every band
     * independent of the font's actual height.
     */
    private static float lineTop(float bandBottom, float bandHeight, float tier, float scale) {
        float box = PanelLayout.LINE_H * tier * scale;
        return bandBottom + (bandHeight + box) * 0.5f;
    }

    /**
     * Tops a smaller tier so its optical centre matches {@code anchorTier}'s.
     *
     * <p>Two strings that share a "baseline" y are not optically aligned when their tiers differ:
     * with top-edge semantics the smaller one floats high. This drops it by half the difference of
     * the two line boxes, which is what keeps "已用 7.4s" from looking like a superscript.
     */
    private static float alignTo(float top, float tier, float anchorTier, float scale) {
        return top - PanelLayout.LINE_H * (anchorTier - tier) * scale * 0.5f;
    }

    /** Width of a string at a tier, through the shared measurement cache. */
    private static float textWidth(BitmapFont font, String s, float tier) {
        return s == null || s.isEmpty() ? 0.0f : measure(font, tier).width(s);
    }

    /** Same, but never wider than {@code max}, shrinking the glyphs to fit (B144's rule). */
    private static void textFit(SpriteBatch sb, BitmapFont font, String s, float x, float baseline,
                                float tier, float max, float r, float g, float b, float a) {
        if (s == null || s.isEmpty()) {
            return;
        }
        float w = textWidth(font, s, tier);
        float fit = w > max && w > 0.0f ? max / w : 1.0f;
        text(sb, font, s, x, baseline, tier * fit, r, g, b, a);
    }

    /** Right-aligned string at a tier. */
    private static void textRight(SpriteBatch sb, BitmapFont font, String s, float right,
                                  float baseline, float tier, float max,
                                  float r, float g, float b, float a) {
        if (s == null || s.isEmpty()) {
            return;
        }
        float w = Math.min(textWidth(font, s, tier), max);
        textFit(sb, font, s, right - w, baseline, tier, max, r, g, b, a);
    }

    /** The whole expanded panel: header, status strip, actions, params, foot. */
    private static void drawBody(SpriteBatch sb, BitmapFont font, float scale, float x, float top,
                                 sts1solver.Gameplay.Status s) {
        float w = PanelLayout.width(scale);
        float h = PanelLayout.panelHeight(scale);
        fill(sb, x, top - h, w, h, 0.067f, 0.094f, 0.137f, 0.94f);
        stroke(sb, x, top - h, w, h, 0.376f, 0.573f, 0.800f, 0.48f);
        sb.setColor(Color.WHITE);
        drawHeader(sb, font, scale, x, top, s);
        drawStatus(sb, font, scale, s);
        drawActions(sb, font, scale);
        drawParams(sb, font, scale);
        drawFoot(sb, font, scale);
    }

    /** Title band: the name, the live state, and the report button at the right end. */
    private static void drawHeader(SpriteBatch sb, BitmapFont font, float scale, float x, float top,
                                   sts1solver.Gameplay.Status s) {
        float pad = PanelLayout.pad(scale);
        float band = PanelLayout.headerHeight(scale);
        float baseline = lineTop(top - band, band, TITLE_SCALE, scale);
        // The accent bar carries the title's weight instead of a bigger font: the game's fonts are
        // bitmap atlases, and stretching one upward is what made the old header look blurry.
        float barW = 2.5f * scale;
        fill(sb, x + pad, baseline - 9.0f * scale, barW, 13.0f * scale, 0.310f, 0.639f, 1.0f, 0.95f);
        float titleX = x + pad + barW + 6.0f * scale;
        text(sb, font, "尖塔求解器", titleX, baseline, TITLE_SCALE, 0.918f, 0.949f, 0.988f, 1.0f);
        float after = titleX + textWidth(font, "尖塔求解器", TITLE_SCALE) + 8.0f * scale;
        float[] rr = PanelLayout.reportRect(Settings.WIDTH, Settings.HEIGHT, scale);
        float dotX = after;
        float dotY = baseline - 5.0f * scale;
        float[] tone = stateTone(s);
        fill(sb, dotX, dotY, 5.0f * scale, 5.0f * scale, tone[0], tone[1], tone[2], 0.95f);
        text(sb, font, stateWord(s), dotX + 9.0f * scale,
                alignTo(baseline, MICRO_SCALE, TITLE_SCALE, scale), MICRO_SCALE,
                tone[0], tone[1], tone[2], 0.95f);
        drawReportIcon(sb, font, rr, scale);
    }

    /**
     * The report button is an icon, not a word: the header is a title bar now, and an 84 px label
     * there read as a third row of buttons. Drawn from three rectangles so it needs no asset.
     */
    private static void drawReportIcon(SpriteBatch sb, BitmapFont font, float[] r, float scale) {
        if (reportHover) {
            fill(sb, r[0], r[1], r[2], r[3], 0.243f, 0.431f, 0.667f, 0.80f);
            stroke(sb, r[0], r[1], r[2], r[3], 0.75f, 0.88f, 1.0f, 1.0f);
        } else {
            fill(sb, r[0], r[1], r[2], r[3], 0.149f, 0.212f, 0.290f, 0.72f);
            stroke(sb, r[0], r[1], r[2], r[3], 0.431f, 0.588f, 0.769f, 0.42f);
        }
        float cx = r[0] + r[2] * 0.5f;
        float midY = r[1] + r[3] * 0.54f;
        float unit = scale;
        float col = reportHover ? 1.0f : 0.72f;
        // stem
        fill(sb, cx - 1.0f * unit, midY + 1.0f * unit, 2.0f * unit, 5.0f * unit,
                col, col, col, 0.95f);
        // arrow head, two steps so it reads as a triangle at this size
        fill(sb, cx - 4.0f * unit, midY + 2.0f * unit, 8.0f * unit, 2.0f * unit,
                col, col, col, 0.95f);
        fill(sb, cx - 2.5f * unit, midY + 0.0f * unit, 5.0f * unit, 2.0f * unit,
                col, col, col, 0.95f);
        // tray
        fill(sb, cx - 5.0f * unit, midY - 4.0f * unit, 10.0f * unit, 1.5f * unit,
                col, col, col, 0.95f);
    }

    /** A word for the search's state, from the same numbers the strip prints. */
    private static String stateWord(sts1solver.Gameplay.Status s) {
        if (s.paused) {
            return "已暂停";
        }
        if (s.stalled()) {
            return "无进展";
        }
        if (s.searching) {
            return "计算中";
        }
        return s.hasPlan ? "已就绪" : "空闲";
    }

    /** RGB for the state dot and word: blue while searching, green when a line is held, grey idle. */
    private static float[] stateTone(sts1solver.Gameplay.Status s) {
        if (s.paused || s.stalled()) {
            return new float[]{1.0f, 0.761f, 0.302f};
        }
        if (s.searching) {
            return new float[]{0.310f, 0.639f, 1.0f};
        }
        if (s.hasPlan) {
            return new float[]{0.373f, 0.827f, 0.604f};
        }
        return new float[]{0.502f, 0.596f, 0.694f};
    }

    /**
     * The status strip: state + percentage + promise, a progress bar with the three downgrade marks
     * 1.0.1 actually uses (25 / 50 / 75 %), then two short rows of numbers.
     *
     * <p>It replaces five or six sentences from {@code Gameplay.lines()} -- 130-156 px of paragraph
     * the player had to read -- with the same facts at 84 px. The sentences still exist for the log
     * and the bug report; only the panel reads the numbers instead.
     */
    private static void drawStatus(SpriteBatch sb, BitmapFont font, float scale,
                                   sts1solver.Gameplay.Status s) {
        float[] r = PanelLayout.statusRect(Settings.WIDTH, Settings.HEIGHT, scale);
        float left = r[0];
        float right = r[0] + r[2];
        float top = r[1] + r[3];
        fill(sb, left, r[1], r[2], r[3], 0.031f, 0.043f, 0.067f, 0.62f);
        stroke(sb, left, r[1], r[2], r[3], 0.376f, 0.573f, 0.800f, 0.22f);

        // The bands are stacked from the strip's top edge with a 4 unit inner pad each. m19272: the
        // stack is now 4 + 26 + 4 + 26 + 4 + 14 bar + 4 = STATUS_H (82), the bar owning the bottom
        // band. Each baseline comes from lineTop, so a band can never overflow into the one below it
        // (user m03103).
        float bandH = PanelLayout.LINE_H * scale;

        // row 1: state word, the one number, then the promise pushed to the right edge
        float r1Top = top - 4.0f * scale;
        float r1 = lineTop(r1Top - bandH, bandH, AUX_SCALE, scale);
        text(sb, font, stateWord(s), left + 8.0f * scale, r1, AUX_SCALE, 0.863f, 0.910f, 0.965f, 1.0f);
        float afterWord = left + 8.0f * scale + textWidth(font, stateWord(s), AUX_SCALE) + 8.0f * scale;
        String pct = s.hasPct() ? (s.pct + "%") : "";
        if (!pct.isEmpty()) {
            text(sb, font, pct, afterWord, alignTo(r1, NUM_SCALE, AUX_SCALE, scale), NUM_SCALE,
                    0.310f, 0.639f, 1.0f, 1.0f);
            afterWord += textWidth(font, pct, NUM_SCALE) + 8.0f * scale;
        }
        // 1.1.0 (user m04872 "y轴太长了"): row 3 is gone. It printed the running pass's phase --
        // "窄深搜 beam=10 layers=3" -- which no decision ever turned on. The one thing in it that
        // did matter, "this search has stopped making progress", moves up here: when the search is
        // stalled the promise slot shows the warning instead, in amber. Same fact, no extra band.
        // m19272 (user "右边交互面板把世界线的两个部分删了"): the warning used to name the thing it
        // was counting ("没有新世界线"). The count itself is gone from this block, so naming it here
        // would point at a number the player can no longer see. It now says the fact that matters --
        // the search has stopped moving -- in the same words the state word uses.
        String promise = s.stalled()
                ? "⚠ 已有 " + (s.stalledMs / 1000L) + "s 没有进展"
                : promiseText(s);
        if (!promise.isEmpty()) {
            float room = right - 8.0f * scale - afterWord;
            if (room > 20.0f * scale) {
                boolean warn = s.stalled();
                textRight(sb, font, promise, right - 8.0f * scale, r1, AUX_SCALE, room,
                        warn ? 1.0f : 0.561f, warn ? 0.761f : 0.651f, warn ? 0.302f : 0.745f, 1.0f);
            }
        }

        // m19272 (user "然后把这里之前的进度条还回来，不过要做成像素网格填充"): the bar is back,
        // as a grid of pixels rather than one solid rectangle. m06251 removed it on the argument
        // that the numbers below already said where the search stood; the player wants the visual
        // again, so it gets its own 14 unit band under row 2 (PanelLayout.STATUS_BAR_H) and the
        // numbers stay exactly where they were.
        drawGridBar(sb, s, scale);

        // row 2: elapsed / budget, remaining, best line. m19272: the worldline counter left this
        // row -- the player reads progress from the bar above it, and the number duplicated the
        // bar's own meaning. The pair is gone rather than emptied, so 最优 keeps the same gap.
        float r2Top = r1Top - bandH - 4.0f * scale;
        float r2 = lineTop(r2Top - bandH, bandH, AUX_SCALE, scale);
        float cursor = left + 8.0f * scale;
        float gap = 12.0f * scale;
        cursor = pair(sb, font, cursor, r2, scale, "已用", elapsedText(s), gap);
        cursor = pair(sb, font, cursor, r2, scale, "剩", leftText(s), gap);
        cursor = pair(sb, font, cursor, r2, scale, "最优", bestText(s), gap);
    }

    /**
     * The expanded panel's progress readout, drawn as an N x N field of square pixels (m19272,
     * m19340).
     *
     * <p>Why a field and not a bar: the player asked for "像素网格填充" and then spelled out the
     * shape -- a square cut into N x N cells, lit by progress. A left-to-right fill of a square
     * reads exactly like the progress bar it replaces, so the ORDER is shuffled: cell k is lit by
     * its rank in a fixed pseudo-random permutation, not by its position. Progress then looks like
     * a block of pixels coming alive instead of a stripe growing across the panel.
     *
     * <p>Three cues, all cheap:
     * <ul>
     *   <li><b>Shuffled order</b> -- {@link #PIXEL_ORDER} is one fixed permutation, so the same
     *       progress always lights the same cells (a screenshot stays explainable).</li>
     *   <li><b>Breathing</b> -- every lit cell's alpha rides a slow sine, so a stalled search still
     *       looks alive without claiming progress it does not have.</li>
     *   <li><b>Leading flash</b> -- the cell that just lit is drawn brighter for a beat, which is
     *       what makes "the search moved" visible at a glance.</li>
     * </ul>
     *
     * <p>Cost: {@code N*N} draws of one shared white texture -- the same call {@link #fill} makes
     * for every other rectangle on the panel. No allocation, no text measurement, nothing that
     * depends on the plan. The permutation is a static table, not a per-frame shuffle.
     *
     * <p>Unlimited budgets have no percentage ({@code hasPct()} is false): the field then shows a
     * full track with no lit cells, because a percentage of "no limit" would be a lie. A finished
     * search ({@code !searching}) lights every cell.
     */
    private static void drawGridBar(SpriteBatch sb, sts1solver.Gameplay.Status s, float scale) {
        float[] b = PanelLayout.statusBarRect(Settings.WIDTH, Settings.HEIGHT, scale);
        if (b[2] <= 0.0f || b[3] <= 0.0f) {
            return;
        }
        int n = PanelLayout.statusBarCells();
        float gap = PanelLayout.statusBarCellGap() * scale;
        float edge = PanelLayout.statusBarCellEdge() * scale;
        if (edge <= 0.0f || n <= 0) {
            return;
        }
        int total = n * n;
        int lit = s.hasPct()
                ? Math.round(Math.max(0, Math.min(100, s.pct)) * total / 100.0f)
                : (s.searching ? 0 : total);
        // One clock read per frame; the two waves are independent so breathing and flashing never
        // land in lockstep (which would read as a single pulsing block).
        long now = System.currentTimeMillis();
        float breathe = 0.78f + 0.22f * (float) Math.sin(now / 620.0);
        float flash = 0.55f + 0.45f * (float) Math.abs(Math.cos(now / 180.0));
        float inset = Math.max(0.5f * scale, edge * 0.14f);
        // Track: every cell's socket, so the empty part still reads as a grid.
        for (int i = 0; i < total; i++) {
            float[] xy = pixelCell(b, n, edge, gap, i);
            fill(sb, xy[0], xy[1], edge, edge, 0.471f, 0.627f, 0.784f, 0.16f);
        }
        // Fill: lit cells in shuffled order, breathing; the newest one flashes.
        for (int rank = 0; rank < lit && rank < total; rank++) {
            float[] xy = pixelCell(b, n, edge, gap, PIXEL_ORDER[rank % total]);
            boolean leading = rank >= lit - 2;
            float a = leading ? flash : breathe * 0.92f;
            fill(sb, xy[0] + inset, xy[1] + inset, edge - 2.0f * inset, edge - 2.0f * inset,
                    leading ? 0.560f : 0.310f, leading ? 0.820f : 0.639f, 1.0f, a);
        }
    }

    /** Where cell {@code index} sits inside the field's rect {@code b}. */
    private static float[] pixelCell(float[] b, int n, float edge, float gap, int index) {
        int col = index % n;
        int row = index / n;
        // Row 0 is the TOP row: the field grows from the top-left of its rect downward, which is the
        // direction the rest of the panel reads in.
        return new float[]{b[0] + col * (edge + gap),
                b[1] + b[3] - edge - row * (edge + gap)};
    }

    /** "label value" at two tiers, returns the x the next pair starts at. */
    private static float pair(SpriteBatch sb, BitmapFont font, float x, float baseline, float scale,
                              String label, String value, float gap) {
        if (label != null && !label.isEmpty()) {
            text(sb, font, label, x, alignTo(baseline, MICRO_SCALE, AUX_SCALE, scale), MICRO_SCALE,
                    0.392f, 0.475f, 0.561f, 1.0f);
            x += textWidth(font, label, MICRO_SCALE) + 3.0f * scale;
        }
        if (value != null && !value.isEmpty()) {
            text(sb, font, value, x, baseline, AUX_SCALE, 0.863f, 0.910f, 0.965f, 1.0f);
            x += textWidth(font, value, AUX_SCALE);
        }
        return x + gap;
    }

    /** The promise in force, and how far the search has walked it. */
    /**
     * The promise slot on row 1: the turn budget, in as few characters as it can be said.
     *
     * <p>m19340 (user "右边只需要有各种数字表示硬核就可以了"): this slot used to print a sentence --
     * "推到战斗结束", "3 回合最优（已降档）", " · 走完 4". The player's point was that the RIGHT panel
     * is the instrument cluster: numbers belong here, the prose belongs on the left strip, which
     * has room for it. So the slot now reads like a gauge: {@code 结束}, {@code 3回合}, or
     * {@code 3/5回合} when the clock has narrowed the promise, with a trailing {@code ·4} for the
     * turns actually finished.
     */
    private static String promiseText(sts1solver.Gameplay.Status s) {
        if (!s.searching && !s.hasPlan) {
            return "";
        }
        StringBuilder b = new StringBuilder(8);
        if (s.horizonLimit <= 0) {
            b.append("结束");
        } else if (s.horizonTurns > 0 && s.horizonTurns < s.horizonLimit) {
            b.append(s.horizonTurns).append('/').append(s.horizonLimit).append("回合");
        } else {
            b.append(s.horizonLimit).append("回合");
        }
        if (s.turnsFinished > 0) {
            b.append(" ·").append(s.turnsFinished);
        }
        return b.toString();
    }

    private static String elapsedText(sts1solver.Gameplay.Status s) {
        if (!s.searching) {
            return "";
        }
        String used = (s.elapsedMs / 100L) / 10.0f + "s";
        return s.budgetMs <= 0L ? used + "（不限）" : used + "/" + (s.budgetMs / 1000L) + "s";
    }

    private static String leftText(sts1solver.Gameplay.Status s) {
        if (!s.searching || s.budgetMs <= 0L) {
            return "";
        }
        return (s.leftMs / 100L) / 10.0f + "s";
    }

    private static String bestText(sts1solver.Gameplay.Status s) {
        if (s.searching) {
            return s.bestSteps > 0 ? s.bestSteps + " 步" : "";
        }
        return s.planSteps > 0 ? s.planSteps + " 步" : "";
    }

    /** The primary action, the two-up row, then the params band. */
    private static void drawActions(SpriteBatch sb, BitmapFont font, float scale) {
        boolean searching = sts1solver.Gameplay.quickSearching();
        float[] pr = PanelLayout.primaryRect(Settings.WIDTH, Settings.HEIGHT, scale);
        if (sts1solver.Gameplay.fullAuto()) {
            // 1.1.0 (user m06672): 全自动 hides 计算 -- there is nothing for the player to start, the
            // mode keeps solving by itself until the fight ends. The band stays as a muted banner
            // instead of a hole (the layout is pinned by the gates), and pressing it is the way back
            // to 半自动. 自动出牌 stays the stop, because the user asked for exactly one obvious exit.
            fill(sb, pr[0], pr[1], pr[2], pr[3], 0.106f, 0.157f, 0.220f, 0.86f);
            stroke(sb, pr[0], pr[1], pr[2], pr[3], 0.310f, 0.443f, 0.573f, 0.62f);
            String banner = "全自动 · 打到结束";
            float bw = textWidth(font, banner, FIXED_TEXT_SCALE);
            text(sb, font, banner, pr[0] + (pr[2] - bw) * 0.5f,
                    lineTop(pr[1], pr[3], FIXED_TEXT_SCALE, scale),
                    FIXED_TEXT_SCALE, 0.706f, 0.784f, 0.863f, 1.0f);
        } else {
            boolean hot = hover == PanelLayout.indexOf("solve");
            float[] base = searching ? new float[]{0.549f, 0.176f, 0.176f} : new float[]{0.161f, 0.361f, 0.643f};
            if (hot) {
                fill(sb, pr[0], pr[1], pr[2], pr[3], base[0] + 0.14f, base[1] + 0.14f, base[2] + 0.14f, 0.94f);
                stroke(sb, pr[0] - 1.0f * scale, pr[1] - 1.0f * scale, pr[2] + 2.0f * scale,
                        pr[3] + 2.0f * scale, 0.75f, 0.88f, 1.0f, 0.95f);
            } else {
                fill(sb, pr[0], pr[1], pr[2], pr[3], base[0], base[1], base[2], 0.86f);
                stroke(sb, pr[0], pr[1], pr[2], pr[3], 0.58f, 0.74f, 0.94f, 0.62f);
            }
            String label = searching ? "停止计算" : "计算本场";
            float tw = textWidth(font, label, FIXED_TEXT_SCALE);
            text(sb, font, label, pr[0] + (pr[2] - tw) * 0.5f,
                    lineTop(pr[1], pr[3], FIXED_TEXT_SCALE, scale),
                    FIXED_TEXT_SCALE, 1.0f, 1.0f, 1.0f, 1.0f);
        }
        for (int i = 1; i <= 2; i++) {
            float[] r = PanelLayout.row2Rect(i, Settings.WIDTH, Settings.HEIGHT, scale);
            boolean on = i == 2 && sts1solver.Gameplay.autopilot();
            drawButton(sb, font, r[0], r[1], r[2], r[3], actionLabel(i), hover == i, on);
        }
    }

    /**
     * The five chips. A chip reads "秒数 12s": caption dim, value bright.
     *
     * <p>1.1.0 (user m04872): the "参数" caption that used to sit above this row is gone -- see
     * {@code PanelLayout.CHIPS_OFF}. The chips carry their own captions, so a band whose only job
     * was to say "these are parameters" was 30 units of vertical space for one word.
     */
    private static void drawParams(SpriteBatch sb, BitmapFont font, float scale) {
        for (int i = 0; i < PanelLayout.IDS.length; i++) {
            if (!PanelLayout.isChip(i)) {
                continue;
            }
            float[] r = PanelLayout.chipRect(i - 3, Settings.WIDTH, Settings.HEIGHT, scale);
            boolean hot = hover == i;
            fill(sb, r[0], r[1], r[2], r[3], hot ? 0.243f : 0.149f, hot ? 0.361f : 0.212f,
                    hot ? 0.549f : 0.290f, hot ? 0.80f : 0.66f);
            stroke(sb, r[0], r[1], r[2], r[3], hot ? 0.75f : 0.431f, hot ? 0.88f : 0.588f,
                    hot ? 1.0f : 0.769f, hot ? 1.0f : 0.42f);
            float baseline = lineTop(r[1], r[3], AUX_SCALE, scale);
            String caption = chipCaption(i);
            float cw = textWidth(font, caption, MICRO_SCALE);
            String value = chipValue(i);
            float vw = textWidth(font, value, AUX_SCALE);
            float total = cw + (value.isEmpty() ? 0.0f : 4.0f * scale + vw);
            float cx = r[0] + Math.max(6.0f * scale, (r[2] - total) * 0.5f);
            text(sb, font, caption, cx, alignTo(baseline, MICRO_SCALE, AUX_SCALE, scale), MICRO_SCALE,
                    0.502f, 0.596f, 0.694f, 1.0f);
            text(sb, font, value, cx + cw + 4.0f * scale, baseline, AUX_SCALE,
                    0.863f, 0.910f, 0.965f, 1.0f);
        }
    }

    /**
     * Foot band: 收起 and 隐藏, as two real buttons.
     *
     * <p>m02372: this band used to print the live strip scale ("1.00×") to the left of two bare
     * text links. The scale is already visible in the thing it describes -- the strip the player
     * just dragged -- so the readout was a label for a control that carries its own feedback, and
     * it sat where the eye goes looking for an action. The two links became buttons of the same
     * size and paint as the action rows: they shrink and hide the whole overlay, which is not a
     * caption-sized job.
     */
    private static void drawFoot(SpriteBatch sb, BitmapFont font, float scale) {
        float[] collapse = PanelLayout.footRect(false, Settings.WIDTH, Settings.HEIGHT, scale);
        drawButton(sb, font, collapse[0], collapse[1], collapse[2], collapse[3], "收起",
                hover == PanelLayout.indexOf("collapse"));
        float[] hide = PanelLayout.footRect(true, Settings.WIDTH, Settings.HEIGHT, scale);
        drawButton(sb, font, hide[0], hide[1], hide[2], hide[3],
                sts1solver.ui.Hud.visible() ? "隐藏" : "显示",
                hover == PanelLayout.indexOf("hide"));
    }

    /**
     * The collapsed panel: a title strip, the progress bar, and the two action buttons.
     *
     * <p>1.1.0 (user m06251). The old tab was a single pill that said "▶ ●求解器 62%" and expanded
     * on any click. That made the two buttons a player reaches for mid-fight -- 计算 and 自动出牌 --
     * cost an expand click first, and it hid the bar in the one state where there are no numbers to
     * read. Now the bar lives here, the two buttons stay drawn and stay clickable, and the tab strip
     * is the only part that expands.
     */
    private static void drawCollapsedTab(SpriteBatch sb, BitmapFont font, float scale, float x,
                                         float top, sts1solver.Gameplay.Status s) {
        float[] r = PanelLayout.collapsedRect(Settings.WIDTH, Settings.HEIGHT, scale);
        float[] tab = PanelLayout.collapsedTabRect(Settings.WIDTH, Settings.HEIGHT, scale);
        boolean hot = hover == PanelLayout.indexOf("collapse");
        fill(sb, r[0], r[1], r[2], r[3], 0.067f, 0.094f, 0.137f, 0.94f);
        stroke(sb, r[0], r[1], r[2], r[3], 0.376f, 0.573f, 0.800f, 0.48f);

        // the tab strip: the only part that expands
        if (hot) {
            fill(sb, tab[0], tab[1], tab[2], tab[3], 0.243f, 0.431f, 0.667f, 0.55f);
        }
        float baseline = lineTop(tab[1], tab[3], AUX_SCALE, scale);
        text(sb, font, "▶", r[0] + 8.0f * scale, alignTo(baseline, MICRO_SCALE, AUX_SCALE, scale),
                MICRO_SCALE, 0.612f, 0.694f, 0.784f, 1.0f);
        float[] tone = stateTone(s);
        float dotX = r[0] + 8.0f * scale + textWidth(font, "▶", MICRO_SCALE) + 5.0f * scale;
        fill(sb, dotX, baseline - 9.0f * scale, 5.0f * scale, 5.0f * scale, tone[0], tone[1], tone[2], 0.95f);
        float nameX = dotX + 9.0f * scale;
        text(sb, font, "求解器", nameX, baseline, AUX_SCALE, 0.863f, 0.910f, 0.965f, 1.0f);
        float valueX = nameX + textWidth(font, "求解器", AUX_SCALE) + 6.0f * scale;
        String value = collapsedValue(s);
        textFit(sb, font, value, valueX, baseline, AUX_SCALE,
                r[0] + r[2] - 8.0f * scale - valueX, tone[0], tone[1], tone[2], 1.0f);

        drawCollapsedBar(sb, s, scale);

        // the two buttons that survive collapse
        String solveLabel = sts1solver.Gameplay.fullAuto() ? "全自动"
                : (sts1solver.Gameplay.quickSearching() ? "停止" : "计算");
        drawCollapsedAction(sb, font, scale, 0, solveLabel,
                hover == PanelLayout.indexOf("solve"), sts1solver.Gameplay.quickSearching());
        drawCollapsedAction(sb, font, scale, 1, actionLabel(2),
                hover == PanelLayout.indexOf("auto"), sts1solver.Gameplay.autopilot());
    }

    /**
     * The progress bar, drawn only on the collapsed panel (1.1.0, user m06251). Collapsed there are
     * no numbers on screen, so this bar is the one thing that says the search is still moving. The
     * three marks are the 25/50/75% downgrade points of 1.0.1, so the player can see the rung drop.
     */
    private static void drawCollapsedBar(SpriteBatch sb, sts1solver.Gameplay.Status s, float scale) {
        float[] b = PanelLayout.collapsedBarRect(Settings.WIDTH, Settings.HEIGHT, scale);
        fill(sb, b[0], b[1], b[2], b[3], 0.471f, 0.627f, 0.784f, 0.16f);
        int fillPct = s.hasPct() ? Math.max(0, Math.min(100, s.pct)) : (s.searching ? 0 : 100);
        if (fillPct > 0) {
            fill(sb, b[0], b[1], b[2] * fillPct / 100.0f, b[3], 0.310f, 0.639f, 1.0f, 0.92f);
        }
        float[] marks = {0.25f, 0.50f, 0.75f};
        for (int i = 0; i < marks.length; i++) {
            fill(sb, b[0] + b[2] * marks[i], b[1], Math.max(1.0f, 1.0f * scale), b[3],
                    0.027f, 0.035f, 0.051f, 0.90f);
        }
    }

    /** One of the two action buttons drawn on the collapsed panel. */
    private static void drawCollapsedAction(SpriteBatch sb, BitmapFont font, float scale, int slot,
                                            String label, boolean hot, boolean on) {
        float[] r = PanelLayout.collapsedActionRect(slot, Settings.WIDTH, Settings.HEIGHT, scale);
        float br = slot == 0 ? (sts1solver.Gameplay.quickSearching() ? 0.549f : 0.161f) : 0.161f;
        float bg = slot == 0 ? (sts1solver.Gameplay.quickSearching() ? 0.176f : 0.361f) : 0.361f;
        float bb = slot == 0 ? (sts1solver.Gameplay.quickSearching() ? 0.176f : 0.643f) : 0.643f;
        float lift = hot ? 0.14f : 0.0f;
        fill(sb, r[0], r[1], r[2], r[3], br + lift, bg + lift, bb + lift,
                on ? 0.94f : (hot ? 0.94f : 0.86f));
        stroke(sb, r[0], r[1], r[2], r[3],
                hot ? 0.75f : 0.58f, hot ? 0.88f : 0.74f, hot ? 1.0f : 0.94f,
                hot ? 0.95f : 0.62f);
        float tw = textWidth(font, label, FIXED_TEXT_SCALE);
        text(sb, font, label, r[0] + (r[2] - tw) * 0.5f,
                lineTop(r[1], r[3], FIXED_TEXT_SCALE, scale), FIXED_TEXT_SCALE, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** What the collapsed tab's figure says: a percentage while searching, else the held line. */
    private static String collapsedValue(sts1solver.Gameplay.Status s) {
        if (s.searching) {
            return s.hasPct() ? s.pct + "%" : (s.worldlines + " 条");
        }
        if (s.planSteps > 0) {
            return s.planSteps + " 步";
        }
        return "空闲";
    }

    /** Chip caption, e.g. "秒数". */
    private static String chipCaption(int i) {
        String id = PanelLayout.IDS[i];
        if ("seconds".equals(id)) {
            // 1.1.0 (user m06672): 全自动 sets a turn budget, not a time budget -- "可以追求 1 回合、2
            // 回合、3 回合、无限回合内的预算最优解". The slot is the same, the setting is not, so the
            // chip becomes 回合 there and stays 秒数 in 半自动.
            return sts1solver.Gameplay.fullAuto() ? "回合" : "秒数";
        }
        if ("nodes".equals(id)) {
            return "节点";
        }
        if ("pace".equals(id)) {
            return "节奏";
        }
        if ("potion".equals(id)) {
            return "药水";
        }
        return "收益";
    }

    /** Chip value, e.g. "12s". The long state words stay in the log, not in a 122 px chip. */
    private static String chipValue(int i) {
        String id = PanelLayout.IDS[i];
        if ("seconds".equals(id)) {
            if (sts1solver.Gameplay.fullAuto()) {
                // "1" / "2" / "3" / "走完": a short horizon asks for the best play inside those turns,
                // it is not a promise to win, so the value says the number and nothing more.
                return sts1solver.search.Limits.horizonLabel();
            }
            int sec = Budget.seconds();
            return sec < 0 ? "自动" : (sec == 0 ? "无限" : sec + "s");
        }
        if ("nodes".equals(id)) {
            int n = Budget.nodes();
            // m03534: the top profile is 1,000,000 nodes, and "/1000 + k" printed it as "1000k" -- four
            // characters that read as a different number at a glance. Anything at or past a million is
            // said in M.
            if (n <= 0) {
                return "不限";
            }
            if (n >= 1000000) {
                return (n / 1000000) + "M";
            }
            return (n / 1000) + "k";
        }
        if ("pace".equals(id)) {
            int ms = AutoPace.delayMs();
            return ms <= 0 ? "瞬时" : ms + "ms";
        }
        if ("potion".equals(id)) {
            return sts1solver.search.Limits.potionsOffered() ? "允许" : "禁用";
        }
        return sts1solver.search.GrowthBook.counting() ? "计" : "不计";
    }

    /** Label for the two buttons in the two-up row (1 = mode switch, 2 = 自动 开/关). */
    private static String actionLabel(int i) {
        if (i == 1) {
            // The mode switch keeps its label in both modes, running or idle.
            return sts1solver.Gameplay.fullAuto() ? "全自动 ✓" : "半自动 ✓";
        }
        if (sts1solver.Gameplay.autopilot()) {
            return "自动 开";
        }
        return sts1solver.Gameplay.autoplayArmed() ? "自动 待启动" : "自动 关";
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
                                     float scale, sts1solver.Gameplay.Status s) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        // m04872: the strip is back on the left, in its own column. Hanging it under the panel
        // (m03871) put it on top of the play area the panel had just cleared, so collapsing the
        // panel no longer uncovered anything. It keeps no background of its own -- the text sits
        // straight on the game's own art, the way a caption would -- and it starts below the game's
        // top bar so it never covers the potions or the relics.
        // m07347 (user): 收起 and 隐藏 are two different things. 收起 only folds the button panel;
        // the status text and the action list on the left are the whole point of leaving the solver
        // running, so they stay. 隐藏 is the switch that makes everything go away. Collapsing used
        // to return here, which meant the panel's own 收起 button silently blanked the plan.
        float pad = 20.0f * scale;
        float x = 22.0f * scale;
        // m06071: the lines were touching -- a 26-unit glyph box stacked on a 26-unit line height
        // leaves no leading at all, and a paragraph of them reads as one grey block. The strip now
        // carries the full status text again, so it needs the breathing room.
        float lineH = (PanelLayout.LINE_H + 6.0f) * scale;
        float top = Settings.HEIGHT - PanelLayout.topInset(scale);
        // m06071: the strip says what the solver sees again -- the plan, the expected damage, the
        // intent, the progress bar, the worldline count -- instead of the two-line summary m03871
        // put here. That summary was written for a strip that only had to say "idle, press compute";
        // the player's answer was that the numbers they were watching had disappeared.
        //
        // The pull happens here, once per drawn frame, and P1 still holds: Gameplay no longer
        // pushes this list from 76 places (tickQuick included) for a panel that only asked whether
        // it was empty. lines() keeps feeding solver.log and the exported report unchanged.
        java.util.List<String> text = sts1solver.Gameplay.lines();
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
        // m06071: the header rows are the solver's own report again, filtered by how much room the
        // strip has. PanelDetail.headerLines keeps the lines that say what the solver is doing and
        // drops the bookkeeping nobody reads at a glance, which is what this did before m03871.
        java.util.List<String> intro = PanelDetail.headerLines(text, detail);
        // m19280 (user's "稳定主状态 + 轮换副文案" design): one extra header row that says what the
        // search is doing in plain words -- 阶段 · 节点 · 深度 · 副文案 -- and rotates only its last
        // phrase, on a 1.5s clock (StatusTicker). It is appended AFTER the filter, so a narrow strip
        // still gets it: this is the line that makes a long search readable, and PanelDetail would
        // have dropped it (it matches none of the IMPORTANT keywords).
        //
        // Cost: the ticker returns a cached string while its key is unchanged, so an idle frame adds
        // one list copy and no formatting. When the phrase does rotate the layout signature changes
        // and the strip re-lays-out once -- every 1.5s, not every frame.
        if (s != null && (s.searching || s.hasPlan)) {
            String tick = sts1solver.ui.StatusTicker.line(s);
            if (tick != null && !tick.isEmpty()) {
                intro = new java.util.ArrayList<String>(intro);
                intro.add(tick);
            }
        }
        // m04872: the strip is sized for its own column again, not for the panel's. It may not run
        // under the button panel -- that is how labels used to end up past the right edge of the
        // screen -- so the space it gets is whatever is left of the panel's left edge.
        RouteLayout.Measure m = measure(font, textScale);
        float maxLine = 0.0f;
        for (int i = 0; i < intro.size(); i++) {
            maxLine = Math.max(maxLine, m.width(intro.get(i)));
        }
        float available = Settings.WIDTH - x - 12.0f * scale;
        // m03585: the limit applies whether or not the panel is collapsed. The collapsed tab is
        // the same width at the same x as the open panel (PanelLayout.width/x0), so skipping the
        // limit while collapsed let the header run straight over its title bar -- the strip was
        // told to keep clear of a panel that had not moved. Collapsing changes what the panel
        // draws, not where it sits, so the space the strip may use is the same either way.
        float sideLimit = PanelLayout.x0(Settings.WIDTH, scale) - x - 14.0f * scale;
        if (sideLimit > 80.0f * scale) {
            available = Math.min(available, sideLimit);
        }
        float panelW = TextWrap.boxWidth(720.0f * scale, maxLine, pad, available);
        panelW = Math.min(available, Math.max(panelW, Math.min(520.0f * strip, available)));
        float innerW = Math.max(120.0f * scale, panelW - 2.0f * pad);
        // Lines that still do not fit are wrapped instead of being cut off by the screen edge.
        // P2-27: both the wrapping and the pill layout are cached behind one signature, so an idle
        // panel costs a hash of its own text instead of a full text layout of the whole plan.
        // m03871: the second argument is the strip factor, and it has to be the strip factor. It was
        // being passed `scale` twice, so the cached layout ignored the grip entirely -- dragging the
        // grip could leave a layout sized for the previous factor on screen until something else
        // changed. `strip` already folds in the panel scale, so it covers both.
        long sig = layoutSignature(intro, rows, innerW, strip, scale, demoActive());
        if (sig != layoutSig || layoutPlaced == null) {
            // Measure at the scale the text is actually drawn with, otherwise wrapping is computed
            // for glyphs that are not the ones on screen (that mismatch is what made small panels
            // overflow their pills).
            setFontScale(font, textScale);
            try {
                layoutFitted = fittedLines(intro, innerW,
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
        // m03871 (Q2=B): no box, no card bodies, no frames. The strip is text on the game's own
        // background now -- only the pills keep a body, because they are the click targets and a
        // player has to see where to press. The current turn is marked with a short vertical bar in
        // the gutter plus a brighter label, which reads at a glance without drawing a box around it.
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
            boolean currentTurn = rowHasStep(p, current);
            if (currentTurn) {
                fill(sb, x + 3.0f * scale, band[0], 3.0f * scale, band[1],
                        0.98f, 0.82f, 0.30f, 0.95f);
            }
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
                    // GlyphLayout measures at the font's *current* data scale, not at the tier
                    // asked for. Set it first, or a width measured for 1.0 gets cached under the
                    // 0.78 key and every shrunk label is laid out at the wrong size.
                    float base = font.getData().scaleX;
                    font.getData().setScale(scale);
                    try {
                        w = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text).width;
                    } finally {
                        font.getData().setScale(base);
                    }
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