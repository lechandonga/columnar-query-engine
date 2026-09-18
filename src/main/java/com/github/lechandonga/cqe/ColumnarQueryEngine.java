package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.analyze.AnalyzedQuery;
import com.github.lechandonga.cqe.analyze.SemanticAnalyzer;
import com.github.lechandonga.cqe.exec.ExecutionEngine;
import com.github.lechandonga.cqe.exec.Frame;
import com.github.lechandonga.cqe.sql.QueryParser;
import com.github.lechandonga.cqe.sql.ast.SelectStatement;
import com.github.lechandonga.cqe.store.Catalog;

/**
 * 引擎门面：解析 → 校验（数据快照在此刻固定）→ 限额内执行。
 *
 * <p>分析与执行都基于目录中不可变的表快照：执行期间其它线程的插入/结构演进
 * 只会产生新快照，本查询始终看到开始时的一致视图。</p>
 */
public final class ColumnarQueryEngine {

    private final Catalog catalog;
    private final QueryOptions defaultOptions;
    private final SemanticAnalyzer analyzer;
    private final ExecutionEngine executionEngine;

    public ColumnarQueryEngine() {
        this(new Catalog(), QueryOptions.defaults());
    }

    public ColumnarQueryEngine(Catalog catalog, QueryOptions defaultOptions) {
        this.catalog = catalog;
        this.defaultOptions = defaultOptions;
        this.analyzer = new SemanticAnalyzer(catalog);
        this.executionEngine = new ExecutionEngine();
    }

    public Catalog catalog() {
        return catalog;
    }

    /** 仅解析与校验，不执行；用于预检 SQL。 */
    public AnalyzedQuery prepare(String sql) {
        SelectStatement statement = new QueryParser().parse(sql);
        return analyzer.analyze(statement);
    }

    public QueryResult execute(String sql) {
        return execute(sql, defaultOptions);
    }

    public QueryResult execute(String sql, QueryOptions options) {
        // 解析、分析在目录快照上完成；随后执行不再访问可变目录
        AnalyzedQuery query = prepare(sql);
        Frame frame = executionEngine.execute(query, options);
        return new QueryResult(frame.schema(), frame.columns());
    }
}
