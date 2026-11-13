package sts1solver.search;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
import com.megacrit.cardcrawl.rooms.MonsterRoomElite;
import java.util.ArrayList;
import java.util.List;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

/**
 * L1-L3 (user m16960 / m17363): what the post-combat reward screen is worth for THIS fight.
 *
 * <p>The problem: a winning terminal used to score exactly the HP it kept and the growth it realized.
 * A Looter's 20 stolen gold only reaches {@code room.rewards} (the reward screen), never
 * {@code player.gold}, so "kill it and take the gold back" and "let it run" scored the same -- the
 * plan in solver.log:1250 deliberately ended the turn on an 8 HP Looter and let it escape. The escape
 * terminal's {@link EscapePolicy#forfeitPoints()} existed but was never wired in, and the room's own
 * 10-20 normal-fight gold is skipped by the engine whenever a monster escaped
 * ({@code AbstractRoom.update} line 309: {@code !getMonsters().haveMonstersEscaped()}).
 *
 * <p>L2 (the user's chosen order): run the ENGINE's own settlement instead of re-implementing it. The
 * search already holds the finished fight inside an installed sandbox world, so this class sets
 * {@code isBattleOver} + a negative {@code endBattleTimer} and pumps {@code room.update()} until the
 * room reaches {@code COMPLETE}: the engine then rolls the real gold, drops the real relic and potion,
 * and its own kill counters and RNG advance exactly as they would live. Card rewards are not created
 * by {@code room.update()} (they are made by the reward screen), so {@code setupItemReward()} is called
 * on the sandbox's own screen clone -- the method only rebuilds {@code screen.rewards},
 * {@code positionRewards()} and {@code proceedButton.show()}, it does not open a screen or play a
 * sound.
 *
 * <p>Nothing may be heard or seen (user m17363): {@code rewardAllowed = false} makes the engine skip
 * both {@code combatRewardScreen.open()} and {@code SaveAndContinue.save()}, the "VICTORY" sound is
 * swallowed by {@code SfxMute} (which covers every sound inside a world install window), and
 * {@code isScreenUp} is held true during the pump so the room cannot run player input.
 *
 * <p>L3 fallback: if any of that throws (unknown mod room, missing screen), the expected values from
 * {@link EscapePolicy#expectedGold} and {@link LootBook} are used and a WARN is logged once -- the
 * search never fails because the reward screen could not be reproduced.
 *
 * <p>Everything touched is restored in {@code finally} (room phase/timer/flags, reward list and each
 * item's gold, the five RNG counters, the five relic pools, the kill counters, the screen's reward
 * list, the escaped flags). The pools and kill counters are real statics that are NOT per-world
 * mirrors, so leaving them advanced would change the live game's relic rolls.
 */
public final class PostCombatLoot {

    /** One fight's reward-screen value, in the units the score uses. */
    public static final class Loot {
        /** Gold the reward screen hands over (normal/stolen gold plus relic bonus gold). */
        public final int gold;
        /** How many card rewards the screen would offer. */
        public final int cards;
        /** HP value of those cards. */
        public final int cardHp;
        /** HP value of the relic reward (0 when none). */
        public final int relicHp;
        /** How many potion rewards. */
        public final int potions;
        /** HP value of those potions. */
        public final int potionHp;
        /** Sum of {@link #cardHp} + {@link #relicHp} + {@link #potionHp}. */
        public final int hp;
        /** True when the numbers came from the engine's own settlement, false for the formula. */
        public final boolean engine;

        /** Public so a gate can price an arbitrary reward list without a fight. */
        public Loot(int gold, int cards, int cardHp, int relicHp, int potions, int potionHp, boolean engine) {
            this.gold = Math.max(0, gold);
            this.cards = Math.max(0, cards);
            this.cardHp = Math.max(0, cardHp);
            this.relicHp = Math.max(0, relicHp);
            this.potions = Math.max(0, potions);
            this.potionHp = Math.max(0, potionHp);
            this.hp = this.cardHp + this.relicHp + this.potionHp;
            this.engine = engine;
        }

