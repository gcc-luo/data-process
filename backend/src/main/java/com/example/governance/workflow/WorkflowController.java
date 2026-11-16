package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.util.List;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowController {
    private final WorkflowDefinitionStore workflows;
    private final TaskVersionStore tasks;
    private final AuditLog audit;
    private final WorkflowRunService runService;
    private final WorkflowRunStore runs;
    private final WorkflowScheduleStore schedules;
    public WorkflowController(WorkflowDefinitionStore workflows, TaskVersionStore tasks, AuditLog audit, WorkflowRunService runService, WorkflowRunStore runs, WorkflowScheduleStore schedules) { this.workflows = workflows; this.tasks = tasks; this.audit = audit; this.runService = runService; this.runs = runs; this.schedules = schedules; }
    record CreateRequest(String name, List<WorkflowDefinitionStore.Node> nodes, List<WorkflowGraph.Edge> edges) {}
    @GetMapping("/api/workflows")
    List<WorkflowDefinitionStore.Summary> list() { return workflows.list(); }
    @PostMapping("/api/workflows")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request, Authentication authentication) {
        try {
            if (request.nodes() == null || request.nodes().stream().anyMatch(node -> node == null || tasks.findVersion(node.taskId(), node.taskVersion()).isEmpty())) throw new IllegalArgumentException();
            long id = workflows.create(request.name(), request.nodes(), request.edges());
            audit.success(authentication, "workflow.create", "workflow", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "version", 1));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow definition"));
        }
    }
    record RunRequest(int version, LocalDate businessDate) {}
    @PostMapping("/api/workflows/{id}/runs")
    ResponseEntity<Map<String, Long>> start(@PathVariable long id, @RequestBody RunRequest request, Authentication authentication) {
        try { return ResponseEntity.accepted().body(Map.of("id", runService.start(id, request.version(), request.businessDate(), authentication))); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workflow run"); }
    }
    @GetMapping("/api/workflows/{id}/runs")
    List<WorkflowRunStore.RunSummary> history(@PathVariable long id, @RequestParam(required=false) String state, @RequestParam(required=false) LocalDate date, @RequestParam(required=false) Long beforeId, @RequestParam(defaultValue="50") int limit) {
        try { return runs.list(id, state, date, beforeId, limit); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid run history filter"); }
    }
    @GetMapping("/api/runs/{id}")
    WorkflowRunStore.RunView run(@PathVariable long id) { return runs.view(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); }
    @PostMapping("/api/runs/{id}/retry")
    ResponseEntity<Void> retry(@PathVariable long id, Authentication authentication) {
        try { runService.retry(id, authentication); return ResponseEntity.accepted().build(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Run cannot be retried"); }
    }
    record ScheduleRequest(int version, String cron, String zone) {}
    record EnabledRequest(Boolean enabled) {}
    @GetMapping("/api/schedules")
    List<WorkflowScheduleStore.View> schedules() { return schedules.list(); }
    @PostMapping("/api/workflows/{id}/schedules")
    @Transactional
    ResponseEntity<Map<String, Long>> schedule(@PathVariable long id, @RequestBody ScheduleRequest request, Authentication authentication) {
        try {
            long scheduleId = schedules.create(id, request.version(), request.cron(), request.zone(), java.time.Instant.now());
            audit.success(authentication, "schedule.create", "schedule", scheduleId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", scheduleId));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid schedule"); }
    }
    @PostMapping("/api/schedules/{id}/enabled")
    @Transactional
    ResponseEntity<Void> enable(@PathVariable long id, @RequestBody EnabledRequest request, Authentication authentication) {
        if (request.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            schedules.setEnabled(id, request.enabled(), java.time.Instant.now());
            audit.success(authentication, request.enabled() ? "schedule.enable" : "schedule.disable", "schedule", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
    record BackfillRequest(int version, LocalDate startDate, LocalDate endDate) {}
    @PostMapping("/api/workflows/{id}/backfill")
    ResponseEntity<Map<String, List<Long>>> backfill(@PathVariable long id, @RequestBody BackfillRequest request, Authentication authentication) {
        try { return ResponseEntity.accepted().body(Map.of("ids", runService.backfill(id, request.version(), request.startDate(), request.endDate(), authentication))); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid backfill configuration"); }
    }
    record VersionRequest(List<WorkflowDefinitionStore.Node> nodes, List<WorkflowGraph.Edge> edges) {}
    @GetMapping("/api/workflows/{id}/versions/{version}")
    WorkflowDefinitionStore.Definition definition(@PathVariable long id, @PathVariable int version) {
        return workflows.get(id, version).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @PostMapping("/api/workflows/{id}/versions") @Transactional
    ResponseEntity<Map<String, Object>> version(@PathVariable long id, @RequestBody VersionRequest request, Authentication authentication) {
        try {
            if (request.nodes() == null || request.nodes().stream().anyMatch(node -> node == null || tasks.findVersion(node.taskId(), node.taskVersion()).isEmpty())) throw new IllegalArgumentException();
            int version = workflows.addVersion(id, request.nodes(), request.edges());
            audit.success(authentication, "workflow.version", "workflow", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "version", version));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workflow definition"); }
    }
}
