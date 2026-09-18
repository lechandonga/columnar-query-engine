package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.query.AggFunc;
import com.github.lechandonga.cqe.query.Expr;
import com.github.lechandonga.cqe.query.Query;
import com.github.lechandonga.cqe.query.SelectItem;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.storage.Snapshot;
import com.github.lechandonga.cqe.storage.Table;
import com.github.lechandonga.cqe.type.DataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义校验：未知表 / 未知字段 / 类型不匹配在此被拒绝并给出可定位原因。
 * 校验同时把列引用解析为"表名.列名"的完全限定形式，并推断输出 schema，
 * 使执行阶段无需再做名称解析。
 */
public class QueryValidator {

    /** 作用域中的一张表：可匹配别名或表名。 */
    private record ScopeEntry(String tableName, String alias, Table table) {
        boolean matches(String qualifier) {
            return tableName.equalsIgnoreCase(qualifier)
                    || (alias != null && alias.equalsIgnoreCase(qualifier));
        }
    }

    public ValidatedQuery validate(Query query, Snapshot snapshot) {
        List<ScopeEntry> scope = new ArrayList<>();
        Table from = snapshot.table(query.fromTable()); // 不存在时抛 UNKNOWN_TABLE
        scope.add(new ScopeEntry(from.name(), query.fromAlias(), from));
        if (query.join() != null) {
            Table jt = snapshot.table(query.join().table());
            scope.add(new ScopeEntry(jt.name(), query.join().alias(), jt));
        }

        // 1. JOIN ON 条件：解析并校验为布尔表达式
        Expr joinOn = null;
        if (query.join() != null) {
            joinOn = resolveExpr(query.join().on(), scope);
            requireBoolean(joinOn, scope, "JOIN ON condition");
        }

        // 2. WHERE：解析并校验为布尔表达式
        Expr where = null;
        if (query.where() != null) {
            where = resolveExpr(query.where(), scope);
            requireBoolean(where, scope, "WHERE clause");
        }

        // 3. GROUP BY 键解析
        List<Expr.ColumnRef> groupBy = new ArrayList<>();
        for (Expr.ColumnRef ref : query.groupBy()) {
            groupBy.add(resolveColumnRef(ref, scope));
        }

        // 4. SELECT 列表解析与输出 schema 推断
        boolean hasAgg = query.select().stream().anyMatch(i -> i instanceof SelectItem.AggItem);
        if (hasAgg && query.select().stream().anyMatch(i -> i instanceof SelectItem.Star)) {
            throw new QueryException(QueryException.Category.SYNTAX_ERROR,
                    "SELECT * cannot be combined with aggregate functions");
        }
        if (!groupBy.isEmpty() && query.select().stream().anyMatch(i -> i instanceof SelectItem.Star)) {
            throw new QueryException(QueryException.Category.SYNTAX_ERROR,
                    "SELECT * cannot be combined with GROUP BY");
        }

        List<SelectItem> resolvedItems = new ArrayList<>();
        List<ColumnSchema> output = new ArrayList<>();
        Set<String> groupKeys = new LinkedHashSet<>();
        for (Expr.ColumnRef g : groupBy) {
            groupKeys.add(qualified(g));
        }

        for (SelectItem item : query.select()) {
            switch (item) {
                case SelectItem.Star s -> {
                    // 展开 *：按 from 表、join 表顺序输出全部列；
                    // 重名列以 table.column 命名，保证可区分、可复现。
                    for (ScopeEntry e : scope) {
                        for (int i = 0; i < e.table().schema().size(); i++) {
                            String colName = e.table().schema().column(i).name();
                            boolean duplicated = scope.stream()
                                    .filter(x -> x.table().schema().indexOf(colName) >= 0)
                                    .count() > 1;
                            String outName = duplicated
                                    ? e.tableName() + "." + colName : colName;

                            resolvedItems.add(new SelectItem.ExprItem(
                                    new Expr.ColumnRef(e.tableName(), colName), outName));
                            output.add(new ColumnSchema(outName,
                                    e.table().schema().column(i).type()));
                        }
                    }
                }
                case SelectItem.ExprItem ei -> {
                    Expr resolved = resolveExpr(ei.expr(), scope);
                    if (!groupKeys.isEmpty() || hasAgg) {
                        // 有聚合/GROUP BY 时，普通列必须是分组键
                        if (!(resolved instanceof Expr.ColumnRef cr)
                                || !groupKeys.contains(qualified(cr))) {
                            throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                                    "selected column must appear in GROUP BY or be aggregated: "
                                            + describe(resolved));
                        }
                    }
                    String name = ei.alias() != null ? ei.alias() : defaultName(resolved);
                    resolvedItems.add(new SelectItem.ExprItem(resolved, name));
                    output.add(new ColumnSchema(name, typeOf(resolved, scope)));
                }
                case SelectItem.AggItem ai -> {
                    Expr.ColumnRef arg = null;
                    DataType argType = null;
                    if (ai.arg() != null) {
                        arg = resolveColumnRef(ai.arg(), scope);
                        argType = columnType(arg, scope);
                    }
                    DataType outType = switch (ai.func()) {
                        case COUNT -> DataType.LONG;
                        case SUM -> {
                            requireNumeric(ai.func(), arg, argType);
                            yield argType == DataType.DOUBLE ? DataType.DOUBLE : DataType.LONG;
                        }
                        case AVG -> {
                            requireNumeric(ai.func(), arg, argType);
                            yield DataType.DOUBLE;
                        }
                        case MIN, MAX -> argType;
                    };
                    String name = ai.alias() != null ? ai.alias()
                            : ai.func() + "(" + (arg == null ? "*" : qualified(arg)) + ")";
                    resolvedItems.add(new SelectItem.AggItem(ai.func(), arg, name));
                    output.add(new ColumnSchema(name, outType));
                }
            }
        }

