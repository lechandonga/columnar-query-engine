package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 结果顺序与跨数据规模/执行顺序的可复现性。 */
class DeterminismTest {

    private ColumnarQueryEngine engineWith(int rows) {
        ColumnarQueryEngine engine = new ColumnarQueryEngine();
        engine.catalog().createTable("t", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("g", DataType.INT),
                ColumnSchema.of("v", DataType.STRING))));
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            // 大量重复键、重复值，哈希顺序天然不稳定
            data.add(new Object[]{i, i % 3, i % 2 == 0 ? null : "v" + (i % 5)});
        }
        engine.catalog().insertRows("t", data);
        return engine;
    }

    @Test
    void groupOrderIsDeterministicAcrossScales() {
        List<Object> small = engineWith(7).execute(
                "SELECT g, count(*) c FROM t GROUP BY g").rows().stream()
                .map(row -> row[0]).toList();
        List<Object> big = engineWith(100_000).execute(
                "SELECT g, count(*) c FROM t GROUP BY g").rows().stream()
                .map(row -> row[0]).toList();
        assertEquals(small, big, "分组输出顺序必须由首次出现顺序决定，与规模无关");
    }

    @Test
    void repeatedExecutionsAreIdentical() {
        ColumnarQueryEngine engine = engineWith(5_000);
        String sql = "SELECT g, sum(id) s, count(*) c FROM t GROUP BY g ORDER BY g DESC NULLS LAST";
        QueryResult first = engine.execute(sql);
        for (int i = 0; i < 5; i++) {
            QueryResult other = engine.execute(sql);
            assertEquals(first.rowCount(), other.rowCount());
            for (int r = 0; r < first.rowCount(); r++) {
                assertArrayEquals(first.rows().get(r), other.rows().get(r));
            }
        }
    }

    @Test
    void tieBreakerIsStableForDuplicateSortKeys() {
        ColumnarQueryEngine engine = engineWith(100);
        // 仅按重复率极高的 g 排序：等值行必须保持输入顺序（稳定排序）
        QueryResult r1 = engine.execute("SELECT id FROM t ORDER BY g ASC");
        QueryResult r2 = engine.execute("SELECT id FROM t ORDER BY g ASC");
        List<Object> ids1 = r1.rows().stream().map(row -> row[0]).toList();
        List<Object> ids2 = r2.rows().stream().map(row -> row[0]).toList();
        assertEquals(ids1, ids2);
        // g=0 组的 id 必须保持 0,3,6,... 的输入顺序
        List<Object> groupZeroIds = new ArrayList<>();
        for (Object id : ids1) {
            if ((Integer) id % 3 == 0) {
                groupZeroIds.add(id);
            }
        }
        List<Object> expected = new ArrayList<>();
        for (int i = 0; i < 100; i += 3) {
            expected.add(i);
        }
        assertEquals(expected, groupZeroIds);
    }

    @Test
    void limitOffsetIsDeterministicAndOrdered() {
        ColumnarQueryEngine engine = engineWith(1000);
        QueryResult page1 = engine.execute("SELECT id FROM t ORDER BY id ASC LIMIT 10 OFFSET 0");
        QueryResult page2 = engine.execute("SELECT id FROM t ORDER BY id ASC LIMIT 10 OFFSET 10");
        assertEquals(10, page1.rowCount());
        assertEquals(10, page2.rowCount());
        assertEquals(0, page1.rows().get(0)[0]);
        assertEquals(9, page1.rows().get(9)[0]);
        assertEquals(10, page2.rows().get(0)[0]);
        assertEquals(19, page2.rows().get(9)[0]);
    }

    @Test
    void distinctIsDeterministic() {
        ColumnarQueryEngine engine = engineWith(1000);
        QueryResult r1 = engine.execute("SELECT DISTINCT g FROM t ORDER BY g");
        QueryResult r2 = engine.execute("SELECT DISTINCT g FROM t ORDER BY g");
        assertEquals(Arrays.asList(0, 1, 2),
                r1.rows().stream().map(row -> row[0]).toList());
        assertEquals(r1.rows().size(), r2.rows().size());
    }

    @Test
    void joinOrderDoesNotChangeSetSemanticsWithInnerJoins() {
        ColumnarQueryEngine engine = new ColumnarQueryEngine();
        engine.catalog().createTable("x", new Schema(List.of(
                ColumnSchema.of("k", DataType.INT))));
        engine.catalog().createTable("y", new Schema(List.of(
                ColumnSchema.of("k", DataType.INT))));
        engine.catalog().createTable("z", new Schema(List.of(
                ColumnSchema.of("k", DataType.INT))));
        List<Object[]> xs = new ArrayList<>();
        List<Object[]> ys = new ArrayList<>();
        List<Object[]> zs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            xs.add(new Object[]{i});
            ys.add(new Object[]{i % 5});
            zs.add(new Object[]{i % 2});
        }
        engine.catalog().insertRows("x", xs);
        engine.catalog().insertRows("y", ys);
        engine.catalog().insertRows("z", zs);
        QueryResult r1 = engine.execute(
                "SELECT count(*) c FROM x JOIN y ON x.k = y.k JOIN z ON x.k = z.k");
        QueryResult r2 = engine.execute(
                "SELECT count(*) c FROM z JOIN y ON z.k = y.k JOIN x ON x.k = y.k");
        // 不同连接顺序/关联条件表述，行数（多重集语义）必须一致
        assertEquals(r1.rows().get(0)[0], r2.rows().get(0)[0]);
    }
}
