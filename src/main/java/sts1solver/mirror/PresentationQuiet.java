package sts1solver.mirror;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.actions.common.InstantKillAction;
import com.megacrit.cardcrawl.actions.common.SuicideAction;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.exordium.TheGuardian;
import com.megacrit.cardcrawl.relics.NeowsLament;
import com.megacrit.cardcrawl.relics.PreservedInsect;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import sts1solver.util.Log;

/**
 * B168 (user m18305 / m18398 / m19477): a sandbox window must not DISPATCH the engine's presentation
 * events to other mods.
 *
 * <p>What went wrong: {@code AbstractCreature.healthBarUpdatedEvent()} is a pure presentation hook --
 * its own body only sets {@code healthBarAnimTimer = 1.2f} and {@code targetHealthBarWidth} -- but
 * every damage/heal goes through it, so any mod may have hung effects off it. The reported case is
 * YuAi ("雨爱"), which starts a music/particle/health-bar animation when the PLAYER crosses half
 * health. The search evaluates thousands of nodes per step, and every node that damages the player
 * past half health dispatched that event once more. Because the search runs on the render thread
 * ({@code Gameplay.tick()} -> {@code QUICK_SLICE_MS}), those listeners turned into the measured
 * 500-600 ms frames: the game looked frozen while it was calculating.
 *
 * <p>Why the obvious fixes do not work:
 * <ul>
 *   <li>A {@code @SpirePrefixPatch} returning {@code SpireReturn.Return(null)} does NOT stop other
 *       mods' postfixes: ModTheSpire inserts prefixes with {@code insertBefore} (ordering 1) and
 *       postfixes with {@code insertAfter} (ordering 2), and {@code insertAfter} lands before every
 *       RETURN in the method, including the one the prefix just added. Measured in
 *       {@code _tmp/javassist-test}: a prefix that returns early still ran the postfix.</li>
 *   <li>Flipping {@code CardCrawlGame.mode} to something other than GAMEPLAY for the window changes
 *       every mod's {@code isInRun} answer and the engine's own behaviour -- far larger blast radius
 *       than muting one presentation event.</li>
 * </ul>
 *
 * <p>What this class does: at load time (ordering -1, i.e. before any prefix/postfix insertion) it
 * rewrites each of the 13 CALL SITES of {@code healthBarUpdatedEvent()} into
 * {@code if (!PresentationQuiet.quiet()) { <receiver>.healthBarUpdatedEvent(); }}. The method itself
 * is untouched, so a direct call (and the engine's own rendering) still works; only the dispatch is
 * skipped while a {@link World} is installed. With no window installed {@code quiet()} is false and
 * the bytecode behaves exactly as before, so the live game still plays the mod's effect.
 *
 * <p>Why per call site and not per listener: the mod that listens is not known to us and must not be
 * special-cased. Muting the engine's presentation dispatch for the duration of a window is a
 * property of the sandbox, and it covers every listener at once.
 */
public final class PresentationQuiet {

    /** Call sites of {@code healthBarUpdatedEvent()} in the game jar (see the patch classes below). */
    public static final int EXPECTED_SITES = 14;

    /**
     * Call sites replaced so far. ModTheSpire applies patches through the patch class it loaded for
     * the mod jar, which can be a different copy of this class than the one the running game uses
     * (measured: the field stayed 0 while the rewrite had plainly happened -- the gate saw
     * timerAfterDamage=0). A system property is the one piece of state both copies share.
     */
    private static final String SITES_PROPERTY = "sts1solver.presentationQuiet.patchedSites";
    private static int patchedSites;
    private static volatile int logged;

    private PresentationQuiet() {
    }

    /**
     * True while the sandbox statics are live. Every presentation event dispatched in that window
     * would reach a real listener (music, particles, another mod's bookkeeping), so it is skipped.
     */
    public static boolean quiet() {
        return World.anyInstalled();
    }

    /** How many call sites the load-time rewrite actually replaced (gate S227 pins this). */
    public static int patchedSites() {
        int local = patchedSites;
        if (local > 0) {
            return local;
        }
        try {
            String value = System.getProperty(SITES_PROPERTY);
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void resetForTest() {
        logged = 0;
    }

    private static ExprEditor editor() {
        return new ExprEditor() {
            @Override
            public void edit(MethodCall call) throws CannotCompileException {
                if (!"healthBarUpdatedEvent".equals(call.getMethodName())) {
                    return;
                }
                // "$0" is the receiver, "$$" splices the original arguments. The call is void, so
                // there is no "$_" to keep.
                call.replace("{ if (!sts1solver.mirror.PresentationQuiet.quiet())"
                        + " { $0.healthBarUpdatedEvent(); } }");
                ++patchedSites;
                try {
                    System.setProperty(SITES_PROPERTY, String.valueOf(patchedSites));
                } catch (Throwable ignored) {
                    // a SecurityManager would be the only way this fails; the gate counts behaviour too
                }
            }
        };
    }

    /** AbstractCreature.increaseMaxHp(int,boolean) */
    @SpirePatch(clz = AbstractCreature.class, method = "increaseMaxHp", paramtypez = { int.class, boolean.class })
    public static final class Site01 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractCreature.decreaseMaxHealth(int) */
    @SpirePatch(clz = AbstractCreature.class, method = "decreaseMaxHealth", paramtypez = { int.class })
    public static final class Site02 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractCreature.heal(int,boolean) */
    @SpirePatch(clz = AbstractCreature.class, method = "heal", paramtypez = { int.class, boolean.class })
    public static final class Site03 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractPlayer.damage(DamageInfo) */
    @SpirePatch(clz = AbstractPlayer.class, method = "damage", paramtypez = { DamageInfo.class })
    public static final class Site04 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractPlayer.preBattlePrep() */
    @SpirePatch(clz = AbstractPlayer.class, method = "preBattlePrep", paramtypez = { })
    public static final class Site05 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractMonster.heal(int) */
    @SpirePatch(clz = AbstractMonster.class, method = "heal", paramtypez = { int.class })
    public static final class Site06 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractMonster.damage(DamageInfo) */
    @SpirePatch(clz = AbstractMonster.class, method = "damage", paramtypez = { DamageInfo.class })
    public static final class Site07 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** AbstractMonster.init() */
    @SpirePatch(clz = AbstractMonster.class, method = "init", paramtypez = { })
    public static final class Site08 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** InstantKillAction.update() */
    @SpirePatch(clz = InstantKillAction.class, method = "update", paramtypez = { })
    public static final class Site09 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** SuicideAction.update() */
    @SpirePatch(clz = SuicideAction.class, method = "update", paramtypez = { })
    public static final class Site10 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** TheGuardian.changeState(String) -- two sites (the mode change updates the bar twice) */
    @SpirePatch(clz = TheGuardian.class, method = "changeState", paramtypez = { String.class })
    public static final class Site11 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** NeowsLament.atBattleStart() */
    @SpirePatch(clz = NeowsLament.class, method = "atBattleStart", paramtypez = { })
    public static final class Site12 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

    /** PreservedInsect.atBattleStart() */
    @SpirePatch(clz = PreservedInsect.class, method = "atBattleStart", paramtypez = { })
    public static final class Site13 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }

}
