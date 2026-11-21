package sts1solver.mirror;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import sts1solver.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * FrierenMod's Fern wand (海塔留下的法杖), reached without a real right click.
 *
 * <p>The relic itself is passive. Its "right click" lives in {@code FernPanel.updateAction()}, which
 * requires the tip hitbox to be hovered AND {@code InputHelper.justClickedRight} to be set, then
 * consumes that click. Reflecting a click would corrupt the global input state, so this bridge queues
 * the same effect the panel queues: one {@code MakeSpecializedOffensiveMagicAction(damage)}, one
 * {@code RemoveSpecificPowerAction} for 专注, and {@code FernPanel.useEnergy(1)}. Damage is the
 * panel's own {@code getBaseDamage()} (专注 x 倍率).
 *
 * <p>Like the mod's own cards, this is always part of the action space when its state allows it; it
 * has no switch. The panel and its charge/rate live in SpireFields injected as instance fields on
 * {@code AbstractPlayer}; {@link Cloner} copies them, so the sandbox spends its own charge and
 * concentration. The gate that proves this is S301.
 */
public final class FernWand {

    private static boolean loaded;
    private static Class<?> fernClass;
    private static Class<?> panelClass;
    private static Class<?> actionClass;
    private static Field panelHolder;
    private static Method spireGet;
    private static Method currentEnergy;
    private static Method maxEnergy;
    private static Method useEnergy;
    private static Method baseDamage;
    private static Field rateField;
    private static Constructor<?> makeCtor;
    private static Field powerIdField;
    private static Constructor<?> removeCtor;
    private static Method inCombat;

    private FernWand() {
    }

    private static boolean load() {
        if (loaded) {
            return fernClass != null;
        }
        loaded = true;
        try {
            fernClass = Class.forName("FrierenMod.Characters.Fern");
            panelClass = Class.forName("FrierenMod.ui.panels.FernPanel");
            actionClass = Class.forName("FrierenMod.actions.MakeSpecializedOffensiveMagicAction");
            panelHolder = Class.forName("FrierenMod.patches.PanelPatch$PanelField")
                    .getField("FernPanel");
            spireGet = Class.forName("com.evacipated.cardcrawl.modthespire.lib.SpireField")
                    .getMethod("get", Object.class);
            currentEnergy = panelClass.getMethod("getCurrentEnergy");
            maxEnergy = panelClass.getMethod("getMaxEnergy");
            useEnergy = panelClass.getMethod("useEnergy", int.class);
            baseDamage = panelClass.getMethod("getBaseDamage");
            rateField = panelClass.getDeclaredField("rate");
            rateField.setAccessible(true);
            makeCtor = actionClass.getConstructor(int.class);
            powerIdField = Class.forName("FrierenMod.powers.ConcentrationPower")
                    .getField("POWER_ID");
            removeCtor = RemoveSpecificPowerAction.class.getConstructor(
                    com.megacrit.cardcrawl.core.AbstractCreature.class,
                    com.megacrit.cardcrawl.core.AbstractCreature.class, String.class);
            inCombat = Class.forName("FrierenMod.gameHelpers.CombatHelper")
                    .getMethod("isInCombat");
            Log.info("fern wand: bridge ready");
        } catch (Throwable t) {
            resetBridge();
            Log.warn("fern wand: bridge unavailable (" + t + ")");
        }
        return fernClass != null;
    }

    private static void resetBridge() {
        fernClass = null;
        panelClass = null;
        actionClass = null;
        panelHolder = null;
        spireGet = null;
        currentEnergy = null;
        maxEnergy = null;
        useEnergy = null;
        baseDamage = null;
        rateField = null;
        makeCtor = null;
        powerIdField = null;
        removeCtor = null;
        inCombat = null;
    }

    /** True when the mod is loaded and the live player is Fern (the wand's panel is hers only). */
    public static boolean available() {
        if (!load()) {
            return false;
        }
        try {
            AbstractPlayer p = AbstractDungeon.player;
            return p != null && fernClass.isInstance(p) && panel(p) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object panel(AbstractPlayer p) throws Exception {
        Object holder = panelHolder.get(null);
        return holder == null || p == null ? null : spireGet.invoke(holder, p);
    }

    public static int charge() {
        if (!load()) {
            return -1;
        }
        try {
            Object v = currentEnergy.invoke(null);
            return v == null ? -1 : ((Integer) v).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int max() {
        if (!load()) {
            return -1;
        }
        try {
            Object v = maxEnergy.invoke(null);
            return v == null ? -1 : ((Integer) v).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int rate() {
        if (!load()) {
            return -1;
        }
        try {
            Object p = panel(AbstractDungeon.player);
            return p == null ? -1 : rateField.getInt(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The panel's own number: 专注 x 倍率. -1 when the bridge or the panel is missing. */
    public static int damage() {
        if (!load()) {
            return -1;
        }
        try {
            Object p = panel(AbstractDungeon.player);
            if (p == null) {
                return -1;
            }
            Object v = baseDamage.invoke(p);
            return v == null ? -1 : ((Integer) v).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Charge / rate / max, or "" when Fern is not the player. Keeps transposition honest. */
    public static String digest() {
        if (!available()) {
            return "";
        }
        int c = charge();
        int r = rate();
        if (c < 0 || r < 0) {
            return "";
        }
        int m = max();
        return c + "/" + r + "/" + (m < 0 ? 0 : m);
    }

    public static boolean canUse() {
        if (!available()) {
            return false;
        }
        try {
            AbstractPlayer p = AbstractDungeon.player;
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                return false;
            }
            if (AbstractDungeon.isScreenUp || AbstractDungeon.actionManager == null) {
                return false;
            }
            if (AbstractDungeon.actionManager.turnHasEnded) {
                return false;
            }
            if (inCombat != null
                    && !((Boolean) inCombat.invoke(null)).booleanValue()) {
                return false;
            }
            // FernPanel.updateAction only refuses at charge 0. Damage may be 0 (no 专注); the
            // MakeSpecializedOffensiveMagicAction still adds every DamageFusionPower on top.
            if (charge() <= 0) {
                return false;
            }
            // Same reentry guard as FernPanel.updateAction: one wand card per queue.
            ArrayList<AbstractGameAction> queued = AbstractDungeon.actionManager.actions;
            for (int i = 0; i < queued.size(); i++) {
                if (actionClass.isInstance(queued.get(i))) {
                    return false;
                }
            }
            return p != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String label() {
        return "法杖造牌（" + damage() + " 伤害）";
    }

    /**
     * The panel's right-click effect without the hover/click gate. Returns false when the state no
     * longer allows it; callers treat that as a stale plan, not as a silent no-op.
     */
    public static boolean use() {
        if (!canUse()) {
            return false;
        }
        int dmg = damage();
        if (dmg < 0) {
            return false;
        }
        try {
            AbstractPlayer p = AbstractDungeon.player;
            Object make = makeCtor.newInstance(Integer.valueOf(dmg));
            AbstractDungeon.actionManager.addToBottom((AbstractGameAction) make);
            Object powerId = powerIdField.get(null);
            AbstractGameAction remove = (AbstractGameAction) removeCtor.newInstance(
                    p, p, String.valueOf(powerId));
            AbstractDungeon.actionManager.addToBottom(remove);
            useEnergy.invoke(null, Integer.valueOf(1));
            return true;
        } catch (Throwable t) {
            Log.warn("fern wand: use failed: " + t);
            return false;
        }
    }
}