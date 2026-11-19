package sts1solver.mirror;

import basemod.BaseMod;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

/**
 * B197 (user 2026-11-19): a sandbox window can OPTIONALLY keep BaseMod combat events to itself.
 * Off by default; see the toggle paragraph below for why the safe behaviour is the experiment.
 *
 * <p>What went wrong: the search mirrors the fight and drives the real engine on the render thread.
 * When a simulated monster attack landed, {@code AbstractPlayer.damage} called BaseMod's
 * {@code OnPlayerDamagedHook}, which published the event to every registered subscriber. One of
 * them -- the luShi mod -- entered an infinite loop in {@code luShi.luShi.onAttackAssit}
 * ({@code GuFu.jar}, bytecode: the "not my minion" branch jumps back to the loop head without
 * advancing the index, line 519). The frame never returned, so input died with it, and every
 * restart hit the same line again. The hang dumps show 20/20 samples on that line for 3.8 s and a
 * frame 4.1 s late; the engine frames below it are ours.
 *
 * <p>Why muting and not a timeout: the dispatch runs synchronously inside the engine call on this
 * thread, and Java cannot interrupt a loop it does not control. A watchdog can only report a hang
 * after the fact (that is what the dumps are). The only reliable way to keep an arbitrary
 * subscriber from bricking the game is to not run it inside the window. This is the same principle
 * as {@link PresentationQuiet} (B168) -- "the sandbox does not announce events to other mods" --
 * widened from presentation events to the combat hooks that can change state: a listener written
 * for a real frame may pause on an animation, open a screen, or (as here) loop on a condition the
 * sandbox never satisfies.
 *
 * <p><b>When it mutes is a player choice</b> ({@link CompatMode}). Since 1.6.8 that choice has two
 * independent groups -- the 15 combat hooks and the 6 non-combat events -- both off by default,
 * i.e. the pre-1.6.7 behaviour: inside a window the real engine publishes to every subscriber, so
 * their effects belong to the prediction (and any buggy listener, luShi included, still chokes the
 * render thread). Turning a group on in the Mods screen keeps that group's events to the window,
 * which is the only way to guarantee that a third-party listener in that group cannot freeze a
 * search.
 *
 * <p>What is muted when a group is on: every BaseMod {@code publish*} a sandbox window can reach is
 * in one of the two groups ({@link #COMBAT_HOOKS}, {@link #OTHER_HOOKS}). The combat group is the
 * 15 {@code ModHooks.COMBAT_HOOKS} lists -- card draw/use/exhaust, energy recharge, monster turn,
 * battle start/end, potion use, power apply/modified, player block loss/damage, player turn start
 * -- plus the six non-combat-list dispatches engine calls still reach inside a window: max-HP
 * change ({@code AbstractCreature.increaseMaxHp}), relic get ({@code AbstractRelic.obtain}),
 * potion get ({@code AbstractPlayer.obtainPotion}), card description
 * ({@code AbstractCard.initializeDescription}), unlock refresh ({@code UnlockTracker.refresh}) and
 * post-death ({@code DeathScreen}/{@code VictoryScreen}). Each publish method returns the value a
 * real dispatch would return with no subscriber interested (the input amount or text,
 * {@code true} for the monster-turn veto), so the engine continues with its own logic.
 * Vanilla content is unaffected (no vanilla class subscribes), and the mods' cards, relics,
 * potions and monsters still run their own code in the sandbox -- only the cross-cutting hook
 * listeners are skipped.
 *
 * <p>Outside a window both checks return false and every method behaves exactly as BaseMod's, so
 * real play keeps its mod behaviour. The cost inside a window is fidelity: a mod whose combat
 * effect lives in one of these hooks is not modelled by a prediction (it never was certified --
 * ModHooks documents the reference solver treating subscribers as a certification blocker). The
 * panel and the log say which mode is on; a silent lie would be worse than the loss.
 *
 * <p><b>Completeness</b> (user 2026-11-19, "做到底"): BaseMod exposes 50 {@code publish*} methods.
 * {@link #GUARDED} and {@link #UNREACHABLE} classify every one of them, and S275 checks that the
 * classification is total and current and that the number of patch classes below matches the
 * guarded list. A BaseMod update that adds a publish method fails that gate until someone decides
 * which side it belongs on. The binding of each patch is checked by the loader itself (a patch on a
 * method BaseMod does not have fails the game at load). The frame hooks (Update/Render/ImGui) and
 * the startup/menu hooks are in {@link #UNREACHABLE} because the sandbox runs engine calls, not a
 * frame loop.
 */
public final class HookQuiet {

    /** Dispatch calls skipped so far; the quick-search log prints the per-search delta. */
    private static volatile int muted;

    private HookQuiet() {
    }

    /** True while a window is installed AND the player muted the 15 combat hooks (default off). */
    public static boolean combatMuted() {
        return World.anyInstalled() && CompatMode.combat();
    }

