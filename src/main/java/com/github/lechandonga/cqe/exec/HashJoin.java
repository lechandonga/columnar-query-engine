package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.column.BooleanVector;
import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.column.Vectors;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 哈希连接。语义：
 * <ul>
 *   <li>ON 条件由 AND 连接的等值比较组成（可混合额外剩余谓词）；等值键驱动哈希探测，
 *       CROSS JOIN 走笛卡尔积；</li>
 *   <li>任一键列为空值时该行不产生内连接匹配（NULL = NULL 不为真）；</li>
 *   <li>INNER/LEFT/RIGHT/FULL 外连接按标准补空行规则输出；</li>
 *   <li>哈希表等工作内存计入 MemoryTracker。</li>
 * </ul>
 */
public final class HashJoin {

    private HashJoin() {
    }

    public static Frame apply(Frame left, Frame right, SelectStatement.JoinType type,
                              Expr on, MemoryTracker tracker) {
        if (type == SelectStatement.JoinType.CROSS) {
            return cartesian(left, right, null, tracker);
        }
        // 1. 从 ON 中提取等值键与剩余谓词
        List<Expr> conjuncts = new ArrayList<>();
        splitConjuncts(on, conjuncts);
        List<int[]> equalities = new ArrayList<>();
        List<Expr> residual = new ArrayList<>();
        for (Expr conjunct : conjuncts) {
            int[] eq = asEquality(conjunct, left, right);
            if (eq == null) {
                residual.add(conjunct);
            } else {
                equalities.add(eq);
            }
        }
        if (equalities.isEmpty()) {
            throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "JOIN 的 ON 条件必须至少包含一个等值比较（如 l.k = r.k）");
        }
        Expr residualExpr = residual.isEmpty() ? null
                : residual.size() == 1 ? residual.get(0)
                : combineAnd(residual);

        // 2. 在右表构建哈希表：键 -> 右表行下标
        Map<RowKey, int[]> table = new HashMap<>();
        int[] rightIndices = new int[equalities.size()];
        int[] leftIndices = new int[equalities.size()];
        for (int k = 0; k < equalities.size(); k++) {
            leftIndices[k] = equalities.get(k)[0];
            rightIndices[k] = equalities.get(k)[1];
        }
        // 数值跨类型（INT/DOUBLE）统一归一化为 double
        boolean[] numericKey = new boolean[equalities.size()];
        for (int k = 0; k < equalities.size(); k++) {
            DataType lt = left.schema().get(leftIndices[k]).type();
            DataType rt = right.schema().get(rightIndices[k]).type();
            numericKey[k] = lt.isNumeric() && rt.isNumeric();
        }
        // 哈希表开销估计
        long hashOverhead = 48L * right.rowCount();
        tracker.reserve(hashOverhead);

        for (int r = 0; r < right.rowCount(); r++) {
            RowKey key = makeJoinKey(right.columns(), rightIndices, numericKey, r);
            if (key.anyNull()) {
                continue; // 空键不放入可探测哈希表
            }
            int[] existing = table.get(key);
            if (existing == null) {
                table.put(key, new int[]{r});
            } else {
                int[] grown = new int[existing.length + 1];
                System.arraycopy(existing, 0, grown, 0, existing.length);
                grown[existing.length] = r;
                table.put(key, grown);
            }
        }

        boolean matchedLeft[] = new boolean[left.rowCount()];
        boolean matchedRight[] = new boolean[right.rowCount()];
        List<int[]> pairs = new ArrayList<>();

        for (int l = 0; l < left.rowCount(); l++) {
            RowKey key = makeJoinKey(left.columns(), leftIndices, numericKey, l);
            int[] candidates = key.anyNull() ? null : table.get(key);
            if (candidates != null) {
                for (int r : candidates) {
                    if (residualHolds(left, right, residualExpr, l, r)) {
                        pairs.add(new int[]{l, r});
                        matchedLeft[l] = true;
                        matchedRight[r] = true;
                    }
                }
            }
        }

