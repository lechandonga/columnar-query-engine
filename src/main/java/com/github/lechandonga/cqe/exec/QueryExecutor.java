package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.storage.Snapshot;

/** 在一致性快照上执行已校验的查询，受内存限额约束。 */
public class QueryExecutor {

    public ResultSet execute(ValidatedQuery query, Snapshot snapshot, MemoryTracker memory) {
        throw new UnsupportedOperationException("not implemented");
    }
}
