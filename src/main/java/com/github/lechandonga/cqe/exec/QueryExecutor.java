package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.query.AggFunc;
import com.github.lechandonga.cqe.query.Expr;
import com.github.lechandonga.cqe.query.Query;
import com.github.lechandonga.cqe.query.SelectItem;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.storage.Snapshot;
import com.github.lechandonga.cqe.storage.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在一致性快照上执行已校验的查询。
 *
 * 语义约定：
 * - 三值逻辑：任何含 NULL 的比较结果为 UNKNOWN，WHERE/JOIN ON 只放行 TRUE；
 * - 连接键为 NULL 时不匹配（INNER 丢弃、LEFT 保留左侧并补 NULL）；
 * - 分组键中的 NULL 归为同一组；聚合忽略 NULL 输入；
 * - 排序：ASC 时 NULL 排最后，DESC 时 NULL 排最前；稳定排序保证结果可复现；
 * - 中间结果按行记账，超出内存限额即失败，绝不无界占用。
 */
public class QueryExecutor {

    /** 三值逻辑真值。 */
    private enum Tri { TRUE, FALSE, UNKNOWN }

    /** 列定位：表名(小写).列名(小写) → 组合行中的下标。 */
    private record ColumnIndex(Map<String, Integer> positions) {
        int of(Expr.ColumnRef ref) {
            Integer idx = positions.get(key(ref.table(), ref.name()));
            if (idx == null) {
                throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                        "unknown column: " + ref.table() + "." + ref.name());
            }
            return idx;
        }

