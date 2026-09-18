package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.query.Query;
import com.github.lechandonga.cqe.schema.ColumnSchema;

import java.util.List;

/** 校验通过的查询：原 AST + 推断出的输出 schema。 */
public record ValidatedQuery(Query query, List<ColumnSchema> outputSchema) {}
