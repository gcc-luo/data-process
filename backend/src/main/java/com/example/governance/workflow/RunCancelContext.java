package com.example.governance.workflow;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation shared between the stop endpoint and the executing worker. */
public final class RunCancelContext {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Statement statement;
    private volatile Thread worker;
    public boolean isCancelled() { return cancelled.get(); }
    public void worker(Thread thread) { this.worker = thread; }
    public void register(Statement active) { this.statement = active; }
    public void cancel() {
        cancelled.set(true);
        Statement active = statement;
        if (active != null) try { active.cancel(); } catch (SQLException ignored) { }
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }
}
