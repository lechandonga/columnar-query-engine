package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.engine.QueryEngine;
import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.exec.ResultSet;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.schema.TableSchema;
import com.github.lechandonga.cqe.storage.DataStore;
import com.github.lechandonga.cqe.type.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 结构演进：新增/移除列时既有查询语义确定，兼容性失败不得部分应用。 */
class SchemaEvolutionTest {

    private DataStore store;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        store = new DataStore();
        store.createTable("users", new TableSchema(List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("name", DataType.STRING))));
        store.insertRows("users", List.of(
                new Object[]{1, "alice"},
                new Object[]{2, "bob"}));
        engine = new QueryEngine(store);
    }

    @Test
    void addColumnFillsExistingRowsWithDefault() {
        store.addColumn("users", new ColumnSchema("age", DataType.INT), 0);
        ResultSet rs = engine.execute("SELECT id, age FROM users ORDER BY id ASC");
        assertEquals(2, rs.rowCount());
        assertEquals(0, rs.valueAt(0, 1));
        assertEquals(0, rs.valueAt(1, 1));
    }

    @Test
    void existingQueryUnaffectedByAddedColumn() {
        ResultSet before = engine.execute("SELECT id, name FROM users");
        store.addColumn("users", new ColumnSchema("age", DataType.INT), 0);
        ResultSet after = engine.execute("SELECT id, name FROM users");
        assertEquals(before.rows().stream().map(java.util.Arrays::asList).toList(),
                after.rows().stream().map(java.util.Arrays::asList).toList());
    }

    @Test
    void starQueryPicksUpNewColumnDeterministically() {
        store.addColumn("users", new ColumnSchema("age", DataType.INT), 0);
        ResultSet rs = engine.execute("SELECT * FROM users");
        assertEquals(List.of("id", "name", "age"), rs.columnNames());
    }

    @Test
    void queryOnDroppedColumnFailsWithUnknownField() {
        store.addColumn("users", new ColumnSchema("age", DataType.INT), 0);
        store.dropColumn("users", "age");
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT age FROM users"));
        assertEquals(QueryException.Category.UNKNOWN_FIELD, e.category());
    }

    @Test
    void dropColumnKeepsRemainingQuerySemantics() {
        store.dropColumn("users", "name");
        ResultSet rs = engine.execute("SELECT id FROM users ORDER BY id DESC");
        assertEquals(List.of(2, 1), rs.rows().stream().map(r -> r[0]).toList());
    }

    @Test
    void incompatibleAddColumnIsRejectedAtomically() {
        // 默认值类型不兼容：拒绝且结构不变
        QueryException e = assertThrows(QueryException.class,
                () -> store.addColumn("users", new ColumnSchema("age", DataType.INT), "not-an-int"));
        assertEquals(QueryException.Category.TYPE_MISMATCH, e.category());
        assertEquals(2, store.snapshot().table("users").schema().size());
        // 重名列：拒绝且结构不变
        assertThrows(QueryException.class,
                () -> store.addColumn("users", new ColumnSchema("name", DataType.STRING), "x"));
        assertEquals(2, store.snapshot().table("users").schema().size());
    }

    @Test
    void dropUnknownColumnIsRejectedAtomically() {
        assertThrows(QueryException.class, () -> store.dropColumn("users", "nope"));
        assertEquals(2, store.snapshot().table("users").schema().size());
    }

    @Test
    void dropLastColumnIsRejected() {
        store.dropColumn("users", "name");
        QueryException e = assertThrows(QueryException.class,
                () -> store.dropColumn("users", "id"));
        assertEquals(QueryException.Category.SCHEMA_INCOMPATIBLE, e.category());
        assertEquals(1, store.snapshot().table("users").schema().size());
    }

    @Test
    void insertsAfterEvolutionMustMatchNewSchema() {
        store.addColumn("users", new ColumnSchema("age", DataType.INT), 0);
        // 行宽不足：整批拒绝
        assertThrows(QueryException.class,
                () -> store.insertRows("users", List.<Object[]>of(new Object[]{3, "carol"})));
        assertEquals(2, store.snapshot().table("users").rowCount());
        // 行宽匹配：成功
        store.insertRows("users", List.<Object[]>of(new Object[]{3, "carol", 25}));
        assertEquals(3, store.snapshot().table("users").rowCount());
    }

    @Test
    void nullDefaultIsAllowedForAddedColumn() {
        store.addColumn("users", new ColumnSchema("nickname", DataType.STRING), null);
        ResultSet rs = engine.execute("SELECT nickname FROM users WHERE nickname IS NULL");
        assertEquals(2, rs.rowCount());
    }
}
