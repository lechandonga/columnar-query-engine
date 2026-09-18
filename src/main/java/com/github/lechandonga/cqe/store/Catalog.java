package com.github.lechandonga.cqe.store;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.column.VectorBuilder;
import com.github.lechandonga.cqe.column.Vectors;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 表目录：以不可变快照 + 原子替换实现并发安全的读写与结构演进。 */
public final class Catalog {

    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    /**
     * 提交锁：结构/数据变更在该监视器内原子发布，多表快照也在该监视器内一致抓取。
     * 注意：ConcurrentHashMap.compute 的映射函数内部仅做构建工作（持有的是单个 bin
     * 的监视器），而“原子替换”这一发布动作本身在 compute 返回时完成；为了让
     * 多表快照与变更互斥，变更在 compute 之外再经此锁串行化发布顺序。
     */
    private final Object commitLock = new Object();
    private volatile long version = 0;

    public void createTable(String name, Schema schema) {
        if (name == null || name.isBlank()) {
            throw new QueryException(QueryException.Code.DATA_VIOLATION, "表名不能为空");
        }
        List<ColumnVector> empty = new ArrayList<>();
        for (ColumnSchema column : schema.columns()) {
            empty.add(Vectors.empty(column.type()));
        }
        Table table = new Table(name, schema, empty);
        synchronized (commitLock) {
            if (tables.putIfAbsent(name, table) != null) {
                throw new QueryException(QueryException.Code.DATA_VIOLATION,
                        "表已存在: " + name);
            }
            version++;
        }
    }

    public Table get(String name) {
        Table table = tables.get(name);
        if (table == null) {
            throw new QueryException(QueryException.Code.UNKNOWN_TABLE, "未知表: " + name);
        }
        return table;
    }

    public boolean exists(String name) {
        return tables.containsKey(name);
    }

    /** 当前已提交版本号（每次成功插入/演进/建表/删表后递增）。 */
    public long version() {
        return version;
    }

    /**
     * 在单个原子步骤内抓取一组表的一致快照：读取期间任何提交都不会只对其中一部分表可见。
     * 未知表以 UNKNOWN_TABLE 明确失败（此时不会开始任何查询）。
     */
    public List<Table> snapshotOf(List<String> namesInOrder) {
        synchronized (commitLock) {
            List<Table> snapshot = new ArrayList<>(namesInOrder.size());
            for (String name : namesInOrder) {
                Table table = tables.get(name);
                if (table == null) {
                    throw new QueryException(QueryException.Code.UNKNOWN_TABLE,
                            "未知表: " + name);
                }
                snapshot.add(table);
            }
            return snapshot;
        }
    }

    /** 追加行；任一行校验失败则整体不生效，旧快照保持不变。 */
    public void insertRows(String name, List<Object[]> rows) {
        if (rows == null) {
            throw new QueryException(QueryException.Code.DATA_VIOLATION, "rows 不能为空");
        }
        synchronized (commitLock) {
            insertRowsLocked(name, rows);
            version++;
        }
    }

    private void insertRowsLocked(String name, List<Object[]> rows) {
        Table current = tables.get(name);
        if (current == null) {
            throw new QueryException(QueryException.Code.UNKNOWN_TABLE, "未知表: " + name);
        }
        tables.put(name, buildInsertion(name, current, rows));
    }

    /** 基于旧快照构建追加后的新快照；任何行校验失败都会抛出，且不触碰现有映射。 */
    private Table buildInsertion(String name, Table current, List<Object[]> rows) {
        Schema schema = current.schema();
        List<VectorBuilder> builders = new ArrayList<>(schema.size());
        for (int c = 0; c < schema.size(); c++) {
            builders.add(Vectors.newBuilder(schema.get(c).type(),
                    current.rowCount() + rows.size()));
        }
        // 先复制旧数据（不可变向量，读出的必定是完整快照）
        for (int c = 0; c < schema.size(); c++) {
            ColumnVector old = current.columns().get(c);
            for (int r = 0; r < old.size(); r++) {
                builders.get(c).append(old.get(r));
            }
        }
        // 再校验并写入新行；任何不合法值抛异常，调用方保证不发布半成品
        for (int r = 0; r < rows.size(); r++) {
            Object[] row = rows.get(r);
            if (row == null || row.length != schema.size()) {
                throw new QueryException(QueryException.Code.DATA_VIOLATION,
                        "表 '" + name + "' 第 " + r + " 行列数不匹配: 期望 "
                                + schema.size() + " 实际 "
                                + (row == null ? "null" : row.length));
            }
            for (int c = 0; c < schema.size(); c++) {
                Object value = row[c];
                ColumnSchema cs = schema.get(c);
                if (value == null && !cs.nullable()) {
                    throw new QueryException(QueryException.Code.DATA_VIOLATION,
                            "表 '" + name + "' 第 " + r + " 行列 '"
                                    + cs.name() + "' 不允许空值");
                }
                try {
                    builders.get(c).append(value);
                } catch (QueryException e) {
                    throw new QueryException(QueryException.Code.DATA_VIOLATION,
                            "表 '" + name + "' 第 " + r + " 行列 '"
                                    + cs.name() + "': " + e.getMessage());
                }
            }
        }
        List<ColumnVector> newColumns = new ArrayList<>(schema.size());
        for (VectorBuilder builder : builders) {
            newColumns.add(builder.build());
        }
        return new Table(name, schema, newColumns);
    }

