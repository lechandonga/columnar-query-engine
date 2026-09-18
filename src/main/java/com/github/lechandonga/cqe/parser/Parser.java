package com.github.lechandonga.cqe.parser;

import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.parser.Lexer.Kind;
import com.github.lechandonga.cqe.parser.Lexer.Token;
import com.github.lechandonga.cqe.query.AggFunc;
import com.github.lechandonga.cqe.query.Expr;
import com.github.lechandonga.cqe.query.Query;
import com.github.lechandonga.cqe.query.SelectItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 语法分析：SELECT ... FROM ... [JOIN ... ON ...] [WHERE ...]
 * [GROUP BY ...] [ORDER BY ...] [LIMIT n]。
 * 语法错误抛出带位置的 SYNTAX_ERROR。
 */
public class Parser {

    private static final Set<String> RESERVED = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "LIMIT",
            "JOIN", "INNER", "LEFT", "ON", "AS", "AND", "OR", "NOT",
            "IS", "NULL", "ASC", "DESC", "TRUE", "FALSE");

    private List<Token> tokens;
    private int pos;

    public Query parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw error("empty query", 0);
        }
        this.tokens = new Lexer().tokenize(sql);
        this.pos = 0;
        Query q = parseQuery();
        expect(Kind.EOF, null);
        return q;
    }

    private Query parseQuery() {
        expectKeyword("SELECT");
        List<SelectItem> select = parseSelectList();
        expectKeyword("FROM");
        String fromTable = expectIdent();
        String fromAlias = parseOptionalAlias();

        Query.JoinClause join = null;
        if (isKeyword("INNER") || isKeyword("LEFT") || isKeyword("JOIN")) {
            Query.JoinType type = Query.JoinType.INNER;
            if (isKeyword("INNER")) {
                advance();
            } else if (isKeyword("LEFT")) {
                type = Query.JoinType.LEFT;
                advance();
            }
            expectKeyword("JOIN");
            String joinTable = expectIdent();
            String joinAlias = parseOptionalAlias();
            expectKeyword("ON");
            Expr on = parseExpr();
            join = new Query.JoinClause(type, joinTable, joinAlias, on);
        }

        Expr where = null;
        if (isKeyword("WHERE")) {
            advance();
            where = parseExpr();
        }

        List<Expr.ColumnRef> groupBy = List.of();
        if (isKeyword("GROUP")) {
            advance();
            expectKeyword("BY");
            groupBy = parseColumnRefList();
        }

        List<Query.OrderItem> orderBy = List.of();
        if (isKeyword("ORDER")) {
            advance();
            expectKeyword("BY");
            orderBy = parseOrderList();
        }

        Integer limit = null;
        if (isKeyword("LIMIT")) {
            advance();
            Token t = peek();
            if (t.kind() != Kind.NUMBER || t.text().contains(".")) {
                throw error("LIMIT expects a non-negative integer", t.position());
            }
            long v;
            try {
                v = Long.parseLong(t.text());
            } catch (NumberFormatException e) {
                throw error("LIMIT value out of range", t.position());
            }
            if (v < 0 || v > Integer.MAX_VALUE) {
                throw error("LIMIT value out of range", t.position());
            }
            limit = (int) v;
            advance();
        }

        return new Query(select, fromTable, fromAlias, join, where, groupBy, orderBy, limit);
    }

    private List<SelectItem> parseSelectList() {
        List<SelectItem> items = new ArrayList<>();
        items.add(parseSelectItem());
        while (isSymbol(",")) {
            advance();
            items.add(parseSelectItem());
        }
        return items;
    }

    private SelectItem parseSelectItem() {
        if (isSymbol("*")) {
            advance();
            return new SelectItem.Star();
        }
        Token t = peek();
        if (t.kind() == Kind.IDENT && isAggFunc(t.text())) {
            Token la = peekAt(1);
            if (la.kind() == Kind.SYMBOL && la.text().equals("(")) {
                advance(); // func
                advance(); // (
                AggFunc func = AggFunc.valueOf(t.text().toUpperCase());
                Expr.ColumnRef arg = null;
                if (isSymbol("*")) {
                    advance();
                    if (func != AggFunc.COUNT) {
                        throw error(func + "(*) is not allowed; only COUNT(*)", t.position());
                    }
                } else {
                    arg = parseColumnRef();
                }
                expect(Kind.SYMBOL, ")");
                String alias = parseOptionalAlias();
                return new SelectItem.AggItem(func, arg, alias);
            }
        }
        Expr.ColumnRef ref = parseColumnRef();
        String alias = parseOptionalAlias();
        return new SelectItem.ExprItem(ref, alias);
    }

    private static boolean isAggFunc(String ident) {
        return switch (ident.toUpperCase()) {
            case "COUNT", "SUM", "AVG", "MIN", "MAX" -> true;
            default -> false;
        };
    }

    private List<Expr.ColumnRef> parseColumnRefList() {
        List<Expr.ColumnRef> cols = new ArrayList<>();
        cols.add(parseColumnRef());
        while (isSymbol(",")) {
            advance();
            cols.add(parseColumnRef());
        }
        return cols;
    }

    private List<Query.OrderItem> parseOrderList() {
        List<Query.OrderItem> items = new ArrayList<>();
        items.add(parseOrderItem());
        while (isSymbol(",")) {
            advance();
            items.add(parseOrderItem());
        }
        return items;
    }

    private Query.OrderItem parseOrderItem() {
        Expr.ColumnRef ref = parseColumnRef();
        boolean asc = true;
        if (isKeyword("ASC")) {
            advance();
        } else if (isKeyword("DESC")) {
            asc = false;
            advance();
        }
        return new Query.OrderItem(ref, asc);
    }

    // expr := orExpr
    private Expr parseExpr() {
        Expr left = parseAnd();
        while (isKeyword("OR")) {
            advance();
            left = new Expr.Logic(Expr.LogicOp.OR, left, parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        while (isKeyword("AND")) {
            advance();
            left = new Expr.Logic(Expr.LogicOp.AND, left, parseNot());
        }
        return left;
    }

    private Expr parseNot() {
        if (isKeyword("NOT")) {
            advance();
            return new Expr.Not(parseNot());
        }
        return parsePredicate();
    }

    private Expr parsePredicate() {
        if (isSymbol("(")) {
            advance();
            Expr e = parseExpr();
            expect(Kind.SYMBOL, ")");
            return e;
        }
        Expr left = parseOperand();
        if (isKeyword("IS")) {
            advance();
            boolean negated = false;
            if (isKeyword("NOT")) {
                negated = true;
                advance();
            }
            expectKeyword("NULL");
            return new Expr.IsNull(left, negated);
        }
        Token t = peek();
        if (t.kind() == Kind.SYMBOL) {
            Expr.CompareOp op = switch (t.text()) {
                case "=" -> Expr.CompareOp.EQ;
                case "!=" -> Expr.CompareOp.NE;
                case "<" -> Expr.CompareOp.LT;
                case "<=" -> Expr.CompareOp.LE;
                case ">" -> Expr.CompareOp.GT;
                case ">=" -> Expr.CompareOp.GE;
                default -> null;
            };
            if (op != null) {
                advance();
                return new Expr.Compare(op, left, parseOperand());
            }
        }
        // 单独的列引用 / 字面量作为布尔谓词
        return left;
    }

    private Expr parseOperand() {
        Token t = peek();
        switch (t.kind()) {
            case NUMBER -> {
                advance();
                if (t.text().contains(".")) {
                    return new Expr.Literal(Double.parseDouble(t.text()));
                }
                try {
                    return new Expr.Literal(Integer.valueOf(t.text()));
                } catch (NumberFormatException e) {
                    try {
                        return new Expr.Literal(Long.valueOf(t.text()));
                    } catch (NumberFormatException e2) {
                        throw error("numeric literal out of range", t.position());
                    }
                }
            }
            case STRING -> {
                advance();
                return new Expr.Literal(t.text());
            }
            case IDENT -> {
                String upper = t.text().toUpperCase();
                if (upper.equals("TRUE") || upper.equals("FALSE")) {
                    advance();
                    return new Expr.Literal(Boolean.valueOf(upper.equals("TRUE")));
                }
                if (upper.equals("NULL")) {
                    advance();
                    return new Expr.Literal(null);
                }
                return parseColumnRef();
            }
            default -> throw error("expected column, literal or expression", t.position());
        }
    }

    private Expr.ColumnRef parseColumnRef() {
        Token t = peek();
        if (t.kind() != Kind.IDENT || RESERVED.contains(t.text().toUpperCase())) {
            throw error("expected column name", t.position());
        }
        advance();
        String first = t.text();
        if (isSymbol(".")) {
            advance();
            Token t2 = peek();
            if (t2.kind() != Kind.IDENT) {
                throw error("expected column name after '.'", t2.position());
            }
            advance();
            return new Expr.ColumnRef(first, t2.text());
        }
        return new Expr.ColumnRef(null, first);
    }

    /** 可选别名：AS ident，或紧跟的非保留字标识符。 */
    private String parseOptionalAlias() {
        if (isKeyword("AS")) {
            advance();
            return expectIdent();
        }
        Token t = peek();
        if (t.kind() == Kind.IDENT && !RESERVED.contains(t.text().toUpperCase())
                && !isAggFunc(t.text())) {
            advance();
            return t.text();
        }
        return null;
    }

    // ---- token 工具 ----

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAt(int offset) {
        int idx = Math.min(pos + offset, tokens.size() - 1);
        return tokens.get(idx);
    }

    private void advance() {
        if (pos < tokens.size() - 1) pos++;
    }

    private boolean isKeyword(String kw) {
        Token t = peek();
        return t.kind() == Kind.IDENT && t.text().equalsIgnoreCase(kw);
    }

    private boolean isSymbol(String sym) {
        Token t = peek();
        return t.kind() == Kind.SYMBOL && t.text().equals(sym);
    }

    private void expectKeyword(String kw) {
        if (!isKeyword(kw)) {
            throw error("expected " + kw + " but found '" + describe(peek()) + "'",
                    peek().position());
        }
        advance();
    }

    private void expect(Kind kind, String text) {
        Token t = peek();
        if (t.kind() != kind || (text != null && !t.text().equals(text))) {
            String what = text != null ? "'" + text + "'" : kind.name();
            throw error("expected " + what + " but found '" + describe(t) + "'", t.position());
        }
        advance();
    }

    private String expectIdent() {
        Token t = peek();
        if (t.kind() != Kind.IDENT || RESERVED.contains(t.text().toUpperCase())) {
            throw error("expected identifier but found '" + describe(t) + "'", t.position());
        }
        advance();
        return t.text();
    }

    private static String describe(Token t) {
        return t.kind() == Kind.EOF ? "<end of query>" : t.text();
    }

    private static QueryException error(String msg, int pos) {
        return new QueryException(QueryException.Category.SYNTAX_ERROR,
                msg + " (at position " + pos + ")", pos);
    }
}
