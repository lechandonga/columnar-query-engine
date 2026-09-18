package com.github.lechandonga.cqe.exec;

/**
 * 内存限额跟踪：对物化的中间结果按估算字节记账，
 * 超出上限时抛出 MEMORY_LIMIT_EXCEEDED，保证执行不会无界占用内存。
 */
public class MemoryTracker {

    private final long maxBytes;
    private long usedBytes;

    public MemoryTracker(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public void allocate(long bytes) {
        throw new UnsupportedOperationException("not implemented");
    }

    public long usedBytes() {
        return usedBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
