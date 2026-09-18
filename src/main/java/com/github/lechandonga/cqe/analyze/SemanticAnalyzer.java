package com.github.lechandonga.cqe.analyze;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.exec.HashAggregate;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;
import com.github.lechandonga.cqe.store.Catalog;
import com.github.lechandonga.cqe.store.Table;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 名称解析与类型校验。分析时刻从目录抓取不可变快照；任何不合法查询都抛出
 * 可区分错误码的 QueryException，绝不静默产出空/残缺结果。
 */
public final class SemanticAnalyzer {

    private static final Set<String> AGG_FUNCS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    private final Catalog catalog;

    public SemanticAnalyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    public AnalyzedQuery analyze(SelectStatement st) {
        // 1. 收集来源表名并校验别名（重复别名在抓快照前拒绝）
        List<String> tableNames = new ArrayList<>();
        List<String> aliasNames = new ArrayList<>();
        tableNames.add(st.from().tableName());
        aliasNames.add(st.from().alias());
        for (SelectStatement.JoinClause join : st.joins()) {
            tableNames.add(join.table().tableName());
            aliasNames.add(join.table().alias());
        }
        Set<String> aliasSet = new HashSet<>();
        for (String alias : aliasNames) {
            if (!aliasSet.add(alias)) {
                throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                        "重复的表别名: " + alias);
            }
        }
        // 2. 在单个原子步骤内抓取全部来源表快照，保证一次查询看到同一已提交版本
        List<com.github.lechandonga.cqe.store.Table> snapshots =
                catalog.snapshotOf(tableNames);
        List<AnalyzedQuery.SourceTable> sources = new ArrayList<>();
        for (int i = 0; i < tableNames.size(); i++) {
            sources.add(new AnalyzedQuery.SourceTable(aliasNames.get(i), snapshots.get(i)));
        }
        List<SelectStatement.JoinType> joinTypes = new ArrayList<>();
        for (SelectStatement.JoinClause join : st.joins()) {
            joinTypes.add(join.type());
        }

        // 2. 构建连接后基础帧的列作用域
        Scope baseScope = buildBaseScope(sources);

