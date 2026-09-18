package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.column.Vectors;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 哈希分组聚合。语义：
 * <ul>
 *   <li>分组键中的空值归入同一组（与连接不同：GROUP BY 时空值是有效分组值）；</li>
 *   <li>组的输出顺序按首次出现顺序确定，保证跨数据规模/执行顺序结果可复现；</li>
 *   <li>COUNT(*) 统计组内所有行；其它聚合忽略参数为空的行；</li>
 *   <li>无 GROUP BY 的空输入产生一行（COUNT=0，其它聚合=NULL）；带 GROUP BY 时无组输出；</li>
 *   <li>DISTINCT 聚合先对非空参数去重；SUM 溢出抛 DATA_VIOLATION，AVG 返回 DOUBLE；</li>
 *   <li>哈希表与聚合状态计入 MemoryTracker。</li>
 * </ul>
 */
public final class HashAggregate {

    private HashAggregate() {
    }

    /**
     * @param resultType 分析期推导出的输出类型（执行器以它为准构建输出向量）
     */
    public record AggSpec(String outputName, Expr.AggregateCall call, DataType resultType) {
    }

    public static Frame apply(Frame input, List<Expr> groupExprs, List<String> groupOutputNames,
                              List<AggSpec> aggregates, MemoryTracker tracker) {
        ExprEvaluator evaluator = new ExprEvaluator(tracker);

        // 1. 求值分组键
        int keyCount = groupExprs.size();
        List<ColumnVector> keyColumns = new ArrayList<>(keyCount);
        DataType[] keyTypes = new DataType[keyCount];
        long evalBytes = 0;
        for (int k = 0; k < keyCount; k++) {
            ColumnVector v = evaluator.evaluate(groupExprs.get(k), input);
            keyColumns.add(v);
            keyTypes[k] = v.type();
            evalBytes += v.estimatedBytes();
        }

        // 2. 求值聚合参数
        int aggCount = aggregates.size();
        ColumnVector[] argColumns = new ColumnVector[aggCount];
        for (int a = 0; a < aggCount; a++) {
            Expr arg = aggregates.get(a).call().arg();
            if (arg != null) {
                ColumnVector v = evaluator.evaluate(arg, input);
                argColumns[a] = v;
                evalBytes += v.estimatedBytes();
            }
        }
        tracker.reserve(evalBytes);

        // 3. 哈希分组（LinkedHashMap 固定首次出现顺序）
        Map<RowKey, Integer> groupIndex = new LinkedHashMap<>();
        List<GroupState> groups = new ArrayList<>();
        tracker.reserve(48L * Math.max(1, input.rowCount()));
        // 分组键列已按 g0.. 顺序排在独立列表中，索引恒为 0..n-1
        int[] keyIndices = new int[keyCount];
        for (int k = 0; k < keyCount; k++) {
            keyIndices[k] = k;
        }

        for (int row = 0; row < input.rowCount(); row++) {
            RowKey key = RowKey.forGroup(keyColumns, keyIndices, row);
            Integer gi = groupIndex.get(key);
            if (gi == null) {
                gi = groups.size();
                groupIndex.put(key, gi);
                groups.add(new GroupState(key, aggCount));
            }
            GroupState state = groups.get(gi);
            state.rowCount++;
            for (int a = 0; a < aggCount; a++) {
                AggSpec spec = aggregates.get(a);
                ColumnVector arg = argColumns[a];
                if (arg == null) {
                    state.counts[a]++; // COUNT(*)
                    continue;
                }
                if (arg.isNull(row)) {
                    continue; // 聚合忽略空值
                }
                if (spec.call().distinct() && !state.seen[a].add(arg.get(row))) {
                    continue; // DISTINCT：重复值只计一次
                }
                state.counts[a]++;
                accumulate(state, a, spec.call(), arg, row);
            }
        }

        // 4. 无分组键且无输入：产生单一空组
        if (keyCount == 0 && groups.isEmpty()) {
            groups.add(new GroupState(null, aggCount));
        }

        // 5. 物化输出（g0.. 在前，a0.. 在后）
        List<ColumnSchema> schemaCols = new ArrayList<>();
        for (int k = 0; k < keyCount; k++) {
            schemaCols.add(new ColumnSchema(groupOutputNames.get(k), keyTypes[k], true));
        }
        for (AggSpec spec : aggregates) {
            schemaCols.add(new ColumnSchema(spec.outputName(), spec.resultType(), true));
        }
        List<com.github.lechandonga.cqe.column.VectorBuilder> builders = new ArrayList<>();
        for (ColumnSchema cs : schemaCols) {
            builders.add(Vectors.newBuilder(cs.type(), groups.size()));
        }
        for (GroupState state : groups) {
            int col = 0;
            if (state.key != null) {
                for (int k = 0; k < keyCount; k++) {
                    builders.get(col++).append(state.key.valueAt(k));
                }
            } else {
                for (int k = 0; k < keyCount; k++) {
                    builders.get(col++).appendNull();
                }
            }
            for (int a = 0; a < aggCount; a++) {
                appendAggregate(builders.get(col++), aggregates.get(a), state, a);
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

    private static void accumulate(GroupState state, int a, Expr.AggregateCall call,
                                   ColumnVector arg, int row) {
        switch (call.function()) {
            case "COUNT" -> { /* 计数已在外部完成 */ }
            case "SUM" -> {
                double d = ((Number) arg.get(row)).doubleValue();
                state.sum[a] += d;
            }
            case "AVG" -> {
                double d = ((Number) arg.get(row)).doubleValue();
                state.sum[a] += d;
            }
            case "MIN" -> {
                Object v = arg.get(row);
                if (state.mins[a] == null || compareValue(v, state.mins[a]) < 0) {
                    state.mins[a] = v;
                }
            }
            case "MAX" -> {
                Object v = arg.get(row);
                if (state.maxs[a] == null || compareValue(v, state.maxs[a]) > 0) {
                    state.maxs[a] = v;
                }
            }
            default -> throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "未知聚合函数: " + call.function());
        }
    }

    @SuppressWarnings("unchecked")
    private static int compareValue(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable<Object>) a).compareTo(b);
    }

