package com.example.governance.workflow;

import com.example.governance.assets.DataSourceStore;
import com.example.governance.security.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Orchestration task API: draft/publish lifecycle, validation, versions and run control. */
@RestController
public class TaskController {
    private final TaskRunService runs;
    private final TaskVersionStore tasks;
    private final OrchDraftStore drafts;
    private final DataSourceStore sources;
    private final AuditLog audit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CamelTaskRunner testRunner;
    private final TaskScheduleStore schedules;
    private final OrchQueryService queryService;

    public TaskController(TaskVersionStore tasks, OrchDraftStore drafts, DataSourceStore sources, AuditLog audit, TaskRunService runs, CamelTaskRunner testRunner, TaskScheduleStore schedules, OrchQueryService queryService) {
        this.runs = runs; this.tasks = tasks; this.drafts = drafts; this.sources = sources; this.audit = audit; this.testRunner = testRunner; this.schedules = schedules; this.queryService = queryService;
    }

    public record LastRunView(String state, java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt) {}
    public record TaskView(long id, String name, String type, int version, String configuration, Long directoryId, String description,
                           String runState, LastRunView lastRun, Long draftRevision, String draftUpdatedBy, java.time.LocalDateTime draftUpdatedAt) {}

    @GetMapping("/api/tasks")
    List<TaskView> list() {
        var summaries = runs.summary();
        return tasks.list().stream().map(task -> {
            var summary = summaries.get(task.id());
            boolean running = summary != null && summary.running();
            LastRunView last = summary == null || summary.lastRun() == null ? null : new LastRunView(summary.lastRun().state(), summary.lastRun().startedAt(), summary.lastRun().finishedAt());
            var draft = drafts.find(task.id()).orElse(null);
            return new TaskView(task.id(), task.name(), task.type(), task.version(), task.configuration(), task.directoryId(), task.description(),
                    running ? "RUNNING" : "STOPPED", last,
                    draft == null ? null : draft.revision(), draft == null ? null : draft.updatedBy(), draft == null ? null : draft.updatedAt());
        }).toList();
    }

