package com.example.governance.workflow;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation shared between the stop endpoint and the executing worker. */
public final class RunCancelContext {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    // 多语句注册：FLOW 管道同时持有来源/insert/update 语句，cancel 需逐个传播
    private final java.util.concurrent.CopyOnWriteArrayList<Statement> statements = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Thread worker;
    public boolean isCancelled() { return cancelled.get(); }
    public void worker(Thread thread) { this.worker = thread; }
    public void register(Statement active) { statements.add(active); }
    /** worker 结束后摘除引用：迟到的 cancel 只置标志，不再 interrupt/cancel，避免误伤复用的池线程。 */
    public void detach() { worker = null; statements.clear(); }
    public void cancel() {
        cancelled.set(true);
        for (Statement active : statements) try { active.cancel(); } catch (SQLException ignored) { }
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }
}