        /** Nothing worth counting: nothing is logged for this win either (m17808: no panel line). */
        public boolean isEmpty() {
            return gold <= 0 && cards <= 0 && potions <= 0 && hp <= 0;
        }

        /** Score for this loot: HP value at the solver's own rate, gold at {@link Solver#goldRate()}. */
        public int points() {
            return hp * StrategicDeficit.POINTS_PER_HP + gold * Solver.goldRate();
        }


        /** Log form, including whether the engine or the fallback produced it. */
        public String describe() {
            return (engine ? "engine" : "formula") + " gold=" + gold + " cards=" + cards
                    + " cardHp=" + cardHp + " relicHp=" + relicHp + " potions=" + potions
                    + " potionHp=" + potionHp + " total=" + hp + " HP";
        }
    }

    /** Frames pumped before giving up on the room transition (the delta is patched, 1 is normally enough). */
    private static final int MAX_FRAMES = 8;

    private static final String[] RNG_FIELDS =
            {"miscRng", "treasureRng", "cardRng", "potionRng", "relicRng"};
    private static final String[] POOL_FIELDS =
            {"commonRelicPool", "uncommonRelicPool", "rareRelicPool", "shopRelicPool", "bossRelicPool"};

    private PostCombatLoot() {
    }

    /**
     * Read this fight's reward value. Must be called while the node's world is installed and the
     * battle is already over (i.e. from {@code Solver.readChoice} on a winning node). Never throws.
     *
     * @return the value, or null when even the formula could not be evaluated
     */
    public static Loot settle() {
        AbstractRoom room = null;
        try {
            room = AbstractDungeon.getCurrRoom();
        } catch (Throwable ignored) {
            room = null;
        }
        if (room == null) {
            return null;
        }
        boolean boss = room instanceof MonsterRoomBoss;
        boolean elite = room instanceof MonsterRoomElite;
        boolean ordinary = room instanceof MonsterRoom && !boss && !elite;

        Snapshot snap = new Snapshot(room, boss, elite);
        Loot out = null;
        try {
            snap.begin();
            if (claimEscapedMonsters()) {
                for (int i = 0; i < MAX_FRAMES; i++) {
                    try {
                        room.update();
                    } catch (Throwable t) {
                        Log.warnEvery("loot-pump", 10000L, "post-combat loot: room.update() threw (" + t
                                + "); the expected values are used instead");
                        break;
                    }
                    if (room.phase == AbstractRoom.RoomPhase.COMPLETE) {
                        break;
                    }
                }
            }
            if (room.phase == AbstractRoom.RoomPhase.COMPLETE) {
                boolean screenReady = false;
                try {
                    if (AbstractDungeon.combatRewardScreen != null) {
                        AbstractDungeon.combatRewardScreen.setupItemReward();
                        screenReady = true;
                    }
                } catch (Throwable t) {
                    Log.warnEvery("loot-screen", 10000L, "post-combat loot: setupItemReward() threw (" + t
                            + "); card rewards are priced by the formula");
                }
                List<RewardItem> items = screenReady
                        ? AbstractDungeon.combatRewardScreen.rewards : room.rewards;
                out = read(items, boss, elite);
                if (!screenReady) {
                    int cards = expectedCards(ordinary);
                    out = new Loot(out.gold, cards, cards * LootBook.cardHp(boss, elite), out.relicHp,
                            out.potions, out.potionHp, true);
                }
            }
        } catch (Throwable t) {
            Log.warnEvery("loot-settle", 10000L, "post-combat loot: engine settlement failed (" + t
                    + "); the expected values are used instead");
            out = null;
        } finally {
            try {
                snap.restore();
            } catch (Throwable t) {
                Log.warn("post-combat loot: could not restore the sandbox fully (" + t + ")");
            }
        }
        if (out == null) {
            out = formula(boss, elite, ordinary);
        }
        return out;
    }

