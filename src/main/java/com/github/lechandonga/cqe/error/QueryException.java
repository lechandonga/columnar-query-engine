package com.github.lechandonga.cqe.error;

/**
 * 所有查询引擎错误的统一异常。category 区分错误类别，
 * position 给出可定位的源码位置（不适用时为 -1）。
 */
public class QueryException extends RuntimeException {

    public enum Category {
        SYNTAX_ERROR,
        UNKNOWN_TABLE,
        UNKNOWN_FIELD,
        TYPE_MISMATCH,
        MEMORY_LIMIT_EXCEEDED,
        SCHEMA_INCOMPATIBLE
    }

    private final Category category;
    private final int position;

    public QueryException(Category category, String message) {
        this(category, message, -1);
    }

    public QueryException(Category category, String message, int position) {
        super(message);
        this.category = category;
        this.position = position;
    }

    public Category category() {
        return category;
    }

    public int position() {
        return position;
    }
}