    record CreateRequest(String name, Long directoryId, String description, String template) {}
    @PostMapping("/api/tasks")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request, Authentication authentication) {
        try {
            long id = tasks.create(request.name(), "ORCH", OrchDraftStore.template(request.template()), request.directoryId(), request.description());
            drafts.initDraft(id, OrchDraftStore.template(request.template()), authentication.getName());
            audit.success(authentication, "task.create", "task", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "revision", 1));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/api/tasks/{id}/draft")
    ResponseEntity<Map<String, Object>> draft(@PathVariable long id) {
        var task = tasks.list().stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        var draft = drafts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务没有草稿"));
        try {
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("taskId", id);
            body.put("name", task.name());
            body.put("directoryId", task.directoryId());
            body.put("description", task.description());
            body.put("publishedVersion", task.version());
            body.put("revision", draft.revision());
            body.put("definition", mapper.readTree(draft.definition()));
            body.put("updatedBy", draft.updatedBy());
            body.put("updatedAt", draft.updatedAt());
            return ResponseEntity.ok(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "草稿解析失败");
        }
    }

    record DraftRequest(long expectedRevision, JsonNode definition) {}
    @PutMapping("/api/tasks/{id}/draft")
    ResponseEntity<Map<String, Object>> saveDraft(@PathVariable long id, @RequestBody DraftRequest request, Authentication authentication) {
        try {
            long revision = drafts.saveDraft(id, request.expectedRevision(), request.definition(), authentication.getName());
            audit.success(authentication, "task.draft", "task", id);
            return ResponseEntity.ok(Map.of("revision", revision));
        } catch (OrchDraftStore.RevisionConflictException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "草稿已被他人修改，请刷新后重试", "currentRevision", ex.currentRevision()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    record ValidateRequest(JsonNode definition) {}
    @PostMapping("/api/tasks/{id}/validate")
    ResponseEntity<Map<String, Object>> validate(@PathVariable long id, @RequestBody(required = false) ValidateRequest request) {
        JsonNode definition = request == null ? null : request.definition();
        if (definition == null) {
            var draft = drafts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务没有草稿"));
            try { definition = mapper.readTree(draft.definition()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "草稿解析失败"); }
        }
        return ResponseEntity.ok(validationResult(definition));
    }

    /** Standalone validation for unsaved definitions (editor 校验 button before first save). */
    @PostMapping("/api/orch/validate")
    ResponseEntity<Map<String, Object>> validateDefinition(@RequestBody ValidateRequest request) {
        return ResponseEntity.ok(validationResult(request.definition()));
    }

    private Map<String, Object> validationResult(JsonNode definition) {
        OrchDefinition parsed;
        try { parsed = OrchDefinition.parse(definition); }
        catch (IllegalArgumentException ex) {
            return Map.of("valid", false, "errors", List.of(Map.of("code", "DEFINITION_INVALID", "severity", "ERROR", "targetType", "workflow", "targetId", "", "field", "", "message", ex.getMessage())), "warnings", List.of());
        }
        var issues = parsed.validate(sourceId -> sources.find(sourceId).map(com.example.governance.assets.DataSourceDefinition::type));
        var errors = issues.stream().filter(i -> "ERROR".equals(i.severity())).toList();
        var warnings = issues.stream().filter(i -> "WARNING".equals(i.severity())).toList();
        return Map.of("valid", errors.isEmpty(), "errors", errors, "warnings", warnings);
    }

    record PublishRequest(long expectedRevision, String comment) {}
    @PostMapping("/api/tasks/{id}/publish")
    @Transactional
    ResponseEntity<Map<String, Object>> publish(@PathVariable long id, @RequestBody PublishRequest request, Authentication authentication) {
        var draft = drafts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务没有草稿"));
        JsonNode definition;
        try { definition = mapper.readTree(draft.definition()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "草稿解析失败"); }
        var result = validationResult(definition);
        if (!Boolean.TRUE.equals(result.get("valid")))
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "校验未通过，不能发布", "validation", result));
        OrchDefinition parsed;
        try { parsed = OrchDefinition.parse(definition); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        try {
            if (!sources.lockSources(parsed.sourceIds())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据源不存在或已删除");
            int version = drafts.publish(id, request.expectedRevision(), request.comment(), authentication.getName(), tasks);
            audit.success(authentication, "task.publish", "task", id);
            return ResponseEntity.ok(Map.of("version", version));
        } catch (OrchDraftStore.RevisionConflictException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "草稿已被他人修改，请刷新后重试", "currentRevision", ex.currentRevision()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/api/tasks/{id}/versions")
    List<OrchDraftStore.VersionView> versions(@PathVariable long id) { return drafts.versions(id); }

    @GetMapping("/api/tasks/{id}/versions/{version}")
    ResponseEntity<Map<String, Object>> version(@PathVariable long id, @PathVariable int version) {
        var snapshot = tasks.findVersion(id, version).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));
        var meta = drafts.versions(id).stream().filter(v -> v.version() == version).findFirst().orElse(null);
        try {
            return ResponseEntity.ok(Map.of("version", version, "definition", mapper.readTree(snapshot.configuration()),
                    "comment", meta == null || meta.comment() == null ? "" : meta.comment(),
                    "publishedBy", meta == null || meta.publishedBy() == null ? "" : meta.publishedBy(),
                    "publishedAt", meta == null || meta.publishedAt() == null ? "" : meta.publishedAt()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "版本解析失败");
        }
    }

    record CopyRequest(String name, Long directoryId) {}
    @PostMapping("/api/tasks/{id}/copy")
    @Transactional
    ResponseEntity<Map<String, Object>> copy(@PathVariable long id, @RequestBody CopyRequest request, Authentication authentication) {
        var draft = drafts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务没有草稿"));
        try {
            long newId = tasks.create(request.name(), "ORCH", draft.definition(), request.directoryId(), null);
            drafts.initDraft(newId, draft.definition(), authentication.getName());
            audit.success(authentication, "task.copy", "task", newId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", newId, "revision", 1));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/api/tasks/{id}")
    ResponseEntity<Void> metadata(@PathVariable long id, @RequestBody JsonNode changes, Authentication auth) {
        if (runs.hasActiveRun(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务运行中，不能修改");
        try { tasks.updateMetadata(id, changes); }
        catch (java.util.NoSuchElementException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基本信息无效"); }
        audit.success(auth, "task.update", "task", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/tasks/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        if (runs.hasActiveRun(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务运行中，不能删除");
        try { tasks.delete(id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
        audit.success(auth, "task.delete", "task", id);
        return ResponseEntity.noContent().build();
    }

    record RunRequest(Integer version, java.time.LocalDate businessDate, JsonNode variables) {}
    @PostMapping("/api/tasks/{id}/runs")
    ResponseEntity<Map<String, Long>> run(@PathVariable long id, @RequestBody RunRequest request, Authentication auth) {
        try {
            int version = request.version() == null ? publishedVersion(id) : request.version();
            return ResponseEntity.accepted().body(Map.of("id", runs.start(id, version, request.businessDate(), request.variables(), auth)));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
    }

    private int publishedVersion(long id) {
        var task = tasks.list().stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (task.version() < 1) throw new IllegalStateException("任务尚未发布，请先发布或改用运行测试");
        return task.version();
    }

    @PostMapping("/api/tasks/{id}/stop")
    ResponseEntity<Map<String, Integer>> stop(@PathVariable long id, Authentication auth) {
        try { return ResponseEntity.ok(Map.of("stopped", runs.stop(id, auth))); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
    }

    @GetMapping("/api/tasks/{id}/runs")
    List<TaskRunService.View> runs(@PathVariable long id) { return runs.list(id); }

    @PostMapping("/api/tasks/{id}/runs/{runId}/retry")
    ResponseEntity<Void> retry(@PathVariable long id, @PathVariable long runId, Authentication auth) {
        try { runs.retry(id, runId, auth); return ResponseEntity.accepted().build(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Task run cannot be retried"); }
    }

    @GetMapping("/api/orch/components")
    List<OrchComponents.Component> components() { return OrchComponents.all(); }

    record SchemaRequest(Long sourceId, String sql, JsonNode values, JsonNode types) {}
    @PostMapping("/api/orch/schema")
    ResponseEntity<?> schema(@RequestBody SchemaRequest request) {
        if (request.sourceId() == null || request.sourceId() < 1) return ResponseEntity.badRequest().body(Map.of("error", "请先选择数据源"));
        try {
            Map<String, Object> values = request.values() == null || request.values().isNull() ? Map.of() : mapper.convertValue(request.values(), Map.class);
            Map<String, String> types = request.types() == null || request.types().isNull() ? Map.of() : mapper.convertValue(request.types(), Map.class);
            return ResponseEntity.ok(queryService.schema(request.sourceId(), request.sql(), values, types));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "获取字段失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
        }
    }

    record QueryPreviewRequest(Long sourceId, String sql, JsonNode values, JsonNode types, Integer limit) {}
    @PostMapping("/api/orch/query-preview")
    ResponseEntity<?> queryPreview(@RequestBody QueryPreviewRequest request) {
        if (request.sourceId() == null || request.sourceId() < 1) return ResponseEntity.badRequest().body(Map.of("error", "请先选择数据源"));
        try {
            Map<String, Object> values = request.values() == null || request.values().isNull() ? Map.of() : mapper.convertValue(request.values(), Map.class);
            Map<String, String> types = request.types() == null || request.types().isNull() ? Map.of() : mapper.convertValue(request.types(), Map.class);
            var result = queryService.preview(request.sourceId(), request.sql(), values, types, request.limit() == null ? 100 : request.limit());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
        }
    }

    record ScheduleRequest(String cron, String zone, Boolean enabled) {}
    @GetMapping("/api/tasks/{id}/schedule")
    ResponseEntity<?> schedule(@PathVariable long id) {
        return schedules.find(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PutMapping("/api/tasks/{id}/schedule")
    TaskScheduleStore.View saveSchedule(@PathVariable long id, @RequestBody ScheduleRequest request, Authentication auth) {
        try {
            var view = schedules.upsert(id, request.cron(), request.zone() == null ? "Asia/Shanghai" : request.zone(), request.enabled() == null || request.enabled(), java.time.Instant.now());
            audit.success(auth, "task.schedule", "task", id);
            return view;
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }
    @DeleteMapping("/api/tasks/{id}/schedule")
    ResponseEntity<Void> deleteSchedule(@PathVariable long id, Authentication auth) {
        schedules.delete(id);
        audit.success(auth, "task.schedule.delete", "task", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/task-runs/{runId}")
    TaskRunService.RunDetail runDetail(@PathVariable long runId) {
        try { return runs.detail(runId); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在"); }
    }

    @GetMapping("/api/task-runs/{runId}/logs")
    List<TaskRunService.LogLine> runLogs(@PathVariable long runId,
                                         @org.springframework.web.bind.annotation.RequestParam(required = false) String nodeKey,
                                         @org.springframework.web.bind.annotation.RequestParam(required = false) String level,
                                         @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long afterId,
                                         @org.springframework.web.bind.annotation.RequestParam(defaultValue = "500") int limit) {
        return runs.logs(runId, nodeKey, level, afterId, limit);
    }

    @GetMapping("/api/task-runs/{runId}/nodes/{nodeKey}/preview")
    java.util.Map<String, Object> nodePreview(@PathVariable long runId, @PathVariable String nodeKey) {
        return runs.preview(runId, nodeKey);
    }

    record TestRunRequest(JsonNode definition, java.time.LocalDate businessDate, JsonNode variables) {}
    /** Dry run: validates, executes with writes disabled, returns logs/node states/previews. 30s cap. */
    @PostMapping("/api/orch/test-run")
    ResponseEntity<Map<String, Object>> testRun(@RequestBody TestRunRequest request) {
        var result = validationResult(request.definition());
        if (!Boolean.TRUE.equals(result.get("valid")))
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "校验未通过", "validation", result));
        OrchDefinition definition;
        try { definition = OrchDefinition.parse(request.definition()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        var businessDate = request.businessDate() == null ? java.time.LocalDate.now() : request.businessDate();
        var variables = request.variables() == null || request.variables().isNull() ? Map.<String, Object>of() : CamelTaskRunner.parseVariables(request.variables().toString());
        var cancel = new RunCancelContext();
        var logs = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        var nodeStates = new java.util.concurrent.CopyOnWriteArrayList<OrchTaskExecutor.NodeState>();
        RunLogSink collect = (level, message) -> logs.add(Map.of("lineNo", logs.size() + 1, "level", level, "message", message));
        var nodeSink = new OrchTaskExecutor.NodeSink() {
            @Override public void start(String nodeKey, int attempt) {}
            @Override public void success(String nodeKey, int attempt, long rowsIn, long rowsOut, long durationMs) {}
            @Override public void failure(String nodeKey, int attempt, String error, long durationMs) {}
            @Override public void skipped(String nodeKey) {}
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                cancel.worker(Thread.currentThread());
                if (cancel.isCancelled()) throw new InterruptedException("试运行已取消");
                var ctx = new OrchTaskExecutor.Context(0, null, businessDate, variables, true);
                var sinks = new ExecutionSinks(collect, json -> {}, nodeKey -> collect);
                return testRunner.execute(new TaskVersionStore.Version(0, 0, "ORCH", request.definition().toString()), ctx, cancel, sinks, nodeSink, 100, 1000);
            });
            try {
                var outcome = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                nodeStates.addAll(outcome.states());
                return ResponseEntity.ok(Map.of("logs", logs, "nodeStates", nodeStates, "previews", outcome.previews()));
            } catch (java.util.concurrent.TimeoutException | InterruptedException ex) {
                cancel.cancel();
                throw ex;
            }
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "试运行超过 30 秒");
        } catch (java.util.concurrent.ExecutionException ex) {
            String message = ex.getCause() == null ? null : ex.getCause().getMessage();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message == null ? "试运行失败" : message);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "试运行被中断");
        } finally {
            executor.shutdown();
        }
    }
}