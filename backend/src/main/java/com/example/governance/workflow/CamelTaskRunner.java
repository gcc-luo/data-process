package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Runs only pre-authorized task definitions. Connections are resolved by a trusted provider. */
public final class CamelTaskRunner implements WorkflowWorker.TaskRunner, AutoCloseable {
    @FunctionalInterface public interface DataSourceProvider { DataSource get(long sourceId) throws Exception; }
    private final DataSourceProvider provider;
    private final SqlTaskExecutor sql;
    private final DatabaseSyncExecutor sync;
    private final FlowTaskExecutor flow = new FlowTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    public CamelTaskRunner(DataSourceProvider provider) throws Exception {
        this.provider = provider;
        sql = new SqlTaskExecutor();
        try { sync = new DatabaseSyncExecutor(); } catch (Exception e) { sql.close(); throw e; }
    }
    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate) throws Exception { execute(task, businessDate, new RunCancelContext(), ExecutionSinks.NOOP); }
    public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel) throws Exception { execute(task, businessDate, cancel, ExecutionSinks.NOOP); }
    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks) throws Exception {
        JsonNode config = mapper.readTree(task.configuration());
        if (config == null || !config.isObject() || businessDate == null) throw new IllegalArgumentException("Invalid task configuration");
        if ("SQL".equals(task.type())) {
            long sourceId = positiveId(config, "sourceId");
            String statement = text(config, "sql");
            List<Object> values = parameters(config.path("parameters"), businessDate);
            sql.execute(provider.get(sourceId), statement, values, cancel);
        } else if ("SYNC".equals(task.type())) {
            long sourceId = positiveId(config, "sourceId"), targetId = positiveId(config, "targetId");
            JsonNode fields = config.path("targetColumns");
            if (!fields.isArray() || fields.isEmpty()) throw new IllegalArgumentException("Invalid target columns");
            List<String> columns = new ArrayList<>();
            for (JsonNode field : fields) { if (!field.isTextual()) throw new IllegalArgumentException("Invalid target column"); columns.add(field.textValue()); }
            int batchSize = config.path("batchSize").asInt(500);
            List<Object> values = config.has("sourceParameters") ? parameters(config.path("sourceParameters"), businessDate) : List.of();
            String mode = config.has("writeMode") ? text(config, "writeMode") : "APPEND";
            if ("APPEND".equals(mode)) sync.append(provider.get(sourceId), provider.get(targetId), text(config, "sourceQuery"), values, text(config, "targetTable"), columns, batchSize, cancel);
            else if ("UPSERT".equals(mode)) {
                JsonNode keyNodes = config.path("keyColumns");
                if (!keyNodes.isArray() || keyNodes.isEmpty()) throw new IllegalArgumentException("Primary keys required");
                List<String> keys = new ArrayList<>();
                for (JsonNode key : keyNodes) { if (!key.isTextual()) throw new IllegalArgumentException("Invalid primary key"); keys.add(key.textValue()); }
                sync.upsert(provider.get(sourceId), provider.get(targetId), text(config, "sourceQuery"), values, text(config, "targetTable"), columns, keys, batchSize, cancel);
            } else throw new IllegalArgumentException("Unsupported write mode");
        } else if ("FLOW".equals(task.type())) {
            flow.execute(FlowGraph.parse(config), provider, businessDate, cancel, sinks, 0);
        } else throw new IllegalArgumentException("Unsupported task type");
    }
    private static List<Object> parameters(JsonNode parameters, LocalDate businessDate) {
        if (!parameters.isArray() || parameters.size() > 1000) throw new IllegalArgumentException("Invalid task parameters");
        List<Object> values = new ArrayList<>();
        for (JsonNode parameter : parameters) {
            String type = text(parameter, "type");
            if ("BUSINESS_DATE".equals(type)) values.add(Date.valueOf(businessDate));
            else if ("VALUE".equals(type)) {
                JsonNode value = parameter.get("value");
                if (value == null || value.isNull()) values.add(null);
                else if (value.isTextual()) values.add(value.textValue());
                else if (value.isBoolean()) values.add(value.booleanValue());
                else if (value.isNumber()) values.add(value.decimalValue());
                else throw new IllegalArgumentException("Invalid parameter value");
            } else throw new IllegalArgumentException("Invalid parameter type");
        }
        return values;
    }
    private static long positiveId(JsonNode config, String key) {
        JsonNode value = config.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw new IllegalArgumentException("Invalid source ID");
        return value.longValue();
    }
    private static String text(JsonNode config, String key) {
        JsonNode value = config.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Invalid task field");
        return value.textValue();
    }
    @Override public void close() { try { sync.close(); } finally { sql.close(); } }
}
