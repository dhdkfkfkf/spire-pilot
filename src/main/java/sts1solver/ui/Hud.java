package sts1solver.ui;

import basemod.BaseMod;
import basemod.interfaces.PostRenderSubscriber;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The plan overlay: a translucent strip of text at the top-left.
 *
 * <p>Coordinates are in the GAME's virtual space ({@code Settings.WIDTH/HEIGHT}), never in window
 * pixels. The first live build used {@code Gdx.graphics.getHeight()} and drew the panel off the top
 * of the screen: the solve worked, the log was correct, and nothing was visible. That is the whole
 * "panel never appeared" bug.
 *
 * <p>Three other details are not cosmetic:
 *
 * <ul>
 *   <li>The batch may or may not already be drawing when this is called, so the begin/end pair is
 *       conditional on {@code sb.isDrawing()}. Calling {@code begin()} on an active batch throws and
 *       would kill the game's frame.</li>
 *   <li>Every failure is LOGGED once (with the exception). Swallowing it is what made the previous
 *       invisible panel indistinguishable from "no plan".</li>
 *   <li>The game's CJK font is used, with its colour saved and restored: card names are localised
 *       and a shared font's colour must not be left tinted.</li>
 * </ul>
 */
public final class Hud implements PostRenderSubscriber {

    private static volatile boolean enabled = true;
    private static volatile boolean demo;
    private static int failures;
    private static String lastFailure;

    /** The action to point at: hand index, monster-group index, or the End Turn button. */
    private static volatile int markCard = -1;
    private static volatile int markTarget = -1;
    private static volatile boolean markEndTurn;

    /** The marked card's identity (uuid), "" when unknown. */
    private static volatile String markCardUuid = "";

    /**
     * B48 (m14700/m14739): true while the solver owns the frame -- a search is running, or a planned
     * action is being pushed into the game.
     *
     * <p>Drawing the marker layer then is wrong twice over. {@code Gameplay.tick()} returns early for
     * the whole duration of a sliced search, so {@link #markStep} still holds the mark of the card
     * that was played BEFORE the search began: the arrow points at a card that is already in the
     * discard pile. And the marker is a "click this next" hint, which nobody needs while the mod is
     * clicking for the player.
     */
    private static volatile boolean busy;

    /** Set once the marker layer blew its frame budget; it stays off for this session (B48). */
    private static volatile boolean markersOff;

    /** Hard ceiling on the dots of the card -> monster line (B48). */
    public static final int MAX_DOTS = 128;

    /** A marker line longer than this is corrupt data, not a line (B48). */
    private static final float MAX_MARK_LEN = 20000.0f;
    private static final float MIN_BOX = 1.0f;
    private static final float MAX_BOX = 20000.0f;

    /** One marker frame above this is a bug worth logging and switching off (B48). */
    private static final long MARKER_BUDGET_US = 8000L;

    private static volatile long lastMarkerMicros;
    private static volatile String lastMarkerGeometry = "";
    private static volatile int cappedLines;

    /**
     * m21115-2: the boxes are an autopilot aid, not part of the plan view. A plan computed with the
     * button (F8 / 计算) used to paint the card it wanted on the board even though nobody was going
     * to play it; the player asked for the markers only while 自动出牌 is running.
     */
    private static volatile boolean autopilotMarkers = true;

    /** Called by the solver when the plan or the current step changes. */
    public static void markStep(int cardIndex, int targetIndex, boolean endTurn) {
        markStep("", cardIndex, targetIndex, endTurn);
    }

    /**
     * Points at a CARD by identity, with the index only as a fallback.
     *
     * <p>The index is a position, and positions drift: the hand is rebuilt whenever a card is drawn
     * or discarded, so a plan made a moment ago can point at the neighbour of the intended card --
     * which is exactly the "the highlight is one card to the right" report. The uuid survives
     * cloning and never changes, so that is what the marker (and the executor) trusts.
     */
    public static void markStep(String cardUuid, int cardIndex, int targetIndex, boolean endTurn) {
        markCardUuid = cardUuid == null ? "" : cardUuid;
        markCard = cardIndex;
        markTarget = targetIndex;
        markEndTurn = endTurn;
    }

    private static boolean hasMark() {
        return markTarget >= 0 || markEndTurn || (markCardUuid != null && !markCardUuid.isEmpty())
                || markCard >= 0;
    }

    /** B48: the solver owns the frame; the marker layer stays off until it gives it back. */
    public static void setBusy(boolean on) {
        busy = on;
    }

    public static boolean isBusy() {
        return busy;
    }

