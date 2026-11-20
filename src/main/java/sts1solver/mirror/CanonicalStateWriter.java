package sts1solver.mirror;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic, length-delimited encoding for search-state fields.
 *
 * <p>Callers assign stable numeric field tags and write fields in schema order. This class never
 * relies on Object.toString(), platform byte order, default charset, or collection iteration order.
 * Collection ordering is the responsibility of the snapshot/contributor that owns the field.
 */
public final class CanonicalStateWriter {
    private static final int TYPE_NULL = 0;
    private static final int TYPE_BOOLEAN = 1;
    private static final int TYPE_INT = 2;
    private static final int TYPE_LONG = 3;
    private static final int TYPE_FLOAT = 4;
    private static final int TYPE_DOUBLE = 5;
    private static final int TYPE_STRING = 6;
    private static final int TYPE_BYTES = 7;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
    private final DataOutputStream out = new DataOutputStream(bytes);
    private int lastTag = -1;

    public CanonicalStateWriter putNull(int tag) {
        field(tag, TYPE_NULL, new byte[0]);
        return this;
    }

    public CanonicalStateWriter putBoolean(int tag, boolean value) {
        field(tag, TYPE_BOOLEAN, new byte[] {(byte) (value ? 1 : 0)});
        return this;
    }

    public CanonicalStateWriter putInt(int tag, int value) {
        byte[] data = new byte[4];
        data[0] = (byte) (value >>> 24);
        data[1] = (byte) (value >>> 16);
        data[2] = (byte) (value >>> 8);
        data[3] = (byte) value;
        field(tag, TYPE_INT, data);
        return this;
    }

    public CanonicalStateWriter putLong(int tag, long value) {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; --i) {
            data[i] = (byte) value;
            value >>>= 8;
        }
        field(tag, TYPE_LONG, data);
        return this;
    }

    public CanonicalStateWriter putFloat(int tag, float value) {
        return putIntTyped(tag, TYPE_FLOAT, Float.floatToIntBits(value));
    }

    public CanonicalStateWriter putDouble(int tag, double value) {
        return putLongTyped(tag, TYPE_DOUBLE, Double.doubleToLongBits(value));
    }

    public CanonicalStateWriter putString(int tag, String value) {
        if (value == null) {
            return putNull(tag);
        }
        field(tag, TYPE_STRING, value.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public CanonicalStateWriter putBytes(int tag, byte[] value) {
        if (value == null) {
            return putNull(tag);
        }
        field(tag, TYPE_BYTES, value.clone());
        return this;
    }

    /** Append an already-canonical nested value as one field. */
    public CanonicalStateWriter putCanonical(int tag, byte[] value) {
        return putBytes(tag, value);
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }

    private CanonicalStateWriter putIntTyped(int tag, int type, int value) {
        byte[] data = new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value
        };
        field(tag, type, data);
        return this;
    }

    private CanonicalStateWriter putLongTyped(int tag, int type, long value) {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; --i) {
            data[i] = (byte) value;
            value >>>= 8;
        }
        field(tag, type, data);
        return this;
    }

    private void field(int tag, int type, byte[] value) {
        if (tag < 0 || tag <= lastTag) {
            throw new IllegalArgumentException("Canonical field tags must be strictly increasing and non-negative: " + tag);
        }
        lastTag = tag;
        try {
            out.writeInt(tag);
            out.writeByte(type);
            out.writeInt(value.length);
            out.write(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory canonical encoding failed", impossible);
        }
    }
}
