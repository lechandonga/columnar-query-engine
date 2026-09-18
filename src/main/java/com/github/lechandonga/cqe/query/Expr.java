package com.github.lechandonga.cqe.query;

/** 过滤 / 连接条件表达式 AST。 */
public sealed interface Expr {

    record Literal(Object value) implements Expr {}

    /** 列引用；table 可为 null（未限定），由校验阶段解析。 */
    record ColumnRef(String table, String name) implements Expr {}

    enum CompareOp { EQ, NE, LT, LE, GT, GE }

    record Compare(CompareOp op, Expr left, Expr right) implements Expr {}

    enum LogicOp { AND, OR }

    record Logic(LogicOp op, Expr left, Expr right) implements Expr {}

    record Not(Expr inner) implements Expr {}

    record IsNull(Expr inner, boolean negated) implements Expr {}
}
