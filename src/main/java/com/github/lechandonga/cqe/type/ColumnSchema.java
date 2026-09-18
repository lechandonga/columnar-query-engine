package com.github.lechandonga.cqe.type;

/** 单列结构定义。 */
public record ColumnSchema(String name, DataType type, boolean nullable) {

    public ColumnSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("列类型不能为空");
        }
    }

    public static ColumnSchema of(String name, DataType type) {
        return new ColumnSchema(name, type, true);
    }

    public static ColumnSchema required(String name, DataType type) {
        return new ColumnSchema(name, type, false);
    }
}
