package com.example.governance.workflow;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Single-node task schedule poller; a busy task skips the fire and retries on the next poll. */
public final class TaskSchedulePoller {
    private static final Logger log = LoggerFactory.getLogger(TaskSchedulePoller.class);
    private final TaskScheduleStore schedules;
    private final AtomicBoolean ready = new AtomicBoolean();
    public TaskSchedulePoller(TaskScheduleStore schedules) { this.schedules = schedules; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready.set(true); }
    @Scheduled(fixedDelayString = "${governance.schedule-poll-ms:10000}", initialDelayString = "${governance.schedule-poll-ms:10000}")
    public void poll() {
        if (!ready.get()) return;
        Instant now = Instant.now();
        for (long id : schedules.due(now, 100)) {
            try { schedules.fireDue(id, now); }
            catch (RuntimeException ex) { log.warn("Task schedule {} fire skipped: {}", id, ex.getMessage()); }
        }
    }
}