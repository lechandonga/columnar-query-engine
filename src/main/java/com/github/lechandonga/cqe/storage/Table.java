package com.github.lechandonga.cqe.storage;

import com.github.lechandonga.cqe.schema.TableSchema;

import java.util.List;

/** 不可变列式表：schema + 等长列集合。 */
public class Table {

    private final String name;
    private final TableSchema schema;
    private final List<Column> columns;
    private final int rowCount;

    public Table(String name, TableSchema schema, List<Column> columns, int rowCount) {
        this.name = name;
        this.schema = schema;
        this.columns = List.copyOf(columns);
        this.rowCount = rowCount;
    }

    public String name() {
        return name;
    }

    public TableSchema schema() {
        return schema;
    }

    public int rowCount() {
        return rowCount;
    }

    public Column column(int index) {
        return columns.get(index);
    }

    public Column column(String colName) {
        return columns.get(schema.requireIndex(colName));
    }

    public int columnCount() {
        return columns.size();
    }

    public List<Column> columns() {
        return columns;
    }
}
