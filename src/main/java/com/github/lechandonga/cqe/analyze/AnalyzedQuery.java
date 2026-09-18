package com.github.lechandonga.cqe.analyze;

import com.github.lechandonga.cqe.exec.HashAggregate;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;
import com.github.lechandonga.cqe.store.Table;
import com.github.lechandonga.cqe.type.Schema;

import java.util.List;

/**
 * 校验并绑定后的查询。所有数据来源均为目录在分析时刻抓取的不可变快照；
 * 列引用已规范化为执行帧中的规范列名（"别名.列名"、"g0"/"a0" 等）。
 */
public record AnalyzedQuery(
        // 来源表（左→右，含 FROM 主表）与连接方式/ON 条件
        List<SourceTable> sources,
        List<SelectStatement.JoinType> joinTypes,
        List<Expr> boundOn,
        // 过滤、分组（绑定在连接后的基础帧上）
        Expr boundWhere,
        List<Expr> boundGroupExprs,
        List<String> groupOutputNames,
        List<HashAggregate.AggSpec> aggregates,
        boolean aggregateQuery,
        // HAVING（绑定在分组输出帧 g0..,a0.. 上）
        Expr postAggregateHaving,
        // 投影：非聚合查询绑定在基础帧；聚合查询绑定在分组输出帧
        List<NamedExpr> projections,
        // 排序：非 DISTINCT 时表达式绑定在投影前帧；distinctOrderIndex>=0 表示按第 i 个输出列排序
        List<ResolvedOrder> order,
        boolean distinct,
        Long limit,
        long offset,
        Schema outputSchema
) {

    public record SourceTable(String alias, Table table) {
    }

    public record NamedExpr(Expr expr, String outputName) {
    }

    public record ResolvedOrder(Expr boundExpr, int distinctOrderIndex,
                                boolean ascending, boolean nullsFirst) {
    }
}
