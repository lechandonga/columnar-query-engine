package com.github.lechandonga.cqe.column;

/** 列向量构建器的统一接口。 */
public interface VectorBuilder {

    void appendNull();

    /** 按运行时类型追加值（null 表示空值）。 */
    void append(Object value);

    int size();

    ColumnVector build();
}