    /** True while a window is installed AND the player muted the 6 non-combat events (default off). */
    public static boolean otherMuted() {
        return World.anyInstalled() && CompatMode.other();
    }

    static void count() {
        ++muted;
    }

    public static int mutedCount() {
        return muted;
    }

    public static void resetForTest() {
        muted = 0;
    }

    /** One entry per publish method guarded here; the gate suite pins the count. */
    public static final int GUARDED_METHODS = 21;

    /** The 15 hooks BaseMod itself groups as combat hooks (ModHooks.COMBAT_HOOKS). */
    public static final int COMBAT_METHODS = 15;

    /** The 6 non-combat dispatches a window can still reach. */
    public static final int OTHER_METHODS = 6;

    /** The combat group, in the order the Config screen lists them. */
    public static final String[] COMBAT_HOOKS = {
            "publishStartBattle", "publishOnPlayerTurnStart",
            "publishOnPlayerTurnStartPostDraw", "publishPostDraw", "publishOnCardUse",
            "publishPostExhaust", "publishPostEnergyRecharge", "publishPreMonsterTurn",
            "publishPostPowerApply", "publishOnPowersModified", "publishOnPlayerLoseBlock",
            "publishOnPlayerDamaged", "publishPrePotionUse", "publishPostPotionUse",
            "publishPostBattle" };

    /** The non-combat group, in the order the Config screen lists them. */
    public static final String[] OTHER_HOOKS = {
            "publishMaxHPChange", "publishRelicGet", "publishPotionGet",
            "publishOnCreateDescription", "publishPostRefresh", "publishPostDeath" };

    /** The publish methods muted inside a window. S275 checks this against the patches. */
    public static final String[] GUARDED;

    static {
        GUARDED = new String[GUARDED_METHODS];
        System.arraycopy(COMBAT_HOOKS, 0, GUARDED, 0, COMBAT_METHODS);
        System.arraycopy(OTHER_HOOKS, 0, GUARDED, COMBAT_METHODS, OTHER_METHODS);
    }

    /**
     * The rest of BaseMod's publish surface, which a window cannot reach: frame hooks (the sandbox
     * runs engine calls, not CardCrawlGame.update/render) and startup/menu hooks. S275 requires
     * these two lists together to equal BaseMod's whole publish set.
     */
    public static final String[] UNREACHABLE = {
            "publishStartAct", "publishPostCampfire", "publishPostDungeonInitialize",
            "publishPostInitialize", "publishRender", "publishAnimationRender",
            "publishPreRender", "publishPostRender", "publishPreStartGame", "publishStartGame",
            "publishPreUpdate", "publishPostUpdate", "publishPostDungeonUpdate",
            "publishPreDungeonUpdate", "publishPostPlayerUpdate", "publishPrePlayerUpdate",
            "publishPostCreateStartingDeck", "publishPostCreateStartingRelics",
            "publishPostCreateShopRelics", "publishPostCreateShopPotions", "publishEditCards",
            "publishEditRelics", "publishEditCharacters", "publishEditStrings",
            "publishAddAudio", "publishEditKeywords", "publishAddCustomModeMods",
            "publishPreRoomRender", "publishImGui" };

    private static SpireReturn<Void> skipVoid() {
        count();
        return SpireReturn.Return(null);
    }

    private static SpireReturn<Integer> skipInt(int amount) {
        count();
        return SpireReturn.Return(Integer.valueOf(amount));
    }

    private static SpireReturn<String> skipString(String text) {
        count();
        return SpireReturn.Return(text);
    }

    private static SpireReturn<Boolean> skipTrue() {
        count();
        return SpireReturn.Return(Boolean.TRUE);
    }

    /** publishPostDraw(AbstractCard) -- postDrawSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPostDraw",
            paramtypez = { AbstractCard.class })
    public static final class PostDraw {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractCard card) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPostExhaust(AbstractCard) -- postExhaustSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPostExhaust",
            paramtypez = { AbstractCard.class })
    public static final class PostExhaust {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractCard card) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishOnCardUse(AbstractCard) -- onCardUseSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishOnCardUse",
            paramtypez = { AbstractCard.class })
    public static final class OnCardUse {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractCard card) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPostEnergyRecharge() -- postEnergyRechargeSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPostEnergyRecharge",
            paramtypez = { })
    public static final class PostEnergyRecharge {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix() {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPreMonsterTurn(AbstractMonster) -- preMonsterTurnSubscribers. true = turn may run. */
    @SpirePatch(clz = BaseMod.class, method = "publishPreMonsterTurn",
            paramtypez = { AbstractMonster.class })
    public static final class PreMonsterTurn {
        @SpirePrefixPatch
        public static SpireReturn<Boolean> prefix(AbstractMonster monster) {
            return combatMuted() ? HookQuiet.skipTrue() : SpireReturn.Continue();
        }
    }

