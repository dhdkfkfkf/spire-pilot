package sts1solver.guard.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.powers.AbstractPower;
import sts1solver.mirror.World;

/**
 * 1.7.5: the "power gained" flash is presentation, and inside a sandbox it must not be built.
 *
 * <p>{@code AbstractPower.flash()} adds a {@code GainPowerEffect} (the per-power icon pulse) and a
 * {@code FlashPowerEffect} (the screen flash). The second one reads {@code power.region128}:
 * {@code loadRegion(name)} fills it from the power atlas via {@code findRegion("128/" + name)}, and
 * a power whose region name is missing -- a mod power with its own art, a power that never calls
 * loadRegion, any mismatch between the mod and this game build -- leaves it null. The constructor
 * then dereferences it ({@code FlashPowerEffect.java:29}), and because this happens inside
 * {@code ApplyPowerAction.update}, the exception aborts the whole step: the search reports
 * "引擎异常（搜索分支被丢弃）" and silently loses that branch.
 *
 * <p>A sandbox has no renderer and nothing in these two effect lists is combat state, so the flash is
 * skipped ONLY while a world is installed; the live game keeps its animation exactly as before.
 * {@code flashWithoutSound()} is the same pair minus the sound and is guarded the same way.
 */
public class PowerFlashPatch {

    @SpirePatch(clz = AbstractPower.class, method = "flash")
    public static class Flash {

        @SpirePrefixPatch
        public static SpireReturn<Void> skip(AbstractPower __instance) {
            if (World.anyInstalled()) {
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch(clz = AbstractPower.class, method = "flashWithoutSound")
    public static class FlashWithoutSound {

        @SpirePrefixPatch
        public static SpireReturn<Void> skip(AbstractPower __instance) {
            if (World.anyInstalled()) {
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }
}
