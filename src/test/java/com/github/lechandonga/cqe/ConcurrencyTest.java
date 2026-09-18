package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.engine.QueryEngine;
import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.exec.ResultSet;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.schema.TableSchema;
import com.github.lechandonga.cqe.storage.DataStore;
import com.github.lechandonga.cqe.storage.Snapshot;
import com.github.lechandonga.cqe.type.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 并发查询与查询期间的数据变更：查询看到一致快照，失败变更不破坏数据。 */
class ConcurrencyTest {

    private DataStore store;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        store = new DataStore();
        store.createTable("t", new TableSchema(List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("v", DataType.INT))));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(new Object[]{i, i * 10});
        }
        store.insertRows("t", rows);
        engine = new QueryEngine(store);
    }

    @Test
    void snapshotIsStableWhileDataChanges() {
        Snapshot snap = store.snapshot();
        int before = snap.table("t").rowCount();
        // 快照持有期间发生写入
        store.insertRows("t", List.<Object[]>of(new Object[]{1000, 1000}));
        store.addColumn("t", new ColumnSchema("extra", DataType.STRING), "x");
        // 快照内容不变
        assertEquals(before, snap.table("t").rowCount());
        assertEquals(-1, snap.table("t").schema().indexOf("extra"));
        // 新快照能看到变更
        assertEquals(before + 1, store.snapshot().table("t").rowCount());
    }

    @Test
    void concurrentQueriesAndWritesAllSucceed() throws Exception {
        int threads = 8;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            boolean writer = t % 2 == 0;
            int id = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        if (writer) {
                            store.insertRows("t", List.<Object[]>of(new Object[]{1000 + id * rounds + r, r}));
                        } else {
                            ResultSet rs = engine.execute("SELECT COUNT(*) FROM t");
                            long count = (Long) rs.valueAt(0, 0);
                            assertTrue(count >= 100, "count must never go below initial rows");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS); // 任何线程异常都会在此暴露
        }
        pool.shutdown();
        // 全部写入都已原子生效
        long expected = 100 + (threads / 2L) * rounds;
        assertEquals(expected, store.snapshot().table("t").rowCount());
    }

    @Test
    void failedInsertLeavesDataUntouched() {
        int before = store.snapshot().table("t").rowCount();
        // 第二批中有一行类型错误：整批必须被拒绝
        List<Object[]> bad = new ArrayList<>();
        bad.add(new Object[]{500, 1});
        bad.add(new Object[]{501, "not-an-int"});
        QueryException e = assertThrows(QueryException.class, () -> store.insertRows("t", bad));
        assertEquals(QueryException.Category.TYPE_MISMATCH, e.category());
        assertEquals(before, store.snapshot().table("t").rowCount());
    }

    @Test
    void failedSchemaChangeLeavesSchemaUntouched() {
        // 添加重名列必须失败且不改变现状
        assertThrows(QueryException.class,
                () -> store.addColumn("t", new ColumnSchema("v", DataType.INT), 0));
        assertEquals(2, store.snapshot().table("t").schema().size());
        // 删除不存在的列必须失败且不改变现状
        assertThrows(QueryException.class, () -> store.dropColumn("t", "nope"));
        assertEquals(2, store.snapshot().table("t").schema().size());
    }

    @Test
    void queryDuringConcurrentWritesNeverSeesPartialState() throws Exception {
        // 写入线程批量插入；查询线程反复聚合，结果必须始终是合法快照上的值
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    store.insertRows("t", List.<Object[]>of(new Object[]{2000 + i, i}));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        for (int i = 0; i < 100; i++) {
            ResultSet rs = engine.execute("SELECT COUNT(*), SUM(id) FROM t");
            long count = (Long) rs.valueAt(0, 0);
            long sum = (Long) rs.valueAt(0, 1);
            // 校验快照一致性：count 与 sum 必须来自同一快照
            // 初始行贡献 sum0；每次插入 id=2000+k 是原子的
            long base = 100;
            long baseSum = 0;
            for (int k = 0; k < 100; k++) baseSum += k;
            long extra = count - base;
            long expectedSum = baseSum;
            // 若看到 n 条额外行，它们必然是前 n 次插入（id 连续），sum 必须精确匹配
            for (long k = 0; k < extra; k++) expectedSum += 2000 + k;
            assertEquals(expectedSum, sum,
                    "count and sum must come from the same consistent snapshot");
        }
        writer.get(30, TimeUnit.SECONDS);
        pool.shutdown();
    }
}
