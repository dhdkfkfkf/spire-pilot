package sts1solver.guard.patches;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import sts1solver.guard.TexGuard;

/**
 * 1.0.7 (T1): the ONE texture guard. Every textured quad -- card, orb, power, relic, potion,
 * monster, screen, effect -- passes through {@code SpriteBatch.switchTexture}, so this is the only
 * place a broken texture has to be caught. The 1.0.5 per-class field patches are gone: they guessed
 * at four owners and the crash came from a fifth.
 *
 * <p>{@code PolygonSpriteBatch} inherits this method (this libgdx has no separate PolygonBatch), so
 * the map and beam layers are covered by the same patch.
 */
public class SpriteBatchSwitchPatch {

    @SpirePatch(clz = SpriteBatch.class, method = "switchTexture", paramtypez = {Texture.class})
    public static class SwitchTexture {

        @SpirePrefixPatch
        public static SpireReturn<Void> guard(SpriteBatch __instance, Texture texture) {
            TexGuard.noteActive();
            if (TexGuard.usable(texture)) {
                return SpireReturn.Continue();
            }
            // Never let the original run: its next statement is texture.getWidth(), the crash site.
            TexGuard.repair(__instance, texture);
            return SpireReturn.Return(null);
        }
    }
}
