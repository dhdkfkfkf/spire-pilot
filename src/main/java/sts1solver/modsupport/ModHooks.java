package sts1solver.modsupport;

import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts mod code that is attached to COMBAT through BaseMod's hook registries.
 *
 * <p>Why this exists: a fight can contain only vanilla cards, relics, potions and monsters and still
 * behave differently from the shipped game, because a mod subscribed to `onCardUse`, `postDraw`,
 * `onPlayerDamaged`, `preMonsterTurn`... and mutates state the mirror replays. The reference solver
 * treats exactly this as a certification blocker (`RootRunModSubscriberCount != 0 ||
 * RootCombatModSubscriberCount != 0 || RootHasBaseLibCardModifiers` -> not certified), rather than
 * assuming that "all classes are native" is enough.
 *
 * <p>This is a COUNT of code that is loaded and listening, not a judgement about what it does. It can
 * therefore only make the panel more honest, never wrong.
 */
public final class ModHooks {

    /**
     * BaseMod's hook lists that can change combat state. Names are matched, not types, so a new
     * BaseMod version with an additional combat hook is simply not counted (conservative in the same
     * direction: fewer counted hooks, and vanilla still needs no hooks to be certified).
     */
    private static final String[] COMBAT_HOOKS = {
            "postDrawSubscribers",
            "postExhaustSubscribers",
            "onCardUseSubscribers",
            "postEnergyRechargeSubscribers",
            "preMonsterTurnSubscribers",
            "postBattleSubscribers",
            "startBattleSubscribers",
            "postPotionUseSubscribers",
            "prePotionUseSubscribers",
            "postPowerApplySubscribers",
            "onPowersModifiedSubscribers",
            "onPlayerLoseBlockSubscribers",
            "onPlayerDamagedSubscribers",
            "onPlayerTurnStartSubscribers",
            "onPlayerTurnStartPostDrawSubscribers"
    };

    private ModHooks() {
    }

    /** Non-vanilla, non-framework class: something a mod wrote. */
    public static boolean countsAsMod(String className) {
        return isModOwned(className);
    }

    /**
     * Framework packages are never mod content. Everything else is decided by where the class was
     * loaded from, not by its name: ArlequinAndante registers its merchant character (and its hook
     * subscribers) inside {@code com.megacrit.cardcrawl.characters}, and the old name test dropped
     * it from every mod-hook list the panel shows.
     */
    static boolean isModOwned(String className) {
        if (className == null) {
            return false;
        }
        if (className.startsWith("basemod.")
                || className.startsWith("com.evacipated.")
                || className.startsWith("sts1solver.")) {
            return false;
        }
        return !sts1solver.util.Provenance.isGameName(className);
    }

    /** Up to {@code max} "mod.Class#hookName" strings for the panel and the log. */
    public static List<String> combatHookNames(int max) {
        List<String> out = new ArrayList<String>();
        try {
            Class<?> baseMod = Class.forName("basemod.BaseMod", false,
                    ModHooks.class.getClassLoader());
            for (int i = 0; i < COMBAT_HOOKS.length && out.size() < max; i++) {
                String fieldName = COMBAT_HOOKS[i];
                Field f = Reflect.find(baseMod, fieldName);
                if (f == null) {
                    continue;
                }
                Object value = Reflect.get(f, null);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                for (int k = 0; k < list.size() && out.size() < max; k++) {
                    Object item = list.get(k);
                    if (item == null) {
                        continue;
                    }
                    String cn = item.getClass().getName();
                    if (isModOwned(cn)) {
                        out.add(cn + "#" + fieldName);
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("mod hooks: cannot read BaseMod hook lists: " + t);
        }
        return out;
    }

    /** Mod-owned hook classes (no field suffix), for whitelist matching. */
    public static List<String> combatHookClasses() {
        List<String> out = new ArrayList<String>();
        int count = combatHookCount();
        java.util.List<String> names = combatHookNames(Math.max(count, 1));
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            int hash = n.indexOf('#');
            out.add(hash > 0 ? n.substring(0, hash) : n);
        }
        return out;
    }

    /** Total number of mod-owned combat hook registrations. */
    public static int combatHookCount() {
        int total = 0;
        try {
            Class<?> baseMod = Class.forName("basemod.BaseMod", false,
                    ModHooks.class.getClassLoader());
            for (int i = 0; i < COMBAT_HOOKS.length; i++) {
                Field f = Reflect.find(baseMod, COMBAT_HOOKS[i]);
                if (f == null) {
                    continue;
                }
                Object value = Reflect.get(f, null);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                for (int k = 0; k < list.size(); k++) {
                    Object item = list.get(k);
                    if (item != null && isModOwned(item.getClass().getName())) {
                        total++;
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("mod hooks: cannot count BaseMod hooks: " + t);
        }
        return total;
    }
}