        static String key(String table, String column) {
            return table.toLowerCase() + "." + column.toLowerCase();
        }
    }

    public ResultSet execute(ValidatedQuery validated, Snapshot snapshot, MemoryTracker memory) {
        Query query = validated.query();
        Table from = snapshot.table(query.fromTable());

        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int i = 0; i < from.columnCount(); i++) {
            positions.put(ColumnIndex.key(from.name(), from.schema().column(i).name()), i);
        }
        Table joinTable = null;
        int joinOffset = from.columnCount();
        if (query.join() != null) {
            joinTable = snapshot.table(query.join().table());
            for (int i = 0; i < joinTable.columnCount(); i++) {
                positions.put(ColumnIndex.key(joinTable.name(), joinTable.schema().column(i).name()),
                        joinOffset + i);
            }
        }
        ColumnIndex index = new ColumnIndex(positions);
        int width = from.columnCount() + (joinTable == null ? 0 : joinTable.columnCount());

        // 1. 扫描 + 连接，产出组合行（确定性顺序：左表行序为主，右表行序为辅）
        List<Object[]> rows = new ArrayList<>();
        for (int li = 0; li < from.rowCount(); li++) {
            Object[] left = readRow(from, li, width);
            if (joinTable == null) {
                materialize(rows, left, memory);
                continue;
            }
            boolean matched = false;
            for (int ri = 0; ri < joinTable.rowCount(); ri++) {
                Object[] combined = readRow(from, li, width);
                for (int c = 0; c < joinTable.columnCount(); c++) {
                    combined[joinOffset + c] = joinTable.column(c).get(ri);
                }
                if (evalTri(query.join().on(), combined, index) == Tri.TRUE) {
                    matched = true;
                    materialize(rows, combined, memory);
                }
            }
            if (!matched && query.join().type() == Query.JoinType.LEFT) {
                materialize(rows, left, memory); // 右侧列保持 NULL
            }
        }

        // 2. WHERE 过滤（只放行 TRUE）
        if (query.where() != null) {
            List<Object[]> filtered = new ArrayList<>();
            for (Object[] row : rows) {
                if (evalTri(query.where(), row, index) == Tri.TRUE) {
                    materialize(filtered, row, memory);
                }
            }
            rows = filtered;
        }

        // 3. 投影 / 分组聚合
        boolean aggregate = !query.groupBy().isEmpty()
                || query.select().stream().anyMatch(i -> i instanceof SelectItem.AggItem);
        List<Object[]> output;
        if (aggregate) {
            output = aggregate(query, rows, index, memory);
        } else {
            output = new ArrayList<>();
            for (Object[] row : rows) {
                Object[] out = new Object[query.select().size()];
                for (int i = 0; i < query.select().size(); i++) {
                    SelectItem.ExprItem item = (SelectItem.ExprItem) query.select().get(i);
                    out[i] = eval(item.expr(), row, index);
                }
                materialize(output, out, memory);
            }
        }

        // 4. ORDER BY（稳定排序；NULL：ASC 最后、DESC 最前）
        if (!query.orderBy().isEmpty()) {
            List<String> outNames = validated.outputSchema().stream()
                    .map(c -> c.name().toLowerCase()).toList();
            Comparator<Object[]> cmp = null;
            for (Query.OrderItem oi : query.orderBy()) {
                int colIdx = outNames.indexOf(oi.column().name().toLowerCase());
                Comparator<Object[]> c = (a, b) ->
                        compareNullable(a[colIdx], b[colIdx], oi.ascending());
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            output.sort(cmp);
        }

        // 5. LIMIT 截断
        if (query.limit() != null && output.size() > query.limit()) {
            output = new ArrayList<>(output.subList(0, query.limit()));
        }

        return new ResultSet(validated.outputSchema(), output);
    }

    private static void materialize(List<Object[]> target, Object[] row, MemoryTracker memory) {
        memory.allocate(MemoryTracker.estimateRowBytes(row));
        target.add(row);
    }

    private static Object[] readRow(Table table, int rowIdx, int width) {
        Object[] row = new Object[width];
        for (int c = 0; c < table.columnCount(); c++) {
            row[c] = table.column(c).get(rowIdx);
        }
        return row;
    }

    // ---- 分组聚合 ----

    private List<Object[]> aggregate(Query query, List<Object[]> rows,
                                     ColumnIndex index, MemoryTracker memory) {
        // LinkedHashMap 保证组顺序 = 键首次出现顺序，与执行线程数无关、可复现
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] row : rows) {
            List<Object> key = new ArrayList<>(query.groupBy().size());
            for (Expr.ColumnRef g : query.groupBy()) {
                key.add(row[index.of(g)]); // NULL 键归为同组
            }
            List<Object[]> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(key, bucket);
            }
            memory.allocate(MemoryTracker.estimateRowBytes(row));
            bucket.add(row);
        }
        if (groups.isEmpty() && query.groupBy().isEmpty()) {
            groups.put(List.of(), List.of()); // 无 GROUP BY 的全表聚合：空输入也产出一行
        }

        List<Object[]> output = new ArrayList<>();
        for (List<Object[]> bucket : groups.values()) {
            Object[] out = new Object[query.select().size()];
            for (int i = 0; i < query.select().size(); i++) {
                SelectItem item = query.select().get(i);
                if (item instanceof SelectItem.ExprItem ei) {
                    // 校验已保证是分组键，取组内任意一行（键值全组相同）
                    out[i] = bucket.isEmpty() ? null : eval(ei.expr(), bucket.get(0), index);
                } else {
                    SelectItem.AggItem ai = (SelectItem.AggItem) item;
                    out[i] = computeAggregate(ai, bucket, index);
                }
            }
            materialize(output, out, memory);
        }
        return output;
    }

    private Object computeAggregate(SelectItem.AggItem agg, List<Object[]> rows, ColumnIndex index) {
        switch (agg.func()) {
            case COUNT -> {
                if (agg.arg() == null) {
                    return (long) rows.size();
                }
                int col = index.of(agg.arg());
                long count = 0;
                for (Object[] row : rows) {
                    if (row[col] != null) count++;
                }
                return count;
            }
            case SUM -> {
                int col = index.of(agg.arg());
                boolean any = false;
                boolean floating = false;
                long longSum = 0;
                double doubleSum = 0;
                for (Object[] row : rows) {
                    Object v = row[col];
                    if (v == null) continue; // 忽略 NULL
                    any = true;
                    if (v instanceof Double d) {
                        if (!floating) {
                            doubleSum = longSum; // 整数部分并入浮点累加
                            floating = true;
                        }
                        doubleSum += d;
                    } else if (floating) {
                        doubleSum += ((Number) v).doubleValue();
                    } else {
                        longSum += ((Number) v).longValue();
                    }
                }
                if (!any) return null;
                // 注意：不能写成三元式，double/long 会发生数值提升导致类型丢失
                if (floating) return doubleSum;
                return longSum;
            }
            case AVG -> {
                int col = index.of(agg.arg());
                long count = 0;
                double sum = 0;
                for (Object[] row : rows) {
                    Object v = row[col];
                    if (v == null) continue;
                    count++;
                    sum += ((Number) v).doubleValue();
                }
                return count == 0 ? null : sum / count;
            }
            case MIN, MAX -> {
                int col = index.of(agg.arg());
                Object best = null;
                for (Object[] row : rows) {
                    Object v = row[col];
                    if (v == null) continue;
                    if (best == null
                            || (agg.func() == AggFunc.MIN ? compareValues(v, best) < 0
                                                          : compareValues(v, best) > 0)) {
                        best = v;
                    }
                }
                return best;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    // ---- 表达式求值（三值逻辑） ----

    private Tri evalTri(Expr expr, Object[] row, ColumnIndex index) {
        return switch (expr) {
            case Expr.Compare c -> {
                Object l = eval(c.left(), row, index);
                Object r = eval(c.right(), row, index);
                if (l == null || r == null) yield Tri.UNKNOWN;
                int cmp = compareValues(l, r);
                yield switch (c.op()) {
                    case EQ -> cmp == 0 ? Tri.TRUE : Tri.FALSE;
                    case NE -> cmp != 0 ? Tri.TRUE : Tri.FALSE;
                    case LT -> cmp < 0 ? Tri.TRUE : Tri.FALSE;
                    case LE -> cmp <= 0 ? Tri.TRUE : Tri.FALSE;
                    case GT -> cmp > 0 ? Tri.TRUE : Tri.FALSE;
                    case GE -> cmp >= 0 ? Tri.TRUE : Tri.FALSE;
                };
            }
            case Expr.Logic lg -> {
                Tri l = evalTri(lg.left(), row, index);
                Tri r = evalTri(lg.right(), row, index);
                yield switch (lg.op()) {
                    case AND -> (l == Tri.FALSE || r == Tri.FALSE) ? Tri.FALSE
                            : (l == Tri.TRUE && r == Tri.TRUE) ? Tri.TRUE : Tri.UNKNOWN;
                    case OR -> (l == Tri.TRUE || r == Tri.TRUE) ? Tri.TRUE
                            : (l == Tri.FALSE && r == Tri.FALSE) ? Tri.FALSE : Tri.UNKNOWN;
                };
            }
            case Expr.Not n -> switch (evalTri(n.inner(), row, index)) {
                case TRUE -> Tri.FALSE;
                case FALSE -> Tri.TRUE;
                case UNKNOWN -> Tri.UNKNOWN;
            };
            case Expr.IsNull is -> {
                boolean isNull = eval(is.inner(), row, index) == null;
                boolean result = is.negated() ? !isNull : isNull;
                yield result ? Tri.TRUE : Tri.FALSE;
            }
            case Expr.Literal lit -> lit.value() == null ? Tri.UNKNOWN
                    : Boolean.TRUE.equals(lit.value()) ? Tri.TRUE : Tri.FALSE;
            case Expr.ColumnRef cr -> {
                Object v = row[index.of(cr)];
                yield v == null ? Tri.UNKNOWN : Boolean.TRUE.equals(v) ? Tri.TRUE : Tri.FALSE;
            }
        };
    }

    private Object eval(Expr expr, Object[] row, ColumnIndex index) {
        return switch (expr) {
            case Expr.Literal lit -> lit.value();
            case Expr.ColumnRef cr -> row[index.of(cr)];
            default -> evalTri(expr, row, index) == Tri.TRUE ? Boolean.TRUE
                    : evalTri(expr, row, index) == Tri.FALSE ? Boolean.FALSE : null;
        };
    }

    // ---- 值比较 ----

    /** 排序比较：ASC 时 NULL 最后，DESC 时 NULL 最前。 */
    private static int compareNullable(Object a, Object b, boolean ascending) {
        if (a == null && b == null) return 0;
        if (a == null) return ascending ? 1 : -1;
        if (b == null) return ascending ? -1 : 1;
        int cmp = compareValues(a, b);
        return ascending ? cmp : -cmp;
    }

    /** 跨类型数值比较用 BigDecimal，避免 long→double 精度损失导致结果不可复现。 */
    static int compareValues(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return toBigDecimal(na).compareTo(toBigDecimal(nb));
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.compareTo(bb);
        }
        throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                "cannot compare values of different types: "
                        + a.getClass().getSimpleName() + " vs " + b.getClass().getSimpleName());
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof Double d) return BigDecimal.valueOf(d);
        return BigDecimal.valueOf(n.longValue());
    }
}
