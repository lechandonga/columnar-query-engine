package com.github.lechandonga.cqe.parser;

import com.github.lechandonga.cqe.query.Query;

/**
 * 语法分析：SELECT ... FROM ... [JOIN ... ON ...] [WHERE ...]
 * [GROUP BY ...] [ORDER BY ...] [LIMIT n]。
 * 语法错误抛出带位置的 SYNTAX_ERROR。
 */
public class Parser {

    public Query parse(String sql) {
        throw new UnsupportedOperationException("not implemented");
    }
}
