package sts1solver.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cached reflective access. Every handle is resolved once and reused: a search reads fields far
 * more often than it discovers them. */
public final class Reflect {

    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    private static final Map<Class<?>, List<Field>> INSTANCE = new HashMap<Class<?>, List<Field>>();
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();

    private Reflect() {
    }

    public static Field find(Class<?> c, String name) {
        String key = c.getName() + '#' + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        FIELDS.put(key, null);
        return null;
    }

    public static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object get(Class<?> c, String name, Object target) {
        Field f = find(c, name);
        return f == null ? null : get(f, target);
    }

    public static void set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
        } catch (Throwable t) {
            throw new RuntimeException("cannot set " + f, t);
        }
    }

    public static boolean set(Class<?> c, String name, Object target, Object value) {
        Field f = find(c, name);
        if (f == null) {
            return false;
        }
        try {
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Method findMethod(Class<?> c, String name, Class<?>... types) {
        String key = c.getName() + '#' + name;
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, types);
                m.setAccessible(true);
                METHODS.put(key, m);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        METHODS.put(key, null);
        return null;
    }

    public static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            throw new RuntimeException("cannot invoke " + m, cause);
        }
    }

    /** Every non-static field of {@code c} and its superclasses, caches, child-first order. */
    public static List<Field> instanceFields(Class<?> c) {
        List<Field> cached = INSTANCE.get(c);
        if (cached != null) {
            return cached;
        }
        List<Field> out = new ArrayList<Field>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] declared;
            try {
                declared = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : declared) {
                int mod = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mod)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                out.add(f);
            }
        }
        INSTANCE.put(c, out);
        return out;
    }

    public static boolean isStatic(Field f) {
        return java.lang.reflect.Modifier.isStatic(f.getModifiers());
    }
}
