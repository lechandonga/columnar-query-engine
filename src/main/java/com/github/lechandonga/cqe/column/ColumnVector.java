package com.github.lechandonga.cqe.column;

import com.github.lechandonga.cqe.type.DataType;

/** 列式向量：某一列在一批行中的值与空值标记。 */
public sealed interface ColumnVector
        permits IntVector, DoubleVector, BooleanVector, StringVector {

    DataType type();

    int size();

    boolean isNull(int row);

    /** 以装箱形式返回值；空值返回 null。 */
    Object get(int row);

    /** 该向量占用内存的估计字节数，用于内存限额核算。 */
    long estimatedBytes();
}
