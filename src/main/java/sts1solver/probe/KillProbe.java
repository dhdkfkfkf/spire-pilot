package sts1solver.probe;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import sts1solver.drive.Driver;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.search.Legal;
import sts1solver.util.Log;

/**
 * Diagnostic: what does the engine look like right after a slime dies (B14 investigation)?
 *
 * <p>A split slime does not vanish on death: it dies inside CannotLoseAction, then spawns children, and
 * only then can the room be won. If "all listed monsters are dying" is taken as victory before the
 * children exist, the search finishes the fight early and the live plan has nowhere to go.
 */
public final class KillProbe {

    private KillProbe() {
    }

    public static void run() {
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 42L;
            spec.maxHealth = 200;
            spec.deck = new String[]{"Bludgeon", "Bludgeon", "Bludgeon", "Bash", "Bash"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.AcidSlime_L"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            AbstractMonster slime = AbstractDungeon.getMonsters().monsters.get(0);
            slime.currentHealth = 3;
            Log.info("killcheck: before, hp=" + slime.currentHealth + "/" + slime.maxHealth
                    + " over=" + Driver.combatOver());
            World w = World.captureLive();
            w.install();
            Driver.begin();
            try {
                for (int attempt = 0; attempt < 6; attempt++) {
                    java.util.List<Action> legal = Legal.actions();
                    Action attack = null;
                    for (int i = 0; i < legal.size(); i++) {
                        if (legal.get(i).isCard()) {
                            attack = legal.get(i);
                            break;
                        }
                    }
                    if (attack == null) {
                        break;
                    }
                    Log.info("killcheck: play " + attack.label);
                    Legal.apply(attack);
                    dump("after play " + attempt);
                    if (Driver.combatOver()) {
                        break;
                    }
                }
            } finally {
                // Each step gets its own guard. When these three shared one finally block, a throw
                // from Driver.end() or readBack() SKIPPED uninstall(), and the sandbox statics stayed
                // installed in the live game -- a stale snapshot that the next real run could restore
                // over fresh state. Cleanup must be impossible to skip.
                try {
                    Driver.end();
                } catch (Throwable t) {
                    Log.warn("killcheck: Driver.end failed: " + t);
                }
                try {
                    w.readBack();
                } catch (Throwable t) {
                    Log.warn("killcheck: readBack failed: " + t);
                }
                try {
                    w.uninstall();
                } catch (Throwable t) {
                    Log.warn("killcheck: uninstall failed, forcing a restore: " + t);
                    World.forceRestore();
                }
            }
            Log.info("killcheck done");
            Log.info("AUTOTEST RESULT passed=1 failed=0");
            Log.info("AUTOTEST DONE");
        } catch (Throwable t) {
            Log.error("killcheck failed", t);
            Log.info("AUTOTEST RESULT passed=0 failed=1");
            Log.info("AUTOTEST DONE");
        }
    }

    private static void dump(String when) {
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            StringBuilder sb = new StringBuilder("killcheck: ").append(when).append(" over=")
                    .append(Driver.combatOver())
                    .append(" battleOver=").append(AbstractDungeon.getCurrRoom().isBattleOver)
                    .append(" q=").append(gam.actions.size()).append('/').append(gam.cardQueue.size())
                    .append('/').append(gam.monsterQueue.size()).append('/').append(gam.preTurnActions.size())
                    .append(" using=").append(gam.usingCard).append(" ended=").append(gam.turnHasEnded)
                    .append(" mons=");
            java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < ms.size(); i++) {
                AbstractMonster m = ms.get(i);
                sb.append(m.getClass().getSimpleName()).append(':').append(m.currentHealth)
                        .append(" dying=").append(m.isDying).append(" escaped=").append(m.isEscaping)
                        .append(" half=").append(m.halfDead).append(' ');
            }
            Log.info(sb.toString());
        } catch (Throwable t) {
            Log.warn("killcheck dump failed: " + t);
        }
    }
}