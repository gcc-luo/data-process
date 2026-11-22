package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestration definition (schemaVersion 1): nodes/edges/variables with port-aware structural
 * and per-component semantic validation. Issues carry nodeId/edgeId for UI localization.
 */
public final class OrchDefinition {
    public static final int MAX_NODES = 100;
    public static final int MAX_EDGES = 200;
    public static final int MAX_DEFINITION_CHARS = 200_000;
    public static final String IDENT = "[A-Za-z_][A-Za-z0-9_$]*";
    public static final String NODE_ID = "[A-Za-z_][A-Za-z0-9_$-]{0,63}";
    public static final String QUALIFIED = IDENT + "(\\." + IDENT + ")?";
    public static final String BUILTIN_BUSINESS_DATE = "business_date";

    public record RetryPolicy(boolean enabled, int maxAttempts, int intervalSeconds, String backoff,
                              int maxIntervalSeconds, boolean acknowledgeDuplicateRisk) {
        public static RetryPolicy none() { return new RetryPolicy(false, 1, 30, "FIXED", 300, false); }
    }
    public record Node(String id, String component, String name, String description, boolean enabled,
                       double x, double y, int timeoutSeconds, RetryPolicy retry, JsonNode config) {}
    public record Edge(String id, String from, String fromPort, String to, String toPort, String kind) {}
    public record Variable(String name, String type, String valueSource, JsonNode defaultValue, boolean required, String description) {}
    public record Issue(String code, String severity, String targetType, String targetId, String field, String message) {
        public static Issue error(String code, String targetType, String targetId, String field, String message) { return new Issue(code, "ERROR", targetType, targetId, field, message); }
        public static Issue warn(String code, String targetType, String targetId, String field, String message) { return new Issue(code, "WARNING", targetType, targetId, field, message); }
    }
    public record Layout(double zoom, double viewportX, double viewportY) {}

    public final List<Node> nodes;
    public final List<Edge> edges;
    public final List<Variable> variables;
    public final Layout layout;
    private final Map<String, Node> byId = new LinkedHashMap<>();

    private OrchDefinition(List<Node> nodes, List<Edge> edges, List<Variable> variables, Layout layout) {
        this.nodes = List.copyOf(nodes); this.edges = List.copyOf(edges); this.variables = List.copyOf(variables); this.layout = layout;
        for (Node n : nodes) byId.put(n.id(), n);
    }

    public Optional<Node> node(String id) { return Optional.ofNullable(byId.get(id)); }

