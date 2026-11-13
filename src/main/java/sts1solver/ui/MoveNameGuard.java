package sts1solver.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.vfx.combat.MoveNameEffect;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

import java.lang.reflect.Field;

/**
 * Instrumented guard on the effect that crashes the render thread.
 *
 * <p>The observed crash is always the same:
 *
 * <pre>
 *   GlyphLayout.setText  &lt;-  FontHelper.renderFontCentered  &lt;-  MoveNameEffect.render
 *   &lt;-  AbstractDungeon.render  &lt;-  CardCrawlGame.render      (game shuts down)
 * </pre>
 *
 * <p>Two things can be null there and they need different fixes: the effect's message, or the font
 * the effect renders with. Reading the bytecode narrowed it to those two; this prefix patch decides
 * which, LOGS it, and returns without drawing so the game survives either way.
 *
 * <p>Keeping it permanently is deliberate: a mod that constructs this effect with a null message
 * would crash the player's game with or without a solver, and a display-only effect must never be
 * able to do that.
 */
@SpirePatch(clz = MoveNameEffect.class, method = "render")
public final class MoveNameGuard {

    private static int logged;

    @SpirePrefixPatch
    public static SpireReturn<Void> prefix(MoveNameEffect __instance, SpriteBatch sb) {
        try {
            Object msg = read(__instance, "msg");
            if (msg == null) {
                if (logged++ < 8) {
                    Log.warn("MoveNameEffect.render with NULL msg -> suppressed"
                            + " (isDone=" + __instance.isDone
                            + " duration=" + __instance.duration
                            + " starting=" + __instance.startingDuration
                            + " identity=" + Integer.toHexString(System.identityHashCode(__instance))
                            + " msgField=" + msgFieldInfo(__instance) + ")");
                }
                __instance.isDone = true;
                return SpireReturn.Return(null);
            }
            if (FontHelper.cardTitleFont == null) {
                if (logged++ < 8) {
                    Log.warn("MoveNameEffect.render with NULL FontHelper.cardTitleFont -> suppressed");
                }
                return SpireReturn.Return(null);
            }
        } catch (Throwable t) {
            Log.warn("MoveNameGuard failed: " + t);
        }
        return SpireReturn.Continue();
    }

    private static Object read(Object target, String name) {
        Field f = Reflect.find(target.getClass(), name);
        return f == null ? null : Reflect.get(f, target);
    }

    private static String msgFieldInfo(Object target) {
        Field f = Reflect.find(target.getClass(), "msg");
        if (f == null) {
            return "no-field";
        }
        return f.getDeclaringClass().getSimpleName() + "." + f.getName()
                + (java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? " STATIC" : "");
    }
}