        // 3. 绑定并校验 JOIN ON
        List<Expr> boundOn = new ArrayList<>();
        for (SelectStatement.JoinClause join : st.joins()) {
            if (join.type() == SelectStatement.JoinType.CROSS) {
                if (join.on() != null) {
                    throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                            "CROSS JOIN 不能带 ON 条件");
                }
                boundOn.add(null);
            } else {
                if (join.on() == null) {
                    throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                            join.type() + " JOIN 必须提供 ON 条件");
                }
                Expr on = bindScalar(join.on(), baseScope);
                if (inferType(on, baseScope) != DataType.BOOLEAN) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "JOIN ON 必须是布尔表达式");
                }
                boundOn.add(on);
            }
        }

        // 4. 绑定 WHERE
        Expr boundWhere = null;
        if (st.where() != null) {
            if (containsAggregate(st.where())) {
                throw new QueryException(QueryException.Code.AGGREGATE_MISUSE,
                        "WHERE 子句中不允许聚合函数");
            }
            boundWhere = bindScalar(st.where(), baseScope);
            if (inferType(boundWhere, baseScope) != DataType.BOOLEAN) {
                throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                        "WHERE 必须是布尔表达式");
            }
        }

        // 5. 绑定 GROUP BY
        List<Expr> boundGroupExprs = new ArrayList<>();
        List<String> groupOutputNames = new ArrayList<>();
        for (int i = 0; i < st.groupBy().size(); i++) {
            Expr g = st.groupBy().get(i);
            if (containsAggregate(g)) {
                throw new QueryException(QueryException.Code.GROUPING_ERROR,
                        "GROUP BY 表达式中不允许聚合函数");
            }
            Expr bound = bindScalar(g, baseScope);
            boundGroupExprs.add(bound);
            groupOutputNames.add("g" + i);
        }

        boolean hasGroupBy = !boundGroupExprs.isEmpty();
        boolean projectionHasAgg = st.projections().stream()
                .anyMatch(p -> p.expr() != null && containsAggregate(p.expr()));
        boolean orderHasAgg = st.orderBy().stream()
                .anyMatch(o -> containsAggregate(o.expr()));
        boolean havingHasAgg = st.having() != null && containsAggregate(st.having());
        if (st.having() != null && !havingHasAgg && !hasGroupBy) {
            // HAVING 不带聚合且没有 GROUP BY：本引擎要求显式分组，明确拒绝
            throw new QueryException(QueryException.Code.GROUPING_ERROR,
                    "HAVING 子句需要 GROUP BY 或聚合函数");
        }
        boolean aggregateQuery = hasGroupBy || projectionHasAgg || orderHasAgg
                || havingHasAgg;

        // 6. 聚合与投影
        List<HashAggregate.AggSpec> aggregates = new ArrayList<>();
        List<AnalyzedQuery.NamedExpr> projections = new ArrayList<>();
        List<ColumnSchema> outputColumns = new ArrayList<>();
        Expr postHaving = null;
        boolean star = st.projections().size() == 1 && st.projections().get(0).expr() == null;

        if (aggregateQuery) {
            // 先在基础帧上规范化全部聚合调用（参数绑定、类型校验），输出 a0,a1,...
            List<Expr.AggregateCall> aggOrigins = new ArrayList<>();
            for (SelectStatement.Projection p : st.projections()) {
                collectAggregateOrigins(p.expr(), aggOrigins);
            }
            if (st.having() != null) {
                collectAggregateOrigins(st.having(), aggOrigins);
            }
            for (SelectStatement.OrderItem o : st.orderBy()) {
                collectAggregateOrigins(o.expr(), aggOrigins);
            }
            for (Expr.AggregateCall call : aggOrigins) {
                validateAggregate(call, baseScope);
                Expr boundArg = call.arg() == null ? null : bindScalar(call.arg(), baseScope);
                Expr.AggregateCall boundCall =
                        new Expr.AggregateCall(call.function(), call.distinct(), boundArg);
                aggregates.add(new HashAggregate.AggSpec("a" + aggregates.size(), boundCall,
                        aggregateResultType(boundCall, baseScope)));
            }
            Scope postScope = buildPostScope(boundGroupExprs, aggregates, baseScope);

            // 绑定投影到分组输出帧
            for (int i = 0; i < st.projections().size(); i++) {
                SelectStatement.Projection p = st.projections().get(i);
                Expr bound = bindPostAggregate(p.expr(), baseScope, boundGroupExprs,
                        aggregates, postScope);
                String name = projectionName(p, bound, i);
                projections.add(new AnalyzedQuery.NamedExpr(bound, name));
                outputColumns.add(new ColumnSchema(name, inferType(bound, postScope), true));
            }
            if (st.having() != null) {
                postHaving = bindPostAggregate(st.having(), baseScope, boundGroupExprs,
                        aggregates, postScope);
                if (inferType(postHaving, postScope) != DataType.BOOLEAN) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "HAVING 必须是布尔表达式");
                }
            }
        } else {
            if (star) {
                for (Scope.Entry entry : baseScope.entries) {
                    String alias = entry.alias() + "." + entry.columnName();
                    projections.add(new AnalyzedQuery.NamedExpr(
                            new Expr.ColumnRef(null, alias), entry.columnName()));
                    outputColumns.add(new ColumnSchema(entry.columnName(), entry.type(), true));
                }
            } else {
                for (int i = 0; i < st.projections().size(); i++) {
                    SelectStatement.Projection p = st.projections().get(i);
                    Expr bound = bindScalar(p.expr(), baseScope);
                    String name = projectionName(p, bound, i);
                    projections.add(new AnalyzedQuery.NamedExpr(bound, name));
                    outputColumns.add(new ColumnSchema(name, inferType(bound, baseScope), true));
                }
            }
        }

        // 7. 排序
        List<AnalyzedQuery.ResolvedOrder> order = new ArrayList<>();
        for (SelectStatement.OrderItem item : st.orderBy()) {
            order.add(resolveOrder(item, aggregateQuery, baseScope, boundGroupExprs,
                    aggregates, projections, st.distinct()));
        }

        // 8. 截断参数校验
        if (st.limit() != null && st.limit() < 0) {
            throw new QueryException(QueryException.Code.INVALID_LIMIT, "LIMIT 不能为负");
        }
        if (st.offset() < 0) {
            throw new QueryException(QueryException.Code.INVALID_LIMIT, "OFFSET 不能为负");
        }

        return new AnalyzedQuery(
                List.copyOf(sources), List.copyOf(joinTypes),
                unmodifiableNullable(boundOn),
                boundWhere, List.copyOf(boundGroupExprs), List.copyOf(groupOutputNames),
                List.copyOf(aggregates), aggregateQuery, postHaving,
                List.copyOf(projections), List.copyOf(order),
                st.distinct(), st.limit(), st.offset(),
                new Schema(outputColumns, false));
    }

    private static <T> List<T> unmodifiableNullable(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    private Scope buildBaseScope(List<AnalyzedQuery.SourceTable> sources) {
        List<Scope.Entry> entries = new ArrayList<>();
        for (AnalyzedQuery.SourceTable source : sources) {
            for (ColumnSchema column : source.table().schema().columns()) {
                entries.add(new Scope.Entry(source.alias(), column.name(), column.type()));
            }
        }
        return new Scope(entries);
    }

    private Scope buildPostScope(List<Expr> groupExprs, List<HashAggregate.AggSpec> aggs,
                                 Scope baseScope) {
        List<Scope.Entry> entries = new ArrayList<>();
        for (int i = 0; i < groupExprs.size(); i++) {
            entries.add(new Scope.Entry(null, "g" + i,
                    inferType(groupExprs.get(i), baseScope)));
        }
        for (HashAggregate.AggSpec agg : aggs) {
            entries.add(new Scope.Entry(null, agg.outputName(),
                    aggregateResultType(agg.call(), baseScope)));
        }
        return new Scope(entries);
    }

    // ---------- 表达式绑定（基础帧，无聚合） ----------

    private Expr bindScalar(Expr expr, Scope scope) {
        return switch (expr) {
            case Expr.Literal lit -> lit;
            case Expr.ColumnRef ref -> new Expr.ColumnRef(null, resolveColumn(ref, scope));
            case Expr.UnaryOp un -> {
                Expr operand = bindScalar(un.operand(), scope);
                checkUnary(un.operator(), operand, scope);
                yield new Expr.UnaryOp(un.operator(), operand);
            }
            case Expr.BinaryOp bin -> {
                Expr left = bindScalar(bin.left(), scope);
                Expr right = bindScalar(bin.right(), scope);
                checkBinary(left, bin.operator(), right, scope);
                yield new Expr.BinaryOp(left, bin.operator(), right);
            }
            case Expr.IsNull isNull -> {
                Expr operand = bindScalar(isNull.operand(), scope);
                yield new Expr.IsNull(operand, isNull.negated());
            }
            case Expr.AggregateCall call -> throw new QueryException(
                    QueryException.Code.AGGREGATE_MISUSE,
                    "此处不允许聚合函数 " + call.function());
        };
    }

    private String resolveColumn(Expr.ColumnRef ref, Scope scope) {
        List<Scope.Entry> matches = new ArrayList<>();
        for (Scope.Entry entry : scope.entries) {
            if (ref.qualifier() == null) {
                if (entry.columnName().equals(ref.name())) {
                    matches.add(entry);
                }
            } else {
                if (entry.alias() != null && entry.alias().equals(ref.qualifier())
                        && entry.columnName().equals(ref.name())) {
                    matches.add(entry);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new QueryException(QueryException.Code.UNKNOWN_COLUMN,
                    "未知字段: " + (ref.qualifier() == null ? ref.name()
                            : ref.qualifier() + "." + ref.name()));
        }
        if (matches.size() > 1 && ref.qualifier() == null) {
            throw new QueryException(QueryException.Code.AMBIGUOUS_COLUMN,
                    "字段 '" + ref.name() + "' 存在歧义，请使用 别名.列名 限定");
        }
        return matches.get(0).alias() + "." + matches.get(0).columnName();
    }

    private void checkUnary(String op, Expr operand) {
        checkUnary(op, operand, null);
    }

    private void checkUnary(String op, Expr operand, Scope scope) {
        DataType t = inferType(operand, scope);
        if (t == null) {
            return; // NULL 字面量，传播即可
        }
        switch (op) {
            case "NOT" -> {
                if (t != DataType.BOOLEAN) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "NOT 要求布尔操作数，实际: " + t);
                }
            }
            case "-", "+" -> {
                if (!t.isNumeric()) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "一元 '" + op + "' 要求数值操作数，实际: " + t);
                }
            }
            default -> throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "不支持的一元运算符: " + op);
        }
    }

    private void checkBinary(Expr left, String op, Expr right) {
        checkBinary(left, op, right, null);
    }

    private void checkBinary(Expr left, String op, Expr right, Scope scope) {
        DataType lt = inferType(left, scope);
        DataType rt = inferType(right, scope);
        if (lt == null || rt == null) {
            return; // NULL 字面量参与时类型传播（NULL 与任何类型都兼容）
        }
        switch (op) {
            case "AND", "OR" -> {
                if (lt != DataType.BOOLEAN || rt != DataType.BOOLEAN) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            op + " 要求两个布尔操作数，实际: " + lt + ", " + rt);
                }
            }
            case "+", "-", "*", "/" -> {
                if (!lt.isNumeric() || !rt.isNumeric()) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "算术运算要求数值类型，实际: " + lt + " " + op + " " + rt);
                }
            }
            case "||" -> {
                if (lt != DataType.STRING || rt != DataType.STRING) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "|| 要求两个字符串操作数，实际: " + lt + ", " + rt);
                }
            }
            default -> {
                // 比较运算
                if (!lt.isComparableWith(rt)) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            "不可比较的类型: " + lt + " " + op + " " + rt);
                }
            }
        }
    }

    // ---------- 聚合后的绑定 ----------

    private Expr bindPostAggregate(Expr expr, Scope baseScope, List<Expr> groupExprs,
                                   List<HashAggregate.AggSpec> aggregates, Scope postScope) {
        return switch (expr) {
            case Expr.Literal lit -> lit;
            case Expr.ColumnRef col -> {
                // 已在分组输出帧中的规范名（g0/a0 或输出别名）直接保留
                if (col.qualifier() == null && postScopeHas(postScope, col.name())) {
                    yield col;
                }
                String canonical;
                try {
                    canonical = resolveColumn(col, baseScope);
                } catch (QueryException e) {
                    throw new QueryException(QueryException.Code.GROUPING_ERROR,
                            "聚合后表达式引用了既非分组键也非聚合的字段: "
                                    + (col.qualifier() == null ? col.name()
                                    : col.qualifier() + "." + col.name()));
                }
                int gi = indexOfStructural(groupExprs,
                        new Expr.ColumnRef(null, canonical));
                if (gi < 0) {
                    throw new QueryException(QueryException.Code.GROUPING_ERROR,
                            "列 '" + canonical + "' 不在 GROUP BY 中，也不在聚合函数内");
                }
                yield new Expr.ColumnRef(null, "g" + gi);
            }
            case Expr.UnaryOp un -> {
                Expr operand = bindPostAggregate(un.operand(), baseScope, groupExprs,
                        aggregates, postScope);
                checkUnary(un.operator(), operand, postScope);
                yield new Expr.UnaryOp(un.operator(), operand);
            }
            case Expr.BinaryOp bin -> {
                Expr left = bindPostAggregate(bin.left(), baseScope, groupExprs,
                        aggregates, postScope);
                Expr right = bindPostAggregate(bin.right(), baseScope, groupExprs,
                        aggregates, postScope);
                checkBinary(left, bin.operator(), right, postScope);
                yield new Expr.BinaryOp(left, bin.operator(), right);
            }
            case Expr.IsNull isNull -> new Expr.IsNull(
                    bindPostAggregate(isNull.operand(), baseScope, groupExprs,
                            aggregates, postScope), isNull.negated());
            case Expr.AggregateCall call -> {
                Expr.AggregateCall boundCall = new Expr.AggregateCall(call.function(),
                        call.distinct(),
                        call.arg() == null ? null : bindScalar(call.arg(), baseScope));
                int ai = findAggregateIndex(boundCall, aggregates);
                yield new Expr.ColumnRef(null, "a" + ai);
            }
        };
    }

    private void collectAggregateOrigins(Expr expr, List<Expr.AggregateCall> out) {
        switch (expr) {
            case Expr.Literal ignored -> { }
            case Expr.ColumnRef ignored -> { }
            case Expr.UnaryOp un -> collectAggregateOrigins(un.operand(), out);
            case Expr.BinaryOp bin -> {
                collectAggregateOrigins(bin.left(), out);
                collectAggregateOrigins(bin.right(), out);
            }
            case Expr.IsNull isNull -> collectAggregateOrigins(isNull.operand(), out);
            case Expr.AggregateCall call -> {
                validateAggregateNesting(call);
                if (call.arg() != null) {
                    collectAggregateOrigins(call.arg(), out);
                }
                out.add(call);
            }
        }
    }

    private void validateAggregateNesting(Expr.AggregateCall call) {
        if (call.arg() != null && containsAggregate(call.arg())) {
            throw new QueryException(QueryException.Code.AGGREGATE_MISUSE,
                    "聚合函数参数中不允许再嵌套聚合: " + call.function());
        }
    }

    private void validateAggregate(Expr.AggregateCall call, Scope baseScope) {
        String fn = call.function();
        if (!AGG_FUNCS.contains(fn)) {
            throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "未知聚合函数: " + fn);
        }
        if (call.arg() == null && !fn.equals("COUNT")) {
            throw new QueryException(QueryException.Code.AGGREGATE_MISUSE,
                    fn + " 必须有一个参数（COUNT(*) 除外）");
        }
        if (call.arg() != null) {
            DataType t = inferType(bindScalar(call.arg(), baseScope), baseScope);
            if (t != null) {
                if ((fn.equals("SUM") || fn.equals("AVG")) && !t.isNumeric()) {
                    throw new QueryException(QueryException.Code.TYPE_MISMATCH,
                            fn + " 要求数值参数，实际: " + t);
                }
            }
        }
        if (call.distinct() && call.arg() == null) {
            throw new QueryException(QueryException.Code.AGGREGATE_MISUSE,
                    "COUNT(*) 不能带 DISTINCT");
        }
    }

    private boolean postScopeHas(Scope scope, String name) {
        for (Scope.Entry entry : scope.entries) {
            if (entry.columnName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private int findAggregateIndex(Expr.AggregateCall call, List<HashAggregate.AggSpec> aggs) {
        for (int i = 0; i < aggs.size(); i++) {
            if (aggs.get(i).call().equals(call)) {
                return i;
            }
        }
        throw new IllegalStateException("聚合调用未被收集: " + call);
    }

    private int indexOfStructural(List<Expr> exprs, Expr target) {
        for (int i = 0; i < exprs.size(); i++) {
            if (exprs.get(i).equals(target)) {
                return i;
            }
        }
        return -1;
    }

    // ---------- 排序解析 ----------

    private AnalyzedQuery.ResolvedOrder resolveOrder(SelectStatement.OrderItem item,
                                                     boolean aggregateQuery, Scope baseScope,
                                                     List<Expr> groupExprs,
                                                     List<HashAggregate.AggSpec> aggregates,
                                                     List<AnalyzedQuery.NamedExpr> projections,
                                                     boolean distinct) {
        Scope postScope = aggregateQuery
                ? buildPostScope(groupExprs, aggregates, baseScope)
                : baseScope;
        // 先看是否按输出别名排序
        if (item.expr() instanceof Expr.ColumnRef ref && ref.qualifier() == null) {
            for (int i = 0; i < projections.size(); i++) {
                if (projections.get(i).outputName().equals(ref.name())) {
                    return new AnalyzedQuery.ResolvedOrder(
                            new Expr.ColumnRef(null, ref.name()), i,
                            item.ascending(), item.nullsFirst());
                }
            }
        }
        Expr bound;
        if (aggregateQuery) {
            bound = bindPostAggregate(item.expr(), baseScope, groupExprs, aggregates, postScope);
        } else {
            bound = bindScalar(item.expr(), baseScope);
        }
        if (distinct) {
            // SQL 标准：DISTINCT 查询的排序键必须出现在投影中
            int idx = -1;
            for (int i = 0; i < projections.size(); i++) {
                if (projections.get(i).expr().equals(bound)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new QueryException(QueryException.Code.GROUPING_ERROR,
                        "SELECT DISTINCT 的 ORDER BY 表达式必须出现在投影列表中");
            }
            return new AnalyzedQuery.ResolvedOrder(bound, idx, item.ascending(), item.nullsFirst());
        }
        return new AnalyzedQuery.ResolvedOrder(bound, -1, item.ascending(), item.nullsFirst());
    }

    // ---------- 类型推导 ----------

    private DataType inferType(Expr expr, Scope scope) {
        if (expr == null) {
            return null;
        }
        return switch (expr) {
            case Expr.Literal lit -> lit.type();
            case Expr.ColumnRef col -> {
                if (scope == null) {
                    yield null; // 未提供作用域的校验路径：列类型由其它机制保证
                }
                DataType found = null;
                for (Scope.Entry entry : scope.entries) {
                    boolean match = entry.columnName().equals(col.name())
                            || (entry.alias() != null
                                && (entry.alias() + "." + entry.columnName())
                                        .equals(col.name()));
                    if (match) {
                        found = entry.type();
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalStateException("类型推导找不到列: " + col.name());
                }
                yield found;
            }
            case Expr.UnaryOp un -> {
                DataType t = inferType(un.operand(), scope);
                if (un.operator().equals("NOT")) {
                    yield t == null ? null : DataType.BOOLEAN;
                }
                yield t;
            }
            case Expr.BinaryOp bin -> {
                DataType lt = inferType(bin.left(), scope);
                DataType rt = inferType(bin.right(), scope);
                if (lt == null || rt == null) {
                    // 混合 NULL 字面量时按另一侧推导
                    yield bin.operator().equals("AND") || bin.operator().equals("OR")
                            ? DataType.BOOLEAN
                            : isCompare(bin.operator()) ? DataType.BOOLEAN
                            : (lt == DataType.DOUBLE || rt == DataType.DOUBLE
                                    ? DataType.DOUBLE : (lt == null ? rt : lt));
                }
                yield switch (bin.operator()) {
                    case "AND", "OR" -> DataType.BOOLEAN;
                    case "+", "-", "*", "/" ->
                            (lt == DataType.DOUBLE || rt == DataType.DOUBLE)
                                    ? DataType.DOUBLE : DataType.INT;
                    case "||" -> DataType.STRING;
                    default -> DataType.BOOLEAN;
                };
            }
            case Expr.IsNull ignored -> DataType.BOOLEAN;
            case Expr.AggregateCall call -> aggregateResultType(call, scope);
        };
    }

    private boolean isCompare(String op) {
        return op.equals("=") || op.equals("<>") || op.equals("!=")
                || op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=");
    }

    private DataType aggregateResultType(Expr.AggregateCall call, Scope baseScope) {
        return switch (call.function()) {
            case "COUNT" -> DataType.INT;
            case "AVG" -> DataType.DOUBLE;
            case "SUM" -> {
                DataType t = call.arg() == null ? null : inferType(call.arg(), baseScope);
                yield t == DataType.DOUBLE ? DataType.DOUBLE : DataType.INT;
            }
            case "MIN", "MAX" -> call.arg() == null
                    ? null : inferType(call.arg(), baseScope);
            default -> throw new QueryException(QueryException.Code.UNSUPPORTED_FEATURE,
                    "未知聚合函数: " + call.function());
        };
    }

    private boolean containsAggregate(Expr expr) {
        return switch (expr) {
            case Expr.Literal ignored -> false;
            case Expr.ColumnRef ignored -> false;
            case Expr.UnaryOp un -> containsAggregate(un.operand());
            case Expr.BinaryOp bin -> containsAggregate(bin.left()) || containsAggregate(bin.right());
            case Expr.IsNull isNull -> containsAggregate(isNull.operand());
            case Expr.AggregateCall ignored -> true;
        };
    }

    private String projectionName(SelectStatement.Projection p, Expr bound, int index) {
        if (p.alias() != null) {
            return p.alias();
        }
        if (bound instanceof Expr.ColumnRef col) {
            String n = col.name();
            int dot = n.indexOf('.');
            return dot >= 0 ? n.substring(dot + 1) : n;
        }
        if (bound instanceof Expr.AggregateCall call) {
            return call.function().toLowerCase(Locale.ROOT)
                    + (call.arg() == null ? "(*)" : "");
        }
        return "col" + (index + 1);
    }

    private record Scope(List<Entry> entries) {
        record Entry(String alias, String columnName, DataType type) {
        }
    }
}
