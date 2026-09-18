package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.QueryOptions;
import com.github.lechandonga.cqe.analyze.AnalyzedQuery;
import com.github.lechandonga.cqe.column.BooleanVector;
import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.column.Vectors;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 扫描→连接→过滤→分组聚合→HAVING→投影→去重→排序→截断 的流水线执行。
 * 所有中间状态不可变；工作内存统一计入 MemoryTracker，超限即明确失败。
 */
public final class ExecutionEngine {

    public Frame execute(AnalyzedQuery query, QueryOptions options) {
        MemoryTracker tracker = new MemoryTracker(
                options.memoryLimitBytes(), options.memoryPolicy());
        ExprEvaluator evaluator = new ExprEvaluator(tracker);
        Frame preProjectionFrame;

        // 1. 扫描主表（快照在分析期固定，此处不可能读到半写入状态）
        Frame frame = scanFrame(query.sources().get(0));

        // 2. 依次连接
        for (int j = 0; j < query.joinTypes().size(); j++) {
            Frame right = scanFrame(query.sources().get(j + 1));
            SelectStatement.JoinType type = query.joinTypes().get(j);
            frame = type == SelectStatement.JoinType.CROSS
                    ? HashJoin.cartesian(frame, right, null, tracker)
                    : HashJoin.apply(frame, right, type, query.boundOn().get(j), tracker);
        }

        // 3. WHERE 过滤
        if (query.boundWhere() != null) {
            frame = filter(frame, query.boundWhere(), evaluator, tracker);
        }

        Frame sortKeyFrame;
        if (query.aggregateQuery()) {
            // 4. 分组聚合
            frame = HashAggregate.apply(frame, query.boundGroupExprs(),
                    query.groupOutputNames(), query.aggregates(), tracker);
            // 5. HAVING 过滤
            if (query.postAggregateHaving() != null) {
                frame = filter(frame, query.postAggregateHaving(), evaluator, tracker);
            }
            preProjectionFrame = frame;
            // 6. 投影（g/a 帧）
            frame = project(frame, query.projections(), query.outputSchema(),
                    evaluator, tracker);

        } else {
            preProjectionFrame = frame;
            // 6'. 投影（基础帧）
            frame = project(frame, query.projections(), query.outputSchema(),
                    evaluator, tracker);
        }

        // 7. 去重（必须在排序前完成）。
        // 去重后排序键只能引用输出列（分析器已强制），因此键帧就是输出帧。
        if (query.distinct()) {
            frame = distinct(frame, tracker);
            sortKeyFrame = frame;
        } else {
            // 非去重查询允许按未输出的列排序：排序键在投影前帧上求值，
            // 别名引用通过附加的输出列（输出名 -> 已投影向量）解析。
            sortKeyFrame = appendOutputAliasColumns(preProjectionFrame, frame, query);
        }

        // 8. 排序 + OFFSET/LIMIT（对输出帧按键帧顺序重排）
        if (!query.order().isEmpty() || query.offset() > 0 || query.limit() != null) {
            List<SelectStatement.OrderItem> items = new ArrayList<>();
            for (AnalyzedQuery.ResolvedOrder order : query.order()) {
                Expr expr;
                if (order.distinctOrderIndex() >= 0) {
                    String outputName = query.outputSchema()
                            .get(order.distinctOrderIndex()).name();
                    expr = new Expr.ColumnRef(null, outputName);
                } else {
                    expr = order.boundExpr();
                }
                items.add(new SelectStatement.OrderItem(expr,
                        order.ascending(), order.nullsFirst()));
            }
            frame = SortOperator.apply(sortKeyFrame, frame, items,
                    query.offset(), query.limit(), tracker);
        }

        return frame;
    }

    private Frame scanFrame(AnalyzedQuery.SourceTable source) {
        // 快照中的列名加别名前缀，作为执行期规范名
        List<ColumnSchema> schemaCols = new ArrayList<>();
        for (ColumnSchema cs : source.table().schema().columns()) {
            schemaCols.add(new ColumnSchema(
                    source.alias() + "." + cs.name(), cs.type(), cs.nullable()));
        }
        return new Frame(new Schema(schemaCols, false), source.table().columns());
    }

