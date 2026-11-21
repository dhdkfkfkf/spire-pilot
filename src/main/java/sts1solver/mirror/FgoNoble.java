package sts1solver.mirror;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.common.DrawCardAction;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import sts1solver.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * FGO「The Master」宝具解放, reached without clicking the mod's UI button.
 *
 * <p>The button is only a gate: it checks hover + left click + {@code master.fgoNp >= 100}, then
 * queues one of the mod's own actions and resets the gauge. Reflecting a click is not safe (the same
 * hover/click problem as Fern's wand), so the planner queues the effect directly. The effect opens a
 * standard {@code GridCardSelectScreen}, which the ordinary screen expansion already handles.
 *
 * <p>The gauge is a mod static scalar ({@code fatemaster.characters.master#fgoNp}), so
 * {@link ModStatics} mirrors it into the sandbox like any other mod global. That is what makes a
 * release safe to simulate: the search spends its own copy, not the live value.
 */
public final class FgoNoble {

    private static boolean loaded;
    private static Class<?> masterClass;
    private static Field npField;
    private static Constructor<?> selectCtor;
    private static Method updateEvent;
    private static String bowId;
    private static Boolean gaugeMirrored;

    private FgoNoble() {
    }

    private static boolean load() {
        if (loaded) {
            return masterClass != null;
        }
        loaded = true;
        try {
            masterClass = Class.forName("fatemaster.characters.master");
            npField = masterClass.getField("fgoNp");
            selectCtor = Class.forName("fatemaster.Action.NP.NoblePhantasmSelectAction")
                    .getConstructor(boolean.class, int.class);
            try {
                updateEvent = masterClass.getMethod("TruthValueUpdatedEvent");
            } catch (Throwable ignored) {
                // presentation only; the action and the gauge reset still work
            }
            try {
                bowId = (String) Class.forName("fatemaster.relics.BowInsignia")
                        .getField("ID").get(null);
            } catch (Throwable ignored) {
                // without the id the relic branch is simply not seen
            }
            Log.info("fgo noble: bridge ready");
        } catch (Throwable t) {
            masterClass = null;
            npField = null;
            selectCtor = null;
        }
        return masterClass != null;
    }

    /** True when the mod is present and the current player is the female Master. */
    public static boolean available() {
        if (!load()) {
            return false;
        }
        try {
            return masterClass.isInstance(AbstractDungeon.player);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int gauge() {
        if (!load()) {
            return -1;
        }
        try {
            return npField.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** -1 when FGO is absent, so a digest reader can skip the field entirely. */
    public static int gaugeForDigest() {
        return available() ? gauge() : -1;
    }

    public static boolean canRelease() {
        if (!available() || gauge() < 100 || !gaugeMirrored()) {
            return false;
        }
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room != null && room.phase == AbstractRoom.RoomPhase.COMBAT
                    && !AbstractDungeon.isScreenUp
                    && AbstractDungeon.actionManager != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String label() {
        if (hasBow()) {
            return "宝具解放（抽 3 张）";
        }
        int g = gauge();
        if (g >= 300) {
            return "宝具解放（300%，两张升级）";
        }
        if (g >= 200) {
            return "宝具解放（200%，升级）";
        }
        return "宝具解放（100%）";
    }

    /**
     * The button's effect, without the hover/click gate. Returns false when the gauge or the combat
     * state no longer allows it; callers treat that as a stale plan, not as a silent no-op.
     */
    public static boolean release() {
        if (!canRelease()) {
            return false;
        }
        int g = gauge();
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (hasBow()) {
                AbstractDungeon.actionManager.addToBottom(new DrawCardAction(player, 3));
            } else {
                boolean upgraded = g >= 200;
                int amount = g >= 300 ? 2 : 1;
                Object action = selectCtor.newInstance(Boolean.valueOf(upgraded),
                        Integer.valueOf(amount));
                AbstractDungeon.actionManager.addToBottom((AbstractGameAction) action);
            }
            npField.setInt(null, 0);
            if (updateEvent != null) {
                updateEvent.invoke(player);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Releasing in the sandbox writes {@code fgoNp}; that is only safe if the static sweep actually
     * owns the field. If the scan missed it (a future mod version moved the field), the plan must
     * not silently spend the live gauge, so the action disappears instead.
     */
    private static boolean gaugeMirrored() {
        if (gaugeMirrored == null) {
            boolean found;
            try {
                found = ModStatics.keys().contains("fatemaster.characters.master#fgoNp");
            } catch (Throwable t) {
                found = false;
            }
            if (found) {
                Log.info("fgo noble: fgoNp is in the static mirror");
            } else {
                Log.warn("fgo noble: fgoNp is not in the static mirror; noble action disabled");
            }
            gaugeMirrored = Boolean.valueOf(found);
        }
        return gaugeMirrored.booleanValue();
    }

    private static boolean hasBow() {
        try {
            return bowId != null && AbstractDungeon.player != null
                    && AbstractDungeon.player.hasRelic(bowId);
        } catch (Throwable t) {
            return false;
        }
    }
}
