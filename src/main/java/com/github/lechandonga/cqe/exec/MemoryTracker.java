package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.QueryException;
import com.github.lechandonga.cqe.QueryOptions;

/**
 * 查询工作内存的单线程记账器。按字节估计；预留失败即抛出 MEMORY_LIMIT
 * （当前仅支持 FAIL 策略：明确失败，绝不静默截断或无界增长）。
 */
public final class MemoryTracker {

    /** 不计账的求值器（用于一次性 1 行谓词判断，无额外工作内存）。 */
    public static final MemoryTracker NOOP = new MemoryTracker(Long.MAX_VALUE,
            QueryOptions.MemoryPolicy.FAIL);

    private final long limit;
    private final QueryOptions.MemoryPolicy policy;
    private long used;

    public MemoryTracker(long limit, QueryOptions.MemoryPolicy policy) {
        this.limit = limit;
        this.policy = policy;
    }

    public long usedBytes() {
        return used;
    }

    public long limitBytes() {
        return limit;
    }

    /** 预留工作内存；不足时按策略失败。 */
    public void reserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("预留字节数不能为负");
        }
        long next = used + bytes;
        if (next < 0 || next > limit) {
            throw new QueryException(QueryException.Code.MEMORY_LIMIT,
                    "超出查询内存限额: 已用 " + used + " + 申请 " + bytes
                            + " > 上限 " + limit + "（策略 FAIL：查询明确失败，结果不变）");
        }
        used = next;
    }

    /** 释放预留（中间哈希表等可释放结构归还额度）。 */
    public void release(long bytes) {
        if (bytes > used) {
            throw new IllegalStateException("释放额超过已预留额");
        }
        used -= bytes;
    }
}