    /** m21115-2: only 自动出牌 gets the boxes. */
    public static void setAutopilotMarkers(boolean on) {
        autopilotMarkers = on;
    }

    public static boolean autopilotMarkers() {
        return autopilotMarkers;
    }

    /** Would the marker layer draw this frame? Everything a gate needs to see, in one place. */
    public static boolean markersAllowed() {
        return enabled && !busy && !markersOff && autopilotMarkers && hasMark();
    }

    /** Harness hook: the marker gate as text. */
    public static String markerGate() {
        return "enabled=" + enabled + " busy=" + busy + " off=" + markersOff + " auto=" + autopilotMarkers
                + " mark=" + hasMark() + " draw=" + (markersAllowed() ? "yes" : "no");
    }

    public static long lastMarkerMicros() {
        return lastMarkerMicros;
    }

    public static String lastMarkerGeometry() {
        return lastMarkerGeometry;
    }

    public static int cappedLines() {
        return cappedLines;
    }

    /**
     * Harness hook: how many dots a line of {@code len} game-units gets, or -1 when it must not be
     * drawn at all. Pure arithmetic, so the bound is testable without a live hand.
     */
    public static int lineDots(float len, float scale) {
        if (!finite(len) || len < 1.0f || len > MAX_MARK_LEN || !finite(scale)
                || scale < 0.05f || scale > 20.0f) {
            return -1;
        }
        int dots = (int) (len / (12.0f * scale));
        if (dots < 4) {
            return 4;
        }
        return Math.min(dots, MAX_DOTS);
    }

