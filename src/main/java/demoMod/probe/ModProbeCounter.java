package demoMod.probe;

/**
 * A stand-in for a third-party mod's global state: one mutable static scalar that no vanilla sweep
 * could ever see. Gate S77 installs a world, writes this counter inside the sandbox, and requires
 * the live value to come back unchanged -- i.e. mod statics are mirrored like game statics are.
 *
 * <p>It lives in this jar on purpose (the gate has to load it), under a package that is not
 * {@code sts1solver.*} so {@link sts1solver.mirror.ModStatics} treats it as mod content.
 */
public final class ModProbeCounter {

    /** Written by {@link ModOrb#onEvoke()}; the mirror must restore it after a sandbox install. */
    public static int counter;

    private ModProbeCounter() {
    }
}
