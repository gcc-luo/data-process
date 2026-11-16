package com.example.governance.workflow;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Single-node poller. Missed scheduled times are processed one per schedule per poll. */
public final class WorkflowSchedulePoller {
    private static final Logger log = LoggerFactory.getLogger(WorkflowSchedulePoller.class);
    private final WorkflowScheduleStore schedules;
    private final WorkflowDispatcher dispatcher;
    private final AtomicBoolean ready = new AtomicBoolean();
    public WorkflowSchedulePoller(WorkflowScheduleStore schedules, WorkflowDispatcher dispatcher) { this.schedules = schedules; this.dispatcher = dispatcher; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready.set(true); }
    @Scheduled(fixedDelayString = "${governance.schedule-poll-ms:10000}", initialDelayString = "${governance.schedule-poll-ms:10000}")
    public void poll() {
        if (!ready.get()) return;
        Instant now = Instant.now();
        for (long id : schedules.due(now, 100)) {
            try { schedules.fireDue(id, now).ifPresent(dispatcher::dispatch); }
            catch (RuntimeException ex) { log.error("Schedule {} could not be dispatched; inspect scheduler and run status", id); }
        }
    }
}
