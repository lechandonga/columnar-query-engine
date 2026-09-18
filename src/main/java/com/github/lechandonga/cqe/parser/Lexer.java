package com.github.lechandonga.cqe.parser;

import java.util.List;

/** 词法分析：把 SQL 文本切分为带位置信息的 token 序列。 */
public class Lexer {

    public enum Kind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    public record Token(Kind kind, String text, int position) {}

    public List<Token> tokenize(String sql) {
        throw new UnsupportedOperationException("not implemented");
    }
}
