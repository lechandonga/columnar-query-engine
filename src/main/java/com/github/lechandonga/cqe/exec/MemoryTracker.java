package com.github.lechandonga.cqe.exec;

import com.github.lechandonga.cqe.error.QueryException;

/**
 * 内存限额跟踪：对物化的中间结果按估算字节记账，
 * 超出上限时抛出 MEMORY_LIMIT_EXCEEDED，保证执行不会无界占用内存。
 * 记账规则确定（同一查询 + 同一数据 + 同一限额 → 同一结果），
 * 因此不同限额下的行为可复现、可解释。
 */
public class MemoryTracker {

    /** 每行固定开销（对象头、数组引用等估算）。 */
    public static final long ROW_OVERHEAD_BYTES = 32;

    private final long maxBytes;
    private long usedBytes;

    public MemoryTracker(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public void allocate(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0");
        }
        if (usedBytes + bytes > maxBytes) {
            throw new QueryException(QueryException.Category.MEMORY_LIMIT_EXCEEDED,
                    "memory limit exceeded: need " + (usedBytes + bytes)
                            + " bytes but limit is " + maxBytes + " bytes");
        }
        usedBytes += bytes;
    }

    /** 估算一行的字节数：固定开销 + 各列值估算。 */
    public static long estimateRowBytes(Object[] row) {
        long bytes = ROW_OVERHEAD_BYTES;
        for (Object v : row) {
            bytes += estimateValueBytes(v);
        }
        return bytes;
    }

    public static long estimateValueBytes(Object v) {
        if (v == null) return 4;
        if (v instanceof Integer || v instanceof Boolean) return 8;
        if (v instanceof Long) return 16;
        if (v instanceof Double) return 16;
        if (v instanceof String s) return 40L + 2L * s.length();
        return 16;
    }

    public long usedBytes() {
        return usedBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
