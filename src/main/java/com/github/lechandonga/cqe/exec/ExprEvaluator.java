package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.column.BooleanVector;
import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.column.DoubleVector;
import com.github.lechandonga.cqe.column.IntVector;
import com.github.lechandonga.cqe.column.Vectors;
import com.github.lechandonga.cqe.column.StringVector;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.type.DataType;

/**
 * 在帧上对表达式做向量化求值。列引用按规范列名（"别名.列名"/"g0"/"a0"）绑定。
 * 比较与算术遵循 SQL NULL 传播；AND/OR 采用三值逻辑。
 * 输出向量的工作内存计入 MemoryTracker。
 */
public final class ExprEvaluator {

    private final MemoryTracker tracker;

    public ExprEvaluator(MemoryTracker tracker) {
        this.tracker = tracker;
    }

    public ColumnVector evaluate(Expr expr, Frame input) {
        return switch (expr) {
            case Expr.Literal lit -> literalVector(lit, input.rowCount());
            case Expr.ColumnRef col -> {
                int idx = input.indexOf(col.name());
                if (idx < 0) {
                    throw new QueryException(QueryException.Code.UNKNOWN_COLUMN,
                            "执行期找不到列: " + col.name());
                }
                yield input.column(idx);
            }
            case Expr.UnaryOp un -> evalUnary(un, input);
            case Expr.BinaryOp bin -> evalBinary(bin, input);
            case Expr.IsNull isNull -> evalIsNull(isNull, input);
            case Expr.AggregateCall call -> throw new QueryException(
                    QueryException.Code.AGGREGATE_MISUSE,
                    "执行期不应直接求值聚合调用: " + call.function());
        };
    }

    private ColumnVector literalVector(Expr.Literal lit, int rows) {
        DataType type = lit.type();
        Object value = lit.value();
        // NULL 字面量在二元运算中会被对齐成对方类型；独立出现时按 STRING 空向量承载
        if (type == null) {
            type = DataType.STRING;
        }
        switch (type) {
            case INT -> {
                var b = IntVector.builder(rows);
                for (int i = 0; i < rows; i++) {
                    if (value == null) {
                        b.appendNull();
                    } else {
                        b.appendInt((Integer) value);
                    }
                }
                return b.build();
            }
            case DOUBLE -> {
                var b = DoubleVector.builder(rows);
                for (int i = 0; i < rows; i++) {
                    if (value == null) {
                        b.appendNull();
                    } else {
                        b.appendDouble(((Number) value).doubleValue());
                    }
                }
                return b.build();
            }
            case BOOLEAN -> {
                var b = BooleanVector.builder(rows);
                for (int i = 0; i < rows; i++) {
                    if (value == null) {
                        b.appendNull();
                    } else {
                        b.appendBoolean((Boolean) value);
                    }
                }
                return b.build();
            }
            case STRING -> {
                var b = StringVector.builder(rows);
                for (int i = 0; i < rows; i++) {
                    b.append(value == null ? null : value.toString());
                }
                return b.build();
            }
        }
        throw new IllegalStateException("未知字面量类型: " + type);
    }

