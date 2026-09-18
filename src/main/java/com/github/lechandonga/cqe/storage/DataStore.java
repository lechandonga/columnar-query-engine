package com.github.lechandonga.cqe.storage;

import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.schema.TableSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * 数据集存储：MVCC 风格。查询通过 {@link #snapshot()} 获取一致快照，
 * 快照持有不可变表引用，因此查询期间的数据变更对查询不可见，
 * 查询绝不会读到半写入状态。
 *
 * 所有变更遵循"先完整校验、再原子替换"：校验失败抛异常且原状态不变，
 * 不会出现部分应用的变更。
 */
public class DataStore {

    private final AtomicReference<Map<String, Table>> state = new AtomicReference<>(Map.of());

    /** 获取当前一致性快照；快照内容不受后续变更影响。 */
    public Snapshot snapshot() {
        return new Snapshot(state.get());
    }

    public void createTable(String name, TableSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }
        mutate(s -> {
            String key = Snapshot.normalize(name);
            if (s.containsKey(key)) {
                throw new QueryException(QueryException.Category.SCHEMA_INCOMPATIBLE,
                        "table already exists: " + name);
            }
            Map<String, Table> next = new HashMap<>(s);
            List<Column> cols = schema.columns().stream()
                    .map(c -> new Column(c.type(), List.of()))
                    .toList();
            next.put(key, new Table(name, schema, cols, 0));
            return next;
        });
    }

    /**
     * 追加行。整批先校验（行宽、列类型），任何一行不合法则整批拒绝，
     * 已有数据保持不变。
     */
    public void insertRows(String table, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        mutate(s -> {
            Table t = requireTable(s, table);
            TableSchema schema = t.schema();
            for (int r = 0; r < rows.size(); r++) {
                Object[] row = rows.get(r);
                if (row == null || row.length != schema.size()) {
                    throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                            "row " + r + " has " + (row == null ? 0 : row.length)
                                    + " values, expected " + schema.size());
                }
                for (int c = 0; c < row.length; c++) {
                    ColumnSchema col = schema.column(c);
                    if (!col.type().accepts(row[c])) {
                        throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                                "row " + r + " column '" + col.name() + "' expects "
                                        + col.type() + " but got "
                                        + (row[c] == null ? "NULL"
                                                : row[c].getClass().getSimpleName()));
                    }
                }
            }
            List<Column> newCols = new ArrayList<>(schema.size());
            for (int c = 0; c < schema.size(); c++) {
                List<Object> vals = new ArrayList<>(t.column(c).values());
                for (Object[] row : rows) {
                    vals.add(row[c]);
                }
                newCols.add(new Column(schema.column(c).type(), vals));
            }
            Map<String, Table> next = new HashMap<>(s);
            next.put(Snapshot.normalize(table),
                    new Table(t.name(), schema, newCols, t.rowCount() + rows.size()));
            return next;
        });
    }

    /** 结构演进：新增列，既有行以 defaultValue 填充。校验失败则不应用任何变更。 */
    public void addColumn(String table, ColumnSchema column, Object defaultValue) {
        mutate(s -> {
            Table t = requireTable(s, table);
            if (t.schema().indexOf(column.name()) >= 0) {
                throw new QueryException(QueryException.Category.SCHEMA_INCOMPATIBLE,
                        "column already exists: " + table + "." + column.name());
            }
            if (!column.type().accepts(defaultValue)) {
                throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                        "default value for column '" + column.name() + "' expects "
                                + column.type());
            }
            List<ColumnSchema> newSchemas = new ArrayList<>(t.schema().columns());
            newSchemas.add(column);
            TableSchema newSchema = new TableSchema(newSchemas);
            List<Column> newCols = new ArrayList<>(t.columnCount() + 1);
            newCols.addAll(t.columns());
            List<Object> defaults = new ArrayList<>(t.rowCount());
            for (int i = 0; i < t.rowCount(); i++) {
                defaults.add(defaultValue);
            }
            newCols.add(new Column(column.type(), defaults));
            Map<String, Table> next = new HashMap<>(s);
            next.put(Snapshot.normalize(table),
                    new Table(t.name(), newSchema, newCols, t.rowCount()));
            return next;
        });
    }

    /** 结构演进：移除列。列不存在或删除后无列时拒绝，且不改变现状。 */
    public void dropColumn(String table, String columnName) {
        mutate(s -> {
            Table t = requireTable(s, table);
            int idx = t.schema().indexOf(columnName);
            if (idx < 0) {
                throw new QueryException(QueryException.Category.SCHEMA_INCOMPATIBLE,
                        "cannot drop unknown column: " + table + "." + columnName);
            }
            if (t.schema().size() == 1) {
                throw new QueryException(QueryException.Category.SCHEMA_INCOMPATIBLE,
                        "cannot drop the last column of table: " + table);
            }
            List<ColumnSchema> newSchemas = new ArrayList<>(t.schema().columns());
            newSchemas.remove(idx);
            TableSchema newSchema = new TableSchema(newSchemas);
            List<Column> newCols = new ArrayList<>(t.columnCount() - 1);
            for (int i = 0; i < t.columnCount(); i++) {
                if (i != idx) newCols.add(t.column(i));
            }
            Map<String, Table> next = new HashMap<>(s);
            next.put(Snapshot.normalize(table),
                    new Table(t.name(), newSchema, newCols, t.rowCount()));
            return next;
        });
    }

    private static Table requireTable(Map<String, Table> s, String name) {
        Table t = s.get(Snapshot.normalize(name));
        if (t == null) {
            throw new QueryException(QueryException.Category.UNKNOWN_TABLE,
                    "unknown table: " + name);
        }
        return t;
    }

    /**
     * 先基于当前状态计算并校验新状态（校验失败在此抛异常，原状态未被修改），
     * 再通过 CAS 原子替换；并发写冲突时重试。
     */
    private void mutate(UnaryOperator<Map<String, Table>> fn) {
        while (true) {
            Map<String, Table> cur = state.get();
            Map<String, Table> next = fn.apply(cur);
            if (state.compareAndSet(cur, Map.copyOf(next))) {
                return;
            }
        }
    }
}
