package com.github.lechandonga.cqe.storage;

import com.github.lechandonga.cqe.type.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 不可变列：类型 + 按行序排列的值，null 表示空值。 */
public class Column {

    private final DataType type;
    private final List<Object> values;

    public Column(DataType type, List<Object> values) {
        this.type = type;
        // 列值允许 null，不能用 List.copyOf
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public DataType type() {
        return type;
    }

    public int size() {
        return values.size();
    }

    public Object get(int row) {
        return values.get(row);
    }

    public List<Object> values() {
        return values;
    }
}