    private static void appendAggregate(com.github.lechandonga.cqe.column.VectorBuilder builder,
                                        AggSpec spec, GroupState state, int a) {
        Expr.AggregateCall call = spec.call();
        long count = state.counts[a];
        switch (call.function()) {
            case "COUNT" -> builder.append((int) Math.min(count, Integer.MAX_VALUE));
            case "SUM" -> {
                if (count == 0) {
                    builder.appendNull();
                } else if (spec.resultType() == DataType.INT) {
                    long sum = (long) state.sum[a];
                    if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) {
                        throw new QueryException(QueryException.Code.DATA_VIOLATION,
                                "SUM(INT) 结果超出 INT 范围");
                    }
                    builder.append((int) sum);
                } else {
                    builder.append(state.sum[a]);
                }
            }
            case "AVG" -> {
                if (count == 0) {
                    builder.appendNull();
                } else {
                    builder.append(state.sum[a] / count);
                }
            }
            case "MIN" -> builder.append(state.mins[a]);
            case "MAX" -> builder.append(state.maxs[a]);
            default -> throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "未知聚合函数: " + call.function());
        }
    }

    private static final class GroupState {
        final RowKey key;
        long rowCount;
        final long[] counts;
        final double[] sum;
        final Object[] mins;
        final Object[] maxs;
        final java.util.Set<Object>[] seen;

        @SuppressWarnings("unchecked")
        GroupState(RowKey key, int aggCount) {
            this.key = key;
            this.counts = new long[aggCount];
            this.sum = new double[aggCount];
            this.mins = new Object[aggCount];
            this.maxs = new Object[aggCount];
            this.seen = new java.util.Set[aggCount];
            for (int i = 0; i < aggCount; i++) {
                this.seen[i] = new java.util.HashSet<>();
            }
        }
    }
}
