package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.engine.QueryEngine;
import com.github.lechandonga.cqe.exec.ResultSet;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.schema.TableSchema;
import com.github.lechandonga.cqe.storage.DataStore;
import com.github.lechandonga.cqe.type.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 空值、重复值、跨列比较、连接与分组语义、结果顺序必须与声明一致。 */
class ExecutionSemanticsTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        store.createTable("users", new TableSchema(List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("name", DataType.STRING),
                new ColumnSchema("age", DataType.INT))));
        store.insertRows("users", List.of(
                new Object[]{1, "alice", 30},
                new Object[]{2, "bob", null},
                new Object[]{3, "carol", 25},
                new Object[]{4, "bob", 40},   // 重复 name
                new Object[]{5, null, 30}));  // NULL name

        store.createTable("orders", new TableSchema(List.of(
                new ColumnSchema("oid", DataType.INT),
                new ColumnSchema("user_id", DataType.INT),
                new ColumnSchema("amount", DataType.DOUBLE))));
        store.insertRows("orders", List.of(
                new Object[]{10, 1, 9.5},
                new Object[]{11, 1, 20.0},
                new Object[]{12, 3, 7.25},
                new Object[]{13, null, 100.0}, // NULL 外键：不得匹配任何 user
                new Object[]{14, 99, 1.0}));   // 无对应 user

        engine = new QueryEngine(store);
    }

    @Test
    void projectionPreservesRowOrderAndDuplicates() {
        ResultSet rs = engine.execute("SELECT name FROM users");
        assertEquals(5, rs.rowCount());
        assertEquals(java.util.Arrays.asList("alice", "bob", "carol", "bob", null),
                rs.rows().stream().map(r -> r[0]).toList());
    }

    @Test
    void whereFiltersWithThreeValuedLogic() {
        // age = 30：NULL age 的行不得通过
        ResultSet rs = engine.execute("SELECT id FROM users WHERE age = 30");
        assertEquals(2, rs.rowCount());
        assertEquals(1, rs.valueAt(0, 0));
        assertEquals(5, rs.valueAt(1, 0));
    }

    @Test
    void nullComparisonNeverMatches() {
        ResultSet rs = engine.execute("SELECT id FROM users WHERE age = NULL");
        assertEquals(0, rs.rowCount());
        ResultSet rs2 = engine.execute("SELECT id FROM users WHERE age != 30");
        // age 为 NULL 的行在 != 下同样被过滤（UNKNOWN）
        assertEquals(List.of(3, 4), rs2.rows().stream().map(r -> r[0]).toList());
    }

    @Test
    void isNullAndIsNotNull() {
        ResultSet rs = engine.execute("SELECT id FROM users WHERE age IS NULL");
        assertEquals(List.of(2), rs.rows().stream().map(r -> r[0]).toList());
        ResultSet rs2 = engine.execute("SELECT id FROM users WHERE name IS NOT NULL");
        assertEquals(4, rs2.rowCount());
    }

    @Test
    void crossColumnComparison() {
        DataStore store = engine.store();
        store.createTable("pairs", new TableSchema(List.of(
                new ColumnSchema("a", DataType.INT),
                new ColumnSchema("b", DataType.INT))));
        store.insertRows("pairs", List.of(
                new Object[]{1, 2}, new Object[]{3, 3}, new Object[]{5, 4}, new Object[]{null, 1}));
        ResultSet rs = engine.execute("SELECT a FROM pairs WHERE a > b");
        assertEquals(List.of(5), rs.rows().stream().map(r -> r[0]).toList());
    }

    @Test
    void innerJoinMatchesAndSkipsNullKeys() {
        ResultSet rs = engine.execute(
                "SELECT users.id, orders.oid FROM users JOIN orders ON users.id = orders.user_id");
        // user1×2, user3×1；orders.user_id NULL 与 99 均不匹配；user 2/4/5 无订单
        assertEquals(3, rs.rowCount());
        assertEquals(List.of(1, 1, 3), rs.rows().stream().map(r -> r[0]).toList());
        assertEquals(List.of(10, 11, 12), rs.rows().stream().map(r -> r[1]).toList());
    }

    @Test
    void leftJoinKeepsUnmatchedRowsWithNulls() {
        ResultSet rs = engine.execute(
                "SELECT users.id, orders.oid FROM users LEFT JOIN orders ON users.id = orders.user_id");
        assertEquals(6, rs.rowCount()); // 3 匹配 + 3 个未匹配 user(2,4,5)
        long nullOid = rs.rows().stream().filter(r -> r[1] == null).count();
        assertEquals(3, nullOid);
    }

    @Test
    void groupByAggregatesWithNullHandling() {
        ResultSet rs = engine.execute(
                "SELECT name, COUNT(*), COUNT(age), AVG(age) FROM users GROUP BY name");
        assertEquals(4, rs.rowCount()); // alice, bob, carol, NULL 组
        // 组顺序 = 键首次出现顺序
        assertEquals(java.util.Arrays.asList("alice", "bob", "carol", null),
                rs.rows().stream().map(r -> r[0]).toList());
        // bob 两行：COUNT(*)=2，COUNT(age)=1（一行 age 为 NULL）
        Object[] bob = rs.rows().get(1);
        assertEquals(2L, bob[1]);
        assertEquals(1L, bob[2]);
        assertEquals(40.0, (Double) bob[3], 1e-9);
        // NULL name 组：age=30
        Object[] nullGroup = rs.rows().get(3);
        assertEquals(1L, nullGroup[1]);
        assertEquals(30.0, (Double) nullGroup[3], 1e-9);
    }

    @Test
    void aggregatesIgnoreNullsAndEmptyInputYieldsNull() {
        DataStore store = engine.store();
        store.createTable("t", new TableSchema(List.of(
                new ColumnSchema("g", DataType.STRING),
                new ColumnSchema("v", DataType.INT))));
        store.insertRows("t", List.of(
                new Object[]{"x", null}, new Object[]{"x", null}));
        ResultSet rs = engine.execute("SELECT SUM(v), MIN(v), MAX(v), COUNT(v), COUNT(*) FROM t");
        assertEquals(1, rs.rowCount());
        assertNull(rs.valueAt(0, 0));
        assertNull(rs.valueAt(0, 1));
        assertNull(rs.valueAt(0, 2));
        assertEquals(0L, rs.valueAt(0, 3));
        assertEquals(2L, rs.valueAt(0, 4));
    }

    @Test
    void orderByNullPlacementAndStability() {
        ResultSet asc = engine.execute("SELECT id, age FROM users ORDER BY age ASC");
        // ASC：NULL 最后；相同 age 保持原行序（稳定排序）
        assertEquals(List.of(3, 1, 5, 4, 2),
                asc.rows().stream().map(r -> r[0]).toList());
        ResultSet desc = engine.execute("SELECT id, age FROM users ORDER BY age DESC");
        // DESC：NULL 最前
        assertEquals(2, desc.valueAt(0, 0));
        assertNull(desc.valueAt(0, 1));
    }

    @Test
    void limitTruncatesDeterministically() {
        ResultSet rs = engine.execute("SELECT id FROM users ORDER BY id ASC LIMIT 2");
        assertEquals(2, rs.rowCount());
        assertEquals(List.of(1, 2), rs.rows().stream().map(r -> r[0]).toList());
    }

    @Test
    void sameQueryIsReproducibleAcrossRuns() {
        String sql = "SELECT name, COUNT(*) c FROM users GROUP BY name ORDER BY c DESC";
        ResultSet a = engine.execute(sql);
        ResultSet b = engine.execute(sql);
        assertEquals(a.rows().stream().map(java.util.Arrays::asList).toList(),
                b.rows().stream().map(java.util.Arrays::asList).toList());
    }

    @Test
    void starExpansionWithJoinDisambiguatesDuplicateNames() {
        DataStore store = engine.store();
        store.createTable("a", new TableSchema(List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("v", DataType.STRING))));
        store.createTable("b", new TableSchema(List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("w", DataType.STRING))));
        store.insertRows("a", List.<Object[]>of(new Object[]{1, "x"}));
        store.insertRows("b", List.<Object[]>of(new Object[]{1, "y"}));
        ResultSet rs = engine.execute("SELECT * FROM a JOIN b ON a.id = b.id");
        assertEquals(List.of("a.id", "v", "b.id", "w"), rs.columnNames());
    }
}
