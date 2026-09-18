package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.column.Vectors;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 确定性多键排序：
 * <ul>
 *   <li>NULL 顺序由 NULLS FIRST/LAST 显式声明；</li>
 *   <li>所有排序键完全相等时按输入行下标保持稳定顺序，结果可复现；</li>
 *   <li>排序键在“投影前帧”上求值，但用于对投影后的输出帧重排
 *       （因此非 DISTINCT 查询可按未输出的列排序）；</li>
 *   <li>排序后应用 OFFSET/LIMIT；输出向量工作内存计入 MemoryTracker。</li>
 * </ul>
 */
public final class SortOperator {

    private SortOperator() {
    }

    /**
     * @param keyFrame 排序键求值所用帧（投影前）
     * @param output   待重排的输出帧（与 keyFrame 行数一致；DISTINCT 时二者同为去重后帧）
     */
    public static Frame apply(Frame keyFrame, Frame output,
                              List<SelectStatement.OrderItem> keys,
                              long offset, Long limit, MemoryTracker tracker) {
        int n = output.rowCount();

        ExprEvaluator evaluator = new ExprEvaluator(tracker);
        List<ColumnVector> keyVectors = new ArrayList<>(keys.size());
        long keyBytes = 0;
        for (SelectStatement.OrderItem key : keys) {
            ColumnVector kv = evaluator.evaluate(key.expr(), keyFrame);
            keyVectors.add(kv);
            keyBytes += kv.estimatedBytes();
        }
        tracker.reserve(keyBytes + 4L * n);

        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(buildComparator(keyVectors, keys));

        long skip = Math.min(offset, n);
        long end = limit == null ? n : Math.min(n, skip + limit);

        List<ColumnSchema> schemaCols = new ArrayList<>(output.schema().columns());
        List<com.github.lechandonga.cqe.column.VectorBuilder> builders = new ArrayList<>();
        for (ColumnSchema cs : schemaCols) {
            builders.add(Vectors.newBuilder(cs.type(), (int) Math.max(0, end - skip)));
        }
        for (long pos = skip; pos < end; pos++) {
            int sourceRow = order.get((int) pos);
            for (int c = 0; c < output.schema().size(); c++) {
                builders.get(c).append(output.column(c).get(sourceRow));
            }
        }
        long bytes = 0;
        List<ColumnVector> out = new ArrayList<>(builders.size());
        for (var b : builders) {
            ColumnVector v = b.build();
            bytes += v.estimatedBytes();
            out.add(v);
        }
        tracker.reserve(bytes);
        return new Frame(new Schema(schemaCols, false), out);
    }

    private static Comparator<Integer> buildComparator(List<ColumnVector> keyVectors,
                                                       List<SelectStatement.OrderItem> keys) {
        Comparator<Integer> comparator = Comparator.comparingInt(i -> i); // 稳定回退
        for (int k = keys.size() - 1; k >= 0; k--) {
            SelectStatement.OrderItem item = keys.get(k);
            ColumnVector vector = keyVectors.get(k);
            final boolean asc = item.ascending();
            final boolean nullsFirst = item.nullsFirst();
            Comparator<Integer> cmp = (a, b) -> {
                boolean an = vector.isNull(a);
                boolean bn = vector.isNull(b);
                if (an && bn) {
                    return 0;
                }
                if (an) {
                    return nullsFirst ? -1 : 1;
                }
                if (bn) {
                    return nullsFirst ? 1 : -1;
                }
                int c = compare(vector, a, b);
                return asc ? c : -c;
            };
            comparator = cmp.thenComparing(comparator);
        }
        return comparator;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(ColumnVector vector, int a, int b) {
        Object va = vector.get(a);
        Object vb = vector.get(b);
        if (va instanceof Number && vb instanceof Number) {
            return Double.compare(((Number) va).doubleValue(), ((Number) vb).doubleValue());
        }
        if (va instanceof Comparable && va.getClass() == vb.getClass()) {
            return ((Comparable) va).compareTo(vb);
        }
        return va.toString().compareTo(vb.toString());
    }
}
