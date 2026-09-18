package com.github.lechandonga.cqe.sql.ast;

import java.util.List;

/** SELECT 语句 AST。 */
public record SelectStatement(
        boolean distinct,
        List<Projection> projections,
        TableRef from,
        List<JoinClause> joins,
        Expr where,
        List<Expr> groupBy,
        Expr having,
        List<OrderItem> orderBy,
        Long limit,
        long offset
) {

    public record Projection(Expr expr, String alias) {
    }

    public record TableRef(String tableName, String alias) {
    }

    public enum JoinType { INNER, LEFT, RIGHT, FULL, CROSS }

    public record JoinClause(JoinType type, TableRef table, Expr on) {
    }

    public record OrderItem(Expr expr, boolean ascending, boolean nullsFirst) {
    }
}