    /** Harness hook: is this hitbox safe to draw? A box that was never laid out is not. */
    public static boolean boxOk(float x, float y, float w, float h) {
        if (!finite(x) || !finite(y) || !finite(w) || !finite(h)) {
            return false;
        }
        if (w < MIN_BOX || h < MIN_BOX || w > MAX_BOX || h > MAX_BOX) {
            return false;
        }
        return Math.abs(x) < MAX_BOX && Math.abs(y) < MAX_BOX;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** Harness hook: exercise the marker drawing path without a real frame. */
    public static void drawMarkersNow(SpriteBatch sb) {
        drawMarkers(sb);
    }

    /**
     * Harness hook: does the overlay resolve a (hand index, monster index) pair?
     *
     * <p>The live marker check cannot assert "target resolves" unconditionally: the first planned
     * action is often a self-buff (InFlame) or End Turn, which legitimately has no target. This
     * resolves a specific pair instead, so the test asks the question that has a definite answer.
     */
    public static String markerSnapshotFor(int cardIndex, int targetIndex) {
        return "card=" + (hitboxOfCard("", cardIndex) == null ? "none" : "ok")
                + " target=" + (hitboxOfMonster(targetIndex) == null ? "none" : "ok");
    }

    /** Harness hook: resolve a card marker by identity (the P0 path for the drift bug). */
    public static String markerSnapshotForUuid(String uuid) {
        return "card=" + (hitboxOfCard(uuid, -1) == null ? "none" : "ok")
                + " marked=" + markCardUuid + "/" + markCard;
    }

    /** Diagnostics: what the markers resolved to (null when they cannot be resolved). */
    public static String markerSnapshot() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("card=").append(hitboxOfCard(markCardUuid, markCard) == null ? "none" : "ok")
                    .append("(idx=").append(markCard).append(")");
            sb.append(" target=").append(hitboxOfMonster(markTarget) == null ? "none" : "ok")
                    .append("(idx=").append(markTarget).append(",");
            try {
                sb.append("monsters=").append(
                        com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters() == null
                                ? "null" : com.megacrit.cardcrawl.dungeons.AbstractDungeon
                                .getMonsters().monsters.size());
            } catch (Throwable t) {
                sb.append("monsters=?");
            }
            sb.append(")");
            sb.append(" endTurn=").append(markEndTurn);
            return sb.toString();
        } catch (Throwable t) {
            return "unresolvable:" + t;
        }
    }

    public static void install() {
        BaseMod.subscribe(new Hud());
    }

    /**
     * 1.1.0 (user m04872 "p1有必要"): the plan text is no longer pushed in here.
     *
     * <p>This used to be {@code set(List<String> l)}, called from 76 places in {@code Gameplay} --
     * one of them every frame -- each time rebuilding the full status sentence list. Nothing read
     * the words: the panel took the list, asked whether it was empty, and drew its own lines from
     * {@code Gameplay.status()} instead (m03871). The list is still built for solver.log and the
     * bug report; the panel just asks the state a yes/no question now.
     */
    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean visible() {
        return enabled;
    }

    /** F12: draw a fixed banner no matter what state the solver is in. */
    /**
     * F12: a 5-second demo of the overlay, then back to the real plan.
     *
     * <p>It used to latch on, which meant the real plan stopped being drawn after one F12 press
     * (reported: "after F12 it no longer shows how to play"). A diagnostic must not be able to hide
     * the thing it is diagnosing.
     */
    public static void demo() {
        demoUntil = System.currentTimeMillis() + 5000L;
        enabled = true;
        Log.info("F12: overlay demo for 5s");
    }

    /** Stops the demo immediately (F8/F10 do this: the player is back to the real plan). */
    public static void demoOff() {
        demoUntil = 0L;
    }

    private static boolean demoActive() {
        return System.currentTimeMillis() < demoUntil;
    }

    /** m05752: the panel's click gate asks the same question the draw path does. */
    public static boolean isDemo() {
        return demoActive();
    }

    static long demoUntil;

    public static int failures() {
        return failures;
    }

    public static String lastFailure() {
        return lastFailure;
    }

    @Override
    public void receivePostRender(SpriteBatch sb) {
        // P2-27: no overlay work on menu / character-select / fade frames either -- the panel is
        // about the fight on screen, and those frames are exactly where the game is busy loading.
        // m05752: and nothing at all outside a fight. The map, the event screens and the rest site
        // are all GAMEPLAY, so the old check let the panel sit on top of the map with nothing to say.
        if (!demo && !sts1solver.ui.PhaseGate.fighting()) {
            return;
        }
        // 1.1.0 (user m04872): no list is pushed in any more, so there is nothing to wait for. The
        // panel draws its own lines from the live state and asks only "is there a plan to draw".
        List<String> l = demoActive() ? demoLines() : sts1solver.ui.Panel.planGate();
        if (!enabled) {
            // P2-10: hidden means "draw nothing but the tab that brings the panel back", so the
            // mouse can undo a hide without knowing the F11 hotkey.
            if (!demo) {
                try {
                    if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
                        return;
                    }
                    sts1solver.ui.Panel.renderHidden(sb);
                } catch (Throwable t) {
                    // a failed tab must never take the game down
                }
            }
            return;
        }
        if (l == null || l.isEmpty()) {
            return;
        }
        if (!demo) {
            try {
                if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
                    return;
                }
            } catch (Throwable t) {
                return;
            }
        }
        try {
            // The interactive panel owns the plan text, the buttons and the cursor ring now; drawNow
            // stays as the harness/demo renderer so the font path keeps its gate.
            sts1solver.ui.Panel.render(sb, l);
        } catch (Throwable t) {
            failures++;
            if (lastFailure == null) {
                lastFailure = String.valueOf(t);
                Log.error("HUD draw failed (this is the panel, not the solver)", t);
            }
        }
        try {
            drawMarkers(sb);
        } catch (Throwable t) {
            failures++;
            if (lastFailure == null) {
                lastFailure = String.valueOf(t);
                Log.error("HUD markers failed", t);
            }
        }
    }

    /**
     * The "which card, which monster" half of the panel.
     *
     * <p>The STS2 solver marks the card and the target on screen; a text row alone leaves the player
     * to find the monster themselves. Everything is resolved from the LIVE objects by index, and
     * every lookup can legitimately fail (the hand changed, the monster died), so each is guarded.
     */
    private static void drawMarkers(SpriteBatch sb) {
        // B48: no overlay while the solver owns the frame (search running, action being pushed).
        if (!markersAllowed()) {
            return;
        }
        try {
            if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        Hitbox card = hitboxOfCard(markCardUuid, markCard);
        Hitbox target = hitboxOfMonster(markTarget);
        Hitbox end = markEndTurn ? hitboxOfEndTurn() : null;
        if (card == null && target == null && end == null) {
            return;
        }
        lastMarkerGeometry = "card=" + describe(card) + " target=" + describe(target)
                + " end=" + describe(end) + " scale=" + Settings.scale;
        long startedNs = System.nanoTime();
        boolean wasDrawing = sb.isDrawing();
        Color old = new Color(sb.getColor());
        if (!wasDrawing) {
            sb.begin();
        }
        try {
            if (card != null) {
                fill(sb, card, 0.35f, 0.75f, 1.0f, 0.30f);
            }
            if (target != null) {
                fill(sb, target, 1.0f, 0.35f, 0.30f, 0.30f);
            }
            if (end != null) {
                fill(sb, end, 0.35f, 1.0f, 0.45f, 0.30f);
            }
            // A dotted arrow from the card to the monster: the same "this card hits that enemy"
            // relation the row states in text.
            if (card != null && target != null
                    && ImageMaster.WHITE_SQUARE_IMG != null
                    && boxOk(card.x, card.y, card.width, card.height)
                    && boxOk(target.x, target.y, target.width, target.height)) {
                dottedLine(sb, card.cX, card.cY, target.cX, target.cY);
            }
        } finally {
            sb.setColor(old);
            if (!wasDrawing) {
                sb.end();
            }
            // B48: a marker frame has no business being slow. If one ever is, the numbers are logged
            // once and the layer is switched off instead of risking another frozen frame.
            long us = (System.nanoTime() - startedNs) / 1000L;
            lastMarkerMicros = us;
            if (us > MARKER_BUDGET_US) {
                markersOff = true;
                Log.warn("hud markers: one frame took " + us + "us -- the marker layer is off for this"
                        + " session (" + lastMarkerGeometry + ")");
            }
        }
    }

    private static String describe(Hitbox hb) {
        if (hb == null) {
            return "-";
        }
        return "(" + (int) hb.x + "," + (int) hb.y + "," + (int) hb.width + "x" + (int) hb.height + ")"
                + (boxOk(hb.x, hb.y, hb.width, hb.height) ? "" : "!");
    }

    private static void fill(SpriteBatch sb, Hitbox hb, float r, float g, float b, float a) {
        if (ImageMaster.WHITE_SQUARE_IMG == null || !boxOk(hb.x, hb.y, hb.width, hb.height)) {
            return;
        }
        sb.setColor(r, g, b, a);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, hb.x, hb.y, hb.width, hb.height);
        sb.setColor(r, g, b, a + 0.45f);
        float th = 3.0f * Settings.scale;
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, hb.x, hb.y, hb.width, th);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, hb.x, hb.y + hb.height - th, hb.width, th);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, hb.x, hb.y, th, hb.height);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, hb.x + hb.width - th, hb.y, th, hb.height);
    }

    /**
     * The dotted card -> monster line.
     *
     * <p>B48 (m14700/m14739): the dot count used to be {@code len / (12 * scale)} with no ceiling, so
     * one corrupt coordinate -- a hitbox that was never laid out, a scale read before the window
     * existed -- asked the batch for an unbounded number of sprites inside a single frame. The hang
     * dump stopped exactly in this loop's {@code sb.draw}. The count is capped and the inputs are
     * validated now: a line we cannot trust is not drawn at all.
     */
    private static void dottedLine(SpriteBatch sb, float x1, float y1, float x2, float y2) {
        if (!finite(x1) || !finite(y1) || !finite(x2) || !finite(y2)) {
            return;
        }
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int dots = lineDots(len, Settings.scale);
        if (dots < 0) {
            return;
        }
        if (len / (12.0f * Settings.scale) > MAX_DOTS && cappedLines < 5) {
            cappedLines++;
            Log.warn("hud markers: a " + (int) len + "px line was capped at " + dots + " dots");
        }
        float size = 4.0f * Settings.scale;
        sb.setColor(1.0f, 0.55f, 0.35f, 0.85f);
        for (int i = 0; i <= dots; i++) {
            float t = i / (float) dots;
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x1 + dx * t - size * 0.5f,
                    y1 + dy * t - size * 0.5f, size, size);
        }
    }

    /**
     * The marked card's hitbox: by uuid first (stable), by index only when the action carries no
     * identity. Resolving by index alone is what pointed the marker at the neighbouring card.
     */
    private static Hitbox hitboxOfCard(String uuid, int index) {
        if (uuid != null && !uuid.isEmpty()) {
            Hitbox byUuid = hitboxOfUuid(uuid);
            if (byUuid != null) {
                return byUuid;
            }
            return null;
        }
        return hitboxOfIndex(index);
    }

    private static Hitbox hitboxOfUuid(String uuid) {
        try {
            if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.player == null) {
                return null;
            }
            java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> hand =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand.group;
            for (int i = 0; i < hand.size(); i++) {
                com.megacrit.cardcrawl.cards.AbstractCard c = hand.get(i);
                if (c != null && c.uuid != null && uuid.equals(c.uuid.toString())) {
                    return c.hb;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Hitbox hitboxOfIndex(int index) {
        try {
            if (index < 0 || com.megacrit.cardcrawl.dungeons.AbstractDungeon.player == null) {
                return null;
            }
            java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> hand =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand.group;
            if (index >= hand.size() || hand.get(index) == null) {
                return null;
            }
            return hand.get(index).hb;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Hitbox hitboxOfMonster(int index) {
        try {
            if (index < 0 || com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters() == null) {
                return null;
            }
            java.util.List<com.megacrit.cardcrawl.monsters.AbstractMonster> ms =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters;
            if (index >= ms.size() || ms.get(index) == null) {
                return null;
            }
            com.megacrit.cardcrawl.monsters.AbstractMonster m = ms.get(index);
            // B24: a corpse stays in `monsters` with its old slot index, so targeting by index
            // drew the red box on the dead slime the player had just killed. A marker on a
            // monster the plan cannot attack is worse than no marker.
            if (m.currentHealth <= 0 || m.isDying || m.isEscaping) {
                return null;
            }
            return m.hb;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Hitbox hitboxOfEndTurn() {
        try {
            com.megacrit.cardcrawl.core.OverlayMenu menu =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.overlayMenu;
            if (menu == null || menu.endTurnButton == null) {
                return null;
            }
            Object hb = sts1solver.reflect.Reflect.get(
                    sts1solver.reflect.Reflect.find(menu.endTurnButton.getClass(), "hb"),
                    menu.endTurnButton);
            return hb instanceof Hitbox ? (Hitbox) hb : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** x, y, w, h, stepIndex for every clickable plan row drawn last frame. */
    private static final List<float[]> rowBoxes = new ArrayList<float[]>();

    /**
     * Handles a left click on a plan row.
     *
     * @return true when the click was consumed by the panel
     */
    public static boolean handleClick() {
        if (!enabled || demoActive() || rowBoxes.isEmpty()) {
            return false;
        }
        try {
            if (!com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft) {
                return false;
            }
            float mx = com.megacrit.cardcrawl.helpers.input.InputHelper.mX;
            float my = com.megacrit.cardcrawl.helpers.input.InputHelper.mY;
            for (int i = rowBoxes.size() - 1; i >= 0; i--) {
                float[] b = rowBoxes.get(i);
                if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
                    int stepIndex = (int) b[4];
                    if (sts1solver.Gameplay.requestStep(stepIndex)) {
                        com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft = false;
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("HUD click: " + t);
        }
        return false;
    }

    private static List<String> demoLines() {
        List<String> out = new ArrayList<String>();
        out.add("Spire Pilot — overlay demo (F12 to hide)");
        out.add("if you can read this, the panel works");
        out.add("If this is visible but no plan was, the solver produced no plan.");
        return out;
    }

    /** Split out so the harness can exercise the font/batch path without a real fight. */
    public static void drawNow(SpriteBatch sb, List<String> l) {
        BitmapFont font = FontHelper.topPanelInfoFont != null ? FontHelper.topPanelInfoFont
                : FontHelper.buttonLabelFont;
        if (font == null || sb == null || l == null || l.isEmpty()) {
            throw new IllegalStateException("no font or batch: font=" + (font != null)
                    + " batch=" + (sb != null) + " lines=" + (l == null ? "null" : l.size()));
        }
        float scale = Settings.scale;
        float x = 24.0f * scale;
        float lineH = 22.0f * scale;
        // Settings.HEIGHT is the virtual resolution the batch is projected onto.
        float y = Settings.HEIGHT - 28.0f * scale;

        boolean wasDrawing = sb.isDrawing();
        Color oldFontColor = font.getColor() == null ? Color.WHITE : new Color(font.getColor());
        Color oldBatchColor = new Color(sb.getColor());
        if (!wasDrawing) {
            sb.begin();
        }
        try {
            float height = lineH * l.size() + 12.0f * scale;
            if (ImageMaster.WHITE_SQUARE_IMG != null) {
                sb.setColor(0.0f, 0.0f, 0.0f, 0.62f);
                sb.draw(ImageMaster.WHITE_SQUARE_IMG, x - 10.0f * scale, y - height + lineH,
                        560.0f * scale, height);
            }
            sb.setColor(Color.WHITE);
            font.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            rowBoxes.clear();
            for (int i = 0; i < l.size(); i++) {
                font.draw(sb, l.get(i), x, y - i * lineH);
                int stepIndex = sts1solver.Gameplay.rowStepIndex(i);
                if (stepIndex >= 0 && !demoActive()) {
                    // One clickable band per plan row: the STS2 overlay's action pills are clickable
                    // too, so the text panel keeps that affordance (click = execute through it).
                    rowBoxes.add(new float[]{x - 6.0f * scale, y - i * lineH - lineH * 0.9f,
                            540.0f * scale, lineH, stepIndex});
                }
            }
        } finally {
            font.setColor(oldFontColor);
            sb.setColor(oldBatchColor);
            if (!wasDrawing) {
                sb.end();
            }
        }
    }
}