    /**
     * L3: the expected reward without running the engine, for when the settlement above failed.
     * Gold is the roll's mid-point (and zero for an ordinary fight with a monster that escaped,
     * exactly like {@code AbstractRoom.update} line 309); cards and relics use {@link LootBook}.
     */
    public static Loot formula(boolean boss, boolean elite, boolean ordinary) {
        int ascension = 0;
        try {
            ascension = AbstractDungeon.ascensionLevel;
        } catch (Throwable ignored) {
            ascension = 0;
        }
        int gold = 0;
        try {
            boolean escaped = monstersEscaped();
            gold = (ordinary && escaped) ? 0 : EscapePolicy.expectedGold(boss, elite, ascension);
        } catch (Throwable t) {
            gold = EscapePolicy.expectedGold(boss, elite, ascension);
        }
        int cards = expectedCards(ordinary);
        int cardHp = cards * LootBook.cardHp(boss, elite);
        int relicHp = (elite || boss) ? LootBook.relicExpectedHp() : 0;
        int potionHp = (boss || elite || ordinary) ? Math.round(0.4f * PotionBook.STANDARD_HP) : 0;
        Log.warnEvery("loot-formula", 10000L, "post-combat loot: expected values used (gold=" + gold
                + " cards=" + cards + " relicHp=" + relicHp + " potionHp=" + potionHp + ")");
        return new Loot(gold, cards, cardHp, relicHp, potionHp > 0 ? 1 : 0, potionHp, false);
    }

    /** Card rewards the base game offers (Prayer Wheel adds a second roll on an ordinary fight). */
    private static int expectedCards(boolean ordinary) {
        int n = 1;
        if (ordinary) {
            try {
                AbstractPlayer p = AbstractDungeon.player;
                if (p != null && p.hasRelic("Prayer Wheel")) {
                    n++;
                }
            } catch (Throwable ignored) {
                // a player we cannot read simply does not add the extra card
            }
        }
        return n;
    }

    private static Loot read(List<RewardItem> items, boolean boss, boolean elite) {
        int gold = 0;
        int cards = 0;
        int cardHp = 0;
        int relicHp = 0;
        int potions = 0;
        int potionHp = 0;
        if (items != null) {
            for (RewardItem r : items) {
                if (r == null || r.type == null) {
                    continue;
                }
                switch (r.type) {
                    case GOLD:
                    case STOLEN_GOLD:
                        gold += Math.max(0, r.goldAmt) + Math.max(0, r.bonusGold);
                        break;
                    case CARD: {
                        // One CARD reward item is ONE card the player takes: r.cards is the list of
                        // options the screen offers (usually 3), not the number of cards gained.
                        // Prayer Wheel opens a second item, so counting items is what matches the
                        // fallback's expectedCards().
                        cards += 1;
                        cardHp += LootBook.cardHp(boss, elite);
                        break;
                    }
                    case RELIC:
                        relicHp += r.relic == null ? LootBook.relicHp(null) : LootBook.relicHp(r.relic.tier);
                        break;
                    case POTION:
                        potions++;
                        potionHp += r.potion == null ? PotionBook.STANDARD_HP : PotionBook.valueHp(r.potion);
                        break;
                    default:
                        // keys and other unpriced reward types
                        break;
                }
            }
        }
        return new Loot(gold, cards, cardHp, relicHp, potions, potionHp, true);
    }

