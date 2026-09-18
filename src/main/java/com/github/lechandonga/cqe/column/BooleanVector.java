package com.github.lechandonga.cqe.column;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.type.DataType;

public final class BooleanVector implements ColumnVector {

    private final byte[] values;
    private final boolean[] nulls;

    public BooleanVector(byte[] values, boolean[] nulls) {
        if (values == null || nulls == null || values.length != nulls.length) {
            throw new IllegalArgumentException("values 与 nulls 必须等长且非空");
        }
        this.values = values;
        this.nulls = nulls;
    }

    @Override
    public DataType type() {
        return DataType.BOOLEAN;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls[row];
    }

    @Override
    public Object get(int row) {
        return nulls[row] ? null : values[row] != 0;
    }

    public boolean getBoolean(int row) {
        return values[row] != 0;
    }

    @Override
    public long estimatedBytes() {
        return 1L * values.length + 1L * nulls.length + 16L;
    }

    public static Builder builder(int initialCapacity) {
        return new Builder(initialCapacity);
    }

    public static final class Builder implements VectorBuilder {
        private byte[] values;
        private boolean[] nulls;
        private int size;

        public Builder(int initialCapacity) {
            int cap = Math.max(4, initialCapacity);
            this.values = new byte[cap];
            this.nulls = new boolean[cap];
        }

        private void grow() {
            if (size < values.length) {
                return;
            }
            int newCap = values.length + (values.length >> 1);
            byte[] nv = new byte[newCap];
            boolean[] nn = new boolean[newCap];
            System.arraycopy(values, 0, nv, 0, size);
            System.arraycopy(nulls, 0, nn, 0, size);
            values = nv;
            nulls = nn;
        }

        public void appendBoolean(boolean value) {
            grow();
            values[size] = (byte) (value ? 1 : 0);
            nulls[size] = false;
            size++;
        }

        @Override
        public void appendNull() {
            grow();
            values[size] = 0;
            nulls[size] = true;
            size++;
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else if (value instanceof Boolean b) {
                appendBoolean(b);
            } else {
                throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                        "BOOLEAN 列不接受 " + value.getClass().getSimpleName());
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public BooleanVector build() {
            byte[] v = new byte[size];
            boolean[] n = new boolean[size];
            System.arraycopy(values, 0, v, 0, size);
            System.arraycopy(nulls, 0, n, 0, size);
            return new BooleanVector(v, n);
        }
    }
}
