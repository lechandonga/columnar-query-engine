package com.github.lechandonga.cqe.store;

import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.type.Schema;

import java.util.List;

/** 不可变表快照：结构一旦发布，任何执行线程读到的都是完整一致的数据。 */
public final class Table {

    private final String name;
    private final Schema schema;
    private final List<ColumnVector> columns;

    public Table(String name, Schema schema, List<ColumnVector> columns) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        if (schema == null || columns == null) {
            throw new IllegalArgumentException("schema/columns 不能为空");
        }
        if (columns.size() != schema.size()) {
            throw new IllegalArgumentException(
                    "列数不匹配: schema=" + schema.size() + " data=" + columns.size());
        }
        int rows = columns.isEmpty() ? 0 : columns.get(0).size();
        for (ColumnVector vector : columns) {
            if (vector.size() != rows) {
                throw new IllegalArgumentException("所有列向量必须等长");
            }
        }
        this.name = name;
        this.schema = schema;
        this.columns = List.copyOf(columns);
    }

    public String name() {
        return name;
    }

    public Schema schema() {
        return schema;
    }

    public List<ColumnVector> columns() {
        return columns;
    }

    public int rowCount() {
        return columns.isEmpty() ? 0 : columns.get(0).size();
    }
}
