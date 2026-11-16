package com.example.governance.workflow;

import java.util.concurrent.Semaphore;

/** Shared process-wide bound on actively executing database tasks. */
public final class TaskExecutionLimiter {
    private final Semaphore permits;
    public TaskExecutionLimiter(int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 64) throw new IllegalArgumentException("Task concurrency must be between 1 and 64");
        permits = new Semaphore(maxConcurrent, true);
    }
    public Permit acquire() throws InterruptedException { permits.acquire(); return new Permit(); }
    public final class Permit implements AutoCloseable {
        private boolean released;
        @Override public synchronized void close() { if (!released) { released = true; permits.release(); } }
    }
}
