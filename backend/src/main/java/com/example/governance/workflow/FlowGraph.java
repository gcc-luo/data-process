package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FLOW task topology model with structural and per-component configuration validation. */
public final class FlowGraph {
    public record Node(String key, String component, JsonNode config, double x, double y) {}
    public record Edge(String from, String to) {}
    public record Graph(List<Node> nodes, List<Edge> edges) {}
    public static final Set<String> SOURCES = Set.of("SOURCE_TABLE", "SOURCE_SQL");
    public static final Set<String> SINKS = Set.of("SINK_TABLE");
    public static final Set<String> INTERCEPTORS = Set.of("FIELD_MAP", "FIELD_FILTER", "FIELD_MASK", "FIELD_CALC");
    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_$]*";
    private static final String QUALIFIED = IDENT + "(\\." + IDENT + ")?";

    public static Graph parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid flow configuration");
        JsonNode nodes = root.path("nodes"); JsonNode edges = root.path("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 100 || !edges.isArray() || edges.size() > 200) throw new IllegalArgumentException("Invalid flow nodes or edges");
        var nodeList = new ArrayList<Node>();
        for (JsonNode n : nodes) {
            String key = text(n, "key"); String component = text(n, "component");
            JsonNode config = n.path("config");
            if (!config.isObject()) throw new IllegalArgumentException("Invalid node config: " + key);
            nodeList.add(new Node(key, component, config, n.path("position").path("x").asDouble(0), n.path("position").path("y").asDouble(0)));
        }
        var edgeList = new ArrayList<Edge>();
        for (JsonNode e : edges) edgeList.add(new Edge(text(e, "from"), text(e, "to")));
        return new Graph(List.copyOf(nodeList), List.copyOf(edgeList));
    }

    public static void validate(Graph g) {
        var byKey = new HashMap<String, Node>();
        for (Node n : g.nodes()) {
            if (!n.key().matches(IDENT) || n.key().length() > 64 || byKey.put(n.key(), n) != null) throw new IllegalArgumentException("Invalid or duplicate node key: " + n.key());
            if (!SOURCES.contains(n.component()) && !SINKS.contains(n.component()) && !INTERCEPTORS.contains(n.component())) throw new IllegalArgumentException("Unknown component: " + n.component());
        }
        var in = new HashMap<String, Integer>(); var out = new HashMap<String, Integer>();
        var seenEdges = new HashSet<String>();
        for (Edge e : g.edges()) {
            if (!byKey.containsKey(e.from()) || !byKey.containsKey(e.to()) || e.from().equals(e.to()) || !seenEdges.add(e.from() + "->" + e.to())) throw new IllegalArgumentException("Invalid edge: " + e.from() + "->" + e.to());
            out.merge(e.from(), 1, Integer::sum); in.merge(e.to(), 1, Integer::sum);
        }
        boolean hasSource = false; boolean hasSink = false;
        for (Node n : g.nodes()) {
            int indeg = in.getOrDefault(n.key(), 0); int outdeg = out.getOrDefault(n.key(), 0);
            if (SOURCES.contains(n.component())) { hasSource = true; if (indeg != 0) throw new IllegalArgumentException("来源节点不能有入边: " + n.key()); }
            else if (SINKS.contains(n.component())) { hasSink = true; if (outdeg != 0) throw new IllegalArgumentException("目标节点不能有出边: " + n.key()); if (indeg != 1) throw new IllegalArgumentException("目标节点需恰一个入边: " + n.key()); }
            else if (indeg != 1) throw new IllegalArgumentException("预处理节点需恰一个入边: " + n.key());
            if (!SINKS.contains(n.component()) && outdeg > 1) throw new IllegalArgumentException("节点只能有一条出边: " + n.key());
            try { validateConfig(n); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("节点 " + n.key() + ": " + ex.getMessage()); }
        }
        if (!hasSource || !hasSink) throw new IllegalArgumentException("流程需至少一个来源与一个目标");
        var reverse = new HashMap<String, List<String>>();
        for (Edge e : g.edges()) reverse.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        var reachable = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        for (Node n : g.nodes()) if (SINKS.contains(n.component())) { reachable.add(n.key()); queue.add(n.key()); }
        while (!queue.isEmpty()) for (String prev : reverse.getOrDefault(queue.poll(), List.of())) if (reachable.add(prev)) queue.add(prev);
        for (Node n : g.nodes()) if (!reachable.contains(n.key())) throw new IllegalArgumentException("节点未连接到目标: " + n.key());
        topoOrder(g);
    }

    public static List<Node> topoOrder(Graph g) {
        var byKey = new HashMap<String, Node>();
        var indeg = new HashMap<String, Integer>();
        var next = new HashMap<String, List<String>>();
        for (Node n : g.nodes()) { byKey.put(n.key(), n); indeg.put(n.key(), 0); }
        for (Edge e : g.edges()) { next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()); indeg.merge(e.to(), 1, Integer::sum); }
        var queue = new ArrayDeque<String>();
        for (Node n : g.nodes()) if (indeg.get(n.key()) == 0) queue.add(n.key());
        var order = new ArrayList<Node>();
        while (!queue.isEmpty()) {
            String key = queue.poll();
            order.add(byKey.get(key));
            for (String n : next.getOrDefault(key, List.of())) if (indeg.merge(n, -1, Integer::sum) == 0) queue.add(n);
        }
        if (order.size() != g.nodes().size()) throw new IllegalArgumentException("流程存在环");
        return order;
    }

    /** 仅对已通过 validate 的图有意义（单入边约束）。 */
    public static Map<String, String> upstream(Graph g) {
        var map = new HashMap<String, String>();
        for (Edge e : g.edges()) map.put(e.to(), e.from());
        return map;
    }

    private static void validateConfig(Node n) {
        JsonNode c = n.config();
        switch (n.component()) {
            case "SOURCE_TABLE" -> { id(c, "sourceId"); table(c, "table"); }
            case "SOURCE_SQL" -> { id(c, "sourceId"); text(c, "sql"); if (c.has("parameters")) parameters(c.path("parameters")); }
            case "FIELD_MAP" -> {
                JsonNode mappings = c.path("mappings");
                if (!mappings.isArray() || mappings.isEmpty() || mappings.size() > 1000) throw new IllegalArgumentException("Invalid mappings: " + n.key());
                var targets = new HashSet<String>();
                for (JsonNode m : mappings) {
                    String from = text(m, "from"); String to = text(m, "to");
                    if (!from.matches(IDENT) || !to.matches(IDENT) || !targets.add(to.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid mapping: " + n.key());
                }
            }
            case "FIELD_FILTER" -> FlowExpression.parse(text(c, "expression"));
            case "FIELD_MASK" -> { if (!text(c, "field").matches(IDENT)) throw new IllegalArgumentException("Invalid mask field: " + n.key()); }
            case "FIELD_CALC" -> { if (!text(c, "field").matches(IDENT)) throw new IllegalArgumentException("Invalid calc field: " + n.key()); FlowExpression.parse(text(c, "expression")); }
            case "SINK_TABLE" -> {
                id(c, "sourceId"); table(c, "table");
                String mode = c.has("writeMode") ? text(c, "writeMode") : "APPEND";
                if (!Set.of("APPEND", "UPSERT").contains(mode)) throw new IllegalArgumentException("Invalid write mode: " + n.key());
                if ("UPSERT".equals(mode)) {
                    JsonNode keys = c.path("keyColumns");
                    if (!keys.isArray() || keys.isEmpty() || keys.size() > 1000) throw new IllegalArgumentException("Invalid key columns: " + n.key());
                    var seen = new HashSet<String>();
                    for (JsonNode k : keys) if (!k.isTextual() || !k.textValue().matches(IDENT) || !seen.add(k.textValue().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid key column: " + n.key());
                }
                JsonNode batch = c.get("batchSize");
                if (batch != null && (!batch.isIntegralNumber() || !batch.canConvertToInt() || batch.intValue() < 1 || batch.intValue() > 10000)) throw new IllegalArgumentException("Invalid batch size: " + n.key());
            }
            default -> throw new IllegalArgumentException("Unknown component: " + n.component());
        }
    }
    private static void parameters(JsonNode params) {
        if (!params.isArray() || params.size() > 1000) throw new IllegalArgumentException("Invalid parameters");
        for (JsonNode p : params) {
            String kind = text(p, "type");
            if ("VALUE".equals(kind)) { JsonNode v = p.get("value"); if (v != null && !v.isValueNode()) throw new IllegalArgumentException("Invalid parameter"); }
            else if (!"BUSINESS_DATE".equals(kind)) throw new IllegalArgumentException("Invalid parameter type");
        }
    }
    private static void id(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1) throw new IllegalArgumentException("Invalid source ID");
    }
    private static void table(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || !v.isTextual() || !v.textValue().matches(QUALIFIED)) throw new IllegalArgumentException("Invalid table");
    }
    private static String text(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || !v.isTextual() || v.textValue().isBlank()) throw new IllegalArgumentException("Invalid flow field: " + key);
        return v.textValue();
    }
}
