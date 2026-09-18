package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 并发查询与查询期间的数据变更：快照一致、无半写入状态、失败更新不破坏旧数据。 */
class ConcurrentAccessTest {

    private ColumnarQueryEngine engineWithCounter() {
        ColumnarQueryEngine engine = new ColumnarQueryEngine();
        engine.catalog().createTable("counter", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT))));
        List<Object[]> seed = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            seed.add(new Object[]{i});
        }
        engine.catalog().insertRows("counter", seed);
        return engine;
    }

    @Test
    void readersNeverSeePartialWrites() throws Exception {
        ColumnarQueryEngine engine = engineWithCounter();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Integer> observedCounts = Collections.synchronizedList(new ArrayList<>());

        // 4 个写入线程，每批 500 行
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int batch = 0; batch < 10; batch++) {
                        List<Object[]> rows = new ArrayList<>();
                        for (int i = 0; i < 500; i++) {
                            rows.add(new Object[]{1});
                        }
                        engine.catalog().insertRows("counter", rows);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        // 4 个只读线程：count(*) 必须永远是 1000 的整数倍（只看到完整批次）
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        QueryResult r = engine.execute(
                                "SELECT count(*) c FROM counter");
                        int c = (Integer) r.rows().get(0)[0];
                        observedCounts.add(c);
                        if ((c - 1_000) % 500 != 0 || c < 1_000) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "并发读写期间出现错误或读到了半写入状态");

        QueryResult finalCount = engine.execute("SELECT count(*) c FROM counter");
        assertEquals(1_000 + 4 * 10 * 500, finalCount.rows().get(0)[0]);
    }

    @Test
    void querySeesOneConsistentSnapshotAcrossTables() throws Exception {
        // 关键性质：单条查询在分析时刻原子抓取所有来源表快照。
        // 写入者对两表按“每批各加 10 行”推进，因此任何已提交时刻两表行数差至多为 10。
        // 若查询看到的两表快照来自不同时间点，行数差可能累积到任意值 —— 这是要排除的。
        ColumnarQueryEngine engine = new ColumnarQueryEngine();
        engine.catalog().createTable("a", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT))));
        engine.catalog().createTable("b", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT))));

        int writerBatches = 300;
        int batchSize = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger violations = new AtomicInteger();
        CountDownLatch writersDone = new CountDownLatch(1);

        pool.submit(() -> {
            try {
                List<Object[]> rows = new ArrayList<>();
                for (int i = 0; i < batchSize; i++) {
                    rows.add(new Object[]{1});
                }
                for (int i = 0; i < writerBatches; i++) {
                    // 两表作为单个事务原子提交：读者不可能只看到其中一张表的批次
                    engine.catalog().insertRowsAtomically(List.of(
                            com.github.lechandonga.cqe.store.TableInsert.of("a", rows),
                            com.github.lechandonga.cqe.store.TableInsert.of("b", rows)));
                }
            } finally {
                writersDone.countDown();
            }
        });
        pool.submit(() -> {
            // 同一条查询读取两表：CROSS JOIN 行数必须等于快照行数的平方
            // （两表初始为空且每批增量相同；一致快照下两表行数必然相等）。
            while (writersDone.getCount() > 0) {
                QueryResult joined = engine.execute(
                        "SELECT count(*) c FROM a CROSS JOIN b");
                int cj = (Integer) joined.rows().get(0)[0];
                int root = (int) Math.sqrt(cj);
                if (root * root != cj) {
                    violations.incrementAndGet();
                }
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发读写未在时限内结束");
        assertEquals(0, violations.get(),
                "单查询读到了来自不同时间点的两表快照（半写入视图）");
    }

    @Test
    void failedInsertLeavesOldDataIntact() {
        ColumnarQueryEngine engine = engineWithCounter();
        // 构造一个必然失败的批次：列数不匹配
        List<Object[]> bad = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            bad.add(i == 50 ? new Object[]{} : new Object[]{9});
        }
        QueryException e = assertThrows(QueryException.class,
                () -> engine.catalog().insertRows("counter", bad));
        assertEquals(QueryException.Code.DATA_VIOLATION, e.code());

        QueryResult r = engine.execute("SELECT count(*) c FROM counter");
        assertEquals(1_000, r.rows().get(0)[0]);
    }

    @Test
    void failedInsertByTypeMismatchIsAtomic() {
        ColumnarQueryEngine engine = new ColumnarQueryEngine();
        engine.catalog().createTable("t", new Schema(List.of(
                ColumnSchema.of("n", DataType.INT))));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1});
        rows.add(new Object[]{"not-a-number"}); // 第二行类型不合法
        assertThrows(QueryException.class,
                () -> engine.catalog().insertRows("t", rows));
        // 连第一行都不应可见（整体失败）
        QueryResult r = engine.execute("SELECT count(*) c FROM t");
        assertEquals(0, r.rows().get(0)[0]);
    }

    @Test
    void failingQueryUnderMemoryLimitKeepsOtherQueriesWorking() throws Exception {
        ColumnarQueryEngine engine = engineWithCounter();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger unexpected = new AtomicInteger();
        for (int t = 0; t < 4; t++) {
            final int tiny = t % 2;
            pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    try {
                        if (tiny == 1) {
                            engine.execute("SELECT id FROM counter ORDER BY id",
                                    new QueryOptions(32, QueryOptions.MemoryPolicy.FAIL));
                            unexpected.incrementAndGet();
                        } else {
                            QueryResult r = engine.execute(
                                    "SELECT count(*) c FROM counter");
                            if (!r.rows().get(0)[0].equals(1_000)) {
                                unexpected.incrementAndGet();
                            }
                        }
                    } catch (QueryException expected) {
                        if (expected.code() != QueryException.Code.MEMORY_LIMIT) {
                            unexpected.incrementAndGet();
                        }
                    }
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, unexpected.get());
    }
}
