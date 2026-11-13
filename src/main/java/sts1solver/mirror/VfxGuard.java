package sts1solver.mirror;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;
import sts1solver.util.Log;

import java.util.List;

/**
 * Keeps unsafe-allocated visual effects out of the game's render lists.
 *
 * <p>A copied effect is allocated without its constructor. MoveNameEffect, for example, sets
 * isDone = true when its message is null; a copy has msg == null and isDone == false, and its
 * render() then calls FontHelper.renderFontCentered with a null string, which throws inside libGDX
 * and takes the whole game down from the render method.
 *
 * <p>Prevention lives in {@link ClonePolicy} (every AbstractGameEffect is shared, never copied).
 * This class is the guard that makes a regression harmless: after every search the live effect
 * lists are swept, and any effect the cloner allocated is removed and NAMED.
 *
 * <p>The identification is by TAG, not by looking at fields. An earlier version guessed
 * "duration == 0 and not done" and deleted 30 real effects: StrikeEffect and DeckPoofEffect
 * genuinely have no duration and are animated by their own timers.
 */
public final class VfxGuard {

    private VfxGuard() {
    }

    /** @return a human-readable summary; "clean" when nothing had to be removed */
    public static String sweep() {
        int removed = 0;
        StringBuilder detail = new StringBuilder();
        try {
            removed += sweepList(AbstractDungeon.effectList, detail);
            removed += sweepList(AbstractDungeon.effectsQueue, detail);
            removed += sweepList(AbstractDungeon.topLevelEffects, detail);
            removed += sweepList(AbstractDungeon.topLevelEffectsQueue, detail);
        } catch (Throwable t) {
            return "sweep failed: " + t;
        }
        if (removed > 0) {
            Log.warn("VFX GUARD removed " + removed + " copied effect(s): " + detail);
            return "removed " + removed + ": " + detail;
        }
        return "clean";
    }

    private static int sweepList(List<AbstractGameEffect> list, StringBuilder detail) {
        if (list == null) {
            return 0;
        }
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            AbstractGameEffect e = list.get(i);
            if (e == null) {
                continue;
            }
            if (Cloner.EFFECT_CLONES.contains(e)) {
                list.remove(i);
                removed++;
                if (detail.length() < 300) {
                    detail.append(e.getClass().getSimpleName()).append(' ');
                }
            }
        }
        return removed;
    }

    /** Diagnostic: how many copied effects are still tracked (0 after a clean sweep). */
    public static int trackedClones() {
        return Cloner.EFFECT_CLONES.size();
    }
}
