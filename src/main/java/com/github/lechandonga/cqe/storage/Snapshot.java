package com.github.lechandonga.cqe.storage;

import com.github.lechandonga.cqe.error.QueryException;

import java.util.Map;

/** 查询执行期间看到的一致性快照：不可变的表集合。 */
public class Snapshot {

    private final Map<String, Table> tables;

    Snapshot(Map<String, Table> tables) {
        this.tables = tables;
    }

    public boolean contains(String table) {
        return tables.containsKey(normalize(table));
    }

    public Table table(String name) {
        Table t = tables.get(normalize(name));
        if (t == null) {
            throw new QueryException(QueryException.Category.UNKNOWN_TABLE,
                    "unknown table: " + name);
        }
        return t;
    }

    static String normalize(String name) {
        return name.toLowerCase();
    }
}