    /** Shape parsing: throws IllegalArgumentException on malformed JSON structure (draft save gate). */
    public static OrchDefinition parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("编排定义必须是 JSON 对象");
        if (root.path("schemaVersion").asInt(0) != 1) throw new IllegalArgumentException("schemaVersion 必须为 1");
        JsonNode nodes = root.path("nodes"); JsonNode edges = root.path("edges");
        if (!nodes.isArray() || nodes.size() > MAX_NODES) throw new IllegalArgumentException("节点数量必须在 0–" + MAX_NODES + " 之间");
        if (!edges.isArray() || edges.size() > MAX_EDGES) throw new IllegalArgumentException("连线数量不能超过 " + MAX_EDGES);
        var nodeList = new ArrayList<Node>();
        for (JsonNode n : nodes) {
            if (!n.isObject()) throw new IllegalArgumentException("节点必须是对象");
            String id = n.path("id").asText("");
            if (!id.matches(NODE_ID)) throw new IllegalArgumentException("节点 ID 非法: " + id);
            String component = n.path("component").asText("");
            if (component.isBlank() || component.length() > 80) throw new IllegalArgumentException("节点组件类型非法: " + id);
            JsonNode config = n.path("config");
            if (!config.isObject()) throw new IllegalArgumentException("节点配置必须是对象: " + id);
            String name = n.path("name").asText("");
            if (name.length() > 80) throw new IllegalArgumentException("节点名称过长: " + id);
            String description = n.path("description").asText("");
            if (description.length() > 500) throw new IllegalArgumentException("节点描述过长: " + id);
            int timeout = n.path("timeoutSeconds").asInt(300);
            if (timeout < 1 || timeout > 86400) throw new IllegalArgumentException("节点超时时间非法: " + id);
            nodeList.add(new Node(id, component, name, description, n.path("enabled").asBoolean(true),
                    n.path("position").path("x").asDouble(0), n.path("position").path("y").asDouble(0),
                    timeout, parseRetry(n.path("retry")), config));
        }
        var edgeList = new ArrayList<Edge>();
        for (JsonNode e : edges) {
            if (!e.isObject()) throw new IllegalArgumentException("连线必须是对象");
            String id = e.path("id").asText("");
            if (id.isBlank() || id.length() > 80) throw new IllegalArgumentException("连线 ID 非法");
            String kind = e.path("kind").asText("DATA");
            if (!Set.of("DATA", "CONTROL").contains(kind)) throw new IllegalArgumentException("连线类型非法: " + id);
            edgeList.add(new Edge(id, e.path("from").asText(""), e.path("fromPort").asText("out"),
                    e.path("to").asText(""), e.path("toPort").asText("in"), kind));
        }
        var variables = new ArrayList<Variable>();
        JsonNode vars = root.path("variables");
        if (vars.isArray()) {
            if (vars.size() > 100) throw new IllegalArgumentException("变量数量不能超过 100");
            for (JsonNode v : vars) {
                variables.add(new Variable(v.path("name").asText(""), v.path("type").asText("STRING"),
                        v.path("valueSource").asText("MANUAL"), v.path("defaultValue"),
                        v.path("required").asBoolean(false), v.path("description").asText("")));
            }
        }
        JsonNode layout = root.path("layout");
        return new OrchDefinition(nodeList, edgeList, variables,
                new Layout(layout.path("zoom").asDouble(1), layout.path("viewport").path("x").asDouble(0), layout.path("viewport").path("y").asDouble(0)));
    }

    private static RetryPolicy parseRetry(JsonNode r) {
        if (r == null || r.isMissingNode() || !r.isObject()) return RetryPolicy.none();
        return new RetryPolicy(r.path("enabled").asBoolean(false), r.path("maxAttempts").asInt(1),
                r.path("intervalSeconds").asInt(30), r.path("backoff").asText("FIXED"),
                r.path("maxIntervalSeconds").asInt(300), r.path("acknowledgeDuplicateRisk").asBoolean(false));
    }

    @FunctionalInterface
    public interface DataSourceResolver { Optional<String> typeOf(long sourceId); }

    /** Full semantic validation; empty error list means publishable. */
    public List<Issue> validate(DataSourceResolver sources) {
        var issues = new ArrayList<Issue>();
        var components = new HashMap<String, OrchComponents.Component>();
        for (Node n : nodes) {
            var def = OrchComponents.find(n.component());
            if (def.isEmpty()) { issues.add(Issue.error("COMPONENT_NOT_SUPPORTED", "node", n.id(), "component", "未知组件: " + n.component())); continue; }
            var c = def.get();
            components.put(n.id(), c);
            if (!c.enabled()) issues.add(Issue.error("COMPONENT_NOT_SUPPORTED", "node", n.id(), "component", c.name() + " 即将支持，当前不可执行"));
            if (n.name().isBlank()) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "name", "节点名称必填"));
            if (byId.keySet().stream().filter(k -> k.equals(n.id())).count() > 1) issues.add(Issue.error("NODE_DUPLICATE_ID", "node", n.id(), "id", "节点 ID 重复"));
            validateRetry(n, c, issues);
            validateConfig(n, c, sources, issues);
        }
        // duplicate ids (parse keeps last; detect explicitly)
        var seenIds = new HashSet<String>();
        for (Node n : nodes) if (!seenIds.add(n.id())) issues.add(Issue.error("NODE_DUPLICATE_ID", "node", n.id(), "id", "节点 ID 重复"));

        var inData = new HashMap<String, Integer>(); var inControl = new HashMap<String, Integer>();
        var outAll = new HashMap<String, Integer>(); var outData = new HashMap<String, Integer>();
        var seenEdges = new HashSet<String>(); var edgeIds = new HashSet<String>();
        var next = new HashMap<String, List<String>>(); var reverse = new HashMap<String, List<String>>();
        for (Edge e : edges) {
            if (!edgeIds.add(e.id())) issues.add(Issue.error("EDGE_DUPLICATE_ID", "edge", e.id(), "id", "连线 ID 重复"));
            var from = byId.get(e.from()); var to = byId.get(e.to());
            if (from == null || to == null) { issues.add(Issue.error("EDGE_UNKNOWN_NODE", "edge", e.id(), null, "连线端点不存在")); continue; }
            if (e.from().equals(e.to())) { issues.add(Issue.error("EDGE_SELF_LOOP", "edge", e.id(), null, "节点不能连接自己")); continue; }
            String dupKey = e.from() + ":" + e.fromPort() + ">" + e.to() + ":" + e.toPort();
            if (!seenEdges.add(dupKey)) { issues.add(Issue.error("EDGE_DUPLICATE", "edge", e.id(), null, "重复连线")); continue; }
            var fromComp = components.get(e.from()); var toComp = components.get(e.to());
            if (fromComp == null || toComp == null) continue;
            var outPorts = OrchComponents.activeOutputPorts(fromComp, from.config());
            var outPort = outPorts.stream().filter(p -> p.id().equals(e.fromPort())).findFirst();
            var inPort = toComp.inputPorts().stream().filter(p -> p.id().equals(e.toPort())).findFirst();
            if (outPort.isEmpty()) { issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "edge", e.id(), "fromPort", "来源节点没有出口 " + e.fromPort())); continue; }
            if (inPort.isEmpty()) { issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "edge", e.id(), "toPort", "目标节点没有入口 " + e.toPort())); continue; }
            if (!e.kind().equals(inPort.get().kind())) { issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "edge", e.id(), "kind", "连线类型与目标端口不一致（数据端口不能接控制依赖，反之亦然）")); continue; }
            if ("DATA".equals(e.kind())) { inData.merge(e.to(), 1, Integer::sum); outData.merge(e.from(), 1, Integer::sum); }
            else inControl.merge(e.to(), 1, Integer::sum);
            outAll.merge(e.from(), 1, Integer::sum);
            next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            reverse.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        }
        // cycle detection over all edges
        var indeg = new HashMap<String, Integer>();
        for (Node n : nodes) indeg.put(n.id(), 0);
        for (Edge e : edges) if (byId.containsKey(e.from()) && byId.containsKey(e.to()) && !e.from().equals(e.to())) indeg.merge(e.to(), 1, Integer::sum);
        var queue = new ArrayDeque<String>();
        for (var en : indeg.entrySet()) if (en.getValue() == 0) queue.add(en.getKey());
        int visited = 0;
        while (!queue.isEmpty()) {
            String k = queue.poll(); visited++;
            for (String n : next.getOrDefault(k, List.of())) if (indeg.merge(n, -1, Integer::sum) == 0) queue.add(n);
        }
        if (visited != nodes.size()) issues.add(Issue.error("WORKFLOW_CYCLE_DETECTED", "workflow", null, null, "编排存在环，必须是有向无环图"));

        boolean hasTrigger = false;
        var terminals = new HashSet<String>();
        for (Node n : nodes) {
            var c = components.get(n.id());
            if (c == null) continue;
            int di = inData.getOrDefault(n.id(), 0);
            int ci = inControl.getOrDefault(n.id(), 0);
            int oa = outAll.getOrDefault(n.id(), 0);
            int od = outData.getOrDefault(n.id(), 0);
            boolean isSource = OrchComponents.CATEGORY_SOURCE.equals(c.category());
            boolean isSink = OrchComponents.CATEGORY_SINK.equals(c.category());
            boolean isSql = "transform.sql".equals(c.type());
            boolean dml = isSql && "DML".equals(n.config().path("sqlMode").asText("SELECT"));
            boolean isMerge = "control.merge".equals(c.type());
            boolean isBranch = "control.branch".equals(c.type());
            if (isSource || dml) hasTrigger = true;
            if (isSink || dml) terminals.add(n.id());
            if (isSource) {
                if (di > 0) issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "node", n.id(), null, "来源节点不能有数据入边"));
                if (oa == 0) issues.add(Issue.error("NODE_NOT_CONNECTED", "node", n.id(), null, "来源节点未连接任何下游"));
            } else if (isSink) {
                if (di != 1) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), null, "目的节点需恰好一个数据入边"));
                if (od > 0 || oa > 0) issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "node", n.id(), null, "目的节点不能有出边"));
            } else if (isMerge) {
                if (di < 2) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), null, "汇聚节点至少需要两个数据入边"));
                if (od == 0) issues.add(Issue.error("NODE_NOT_CONNECTED", "node", n.id(), null, "汇聚节点未连接下游"));
            } else if (isBranch) {
                if (di != 1) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), null, "条件分支需恰好一个数据入边"));
                var ports = OrchComponents.activeOutputPorts(c, n.config());
                var connected = new HashSet<String>();
                for (Edge e : edges) if (e.from().equals(n.id())) connected.add(e.fromPort());
                for (var p : ports) if (!connected.contains(p.id())) issues.add(Issue.error("BRANCH_PORT_UNCONNECTED", "node", n.id(), "config.rules", "分支出口 " + p.id() + " 未连接下游"));
                if (od == 0 && oa == 0) issues.add(Issue.error("NODE_NOT_CONNECTED", "node", n.id(), null, "条件分支未连接下游"));
            } else if (isSql) {
                if (dml) {
                    if (od > 0) issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "node", n.id(), null, "DML 模式 SQL 脚本是终止步骤，不能有数据出边"));
                } else {
                    if (od == 0) issues.add(Issue.error("NODE_NOT_CONNECTED", "node", n.id(), null, "SELECT 模式 SQL 脚本未连接下游"));
                }
            } else {
                if (di != 1) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), null, "处理节点需恰好一个数据入边（多入边请使用数据汇聚）"));
                if (ci > 0) issues.add(Issue.error("EDGE_INCOMPATIBLE_PORT", "node", n.id(), null, "该组件没有控制依赖入口"));
                if (od == 0) issues.add(Issue.error("NODE_NOT_CONNECTED", "node", n.id(), null, "处理节点未连接下游"));
            }
            if (!n.enabled()) issues.add(Issue.warn("NODE_DISABLED", "node", n.id(), "enabled", "节点已禁用，运行时将跳过"));
        }
        if (nodes.isEmpty()) issues.add(Issue.error("WORKFLOW_EMPTY", "workflow", null, null, "编排至少需要一个节点"));
        else if (!hasTrigger) issues.add(Issue.error("NO_EXECUTABLE_SOURCE", "workflow", null, null, "编排需要至少一个来源节点或 DML SQL 脚本作为执行起点"));
        // every node must reach a terminal (sink / DML), otherwise data has no landing semantics
        var reaches = new HashSet<String>(terminals);
        var pending = new ArrayDeque<>(terminals);
        while (!pending.isEmpty()) for (String prev : reverse.getOrDefault(pending.poll(), List.of())) if (reaches.add(prev)) pending.add(prev);
        for (Node n : nodes) {
            if (!reaches.contains(n.id()) && components.containsKey(n.id())) {
                boolean isSource = OrchComponents.CATEGORY_SOURCE.equals(components.get(n.id()).category());
                if (!isSource || outAll.getOrDefault(n.id(), 0) > 0)
                    issues.add(Issue.error("NODE_NO_TERMINAL", "node", n.id(), null, "节点的数据没有到达任何目的或 DML 终止步骤"));
            }
        }
        validateVariables(issues);
        return issues;
    }

    private void validateRetry(Node n, OrchComponents.Component c, List<Issue> issues) {
        var r = n.retry();
        if (!r.enabled()) return;
        if (!Boolean.TRUE.equals(c.capabilities().get("supportsRetry")))
            issues.add(Issue.error("COMPONENT_NOT_SUPPORTED", "node", n.id(), "retry", c.name() + " 不支持重试"));
        if (r.maxAttempts() < 1 || r.maxAttempts() > 5) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "retry.maxAttempts", "最多尝试次数需在 1–5 之间（含首次）"));
        if (r.intervalSeconds() < 1 || r.intervalSeconds() > 3600) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "retry.intervalSeconds", "重试间隔需在 1–3600 秒之间"));
        if (!Set.of("FIXED", "EXPONENTIAL").contains(r.backoff())) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "retry.backoff", "重试策略仅支持固定间隔或指数退避"));
        if (r.maxIntervalSeconds() < r.intervalSeconds() || r.maxIntervalSeconds() > 86400) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "retry.maxIntervalSeconds", "最大间隔需不小于重试间隔且不超过 86400 秒"));
        if (OrchComponents.isWrite(c) && !r.acknowledgeDuplicateRisk())
            issues.add(Issue.error("NON_IDEMPOTENT_RETRY", "node", n.id(), "retry.acknowledgeDuplicateRisk", "写操作节点开启重试必须确认幂等约束或允许重复风险"));
    }

    private void validateConfig(Node n, OrchComponents.Component c, DataSourceResolver sources, List<Issue> issues) {
        JsonNode cfg = n.config();
        // generic required fields honoring `when`
        for (var field : c.fields()) {
            if (!Boolean.TRUE.equals(field.get("required"))) continue;
            Object when = field.get("when");
            if (when instanceof Map<?, ?> w) {
                String wf = String.valueOf(w.get("field"));
                Object expected = w.get("equals");
                JsonNode actual = cfg.path(wf);
                boolean match = expected instanceof Boolean b ? actual.asBoolean(false) == b : String.valueOf(expected).equals(actual.asText(""));
                if (!match) continue;
            }
            String key = String.valueOf(field.get("key"));
            JsonNode v = cfg.path(key);
            boolean missing = v.isMissingNode() || v.isNull() || (v.isTextual() && v.textValue().isBlank())
                    || ("mappings".equals(field.get("type")) && (!v.isArray() || v.isEmpty()))
                    || ("operations".equals(field.get("type")) && (!v.isArray() || v.isEmpty()))
                    || ("rules".equals(field.get("type")) && (!v.isArray() || v.isEmpty()))
                    || ("columns".equals(field.get("type")) && (!v.isArray() || v.isEmpty()));
            if (missing) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "请填写「" + field.get("label") + "」"));
        }
        // datasource existence + type match
        JsonNode sid = cfg.path("sourceId");
        if (sid.isIntegralNumber() && sid.canConvertToLong() && sid.longValue() >= 1) {
            var type = sources.typeOf(sid.longValue());
            if (type.isEmpty()) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.sourceId", "数据源不存在或已删除"));
            else {
                String expected = null;
                for (var field : c.fields()) if ("source".equals(field.get("type")) && field.get("dbType") != null) expected = String.valueOf(field.get("dbType"));
                if (expected != null && !expected.equals(type.get()))
                    issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.sourceId", "数据源类型不匹配，需要 " + expected));
            }
        }
        switch (c.type()) {
            case "source.mysql", "source.postgresql", "source.oracle" -> {
                String readMode = cfg.path("readMode").asText("TABLE");
                if ("TABLE".equals(readMode)) table(cfg, "table", n, issues);
                else if ("SQL".equals(readMode)) sqlSelect(cfg, n, issues);
                else issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.readMode", "读取方式非法"));
                if (cfg.path("incremental").asBoolean(false)) {
                    if (!"SQL".equals(readMode)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.incremental", "增量读取需要 SQL 查询模式"));
                    ident(cfg, "watermarkColumn", n, issues);
                    String wmVar = cfg.path("watermarkVariable").asText("");
                    if (wmVar.isBlank()) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.watermarkVariable", "请填写水位变量名"));
                    else if (variables.stream().noneMatch(v -> v.name().equals(wmVar) && "WATERMARK".equals(v.valueSource())))
                        issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.watermarkVariable", "水位变量 " + wmVar + " 必须在工作流变量中声明且来源为 WATERMARK"));
                    if (!cfg.path("tieBreakerColumn").asText("").isBlank()) ident(cfg, "tieBreakerColumn", n, issues);
                }
                range(cfg, "fetchSize", 1, 10000, n, issues);
            }
            case "source.http" -> {
                url(cfg, "url", n, issues);
                if (!Set.of("GET", "POST").contains(cfg.path("method").asText("GET"))) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.method", "请求方法仅支持 GET/POST"));
                if ("PAGE_SIZE".equals(cfg.path("pagination").asText("NONE"))) {
                    range(cfg, "pageSize", 1, 1000, n, issues); range(cfg, "maxPages", 1, 1000, n, issues);
                }
                range(cfg, "timeoutSeconds", 1, 300, n, issues);
                headers(cfg, n, issues);
            }
            case "transform.sql" -> {
                String mode = cfg.path("sqlMode").asText("SELECT");
                String sql = cfg.path("sql").asText("");
                if (!Set.of("SELECT", "DML").contains(mode)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.sqlMode", "执行方式非法"));
                if (!sql.isBlank()) {
                    if (SqlParameters.hasMultipleStatements(sql)) issues.add(Issue.error("SQL_MULTI_STATEMENT", "node", n.id(), "config.sql", "不允许多语句 SQL"));
                    String kw = SqlParameters.firstKeyword(sql);
                    if ("SELECT".equals(mode) && !Set.of("SELECT", "WITH").contains(kw))
                        issues.add(Issue.error("SQL_MODE_MISMATCH", "node", n.id(), "config.sql", "查询模式必须以 SELECT/WITH 开头"));
                    if ("DML".equals(mode)) {
                        if (!Set.of("INSERT", "UPDATE", "DELETE").contains(kw))
                            issues.add(Issue.error("SQL_DDL_FORBIDDEN", "node", n.id(), "config.sql", "更新模式仅允许 INSERT/UPDATE/DELETE，禁止 DDL 与其他语句"));
                    }
                    checkPlaceholders(sql, n, issues);
                }
            }
            case "transform.field-map" -> {
                JsonNode mappings = cfg.path("mappings");
                if (mappings.isArray()) {
                    if (mappings.size() > 1000) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.mappings", "映射不能超过 1000 条"));
                    var targets = new HashSet<String>();
                    int i = 0;
                    for (JsonNode m : mappings) {
                        String from = m.path("from").asText(""); String to = m.path("to").asText("");
                        if (!from.matches(IDENT) || !to.matches(IDENT)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.mappings[" + i + "]", "映射字段名非法"));
                        else if (!targets.add(to.toLowerCase(Locale.ROOT))) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.mappings[" + i + "].to", "目标字段重复: " + to));
                        String tt = m.path("targetType").asText("STRING");
                        if (!Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATETIME", "JSON").contains(tt)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.mappings[" + i + "].targetType", "目标类型非法"));
                        String onError = m.path("onError").asText("FAIL");
                        if (!Set.of("FAIL", "NULL", "SKIP_ROW").contains(onError)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.mappings[" + i + "].onError", "转换失败策略非法"));
                        i++;
                    }
                }
            }
            case "transform.filter", "transform.calc" -> {
                String expr = cfg.path("expression").asText("");
                if (!expr.isBlank()) try { FlowExpression.parse(expr); } catch (IllegalArgumentException ex) { issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.expression", ex.getMessage())); }
                if ("transform.calc".equals(c.type())) ident(cfg, "field", n, issues);
            }
            case "transform.mask" -> ident(cfg, "field", n, issues);
            case "transform.clean" -> {
                JsonNode ops = cfg.path("operations");
                if (ops.isArray()) {
                    if (ops.size() > 50) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.operations", "清洗步骤不能超过 50"));
                    int i = 0;
                    for (JsonNode op : ops) {
                        String kind = op.path("op").asText("");
                        if (!Set.of("TRIM", "DROP_NULL", "DEDUP", "REGEX_REPLACE").contains(kind)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.operations[" + i + "].op", "清洗步骤类型非法"));
                        if (Set.of("TRIM", "DROP_NULL", "DEDUP").contains(kind) && (!op.path("fields").isArray() || op.path("fields").isEmpty()))
                            issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.operations[" + i + "].fields", "请填写作用字段"));
                        if ("REGEX_REPLACE".equals(kind)) {
                            if (!op.path("field").asText("").matches(IDENT)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.operations[" + i + "].field", "正则替换字段非法"));
                            String pattern = op.path("pattern").asText("");
                            if (pattern.isBlank()) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.operations[" + i + "].pattern", "正则表达式必填"));
                            else try { java.util.regex.Pattern.compile(pattern); } catch (IllegalArgumentException ex) { issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.operations[" + i + "].pattern", "正则表达式非法")); }
                        }
                        i++;
                    }
                }
            }
            case "control.branch" -> {
                JsonNode rules = cfg.path("rules");
                var keys = new HashSet<String>();
                if (rules.isArray()) {
                    if (rules.size() > 20) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.rules", "分支规则不能超过 20"));
                    int i = 0;
                    for (JsonNode r : rules) {
                        String key = r.path("key").asText("");
                        if (!key.matches(IDENT)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.rules[" + i + "].key", "分支标识非法"));
                        else if (!keys.add(key)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.rules[" + i + "].key", "分支标识重复: " + key));
                        String expr = r.path("expression").asText("");
                        if (expr.isBlank()) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.rules[" + i + "].expression", "分支条件必填"));
                        else try { FlowExpression.parse(expr); } catch (IllegalArgumentException ex) { issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.rules[" + i + "].expression", ex.getMessage())); }
                        i++;
                    }
                }
                String def = cfg.path("defaultKey").asText("");
                if (!def.matches(IDENT)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.defaultKey", "默认出口标识非法"));
                else if (keys.contains(def)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.defaultKey", "默认出口不能与规则分支同名"));
            }
            case "control.merge" -> {
                if (!"UNION".equals(cfg.path("strategy").asText("UNION"))) issues.add(Issue.error("COMPONENT_NOT_SUPPORTED", "node", n.id(), "config.strategy", "当前仅支持 UNION 汇聚"));
            }
            case "sink.mysql", "sink.postgresql", "sink.oracle" -> {
                table(cfg, "table", n, issues);
                String mode = cfg.path("writeMode").asText("APPEND");
                if (!Set.of("APPEND", "UPSERT").contains(mode)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.writeMode", "写入方式非法"));
                if ("UPSERT".equals(mode)) {
                    JsonNode keys = cfg.path("keyColumns");
                    if (!keys.isArray() || keys.isEmpty()) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.keyColumns", "UPSERT 需要唯一键字段"));
                    else {
                        var seen = new HashSet<String>();
                        for (JsonNode k : keys) if (!k.isTextual() || !k.textValue().matches(IDENT) || !seen.add(k.textValue().toLowerCase(Locale.ROOT)))
                            issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.keyColumns", "唯一键字段非法或重复"));
                    }
                }
                range(cfg, "batchSize", 1, 10000, n, issues);
            }
            case "sink.http" -> {
                url(cfg, "url", n, issues);
                if (!Set.of("POST", "PUT").contains(cfg.path("method").asText("POST"))) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config.method", "推送方法仅支持 POST/PUT"));
                range(cfg, "batchSize", 1, 1000, n, issues);
                range(cfg, "timeoutSeconds", 1, 300, n, issues);
                headers(cfg, n, issues);
                String template = cfg.path("bodyTemplate").asText("");
                if (!template.isBlank() && !template.contains("{{rows}}")) issues.add(Issue.warn("HTTP_TEMPLATE_NO_ROWS", "node", n.id(), "config.bodyTemplate", "Body 模板未包含 {{rows}}，将不会携带行数据"));
            }
            default -> { }
        }
    }

    private void checkPlaceholders(String sql, Node n, List<Issue> issues) {
        var names = new HashSet<>(SqlParameters.parse(sql).names());
        var declared = new HashSet<String>();
        for (Variable v : variables) declared.add(v.name());
        declared.add(BUILTIN_BUSINESS_DATE);
        declared.add("last_tie_breaker");
        for (String name : names) if (!declared.contains(name))
            issues.add(Issue.error("SQL_PARAM_UNDECLARED", "node", n.id(), "config.sql", "SQL 占位符 :" + name + " 未在工作流变量中声明"));
    }

    private void validateVariables(List<Issue> issues) {
        var seen = new HashSet<String>();
        for (Variable v : variables) {
            if (!v.name().matches(IDENT)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "workflow", null, "variables", "变量名非法: " + v.name()));
            else if (!seen.add(v.name())) issues.add(Issue.error("NODE_REQUIRED_FIELD", "workflow", null, "variables", "变量名重复: " + v.name()));
            if (BUILTIN_BUSINESS_DATE.equals(v.name())) issues.add(Issue.error("NODE_REQUIRED_FIELD", "workflow", null, "variables", "变量名与系统内置变量冲突: " + v.name()));
            if (!Set.of("STRING", "NUMBER", "BOOLEAN", "DATE", "DATETIME").contains(v.type())) issues.add(Issue.error("NODE_REQUIRED_FIELD", "workflow", null, "variables", "变量类型非法: " + v.name()));
            if (!Set.of("MANUAL", "WATERMARK").contains(v.valueSource())) issues.add(Issue.error("NODE_REQUIRED_FIELD", "workflow", null, "variables", "变量来源非法: " + v.name()));
            if ("WATERMARK".equals(v.valueSource()) && nodes.stream().noneMatch(n -> n.config().path("watermarkVariable").asText("").equals(v.name())))
                issues.add(Issue.warn("VARIABLE_UNUSED", "workflow", null, "variables", "水位变量 " + v.name() + " 未被任何增量来源引用"));
        }
    }

    private void sqlSelect(JsonNode cfg, Node n, List<Issue> issues) {
        String sql = cfg.path("sql").asText("");
        if (sql.isBlank()) return;
        if (SqlParameters.hasMultipleStatements(sql)) issues.add(Issue.error("SQL_MULTI_STATEMENT", "node", n.id(), "config.sql", "不允许多语句 SQL"));
        String kw = SqlParameters.firstKeyword(sql);
        if (!Set.of("SELECT", "WITH").contains(kw)) issues.add(Issue.error("SQL_MODE_MISMATCH", "node", n.id(), "config.sql", "来源查询必须以 SELECT/WITH 开头"));
        checkPlaceholders(sql, n, issues);
    }

    private void table(JsonNode cfg, String key, Node n, List<Issue> issues) {
        String v = cfg.path(key).asText("");
        if (!v.isBlank() && !v.matches(QUALIFIED)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "表名非法（允许 schema.table）"));
    }
    private void ident(JsonNode cfg, String key, Node n, List<Issue> issues) {
        String v = cfg.path(key).asText("");
        if (!v.isBlank() && !v.matches(IDENT)) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "字段名非法: " + v));
    }
    private void url(JsonNode cfg, String key, Node n, List<Issue> issues) {
        String v = cfg.path(key).asText("");
        if (v.isBlank()) return;
        try {
            var uri = java.net.URI.create(v);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "URL 非法，仅支持 http/https"));
        }
    }
    private void range(JsonNode cfg, String key, int min, int max, Node n, List<Issue> issues) {
        JsonNode v = cfg.get(key);
        if (v == null || v.isNull()) return;
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max)
            issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "数值需在 " + min + "–" + max + " 之间"));
    }
    private void headers(JsonNode cfg, Node n, List<Issue> issues) {
        for (String key : List.of("headers", "queryParams")) {
            JsonNode hs = cfg.path(key);
            if (!hs.isArray()) continue;
            if (hs.size() > 50) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "键值对不能超过 50"));
            for (JsonNode h : hs) {
                String name = h.path("name").asText("");
                if (!name.matches("[A-Za-z0-9_-]{1,100}")) issues.add(Issue.error("NODE_REQUIRED_FIELD", "node", n.id(), "config." + key, "键名非法: " + name));
            }
        }
    }

    /** Collect datasource ids referenced by any node config (for reference locking). */
    public List<Long> sourceIds() {
        var ids = new ArrayList<Long>();
        for (Node n : nodes) {
            JsonNode sid = n.config().path("sourceId");
            if (sid.isIntegralNumber() && sid.canConvertToLong() && sid.longValue() >= 1) ids.add(sid.longValue());
        }
        return ids;
    }

    public Map<String, Integer> topoOrder() {
        var indeg = new HashMap<String, Integer>();
        var next = new HashMap<String, List<String>>();
        for (Node n : nodes) indeg.put(n.id(), 0);
        for (Edge e : edges) { next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()); indeg.merge(e.to(), 1, Integer::sum); }
        var queue = new ArrayDeque<String>();
        for (var en : indeg.entrySet()) if (en.getValue() == 0) queue.add(en.getKey());
        var order = new LinkedHashMap<String, Integer>();
        int i = 0;
        while (!queue.isEmpty()) {
            String k = queue.poll(); order.put(k, i++);
            for (String n : next.getOrDefault(k, List.of())) if (indeg.merge(n, -1, Integer::sum) == 0) queue.add(n);
        }
        if (order.size() != nodes.size()) throw new IllegalArgumentException("编排存在环");
        return order;
    }
}