    private ColumnVector evalUnary(Expr.UnaryOp un, Frame input) {
        ColumnVector operand = evaluate(un.operand(), input);
        int n = operand.size();
        switch (un.operator()) {
            case "NOT" -> {
                var b = BooleanVector.builder(n);
                for (int i = 0; i < n; i++) {
                    if (operand.isNull(i)) {
                        b.appendNull();
                    } else {
                        b.appendBoolean(!asBoolean(operand, i));
                    }
                }
                return b.build();
            }
            case "-" -> {
                return negate(operand);
            }
            case "+" -> { return operand; }
            default -> throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "不支持的一元运算: " + un.operator());
        }
    }

    private ColumnVector negate(ColumnVector operand) {
        int n = operand.size();
        if (operand instanceof StringVector && allNull(operand)) {
            // 一元负号作用于 NULL 字面量：输出类型不可知，用全空 INT 承载
            return allNullOfType(DataType.INT, n);
        }
        if (operand instanceof IntVector iv) {
            var b = IntVector.builder(n);
            for (int i = 0; i < n; i++) {
                if (iv.isNull(i)) {
                    b.appendNull();
                } else {
                    b.appendInt(-iv.getInt(i));
                }
            }
            return b.build();
        }
        var b = DoubleVector.builder(n);
        DoubleVector dv = (DoubleVector) operand;
        for (int i = 0; i < n; i++) {
            if (dv.isNull(i)) {
                b.appendNull();
            } else {
                b.appendDouble(-dv.getDouble(i));
            }
        }
        return b.build();
    }

    private ColumnVector evalIsNull(Expr.IsNull isNull, Frame input) {
        ColumnVector operand = evaluate(isNull.operand(), input);
        var b = BooleanVector.builder(operand.size());
        for (int i = 0; i < operand.size(); i++) {
            boolean result = operand.isNull(i);
            b.appendBoolean(isNull.negated() != result);
        }
        return b.build();
    }

    private ColumnVector evalBinary(Expr.BinaryOp bin, Frame input) {
        ColumnVector left = evaluate(bin.left(), input);
        ColumnVector right = evaluate(bin.right(), input);
        // 类型对齐：INT/DOUBLE 数值运算提升为 DOUBLE；NULL 字面量（承载为全空 STRING）
        // 对齐为另一侧类型，保证 NULL 传播语义正确
        if (left.type() != right.type()) {
            if (isNullLiteral(bin.left()) && allNull(left)) {
                left = allNullOfType(right.type(), left.size());
            } else if (isNullLiteral(bin.right()) && allNull(right)) {
                right = allNullOfType(left.type(), right.size());
            } else if (isNumericTypes(left.type(), right.type())) {
                if (left.type() == DataType.INT) {
                    left = toDouble(left);
                }
                if (right.type() == DataType.INT) {
                    right = toDouble(right);
                }
            }
        }
        String op = bin.operator();
        int n = left.size();
        return switch (op) {
            case "AND", "OR" -> evalLogical(op, left, right, n);
            case "=", "<>", "!=", "<", ">", "<=", ">=" -> evalCompare(op, left, right, n);
            case "+", "-", "*", "/" -> evalArithmetic(op, left, right, n);
            case "||" -> evalConcat(left, right, n);
            default -> throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "不支持的运算符: " + op);
        };
    }

    private boolean isNumericTypes(DataType a, DataType b) {
        return a.isNumeric() && b.isNumeric();
    }

    private boolean isNullLiteral(Expr expr) {
        return expr instanceof Expr.Literal lit && lit.type() == null;
    }

    private boolean allNull(ColumnVector vector) {
        for (int i = 0; i < vector.size(); i++) {
            if (!vector.isNull(i)) {
                return false;
            }
        }
        return true;
    }

    private ColumnVector allNullOfType(DataType type, int rows) {
        var b = Vectors.newBuilder(type, rows);
        for (int i = 0; i < rows; i++) {
            b.appendNull();
        }
        return b.build();
    }

    private ColumnVector toDouble(ColumnVector vector) {
        var b = DoubleVector.builder(vector.size());
        if (vector instanceof IntVector iv) {
            for (int i = 0; i < vector.size(); i++) {
                if (iv.isNull(i)) {
                    b.appendNull();
                } else {
                    b.appendDouble(iv.getInt(i));
                }
            }
        } else {
            throw new IllegalStateException("无法转换为 DOUBLE: " + vector.type());
        }
        return b.build();
    }

    private ColumnVector evalConcat(ColumnVector left, ColumnVector right, int n) {
        var b = StringVector.builder(n);
        for (int i = 0; i < n; i++) {
            if (left.isNull(i) || right.isNull(i)) {
                b.appendNull();
            } else {
                b.append(left.get(i).toString() + right.get(i).toString());
            }
        }
        var v = b.build();
        tracker.reserve(v.estimatedBytes());
        return v;
    }

    private ColumnVector evalLogical(String op, ColumnVector left, ColumnVector right, int n) {
        var b = BooleanVector.builder(n);
        for (int i = 0; i < n; i++) {
            Boolean lv = left.isNull(i) ? null : asBoolean(left, i);
            Boolean rv = right.isNull(i) ? null : asBoolean(right, i);
            Boolean result;
            if (op.equals("AND")) {
                if (Boolean.FALSE.equals(lv) || Boolean.FALSE.equals(rv)) {
                    result = false;
                } else if (lv == null || rv == null) {
                    result = null;
                } else {
                    result = true;
                }
            } else { // OR
                if (Boolean.TRUE.equals(lv) || Boolean.TRUE.equals(rv)) {
                    result = true;
                } else if (lv == null || rv == null) {
                    result = null;
                } else {
                    result = false;
                }
            }
            if (result == null) {
                b.appendNull();
            } else {
                b.appendBoolean(result);
            }
        }
        return b.build();
    }

    private ColumnVector evalCompare(String op, ColumnVector left, ColumnVector right, int n) {
        var b = BooleanVector.builder(n);
        for (int i = 0; i < n; i++) {
            if (left.isNull(i) || right.isNull(i)) {
                b.appendNull();
                continue;
            }
            int cmp = compareValues(left, right, i);
            boolean result = switch (op) {
                case "=" -> cmp == 0;
                case "<>", "!=" -> cmp != 0;
                case "<" -> cmp < 0;
                case ">" -> cmp > 0;
                case "<=" -> cmp <= 0;
                case ">=" -> cmp >= 0;
                default -> throw new IllegalStateException();
            };
            b.appendBoolean(result);
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private int compareValues(ColumnVector left, ColumnVector right, int row) {
        if (left.type() == DataType.INT && right.type() == DataType.INT) {
            return Integer.compare(((IntVector) left).getInt(row),
                    ((IntVector) right).getInt(row));
        }
        if (left.type().isNumeric() && right.type().isNumeric()) {
            double l = ((Number) left.get(row)).doubleValue();
            double r = ((Number) right.get(row)).doubleValue();
            return Double.compare(l, r);
        }
        if (left.type() == DataType.BOOLEAN && right.type() == DataType.BOOLEAN) {
            return Boolean.compare(((BooleanVector) left).getBoolean(row),
                    ((BooleanVector) right).getBoolean(row));
        }
        // 字符串按 Unicode 码点字典序，保证跨数据规模/执行顺序结果确定
        return ((String) left.get(row)).compareTo((String) right.get(row));
    }

    private ColumnVector evalArithmetic(String op, ColumnVector left, ColumnVector right, int n) {
        boolean asDouble = left.type() == DataType.DOUBLE || right.type() == DataType.DOUBLE;
        if (asDouble) {
            DoubleVector l = (DoubleVector) (left.type() == DataType.DOUBLE
                    ? left : toDouble(left));
            DoubleVector r = (DoubleVector) (right.type() == DataType.DOUBLE
                    ? right : toDouble(right));
            var b = DoubleVector.builder(n);
            for (int i = 0; i < n; i++) {
                if (l.isNull(i) || r.isNull(i)) {
                    b.appendNull();
                } else {
                    b.appendDouble(applyDouble(op, l.getDouble(i), r.getDouble(i)));
                }
            }
            return b.build();
        }
        IntVector l = (IntVector) left;
        IntVector r = (IntVector) right;
        var b = IntVector.builder(n);
        for (int i = 0; i < n; i++) {
            if (l.isNull(i) || r.isNull(i)) {
                b.appendNull();
            } else {
                int rv = r.getInt(i);
                if (op.equals("/") && rv == 0) {
                    throw new QueryException(QueryException.Code.DATA_VIOLATION,
                            "整数除零错误（第 " + i + " 行）");
                }
                b.appendInt(applyInt(op, l.getInt(i), rv));
            }
        }
        return b.build();
    }

    private double applyDouble(String op, double a, double b) {
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            // 浮点除零产生 Infinity/NaN，遵循 IEEE 754，不抛异常
            case "/" -> a / b;
            default -> throw new IllegalStateException();
        };
    }

    private int applyInt(String op, int a, int b) {
        return switch (op) {
            case "+" -> {
                int r = a + b;
                if (((a ^ r) & (b ^ r)) < 0) {
                    throw overflow(op, a, b);
                }
                yield r;
            }
            case "-" -> {
                int r = a - b;
                if (((a ^ b) & (a ^ r)) < 0) {
                    throw overflow(op, a, b);
                }
                yield r;
            }
            case "*" -> {
                long r = (long) a * (long) b;
                if (r < Integer.MIN_VALUE || r > Integer.MAX_VALUE) {
                    throw overflow(op, a, b);
                }
                yield (int) r;
            }
            case "/" -> a / b;
            default -> throw new IllegalStateException();
        };
    }

    private QueryException overflow(String op, int a, int b) {
        return new QueryException(QueryException.Code.DATA_VIOLATION,
                "整数运算溢出: " + a + " " + op + " " + b);
    }

    private boolean asBoolean(ColumnVector vector, int row) {
        return ((BooleanVector) vector).getBoolean(row);
    }
}
