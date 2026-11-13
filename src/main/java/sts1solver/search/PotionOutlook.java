package sts1solver.search;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import sts1solver.mirror.World;
import sts1solver.util.Log;

/**
 * What the potion reward after THIS fight will be (P2-3b).
 *
 * <p>The reference solver has {@code PotionRewardOutlook}: it clones the reward RNG at root capture and
 * replays the reward draws to learn whether a potion will drop and which one. Vanilla rolls one potion
 * per combat room, and the reward generation runs on an RNG stream that combat does not touch.
 *
 * <p>This class does the same thing in the most faithful way available: it forks the live fight, calls
 * the game's OWN {@code MonsterRoom.dropReward()} on the copy, and reads the potion out of the generated
 * rewards. No rule is re-implemented; whatever the game would roll is what we forecast.
 *
 * <p>The outlook only matters when the potion belt is FULL: a potion spent in this fight frees the slot
 * the reward would otherwise have nowhere to go, so the reward's value returns as a credit against the
 * strategic cost of spending. With an open slot the reward lands either way and the credit is zero.
 * Sozu blocks procurement entirely and also zeroes the credit.
 */
public final class PotionOutlook {

    /** Score units for one predicted potion (1 HP is worth 2000 in {@link Solver#score}). */
    public static final int POTION_HP_CREDIT = 8000;

    private static boolean captured;
    /** True when the reward replay actually ran (even if it rolled no potion). */
    private static boolean known;
    private static boolean beltFull;
    private static boolean sozu;
    private static boolean drop;
    private static String potionId = "";

    private PotionOutlook() {
    }

    public static void reset() {
        captured = false;
        known = false;
        beltFull = false;
        sozu = false;
        drop = false;
        potionId = "";
    }

    /** Reads the outlook once per solve, before the search takes over the live game. */
    public static void capture() {
        if (captured) {
            return;
        }
        captured = true;
        try {
            if (AbstractDungeon.player == null) {
                return;
            }
            int slots = 0;
            int filled = 0;
            for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
                AbstractPotion p = AbstractDungeon.player.potions.get(i);
                slots++;
                if (p != null && !(p instanceof PotionSlot)) {
                    filled++;
                }
            }
            beltFull = slots > 0 && filled == slots;
            for (int i = 0; i < AbstractDungeon.player.relics.size(); i++) {
                AbstractRelic r = AbstractDungeon.player.relics.get(i);
                if (r != null && "Sozu".equals(r.getClass().getSimpleName())) {
                    sozu = true;
                }
            }
            if (sozu) {
                Log.info("potion outlook: Sozu present, no procurement credit");
                return;
            }
            // Replay the game's own reward generation on a COPY of the fight.
            World w = World.captureLive();
            boolean installed = false;
            try {
                w.install();
                installed = true;
                AbstractRoom room = AbstractDungeon.getCurrRoom();
                if (room == null) {
                    return;
                }
                room.dropReward();
                known = true;
                for (int i = 0; i < room.rewards.size(); i++) {
                    RewardItem item = room.rewards.get(i);
                    if (item != null && item.type == RewardItem.RewardType.POTION
                            && item.potion != null) {
                        drop = true;
                        potionId = item.potion.getClass().getSimpleName();
                        break;
                    }
                }
            } finally {
                if (installed) {
                    try {
                        w.readBack();
                        w.uninstall();
                    } catch (Throwable ignored) {
                        World.forceRestore();
                    }
                }
            }
            Log.info("potion outlook: beltFull=" + beltFull + " drop=" + drop
                    + (potionId.isEmpty() ? "" : " potion=" + potionId));
        } catch (Throwable t) {
            // A broken forecast must never break the search: fall back to no credit.
            World.forceRestore();
            drop = false;
            potionId = "";
            Log.warn("potion outlook failed: " + t);
        }
    }

    /** True when the forecast was actually read (the reward replay ran). */
    public static boolean known() {
        return known;
    }

    public static boolean beltFull() {
        return beltFull;
    }

    public static boolean sozu() {
        return sozu;
    }

    public static boolean forecastDrop() {
        return drop;
    }

    public static String forecastPotionId() {
        return potionId;
    }

    /**
     * Credit for spending {@code potionsUsed} potions, when the belt is full and a drop is confirmed.
     *
     * <p>One freed slot receives at most one reward, so the credit is applied once, not per potion.
     */
    public static int replacementHpCredit(int potionsUsed) {
        if (!beltFull || sozu || !drop || potionsUsed <= 0) {
            return 0;
        }
        return POTION_HP_CREDIT;
    }

}