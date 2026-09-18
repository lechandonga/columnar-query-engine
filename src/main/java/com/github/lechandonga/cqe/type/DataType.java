package com.github.lechandonga.cqe.type;

/** 引擎支持的列数据类型。 */
public enum DataType {
    INT,
    DOUBLE,
    BOOLEAN,
    STRING;

    public boolean isNumeric() {
        return this == INT || this == DOUBLE;
    }

    /**
     * 两种类型是否可比较/可用于等值连接。
     * 规则：完全相同的类型可比较；INT 与 DOUBLE 作为数值可跨类型比较；
     * 字符串与数字/布尔之间不可比较（类型不匹配需被拒绝）。
     */
    public boolean isComparableWith(DataType other) {
        if (this == other) {
            return true;
        }
        return this.isNumeric() && other.isNumeric();
    }
}
