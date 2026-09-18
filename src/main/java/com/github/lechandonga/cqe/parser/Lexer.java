package com.github.lechandonga.cqe.parser;

import com.github.lechandonga.cqe.error.QueryException;

import java.util.ArrayList;
import java.util.List;

/** 词法分析：把 SQL 文本切分为带位置信息的 token 序列。 */
public class Lexer {

    public enum Kind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    public record Token(Kind kind, String text, int position) {}

    public List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                tokens.add(new Token(Kind.IDENT, sql.substring(start, i), start));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && Character.isDigit(sql.charAt(i))) i++;
                if (i < n && sql.charAt(i) == '.') {
                    i++;
                    while (i < n && Character.isDigit(sql.charAt(i))) i++;
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
                continue;
            }
            if (c == '\'') {
                int start = i;
                i++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (i >= n) {
                        throw error("unterminated string literal", start);
                    }
                    if (sql.charAt(i) == '\'') {
                        // '' 转义为单引号
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    sb.append(sql.charAt(i));
                    i++;
                }
                tokens.add(new Token(Kind.STRING, sb.toString(), start));
                continue;
            }
            switch (c) {
                case '(', ')', ',', '*', '.' -> {
                    tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), i));
                    i++;
                }
                case '=' -> {
                    tokens.add(new Token(Kind.SYMBOL, "=", i));
                    i++;
                }
                case '!' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(Kind.SYMBOL, "!=", i));
                        i += 2;
                    } else {
                        throw error("unexpected character '!'", i);
                    }
                }
                case '<' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(Kind.SYMBOL, "<=", i));
                        i += 2;
                    } else if (i + 1 < n && sql.charAt(i + 1) == '>') {
                        tokens.add(new Token(Kind.SYMBOL, "!=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Kind.SYMBOL, "<", i));
                        i++;
                    }
                }
                case '>' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(Kind.SYMBOL, ">=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Kind.SYMBOL, ">", i));
                        i++;
                    }
                }
                default -> throw error("unexpected character '" + c + "'", i);
            }
        }
        tokens.add(new Token(Kind.EOF, "", n));
        return tokens;
    }

    private static QueryException error(String msg, int pos) {
        return new QueryException(QueryException.Category.SYNTAX_ERROR,
                msg + " (at position " + pos + ")", pos);
    }
}
