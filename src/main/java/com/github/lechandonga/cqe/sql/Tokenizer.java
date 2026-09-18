package com.github.lechandonga.cqe.sql;

import com.github.lechandonga.cqe.QueryException;

import java.util.List;
import java.util.Set;

/** 将 SQL 文本切分为 Token 流；非法字符抛出带位置的 SYNTAX_ERROR。 */
public final class Tokenizer {

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "DISTINCT", "FROM", "AS", "WHERE", "GROUP", "BY", "HAVING",
            "ORDER", "ASC", "DESC", "NULLS", "FIRST", "LAST", "LIMIT", "OFFSET",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON",
            "AND", "OR", "NOT", "IS", "NULL", "TRUE", "FALSE");

    public List<Token> tokenize(String sql) {
        if (sql == null) {
            throw new QueryException(QueryException.Code.SYNTAX_ERROR, "SQL 不能为空");
        }
        java.util.List<Token> tokens = new java.util.ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 >= n) {
                    throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                            "块注释未闭合", start);
                }
                i += 2;
            } else if (c == '"') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && sql.charAt(i) != '"') {
                    sb.append(sql.charAt(i));
                    i++;
                }
                if (i >= n) {
                    throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                            "标识符引号未闭合", start);
                }
                i++;
                tokens.add(new Token(TokenType.IDENTIFIER, sb.toString(), start));
            } else if (c == '\'') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) {
                        throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                                "字符串字面量未闭合", start);
                    }
                    char ch = sql.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                tokens.add(new Token(TokenType.STRING, sb.toString(), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    sb.append(sql.charAt(i));
                    i++;
                }
                String word = sb.toString();
                String upper = word.toUpperCase(java.util.Locale.ROOT);
                if (KEYWORDS.contains(upper)) {
                    tokens.add(new Token(TokenType.KEYWORD, upper, start));
                } else {
                    // 标识符/函数名保留原始拼写（列名大小写敏感）
                    tokens.add(new Token(TokenType.IDENTIFIER, word, start));
                }
            } else if (Character.isDigit(c)) {
                int start = i;
                boolean dot = false;
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    if (sql.charAt(i) == '.') {
                        if (dot || i + 1 >= n || !Character.isDigit(sql.charAt(i + 1))) {
                            break;
                        }
                        dot = true;
                    }
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, sql.substring(start, i), start));
            } else if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, "(", i));
                i++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")", i));
                i++;
            } else if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, ",", i));
                i++;
            } else if (c == '.') {
                tokens.add(new Token(TokenType.DOT, ".", i));
                i++;
            } else if (c == '*') {
                tokens.add(new Token(TokenType.STAR, "*", i));
                i++;
            } else if (c == '|' && i + 1 < n && sql.charAt(i + 1) == '|') {
                tokens.add(new Token(TokenType.OPERATOR, "||", i));
                i += 2;
            } else if (i + 1 < n && isTwoCharOperator(c, sql.charAt(i + 1))) {
                tokens.add(new Token(TokenType.OPERATOR,
                        "" + c + sql.charAt(i + 1), i));
                i += 2;
            } else if (isOneCharOperator(c)) {
                tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c), i));
                i++;
            } else {
                throw new QueryException(QueryException.Code.SYNTAX_ERROR,
                        "无法识别的字符: '" + c + "'", i);
            }
        }
        tokens.add(new Token(TokenType.END, "", n));
        return List.copyOf(tokens);
    }

    private static boolean isOneCharOperator(char c) {
        return c == '=' || c == '<' || c == '>' || c == '+' || c == '-' || c == '/';
    }

    private static boolean isTwoCharOperator(char a, char b) {
        return (a == '<' && (b == '>' || b == '='))
                || (a == '>' && b == '=')
                || (a == '!' && b == '=');
    }
}
