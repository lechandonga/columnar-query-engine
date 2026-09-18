package com.github.lechandonga.cqe.sql.ast;

import com.github.lechandonga.cqe.type.DataType;

/** 标量表达式 AST（record 自带结构化相等，便于分组表达式匹配）。 */
public sealed interface Expr
        permits Expr.Literal, Expr.ColumnRef, Expr.BinaryOp,
                Expr.UnaryOp, Expr.IsNull, Expr.AggregateCall {

    /** 字面量：value 为 Integer/Double/Boolean/String/null。 */
    record Literal(Object value, DataType type) implements Expr {
    }

    /** 列引用；qualifier 为表别名（可空）。 */
    record ColumnRef(String qualifier, String name) implements Expr {
    }

    /** 二元运算：算术 / 比较 / AND / OR。 */
    record BinaryOp(Expr left, String operator, Expr right) implements Expr {
    }

    record UnaryOp(String operator, Expr operand) implements Expr {
    }

    record IsNull(Expr operand, boolean negated) implements Expr {
    }

    /**
     * 聚合调用；arg 为 null 表示 COUNT(*)。
     */
    record AggregateCall(String function, boolean distinct, Expr arg) implements Expr {
    }
}
