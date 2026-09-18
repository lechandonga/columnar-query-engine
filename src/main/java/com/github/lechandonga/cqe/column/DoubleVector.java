package com.github.lechandonga.cqe.column;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.type.DataType;

public final class DoubleVector implements ColumnVector {

    private final double[] values;
    private final boolean[] nulls;

    public DoubleVector(double[] values, boolean[] nulls) {
        if (values == null || nulls == null || values.length != nulls.length) {
            throw new IllegalArgumentException("values 与 nulls 必须等长且非空");
        }
        this.values = values;
        this.nulls = nulls;
    }

    @Override
    public DataType type() {
        return DataType.DOUBLE;
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

    public double getDouble(int row) {
        return values[row];
    }

    @Override
    public long estimatedBytes() {
        return 8L * values.length + 1L * nulls.length + 16L;
    }

    public static Builder builder(int initialCapacity) {
        return new Builder(initialCapacity);
    }

    public static final class Builder implements VectorBuilder {
        private double[] values;
        private boolean[] nulls;
        private int size;

        public Builder(int initialCapacity) {
            int cap = Math.max(4, initialCapacity);
            this.values = new double[cap];
            this.nulls = new boolean[cap];
        }

        private void grow() {
            if (size < values.length) {
                return;
            }
            int newCap = values.length + (values.length >> 1);
            double[] nv = new double[newCap];
            boolean[] nn = new boolean[newCap];
            System.arraycopy(values, 0, nv, 0, size);
            System.arraycopy(nulls, 0, nn, 0, size);
            values = nv;
            nulls = nn;
        }

        public void appendDouble(double value) {
            grow();
            values[size] = value;
            nulls[size] = false;
            size++;
        }

        @Override
        public void appendNull() {
            grow();
            values[size] = 0d;
            nulls[size] = true;
            size++;
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else if (value instanceof Number n) {
                appendDouble(n.doubleValue());
            } else {
                throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                        "DOUBLE 列不接受 " + value.getClass().getSimpleName());
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public DoubleVector build() {
            double[] v = new double[size];
            boolean[] n = new boolean[size];
            System.arraycopy(values, 0, v, 0, size);
            System.arraycopy(nulls, 0, n, 0, size);
            return new DoubleVector(v, n);
        }
    }
}
