package sts1solver.mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, conservative inventory of built-in combat actors and mirrored static roots.
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
                    ReadResult result = readNamed(player, member);
                    recordReadFailure("player." + member, result, unknown);
                    tag = appendValue(writer, tag, "player." + member, result.value, unknown);
                }
            }

            Object group = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters();
            ReadResult monstersResult = readNamed(group, "monsters");
            recordReadFailure("monsters", monstersResult, unknown);
            Object monsters = monstersResult.value;
            tag = appendValue(writer, tag, "monsters", monsters, unknown);
            if (monsters instanceof Iterable<?>) {
                int index = 0;
                for (Object monster : (Iterable<?>) monsters) {
                    ReadResult powers = readNamed(monster, "powers");
                    recordReadFailure("monster[" + index + "].powers", powers, unknown);
                    tag = appendValue(writer, tag, "monster[" + index++ + "].powers",
                            powers.value, unknown);
                }
            }
            tag = appendMirroredStatics(writer, tag, unknown);
        } catch (Throwable t) {
            unknown.add("built-in-state-capture-failed:" + t.getClass().getName());
        }
    }

    /**
     * Capture the shallow values of the same static roots that World mirrors. This reuses the
     * authoritative Statics table (including generated game scalars and discovered mod scalars)
     * instead of maintaining a second list of global combat fields.
     *
     * <p>This is deliberately shallow: nested object graphs, maps, arrays and external mod services
     * are not proven complete. The caller therefore keeps the resulting key inexact.
     */
    private static int appendMirroredStatics(CanonicalStateWriter writer, int tag,
                                             List<String> unknown) {
        Statics.Spec[] specs = Statics.specs().clone();
        // Dynamic mod registrations can arrive in different orders; canonical output must not
        // depend on registration order or on the mutable backing array owned by Statics.
        java.util.Arrays.sort(specs, new Comparator<Statics.Spec>() {
            public int compare(Statics.Spec a, Statics.Spec b) {
                int c = a.owner.getName().compareTo(b.owner.getName());
                return c != 0 ? c : a.name.compareTo(b.name);
            }
        });
        for (Statics.Spec spec : specs) {
            if (spec.kind == Statics.Kind.PREF) {
                // Save-file overlays are deliberately isolated by World but are not combat state.
                continue;
            }
            String label = "static." + spec.owner.getName() + "#" + spec.name;
            final Object value;
            try {
                value = spec.field.get(null);
            } catch (Throwable t) {
                unknown.add("static-root-unreadable:" + label + ":" + t.getClass().getName());
                continue;
            }
            tag = appendValue(writer, tag, label, value, unknown);
        }
        return tag;
    }

    private static int appendValue(CanonicalStateWriter writer, int tag, String label,
                                   Object value, List<String> unknown) {
        if (value == null) {
            writer.putString(tag++, label + "=null");
            return tag;
        }
        if (isScalar(value.getClass())) {
            writer.putString(tag++, label + "=" + scalar(value));
            return tag;
        }
        if (value instanceof java.util.Set<?>) {
            // Set iteration order is not a canonical ordering contract. Do not hash an arbitrary
            // iteration sequence as if it were stable across JVMs or runs.
            writer.putString(tag++, label + ".set="
                    + value.getClass().getName() + ":" + ((java.util.Set<?>) value).size());
            unknown.add("unordered-set-not-canonicalized:" + label);
            return tag;
        }
        if (value instanceof java.util.List<?> || value instanceof java.util.Deque<?>
                || value instanceof java.util.Queue<?>) {
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                // These collection contracts preserve semantic iteration order.
                tag = appendValue(writer, tag, label + "[" + index++ + "]", item, unknown);
            }
            writer.putString(tag++, label + ".size=" + index);
            return tag;
        }
        if (value instanceof Iterable<?>) {
            writer.putString(tag++, label + ".iterable=" + value.getClass().getName());
            unknown.add("iterable-order-not-guaranteed:" + label);
            return tag;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object item = java.lang.reflect.Array.get(value, index);
                tag = appendValue(writer, tag, label + "[" + index + "]", item, unknown);
            }
            writer.putString(tag++, label + ".array-size=" + length);
            return tag;
        }
        if (value instanceof java.util.Map<?, ?>) {
            writer.putString(tag++, label + ".type=" + value.getClass().getName());
            unknown.add("map-not-canonicalized:" + label);
            return tag;
        }
        ReadResult groupResult = readNamed(value, "group");
        if (groupResult.status == ReadStatus.INACCESSIBLE) {
            recordReadFailure(label + ".group", groupResult, unknown);
        }
        Object group = groupResult.value;
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
                // transient is not a semantic guarantee: game and mod code may use transient
                // instance fields as combat bookkeeping, so only static/synthetic fields are skipped.
                if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
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
            String fieldLabel = label + "." + field.getDeclaringClass().getName()
                    + "#" + field.getName();
            try {
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                Object v = field.get(value);
                if (isScalar(field.getType()) || (v != null && isScalar(v.getClass()))) {
                    writer.putString(tag++, fieldLabel + "=" + scalar(v));
                } else {
                    // Record enough shape to distinguish null from a present nested object, but
                    // never imply that its mutable contents were captured.
                    writer.putString(tag++, fieldLabel + ".nested="
                            + (v == null ? "null" : v.getClass().getName()));
                    unknown.add("nested-field-not-recursively-captured:" + fieldLabel);
                }
            } catch (Throwable t) {
                unknown.add("field-unreadable:" + field.getDeclaringClass().getName()
                        + "#" + field.getName() + ":" + t.getClass().getName());
            }
        }
        return tag;
    }

    private enum ReadStatus { PRESENT, OWNER_NULL, ABSENT, INACCESSIBLE }

    private static final class ReadResult {
        final ReadStatus status;
        final Object value;
        final String detail;

        ReadResult(ReadStatus status, Object value, String detail) {
            this.status = status;
            this.value = value;
            this.detail = detail;
        }
    }

    /** Missing members and inaccessible members are never silently converted into null state. */
    private static ReadResult readNamed(Object owner, String name) {
        if (owner == null) {
            return new ReadResult(ReadStatus.OWNER_NULL, null, "owner-null");
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            final Field field;
            try {
                field = type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                continue;
            }
            try {
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                return new ReadResult(ReadStatus.PRESENT, field.get(owner), "");
            } catch (Throwable t) {
                return new ReadResult(ReadStatus.INACCESSIBLE, null, t.getClass().getName());
            }
        }
        return new ReadResult(ReadStatus.ABSENT, null, "field-not-found");
    }

    private static void recordReadFailure(String label, ReadResult result,
                                          List<String> unknown) {
        if (result.status == ReadStatus.ABSENT || result.status == ReadStatus.INACCESSIBLE) {
            unknown.add("named-field-" + result.status.name().toLowerCase()
                    + ":" + label + ":" + result.detail);
        }
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

}
