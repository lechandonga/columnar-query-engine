package com.github.lechandonga.cqe.query;

import java.util.List;

/** 解析后的查询 AST。 */
public record Query(
        List<SelectItem> select,
        String fromTable,
        String fromAlias,
        JoinClause join,
        Expr where,
        List<Expr.ColumnRef> groupBy,
        List<OrderItem> orderBy,
        Integer limit) {

    public enum JoinType { INNER, LEFT }

    public record JoinClause(JoinType type, String table, String alias, Expr on) {}

    public record OrderItem(Expr.ColumnRef column, boolean ascending) {}
}
