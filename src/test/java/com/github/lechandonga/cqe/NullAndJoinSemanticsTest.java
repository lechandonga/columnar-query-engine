package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 空值、重复值、跨列比较与连接语义。 */
class NullAndJoinSemanticsTest {

    private ColumnarQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ColumnarQueryEngine();
        // 左表：含空键、重复键
        engine.catalog().createTable("emp", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("dept", DataType.STRING),
                ColumnSchema.of("salary", DataType.INT))));
        engine.catalog().insertRows("emp", List.of(
                new Object[]{1, "ENG", 100},
                new Object[]{2, "ENG", 200},
                new Object[]{3, null, 300},   // 空部门
                new Object[]{4, "SALES", 400},
                new Object[]{5, "ENG", 100})); // 与 id=1 重复部门
        // 右表：含空键
        engine.catalog().createTable("dept", new Schema(List.of(
                ColumnSchema.of("code", DataType.STRING),
                ColumnSchema.of("name", DataType.STRING))));
        engine.catalog().insertRows("dept", List.of(
                new Object[]{"ENG", "Engineering"},
                new Object[]{"SALES", "Sales"},
                new Object[]{null, "Unknown Dept"}));
    }

    @Test
    void nullNeverEqualsInWhere() {
        // dept = NULL 与 dept <> NULL 都不应匹配 id=3
        QueryResult r1 = engine.execute("SELECT id FROM emp WHERE dept = NULL");
        assertEquals(0, r1.rowCount());
        QueryResult r2 = engine.execute("SELECT id FROM emp WHERE dept <> NULL");
        assertEquals(0, r2.rowCount());
    }

    @Test
    void isNullDetectsNullRows() {
        QueryResult r = engine.execute("SELECT id FROM emp WHERE dept IS NULL ORDER BY id");
        assertEquals(1, r.rowCount());
        assertEquals(3, r.rows().get(0)[0]);
    }

    @Test
    void isNotNullKeepsNonNull() {
        QueryResult r = engine.execute("SELECT count(*) c FROM emp WHERE dept IS NOT NULL");
        assertEquals(4, r.rows().get(0)[0]);
    }

    @Test
    void threeValuedLogicAndOr() {
        QueryResult r = engine.execute(
                "SELECT id FROM emp WHERE dept = 'ENG' OR dept = NULL ORDER BY id");
        // x OR NULL：x 为真的行保留
        assertEquals(List.of(1, 2, 5), ids(r));

        QueryResult r2 = engine.execute(
                "SELECT id FROM emp WHERE salary > 150 AND dept = NULL ORDER BY id");
        // TRUE AND NULL => NULL，不匹配
        assertEquals(0, r2.rowCount());

        QueryResult r3 = engine.execute(
                "SELECT id FROM emp WHERE salary > 1500 OR dept = NULL ORDER BY id");
        // FALSE OR NULL => NULL，不匹配
        assertEquals(0, r3.rowCount());
    }

    @Test
    void innerJoinNullKeysDoNotMatch() {
        QueryResult r = engine.execute(
                "SELECT emp.id eid, dept.name dname FROM emp "
                        + "JOIN dept ON emp.dept = dept.code ORDER BY emp.id");
        // id=3（空部门）不匹配；dept 表的空 code 行不匹配任何员工
        assertEquals(4, r.rowCount());
        assertEquals(List.of(1, 2, 4, 5), ids(r));
        for (Object[] row : r.rows()) {
            assertNotNull(row[1]);
        }
    }

    @Test
    void leftJoinPreservesUnmatchedLeftRows() {
        QueryResult r = engine.execute(
                "SELECT emp.id eid, dept.name dname FROM emp "
                        + "LEFT JOIN dept ON emp.dept = dept.code ORDER BY emp.id");
        assertEquals(5, r.rowCount());
        Object[] nullDeptRow = r.rows().stream()
                .filter(row -> row[0].equals(3)).findFirst().orElseThrow();
        assertNull(nullDeptRow[1], "左连接未匹配行右侧必须补空");
    }

    @Test
    void rightJoinPreservesUnmatchedRightRows() {
        QueryResult r = engine.execute(
                "SELECT emp.id eid, dept.code code FROM emp "
                        + "RIGHT JOIN dept ON emp.dept = dept.code ORDER BY dept.code");
        // ENG(3 名员工) + SALES(1) + NULL code 部门(1 补空) = 5 行
        assertEquals(5, r.rowCount());
        long nullCodeRows = r.rows().stream().filter(row -> row[1] == null).count();
        assertEquals(1, nullCodeRows);
    }

    @Test
    void fullJoinPreservesBothSides() {
        QueryResult r = engine.execute(
                "SELECT emp.id eid, dept.code code FROM emp "
                        + "FULL JOIN dept ON emp.dept = dept.code ORDER BY dept.code NULLS LAST, emp.id NULLS LAST");
        // 3 ENG + 1 SALES + 1 未匹配员工(id=3, 其 code 输出为 null)
        // + 1 未匹配部门(右表 null code 行, eid 输出为 null) = 6
        assertEquals(6, r.rowCount());
        long missingEmp = r.rows().stream().filter(row -> row[0] == null).count();
        // code 为空的有两行：未匹配员工 id=3 与未匹配的空 code 部门
        long missingDept = r.rows().stream().filter(row -> row[1] == null).count();
        assertEquals(1, missingEmp);
        assertEquals(2, missingDept);
    }

    @Test
    void joinDuplicateKeysProduceCartesianMultiplicity() {
        // 两个 ENG 员工(id 1,2,5 实际三个) -> 与单个 ENG 部门匹配，行数=3
        QueryResult r = engine.execute(
                "SELECT count(*) c FROM emp JOIN dept ON emp.dept = dept.code "
                        + "WHERE dept.code = 'ENG'");
        assertEquals(3, r.rows().get(0)[0]);
    }

    @Test
    void groupByNullFormsSingleGroup() {
        QueryResult r = engine.execute(
                "SELECT dept, count(*) c, sum(salary) s FROM emp GROUP BY dept ORDER BY dept NULLS LAST");
        assertEquals(3, r.rowCount()); // ENG, SALES, NULL 三组
        Object[] nullGroup = r.rows().get(2);
        assertNull(nullGroup[0]);
        assertEquals(1, nullGroup[1]);
        assertEquals(300, nullGroup[2]);
    }

    @Test
    void aggregatesIgnoreNullArgumentsButCountStarCountsAll() {
        QueryResult r = engine.execute(
                "SELECT count(*) allRows, count(dept) nonNull, "
                        + "sum(salary) total, avg(salary) average FROM emp");
        Object[] row = r.rows().get(0);
        assertEquals(5, row[0]);
        assertEquals(4, row[1]);
        assertEquals(1100, row[2]);
        assertEquals(220.0, (Double) row[3], 0.0001);
    }

    @Test
    void emptyGroupedResultYieldsNoRows() {
        QueryResult r = engine.execute(
                "SELECT dept, count(*) c FROM emp WHERE salary > 10000 GROUP BY dept");
        assertEquals(0, r.rowCount());
    }

    @Test
    void emptyUngroupedAggregateYieldsOneRow() {
        QueryResult r = engine.execute(
                "SELECT count(*) c, sum(salary) s, avg(salary) a, min(salary) mn "
                        + "FROM emp WHERE salary > 10000");
        assertEquals(1, r.rowCount());
        Object[] row = r.rows().get(0);
        assertEquals(0, row[0]);
        assertNull(row[1]);
        assertNull(row[2]);
        assertNull(row[3]);
    }

    @Test
    void distinctAggregatesDedupValues() {
        QueryResult r = engine.execute(
                "SELECT count(DISTINCT dept) d, count(DISTINCT salary) ds, "
                        + "sum(DISTINCT salary) ss FROM emp");
        Object[] row = r.rows().get(0);
        // dept: ENG/SALES/NULL -> 2 非空不同值
        assertEquals(2, row[0]);
        // salary: 100 重复 -> 3 个不同值 (100,200,300,400 -> 4)
        assertEquals(4, row[1]);
        assertEquals(1000, row[2]);
    }

    @Test
    void nullArithmeticPropagatesNull() {
        QueryResult r = engine.execute(
                "SELECT id, salary + NULL v FROM emp WHERE id = 1");
        assertNull(r.rows().get(0)[1]);
    }

    @Test
    void intDoubleMixedComparison() {
        QueryResult r = engine.execute(
                "SELECT id FROM emp WHERE salary = 100.0 ORDER BY id");
        assertEquals(List.of(1, 5), ids(r));
    }

    @Test
    void nullSortingIsExplicitAndStable() {
        QueryResult ascDefault = engine.execute(
                "SELECT id, salary FROM emp ORDER BY salary ASC");
        // 无 NULL salary 数据，改用 dept
        QueryResult byDept = engine.execute(
                "SELECT id FROM emp ORDER BY dept ASC NULLS LAST");
        assertEquals(3, byDept.rows().get(byDept.rowCount() - 1)[0]);

        QueryResult byDeptFirst = engine.execute(
                "SELECT id FROM emp ORDER BY dept ASC NULLS FIRST");
        assertEquals(3, byDeptFirst.rows().get(0)[0]);

        QueryResult descDefault = engine.execute(
                "SELECT id FROM emp ORDER BY dept DESC");
        // DESC 默认 NULLS FIRST
        assertEquals(3, descDefault.rows().get(0)[0]);
    }

    @Test
    void crossJoinProducesFullProduct() {
        QueryResult r = engine.execute(
                "SELECT count(*) c FROM emp CROSS JOIN dept");
        assertEquals(5 * 3, r.rows().get(0)[0]);
    }

    private List<Object> ids(QueryResult r) {
        return r.rows().stream().map(row -> row[0]).toList();
    }
}
