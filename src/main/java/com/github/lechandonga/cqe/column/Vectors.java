package com.github.lechandonga.cqe.column;

import com.github.lechandonga.cqe.type.DataType;

/** 按数据类型构造向量/构建器的工厂工具。 */
public final class Vectors {

    private Vectors() {
    }

    public static VectorBuilder newBuilder(DataType type, int capacity) {
        return switch (type) {
            case INT -> IntVector.builder(capacity);
            case DOUBLE -> DoubleVector.builder(capacity);
            case BOOLEAN -> BooleanVector.builder(capacity);
            case STRING -> StringVector.builder(capacity);
        };
    }

    public static ColumnVector empty(DataType type) {
        return newBuilder(type, 0).build();
    }
}