    /**
     * 对多张表执行一次原子写入事务：所有插入在同一提交点一起可见，
     * 任一表校验失败则全部不生效。读取者要么看到整个批次之前的状态，
     * 要么看到之后的状态，不会读到“只提交了一部分表”的中间状态。
     */
    public void insertRowsAtomically(List<TableInsert> inserts) {
        synchronized (commitLock) {
            // 在暂存映射上累积每表最新快照；任何一项失败都不会执行发布
            java.util.Map<String, Table> staged = new java.util.HashMap<>();
            for (TableInsert insert : inserts) {
                Table base = staged.containsKey(insert.tableName())
                        ? staged.get(insert.tableName())
                        : tables.get(insert.tableName());
                if (base == null) {
                    throw new QueryException(QueryException.Code.UNKNOWN_TABLE,
                            "未知表: " + insert.tableName());
                }
                staged.put(insert.tableName(),
                        buildInsertion(insert.tableName(), base, insert.rows()));
            }
            // 全部构建成功后一次性发布
            tables.putAll(staged);
            version++;
        }
    }

    /** 原子地应用结构演进；先整体校验计划，再重建数据，任一失败则全部不生效。 */
    public void evolve(String name, SchemaEvolution evolution) {
        if (evolution == null) {
            throw new QueryException(QueryException.Code.SCHEMA_EVOLUTION_REJECTED,
                    "结构演进计划不能为空");
        }
        synchronized (commitLock) {
            evolveLocked(name, evolution);
            version++;
        }
    }

    private void evolveLocked(String name, SchemaEvolution evolution) {
        Table current = tables.get(name);
        if (current == null) {
            throw new QueryException(QueryException.Code.UNKNOWN_TABLE, "未知表: " + name);
        }
        // 第一阶段：在模拟 schema 上校验整个计划（全部通过才动数据）
        List<ColumnSchema> simulated = new ArrayList<>(current.schema().columns());
        for (SchemaEvolution.Op op : evolution.ops()) {
            switch (op) {
                case SchemaEvolution.Op.AddColumn add -> {
                    ColumnSchema cs = add.column();
                    if (findIndex(simulated, cs.name()) >= 0) {
                        throw new QueryException(QueryException.Code.SCHEMA_EVOLUTION_REJECTED,
                                "新增列已存在: " + cs.name());
                    }
                    validateDefault(add);
                    simulated.add(cs);
                }
                case SchemaEvolution.Op.RemoveColumn remove -> {
                    int idx = findIndex(simulated, remove.name());
                    if (idx < 0) {
                        throw new QueryException(QueryException.Code.SCHEMA_EVOLUTION_REJECTED,
                                "待删除列不存在: " + remove.name());
                    }
                    simulated.remove(idx);
                }
            }
        }
        // 第二阶段：按计划重建列
        List<ColumnSchema> target = new ArrayList<>(current.schema().columns());
        List<ColumnVector> data = new ArrayList<>(current.columns());
        for (SchemaEvolution.Op op : evolution.ops()) {
            switch (op) {
                case SchemaEvolution.Op.AddColumn add -> {
                    ColumnSchema cs = add.column();
                    var builder = Vectors.newBuilder(cs.type(), current.rowCount());
                    for (int r = 0; r < current.rowCount(); r++) {
                        builder.append(add.defaultValue());
                    }
                    target.add(cs);
                    data.add(builder.build());
                }
                case SchemaEvolution.Op.RemoveColumn remove -> {
                    int idx = findIndex(target, remove.name());
                    target.remove(idx);
                    data.remove(idx);
                }
            }
        }
        // 校验与重建都成功后才发布
        tables.put(name, new Table(name, new Schema(target), data));
    }

    private static int findIndex(List<ColumnSchema> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateDefault(SchemaEvolution.Op.AddColumn add) {
        ColumnSchema cs = add.column();
        Object v = add.defaultValue();
        if (v == null) {
            if (!cs.nullable()) {
                throw new QueryException(QueryException.Code.SCHEMA_EVOLUTION_REJECTED,
                        "非空新列 '" + cs.name() + "' 必须提供非空默认值");
            }
            return;
        }
        boolean ok = switch (cs.type()) {
            case INT -> v instanceof Integer
                    || (v instanceof Double d && d == Math.rint(d));
            case DOUBLE -> v instanceof Number;
            case BOOLEAN -> v instanceof Boolean;
            case STRING -> v instanceof String;
        };
        if (!ok) {
            throw new QueryException(QueryException.Code.SCHEMA_EVOLUTION_REJECTED,
                    "新列 '" + cs.name() + "' 默认值类型不匹配: "
                            + v.getClass().getSimpleName() + " -> " + cs.type());
        }
    }

    public void dropTable(String name) {
        synchronized (commitLock) {
            if (tables.remove(name) == null) {
                throw new QueryException(QueryException.Code.UNKNOWN_TABLE, "未知表: " + name);
            }
            version++;
        }
    }
}
