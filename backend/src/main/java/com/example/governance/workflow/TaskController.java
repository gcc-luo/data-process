package com.example.governance.workflow;

import com.example.governance.assets.DataSourceStore;
import com.example.governance.security.AuditLog;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {
    private final TaskRunService runs;
    private final TaskVersionStore tasks;
    private final TaskDefinitionValidator validator;
    private final DataSourceStore sources;
    private final AuditLog audit;
    public TaskController(TaskVersionStore tasks, TaskDefinitionValidator validator, DataSourceStore sources, AuditLog audit, TaskRunService runs) { this.runs = runs; this.tasks = tasks; this.validator = validator; this.sources = sources; this.audit = audit; }
    record CreateRequest(String name, String type, JsonNode configuration, Long directoryId, String description) {}
    public record LastRunView(String state, java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt) {}
    public record TaskView(long id, String name, String type, int version, String configuration, Long directoryId, String description, String runState, LastRunView lastRun) {}
    @GetMapping("/api/tasks")
    List<TaskView> list() {
        var summaries = runs.summary();
        return tasks.list().stream().map(task -> {
            var summary = summaries.get(task.id());
            boolean running = summary != null && summary.running();
            LastRunView last = summary == null || summary.lastRun() == null ? null : new LastRunView(summary.lastRun().state(), summary.lastRun().startedAt(), summary.lastRun().finishedAt());
            return new TaskView(task.id(), task.name(), task.type(), task.version(), task.configuration(), task.directoryId(), task.description(), running ? "RUNNING" : "STOPPED", last);
        }).toList();
    }
    @PutMapping("/api/tasks/{id}")
    ResponseEntity<Void> metadata(@PathVariable long id, @RequestBody JsonNode changes, Authentication auth) {
        if (runs.hasActiveRun(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务运行中，不能修改");
        try { tasks.updateMetadata(id, changes); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基本信息无效"); }
        audit.success(auth, "task.update", "task", id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/tasks")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request, Authentication authentication) {
        try {
            String config = verify(request.type(), request.configuration());
            long id = tasks.create(request.name(), request.type(), config, request.directoryId(), request.description());
            audit.success(authentication, "task.create", "task", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "version", 1));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid task configuration"));
        }
    }
    record VersionRequest(JsonNode configuration) {}
    @PostMapping("/api/tasks/{id}/versions") @Transactional
    ResponseEntity<Map<String, Object>> version(@PathVariable long id, @RequestBody VersionRequest request, Authentication authentication) {
        try {
            String type = tasks.findVersion(id, 1).orElseThrow(() -> new IllegalArgumentException("Unknown task")).type();
            int version = tasks.addVersion(id, verify(type, request.configuration()));
            audit.success(authentication, "task.version", "task", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "version", version));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task configuration"); }
    }
    record RunRequest(int version, java.time.LocalDate businessDate) {}
    @PostMapping("/api/tasks/{id}/runs")
    ResponseEntity<Map<String, Long>> run(@PathVariable long id, @RequestBody RunRequest request, Authentication auth) {
        try { return ResponseEntity.accepted().body(Map.of("id", runs.start(id, request.version(), request.businessDate(), auth))); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task run"); }
    }
    @GetMapping("/api/tasks/{id}/runs")
    List<TaskRunService.View> runs(@PathVariable long id) { return runs.list(id); }
    @PostMapping("/api/tasks/{id}/runs/{runId}/retry")
    ResponseEntity<Void> retry(@PathVariable long id, @PathVariable long runId, Authentication auth) {
        try { runs.retry(id, runId, auth); return ResponseEntity.accepted().build(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Task run cannot be retried"); }
    }
    private String verify(String type, JsonNode configuration) {
        if (configuration == null) throw new IllegalArgumentException();
        String config = configuration.toString(); validator.validate(type, config);
        var sourceIds = new java.util.ArrayList<Long>(); sourceIds.add(configuration.path("sourceId").longValue());
        if ("SYNC".equals(type)) sourceIds.add(configuration.path("targetId").longValue());
        if (!sources.lockSources(sourceIds)) throw new IllegalArgumentException();
        return config;
    }
}
