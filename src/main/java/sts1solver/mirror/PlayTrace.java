package sts1solver.mirror;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

/**
 * B147 (user m12327): every card the fight actually plays, in order, with the random position it
 * was played at -- recorded on the ENGINE side so the live fight and the sandbox produce the same
 * kind of evidence.
 *
 * <p>Why this exists: the plan stores "the fight played these cards while this step ran"
 * ({@link sts1solver.search.Action#autoPlays}) by diffing the hand from the outside. A diff cannot
 * say WHICH of two 防御 the fight played, cannot say what order they came in, cannot see a card the
 * fight played twice (奶龙 replays the same card), and cannot say where the random stream stood when
 * the choice was made. 唐 (TangPower) picks a random hand card at the start of every turn, so when
 * the two worlds part, the question is always "which play was the first one that differed, and on
 * which random position" -- that is exactly what this trace answers.
 *
 * <p>The patch is on {@code AbstractPlayer.useCard}, which every play goes through (the player's
 * own clicks, autoplay actions, mod actions that queue a card). It records nothing but a ring
 * entry: no allocation per play after the buffer warms up, and it runs in the search's own
 * simulation too, so a plan can carry its side of the trace.
 *
 * <p>Entries are thread-tagged: the solver runs searches on the game thread today, but a worker
 * search would otherwise mix its sandbox plays into the live window's trace.
 */
public final class PlayTrace {

    private PlayTrace() {
    }

    /** How many plays are kept; enough for several turns, small enough to stay free. */
    private static final int CAP = 512;

    private static final class Entry {
        volatile long seq;
        volatile long thread;
        volatile int turn;
        volatile String id = "";
        volatile int cost;
        volatile boolean auto;
        volatile String rng = "";
    }

    private static final Entry[] RING = new Entry[CAP];

    private static volatile long seq;
    private static volatile boolean recording = true;

    /** A marker for "everything from here on"; pass it to {@link #describe(long)} or {@link #count(long)}. */
    public static long mark() {
        return seq;
    }

    /** Turn recording off/on (gates that drive the engine by hand can keep their noise out). */
    public static void setRecording(boolean on) {
        recording = on;
    }

    /** How many plays are on the trace after {@code mark}, on this thread. */
    public static int count(long mark) {
        long from = mark < 0 ? 0 : mark;
        if (from < seq - CAP) {
            from = seq - CAP;
        }
        long tid = Thread.currentThread().getId();
        int n = 0;
        for (long s = from + 1; s <= seq; ++s) {
            Entry e = RING[(int) (s % CAP)];
            if (e != null && e.seq == s && e.thread == tid) {
                ++n;
            }
        }
        return n;
    }

    /**
     * The plays after {@code mark}, as {@code 打击+@t3#c=12,s=4,a=1,m=0,h=0[auto] | ...}.
     * Empty when nothing was played.
     */
    public static String describe(long mark) {
        return collect(mark);
    }

    /**
     * B149 (user m12829): the last {@code n} plays on this thread, oldest first. Used when a screen
     * opens with no planned answer -- the trace names the cards that led to it, which a hand diff
     * cannot (two 防御 are one id, and a mod play that re-queues the same card looks like one play).
     */
    public static String recent(int n) {
        if (n <= 0) {
            return "";
        }
        long tid = Thread.currentThread().getId();
        java.util.ArrayList<Entry> picked = new java.util.ArrayList<Entry>(n);
        for (long s = seq; s >= 1 && picked.size() < n && s > seq - CAP; --s) {
            Entry e = RING[(int) (s % CAP)];
            if (e == null || e.seq != s || e.thread != tid) {
                continue;
            }
            picked.add(e);
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = picked.size() - 1; i >= 0; --i) {
            Entry e = picked.get(i);
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e.id).append("@t").append(e.turn).append("#").append(e.rng);
            if (e.cost >= 0) {
                sb.append("/").append(e.cost).append("e");
            }
            if (e.auto) {
                sb.append("[auto]");
            }
        }
        return sb.toString();
    }

    private static String collect(long mark) {
        long from = mark < 0 ? 0 : mark;
        if (from < seq - CAP) {
            from = seq - CAP;
        }
        long tid = Thread.currentThread().getId();
        StringBuilder sb = new StringBuilder(64);
        int shown = 0;
        int total = 0;
        for (long s = from + 1; s <= seq; ++s) {
            Entry e = RING[(int) (s % CAP)];
            if (e == null || e.seq != s || e.thread != tid) {
                continue;
            }
            ++total;
            if (shown > 0) {
                sb.append(" | ");
            }
            if (shown >= 24) {
                sb.append("...");
                break;
            }
            ++shown;
            sb.append(e.id).append("@t").append(e.turn).append("#").append(e.rng);
            if (e.cost >= 0) {
                sb.append("/").append(e.cost).append("e");
            }
            if (e.auto) {
                sb.append("[auto]");
            }
        }
        return sb.toString();
    }

    static void record(AbstractCard card) {
        if (!recording || card == null) {
            return;
        }
        try {
            long s = seq + 1;
            int i = (int) (s % CAP);
            Entry e = RING[i];
            if (e == null) {
                e = new Entry();
                RING[i] = e;
            }
            e.thread = Thread.currentThread().getId();
            e.turn = GameActionManager.turn;
            e.id = card.cardID + (card.upgraded ? "+" : "");
            e.cost = card.costForTurn;
            e.auto = card.isInAutoplay;
            e.rng = RngStamp.compact();
            e.seq = s;
            seq = s;
        } catch (Throwable ignored) {
            // a trace that cannot be written must never break a card play
        }
    }

    /** {@code AbstractPlayer.useCard}: the single door every card play walks through. */
    @SpirePatch(clz = AbstractPlayer.class, method = "useCard",
            paramtypez = {AbstractCard.class, AbstractMonster.class, int.class})
    public static final class UseCardPatch {
        @SpirePostfixPatch
        public static void after(AbstractPlayer __instance, AbstractCard card, AbstractMonster target,
                                 int energyOnUse) {
            record(card);
        }
    }
}
