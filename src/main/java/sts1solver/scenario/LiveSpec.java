package sts1solver.scenario;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import sts1solver.util.Log;

/**
 * The part of a live fight that can be rebuilt faithfully in another process (P1-2h, stage C).
 *
 * <p>A worker cannot receive the combat object graph, so live-root parallelism needs a compact
 * description that REBUILDS to the same state. This class captures that description and provides a
 * fingerprint over exactly the fields it reproduces: HP, block, energy, turn, the four pile orders
 * (card id + upgrade level) and monster HP/max/block. If the fingerprints match, the rebuilt fight
 * is provably the same for everything the solver prices; if they differ, the caller must NOT trust a
 * worker result.
 *
 * <p>Deliberately NOT captured in v1 (and therefore excluded from the fingerprint): monster intents
 * and AI history, arbitrary power amounts, temporary card modifications, relic counters, and the RNG
 * streams. Those are the documented gap that keeps live-root workers advisory rather than
 * authoritative.
 */
public final class LiveSpec {

    public String playerClass = "com.megacrit.cardcrawl.characters.Ironclad";
    public int ascension;
    public int hp;
    public int maxHealth;
    public int block;
    public int energy;
    public int turn = 1;
    public String[] relics = new String[0];
    public String[] drawIds = new String[0];
    public int[] drawUpg = new int[0];
    public String[] handIds = new String[0];
    public int[] handUpg = new int[0];
    public String[] discardIds = new String[0];
    public int[] discardUpg = new int[0];
    public String[] exhaustIds = new String[0];
    public int[] exhaustUpg = new int[0];
    public String[] monsterIds = new String[0];
    public int[] monsterHp = new int[0];
    public int[] monsterMax = new int[0];
    public int[] monsterBlock = new int[0];

    /** Encodes this spec as plain request fields (no JSON dependency). */
    public void toMap(java.util.Map<String, String> out) {
        out.put("live.class", playerClass);
        out.put("live.ascension", String.valueOf(ascension));
        out.put("live.hp", String.valueOf(hp));
        out.put("live.maxHealth", String.valueOf(maxHealth));
        out.put("live.block", String.valueOf(block));
        out.put("live.energy", String.valueOf(energy));
        out.put("live.turn", String.valueOf(turn));
        out.put("live.relics", join(relics));
        out.put("live.handIds", join(handIds));
        out.put("live.handUpg", join(handUpg));
        out.put("live.drawIds", join(drawIds));
        out.put("live.drawUpg", join(drawUpg));
        out.put("live.discardIds", join(discardIds));
        out.put("live.discardUpg", join(discardUpg));
        out.put("live.exhaustIds", join(exhaustIds));
        out.put("live.exhaustUpg", join(exhaustUpg));
        out.put("live.monsterIds", join(monsterIds));
        out.put("live.monsterHp", join(monsterHp));
        out.put("live.monsterMax", join(monsterMax));
        out.put("live.monsterBlock", join(monsterBlock));
    }

    /** Decodes the fields written by {@link #toMap}. */
    public static LiveSpec fromMap(java.util.Map<String, String> in) {
        LiveSpec s = new LiveSpec();
        s.playerClass = or(in.get("live.class"), s.playerClass);
        s.ascension = toInt(in.get("live.ascension"));
        s.hp = toInt(in.get("live.hp"));
        s.maxHealth = toInt(in.get("live.maxHealth"));
        s.block = toInt(in.get("live.block"));
        s.energy = toInt(in.get("live.energy"));
        s.turn = toInt(in.get("live.turn"));
        s.relics = split(or(in.get("live.relics"), ""));
        s.handIds = split(or(in.get("live.handIds"), ""));
        s.handUpg = toInts(in.get("live.handUpg"));
        s.drawIds = split(or(in.get("live.drawIds"), ""));
        s.drawUpg = toInts(in.get("live.drawUpg"));
        s.discardIds = split(or(in.get("live.discardIds"), ""));
        s.discardUpg = toInts(in.get("live.discardUpg"));
        s.exhaustIds = split(or(in.get("live.exhaustIds"), ""));
        s.exhaustUpg = toInts(in.get("live.exhaustUpg"));
        s.monsterIds = split(or(in.get("live.monsterIds"), ""));
        s.monsterHp = toInts(in.get("live.monsterHp"));
        s.monsterMax = toInts(in.get("live.monsterMax"));
        s.monsterBlock = toInts(in.get("live.monsterBlock"));
        return s;
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static int toInt(String value) {
        try {
            return value == null || value.isEmpty() ? 0 : Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String join(String[] values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i] == null ? "" : values[i]);
        }
        return sb.toString();
    }

