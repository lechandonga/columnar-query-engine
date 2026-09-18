package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.store.SchemaEvolution;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 结构演进与既有查询兼容：新增/移除列语义确定，失败时不变更部分应用。 */
class SchemaEvolutionTest {

    private ColumnarQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ColumnarQueryEngine();
        engine.catalog().createTable("items", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("price", DataType.INT))));
        engine.catalog().insertRows("items", List.of(
                new Object[]{1, 10},
                new Object[]{2, 20}));
    }

    @Test
    void addColumnBackfillsDefaultForExistingRows() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.AddColumn(
                        ColumnSchema.of("tag", DataType.STRING), "none"))));

        QueryResult r = engine.execute("SELECT id, price, tag FROM items ORDER BY id");
        assertEquals(3, r.schema().size());
        assertEquals("none", r.rows().get(0)[2]);
        assertEquals("none", r.rows().get(1)[2]);

        engine.catalog().insertRows("items", List.<Object[]>of(new Object[]{3, 30, "hot"}));
        QueryResult r2 = engine.execute("SELECT tag FROM items WHERE id = 3");
        assertEquals("hot", r2.rows().get(0)[0]);
    }

    @Test
    void addNullableColumnBackfillsNull() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.AddColumn(
                        ColumnSchema.of("note", DataType.STRING), null))));
        QueryResult r = engine.execute("SELECT note FROM items");
        assertTrue(r.rows().stream().allMatch(row -> row[0] == null));
    }

    @Test
    void existingQueriesRemainSemanticallyStableAfterAdd() {
        // 演进前的显式列查询在新增列后结果必须完全一致
        QueryResult before = engine.execute("SELECT id, price FROM items ORDER BY id");
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.AddColumn(
                        ColumnSchema.of("extra", DataType.DOUBLE), 0.0))));
        QueryResult after = engine.execute("SELECT id, price FROM items ORDER BY id");
        assertEquals(before.rowCount(), after.rowCount());
        for (int i = 0; i < before.rowCount(); i++) {
            assertArrayEquals(before.rows().get(i), after.rows().get(i));
        }
    }

    @Test
    void starPicksUpNewColumns() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.AddColumn(
                        ColumnSchema.of("extra", DataType.INT), 7))));
        QueryResult r = engine.execute("SELECT * FROM items WHERE id = 1");
        assertEquals(3, r.schema().size());
        assertEquals(7, r.rows().get(0)[2]);
    }

    @Test
    void removeColumnKeepsRemainingRowsIntact() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.RemoveColumn("price"))));
        QueryResult r = engine.execute("SELECT id FROM items ORDER BY id");
        assertEquals(List.of(1, 2),
                r.rows().stream().map(row -> row[0]).toList());
    }

    @Test
    void queryReferencingRemovedColumnRejected() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.RemoveColumn("price"))));
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT price FROM items"));
        assertEquals(QueryException.Code.UNKNOWN_COLUMN, e.code());
        // 不得静默返回空结果
        QueryResult count = engine.execute("SELECT count(*) c FROM items");
        assertEquals(2, count.rows().get(0)[0]);
    }

    @Test
    void addDuplicateColumnRejectedAtomically() {
        assertThrows(QueryException.class, () ->
                engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                        new SchemaEvolution.Op.AddColumn(
                                ColumnSchema.of("id", DataType.INT), 0)))));
        // 旧结构与数据不变
        QueryResult r = engine.execute("SELECT id, price FROM items ORDER BY id");
        assertEquals(2, r.schema().size());
        assertEquals(10, r.rows().get(0)[1]);
    }

    @Test
    void removeMissingColumnRejectedAtomically() {
        QueryException e = assertThrows(QueryException.class, () ->
                engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                        new SchemaEvolution.Op.RemoveColumn("ghost")))));
        assertEquals(QueryException.Code.SCHEMA_EVOLUTION_REJECTED, e.code());
        QueryResult r = engine.execute("SELECT count(*) c FROM items");
        assertEquals(2, r.rows().get(0)[0]);
    }

    @Test
    void defaultValueTypeMismatchRejectsEvolution() {
        QueryException e = assertThrows(QueryException.class, () ->
                engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                        new SchemaEvolution.Op.AddColumn(
                                ColumnSchema.of("flag", DataType.BOOLEAN), "yes")))));
        assertEquals(QueryException.Code.SCHEMA_EVOLUTION_REJECTED, e.code());
    }

    @Test
    void nonNullNewColumnRequiresNonNullDefault() {
        QueryException e = assertThrows(QueryException.class, () ->
                engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                        new SchemaEvolution.Op.AddColumn(
                                ColumnSchema.required("flag", DataType.BOOLEAN), null)))));
        assertEquals(QueryException.Code.SCHEMA_EVOLUTION_REJECTED, e.code());
    }

    @Test
    void multiStepEvolutionAppliesAllOrNothing() {
        // 计划中第二步非法（删除不存在的列）：第一步新增也不得生效
        QueryException e = assertThrows(QueryException.class, () ->
                engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                        new SchemaEvolution.Op.AddColumn(
                                ColumnSchema.of("phase", DataType.INT), 1),
                        new SchemaEvolution.Op.RemoveColumn("nope")))));
        assertEquals(QueryException.Code.SCHEMA_EVOLUTION_REJECTED, e.code());
        assertEquals(2, engine.catalog().get("items").schema().size());
    }

    @Test
    void addRemoveInOnePlanIsSupported() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.AddColumn(
                        ColumnSchema.of("phase", DataType.INT), 1),
                new SchemaEvolution.Op.RemoveColumn("price"))));
        QueryResult r = engine.execute("SELECT id, phase FROM items ORDER BY id");
        assertEquals(2, r.schema().size());
        assertEquals(1, r.rows().get(0)[1]);
    }

    @Test
    void insertsAfterEvolutionFollowNewShape() {
        engine.catalog().evolve("items", SchemaEvolution.of(List.of(
                new SchemaEvolution.Op.RemoveColumn("price"),
                new SchemaEvolution.Op.AddColumn(
                        ColumnSchema.of("name", DataType.STRING), "?")
        )));
        // 旧形状的插入必须被拒绝（列数不匹配），且不破坏数据
        assertThrows(QueryException.class,
                () -> engine.catalog().insertRows("items",
                        List.<Object[]>of(new Object[]{3, 30})));
        engine.catalog().insertRows("items",
                List.<Object[]>of(new Object[]{3, "new"}));
        QueryResult r = engine.execute("SELECT name FROM items WHERE id = 3");
        assertEquals("new", r.rows().get(0)[0]);
        // 旧行的新列使用默认值
        QueryResult old = engine.execute("SELECT name FROM items WHERE id = 1");
        assertEquals("?", old.rows().get(0)[0]);
    }
}
