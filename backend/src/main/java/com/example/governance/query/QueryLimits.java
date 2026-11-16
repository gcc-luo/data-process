package com.example.governance.query;

import java.sql.SQLTransientException;
import java.util.concurrent.Semaphore;

/** Shared limit for interactive queries, reports and external services. */
public final class QueryLimits {
    private final Semaphore permits;
    private final int timeoutSeconds, maxRows;
    public QueryLimits(int concurrency, int timeoutSeconds, int maxRows) {
        if (concurrency < 1 || concurrency > 100 || timeoutSeconds < 1 || timeoutSeconds > 3600 || maxRows < 1 || maxRows > 1000) throw new IllegalArgumentException("Invalid query limits");
        this.permits = new Semaphore(concurrency); this.timeoutSeconds = timeoutSeconds; this.maxRows = maxRows;
    }
    public int timeoutSeconds() { return timeoutSeconds; }
    public int maxRows() { return maxRows; }
    public Permit acquire() throws SQLTransientException {
        if (!permits.tryAcquire()) throw new SQLTransientException("Query concurrency limit reached");
        return new Permit();
    }
    public final class Permit implements AutoCloseable {
        private boolean released;
        @Override public void close() { if (!released) { released = true; permits.release(); } }
    }
}
