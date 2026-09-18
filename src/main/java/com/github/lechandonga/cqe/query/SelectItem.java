package com.github.lechandonga.cqe.query;

/** SELECT 列表项。 */
public sealed interface SelectItem {

    record Star() implements SelectItem {}

    /** 普通列表达式，alias 可为 null。 */
    record ExprItem(Expr expr, String alias) implements SelectItem {}

    /** 聚合项；arg 为 null 表示 COUNT(*)。 */
    record AggItem(AggFunc func, Expr.ColumnRef arg, String alias) implements SelectItem {}
}
