package com.example.governance.workflow;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public final class WorkflowRetryPoller {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRetryPoller.class);
    private final WorkflowRunStore runs;
    private final WorkflowDispatcher dispatcher;
    private final AtomicBoolean ready = new AtomicBoolean();
    public WorkflowRetryPoller(WorkflowRunStore runs, WorkflowDispatcher dispatcher) { this.runs = runs; this.dispatcher = dispatcher; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready.set(true); }
    @Scheduled(fixedDelayString = "${governance.schedule-poll-ms:10000}", initialDelayString = "${governance.schedule-poll-ms:10000}")
    public void poll() {
        if (!ready.get()) return;
        Instant now = Instant.now();
        for (long id : runs.dueRetries(now, 100)) {
            try { if (runs.activateDueRetries(id, now)) dispatcher.dispatch(id); }
            catch (RuntimeException ex) { log.error("Retry for run {} could not be dispatched; inspect run status", id); }
        }
    }
}
