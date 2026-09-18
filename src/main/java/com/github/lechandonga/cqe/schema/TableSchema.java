package com.github.lechandonga.cqe.schema;

import com.github.lechandonga.cqe.error.QueryException;

import java.util.List;

/** 表结构：有序列集合，列名唯一（大小写不敏感）。 */
public class TableSchema {

    private final List<ColumnSchema> columns;

    public TableSchema(List<ColumnSchema> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("schema must have at least one column");
        }
        long distinct = columns.stream().map(c -> c.name().toLowerCase()).distinct().count();
        if (distinct != columns.size()) {
            throw new IllegalArgumentException("duplicate column names in schema");
        }
        this.columns = List.copyOf(columns);
    }

    public List<ColumnSchema> columns() {
        return columns;
    }

    public int size() {
        return columns.size();
    }

    public ColumnSchema column(int index) {
        return columns.get(index);
    }

    /** 大小写不敏感查找，未找到返回 -1。 */
    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** 未找到时抛出可定位的 UNKNOWN_FIELD 错误。 */
    public int requireIndex(String name) {
        int idx = indexOf(name);
        if (idx < 0) {
            throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                    "unknown column: " + name);
        }
        return idx;
    }

    public List<String> names() {
        return columns.stream().map(ColumnSchema::name).toList();
    }
}
