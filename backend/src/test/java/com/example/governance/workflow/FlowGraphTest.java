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

    @Test void duplicateKeysAndUnknownComponentsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("s", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"s\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "KAFKA", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"}"))));
    }
}
