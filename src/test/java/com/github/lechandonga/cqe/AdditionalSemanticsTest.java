package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.store.TableInsert;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 附加语义：连接、算术异常、字符串、原子事务。 */
class AdditionalSemanticsTest {

    private ColumnarQueryEngine engine() {
        ColumnarQueryEngine e = new ColumnarQueryEngine();
        e.catalog().createTable("t", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("s", DataType.STRING))));
        e.catalog().insertRows("t", List.of(
                new Object[]{1, "a"},
                new Object[]{2, "b"}));
        return e;
    }

    @Test
    void stringConcatenationAndNullPropagation() {
        QueryResult r = engine().execute(
                "SELECT s || '_x' v FROM t WHERE id = 1");
        assertEquals("a_x", r.rows().get(0)[0]);
    }

    @Test
    void integerDivisionByZeroFailsExplicitly() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine().execute("SELECT id / 0 FROM t WHERE id = 1"));
        assertEquals(QueryException.Code.DATA_VIOLATION, e.code());
    }

    @Test
    void integerOverflowFailsExplicitly() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine().execute(
                        "SELECT id * 2000000000 FROM t WHERE id = 2"));
        assertEquals(QueryException.Code.DATA_VIOLATION, e.code());
    }

    @Test
    void selfJoinWithAliasesWorks() {
        ColumnarQueryEngine e = engine();
        QueryResult r = e.execute(
                "SELECT l.id li, r.id ri FROM t l JOIN t r ON l.id = r.id ORDER BY l.id");
        assertEquals(2, r.rowCount());
    }

    @Test
    void globalAggregateWithHaving() {
        ColumnarQueryEngine e = engine();
        assertEquals(1, e.execute("SELECT count(*) c FROM t HAVING count(*) > 1")
                .rowCount());
        assertEquals(0, e.execute("SELECT count(*) c FROM t HAVING count(*) > 99")
                .rowCount());
    }

    @Test
    void orderByAggregateWithoutGroupBy() {
        QueryResult r = engine().execute(
                "SELECT count(*) c FROM t ORDER BY count(*) DESC");
        assertEquals(2, r.rows().get(0)[0]);
    }

    @Test
    void joinWithoutEqualityRejected() {
        ColumnarQueryEngine e = engine();
        e.catalog().createTable("u", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT))));
        assertEquals(QueryException.Code.UNSUPPORTED_FEATURE,
                assertThrows(QueryException.class, () -> e.execute(
                        "SELECT t.id FROM t JOIN u ON t.id > u.id")).code());
    }

    @Test
    void atomicMultiTableInsertIsAllOrNothingForReaders() throws Exception {
        ColumnarQueryEngine e = engine();
        e.catalog().createTable("p", new Schema(List.of(
                ColumnSchema.of("n", DataType.INT))));
        e.catalog().createTable("q", new Schema(List.of(
                ColumnSchema.of("n", DataType.INT))));
        // 一次原子事务写两张表，单查询交叉连接行数必须总是完全平方数
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger bad =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        pool.submit(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    e.catalog().insertRowsAtomically(List.of(
                            TableInsert.of("p", List.<Object[]>of(new Object[]{1})),
                            TableInsert.of("q", List.<Object[]>of(new Object[]{1}))));
                }
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            while (done.getCount() > 0) {
                int cj = (Integer) e.execute(
                        "SELECT count(*) c FROM p CROSS JOIN q")
                        .rows().get(0)[0];
                int root = (int) Math.sqrt(cj);
                if (root * root != cj) {
                    bad.incrementAndGet();
                }
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, bad.get());
    }

    @Test
    void failedAtomicBatchChangesNothing() {
        ColumnarQueryEngine e = engine();
        e.catalog().createTable("p", new Schema(List.of(
                ColumnSchema.of("n", DataType.INT))));
        assertThrows(QueryException.class, () ->
                e.catalog().insertRowsAtomically(List.of(
                        TableInsert.of("t", List.<Object[]>of(new Object[]{9, "z"})),
                        TableInsert.of("p", List.<Object[]>of(new Object[]{"bad"})))));
        assertEquals(2, e.execute("SELECT count(*) c FROM t").rows().get(0)[0]);
        assertEquals(0, e.execute("SELECT count(*) c FROM p").rows().get(0)[0]);
    }
}
