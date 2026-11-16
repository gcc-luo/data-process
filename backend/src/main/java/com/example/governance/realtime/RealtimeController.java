package com.example.governance.realtime;

import com.example.governance.assets.DataSourceStore;
import com.example.governance.security.AuditLog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/realtime-jobs")
public class RealtimeController {
    private final RealtimeJobStore jobs;
    private final DataSourceStore sources;
    private final AuditLog audit;
    public RealtimeController(RealtimeJobStore jobs, DataSourceStore sources, AuditLog audit) { this.jobs = jobs; this.sources = sources; this.audit = audit; }
    record CreateRequest(String name, RealtimeJobStore.JobConfig config) {}
    @GetMapping List<RealtimeJobStore.View> list() { return jobs.list(); }
    @PostMapping @Transactional
    ResponseEntity<Map<String, Long>> create(@RequestBody CreateRequest request, Authentication auth) {
        try {
            if (request.config() == null || sources.find(request.config().sourceId()).isEmpty() || sources.find(request.config().targetId()).isEmpty()) throw new IllegalArgumentException();
            long id = jobs.create(request.name(), request.config()); audit.success(auth, "realtime.create", "realtime", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid realtime job"); }
    }
    @PutMapping("/{id}/settings") @Transactional
    ResponseEntity<Void> settings(@PathVariable long id, @RequestBody com.fasterxml.jackson.databind.JsonNode request, Authentication auth) {
        if (request == null || !request.isObject() || request.size() != 2 || !request.path("intervalSeconds").isInt() || !request.path("batchSize").isInt()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid realtime settings");
        try {
            jobs.updateSettings(id, request.path("intervalSeconds").intValue(), request.path("batchSize").intValue());
            audit.success(auth, "realtime.settings", "realtime", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            if (id < 1 || jobs.view(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid realtime settings");
        } catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Stop the job before updating settings"); }
    }
    @PostMapping("/{id}/start") @Transactional
    ResponseEntity<Void> start(@PathVariable long id, Authentication auth) {
        try { jobs.start(id, Instant.now()); audit.success(auth, "realtime.start", "realtime", id); return ResponseEntity.noContent().build(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
    }
    @PostMapping("/{id}/stop") @Transactional
    ResponseEntity<Void> stop(@PathVariable long id, Authentication auth) {
        try { jobs.stop(id); audit.success(auth, "realtime.stop", "realtime", id); return ResponseEntity.noContent().build(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
}
