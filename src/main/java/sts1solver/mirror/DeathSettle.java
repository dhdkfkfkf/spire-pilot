package sts1solver.mirror;

import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.util.Log;

import java.util.ArrayList;

/**
 * B130 (m06143): a monster that dies keeps its powers in the sandbox.
 *
 * <p>In the live game {@code AbstractMonster.die()} only starts a death animation: it sets
 * {@code isDying}, runs every power's {@code onDeath()} and starts a 1.8s timer. The powers are
 * cleared one and a half seconds later by {@code AbstractMonster.updateDeathAnimation()}
 * ({@code this.powers.clear()} right before {@code dispose()}). That method is driven by the render
 * loop ({@code AbstractMonster.update() <- MonsterGroup.update() <- AbstractRoom.update()}), and no
 * render loop runs while the search drives the engine, so a monster killed in the sandbox kept every
 * power it had when it died.
 *
 * <p>That is not cosmetic: cards that COUNT powers still count the dead one. In the 2026-11-04
 * Gremlin Leader fight 防哈+ (CaiNongMod:DefendToKill) gained block once per monster carrying 农白
 * ({@code NongBaiPower}) without checking whether the monster was still alive; the sandbox counted a
 * dead Gremlin Thief the live game had already cleared, predicted 49 block where the live game gave
 * 39, and the panel ended the fight at 61 HP where it really ended at 51.
 *
 * <p>{@link #settle()} applies that terminal state at the usual presentation settle point
 * ({@link World#trimPresentation()}, between steps, before the digest is read). It deliberately does
 * NOT do anything else the real death-end code does: {@code dispose()} is skipped (it frees textures
 * the live game still owns) and no battle is ended here (the Driver decides that from
 * {@code areMonstersBasicallyDead()}).
 */
public final class DeathSettle {
    /** Cap on the number of settle lines written to the log for one process. */
    private static final int NOTE_CAP = 3;

    private static int settled;
    private static int noted;
    private static String last = "";

    private DeathSettle() {
    }

    /**
     * Finishes the death animation of every monster the sandbox killed: marks it dead and drops its
     * powers, exactly what {@code AbstractMonster.updateDeathAnimation()} does when its timer runs
     * out. Call while installed, at a settle point.
     */
    public static void settle() {
        MonsterGroup group;
        try {
            group = AbstractDungeon.getMonsters();
        } catch (Throwable t) {
            return;
        }
        if (group == null || group.monsters == null) {
            return;
        }
        ArrayList<AbstractMonster> list = group.monsters;
        for (int i = 0; i < list.size(); i++) {
            AbstractMonster m = list.get(i);
            if (m == null || !m.isDying || m.isDead) {
                continue;
            }
            int dropped = m.powers == null ? 0 : m.powers.size();
            m.isDead = true;
            if (m.powers != null) {
                m.powers.clear();
            }
            ++settled;
            last = m.name + " (" + m.id + ") is dead, " + dropped + " power(s) dropped";
            if (noted < NOTE_CAP) {
                ++noted;
                Log.info("death settle: " + last
                        + " -- the live game clears powers when the death animation ends (B130)");
            }
        }
    }

    /** One line for the startup/stats report: how many deaths were finished so far. */
    public static String describe() {
        return "death settle: " + settled + " finished, last " + (last.isEmpty() ? "-" : last);
    }
}
