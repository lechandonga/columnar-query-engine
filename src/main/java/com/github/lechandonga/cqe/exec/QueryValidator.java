package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.query.Query;
import com.github.lechandonga.cqe.storage.Snapshot;

/**
 * 语义校验：未知表 / 未知字段 / 类型不匹配在此被拒绝，
 * 并解析列引用、推断输出 schema。
 */
public class QueryValidator {

    public ValidatedQuery validate(Query query, Snapshot snapshot) {
        throw new UnsupportedOperationException("not implemented");
    }
}
