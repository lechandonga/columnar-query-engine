package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.schema.ColumnSchema;

import java.util.List;

/** 查询结果：输出 schema + 有序行集合，顺序由执行语义确定。 */
public class ResultSet {

    private final List<ColumnSchema> columns;
    private final List<Object[]> rows;

    public ResultSet(List<ColumnSchema> columns, List<Object[]> rows) {
        this.columns = List.copyOf(columns);
        this.rows = List.copyOf(rows);
    }

    public List<ColumnSchema> columns() {
        return columns;
    }

    public List<String> columnNames() {
        return columns.stream().map(ColumnSchema::name).toList();
    }

    public int rowCount() {
        return rows.size();
    }

    public List<Object[]> rows() {
        return rows;
    }

    public Object valueAt(int row, int col) {
        return rows.get(row)[col];
    }
}
