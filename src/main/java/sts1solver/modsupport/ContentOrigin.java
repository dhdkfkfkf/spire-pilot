package sts1solver.modsupport;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which content is participating in the current fight.
 *
 * <p>The product decision is (user, m11229): <b>every mod is accepted</b>. There is no whitelist
 * gate any more -- a mod card, relic, potion, monster, character, orb or any other new mechanic is
 * simulated by running its own code in the sandbox, so the mod is just as real as vanilla here.
 * This class therefore answers one question -- "is anything in this fight outside the vanilla
 * game?" -- and names what it found, for the log and for the panel. It no longer decides whether
 * the answer may be trusted: nothing is refused. {@link ModProfile} survives as optional metadata
 * (a human-readable name for a class prefix), not as a permission list.
 *
 * <p>Vanilla detection is by package: every shipped class lives under
 * {@code com.megacrit.cardcrawl.}; a mod that reuses those base classes still declares its cards,
 * relics, potions, monsters and characters in its own package. That is a reliable signal in
 * practice, and a mistake here can only ever be too *permissive* (we would say "mod content" for
 * something unusual), never silently wrong: the panel shows the names it found.
 */
public final class ContentOrigin {

    private static final String VANILLA_PREFIX = "com.megacrit.cardcrawl.";
    private static final int MAX_NAMES = 6;

    /** What the fight is made of, as far as we can tell before solving. */
    public static final class Status {
        public final boolean vanilla;
        /** Up to {@link #MAX_NAMES} names of non-vanilla classes found in the fight. */
        public final List<String> modContent;
        public final List<String> profiles;
        /** Mod-owned BaseMod combat hook registrations, e.g. "ninja.NinjaMod#onCardUseSubscribers". */
        public final List<String> hooks;
        public final int hookCount;
        /** Profiles that happen to name the detected hook classes (display only, never a gate). */
        public final List<String> hookProfiles;

        Status(boolean vanilla, List<String> modContent, List<String> profiles,
               List<String> hooks, int hookCount, List<String> hookProfiles) {
            this.vanilla = vanilla;
            this.modContent = modContent;
            this.profiles = profiles;
            this.hooks = hooks;
            this.hookCount = hookCount;
            this.hookProfiles = hookProfiles;
        }

        /** One log/panel line. Nothing here is a warning: every mod is simulated for real. */
        public String describe() {
            if (vanilla) {
                if (hookCount == 0) {
                    return "来源: 原版";
                }
                // Vanilla classes but mod code is listening: worth naming, no longer a refusal.
                // B197: the mode says whether the sandbox runs those listeners (default) or keeps the
                // events to itself (兼容实验模式; a listener written for a real frame may loop).
                return "来源: 原版 + " + hookCount + " 个 mod 战斗钩子"
                        + (sts1solver.mirror.CompatMode.combat()
                                ? "（模拟时不运行）" : "（模拟时照常运行）")
                        + (hooks.isEmpty() ? "" : ": " + join(hooks));
            }
            StringBuilder sb = new StringBuilder("来源: 含 mod 内容（按真实引擎模拟）");
            if (!profiles.isEmpty()) {
                sb.append("，已识别 mod: ").append(join(profiles));
            }
            sb.append(": ").append(join(modContent));
            if (hookCount > 0) {
                sb.append("；").append(hookCount).append(" 个 mod 战斗钩子")
                        .append(sts1solver.mirror.CompatMode.combat() ? "模拟时不运行" : "照常运行");
            }
            return sb.toString();
        }

        private static String join(List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(list.get(i));
            }
            return sb.toString();
        }
    }

    private ContentOrigin() {
    }

    /** Whether a class belongs to the shipped game rather than to a mod. */
    public static boolean isVanilla(Class<?> type) {
        return type == null || type.getName().startsWith(VANILLA_PREFIX);
    }

    /**
     * Inspects the live fight. Safe to call at any time; a missing piece contributes nothing.
     *
     * <p>Call it while the LIVE game is installed (not inside a sandbox), because the panel wants
     * the real fight's provenance.
     */
    public static Status inspect() {
        Set<String> mod = new LinkedHashSet<String>();
        AbstractPlayer player = AbstractDungeon.player;
        if (player != null) {
            collect(mod, player.getClass());
            collectCards(mod, player.hand);
            collectCards(mod, player.drawPile);
            collectCards(mod, player.discardPile);
            collectCards(mod, player.exhaustPile);
            collectCards(mod, player.masterDeck);
            if (player.relics != null) {
                for (int i = 0; i < player.relics.size(); i++) {
                    AbstractRelic r = player.relics.get(i);
                    if (r != null) {
                        collect(mod, r.getClass());
                    }
                }
            }
            if (player.potions != null) {
                for (int i = 0; i < player.potions.size(); i++) {
                    AbstractPotion p = player.potions.get(i);
                    if (p != null) {
                        collect(mod, p.getClass());
                    }
                }
            }
            if (player.powers != null) {
                for (int i = 0; i < player.powers.size(); i++) {
                    AbstractPower p = player.powers.get(i);
                    if (p != null) {
                        collect(mod, p.getClass());
                    }
                }
            }
        }
        try {
            if (AbstractDungeon.getMonsters() != null) {
                for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
                    AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
                    if (m != null) {
                        collect(mod, m.getClass());
                        if (m.powers != null) {
                            for (int k = 0; k < m.powers.size(); k++) {
                                AbstractPower p = m.powers.get(k);
                                if (p != null) {
                                    collect(mod, p.getClass());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // a missing monster group simply contributes nothing
        }

        List<String> names = new ArrayList<String>(mod);
        List<String> shown = names.size() > MAX_NAMES ? names.subList(0, MAX_NAMES) : names;
        List<String> covered = ModProfile.covering(names);
        int hookCount = ModHooks.combatHookCount();
        List<String> hooks = ModHooks.combatHookNames(MAX_NAMES);
        List<String> hookClasses = ModHooks.combatHookClasses();
        List<String> hookProfiles = hookCount == 0 ? new ArrayList<String>()
                : ModProfile.covering(hookClasses);
        // No gate: a mod is accepted by default (m11229). The names above are information.
        return new Status(names.isEmpty(), shown, covered, hooks, hookCount, hookProfiles);
    }

    private static void collectCards(Set<String> mod, com.megacrit.cardcrawl.cards.CardGroup group) {
        if (group == null) {
            return;
        }
        try {
            for (int i = 0; i < group.group.size(); i++) {
                AbstractCard c = group.group.get(i);
                if (c != null) {
                    collect(mod, c.getClass());
                }
            }
        } catch (Throwable ignored) {
            // a pile that is being rebuilt contributes nothing
        }
    }

    private static void collect(Set<String> mod, Class<?> type) {
        if (!isVanilla(type)) {
            mod.add(type.getName());
        }
    }
}