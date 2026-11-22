package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * DAG executor: topological per-node execution with bounded in-memory artifacts, node-level retry,
 * conditional branching, explicit UNION merge, incremental watermarks and dry-run preview.
 * DATA edges carry row batches; CONTROL edges carry completion dependencies only.
 */
public final class OrchTaskExecutor {
    public record NodeState(String nodeKey, String state, long rowsIn, long rowsOut, long durationMs, int attempt, String error) {}
    public interface NodeSink {
        void start(String nodeKey, int attempt);
        void success(String nodeKey, int attempt, long rowsIn, long rowsOut, long durationMs);
        void failure(String nodeKey, int attempt, String error, long durationMs);
        void skipped(String nodeKey);
    }
    public record Preview(List<String> columns, List<List<Object>> rows) {}
    public record Outcome(List<NodeState> states, Map<String, Preview> previews) {}
    public record Context(long taskId, Long runId, LocalDate businessDate, Map<String, Object> variables, boolean dryRun) {}

    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_$]*";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SqlTaskExecutor sql;
    private final HttpSourceFetcher http;
    private final HttpTargetGuard guard;

    public OrchTaskExecutor(SqlTaskExecutor sql, HttpTargetGuard guard) { this.sql = sql; this.http = new HttpSourceFetcher(guard); this.guard = guard; }

    private record NodeOutput(Map<String, List<Map<String, Object>>> ports, List<String> columns, long rowsIn, long rowsOut) {
        static NodeOutput single(List<Map<String, Object>> rows, List<String> columns, long rowsIn) {
            return new NodeOutput(Map.of("out", rows), columns, rowsIn, rows.size());
        }
        static NodeOutput terminal(long rowsIn, long rowsOut) { return new NodeOutput(Map.of(), List.of(), rowsIn, rowsOut); }
    }

    public Outcome execute(OrchDefinition def, CamelTaskRunner.DataSourceProvider provider, Context ctx, RunCancelContext cancel,
                           ExecutionSinks sinks, NodeSink nodeSink, int previewLimit, int maxArtifactRows, WatermarkStore watermarks) throws Exception {
        var order = def.topoOrder();
        if (nodeSink == null) nodeSink = new NodeSink() {
            @Override public void start(String nodeKey, int attempt) {}
            @Override public void success(String nodeKey, int attempt, long rowsIn, long rowsOut, long durationMs) {}
            @Override public void failure(String nodeKey, int attempt, String error, long durationMs) {}
            @Override public void skipped(String nodeKey) {}
        };
        var nodes = new LinkedHashMap<String, OrchDefinition.Node>();
        for (var n : def.nodes) nodes.put(n.id(), n);
        var artifacts = new LinkedHashMap<String, List<Map<String, Object>>>();
        var columns = new LinkedHashMap<String, List<String>>();
        var states = new LinkedHashMap<String, String>();
        var attempts = new LinkedHashMap<String, Integer>();
        var durations = new LinkedHashMap<String, Long>();
        var counts = new LinkedHashMap<String, long[]>();
        var errors = new LinkedHashMap<String, String>();
        var previews = new LinkedHashMap<String, Preview>();
        var watermarkMax = new LinkedHashMap<String, String[]>();
        int limit = previewLimit > 0 ? Math.min(previewLimit, 1000) : 0;
        var values = bindVariables(def, ctx, watermarks);
        var types = variableTypes(def);
        var closeables = new ArrayList<AutoCloseable>();
        String currentKey = null;
        try {
            for (var entry : order.entrySet()) {
                String key = entry.getKey();
                currentKey = key;
                var node = nodes.get(key);
                var component = OrchComponents.find(node.component()).orElseThrow(() -> new IllegalArgumentException("未知组件: " + node.component()));
                if (!component.enabled()) throw new IllegalArgumentException("组件即将支持，不可执行: " + node.component());
                // dependency gating: skipped/failed upstream or disabled node propagates SKIPPED
                boolean skip = !node.enabled();
                for (var e : def.edges) {
                    if (!e.to().equals(key)) continue;
                    String up = states.get(e.from());
                    if ("SKIPPED".equals(up) || "FAILED".equals(up)) skip = true;
                    if ("CONTROL".equals(e.kind()) && !"SUCCEEDED".equals(up) && !"SKIPPED".equals(up)) skip = true;
                }
                if (skip) {
                    states.put(key, "SKIPPED"); attempts.put(key, 0); durations.put(key, 0L); counts.put(key, new long[]{0, 0});
                    nodeSink.skipped(key);
                    sinks.logFor(key).log("INFO", "节点 " + key + " (" + node.name() + ") 跳过");
                    continue;
                }
                var retry = node.retry();
                int maxAttempts = retry.enabled() ? Math.max(1, retry.maxAttempts()) : 1;
                NodeOutput output = null;
                Exception lastError = null;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                    nodeSink.start(key, attempt);
                    states.put(key, "RUNNING"); attempts.put(key, attempt);
                    sinks.logFor(key).log("INFO", "节点 " + key + " (" + node.name() + ") 开始" + (attempt > 1 ? "，第 " + attempt + " 次尝试" : ""));
                    long started = System.nanoTime();
                    try {
                        output = executeNode(node, component, def, provider, ctx, values, types, artifacts, columns, cancel, sinks, closeables, maxArtifactRows, watermarkMax, watermarks);
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        durations.put(key, elapsed);
                        counts.put(key, new long[]{output.rowsIn(), output.rowsOut()});
                        states.put(key, "SUCCEEDED");
                        nodeSink.success(key, attempt, output.rowsIn(), output.rowsOut(), elapsed);
                        sinks.logFor(key).log("INFO", "节点 " + key + " 完成: 入 " + output.rowsIn() + " 出 " + output.rowsOut() + "，耗时 " + elapsed + " ms");
                        lastError = null;
                        break;
                    } catch (Exception ex) {
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        lastError = ex;
                        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                        nodeSink.failure(key, attempt, message, elapsed);
                        if (cancel.isCancelled() || attempt == maxAttempts) {
                            durations.put(key, elapsed); counts.put(key, new long[]{0, 0}); errors.put(key, message);
                            states.put(key, "FAILED");
                            throw ex;
                        }
                        sinks.logFor(key).log("WARN", "节点 " + key + " 第 " + attempt + " 次尝试失败: " + message + "；重试可能重放已提交数据（至少一次语义）");
                        sleepBackoff(retry, attempt, cancel);
                    }
                }
                if (lastError != null) throw lastError;
                if (output != null) {
                    for (var port : output.ports().entrySet()) artifacts.put(key + "#" + port.getKey(), port.getValue());
                    columns.put(key, output.columns());
                    if (limit > 0) {
                        List<Map<String, Object>> sample = output.ports().isEmpty()
                                ? inputRows(def, key, artifacts)
                                : output.ports().values().iterator().next();
                        var cols = output.ports().isEmpty() ? upstreamColumns(def, key, columns) : output.columns();
                        var collected = new ArrayList<List<Object>>();
                        for (int i = 0; i < Math.min(limit, sample.size()); i++) {
                            var row = sample.get(i);
                            collected.add(cols.stream().map(row::get).toList());
                        }
                        previews.put(key, new Preview(List.copyOf(cols), collected));
                    }
                }
            }
            // watermarks advance only after the whole run succeeded
            if (!ctx.dryRun() && ctx.runId() != null && watermarks != null) {
                for (var wm : watermarkMax.entrySet()) {
                    watermarks.commit(ctx.taskId(), wm.getKey(), wm.getValue()[0], wm.getValue()[1], ctx.runId());
                    sinks.log().log("INFO", "节点 " + wm.getKey() + " 水位推进至 " + wm.getValue()[0]);
                }
            }
            var result = new ArrayList<NodeState>();
            for (var n : def.nodes) {
                long[] c = counts.getOrDefault(n.id(), new long[]{0, 0});
                result.add(new NodeState(n.id(), states.getOrDefault(n.id(), "PENDING"), c[0], c[1],
                        durations.getOrDefault(n.id(), 0L), attempts.getOrDefault(n.id(), 0), errors.get(n.id())));
            }
            if (!ctx.dryRun()) sinks.stats().accept(mapper.writeValueAsString(result));
            return new Outcome(List.copyOf(result), previews);
        } catch (Exception ex) {
            try { sinks.log().log("ERROR", "节点 " + (currentKey == null ? "编排" : currentKey) + " 处理失败: " + ex.getMessage()); } catch (Exception ignored) { }
            throw ex;
        } finally {
            for (int i = closeables.size() - 1; i >= 0; i--) try { closeables.get(i).close(); } catch (Exception ignored) { }
        }
    }

    private void sleepBackoff(OrchDefinition.RetryPolicy retry, int attempt, RunCancelContext cancel) throws InterruptedException {
        long interval = retry.intervalSeconds();
        if ("EXPONENTIAL".equals(retry.backoff())) interval = (long) (interval * Math.pow(2, attempt - 1));
        interval = Math.min(interval, retry.maxIntervalSeconds());
        long deadline = System.currentTimeMillis() + interval * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (cancel.isCancelled()) throw new InterruptedException("手动停止");
            Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
        }
    }

    private Map<String, Object> bindVariables(OrchDefinition def, Context ctx, WatermarkStore watermarks) {
        var values = new LinkedHashMap<String, Object>();
        for (var v : def.variables) {
            if ("WATERMARK".equals(v.valueSource())) {
                String value = null;
                for (var n : def.nodes) {
                    if (n.config().path("watermarkVariable").asText("").equals(v.name())) {
                        value = watermarks == null ? null : watermarks.find(ctx.taskId(), n.id()).map(WatermarkStore.Watermark::value).orElse(null);
                        break;
                    }
                }
                if (value == null && v.defaultValue() != null && !v.defaultValue().isNull()) value = v.defaultValue().asText();
                if (value == null) value = "1970-01-01T00:00:00";
                values.put(v.name(), value);
            } else {
                Object raw = ctx.variables().get(v.name());
                if (raw == null && v.defaultValue() != null && !v.defaultValue().isNull()) raw = mapper.convertValue(v.defaultValue(), Object.class);
                if (raw == null && v.required()) throw new IllegalArgumentException("缺少必填变量: " + v.name());
                values.put(v.name(), raw);
            }
        }
        values.put(OrchDefinition.BUILTIN_BUSINESS_DATE, ctx.businessDate().toString());
        String tie = null;
        for (var n : def.nodes) {
            if (n.config().path("incremental").asBoolean(false)) {
                tie = watermarks == null ? null : watermarks.find(ctx.taskId(), n.id()).map(WatermarkStore.Watermark::tieBreaker).orElse(null);
                break;
            }
        }
        values.put("last_tie_breaker", tie);
        return values;
    }

    private Map<String, String> variableTypes(OrchDefinition def) {
        var types = new LinkedHashMap<String, String>();
        for (var v : def.variables) types.put(v.name(), v.type());
        types.put(OrchDefinition.BUILTIN_BUSINESS_DATE, "DATE");
        types.put("last_tie_breaker", "STRING");
        return types;
    }

    private List<Map<String, Object>> inputRows(OrchDefinition def, String key, Map<String, List<Map<String, Object>>> artifacts) {
        for (var e : def.edges) if (e.to().equals(key) && "DATA".equals(e.kind()))
            return artifacts.getOrDefault(e.from() + "#" + e.fromPort(), List.of());
        return List.of();
    }
    private List<String> upstreamColumns(OrchDefinition def, String key, Map<String, List<String>> columns) {
        for (var e : def.edges) if (e.to().equals(key) && "DATA".equals(e.kind()))
            return columns.getOrDefault(e.from(), List.of());
        return List.of();
    }

    private NodeOutput executeNode(OrchDefinition.Node node, OrchComponents.Component component, OrchDefinition def,
                                   CamelTaskRunner.DataSourceProvider provider, Context ctx, Map<String, Object> values, Map<String, String> types,
                                   Map<String, List<Map<String, Object>>> artifacts, Map<String, List<String>> columns,
                                   RunCancelContext cancel, ExecutionSinks sinks, List<AutoCloseable> closeables,
                                   int maxRows, Map<String, String[]> watermarkMax, WatermarkStore watermarks) throws Exception {
        JsonNode cfg = node.config();
        long deadline = System.currentTimeMillis() + node.timeoutSeconds() * 1000L;
        switch (component.type()) {
            case "source.mysql", "source.postgresql", "source.oracle", "transform.sql" -> {
                boolean isSqlNode = "transform.sql".equals(component.type());
                boolean dml = isSqlNode && "DML".equals(cfg.path("sqlMode").asText("SELECT"));
                String rawSql;
                if (!isSqlNode && "TABLE".equals(cfg.path("readMode").asText("TABLE"))) rawSql = "SELECT * FROM " + cfg.path("table").textValue();
                else rawSql = cfg.path("sql").textValue();
                var parsed = SqlParameters.parse(rawSql);
                if (dml) {
                    var bound = new ArrayList<Object>();
                    for (String name : parsed.names()) {
                        if (!values.containsKey(name)) throw new IllegalArgumentException("缺少参数值: " + name);
                        bound.add(SqlParameters.convert(types.get(name), values.get(name), name));
                    }
                    if (ctx.dryRun()) {
                        sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 试运行：跳过 DML 写入");
                        return NodeOutput.terminal(0, 0);
                    }
                    DataSource ds = provider.get(cfg.path("sourceId").asLong());
                    int affected = sql.execute(ds, parsed.jdbcSql(), bound, cancel);
                    return NodeOutput.terminal(0, affected);
                }
                DataSource ds = provider.get(cfg.path("sourceId").asLong());
                Connection connection = ds.getConnection();
                closeables.add(connection);
                connection.setReadOnly(true);
                PreparedStatement statement = connection.prepareStatement(parsed.jdbcSql());
                closeables.add(statement);
                cancel.register(statement);
                statement.setFetchSize(cfg.path("fetchSize").asInt(500));
                statement.setQueryTimeout(Math.min(node.timeoutSeconds(), 3600));
                SqlParameters.bind(statement, parsed, values, types);
                ResultSet rows = statement.executeQuery();
                closeables.add(rows);
                var cols = new ArrayList<String>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) cols.add(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT));
                var out = new ArrayList<Map<String, Object>>();
                boolean incremental = !isSqlNode && cfg.path("incremental").asBoolean(false);
                String wmCol = incremental ? cfg.path("watermarkColumn").asText("").toLowerCase(Locale.ROOT) : null;
                String tieCol = incremental ? cfg.path("tieBreakerColumn").asText("").toLowerCase(Locale.ROOT) : null;
                long read = 0;
                while (rows.next()) {
                    checkDeadline(deadline, node, cancel);
                    if (out.size() >= maxRows) throw new IllegalArgumentException("节点 " + node.id() + " 输出超过内存交接上限 " + maxRows + " 行，请缩小读取范围");
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < cols.size(); i++) row.put(cols.get(i), convert(rows.getObject(i + 1)));
                    out.add(row); read++;
                    if (incremental && wmCol != null && row.containsKey(wmCol)) {
                        String wm = row.get(wmCol) == null ? null : String.valueOf(row.get(wmCol));
                        String tie = tieCol != null && row.containsKey(tieCol) && row.get(tieCol) != null ? String.valueOf(row.get(tieCol)) : null;
                        String[] max = watermarkMax.get(node.id());
                        if (wm != null && (max == null || WatermarkStore.greater(wm, tie, max[0], max[1])))
                            watermarkMax.put(node.id(), new String[]{wm, tie});
                    }
                }
                return NodeOutput.single(out, cols, read);
            }
            case "source.http" -> {
                if (ctx.dryRun()) sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 试运行：HTTP 来源仍会真实读取（只读）");
                var rows = http.fetch(cfg, cancel);
                if (rows.size() > maxRows) throw new IllegalArgumentException("节点 " + node.id() + " 输出超过内存交接上限 " + maxRows + " 行");
                var cols = new ArrayList<String>();
                var seen = new LinkedHashSet<String>();
                for (var row : rows) for (String k : row.keySet()) if (seen.add(k.toLowerCase(Locale.ROOT))) cols.add(k.toLowerCase(Locale.ROOT));
                var normalized = new ArrayList<Map<String, Object>>();
                for (var row : rows) {
                    var n = new LinkedHashMap<String, Object>();
                    for (var e : row.entrySet()) n.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                    normalized.add(n);
                }
                return NodeOutput.single(normalized, cols, normalized.size());
            }
            case "transform.field-map" -> {
                var input = inputRows(def, node.id(), artifacts);
                var mappings = new ArrayList<JsonNode>();
                for (JsonNode m : cfg.path("mappings")) mappings.add(m);
                var cols = mappings.stream().map(m -> m.path("to").textValue().toLowerCase(Locale.ROOT)).toList();
                var out = new ArrayList<Map<String, Object>>();
                long in = 0;
                for (var row : input) {
                    checkDeadline(deadline, node, cancel);
                    in++;
                    var mapped = new LinkedHashMap<String, Object>();
                    boolean skipRow = false;
                    for (JsonNode m : mappings) {
                        String from = m.path("from").textValue().toLowerCase(Locale.ROOT);
                        String to = m.path("to").textValue().toLowerCase(Locale.ROOT);
                        Object value = row.get(from);
                        if (value == null && m.has("defaultValue") && !m.path("defaultValue").isNull()) value = m.path("defaultValue").asText();
                        String targetType = m.path("targetType").asText("STRING");
                        String onError = m.path("onError").asText("FAIL");
                        try {
                            mapped.put(to, convertType(value, targetType));
                        } catch (RuntimeException ex) {
                            if ("FAIL".equals(onError)) throw new IllegalArgumentException("节点 " + node.id() + " 字段 " + from + " 转换失败: " + ex.getMessage());
                            if ("SKIP_ROW".equals(onError)) { skipRow = true; break; }
                            mapped.put(to, null);
                        }
                    }
                    if (!skipRow) {
                        if (out.size() >= maxRows) throw new IllegalArgumentException("节点 " + node.id() + " 输出超过内存交接上限 " + maxRows + " 行");
                        out.add(mapped);
                    }
                }
                return NodeOutput.single(out, cols, in);
            }
            case "transform.filter" -> {
                var expr = FlowExpression.parse(cfg.path("expression").textValue());
                var input = inputRows(def, node.id(), artifacts);
                var out = new ArrayList<Map<String, Object>>();
                for (var row : input) {
                    checkDeadline(deadline, node, cancel);
                    if (Boolean.TRUE.equals(expr.eval(row))) out.add(row);
                }
                return NodeOutput.single(out, upstreamColumns(def, node.id(), columns), input.size());
            }
            case "transform.mask" -> {
                String field = cfg.path("field").textValue().toLowerCase(Locale.ROOT);
                var input = inputRows(def, node.id(), artifacts);
                var out = new ArrayList<Map<String, Object>>();
                for (var row : input) {
                    checkDeadline(deadline, node, cancel);
                    if (!row.containsKey(field)) throw new IllegalArgumentException("节点 " + node.id() + " 上游缺少字段 " + field);
                    var masked = new LinkedHashMap<>(row);
                    masked.put(field, "****");
                    out.add(masked);
                }
                return NodeOutput.single(out, upstreamColumns(def, node.id(), columns), input.size());
            }
            case "transform.calc" -> {
                String field = cfg.path("field").textValue().toLowerCase(Locale.ROOT);
                var expr = FlowExpression.parse(cfg.path("expression").textValue());
                var input = inputRows(def, node.id(), artifacts);
                var cols = new ArrayList<>(upstreamColumns(def, node.id(), columns));
                if (cols.contains(field)) throw new IllegalArgumentException("节点 " + node.id() + " 计算字段已存在 " + field);
                cols.add(field);
                var out = new ArrayList<Map<String, Object>>();
                for (var row : input) {
                    checkDeadline(deadline, node, cancel);
                    var extended = new LinkedHashMap<>(row);
                    extended.put(field, expr.eval(row));
                    out.add(extended);
                }
                return NodeOutput.single(out, cols, input.size());
            }
            case "transform.clean" -> {
                var input = new ArrayList<>(inputRows(def, node.id(), artifacts));
                long in = input.size();
                long trimmed = 0, droppedNull = 0, deduped = 0, replaced = 0;
                for (JsonNode op : cfg.path("operations")) {
                    String kind = op.path("op").asText("");
                    var fields = new ArrayList<String>();
                    for (JsonNode f : op.path("fields")) fields.add(f.textValue().toLowerCase(Locale.ROOT));
                    switch (kind) {
                        case "TRIM" -> {
                            for (var row : input) for (String f : fields) {
                                if (row.get(f) instanceof String s) { String t = s.trim(); if (!t.equals(s)) { row.put(f, t); trimmed++; } }
                            }
                        }
                        case "DROP_NULL" -> {
                            var kept = new ArrayList<Map<String, Object>>();
                            for (var row : input) {
                                boolean anyNull = fields.stream().anyMatch(f -> row.get(f) == null);
                                if (anyNull) droppedNull++; else kept.add(row);
                            }
                            input = kept;
                        }
                        case "DEDUP" -> {
                            var seen = new HashSet<List<Object>>();
                            var kept = new ArrayList<Map<String, Object>>();
                            for (var row : input) {
                                var key = fields.stream().map(row::get).toList();
                                if (seen.add(key)) kept.add(row); else deduped++;
                            }
                            input = kept;
                        }
                        case "REGEX_REPLACE" -> {
                            String field = op.path("field").textValue().toLowerCase(Locale.ROOT);
                            var pattern = java.util.regex.Pattern.compile(op.path("pattern").textValue());
                            String replacement = op.path("replacement").asText("");
                            for (var row : input) {
                                if (row.get(field) instanceof String s) {
                                    String t = pattern.matcher(s).replaceAll(replacement);
                                    if (!t.equals(s)) { row.put(field, t); replaced++; }
                                }
                            }
                        }
                        default -> throw new IllegalArgumentException("未知清洗步骤: " + kind);
                    }
                    checkDeadline(deadline, node, cancel);
                }
                sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 清洗统计: trim=" + trimmed + " 去空行=" + droppedNull + " 去重=" + deduped + " 正则替换=" + replaced);
                if (input.size() > maxRows) throw new IllegalArgumentException("节点 " + node.id() + " 输出超过内存交接上限 " + maxRows + " 行");
                return NodeOutput.single(input, upstreamColumns(def, node.id(), columns), in);
            }
            case "control.branch" -> {
                var rules = new ArrayList<Map.Entry<String, FlowExpression.Expr>>();
                for (JsonNode r : cfg.path("rules")) rules.add(Map.entry(r.path("key").textValue(), FlowExpression.parse(r.path("expression").textValue())));
                String defaultKey = cfg.path("defaultKey").textValue();
                var ports = new LinkedHashMap<String, List<Map<String, Object>>>();
                for (var r : rules) ports.put(r.getKey(), new ArrayList<>());
                ports.put(defaultKey, new ArrayList<>());
                var input = inputRows(def, node.id(), artifacts);
                for (var row : input) {
                    checkDeadline(deadline, node, cancel);
                    String target = defaultKey;
                    for (var r : rules) if (Boolean.TRUE.equals(r.getValue().eval(row))) { target = r.getKey(); break; }
                    var bucket = ports.get(target);
                    if (bucket.size() >= maxRows) throw new IllegalArgumentException("节点 " + node.id() + " 分支 " + target + " 超过内存交接上限 " + maxRows + " 行");
                    bucket.add(row);
                }
                for (var p : ports.entrySet()) sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 分支 " + p.getKey() + ": " + p.getValue().size() + " 行");
                return new NodeOutput(ports, upstreamColumns(def, node.id(), columns), input.size(), input.size());
            }
            case "control.merge" -> {
                var cols = new ArrayList<String>();
                var seen = new LinkedHashSet<String>();
                var out = new ArrayList<Map<String, Object>>();
                long in = 0;
                for (var e : def.edges) {
                    if (!e.to().equals(node.id()) || !"DATA".equals(e.kind())) continue;
                    for (String c : columns.getOrDefault(e.from(), List.of())) if (seen.add(c)) cols.add(c);
                    var rows = artifacts.getOrDefault(e.from() + "#" + e.fromPort(), List.of());
                    in += rows.size();
                    out.addAll(rows);
                }
                if (out.size() > maxRows) throw new IllegalArgumentException("节点 " + node.id() + " 汇聚结果超过内存交接上限 " + maxRows + " 行");
                var normalized = new ArrayList<Map<String, Object>>();
                for (var row : out) {
                    var n = new LinkedHashMap<String, Object>();
                    for (String c : cols) n.put(c, row.get(c));
                    normalized.add(n);
                }
                return NodeOutput.single(normalized, cols, in);
            }
            case "sink.mysql", "sink.postgresql", "sink.oracle" -> {
                var input = inputRows(def, node.id(), artifacts);
                var cols = upstreamColumns(def, node.id(), columns);
                if (ctx.dryRun()) {
                    sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 试运行：跳过目标写入（" + input.size() + " 行待写）");
                    return NodeOutput.terminal(input.size(), 0);
                }
                long written = writeJdbcSink(node, provider, cols, input.iterator(), cancel, sinks, deadline);
                return NodeOutput.terminal(input.size(), written);
            }
            case "sink.http" -> {
                var input = inputRows(def, node.id(), artifacts);
                if (ctx.dryRun()) {
                    sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 试运行：跳过 HTTP 推送（" + input.size() + " 行待推）");
                    return NodeOutput.terminal(input.size(), 0);
                }
                long sent = pushHttp(node, cfg, input, ctx, cancel, deadline);
                return NodeOutput.terminal(input.size(), sent);
            }
            default -> throw new IllegalArgumentException("组件不可执行: " + component.type());
        }
    }

    private void checkDeadline(long deadline, OrchDefinition.Node node, RunCancelContext cancel) throws InterruptedException {
        if (cancel.isCancelled()) throw new InterruptedException("手动停止");
        if (System.currentTimeMillis() > deadline) throw new IllegalStateException("节点 " + node.id() + " 执行超时");
    }

    private long pushHttp(OrchDefinition.Node node, JsonNode cfg, List<Map<String, Object>> rows, Context ctx, RunCancelContext cancel, long deadline) throws Exception {
        String url = cfg.path("url").textValue();
        String method = cfg.path("method").asText("POST");
        int batchSize = cfg.path("batchSize").asInt(100);
        int timeout = cfg.path("timeoutSeconds").asInt(30);
        String template = cfg.path("bodyTemplate").asText("");
        String idempotencyHeader = cfg.path("idempotencyKeyHeader").asText("");
        var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(10)).build();
        long sent = 0;
        int batchNo = 0;
        for (int i = 0; i < rows.size(); i += batchSize) {
            checkDeadline(deadline, node, cancel);
            var batch = rows.subList(i, Math.min(rows.size(), i + batchSize));
            String rowsJson = mapper.writeValueAsString(batch);
            String body = template.isBlank() ? rowsJson : template.replace("{{rows}}", rowsJson);
            mapper.readTree(body); // template must produce valid JSON
            var uri = java.net.URI.create(url);
            guard.check(uri);
            var builder = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json");
            for (JsonNode h : cfg.path("headers")) {
                String name = h.path("name").asText("");
                if (!name.isBlank()) builder.header(name, h.path("value").asText(""));
            }
            if (!idempotencyHeader.isBlank()) builder.header(idempotencyHeader, ctx.taskId() + "-" + node.id() + "-" + (ctx.runId() == null ? "dry" : ctx.runId()) + "-" + batchNo);
            builder.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
            var response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("节点 " + node.id() + " HTTP 推送返回状态 " + response.statusCode());
            sent += batch.size(); batchNo++;
        }
        return sent;
    }

    private long writeJdbcSink(OrchDefinition.Node node, CamelTaskRunner.DataSourceProvider provider, List<String> cols,
                               Iterator<Map<String, Object>> source, RunCancelContext cancel, ExecutionSinks sinks, long deadline) throws Exception {
        JsonNode cfg = node.config();
        DataSource ds = provider.get(cfg.path("sourceId").asLong());
        String table = cfg.path("table").textValue();
        String mode = cfg.path("writeMode").asText("APPEND");
        int batchSize = cfg.path("batchSize").asInt(500);
        var keys = new ArrayList<String>();
        for (JsonNode k : cfg.path("keyColumns")) keys.add(k.textValue().toLowerCase(Locale.ROOT));
        long written = 0;
        try (Connection target = ds.getConnection()) {
            target.setAutoCommit(false);
            try {
                var targetColumns = new HashSet<String>();
                var meta = target.getMetaData();
                String[] parts = table.split("\\.");
                boolean mysql = meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
                String catalog = mysql ? (parts.length == 2 ? parts[0] : target.getCatalog()) : null;
                String schema = mysql ? null : (parts.length == 2 ? parts[0] : target.getSchema());
                String name = parts[parts.length - 1];
                if (meta.storesUpperCaseIdentifiers()) { name = name.toUpperCase(Locale.ROOT); if (schema != null) schema = schema.toUpperCase(Locale.ROOT); }
                else if (meta.storesLowerCaseIdentifiers()) { name = name.toLowerCase(Locale.ROOT); if (schema != null) schema = schema.toLowerCase(Locale.ROOT); }
                try (ResultSet rc = meta.getColumns(catalog, schema, name, null)) {
                    while (rc.next()) targetColumns.add(rc.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                if (targetColumns.isEmpty()) try (ResultSet rc = meta.getColumns(catalog, schema, name.toUpperCase(Locale.ROOT), null)) {
                    while (rc.next()) targetColumns.add(rc.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                for (String col : cols) {
                    if (!col.matches(IDENT)) throw new IllegalArgumentException("节点 " + node.id() + " 列名非法 " + col);
                    if (!targetColumns.contains(col)) throw new IllegalArgumentException("节点 " + node.id() + " 目标表缺少字段 " + col);
                }
                if ("UPSERT".equals(mode)) DatabaseSyncExecutor.verifyUniqueKey(target, table, keys);
                String placeholders = String.join(",", Collections.nCopies(cols.size(), "?"));
                String assignments = null;
                String where = null;
                if ("UPSERT".equals(mode)) {
                    var nonKeys = cols.stream().filter(c -> !keys.contains(c)).toList();
                    assignments = nonKeys.isEmpty() ? keys.get(0) + "=" + keys.get(0) : nonKeys.stream().map(c -> c + "=?").collect(java.util.stream.Collectors.joining(","));
                    where = keys.stream().map(k -> k + "=?").collect(java.util.stream.Collectors.joining(" AND "));
                }
                try (PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + placeholders + ")");
                     PreparedStatement update = "UPSERT".equals(mode) ? target.prepareStatement("UPDATE " + table + " SET " + assignments + " WHERE " + where) : null) {
                    cancel.register(insert);
                    insert.setQueryTimeout(Math.min(node.timeoutSeconds(), 3600));
                    if (update != null) { update.setQueryTimeout(Math.min(node.timeoutSeconds(), 3600)); cancel.register(update); }
                    int pending = 0;
                    int batchNo = 0;
                    while (source.hasNext()) {
                        checkDeadline(deadline, node, cancel);
                        var row = source.next();
                        if ("UPSERT".equals(mode)) {
                            int pos = 1;
                            for (String c : cols) if (!keys.contains(c)) update.setObject(pos++, row.get(c));
                            for (String k : keys) {
                                if (!row.containsKey(k)) throw new IllegalArgumentException("节点 " + node.id() + " 上游缺少字段 " + k);
                                Object v = row.get(k);
                                if (v == null) throw new IllegalArgumentException("节点 " + node.id() + " 主键值为空 " + k);
                                update.setObject(pos++, v);
                            }
                            int changed = update.executeUpdate();
                            if (changed > 1) throw new IllegalStateException("节点 " + node.id() + " 主键匹配多行");
                            if (changed == 0) { bind(insert, cols, row); insert.executeUpdate(); }
                            written++;
                        } else {
                            bind(insert, cols, row);
                            insert.addBatch();
                            pending++;
                            if (pending == batchSize) { insert.executeBatch(); written += pending; pending = 0; if (++batchNo % 10 == 0) sinks.logFor(node.id()).log("INFO", "节点 " + node.id() + " 已写入 " + written + " 行"); }
                        }
                    }
                    if (pending > 0) { insert.executeBatch(); written += pending; }
                    if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                    target.commit();
                }
            } catch (Exception failure) {
                try { target.rollback(); } catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
        return written;
    }

    private static void bind(PreparedStatement statement, List<String> cols, Map<String, Object> row) throws Exception {
        for (int i = 0; i < cols.size(); i++) statement.setObject(i + 1, row.get(cols.get(i)));
    }

    static Object convertType(Object value, String targetType) {
        if (value == null) return null;
        return switch (targetType) {
            case "STRING" -> value instanceof String s ? s : String.valueOf(value);
            case "INTEGER" -> new BigDecimal(String.valueOf(value)).longValueExact();
            case "DECIMAL" -> new BigDecimal(String.valueOf(value));
            case "BOOLEAN" -> {
                if (value instanceof Boolean b) yield b;
                String s = String.valueOf(value);
                if ("true".equalsIgnoreCase(s) || "1".equals(s)) yield Boolean.TRUE;
                if ("false".equalsIgnoreCase(s) || "0".equals(s)) yield Boolean.FALSE;
                throw new IllegalArgumentException("非法布尔值: " + s);
            }
            case "DATETIME" -> {
                if (value instanceof java.sql.Timestamp t) yield t;
                String s = String.valueOf(value).trim().replace(' ', 'T');
                try { yield java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(s)); }
                catch (java.time.format.DateTimeParseException ex) { yield java.sql.Timestamp.from(java.time.OffsetDateTime.parse(s).toInstant()); }
            }
            case "JSON" -> value instanceof String s ? s : new ObjectMapper().valueToTree(value).toString();
            default -> throw new IllegalArgumentException("不支持的目标类型: " + targetType);
        };
    }

    static Object convert(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp || value instanceof java.sql.Time) return value.toString();
        if (value instanceof java.time.LocalDate || value instanceof java.time.LocalDateTime || value instanceof java.time.LocalTime
                || value instanceof java.time.OffsetDateTime || value instanceof java.time.OffsetTime || value instanceof java.time.Instant) return value.toString();
        if (value instanceof byte[]) return value;
        throw new IllegalArgumentException("不支持的字段类型: " + value.getClass().getSimpleName());
    }
}