package com.github.lechandonga.cqe;

/**
 * 统一的查询错误：携带可区分的错误码与（可选的）字符位置，便于定位与分类处理。
 */
public class QueryException extends RuntimeException {

    public enum Code {
        SYNTAX_ERROR,
        UNKNOWN_TABLE,
        UNKNOWN_COLUMN,
        AMBIGUOUS_COLUMN,
        TYPE_MISMATCH,
        AGGREGATE_MISUSE,
        GROUPING_ERROR,
        INVALID_LIMIT,
        UNSUPPORTED_FEATURE,
        MEMORY_LIMIT,
        DATA_VIOLATION,
        SCHEMA_EVOLUTION_REJECTED,
        CONCURRENT_MODIFICATION
    }

    private final Code code;
    private final int position;

    public QueryException(Code code, String message) {
        this(code, message, -1);
    }

    public QueryException(Code code, String message, int position) {
        super(message);
        this.code = code;
        this.position = position;
    }

    public Code code() {
        return code;
    }

    /** 基于 0 的字符偏移；无位置信息时返回 -1。 */
    public int position() {
        return position;
    }
}
