package sts1solver.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Allocates an object without running its constructor.
 *
 * <p>A deep copy of a card cannot run the card's constructor: it would re-load textures, re-roll
 * random values and re-register the instance. The standard escape from that is
 * {@code sun.misc.Unsafe.allocateInstance}. It is reached <b>reflectively</b> on purpose: the mod is
 * compiled with {@code --release 8}, where {@code sun.misc} is not part of the published API, and a
 * compile-time reference would fail. At runtime the game runs on its bundled Java 8, where the class
 * and its {@code theUnsafe} singleton are present.
 */
public final class Instantiator {

    private static MethodHandle allocate;
    private static String failure;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Method m = unsafeClass.getMethod("allocateInstance", Class.class);
            allocate = MethodHandles.lookup().unreflect(m).bindTo(unsafe);
        } catch (Throwable t) {
            failure = t.toString();
        }
    }

    private Instantiator() {
    }

    public static boolean available() {
        return allocate != null;
    }

    public static String failure() {
        return failure;
    }

    public static Object allocate(Class<?> c) {
        try {
            return allocate.invokeExact(c);
        } catch (Throwable t) {
            throw new RuntimeException("allocateInstance(" + c.getName() + ")", t);
        }
    }
}
