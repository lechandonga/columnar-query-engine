package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 内存限额：超限明确失败（FAIL 策略），不得静默截断或无界占用。 */
class MemoryLimitTest {

    private ColumnarQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ColumnarQueryEngine();
        engine.catalog().createTable("big", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("grp", DataType.INT))));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(new Object[]{i, i % 7});
        }
        engine.catalog().insertRows("big", rows);
    }

    @Test
    void tinyLimitFailsExplicitlyOnSort() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM big ORDER BY id",
                        new QueryOptions(64, QueryOptions.MemoryPolicy.FAIL)));
        assertEquals(QueryException.Code.MEMORY_LIMIT, e.code());
        assertTrue(e.getMessage().contains("内存限额"));
    }

    @Test
    void tinyLimitFailsOnJoin() {
        engine.catalog().createTable("ref", new Schema(List.of(
                ColumnSchema.of("k", DataType.INT))));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            rows.add(new Object[]{i});
        }
        engine.catalog().insertRows("ref", rows);
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute(
                        "SELECT big.id FROM big JOIN ref ON big.grp = ref.k",
                        new QueryOptions(128, QueryOptions.MemoryPolicy.FAIL)));
        assertEquals(QueryException.Code.MEMORY_LIMIT, e.code());
    }

    @Test
    void limitFailureDoesNotCorruptData() {
        assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM big ORDER BY id",
                        new QueryOptions(64, QueryOptions.MemoryPolicy.FAIL)));
        // 失败后数据仍可查询，行数与内容不变
        QueryResult count = engine.execute("SELECT count(*) c FROM big");
        assertEquals(10_000, count.rows().get(0)[0]);
        QueryResult first = engine.execute(
                "SELECT id FROM big WHERE id = 42");
        assertEquals(1, first.rowCount());
    }

    @Test
    void generousLimitCompletesAndMatchesUnlimitedResult() {
        QueryResult limited = engine.execute(
                "SELECT grp, count(*) c, sum(id) s FROM big GROUP BY grp ORDER BY grp",
                new QueryOptions(64L * 1024 * 1024, QueryOptions.MemoryPolicy.FAIL));
        QueryResult unlimited = engine.execute(
                "SELECT grp, count(*) c, sum(id) s FROM big GROUP BY grp ORDER BY grp");
        assertEquals(unlimited.rowCount(), limited.rowCount());
        for (int i = 0; i < unlimited.rowCount(); i++) {
            assertArrayEquals(unlimited.rows().get(i), limited.rows().get(i));
        }
    }

    @Test
    void distinctIsBoundedAndFailsAtTinyLimit() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT DISTINCT grp FROM big",
                        new QueryOptions(1, QueryOptions.MemoryPolicy.FAIL)));
        assertEquals(QueryException.Code.MEMORY_LIMIT, e.code());
    }

    @Test
    void invalidOptionsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueryOptions(0, QueryOptions.MemoryPolicy.FAIL));
    }
}