    /**
     * 在投影前帧上附加投影输出列（以输出名命名，复用不可变输出向量），
     * 使 ORDER BY 既能引用投影前列（如未输出的分组键/基础列），也能引用输出别名。
     * 输出名与投影前规范名冲突时以投影前规范名优先（附加列跳过重名）。
     */
    private Frame appendOutputAliasColumns(Frame preProjection, Frame output,
                                           AnalyzedQuery query) {
        List<ColumnSchema> schemaCols = new ArrayList<>(preProjection.schema().columns());
        List<ColumnVector> columns = new ArrayList<>(preProjection.columns());
        for (int i = 0; i < query.projections().size(); i++) {
            String name = query.outputSchema().get(i).name();
            if (preProjection.indexOf(name) < 0) {
                schemaCols.add(new ColumnSchema(name,
                        query.outputSchema().get(i).type(), true));
                columns.add(output.column(i));
            }
        }
        return new Frame(new Schema(schemaCols, false), columns);
    }

    private Frame filter(Frame input, Expr condition, ExprEvaluator evaluator,
                         MemoryTracker tracker) {
        ColumnVector result = evaluator.evaluate(condition, input);
        BooleanVector bools = (BooleanVector) result;
        int[] selected = new int[input.rowCount()];
        int count = 0;
        for (int i = 0; i < input.rowCount(); i++) {
            // 三值逻辑：NULL 不满足过滤条件
            if (!bools.isNull(i) && bools.getBoolean(i)) {
                selected[count++] = i;
            }
        }
        return selectRows(input, selected, count, tracker);
    }

    private Frame project(Frame input, List<AnalyzedQuery.NamedExpr> projections,
                          Schema outputSchema, ExprEvaluator evaluator,
                          MemoryTracker tracker) {
        List<ColumnVector> columns = new ArrayList<>(projections.size());
        long bytes = 0;
        for (AnalyzedQuery.NamedExpr projection : projections) {
            ColumnVector v = evaluator.evaluate(projection.expr(), input);
            columns.add(v);
            bytes += v.estimatedBytes();
        }
        // 投影输出向量在此统一记账（求值器对中间临时向量不记账）
        tracker.reserve(bytes);
        return new Frame(outputSchema, columns);
    }

    private Frame distinct(Frame input, MemoryTracker tracker) {
        int n = input.rowCount();
        int width = input.schema().size();
        Set<MaterializedKey> seen = new HashSet<>();
        tracker.reserve(48L * n);
        int[] selected = new int[n];
        int count = 0;
        for (int row = 0; row < n; row++) {
            Object[] values = new Object[width];
            for (int c = 0; c < width; c++) {
                values[c] = input.column(c).get(row);
            }
            if (seen.add(new MaterializedKey(values))) {
                selected[count++] = row;
            }
        }
        return selectRows(input, selected, count, tracker);
    }

    private Frame selectRows(Frame input, int[] selected, int count, MemoryTracker tracker) {
        List<ColumnSchema> schemaCols = new ArrayList<>(input.schema().columns());
        List<com.github.lechandonga.cqe.column.VectorBuilder> builders = new ArrayList<>();
        for (ColumnSchema cs : schemaCols) {
            builders.add(Vectors.newBuilder(cs.type(), count));
        }
        for (int i = 0; i < count; i++) {
            int row = selected[i];
            for (int c = 0; c < input.schema().size(); c++) {
                builders.get(c).append(input.column(c).get(row));
            }
        }
        long bytes = 0;
        List<ColumnVector> columns = new ArrayList<>(builders.size());
        for (var builder : builders) {
            ColumnVector v = builder.build();
            bytes += v.estimatedBytes();
            columns.add(v);
        }
        tracker.reserve(bytes);
        return new Frame(new Schema(schemaCols, false), columns);
    }

    /** 全行值的结构化相等键（null 相等语义，用于 DISTINCT/分组去重）。 */
    private record MaterializedKey(Object[] values) {
        @Override
        public boolean equals(Object o) {
            return o instanceof MaterializedKey other
                    && java.util.Arrays.deepEquals(values, other.values);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.deepHashCode(values);
        }
    }
}
