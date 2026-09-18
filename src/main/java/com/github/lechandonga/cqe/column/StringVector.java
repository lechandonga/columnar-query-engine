package com.github.lechandonga.cqe.column;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.type.DataType;

public final class StringVector implements ColumnVector {

    /** 每个非空字符串值的固定对象开销估计（字节）。 */
    public static final long STRING_OBJECT_OVERHEAD = 40L;

    private final String[] values;
    private final boolean[] nulls;

    public StringVector(String[] values, boolean[] nulls) {
        if (values == null || nulls == null || values.length != nulls.length) {
            throw new IllegalArgumentException("values 与 nulls 必须等长且非空");
        }
        this.values = values;
        this.nulls = nulls;
    }

    @Override
    public DataType type() {
        return DataType.STRING;
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
        return nulls[row] ? null : values[row];
    }

    public String getString(int row) {
        return values[row];
    }

    @Override
    public long estimatedBytes() {
        long bytes = 8L * values.length + 1L * nulls.length + 16L;
        for (String value : values) {
            if (value != null) {
                bytes += STRING_OBJECT_OVERHEAD + 2L * value.length();
            }
        }
        return bytes;
    }

    public static Builder builder(int initialCapacity) {
        return new Builder(initialCapacity);
    }

    public static final class Builder implements VectorBuilder {
        private String[] values;
        private boolean[] nulls;
        private int size;

        public Builder(int initialCapacity) {
            int cap = Math.max(4, initialCapacity);
            this.values = new String[cap];
            this.nulls = new boolean[cap];
        }

        private void grow() {
            if (size < values.length) {
                return;
            }
            int newCap = values.length + (values.length >> 1);
            String[] nv = new String[newCap];
            boolean[] nn = new boolean[newCap];
            System.arraycopy(values, 0, nv, 0, size);
            System.arraycopy(nulls, 0, nn, 0, size);
            values = nv;
            nulls = nn;
        }

        @Override
        public void appendNull() {
            grow();
            values[size] = null;
            nulls[size] = true;
            size++;
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else if (value instanceof String s) {
                grow();
                values[size] = s;
                nulls[size] = false;
                size++;
            } else {
                throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                        "STRING 列不接受 " + value.getClass().getSimpleName());
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public StringVector build() {
            String[] v = new String[size];
            boolean[] n = new boolean[size];
            System.arraycopy(values, 0, v, 0, size);
            System.arraycopy(nulls, 0, n, 0, size);
            return new StringVector(v, n);
        }
    }
}
