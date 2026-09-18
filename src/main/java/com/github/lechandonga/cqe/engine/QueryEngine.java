package com.github.lechandonga.cqe.engine;

import com.github.lechandonga.cqe.exec.MemoryTracker;
import com.github.lechandonga.cqe.exec.QueryExecutor;
import com.github.lechandonga.cqe.exec.QueryValidator;
import com.github.lechandonga.cqe.exec.ResultSet;
import com.github.lechandonga.cqe.exec.ValidatedQuery;
import com.github.lechandonga.cqe.parser.Parser;
import com.github.lechandonga.cqe.query.Query;
import com.github.lechandonga.cqe.storage.DataStore;
import com.github.lechandonga.cqe.storage.Snapshot;

/**
 * 查询引擎门面：解析 → 校验 → 在一致性快照上执行。
 * 每次执行独立获取快照，并发查询互不影响；
 * 内存限额作用于单次执行，超限即明确失败。
 */
public class QueryEngine {

    public static final long DEFAULT_MAX_MEMORY_BYTES = 64L * 1024 * 1024;

    private final DataStore store;
    private final long maxMemoryBytes;
    private final Parser parser = new Parser();
    private final QueryValidator validator = new QueryValidator();
    private final QueryExecutor executor = new QueryExecutor();

    public QueryEngine(DataStore store) {
        this(store, DEFAULT_MAX_MEMORY_BYTES);
    }

    public QueryEngine(DataStore store, long maxMemoryBytes) {
        this.store = store;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public DataStore store() {
        return store;
    }

    public long maxMemoryBytes() {
        return maxMemoryBytes;
    }

    public ResultSet execute(String sql) {
        Query query = parser.parse(sql);
        Snapshot snapshot = store.snapshot();
        ValidatedQuery validated = validator.validate(query, snapshot);
        return executor.execute(validated, snapshot, new MemoryTracker(maxMemoryBytes));
    }
}
