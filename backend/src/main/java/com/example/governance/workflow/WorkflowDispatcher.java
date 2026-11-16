package com.example.governance.workflow;

import java.util.concurrent.Executor;

/** Submits an already committed run to a bounded application executor. */
public final class WorkflowDispatcher {
    private final WorkflowRunStore runs;
    private final WorkflowWorker worker;
    private final Executor executor;
    public WorkflowDispatcher(WorkflowRunStore runs, WorkflowWorker worker, Executor executor) { this.runs = runs; this.worker = worker; this.executor = executor; }
    public void dispatch(long runId) {
        try {
            executor.execute(() -> {
                try { worker.process(runId); }
                catch (RuntimeException failure) { runs.markInterrupted(runId); }
            });
        } catch (RuntimeException rejected) {
            try { runs.markInterrupted(runId); } catch (RuntimeException failure) { rejected.addSuppressed(failure); }
            throw rejected;
        }
    }
}
