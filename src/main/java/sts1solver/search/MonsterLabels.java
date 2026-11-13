package sts1solver.search;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Display names for monsters, so a plan can say WHICH of three identical Sentries it hits.
 *
 * <p>m16717-2: with three Sentries every row read "打击 -> 哨卫" and the player could not tell the
 * plan's leftmost from its rightmost. The reference solver (CombatSolver-main,
 * {@code src/Runtime/SolverDisplayNames.cs}, {@code CaptureCreatureBaseName} block) solves this the same
 * way the game's own UI hints do: count how many enemies share the base name, and if more than one,
 * append its left-to-right rank -- {@code 哨卫（左起2）}. It ranks over the WHOLE participant list, not
 * only the living ones, so a rank does not shift when a sibling dies mid-fight.
 *
 * <p>The rank is presentation only. Actions keep their {@code targetIndex} into
 * {@code AbstractDungeon.getMonsters().monsters}, which is what the engine resolves; a label is never
 * parsed back into a target.
 */
public final class MonsterLabels {

    private MonsterLabels() {
    }

    /** Every monster in the fight, in engine order (dead ones included: they hold their rank). */
    public static List<AbstractMonster> battle() {
        List<AbstractMonster> out = new ArrayList<AbstractMonster>();
        try {
            if (AbstractDungeon.getMonsters() == null
                    || AbstractDungeon.getMonsters().monsters == null) {
                return out;
            }
            List<AbstractMonster> all = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) != null) {
                    out.add(all.get(i));
                }
            }
        } catch (Throwable ignored) {
            // No monster group (menu, transition) means no names to disambiguate.
        }
        return out;
    }

    /** The name to show for {@code target}: plain, or "名字（左起N）" when the name is shared. */
    public static String of(AbstractMonster target) {
        return of(target, battle());
    }

    public static String of(AbstractMonster target, List<AbstractMonster> battle) {
        if (target == null) {
            return "";
        }
        String base = baseName(target);
        if (battle == null || battle.size() < 2) {
            return base;
        }
        int same = 0;
        for (int i = 0; i < battle.size(); i++) {
            AbstractMonster m = battle.get(i);
            if (m != null && base.equals(baseName(m))) {
                same++;
            }
        }
        if (same < 2) {
            return base;
        }
        List<AbstractMonster> ordered = new ArrayList<AbstractMonster>();
        for (int i = 0; i < battle.size(); i++) {
            if (battle.get(i) != null) {
                ordered.add(battle.get(i));
            }
        }
        Collections.sort(ordered, new Comparator<AbstractMonster>() {
            @Override
            public int compare(AbstractMonster a, AbstractMonster b) {
                float x1 = xOf(a);
                float x2 = xOf(b);
                return x1 < x2 ? -1 : (x1 > x2 ? 1 : 0);
            }
        });
        int n = 0;
        for (int i = 0; i < ordered.size(); i++) {
            AbstractMonster m = ordered.get(i);
            if (!base.equals(baseName(m))) {
                continue;
            }
            n++;
            if (m == target) {
                return base + "（左起" + n + "）";
            }
        }
        return base;
    }

    /** The shared part of the name: the game's title, falling back to the engine id. */
    public static String baseName(AbstractMonster m) {
        if (m == null) {
            return "";
        }
        String name = null;
        try {
            name = m.name;
        } catch (Throwable ignored) {
            // A half-constructed monster has no name yet.
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        String id = null;
        try {
            id = m.id;
        } catch (Throwable ignored) {
            // ignore
        }
        return id == null ? "?" : id;
    }

    /** Left-to-right coordinate: the hitbox centre when the monster is laid out, else its draw x. */
    private static float xOf(AbstractMonster m) {
        try {
            if (m.hb != null) {
                return m.hb.cX;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return m.drawX;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }
}
