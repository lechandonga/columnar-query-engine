package com.github.lechandonga.cqe.type;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 有序的列结构集合；列名唯一（不区分大小写的比较由解析层在需要时处理，此处按原样区分）。 */
public final class Schema {

    private final List<ColumnSchema> columns;

    public Schema(List<ColumnSchema> columns) {
        this(columns, true);
    }

    /**
     * @param rejectDuplicates 用户定义的表结构禁止重名；执行期帧使用规范列名
     *                         （别名.列名 / g0 / a0），唯一性由分析器保证，允许同名列共存。
     */
    public Schema(List<ColumnSchema> columns, boolean rejectDuplicates) {
        if (rejectDuplicates) {
            Set<String> seen = new HashSet<>();
            for (ColumnSchema column : columns) {
                if (!seen.add(column.name())) {
                    throw new IllegalArgumentException("重复列名: " + column.name());
                }
            }
        }
        this.columns = List.copyOf(columns);
    }

    public int size() {
        return columns.size();
    }

    public List<ColumnSchema> columns() {
        return columns;
    }

    public ColumnSchema get(int index) {
        return columns.get(index);
    }

    /** 返回列的物理下标；不存在返回 -1。 */
    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
