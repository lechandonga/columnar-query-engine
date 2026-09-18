package com.github.lechandonga.cqe.storage;

import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.schema.TableSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据集存储：MVCC 风格，查询通过 {@link #snapshot()} 获取一致快照；
 * 所有变更先校验再原子替换，失败时不影响已有数据。
 */
public class DataStore {

    private final AtomicReference<Map<String, Table>> state = new AtomicReference<>(Map.of());

    public Snapshot snapshot() {
        return new Snapshot(state.get());
    }

    public void createTable(String name, TableSchema schema) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void insertRows(String table, List<Object[]> rows) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 结构演进：新增列，既有行以 defaultValue 填充。 */
    public void addColumn(String table, ColumnSchema column, Object defaultValue) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 结构演进：移除列。 */
    public void dropColumn(String table, String columnName) {
        throw new UnsupportedOperationException("not implemented");
    }
}
