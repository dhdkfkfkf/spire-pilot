package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import sts1solver.reflect.Reflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * B182 (user m00001/m00148): the hand's glow ring is live-session presentation, and the sandbox has
 * no use for it -- so it does not get a copy either.
 *
 * <p>What leaked: {@code AbstractCard.glowList} (AbstractCard.java:162) holds
 * {@code CardGlowBorder} objects (vfx/cardManip/CardGlowBorder.java), one per 0.3 s pulse, each with
 * {@code duration = 1.2f}. Those are {@code AbstractGameEffect}s, and ClonePolicy shares every
 * effect by reference, so the sandbox's hand pointed at the <b>live</b> rings.
 * {@code AbstractCard.stopGlowing()} (:1140) then divides the duration of everything in the list by
 * 5 -- and every simulated draw or energy gain runs {@code hand.glowCheck()}
 * (AbstractPlayer.java:1337/:1539, UseCardAction.java:101, HandCheckAction.java:17). During a search
 * the live ring therefore went 1.2 s -> 0.24 s -> gone: the card kept its static
 * {@code isGlowing} border but lost the pulse, which is exactly what the user saw.
 *
 * <p>Why emptying beats copying: the sandbox never renders, and the queue is only advanced from the
 * render path ({@code updateGlow} runs from {@code renderCard} :1064 / {@code renderInLibrary} :1017)
 * -- nothing reads it inside a simulated window. Copying would pay ~40 new objects on every fork to
 * hold rings nobody updates; clearing costs one field compare per card on one install per world.
 *
 * <p>The field is replaced, never cleared in place: the lists are the sandbox's own copies, but if
 * some card ever turned out to be a shared reference, a replacement only restarts the live pulse
 * (the renderer re-adds it within 0.3 s) instead of emptying the live queue for good.
 */
public final class GlowReset {
    private static Field glowList;
    private static int scanned;
    private static int dropped;
    private static int total;

    private GlowReset() {
    }

    /** Drops the sandbox's queued glow rings. Returns how many queues were emptied. */
    public static int drop() {
        scanned = 0;
        dropped = 0;
        AbstractPlayer p = player();
        Field f = field();
        if (p == null || f == null) {
            return 0;
        }
        sweep(f, p.hand);
        sweep(f, p.drawPile);
        sweep(f, p.discardPile);
        sweep(f, p.exhaustPile);
        sweep(f, p.limbo);
        one(f, p.cardInUse);
        queue(f);
        total += dropped;
        return dropped;
    }

    /** Cards the last {@link #drop()} looked at. */
    public static int scanned() {
        return scanned;
    }

    /** Queues the last {@link #drop()} emptied (0 when nothing had a ring). */
    public static int dropped() {
        return dropped;
    }

    public static int totalDropped() {
        return total;
    }

    private static void queue(Field f) {
        List<CardQueueItem> q;
        try {
            q = AbstractDungeon.actionManager == null ? null : AbstractDungeon.actionManager.cardQueue;
        } catch (Throwable t) {
            return;
        }
        if (q == null) {
            return;
        }
        for (int i = 0, n = q.size(); i < n; i++) {
            CardQueueItem item = q.get(i);
            if (item != null) {
                one(f, item.card);
            }
        }
    }

    private static void sweep(Field f, CardGroup g) {
        if (g == null || g.group == null) {
            return;
        }
        for (int i = 0, n = g.group.size(); i < n; i++) {
            one(f, g.group.get(i));
        }
    }

    private static void one(Field f, AbstractCard c) {
        if (c == null) {
            return;
        }
        ++scanned;
        Object v = Reflect.get(f, c);
        if (!(v instanceof List) || ((List<?>) v).isEmpty()) {
            return;
        }
        Reflect.set(f, c, new ArrayList<Object>(0));
        ++dropped;
    }

    private static Field field() {
        if (glowList == null) {
            glowList = Reflect.find(AbstractCard.class, "glowList");
        }
        return glowList;
    }

    private static AbstractPlayer player() {
        try {
            return AbstractDungeon.player;
        } catch (Throwable t) {
            return null;
        }
    }
}