    /** publishStartBattle(AbstractRoom) -- startBattleSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishStartBattle",
            paramtypez = { AbstractRoom.class })
    public static final class StartBattle {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractRoom room) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPostBattle(AbstractRoom) -- postBattleSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPostBattle",
            paramtypez = { AbstractRoom.class })
    public static final class PostBattle {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractRoom room) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPrePotionUse(AbstractPotion) -- prePotionUseSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPrePotionUse",
            paramtypez = { AbstractPotion.class })
    public static final class PrePotionUse {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractPotion potion) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPostPotionUse(AbstractPotion) -- postPotionUseSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPostPotionUse",
            paramtypez = { AbstractPotion.class })
    public static final class PostPotionUse {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractPotion potion) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPostPowerApply(AbstractPower,AbstractCreature,AbstractCreature) */
    @SpirePatch(clz = BaseMod.class, method = "publishPostPowerApply",
            paramtypez = { AbstractPower.class, AbstractCreature.class, AbstractCreature.class })
    public static final class PostPowerApply {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractPower power, AbstractCreature source,
                                               AbstractCreature target) {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishOnPowersModified() -- onPowersModifiedSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishOnPowersModified",
            paramtypez = { })
    public static final class OnPowersModified {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix() {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishOnPlayerLoseBlock(int) -- onPlayerLoseBlockSubscribers. Returns the block lost. */
    @SpirePatch(clz = BaseMod.class, method = "publishOnPlayerLoseBlock",
            paramtypez = { int.class })
    public static final class OnPlayerLoseBlock {
        @SpirePrefixPatch
        public static SpireReturn<Integer> prefix(int amount) {
            return combatMuted() ? HookQuiet.skipInt(amount) : SpireReturn.Continue();
        }
    }

    /** publishOnPlayerDamaged(int,DamageInfo) -- onPlayerDamagedSubscribers. Returns the damage. */
    @SpirePatch(clz = BaseMod.class, method = "publishOnPlayerDamaged",
            paramtypez = { int.class, DamageInfo.class })
    public static final class OnPlayerDamaged {
        @SpirePrefixPatch
        public static SpireReturn<Integer> prefix(int amount, DamageInfo info) {
            return combatMuted() ? HookQuiet.skipInt(amount) : SpireReturn.Continue();
        }
    }

    /** publishOnPlayerTurnStart() -- onPlayerTurnStartSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishOnPlayerTurnStart",
            paramtypez = { })
    public static final class OnPlayerTurnStart {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix() {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishOnPlayerTurnStartPostDraw() -- onPlayerTurnStartPostDrawSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishOnPlayerTurnStartPostDraw",
            paramtypez = { })
    public static final class OnPlayerTurnStartPostDraw {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix() {
            return combatMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishMaxHPChange(int) -- maxHPChangeSubscribers. Returns the change, so return the input. */
    @SpirePatch(clz = BaseMod.class, method = "publishMaxHPChange",
            paramtypez = { int.class })
    public static final class MaxHPChange {
        @SpirePrefixPatch
        public static SpireReturn<Integer> prefix(int amount) {
            return otherMuted() ? HookQuiet.skipInt(amount) : SpireReturn.Continue();
        }
    }

    /** publishRelicGet(AbstractRelic) -- relicGetSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishRelicGet",
            paramtypez = { AbstractRelic.class })
    public static final class RelicGet {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractRelic relic) {
            return otherMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPotionGet(AbstractPotion) -- potionGetSubscribers */
    @SpirePatch(clz = BaseMod.class, method = "publishPotionGet",
            paramtypez = { AbstractPotion.class })
    public static final class PotionGet {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix(AbstractPotion potion) {
            return otherMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishOnCreateDescription(String,AbstractCard) -- onCreateDescriptionSubscribers. Returns the text. */
    @SpirePatch(clz = BaseMod.class, method = "publishOnCreateDescription",
            paramtypez = { String.class, AbstractCard.class })
    public static final class OnCreateDescription {
        @SpirePrefixPatch
        public static SpireReturn<String> prefix(String text, AbstractCard card) {
            return otherMuted() ? HookQuiet.skipString(text) : SpireReturn.Continue();
        }
    }

    /** publishPostRefresh() -- setUnlocksSubscribers (UnlockTracker.refresh) */
    @SpirePatch(clz = BaseMod.class, method = "publishPostRefresh",
            paramtypez = { })
    public static final class PostRefresh {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix() {
            return otherMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }

    /** publishPostDeath() -- postDeathSubscribers (DeathScreen/VictoryScreen) */
    @SpirePatch(clz = BaseMod.class, method = "publishPostDeath",
            paramtypez = { })
    public static final class PostDeath {
        @SpirePrefixPatch
        public static SpireReturn<Void> prefix() {
            return otherMuted() ? HookQuiet.skipVoid() : SpireReturn.Continue();
        }
    }
}
