package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.List;

/** 查询结果：不可变列式快照。 */
public record QueryResult(Schema schema, List<ColumnVector> columns) {

    public QueryResult {
        if (schema == null || columns == null) {
            throw new IllegalArgumentException("schema/columns 不能为空");
        }
        columns = List.copyOf(columns);
    }

    public int rowCount() {
        return columns.isEmpty() ? 0 : columns.get(0).size();
    }

    /** 行式视图（仅用于展示/断言）。 */
    public List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>(rowCount());
        for (int r = 0; r < rowCount(); r++) {
            Object[] row = new Object[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                row[c] = columns.get(c).get(r);
            }
            rows.add(row);
        }
        return rows;
    }
}