    private static String join(int[] values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String[] split(String value) {
        if (value == null || value.isEmpty()) {
            return new String[0];
        }
        return value.split(",", -1);
    }

    private static int[] toInts(String value) {
        String[] parts = split(value);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = toInt(parts[i]);
        }
        return out;
    }

    /** Reads the live fight. */
    public static LiveSpec capture() {
        LiveSpec s = new LiveSpec();
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null) {
            return s;
        }
        s.playerClass = classOf(p);
        s.ascension = AbstractDungeon.ascensionLevel;
        s.hp = p.currentHealth;
        s.maxHealth = p.maxHealth;
        s.block = p.currentBlock;
        s.energy = EnergyPanel.totalCount;
        s.turn = GameActionManager.turn;
        String[] pileIds;
        int[] pileUpg;
        pileIds = new String[0];
        pileUpg = new int[0];
        s.relics = relicsOf(p);
        fill(p.drawPile, true);
        s.drawIds = LiveSpec.lastIds;
        s.drawUpg = LiveSpec.lastUpg;
        fill(p.hand, true);
        s.handIds = LiveSpec.lastIds;
        s.handUpg = LiveSpec.lastUpg;
        fill(p.discardPile, true);
        s.discardIds = LiveSpec.lastIds;
        s.discardUpg = LiveSpec.lastUpg;
        fill(p.exhaustPile, true);
        s.exhaustIds = LiveSpec.lastIds;
        s.exhaustUpg = LiveSpec.lastUpg;
        if (AbstractDungeon.getMonsters() != null) {
            java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
            int n = ms.size();
            s.monsterIds = new String[n];
            s.monsterHp = new int[n];
            s.monsterMax = new int[n];
            s.monsterBlock = new int[n];
            for (int i = 0; i < n; i++) {
                AbstractMonster m = ms.get(i);
                s.monsterIds[i] = m == null ? "" : m.getClass().getName();
                s.monsterHp[i] = m == null ? 0 : m.currentHealth;
                s.monsterMax[i] = m == null ? 0 : m.maxHealth;
                s.monsterBlock[i] = m == null ? 0 : m.currentBlock;
            }
        }
        return s;
    }

    private static String[] lastIds = new String[0];
    private static int[] lastUpg = new int[0];

    private static void fill(CardGroup g, boolean unused) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        java.util.List<Integer> upg = new java.util.ArrayList<Integer>();
        if (g != null) {
            for (int i = 0; i < g.size(); i++) {
                AbstractCard c = g.group.get(i);
                ids.add(c == null ? "" : c.cardID);
                upg.add(Integer.valueOf(c == null ? 0 : c.timesUpgraded));
            }
        }
        lastIds = ids.toArray(new String[ids.size()]);
        lastUpg = new int[upg.size()];
        for (int i = 0; i < upg.size(); i++) {
            lastUpg[i] = upg.get(i).intValue();
        }
    }

    private static String classOf(AbstractPlayer p) {
        try {
            switch (p.chosenClass) {
                case THE_SILENT:
                    return "com.megacrit.cardcrawl.characters.TheSilent";
                case DEFECT:
                    return "com.megacrit.cardcrawl.characters.Defect";
                case WATCHER:
                    return "com.megacrit.cardcrawl.characters.Watcher";
                default:
                    return "com.megacrit.cardcrawl.characters.Ironclad";
            }
        } catch (Throwable t) {
            return "com.megacrit.cardcrawl.characters.Ironclad";
        }
    }

    private static String[] relicsOf(AbstractPlayer p) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (p.relics != null) {
            for (int i = 0; i < p.relics.size(); i++) {
                AbstractRelic r = p.relics.get(i);
                if (r != null) {
                    out.add(r.relicId);
                }
            }
        }
        return out.toArray(new String[out.size()]);
    }

    /** A fingerprint over exactly the fields this class reproduces. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("turn=").append(turn).append(" hp=").append(hp).append('/').append(maxHealth)
                .append(" b=").append(block).append(" e=").append(energy);
        appendPile(sb, "H", handIds, handUpg);
        appendPile(sb, "D", drawIds, drawUpg);
        appendPile(sb, "X", discardIds, discardUpg);
        appendPile(sb, "E", exhaustIds, exhaustUpg);
        for (int i = 0; i < monsterIds.length; i++) {
            sb.append(" | M").append(i).append('=').append(monsterIds[i]).append(':')
                    .append(monsterHp[i]).append('/').append(monsterMax[i])
                    .append(" b=").append(monsterBlock[i]);
        }
        return sb.toString();
    }

    /** Fingerprint of the CURRENT live state, same format as {@link #fingerprint()}. */
    public static String liveFingerprint() {
        return capture().fingerprint();
    }

    private static void appendPile(StringBuilder sb, String name, String[] ids, int[] upg) {
        sb.append(' ').append(name).append('=');
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids[i]);
            if (upg != null && i < upg.length && upg[i] > 0) {
                sb.append('+').append(upg[i]);
            }
        }
    }

    /**
     * Rebuilds the fight described by this spec, then restores the captured state.
     *
     * <p>The rebuild uses {@link Synthetic} (the same path synthetic fights use) so nothing about
     * the fight setup is special-cased for live capture.
     */
    public void rebuild() {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = 1L;
        spec.playerClass = playerClass;
        spec.maxHealth = maxHealth;
        spec.ascension = ascension;
        spec.relics = relics;
        java.util.List<String> all = new java.util.ArrayList<String>();
        addAll(all, handIds);
        addAll(all, drawIds);
        addAll(all, discardIds);
        addAll(all, exhaustIds);
        spec.deck = all.isEmpty() ? new String[]{"Strike_R"} : all.toArray(new String[all.size()]);
        spec.monsters = monsterIds.length == 0
                ? new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"} : monsterIds;
        Synthetic.build(spec, new Synthetic.SyntheticStep());
        AbstractPlayer p = AbstractDungeon.player;
        p.currentHealth = hp;
        p.currentBlock = block;
        EnergyPanel.totalCount = energy;
        GameActionManager.turn = turn;
        restore(p.hand, handIds, handUpg);
        restore(p.drawPile, drawIds, drawUpg);
        restore(p.discardPile, discardIds, discardUpg);
        restore(p.exhaustPile, exhaustIds, exhaustUpg);
        java.util.List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
        for (int i = 0; i < ms.size() && i < monsterHp.length; i++) {
            AbstractMonster m = ms.get(i);
            if (m == null) {
                continue;
            }
            m.maxHealth = monsterMax[i];
            m.currentHealth = monsterHp[i];
            m.currentBlock = monsterBlock[i];
        }
    }

    private static void addAll(java.util.List<String> out, String[] ids) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null && !ids[i].isEmpty()) {
                out.add(ids[i]);
            }
        }
    }

    private static void restore(CardGroup g, String[] ids, int[] upg) {
        if (g == null) {
            return;
        }
        g.group.clear();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == null || ids[i].isEmpty()) {
                continue;
            }
            AbstractCard c = CardLibrary.getCard(ids[i]);
            if (c == null) {
                Log.warn("live spec: unknown card " + ids[i]);
                continue;
            }
            AbstractCard copy = c.makeCopy();
            int level = upg != null && i < upg.length ? upg[i] : 0;
            for (int u = 0; u < level; u++) {
                copy.upgrade();
            }
            g.group.add(copy);
        }
    }
}