        // 5. ORDER BY：必须引用输出列（名称或别名），保证排序键可解释
        List<Query.OrderItem> orderBy = new ArrayList<>();
        List<String> outNames = output.stream().map(ColumnSchema::name).toList();
        for (Query.OrderItem oi : query.orderBy()) {
            String wanted = oi.column().name();
            boolean found = outNames.stream().anyMatch(n -> n.equalsIgnoreCase(wanted));
            if (!found) {
                throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                        "ORDER BY references unknown output column: " + wanted);
            }
            orderBy.add(oi);
        }

        Query resolved = new Query(resolvedItems, query.fromTable(), query.fromAlias(),
                query.join() == null ? null
                        : new Query.JoinClause(query.join().type(), query.join().table(),
                                query.join().alias(), joinOn),
                where, groupBy, orderBy, query.limit());
        return new ValidatedQuery(resolved, output);
    }

    // ---- 表达式解析与类型检查 ----

    private Expr resolveExpr(Expr expr, List<ScopeEntry> scope) {
        return switch (expr) {
            case Expr.Literal l -> l;
            case Expr.ColumnRef cr -> resolveColumnRef(cr, scope);
            case Expr.Compare c -> {
                Expr l = resolveExpr(c.left(), scope);
                Expr r = resolveExpr(c.right(), scope);
                checkComparable(c.op(), typeOf(l, scope), typeOf(r, scope));
                yield new Expr.Compare(c.op(), l, r);
            }
            case Expr.Logic lg -> {
                Expr l = resolveExpr(lg.left(), scope);
                Expr r = resolveExpr(lg.right(), scope);
                requireBoolean(l, scope, lg.op() + " operand");
                requireBoolean(r, scope, lg.op() + " operand");
                yield new Expr.Logic(lg.op(), l, r);
            }
            case Expr.Not n -> {
                Expr inner = resolveExpr(n.inner(), scope);
                requireBoolean(inner, scope, "NOT operand");
                yield new Expr.Not(inner);
            }
            case Expr.IsNull is -> new Expr.IsNull(resolveExpr(is.inner(), scope), is.negated());
        };
    }

    private Expr.ColumnRef resolveColumnRef(Expr.ColumnRef ref, List<ScopeEntry> scope) {
        if (ref.table() != null) {
            for (ScopeEntry e : scope) {
                if (e.matches(ref.table())) {
                    int idx = e.table().schema().indexOf(ref.name());
                    if (idx < 0) {
                        throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                                "unknown column: " + ref.table() + "." + ref.name());
                    }
                    return new Expr.ColumnRef(e.tableName(), ref.name());
                }
            }
            throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                    "unknown table qualifier in column reference: " + ref.table());
        }
        Expr.ColumnRef found = null;
        for (ScopeEntry e : scope) {
            if (e.table().schema().indexOf(ref.name()) >= 0) {
                if (found != null) {
                    throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                            "ambiguous column reference (exists in multiple tables): " + ref.name());
                }
                found = new Expr.ColumnRef(e.tableName(), ref.name());
            }
        }
        if (found == null) {
            throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                    "unknown column: " + ref.name());
        }
        return found;
    }

    private DataType columnType(Expr.ColumnRef ref, List<ScopeEntry> scope) {
        for (ScopeEntry e : scope) {
            if (e.tableName().equalsIgnoreCase(ref.table())) {
                return e.table().schema().column(e.table().schema().requireIndex(ref.name())).type();
            }
        }
        throw new QueryException(QueryException.Category.UNKNOWN_FIELD,
                "unknown column: " + qualified(ref));
    }

    /** 表达式类型；BOOLEAN 表示谓词。 */
    private DataType typeOf(Expr expr, List<ScopeEntry> scope) {
        return switch (expr) {
            case Expr.Literal l -> l.value() == null ? null : DataType.ofValue(l.value());
            case Expr.ColumnRef cr -> columnType(cr, scope);
            case Expr.Compare c -> DataType.BOOLEAN;
            case Expr.Logic lg -> DataType.BOOLEAN;
            case Expr.Not n -> DataType.BOOLEAN;
            case Expr.IsNull is -> DataType.BOOLEAN;
        };
    }

    private void requireBoolean(Expr expr, List<ScopeEntry> scope, String where) {
        DataType t = typeOf(expr, scope);
        if (t != null && t != DataType.BOOLEAN) {
            throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                    where + " must be a boolean expression, got " + t);
        }
    }

    private void checkComparable(Expr.CompareOp op, DataType left, DataType right) {
        if (left == null || right == null) {
            return; // NULL 字面量可与任何类型比较，结果按三值逻辑处理
        }
        boolean ok = (left.isNumeric() && right.isNumeric()) || left == right;
        if (!ok) {
            throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                    "cannot compare " + left + " with " + right);
        }
    }

    private void requireNumeric(AggFunc func, Expr.ColumnRef arg, DataType argType) {
        if (arg == null || argType == null || !argType.isNumeric()) {
            throw new QueryException(QueryException.Category.TYPE_MISMATCH,
                    func + " requires a numeric column, got "
                            + (arg == null ? "*" : qualified(arg) + " of type " + argType));
        }
    }

    private static String qualified(Expr.ColumnRef ref) {
        return ref.table() == null ? ref.name() : ref.table() + "." + ref.name();
    }

    private static String describe(Expr e) {
        return e instanceof Expr.ColumnRef cr ? qualified(cr) : e.toString();
    }

    private static String defaultName(Expr e) {
        return e instanceof Expr.ColumnRef cr ? cr.name() : e.toString();
    }
}
