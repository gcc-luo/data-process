package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WorkflowRunService {
    private final WorkflowRunStore runs;
    private final WorkflowDispatcher dispatcher;
    private final AuditLog audit;
    private final WorkflowDefinitionStore definitions;
    private final TaskVersionStore tasks;
    private final TaskDefinitionValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();
    public WorkflowRunService(WorkflowRunStore runs, WorkflowDispatcher dispatcher, AuditLog audit, WorkflowDefinitionStore definitions, TaskVersionStore tasks, TaskDefinitionValidator validator) { this.runs = runs; this.dispatcher = dispatcher; this.audit = audit; this.definitions = definitions; this.tasks = tasks; this.validator = validator; }
    @Transactional
    public List<Long> backfill(long workflowId, int version, LocalDate start, LocalDate end, Authentication authentication) {
        if (start == null || end == null || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 31) throw new IllegalArgumentException("Invalid date range");
        var definition = definitions.get(workflowId, version).orElseThrow(() -> new IllegalArgumentException("Unknown workflow version"));
        for (var node : definition.nodes()) {
            var task = tasks.findVersion(node.taskId(), node.taskVersion()).orElseThrow(() -> new IllegalArgumentException("Unknown task"));
            validator.validate(task.type(), task.configuration());
            try {
                var config = mapper.readTree(task.configuration());
                var parameters = config.path("SQL".equals(task.type()) ? "parameters" : "sourceParameters");
                boolean hasDate = false;
                if (parameters.isArray()) for (var parameter : parameters) if ("BUSINESS_DATE".equals(parameter.path("type").asText())) hasDate = true;
                if (!hasDate) throw new IllegalArgumentException("Every task must use business date for backfill");
            } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid task configuration"); }
        }
        List<Long> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            long id = runs.create(workflowId, version, date);
            audit.success(authentication, "workflow.backfill", "run", id);
            ids.add(id);
        }
        var result = List.copyOf(ids);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (long id : result) {
                    try { dispatcher.dispatch(id); }
                    catch (RuntimeException ignored) { /* Dispatcher marks rejected runs interrupted. */ }
                }
            }
        });
        return result;
    }
    @Transactional
    public void retry(long id, Authentication authentication) {
        runs.retryFailed(id);
        audit.success(authentication, "workflow.retry", "run", id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { dispatcher.dispatch(id); }
        });
    }
    @Transactional
    public long start(long workflowId, int version, LocalDate date, Authentication authentication) {
        long id = runs.create(workflowId, version, date);
        audit.success(authentication, "workflow.run", "run", id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { dispatcher.dispatch(id); }
        });
        return id;
    }
}
