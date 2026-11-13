package sts1solver.ui;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.relics.AbstractRelic;

/**
 * The relic row on the solver panel (P1-1b).
 *
 * <p>It lists the relics the solver actually accounted for in this fight. It deliberately does NOT
 * claim per-relic trigger counts: the engine has no cumulative trigger counter we can read, and
 * inventing one would be a guess dressed up as a number. A name list is verifiable; a made-up
 * count is not.
 */
public final class RelicRow {

    private static final int MAX = 6;

    private RelicRow() {
    }

    /** One panel line, empty when the player carries nothing. */
    public static String describe() {
        if (AbstractDungeon.player == null || AbstractDungeon.player.relics == null
                || AbstractDungeon.player.relics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("遗物: ");
        int shown = 0;
        for (int i = 0; i < AbstractDungeon.player.relics.size(); i++) {
            AbstractRelic r = AbstractDungeon.player.relics.get(i);
            if (r == null || r.name == null || r.name.isEmpty()) {
                continue;
            }
            if (shown > 0) {
                sb.append(" · ");
            }
            sb.append(r.name);
            shown++;
            if (shown == MAX) {
                int rest = AbstractDungeon.player.relics.size() - shown;
                if (rest > 0) {
                    sb.append(" 等 ").append(rest).append(" 件");
                }
                break;
            }
        }
        return shown == 0 ? "" : sb.toString();
    }
}