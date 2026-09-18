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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 内存限额：超限必须明确失败；同一数据在不同限额下结果可复现、可解释。 */
class MemoryLimitTest {

    private DataStore store;

    @BeforeEach
    void setUp() {
        store = new DataStore();
        store.createTable("t", new TableSchema(List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("v", DataType.STRING))));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(new Object[]{i, "value-" + i});
        }
        store.insertRows("t", rows);
    }

    @Test
    void exceedsLimitFailsWithMemoryCategory() {
        QueryEngine tight = new QueryEngine(store, 1024); // 1KB 远远不够
        QueryException e = assertThrows(QueryException.class,
                () -> tight.execute("SELECT id, v FROM t"));
        assertEquals(QueryException.Category.MEMORY_LIMIT_EXCEEDED, e.category());
        assertTrue(e.getMessage().contains("limit"));
    }

    @Test
    void sufficientLimitSucceeds() {
        QueryEngine loose = new QueryEngine(store, 16L * 1024 * 1024);
        ResultSet rs = loose.execute("SELECT id, v FROM t");
        assertEquals(1000, rs.rowCount());
    }

    @Test
    void resultIsReproducibleAcrossLimits() {
        // 足够大的两个限额下，结果必须完全一致
        QueryEngine a = new QueryEngine(store, 8L * 1024 * 1024);
        QueryEngine b = new QueryEngine(store, 64L * 1024 * 1024);
        String sql = "SELECT id, v FROM t ORDER BY id DESC LIMIT 50";
        ResultSet ra = a.execute(sql);
        ResultSet rb = b.execute(sql);
        assertEquals(ra.rows().stream().map(java.util.Arrays::asList).toList(),
                rb.rows().stream().map(java.util.Arrays::asList).toList());
    }

    @Test
    void limitFailureIsDeterministic() {
        // 同一限额下重复执行：要么都成功要么都以同一类别失败
        QueryEngine tight = new QueryEngine(store, 2048);
        for (int i = 0; i < 3; i++) {
            QueryException e = assertThrows(QueryException.class,
                    () -> tight.execute("SELECT id, v FROM t"));
            assertEquals(QueryException.Category.MEMORY_LIMIT_EXCEEDED, e.category());
        }
    }

    @Test
    void failedQueryDoesNotCorruptData() {
        QueryEngine tight = new QueryEngine(store, 1024);
        assertThrows(QueryException.class, () -> tight.execute("SELECT id, v FROM t"));
        // 失败后数据仍然完整可读
        QueryEngine loose = new QueryEngine(store, 16L * 1024 * 1024);
        assertEquals(1000L, loose.execute("SELECT COUNT(*) FROM t").valueAt(0, 0));
    }

    @Test
    void limitAppliesToAggregationsToo() {
        QueryEngine tight = new QueryEngine(store, 512);
        assertThrows(QueryException.class,
                () -> tight.execute("SELECT v, COUNT(*) FROM t GROUP BY v"));
    }
}
