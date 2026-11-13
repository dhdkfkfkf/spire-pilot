package sts1solver.ui;

import com.badlogic.gdx.graphics.Color;
import com.megacrit.cardcrawl.core.OverlayMenu;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.ui.buttons.EndTurnButton;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The live UI state a simulated turn must not change: the End Turn button's flags and the combat
 * panel visibility.
 *
 * <p>It exists because those fields belong to objects that COMBAT CODE toggles
 * ({@code EnableEndTurnButtonAction}, {@code DisableEndTurnButtonAction}, {@code hide()}). With the
 * button shared between the sandbox and the live fight, one simulated end-turn hid the real button
 * and it never came back. The policy fix is to copy the button; this class is the audit and repair
 * on top, so a regression is visible and harmless.
 */
public final class ViewState {

    private final List<Field> fields = new ArrayList<Field>();
    private final List<Object> targets = new ArrayList<Object>();
    private final List<Object> values = new ArrayList<Object>();
    private String summary = "none";

    private ViewState() {
    }

    public static ViewState capture() {
        ViewState v = new ViewState();
        try {
            OverlayMenu menu = AbstractDungeon.overlayMenu;
            if (menu != null) {
                Object button = menu.endTurnButton;
                if (button != null) {
                    v.add(button, "isHidden");
                    v.add(button, "enabled");
                    v.add(button, "isGlowing");
                }
                v.add(menu, "combatPanelsShown");
                // The pile panels (draw / discard / exhaust) animate out when the combat panels
                // hide; if a simulated turn leaves one of them hidden, the live fight shows the End
                // Turn button but NO draw/discard pile counts. That is exactly the reported bug, so
                // their hidden flags are audited and restored too.
                if (menu.combatDeckPanel != null) {
                    v.add(menu.combatDeckPanel, "isHidden");
                }
                if (menu.discardPilePanel != null) {
                    v.add(menu.discardPilePanel, "isHidden");
                }
                if (menu.exhaustPanel != null) {
                    v.add(menu.exhaustPanel, "isHidden");
                }
            }
            v.addStatic("screen");
            v.addStatic("deathScreen");
            v.addStatic("combatRewardScreen");
            Object player = AbstractDungeon.player;
            if (player != null) {
                v.add(player, "isDead");
                // B37: these are what the mouse writes while a card is hovered or being aimed, and
                // the player is drawn from them (renderHand / renderHoverReticle). The sandbox runs
                // the same update code, so a simulated step must not be able to leave the live player
                // in "hovering / targeting / dragging" state after the search.
                v.add(player, "isHoveringDropZone");
                v.add(player, "hoveredCard");
                v.add(player, "hoveredMonster");
                v.add(player, "isDraggingCard");
                v.add(player, "inSingleTargetMode");
                v.add(player, "hoverEnemyWaitTimer");
            }
            v.summary = "view(" + v.values.size() + " fields)";
        } catch (Throwable t) {
            v.summary = "capture failed: " + t;
        }
        return v;
    }

    /** A static field of AbstractDungeon that must be the same after a search (screens included). */
    private void addStatic(String name) {
        try {
            Field f = Reflect.find(AbstractDungeon.class, name);
            if (f == null) {
                return;
            }
            Object value = Reflect.get(f, null);
            if (value instanceof Color) {
                value = new Color((Color) value);
            }
            fields.add(f);
            targets.add(null);
            values.add(value);
        } catch (Throwable t) {
            // a missing field simply is not audited
        }
    }

    private void add(Object target, String name) {
        Field f = Reflect.find(target.getClass(), name);
        if (f == null) {
            return;
        }
        Object value = Reflect.get(f, target);
        if (value instanceof Color) {
            value = new Color((Color) value);
        }
        fields.add(f);
        targets.add(target);
        values.add(value);
    }

    /** @return a description of what had to be put back; "clean" when nothing moved */
    public String restoreIfChanged() {
        StringBuilder changed = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            Object target = targets.get(i);
            Object want = values.get(i);
            try {
                Object now = Reflect.get(f, target);
                if (want instanceof Color) {
                    Color w = (Color) want;
                    Color n = (Color) now;
                    if (w.r == n.r && w.g == n.g && w.b == n.b && w.a == n.a) {
                        continue;
                    }
                    f.set(target, new Color(w));
                    changed.append(f.getName()).append(" ");
                    continue;
                }
                if (now != null && now.equals(want)) {
                    continue;
                }
                if (now == null && want == null) {
                    continue;
                }
                f.set(target, want);
                changed.append(f.getName()).append(" ");
                continue;
            } catch (Throwable t) {
                // A field that cannot be restored is reported, not thrown.
                changed.append(f.getName()).append("(failed) ");
            }
        }
        if (changed.length() > 0) {
            Log.warn("VIEW STATE restored: " + changed);
            return changed.toString();
        }
        return "clean";
    }

    public String summary() {
        return summary;
    }
}