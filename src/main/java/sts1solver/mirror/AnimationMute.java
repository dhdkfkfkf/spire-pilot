package sts1solver.mirror;

import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import java.util.IdentityHashMap;
import java.util.Map;
import sts1solver.util.Log;

/**
 * Keeps the search from animating the live game.
 *
 * <p>{@link ClonePolicy} shares every {@code com.esotericsoftware.spine} object between the live game
 * and a sandbox world (the skeletons are read-only for combat logic, and cloning them per node would
 * cost more than the search itself). One of those shared objects is the creature's
 * {@link AnimationState}: a sandbox monster that attacks calls {@code state.setAnimation("attack")}
 * on the <em>live</em> state, and the live game's {@code render()} then plays that animation on the
 * real monster. That is why monsters twitched while a plan was being computed (B34).
 *
 * <p>The fix is cheap and keeps the sharing: after a sandbox world is installed, every sandbox
 * creature gets its own throwaway {@code AnimationState} for the same {@code AnimationStateData}. The
 * sandbox never renders, so the stub is never advanced or applied; it only absorbs the sandbox's
 * {@code setAnimation}/{@code addAnimation} calls. Combat logic reads animation time in exactly one
 * place ({@code SlaverRed.changeState}, which copies the current track time onto a new track) and that
 * value has no bearing on the fight, so the stub is truthful enough.
 *
 * <p>One stub per {@code AnimationStateData} is enough: a fight has one to five monster types, and
 * every sandbox creature of a type can share the stub because nothing reads it back. Nothing has to be
 * restored on uninstall — the stub lives on the throwaway clone and the live creature keeps the state
 * object it always had.
 */
public final class AnimationMute {
    /** One throwaway state per skeleton data, reused by every sandbox creature of that type. */
    private static final Map<Object, AnimationState> STUBS = new IdentityHashMap<Object, AnimationState>();

    private AnimationMute() {
    }

    /** Mutes every creature of the currently installed (sandbox) world. Never throws. */
    public static void muteAll() {
        try {
            mute(AbstractDungeon.player);
            com.megacrit.cardcrawl.rooms.AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room != null && room.monsters != null && room.monsters.monsters != null) {
                for (AbstractMonster m : room.monsters.monsters) {
                    mute(m);
                }
            }
        } catch (Throwable t) {
            Log.warn("animation mute failed: " + t);
        }
    }

    /**
     * B111 (user m05182 follow-up): a fresh {@link AnimationState} has no current track entry, and
     * {@code SlaverRed.changeState} reads {@code state.getCurrent(0).getTime()} -- that NPE is what
     * interrupted {@code World.uninstall()} and cost the player a save (B110). It also asks for an
     * animation name ("idleNoNet") that a skeleton may not define, which returns null and NPEs on the
     * very next line.
     *
     * <p>So the stub is a real AnimationState (all the engine's own behaviour stays) with two
     * null-safety overrides: {@code getCurrent} falls back to the last entry we handed out, and
     * {@code setAnimation} hands back that entry when the requested animation does not exist. The
     * animation time it reports has no bearing on combat, so this is truthful enough for SlaverRed's
     * "keep the current time" trick.
     */
    private static final class SafeState extends AnimationState {
        private TrackEntry fallback;

        SafeState(AnimationStateData data) {
            super(data);
            prime();
        }

        /** Gives the state one real entry so {@code getCurrent(0)} is never null. */
        private void prime() {
            try {
                AnimationStateData d = getData();
                if (d == null || d.getSkeletonData() == null
                        || d.getSkeletonData().getAnimations() == null
                        || d.getSkeletonData().getAnimations().size == 0) {
                    return;
                }
                String name = d.getSkeletonData().getAnimations().first().getName();
                TrackEntry e = super.setAnimation(0, name, true);
                if (e != null) {
                    fallback = e;
                }
            } catch (Throwable t) {
                Log.warn("animation stub priming failed: " + t);
            }
        }

        @Override
        public TrackEntry setAnimation(int trackIndex, String animationName, boolean loop) {
            TrackEntry e = super.setAnimation(trackIndex, animationName, loop);
            if (e != null) {
                fallback = e;
                return e;
            }
            // The skeleton does not define that animation. Hand back the primed entry instead of
            // null: the caller (SlaverRed.changeState) writes a time onto it immediately.
            return fallback;
        }

        @Override
        public TrackEntry getCurrent(int trackIndex) {
            TrackEntry e = super.getCurrent(trackIndex);
            return e != null ? e : fallback;
        }
    }

    private static void mute(AbstractCreature creature) {
        if (creature == null) {
            return;
        }
        AnimationState live = creature.state;
        if (live == null || STUBS.containsValue(live)) {
            return;
        }
        AnimationStateData data = live.getData();
        if (data == null) {
            return;
        }
        AnimationState stub = STUBS.get(data);
        if (stub == null) {
            stub = new SafeState(data);
            STUBS.put(data, stub);
        }
        creature.state = stub;
    }
}
