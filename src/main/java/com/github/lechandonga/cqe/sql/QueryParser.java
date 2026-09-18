package com.github.lechandonga.cqe.sql;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.sql.ast.Expr;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SELECT 递归下降解析器。所有语法错误抛出带字符位置的 SYNTAX_ERROR。 */
public final class QueryParser {

    private List<Token> tokens;
    private int pos;

    public SelectStatement parse(String sql) {
        this.tokens = new Tokenizer().tokenize(sql);
        this.pos = 0;
        SelectStatement statement = parseSelect();
        if (peek().type() != TokenType.END) {
            throw error("SELECT 语句后存在多余输入: '" + peek().text() + "'");
        }
        return statement;
    }

    private SelectStatement parseSelect() {
        expectKeyword("SELECT");
        boolean distinct = matchKeyword("DISTINCT");

        List<SelectStatement.Projection> projections = new ArrayList<>();
        if (peek().type() == TokenType.STAR) {
            Token star = next();
            projections.add(new SelectStatement.Projection(null, null));
            if (peek().type() == TokenType.COMMA) {
                throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                        "SELECT * 不能与其它投影项混用", star.position());
            }
        } else {
            do {
                projections.add(parseProjection());
            } while (accept(TokenType.COMMA) != null);
        }

        expectKeyword("FROM");
        SelectStatement.TableRef from = parseTableRef();

        List<SelectStatement.JoinClause> joins = new ArrayList<>();
        while (isJoinStart(peek())) {
            joins.add(parseJoin());
        }

        Expr where = matchKeyword("WHERE") ? parseExpression() : null;

        List<Expr> groupBy = null;
        if (matchKeyword("GROUP")) {
            expectKeyword("BY");
            groupBy = new ArrayList<>();
            do {
                groupBy.add(parseExpression());
            } while (accept(TokenType.COMMA) != null);
        }

        Expr having = matchKeyword("HAVING") ? parseExpression() : null;

        List<SelectStatement.OrderItem> orderBy = null;
        if (matchKeyword("ORDER")) {
            expectKeyword("BY");
            orderBy = new ArrayList<>();
            do {
                orderBy.add(parseOrderItem());
            } while (accept(TokenType.COMMA) != null);
        }

        Long limit = null;
        if (matchKeyword("LIMIT")) {
            limit = parseNonNegativeInteger("LIMIT");
        }
        long offset = 0;
        if (matchKeyword("OFFSET")) {
            offset = parseNonNegativeInteger("OFFSET");
        }

