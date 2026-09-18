package com.github.lechandonga.cqe.engine;

import com.github.lechandonga.cqe.exec.ResultSet;
import com.github.lechandonga.cqe.storage.DataStore;

/**
 * 查询引擎门面：解析 → 校验 → 在快照上执行。
 * 并发查询各自持有独立快照，互不影响。
 */
public class QueryEngine {

    private final DataStore store;
    private final long maxMemoryBytes;

    public QueryEngine(DataStore store, long maxMemoryBytes) {
        this.store = store;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public DataStore store() {
        return store;
    }

    public ResultSet execute(String sql) {
        throw new UnsupportedOperationException("not implemented");
    }
}
