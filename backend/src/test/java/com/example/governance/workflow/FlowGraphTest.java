package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowGraphTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static FlowGraph.Graph parse(String json) throws Exception { return FlowGraph.parse(MAPPER.readTree(json)); }
    private static String graph(String nodes, String edges) { return "{\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}"; }
    private static String node(String key, String component, String config) { return "{\"key\":\"" + key + "\",\"component\":\"" + component + "\",\"config\":" + config + "}"; }
    private static final String SRC = "{\"sourceId\":1,\"table\":\"orders\"}";
    private static final String SINK = "{\"sourceId\":2,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}";

    @Test void validChainParsesAndTopoOrders() throws Exception {
        var g = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"amount > 0\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"}"));
        FlowGraph.validate(g);
        assertEquals(java.util.List.of("s", "f", "t"), FlowGraph.topoOrder(g).stream().map(FlowGraph.Node::key).toList());
    }

    @Test void cyclesRejected() throws Exception {
        var g = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"},{\"from\":\"f\",\"to\":\"s\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g));
    }

    @Test void layerRulesRejected() throws Exception {
        var g1 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"t\",\"to\":\"s\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g1));
        var g2 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"},{\"from\":\"t\",\"to\":\"s\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g2));
        var g3 = parse(graph(node("s1", "SOURCE_TABLE", SRC) + "," + node("s2", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s1\",\"to\":\"f\"},{\"from\":\"s2\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g3));
    }

    @Test void danglingNodesAndMissingEndpointsRejected() throws Exception {
        var g1 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}"), "{\"from\":\"s\",\"to\":\"f\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g1));
        var g2 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK) + "," + node("x", "FIELD_CALC", "{\"field\":\"c\",\"expression\":\"1\"}"), "{\"from\":\"s\",\"to\":\"t\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g2));
    }

    @Test void componentConfigRequired() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", "{\"sourceId\":1}") + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_SQL", "{\"sourceId\":1,\"sql\":\"SELECT 1\"}") + "," + node("m", "FIELD_MAP", "{\"mappings\":[]}") + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"t\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("c", "FIELD_CALC", "{\"field\":\"c\",\"expression\":\"1 +\"}") + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"c\"},{\"from\":\"c\",\"to\":\"t\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", "{\"sourceId\":2,\"table\":\"t\",\"writeMode\":\"UPSERT\"}"), "{\"from\":\"s\",\"to\":\"t\"}"))));
    }

    @Test void unreachableChainRejectedByReverseBfs() throws Exception {
        // 合法对 s1→t 加孤立链 s2→f：所有度数检查通过，仅反向 BFS 能拒 f/s2
        var g = parse(graph(node("s1", "SOURCE_TABLE", SRC) + "," + node("s2", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s1\",\"to\":\"t\"},{\"from\":\"s2\",\"to\":\"f\"}"));
        var ex = assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g));
        assertTrue(ex.getMessage().contains("未连接到目标"), ex.getMessage());
    }

    @Test void edgeWithMissingEndpointRejected() throws Exception {
        var g = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"nope\"}"));
        var ex = assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g));
        assertTrue(ex.getMessage().contains("Invalid edge"), ex.getMessage());
    }

    @Test void topoOrderRejectsCycleDirectly() throws Exception {
        // a↔b 环图直调 topoOrder，不经 validate 的度数守卫
        var g = parse(graph(node("a", "FIELD_FILTER", "{\"expression\":\"true\"}") + "," + node("b", "FIELD_FILTER", "{\"expression\":\"true\"}"),
                "{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"b\",\"to\":\"a\"}"));
        var ex = assertThrows(IllegalArgumentException.class, () -> FlowGraph.topoOrder(g));
        assertTrue(ex.getMessage().contains("流程存在环"), ex.getMessage());
    }

    @Test void duplicateKeysAndUnknownComponentsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("s", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"s\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "KAFKA", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"}"))));
    }
}