        // 3. 按连接类型物化输出
        boolean keepLeft = type == SelectStatement.JoinType.LEFT
                || type == SelectStatement.JoinType.FULL;
        boolean keepRight = type == SelectStatement.JoinType.RIGHT
                || type == SelectStatement.JoinType.FULL;
        return materialize(left, right, pairs, matchedLeft, matchedRight,
                keepLeft, keepRight, tracker);
    }

    public static Frame cartesian(Frame left, Frame right, Expr condition,
                                  MemoryTracker tracker) {
        List<int[]> pairs = new ArrayList<>(left.rowCount() * right.rowCount());
        for (int l = 0; l < left.rowCount(); l++) {
            for (int r = 0; r < right.rowCount(); r++) {
                if (condition == null || residualHolds(left, right, condition, l, r)) {
                    pairs.add(new int[]{l, r});
                }
            }
        }
        return materialize(left, right, pairs,
                new boolean[left.rowCount()], new boolean[right.rowCount()],
                false, false, tracker);
    }

    private static Frame materialize(Frame left, Frame right, List<int[]> pairs,
                                     boolean[] matchedLeft, boolean[] matchedRight,
                                     boolean keepLeft, boolean keepRight,
                                     MemoryTracker tracker) {
        List<ColumnSchema> schemaCols = new ArrayList<>();
        schemaCols.addAll(left.schema().columns());
        schemaCols.addAll(right.schema().columns());
        Schema outSchema = new Schema(schemaCols);

        List<com.github.lechandonga.cqe.column.VectorBuilder> builders = new ArrayList<>();
        for (ColumnSchema cs : schemaCols) {
            builders.add(Vectors.newBuilder(cs.type(), Math.max(4, pairs.size())));
        }
        int lc = left.schema().size();

        for (int[] pair : pairs) {
            appendRow(builders, left, pair[0], 0);
            appendRow(builders, right, pair[1], lc);
        }
        if (keepLeft) {
            for (int l = 0; l < left.rowCount(); l++) {
                if (!matchedLeft[l]) {
                    appendRow(builders, left, l, 0);
                    appendNulls(builders, right, lc);
                }
            }
        }
        if (keepRight) {
            for (int r = 0; r < right.rowCount(); r++) {
                if (!matchedRight[r]) {
                    appendNulls(builders, left, 0);
                    appendRow(builders, right, r, lc);
                }
            }
        }
        long bytes = 0;
        List<ColumnVector> outColumns = new ArrayList<>(builders.size());
        for (var builder : builders) {
            ColumnVector v = builder.build();
            bytes += v.estimatedBytes();
            outColumns.add(v);
        }
        tracker.reserve(bytes);
        return new Frame(outSchema, outColumns);
    }

    private static void appendRow(List<com.github.lechandonga.cqe.column.VectorBuilder> builders,
                                  Frame frame, int row, int offset) {
        for (int c = 0; c < frame.schema().size(); c++) {
            builders.get(offset + c).append(frame.column(c).get(row));
        }
    }

    private static void appendNulls(List<com.github.lechandonga.cqe.column.VectorBuilder> builders,
                                    Frame frame, int offset) {
        for (int c = 0; c < frame.schema().size(); c++) {
            builders.get(offset + c).appendNull();
        }
    }

    private static boolean residualHolds(Frame left, Frame right, Expr expr, int l, int r) {
        if (expr == null) {
            return true;
        }
        Frame pairFrame = narrowPairFrame(left, right, l, r);
        ColumnVector result = new ExprEvaluator(MemoryTracker.NOOP).evaluate(expr, pairFrame);
        return !result.isNull(0) && ((BooleanVector) result).getBoolean(0);
    }

    // 为剩余谓词构造 1 行的拼接帧（小对象，固定开销可忽略）
    private static Frame narrowPairFrame(Frame left, Frame right, int l, int r) {
        List<ColumnSchema> schemaCols = new ArrayList<>();
        schemaCols.addAll(left.schema().columns());
        schemaCols.addAll(right.schema().columns());
        List<ColumnVector> columns = new ArrayList<>();
        for (ColumnVector v : left.columns()) {
            columns.add(sliceSingle(v, l));
        }
        for (ColumnVector v : right.columns()) {
            columns.add(sliceSingle(v, r));
        }
        return new Frame(new Schema(schemaCols, false), columns);
    }

    private static ColumnVector sliceSingle(ColumnVector source, int row) {
        var builder = Vectors.newBuilder(source.type(), 1);
        builder.append(source.get(row));
        return builder.build();
    }

    private static void splitConjuncts(Expr expr, List<Expr> out) {
        if (expr instanceof Expr.BinaryOp bin && bin.operator().equals("AND")) {
            splitConjuncts(bin.left(), out);
            splitConjuncts(bin.right(), out);
        } else {
            out.add(expr);
        }
    }

    private static Expr combineAnd(List<Expr> exprs) {
        Expr acc = exprs.get(0);
        for (int i = 1; i < exprs.size(); i++) {
            acc = new Expr.BinaryOp(acc, "AND", exprs.get(i));
        }
        return acc;
    }

    /** 若是 left列 = right列 的比较，返回 [左下标, 右下标]，否则返回 null。 */
    private static int[] asEquality(Expr expr, Frame left, Frame right) {
        if (!(expr instanceof Expr.BinaryOp bin) || !bin.operator().equals("=")) {
            return null;
        }
        Integer li = columnIndex(bin.left(), left);
        Integer ri = columnIndex(bin.right(), right);
        Integer li2 = columnIndex(bin.right(), left);
        Integer ri2 = columnIndex(bin.left(), right);
        if (li != null && ri != null) {
            return new int[]{li, ri};
        }
        if (li2 != null && ri2 != null) {
            return new int[]{li2, ri2};
        }
        return null;
    }

    private static Integer columnIndex(Expr expr, Frame frame) {
        if (expr instanceof Expr.ColumnRef col) {
            int idx = frame.indexOf(col.name());
            return idx < 0 ? null : idx;
        }
        return null;
    }

    private static RowKey makeJoinKey(List<ColumnVector> vectors, int[] indices,
                                      boolean[] numericKey, int row) {
        Object[] values = new Object[indices.length];
        boolean anyNull = false;
        for (int i = 0; i < indices.length; i++) {
            ColumnVector v = vectors.get(indices[i]);
            if (v.isNull(row)) {
                anyNull = true;
                values[i] = null;
            } else if (numericKey[i]) {
                values[i] = ((Number) v.get(row)).doubleValue();
            } else {
                values[i] = v.get(row);
            }
        }
        return new RowKey(values, anyNull, true);
    }
}
