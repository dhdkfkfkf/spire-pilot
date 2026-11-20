package sts1solver.mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, conservative inventory of scalar fields on the built-in combat actors.
 *
 * <p>This deliberately does not claim completeness: object-valued fields may contain behavior
 * state, and mod code can keep state in statics or external services. It is an additional diagnostic
 * layer on top of Digest, not an exact-state proof.
 */
final class BuiltInStateFingerprint {
    private BuiltInStateFingerprint() {
    }

    static void append(CanonicalStateWriter writer, int firstTag, List<String> unknown) {
        int tag = firstTag;
        try {
            Object player = sts1solver.reflect.Reflect.get(
                    sts1solver.reflect.Reflect.find(
                            com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "player"), null);
            tag = appendObject(writer, tag, "player", player, unknown);
            if (player != null) {
                String[] members = {"relics", "potions", "orbs", "powers",
                        "hand", "drawPile", "discardPile", "exhaustPile"};
                for (String member : members) {
                    Object value = readNamed(player, member);
                    tag = appendValue(writer, tag, "player." + member, value, unknown);
                }
            }

            Object group = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters();
            Object monsters = readNamed(group, "monsters");
            tag = appendValue(writer, tag, "monsters", monsters, unknown);
        } catch (Throwable t) {
            unknown.add("built-in-state-capture-failed:" + t.getClass().getName());
        }
    }

    private static int appendValue(CanonicalStateWriter writer, int tag, String label,
                                   Object value, List<String> unknown) {
        if (value == null) {
            writer.putString(tag++, label + "=null");
            return tag;
        }
        if (value instanceof Iterable<?>) {
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                tag = appendObject(writer, tag, label + "[" + index++ + "]", item, unknown);
            }
            writer.putString(tag++, label + ".size=" + index);
            return tag;
        }
        Object group = readNamed(value, "group");
        if (group instanceof Iterable<?>) {
            return appendValue(writer, tag, label, group, unknown);
        }
        return appendObject(writer, tag, label, value, unknown);
    }

    private static int appendObject(CanonicalStateWriter writer, int tag, String label,
                                    Object value, List<String> unknown) {
        if (value == null) {
            writer.putString(tag++, label + "=null");
            return tag;
        }
        writer.putString(tag++, label + ".class=" + value.getClass().getName());
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> type = value.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                        || field.isSynthetic() || !isScalar(field.getType())
                        || isPresentationField(field.getName())) {
                    continue;
                }
                fields.add(field);
            }
        }
        Collections.sort(fields, new Comparator<Field>() {
            public int compare(Field a, Field b) {
                int c = a.getDeclaringClass().getName().compareTo(b.getDeclaringClass().getName());
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        for (Field field : fields) {
            try {
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                Object v = field.get(value);
                writer.putString(tag++, label + "." + field.getDeclaringClass().getName()
                        + "#" + field.getName() + "=" + scalar(v));
            } catch (Throwable t) {
                unknown.add("field-unreadable:" + field.getDeclaringClass().getName()
                        + "#" + field.getName());
            }
        }
        return tag;
    }

    private static Object readNamed(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // Continue through the superclass chain.
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Boolean.class
                || Number.class.isAssignableFrom(type) || type.isEnum();
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Float) {
            return Integer.toHexString(Float.floatToIntBits(((Float) value).floatValue()));
        }
        if (value instanceof Double) {
            return Long.toHexString(Double.doubleToLongBits(((Double) value).doubleValue()));
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).getDeclaringClass().getName() + ":" + ((Enum<?>) value).name();
        }
        return String.valueOf(value);
    }

    private static boolean isPresentationField(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("x") || n.contains("y") || n.contains("color")
                || n.contains("scale") || n.contains("angle") || n.contains("timer")
                || n.contains("alpha") || n.contains("transparen") || n.contains("glow")
                || n.contains("hover") || n.contains("texture") || n.contains("image")
                || n.contains("sound") || n.contains("font") || n.contains("hitbox")
                || n.contains("render") || n.contains("animation") || n.contains("effect");
    }
}
