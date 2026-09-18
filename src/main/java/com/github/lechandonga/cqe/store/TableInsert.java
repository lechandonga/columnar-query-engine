package com.github.lechandonga.cqe.store;

import java.util.List;

/** 一次原子多表写入中的单表插入项。 */
public record TableInsert(String tableName, List<Object[]> rows) {

    public static TableInsert of(String tableName, List<Object[]> rows) {
        return new TableInsert(tableName, rows);
    }
}