    /** True when at least one monster left the fight (live: {@code haveMonstersEscaped()}). */
    private static boolean monstersEscaped() {
        try {
            if (AbstractDungeon.getMonsters() == null || AbstractDungeon.getMonsters().monsters == null) {
                return false;
            }
            for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
                if (m != null && (m.isEscaping || m.escaped)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /**
     * The sandbox never runs the escape animation, so {@code escaped} stays false and the engine
     * would treat an escaped fight as a clean kill (gold would be granted, {@code haveMonstersEscaped}
     * would be false). Mark them escaped for the pump; {@link Snapshot#restore()} clears them again.
     */
    private static boolean claimEscapedMonsters() {
        try {
            if (AbstractDungeon.getMonsters() == null || AbstractDungeon.getMonsters().monsters == null) {
                return true;
            }
            for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
                if (m != null && m.isEscaping && !m.escaped) {
                    m.escaped = true;
                }
            }
        } catch (Throwable ignored) {
            // without the flags the engine still settles, it just may grant the room's gold
        }
        return true;
    }

    /** Small guard so the player's end-turn flag can be held for the pump without NPEs. */
    private static final class AbstractPlayerHolder {
        private AbstractPlayerHolder() {
        }

        static boolean endingTurn() {
            try {
                return AbstractDungeon.player != null && AbstractDungeon.player.isEndingTurn;
            } catch (Throwable ignored) {
                return false;
            }
        }

        static void setEndingTurn(boolean value) {
            try {
                if (AbstractDungeon.player != null) {
                    AbstractDungeon.player.isEndingTurn = value;
                }
            } catch (Throwable ignored) {
                // without a player there is no end-turn to hold
            }
        }
    }

    /** Everything the settlement may touch, saved once and restored in {@code finally}. */
    private static final class Snapshot {
        private final AbstractRoom room;
        private final boolean battleOver;
        private final AbstractRoom.RoomPhase phase;
        private final Object endBattleTimer;
        private final boolean rewardAllowed;
        private final boolean screenUp;
        private final boolean endingTurn;
        private final ArrayList<RewardItem> roomRewards;
        private final int[] roomGold;
        private final int[] roomBonus;
        private final ArrayList<RewardItem> screenRewards;
        private final int[] rngCounters = new int[RNG_FIELDS.length];
        private final ArrayList<ArrayList<String>> pools = new ArrayList<ArrayList<String>>();
        private final int monstersSlain;
        private final int elites1;
        private final int elites2;
        private final int elites3;
        private final int elitesModded;

        Snapshot(AbstractRoom room, boolean boss, boolean elite) {
            this.room = room;
            this.battleOver = room.isBattleOver;
            this.phase = room.phase;
            this.endBattleTimer = Reflect.get(AbstractRoom.class, "endBattleTimer", room);
            this.rewardAllowed = room.rewardAllowed;
            this.screenUp = AbstractDungeon.isScreenUp;
            this.endingTurn = AbstractPlayerHolder.endingTurn();
            ArrayList<RewardItem> rewards = room.rewards;
            this.roomRewards = rewards == null ? null : new ArrayList<RewardItem>(rewards);
            this.roomGold = new int[roomRewards == null ? 0 : roomRewards.size()];
            this.roomBonus = new int[this.roomGold.length];
            for (int i = 0; i < this.roomGold.length; i++) {
                RewardItem r = roomRewards.get(i);
                this.roomGold[i] = r == null ? 0 : r.goldAmt;
                this.roomBonus[i] = r == null ? 0 : r.bonusGold;
            }
            ArrayList<RewardItem> s = null;
            try {
                s = AbstractDungeon.combatRewardScreen == null ? null
                        : AbstractDungeon.combatRewardScreen.rewards;
            } catch (Throwable ignored) {
                s = null;
            }
            this.screenRewards = s == null ? null : new ArrayList<RewardItem>(s);
            for (int i = 0; i < RNG_FIELDS.length; i++) {
                this.rngCounters[i] = rngCounter(RNG_FIELDS[i]);
            }
            for (String field : POOL_FIELDS) {
                ArrayList<String> copy = new ArrayList<String>();
                try {
                    Object v = Reflect.get(AbstractDungeon.class, field, null);
                    if (v instanceof List) {
                        for (Object o : (List<?>) v) {
                            copy.add(String.valueOf(o));
                        }
                    }
                } catch (Throwable ignored) {
                    // an unreadable pool is left alone
                }
                this.pools.add(copy);
            }
            this.monstersSlain = CardCrawlGame.monstersSlain;
            this.elites1 = CardCrawlGame.elites1Slain;
            this.elites2 = CardCrawlGame.elites2Slain;
            this.elites3 = CardCrawlGame.elites3Slain;
            this.elitesModded = CardCrawlGame.elitesModdedSlain;
        }

        /** Put the room into the state the engine settles from, without letting it open or save anything. */
        void begin() {
            room.isBattleOver = true;
            room.rewardAllowed = false;
            AbstractDungeon.isScreenUp = true;
            // A room.update() with the end-turn button held would call endTurn() even while a screen is
            // up (AbstractRoom.java:263), so the flag is cleared for the pump and put back after it.
            AbstractPlayerHolder.setEndingTurn(false);
            if (this.endBattleTimer != null) {
                Reflect.set(AbstractRoom.class, "endBattleTimer", room, Float.valueOf(-1.0f));
            }
        }

        void restore() {
            if (roomRewards != null) {
                room.rewards = roomRewards;
                for (int i = 0; i < roomGold.length && i < roomRewards.size(); i++) {
                    RewardItem r = roomRewards.get(i);
                    if (r != null) {
                        r.goldAmt = roomGold[i];
                        r.bonusGold = roomBonus[i];
                    }
                }
            }
            try {
                if (AbstractDungeon.combatRewardScreen != null) {
                    AbstractDungeon.combatRewardScreen.rewards = screenRewards;
                }
            } catch (Throwable ignored) {
                // the screen clone is per world; failing to reset it must not mask the loot
            }
            room.isBattleOver = battleOver;
            room.phase = phase;
            room.rewardAllowed = rewardAllowed;
            AbstractDungeon.isScreenUp = screenUp;
            AbstractPlayerHolder.setEndingTurn(endingTurn);
            if (endBattleTimer instanceof Number) {
                Reflect.set(AbstractRoom.class, "endBattleTimer", room,
                        Float.valueOf(((Number) endBattleTimer).floatValue()));
            }
            for (int i = 0; i < RNG_FIELDS.length; i++) {
                setRngCounter(RNG_FIELDS[i], rngCounters[i]);
            }
            for (int i = 0; i < POOL_FIELDS.length; i++) {
                try {
                    Object v = Reflect.get(AbstractDungeon.class, POOL_FIELDS[i], null);
                    if (v instanceof ArrayList) {
                        @SuppressWarnings("unchecked")
                        ArrayList<String> pool = (ArrayList<String>) v;
                        pool.clear();
                        pool.addAll(pools.get(i));
                    }
                } catch (Throwable ignored) {
                    Log.warnEvery("loot-pool", 10000L, "post-combat loot: relic pool " + POOL_FIELDS[i]
                            + " could not be restored");
                }
            }
            CardCrawlGame.monstersSlain = monstersSlain;
            CardCrawlGame.elites1Slain = elites1;
            CardCrawlGame.elites2Slain = elites2;
            CardCrawlGame.elites3Slain = elites3;
            CardCrawlGame.elitesModdedSlain = elitesModded;
            try {
                if (AbstractDungeon.getMonsters() != null && AbstractDungeon.getMonsters().monsters != null) {
                    for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
                        if (m != null && m.isEscaping) {
                            m.escaped = false;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // escaped flags are presentation state; the search re-reads them from isEscaping
            }
        }

        private static int rngCounter(String field) {
            try {
                Object v = Reflect.get(AbstractDungeon.class, field, null);
                if (v instanceof com.megacrit.cardcrawl.random.Random) {
                    return ((com.megacrit.cardcrawl.random.Random) v).counter;
                }
            } catch (Throwable ignored) {
                // unknown RNG field: nothing to restore
            }
            return -1;
        }

        private static void setRngCounter(String field, int value) {
            if (value < 0) {
                return;
            }
            try {
                Object v = Reflect.get(AbstractDungeon.class, field, null);
                if (v instanceof com.megacrit.cardcrawl.random.Random) {
                    ((com.megacrit.cardcrawl.random.Random) v).counter = value;
                }
            } catch (Throwable ignored) {
                // see rngCounter
            }
        }
    }
}
