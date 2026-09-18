package com.github.lechandonga.cqe.schema;

import com.github.lechandonga.cqe.type.DataType;

public record ColumnSchema(String name, DataType type) {
    public ColumnSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("column type must not be null");
        }
    }
}
