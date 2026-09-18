package com.github.lechandonga.cqe.type;

/**
 * 列式数据集支持的逻辑类型。null 值对任何类型均合法。
 */
public enum DataType {
    INT,
    LONG,
    DOUBLE,
    STRING,
    BOOLEAN;

    public boolean isNumeric() {
        return this == INT || this == LONG || this == DOUBLE;
    }

    /** 根据运行时值推断类型；null 无法推断，返回 null。 */
    public static DataType ofValue(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return INT;
        if (value instanceof Long) return LONG;
        if (value instanceof Double) return DOUBLE;
        if (value instanceof String) return STRING;
        if (value instanceof Boolean) return BOOLEAN;
        throw new IllegalArgumentException("unsupported value type: " + value.getClass());
    }

    /** 校验值是否与本类型兼容（null 恒兼容）。 */
    public boolean accepts(Object value) {
        if (value == null) return true;
        return ofValue(value) == this;
    }
}
