package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/** Runs only pre-authorized ORCH definitions. Connections are resolved by a trusted provider. */
public final class CamelTaskRunner implements WorkflowWorker.TaskRunner, AutoCloseable {
    @FunctionalInterface public interface DataSourceProvider { DataSource get(long sourceId) throws Exception; }
    public static final int DEFAULT_MAX_ARTIFACT_ROWS = 50000;

    private final DataSourceProvider provider;
    private final SqlTaskExecutor sql;
    private final OrchTaskExecutor orch;
    private final WatermarkStore watermarks;
    private final int maxArtifactRows;
    private final ObjectMapper mapper = new ObjectMapper();

    public CamelTaskRunner(DataSourceProvider provider) throws Exception { this(provider, null, new HttpTargetGuard(""), DEFAULT_MAX_ARTIFACT_ROWS); }
    public CamelTaskRunner(DataSourceProvider provider, WatermarkStore watermarks, HttpTargetGuard guard, int maxArtifactRows) throws Exception {
        this.provider = provider;
        this.watermarks = watermarks;
        this.maxArtifactRows = maxArtifactRows;
        sql = new SqlTaskExecutor();
        try { orch = new OrchTaskExecutor(sql, guard); } catch (RuntimeException e) { sql.close(); throw e; }
    }

    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate) throws Exception { execute(task, businessDate, new RunCancelContext(), ExecutionSinks.NOOP); }
    public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel) throws Exception { execute(task, businessDate, cancel, ExecutionSinks.NOOP); }
    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks) throws Exception {
        execute(task, new OrchTaskExecutor.Context(task.taskId(), null, businessDate, Map.of(), false), cancel, sinks, null, 0, maxArtifactRows);
    }

    /** Full execution entry: run context, per-node state sink, dry-run preview limit and artifact cap. */
    public OrchTaskExecutor.Outcome execute(TaskVersionStore.Version task, OrchTaskExecutor.Context ctx, RunCancelContext cancel, ExecutionSinks sinks,
                        OrchTaskExecutor.NodeSink nodeSink, int previewLimit, int maxRows) throws Exception {
        JsonNode config = mapper.readTree(task.configuration());
        if (config == null || !config.isObject() || ctx.businessDate() == null) throw new IllegalArgumentException("Invalid task configuration");
        if (!"ORCH".equals(task.type())) throw new IllegalArgumentException("Unsupported task type");
        var definition = OrchDefinition.parse(config);
        var noop = new OrchTaskExecutor.NodeSink() {
            @Override public void start(String nodeKey, int attempt) {}
            @Override public void success(String nodeKey, int attempt, long rowsIn, long rowsOut, long durationMs) {}
            @Override public void failure(String nodeKey, int attempt, String error, long durationMs) {}
            @Override public void skipped(String nodeKey) {}
        };
        return orch.execute(definition, provider, ctx, cancel, sinks, nodeSink == null ? noop : nodeSink, previewLimit, maxRows > 0 ? maxRows : maxArtifactRows, watermarks);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseVariables(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) return Map.of();
        try { return new ObjectMapper().readValue(parametersJson, LinkedHashMap.class); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("运行参数解析失败"); }
    }

    @Override public void close() { sql.close(); }
}