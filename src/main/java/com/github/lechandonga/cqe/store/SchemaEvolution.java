package com.github.lechandonga.cqe.store;

import com.github.lechandonga.cqe.type.ColumnSchema;

import java.util.List;

/** 结构演进计划：新增列（新行用默认值填充）或删除列，整体校验、整体生效。 */
public final class SchemaEvolution {

    public sealed interface Op permits Op.AddColumn, Op.RemoveColumn {
        record AddColumn(ColumnSchema column, Object defaultValue) implements Op {}
        record RemoveColumn(String name) implements Op {}
    }

    private final List<Op> ops;

    private SchemaEvolution(List<Op> ops) {
        this.ops = List.copyOf(ops);
    }

    public static SchemaEvolution of(List<Op> ops) {
        return new SchemaEvolution(ops);
    }

    public List<Op> ops() { return ops; }
}
