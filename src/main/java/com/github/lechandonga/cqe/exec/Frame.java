package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.column.ColumnVector;
import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.Schema;

import java.util.ArrayList;
import java.util.List;

/** 执行管线中的列式数据帧：结构 + 等长列向量，全部不可变。 */
public record Frame(Schema schema, List<ColumnVector> columns) {

    public Frame {
        if (schema == null || columns == null) {
            throw new IllegalArgumentException("schema/columns 不能为空");
        }
        if (schema.size() != columns.size()) {
            throw new IllegalArgumentException("帧结构与列数不一致");
        }
        int rows = columns.isEmpty() ? 0 : columns.get(0).size();
        for (ColumnVector vector : columns) {
            if (vector.size() != rows) {
                throw new IllegalArgumentException("帧内列向量必须等长");
            }
        }
        columns = List.copyOf(columns);
    }

    public int rowCount() {
        return columns.isEmpty() ? 0 : columns.get(0).size();
    }

    public ColumnVector column(int index) {
        return columns.get(index);
    }

    /** 按规范列名（如 "t.id"、"g0"、"a0"）取下标；不存在返回 -1。 */
    public int indexOf(String canonicalName) {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(canonicalName)) {
                return i;
            }
        }
        return -1;
    }

    public static Builder builder(Schema schema) {
        return new Builder(schema);
    }

    /** 逐列构建帧。 */
    public static final class Builder {
        private final Schema schema;
        private final List<ColumnVector> columns = new ArrayList<>();

        private Builder(Schema schema) {
            this.schema = schema;
        }

        public Builder add(ColumnVector vector) {
            if (vector.type() != schema.get(columns.size()).type()) {
                throw new IllegalArgumentException("列类型与帧结构不匹配: 期望 "
                        + schema.get(columns.size()).type() + " 实际 " + vector.type());
            }
            columns.add(vector);
            return this;
        }

        public Frame build() {
            return new Frame(schema, columns);
        }
    }
}
