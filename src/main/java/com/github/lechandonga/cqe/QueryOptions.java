package com.github.lechandonga.cqe;

/**
 * 单次查询的执行配置。
 *
 * @param memoryLimitBytes 执行工作内存上限（字节），覆盖中间哈希表、排序等开销
 * @param memoryPolicy     超限时的策略
 */
public record QueryOptions(long memoryLimitBytes, MemoryPolicy memoryPolicy) {

    public enum MemoryPolicy {
        /** 超出上限立即以 MEMORY_LIMIT 错误明确失败，不做无界增长或静默截断。 */
        FAIL
    }

    public static QueryOptions defaults() {
        return new QueryOptions(Long.MAX_VALUE, MemoryPolicy.FAIL);
    }

    public QueryOptions {
        if (memoryLimitBytes <= 0) {
            throw new IllegalArgumentException("memoryLimitBytes 必须为正数: " + memoryLimitBytes);
        }
        if (memoryPolicy == null) {
            throw new IllegalArgumentException("memoryPolicy 不能为空");
        }
    }
}
