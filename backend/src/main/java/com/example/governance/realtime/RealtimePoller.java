package com.example.governance.realtime;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public final class RealtimePoller {
    private static final Logger log = LoggerFactory.getLogger(RealtimePoller.class);
    private final RealtimeJobStore jobs;
    private final AtomicBoolean ready = new AtomicBoolean();
    public RealtimePoller(RealtimeJobStore jobs) { this.jobs = jobs; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready.set(true); }
    @Scheduled(fixedDelayString = "${governance.realtime-poll-ms:5000}", initialDelayString = "${governance.realtime-poll-ms:5000}")
    public void poll() {
        if (!ready.get()) return;
        for (long id : jobs.due(Instant.now(), 100)) {
            try { jobs.poll(id, Instant.now()); }
            catch (RuntimeException ex) { log.error("Realtime job {} could not be processed; inspect job status", id); }
        }
    }
}
