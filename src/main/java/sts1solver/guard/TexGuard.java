package sts1solver.guard;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * 1.0.7 (T1): ONE guard at the only place every draw goes through -- {@code SpriteBatch.switchTexture}.
 *
 * <p>Why the field-level patches are gone: 1.0.5 repaired the {@code img} of orbs, power icons, the
 * flash effect and the heal panel, guessing at the four objects that could hold a broken texture.
 * The crash still happened, which means the guess was wrong: the object holding it is something
 * else (a mod card, a relic, a potion, a monster, a screen). Guessing another field would be the
 * same mistake again.
 *
 * <p>{@code switchTexture} is the choke point every textured quad passes through, whatever owns the
 * texture. The guard there does three things and nothing else:
 * <ol>
 *   <li>tests the texture the way the crash does -- by asking for its size, because a failed load on
 *       this device can hand back a non-null Texture whose data buffer is null;</li>
 *   <li>logs the CALLER STACK once per distinct call site, so the log names the real owner instead
 *       of the guess;</li>
 *   <li>swaps in an already-loaded vanilla texture so the frame is drawn instead of throwing.</li>
 * </ol>
 *
 * <p>The placeholder may ONLY be an already-loaded vanilla texture: this device's libgdx has no
 * Gdx2DPixmap native implementation, so creating one at startup leaves the game unable to launch
 * (measured). Nothing here creates a texture.
 */
public final class TexGuard {

    /** Distinct call sites already reported; a crash loop must not flood the log. */
    private static final Set<String> REPORTED = new HashSet<String>();
    private static int reportBudget = 12;
    private static int repairBudget = 20;

    private TexGuard() {
    }

    /** True when the texture can really be drawn: null, or a null backing buffer, is broken. */
    public static boolean usable(Texture t) {
        if (t == null) {
            return false;
        }
        try {
            return t.getWidth() > 0 && t.getHeight() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    private static Texture placeholder;

    /** Set the first time the patched method really runs, which is the proof the patch applied. */
    private static boolean active;

    /**
     * Called on every draw by the patch. The first call proves ModTheSpire transformed
     * {@code SpriteBatch} -- "the class loaded" does not, because a library class can be loaded
     * before the patch is registered. Without this line a report cannot tell "guard is armed" from
     * "guard silently never ran".
     */
    public static void noteActive() {
        if (!active) {
            active = true;
            Log.info("tex guard: active (SpriteBatch.switchTexture is patched)");
        }
    }

    /**
     * A texture known to be usable: only reuses a static texture ImageMaster already loaded.
     * Returns null when none is found, in which case the caller draws nothing.
     */
    public static Texture placeholder() {
        Texture p = placeholder;
        if (p != null) {
            return p;
        }
        try {
            for (Field f : ImageMaster.class.getFields()) {
                if (!Texture.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    Object v = f.get(null);
                    if (v instanceof Texture && usable((Texture) v)) {
                        placeholder = (Texture) v;
                        return placeholder;
                    }
                } catch (Throwable ignored) {
                    // unreadable field, try the next one
                }
            }
        } catch (Throwable ignored) {
            // ImageMaster unavailable: give up quietly
        }
        return null;
    }

    /**
     * Called from the {@code switchTexture} prefix for a texture that cannot be drawn.
     *
     * <p>Reproduces what the original method would have done for the placeholder (flush, remember
     * it as the current texture, recompute its inverse size) and reports the caller. When no
     * placeholder exists it leaves the previous texture in place -- the frame draws something stale
     * for one quad, which is still better than taking the whole run down.
     */
    public static void repair(SpriteBatch batch, Texture bad) {
        Texture p = placeholder();
        report(bad, p != null);
        if (p == null || batch == null) {
            return;
        }
        try {
            batch.flush();
            Field last = Reflect.find(SpriteBatch.class, "lastTexture");
            Field iw = Reflect.find(SpriteBatch.class, "invTexWidth");
            Field ih = Reflect.find(SpriteBatch.class, "invTexHeight");
            if (last == null || iw == null || ih == null) {
                return;
            }
            Reflect.set(last, batch, p);
            Reflect.set(iw, batch, Float.valueOf(1.0f / p.getWidth()));
            Reflect.set(ih, batch, Float.valueOf(1.0f / p.getHeight()));
            if (repairBudget > 0) {
                repairBudget--;
                System.out.println("[sts1solver.guard] texture replaced with placeholder");
            }
        } catch (Throwable t) {
            Log.warn("tex: could not substitute a placeholder: " + t);
        }
    }

    /**
     * One log line (with the stack) per distinct call site. The stack is the point of this method:
     * "SpriteBatch.switchTexture" alone never says which card, power or screen owns the broken
     * texture, and the caller two frames up does.
     */
    private static void report(Texture bad, boolean substituted) {
        String kind;
        try {
            kind = bad == null ? "null" : (bad.getClass().getName() + "@" + System.identityHashCode(bad));
        } catch (Throwable t) {
            kind = "unreadable";
        }
        StackTraceElement[] st = new Throwable().getStackTrace();
        String site = "";
        StringBuilder frames = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < st.length && shown < 8; i++) {
            String cls = st[i].getClassName();
            if (cls.startsWith("sts1solver.guard.") || cls.startsWith("java.lang.Throwable")) {
                continue;
            }
            if (site.isEmpty() && !cls.startsWith("com.badlogic.gdx.")) {
                site = cls + '.' + st[i].getMethodName() + ':' + st[i].getLineNumber();
            }
            frames.append("\n    at ").append(st[i].toString());
            shown++;
        }
        if (site.isEmpty()) {
            site = st.length > 0 ? st[0].toString() : "unknown";
        }
        if (!REPORTED.add(site) || reportBudget <= 0) {
            return;
        }
        reportBudget--;
        Log.error("tex: unusable texture (" + kind + ") reached SpriteBatch.switchTexture; placeholder="
                + substituted + "; first call site: " + site + frames,
                new Throwable("unusable texture " + kind + " drawn by " + site));
    }

    /**
     * The texture currently on a batch, for diagnostics: says whether the guard had to replace the
     * last thing this batch was asked to draw.
     */
    public static String describeLast(SpriteBatch batch) {
        try {
            Object v = Reflect.get(SpriteBatch.class, "lastTexture", batch);
            return v == null ? "none" : (usable((Texture) v) ? "ok" : "broken");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