        return new SelectStatement(distinct, List.copyOf(projections), from,
                List.copyOf(joins), where, groupBy == null ? List.of() : List.copyOf(groupBy),
                having, orderBy == null ? List.of() : List.copyOf(orderBy), limit, offset);
    }

    private SelectStatement.Projection parseProjection() {
        Expr expr = parseExpression();
        String alias = null;
        if (matchKeyword("AS")) {
            alias = expectIdentifier();
        } else if (peek().type() == TokenType.IDENTIFIER) {
            alias = next().text();
        }
        return new SelectStatement.Projection(expr, alias);
    }

    private SelectStatement.OrderItem parseOrderItem() {
        Expr expr = parseExpression();
        boolean ascending = true;
        if (matchKeyword("ASC")) {
            ascending = true;
        } else if (matchKeyword("DESC")) {
            ascending = false;
        }
        // 默认：升序 NULLS LAST，降序 NULLS FIRST（显式声明可覆盖）
        boolean nullsFirst = !ascending;
        if (matchKeyword("NULLS")) {
            if (matchKeyword("FIRST")) {
                nullsFirst = true;
            } else {
                expectKeyword("LAST");
                nullsFirst = false;
            }
        }
        return new SelectStatement.OrderItem(expr, ascending, nullsFirst);
    }

    private SelectStatement.TableRef parseTableRef() {
        String name = expectIdentifier();
        String alias = name;
        if (matchKeyword("AS")) {
            alias = expectIdentifier();
        } else if (peek().type() == TokenType.IDENTIFIER) {
            alias = next().text();
        }
        return new SelectStatement.TableRef(name, alias);
    }

    private boolean isJoinStart(Token token) {
        if (token.type() != TokenType.KEYWORD) {
            return false;
        }
        return token.text().equals("JOIN") || token.text().equals("INNER")
                || token.text().equals("LEFT") || token.text().equals("RIGHT")
                || token.text().equals("FULL") || token.text().equals("CROSS");
    }

    private SelectStatement.JoinClause parseJoin() {
        SelectStatement.JoinType type = SelectStatement.JoinType.INNER;
        Token typeToken = peek();
        switch (typeToken.text()) {
            case "INNER" -> {
                next();
                type = SelectStatement.JoinType.INNER;
            }
            case "LEFT" -> {
                next();
                matchKeyword("OUTER");
                type = SelectStatement.JoinType.LEFT;
            }
            case "RIGHT" -> {
                next();
                matchKeyword("OUTER");
                type = SelectStatement.JoinType.RIGHT;
            }
            case "FULL" -> {
                next();
                matchKeyword("OUTER");
                type = SelectStatement.JoinType.FULL;
            }
            case "CROSS" -> {
                next();
                type = SelectStatement.JoinType.CROSS;
            }
            default -> {
                // 裸 JOIN => INNER
            }
        }
        expectKeyword("JOIN");
        SelectStatement.TableRef table = parseTableRef();
        Expr on = matchKeyword("ON") ? parseExpression() : null;
        return new SelectStatement.JoinClause(type, table, on);
    }

    private double parseDouble(Token token) {
        try {
            double d = Double.parseDouble(token.text());
            if (Double.isInfinite(d) || Double.isNaN(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                    "非法数字字面量: " + token.text(), token.position());
        }
    }

    private long parseNonNegativeInteger(String clause) {
        Token token = expect(TokenType.NUMBER);
        if (token.text().contains(".")) {
            throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                    clause + " 必须是非负整数", token.position());
        }
        try {
            long value = Long.parseLong(token.text());
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                    clause + " 必须是非负整数: " + token.text(), token.position());
        }
    }

    // ---------- 表达式 ----------

    private Expr parseExpression() {
        return parseOr();
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (matchKeyword("OR")) {
            left = new Expr.BinaryOp(left, "OR", parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        while (matchKeyword("AND")) {
            left = new Expr.BinaryOp(left, "AND", parseNot());
        }
        return left;
    }

    private Expr parseNot() {
        Token token = peek();
        if (token.type() == TokenType.KEYWORD && token.text().equals("NOT")) {
            next();
            return new Expr.UnaryOp("NOT", parseNot());
        }
        return parsePredicate();
    }

    private Expr parsePredicate() {
        Expr left = parseAdditive();
        if (peek().type() == TokenType.OPERATOR && isComparison(peek().text())) {
            String op = next().text();
            Expr right = parseAdditive();
            left = new Expr.BinaryOp(left, op, right);
        }
        if (peek().type() == TokenType.KEYWORD && peek().text().equals("IS")) {
            Token isToken = next();
            boolean negated = matchKeyword("NOT");
            if (!matchKeyword("NULL")) {
                throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                        "IS 后期望 NULL", isToken.position());
            }
            left = new Expr.IsNull(left, negated);
        }
        return left;
    }

    private boolean isComparison(String op) {
        return op.equals("=") || op.equals("<>") || op.equals("!=")
                || op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=");
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (peek().type() == TokenType.OPERATOR
                && (peek().text().equals("+") || peek().text().equals("-")
                    || peek().text().equals("||"))) {
            String op = next().text();
            Expr right = op.equals("||") ? parseAdditive() : parseMultiplicative();
            left = new Expr.BinaryOp(left, op, right);
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (peek().type() == TokenType.OPERATOR
                && (peek().text().equals("*") || peek().text().equals("/"))) {
            String op = next().text();
            left = new Expr.BinaryOp(left, op, parseUnary());
        }
        // STAR 在 tokenizer 中是独立类型，乘号也使用 STAR
        while (peek().type() == TokenType.STAR) {
            next();
            left = new Expr.BinaryOp(left, "*", parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        if (peek().type() == TokenType.OPERATOR
                && (peek().text().equals("-") || peek().text().equals("+"))) {
            String op = next().text();
            return new Expr.UnaryOp(op, parseUnary());
        }
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Token token = peek();
        switch (token.type()) {
            case NUMBER -> {
                next();
                if (token.text().contains(".")) {
                    return new Expr.Literal(parseDouble(token),
                            com.github.lechandonga.cqe.type.DataType.DOUBLE);
                }
                try {
                    return new Expr.Literal(Integer.parseInt(token.text()),
                            com.github.lechandonga.cqe.type.DataType.INT);
                } catch (NumberFormatException e) {
                    // 超出 INT 的整数按 DOUBLE 承载；连 DOUBLE 都溢出则是语法错误
                    return new Expr.Literal(parseDouble(token),
                            com.github.lechandonga.cqe.type.DataType.DOUBLE);
                }
            }
            case STRING -> {
                next();
                return new Expr.Literal(token.text(),
                        com.github.lechandonga.cqe.type.DataType.STRING);
            }
            case LPAREN -> {
                next();
                Expr inner = parseExpression();
                expect(TokenType.RPAREN);
                return inner;
            }
            case KEYWORD -> {
                return parseKeywordPrimary();
            }
            case IDENTIFIER -> {
                return parseIdentifierOrCall();
            }
            default -> throw error("此处需要表达式，但遇到 '" + token.text() + "'");
        }
    }

    private Expr parseKeywordPrimary() {
        Token token = next();
        return switch (token.text()) {
            case "TRUE" -> new Expr.Literal(Boolean.TRUE,
                    com.github.lechandonga.cqe.type.DataType.BOOLEAN);
            case "FALSE" -> new Expr.Literal(Boolean.FALSE,
                    com.github.lechandonga.cqe.type.DataType.BOOLEAN);
            case "NULL" -> new Expr.Literal(null, null);
            default -> throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                    "关键字 '" + token.text() + "' 不能作为表达式", token.position());
        };
    }

    private Expr parseIdentifierOrCall() {
        Token nameToken = next();
        String name = nameToken.text();
        if (peek().type() == TokenType.LPAREN) {
            next();
            boolean distinct = matchKeyword("DISTINCT");
            Expr arg = null;
            if (peek().type() != TokenType.RPAREN) {
                if (peek().type() == TokenType.STAR) {
                    next();
                } else {
                    arg = parseExpression();
                }
            }
            expect(TokenType.RPAREN);
            return new Expr.AggregateCall(
                    name.toUpperCase(Locale.ROOT), distinct, arg);
        }
        String qualifier = null;
        String column = name;
        if (peek().type() == TokenType.DOT) {
            next();
            qualifier = name;
            if (peek().type() == TokenType.STAR) {
                throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                        "不支持 '别名.*' 投影", peek().position());
            }
            column = expectIdentifier();
        }
        return new Expr.ColumnRef(qualifier, column);
    }

    // ---------- Token 辅助 ----------

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        return tokens.get(pos++);
    }

    private Token accept(TokenType type) {
        if (peek().type() == type) {
            return next();
        }
        return null;
    }

    private Token expect(TokenType type) {
        Token token = peek();
        if (token.type() != type) {
            throw error("期望 " + type + " 但遇到 '" + token.text() + "'");
        }
        return next();
    }

    private boolean matchKeyword(String keyword) {
        Token token = peek();
        if (token.type() == TokenType.KEYWORD && token.text().equals(keyword)) {
            next();
            return true;
        }
        return false;
    }

    private void expectKeyword(String keyword) {
        if (!matchKeyword(keyword)) {
            throw error("期望关键字 " + keyword + " 但遇到 '" + peek().text() + "'");
        }
    }

    private String expectIdentifier() {
        return expect(TokenType.IDENTIFIER).text();
    }

    private QueryException error(String message) {
        Token token = peek();
        return new QueryException(QueryException.Code.SYNTAX_ERROR,
                message + " (位置 " + token.position() + ")", token.position());
    }
}
