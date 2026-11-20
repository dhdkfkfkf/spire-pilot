package sts1solver.mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.IdentityHashMap;

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
        TraversalContext context = new TraversalContext();
        try {
            Object player = sts1solver.reflect.Reflect.get(
                    sts1solver.reflect.Reflect.find(
                            com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "player"), null);
            tag = appendObject(writer, tag, "player", player, unknown, context, 0);
            if (player != null) {
                String[] members = {"relics", "potions", "orbs", "powers",
                        "hand", "drawPile", "discardPile", "exhaustPile"};
                for (String member : members) {
                    ReadResult result = readNamed(player, member);
                    recordReadFailure("player." + member, result, unknown);
                    tag = appendValue(writer, tag, "player." + member, result.value, unknown, context, 0);
                }
            }

            Object group = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters();
            ReadResult monstersResult = readNamed(group, "monsters");
            recordReadFailure("monsters", monstersResult, unknown);
            Object monsters = monstersResult.value;
            tag = appendValue(writer, tag, "monsters", monsters, unknown, context, 0);
            if (monsters instanceof Iterable<?>) {
                int index = 0;
                for (Object monster : (Iterable<?>) monsters) {
                    ReadResult powers = readNamed(monster, "powers");
                    recordReadFailure("monster[" + index + "].powers", powers, unknown);
                    tag = appendValue(writer, tag, "monster[" + index++ + "].powers",
                            powers.value, unknown, context, 0);
                }
            }
            tag = appendMirroredStatics(writer, tag, unknown, context);
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
                                             List<String> unknown, TraversalContext context) {
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
            tag = appendValue(writer, tag, label, value, unknown, context, 0);
        }
        return tag;
    }

    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 4096;
    private static final int MAX_FIELDS_PER_OBJECT = 256;
    private static final int MAX_ORDERED_ITEMS = 1024;

    /** Shared across all roots so aliases and cycles have one deterministic identity space. */
    private static final class TraversalContext {
        final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<Object, Integer>();
        int nextIdentity = 1;
        int visitedNodes;
    }

    private static int appendValue(CanonicalStateWriter writer, int tag, String label,
                                   Object value, List<String> unknown,
                                   TraversalContext context, int depth) {
        if (value == null) {
            writer.putString(tag++, label + "=null");
            return tag;
        }
        if (isScalar(value.getClass())) {
            writer.putString(tag++, label + "=" + scalar(value));
            return tag;
        }
        if (depth > MAX_DEPTH) {
            writer.putString(tag++, label + ".truncated=depth-limit");
            unknown.add("object-graph-depth-limit:" + label);
            return tag;
        }
        if (value instanceof java.util.Set<?>) {
            java.util.Set<?> set = (java.util.Set<?>) value;
            List<String> members = new ArrayList<String>();
            for (Object item : set) {
                if (item == null || !isScalar(item.getClass())) {
                    writer.putString(tag++, label + ".set="
                            + value.getClass().getName() + ":" + set.size());
                    unknown.add("unordered-set-has-non-scalar-member:" + label);
                    return tag;
                }
                members.add(scalar(item));
            }
            Collections.sort(members);
            if (!beginObject(writer, tag, label, value, context, unknown)) {
                return tag + 1;
            }
            tag++;
            writer.putString(tag++, label + ".set-class=" + value.getClass().getName());
            for (int i = 0; i < members.size(); i++) {
                writer.putString(tag++, label + ".set-member[" + i + "]=" + members.get(i));
            }
            writer.putString(tag++, label + ".set-size=" + members.size());
            return tag;
        }
        if (value instanceof java.util.Map<?, ?>) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
            List<String[]> entries = new ArrayList<String[]>();
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object item = entry.getValue();
                if (key == null || item == null || !isScalar(key.getClass())
                        || !isScalar(item.getClass())) {
                    writer.putString(tag++, label + ".map="
                            + value.getClass().getName() + ":" + map.size());
                    unknown.add("map-has-non-scalar-key-or-value:" + label);
                    return tag;
                }
                entries.add(new String[] {scalar(key), scalar(item)});
            }
            Collections.sort(entries, new Comparator<String[]>() {
                public int compare(String[] a, String[] b) {
                    int c = a[0].compareTo(b[0]);
                    return c != 0 ? c : a[1].compareTo(b[1]);
                }
            });
            if (!beginObject(writer, tag, label, value, context, unknown)) {
                return tag + 1;
            }
            tag++;
            writer.putString(tag++, label + ".map-class=" + value.getClass().getName());
            for (int i = 0; i < entries.size(); i++) {
                writer.putString(tag++, label + ".map-entry[" + i + "].key=" + entries.get(i)[0]);
                writer.putString(tag++, label + ".map-entry[" + i + "].value=" + entries.get(i)[1]);
            }
            writer.putString(tag++, label + ".map-size=" + entries.size());
            return tag;
        }
        if (value instanceof java.util.List<?> || value instanceof java.util.Deque<?>
                || value instanceof java.util.Queue<?>) {
            if (!beginObject(writer, tag, label, value, context, unknown)) {
                return tag + 1;
            }
            tag++;
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                if (index >= MAX_ORDERED_ITEMS) {
                    writer.putString(tag++, label + ".truncated=item-limit");
                    unknown.add("ordered-collection-item-limit:" + label);
                    break;
                }
                tag = appendValue(writer, tag, label + "[" + index++ + "]", item,
                        unknown, context, depth + 1);
            }
            writer.putString(tag++, label + ".size=" + ((java.util.Collection<?>) value).size());
            return tag;
        }
        if (value instanceof Iterable<?>) {
            writer.putString(tag++, label + ".iterable=" + value.getClass().getName());
            unknown.add("iterable-order-not-guaranteed:" + label);
            return tag;
        }
        if (value.getClass().isArray()) {
            if (!beginObject(writer, tag, label, value, context, unknown)) {
                return tag + 1;
            }
            tag++;
            int length = java.lang.reflect.Array.getLength(value);
            int limit = Math.min(length, MAX_ORDERED_ITEMS);
            for (int index = 0; index < limit; index++) {
                Object item = java.lang.reflect.Array.get(value, index);
                tag = appendValue(writer, tag, label + "[" + index + "]", item,
                        unknown, context, depth + 1);
            }
            if (length > limit) {
                writer.putString(tag++, label + ".truncated=item-limit");
                unknown.add("array-item-limit:" + label);
            }
            writer.putString(tag++, label + ".array-size=" + length);
            return tag;
        }
        ReadResult groupResult = readNamed(value, "group");
        if (groupResult.status == ReadStatus.INACCESSIBLE) {
            recordReadFailure(label + ".group", groupResult, unknown);
        }
        Object group = groupResult.value;
        if (group instanceof Iterable<?>) {
            if (!beginObject(writer, tag, label, value, context, unknown)) {
                return tag + 1;
            }
            tag++;
            return appendValue(writer, tag, label + ".group", group, unknown,
                    context, depth + 1);
        }
        return appendObject(writer, tag, label, value, unknown, context, depth);
    }

    private static boolean beginObject(CanonicalStateWriter writer, int tag, String label,
                                       Object value, TraversalContext context,
                                       List<String> unknown) {
        Integer prior = context.identities.get(value);
        if (prior != null) {
            writer.putString(tag, label + ".ref=" + prior);
            return false;
        }
        if (context.visitedNodes >= MAX_NODES) {
            writer.putString(tag, label + ".truncated=node-limit");
            unknown.add("object-graph-node-limit:" + label);
            return false;
        }
        int identity = context.nextIdentity++;
        context.identities.put(value, Integer.valueOf(identity));
        context.visitedNodes++;
        writer.putString(tag, label + ".identity=" + identity);
        return true;
    }

    private static int appendObject(CanonicalStateWriter writer, int tag, String label,
                                    Object value, List<String> unknown,
                                    TraversalContext context, int depth) {
        if (value == null) {
            writer.putString(tag++, label + "=null");
            return tag;
        }
        if (depth > MAX_DEPTH) {
            writer.putString(tag++, label + ".truncated=depth-limit");
            unknown.add("object-graph-depth-limit:" + label);
            return tag;
        }
        if (!beginObject(writer, tag, label, value, context, unknown)) {
            return tag + 1;
        }
        tag++;
        writer.putString(tag++, label + ".class=" + value.getClass().getName());
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> type = value.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
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
        int fieldLimit = Math.min(fields.size(), MAX_FIELDS_PER_OBJECT);
        for (int i = 0; i < fieldLimit; i++) {
            Field field = fields.get(i);
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
                    tag = appendValue(writer, tag, fieldLabel, v, unknown, context, depth + 1);
                }
            } catch (Throwable t) {
                unknown.add("field-unreadable:" + field.getDeclaringClass().getName()
                        + "#" + field.getName() + ":" + t.getClass().getName());
            }
        }
        if (fields.size() > fieldLimit) {
            writer.putString(tag++, label + ".truncated=field-limit");
            unknown.add("object-field-limit:" + label);
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
        return value.getClass().getName() + ":" + String.valueOf(value);
    }

}
