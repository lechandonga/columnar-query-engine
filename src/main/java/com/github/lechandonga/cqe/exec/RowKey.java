package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.column.ColumnVector;

import java.util.Arrays;
import java.util.List;

/**
 * 由若干列在某一行上的值组成的复合键。
 * <ul>
 *   <li>分组语义（{@link #forGroup}）：空值是有效键，所有相同空值组合归入同一组；</li>
 *   <li>连接语义（{@link #forJoin}）：任一键列为空的键互不相等（SQL 中
 *       NULL = NULL 为 unknown，连接永不匹配），实现上空键根本不参与哈希探测。</li>
 * </ul>
 */
final class RowKey {

    private final Object[] values;
    private final boolean anyNull;
    private final boolean joinSemantics;

    RowKey(Object[] values, boolean anyNull, boolean joinSemantics) {
        this.values = values;
        this.anyNull = anyNull;
        this.joinSemantics = joinSemantics;
    }

    /** 连接语义：含空值的键互不相等。 */
    static RowKey forJoin(List<ColumnVector> vectors, int[] indices, int row) {
        Object[] values = new Object[indices.length];
        boolean anyNull = false;
        for (int i = 0; i < indices.length; i++) {
            ColumnVector v = vectors.get(indices[i]);
            if (v.isNull(row)) {
                anyNull = true;
                values[i] = null;
            } else {
                values[i] = v.get(row);
            }
        }
        return new RowKey(values, anyNull, true);
    }

    /** 分组语义：所有空值组合归入同一组。 */
    static RowKey forGroup(List<ColumnVector> vectors, int[] indices, int row) {
        Object[] values = new Object[indices.length];
        boolean anyNull = false;
        for (int i = 0; i < indices.length; i++) {
            ColumnVector v = vectors.get(indices[i]);
            if (v.isNull(row)) {
                anyNull = true;
                values[i] = null;
            } else {
                values[i] = v.get(row);
            }
        }
        return new RowKey(values, anyNull, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RowKey other)) {
            return false;
        }
        if (joinSemantics) {
            // 调用方保证空键不会进入哈希表/探测，走到这里的均为非空键
            if (anyNull || other.anyNull) {
                return false;
            }
        } else if (anyNull != other.anyNull) {
            return false;
        }
        return Arrays.deepEquals(values, other.values);
    }

    @Override
    public int hashCode() {
        // 空键（仅可能出现在分组语义，或连接的探测侧）使用固定哈希，
        // equals 中连接语义保证它们彼此不匹配
        return Arrays.deepHashCode(values);
    }

    boolean anyNull() {
        return anyNull;
    }

    Object valueAt(int i) {
        return values[i];
    }

}