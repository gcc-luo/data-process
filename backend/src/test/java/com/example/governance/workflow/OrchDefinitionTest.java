package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Structural + semantic validation rules with nodeId/edgeId localization. */
class OrchDefinitionTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final OrchDefinition.DataSourceResolver MYSQL = id -> id == 1 || id == 2 ? Optional.of("MYSQL") : Optional.empty();

    private static List<OrchDefinition.Issue> errors(String json) throws Exception {
        return OrchDefinition.parse(M.readTree(json)).validate(MYSQL).stream().filter(i -> "ERROR".equals(i.severity())).toList();
    }
    private static String node(String id, String comp, String cfg) {
        return "{\"id\":\"" + id + "\",\"component\":\"" + comp + "\",\"name\":\"" + id + "\",\"enabled\":true,\"config\":" + cfg + "}";
    }
    private static String edge(String id, String from, String fp, String to, String tp, String kind) {
        return "{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"fromPort\":\"" + fp + "\",\"to\":\"" + to + "\",\"toPort\":\"" + tp + "\",\"kind\":\"" + kind + "\"}";
    }
    private static String def(String nodes, String edges) {
        return "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
    }
    private static final String SRC = node("s", "source.mysql", "{\"sourceId\":1,\"readMode\":\"TABLE\",\"table\":\"src\"}");
    private static final String SINK = node("k", "sink.mysql", "{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}");
    private static final String MAP = node("m", "transform.field-map", "{\"mappings\":[{\"from\":\"id\",\"to\":\"id\",\"targetType\":\"INTEGER\"}]}");

    @Test void validChainHasNoErrors() throws Exception {
        assertTrue(errors(def(SRC + "," + MAP + "," + SINK, edge("e1", "s", "out", "m", "in", "DATA") + "," + edge("e2", "m", "out", "k", "in", "DATA"))).isEmpty());
    }
    @Test void selfLoopRejected() throws Exception {
        assertTrue(errors(def(SRC + "," + SINK, edge("e1", "s", "out", "s", "in", "DATA"))).stream().anyMatch(i -> i.code().equals("EDGE_SELF_LOOP")));
    }
    @Test void duplicateEdgeRejected() throws Exception {
        var e = edge("e1", "s", "out", "k", "in", "DATA");
        assertTrue(errors(def(SRC + "," + SINK, e + "," + edge("e2", "s", "out", "k", "in", "DATA"))).stream().anyMatch(i -> i.code().equals("EDGE_DUPLICATE")));
    }
    @Test void cycleRejected() throws Exception {
        var a = node("a", "transform.filter", "{\"expression\":\"id > 0\"}");
        var b = node("b", "transform.filter", "{\"expression\":\"id > 1\"}");
        assertTrue(errors(def(SRC + "," + a + "," + b + "," + SINK,
                edge("e1", "s", "out", "a", "in", "DATA") + "," + edge("e2", "a", "out", "b", "in", "DATA") + "," + edge("e3", "b", "out", "a", "in", "DATA") + "," + edge("e4", "b", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.code().equals("WORKFLOW_CYCLE_DETECTED")));
    }
    @Test void sourceCannotHaveDataInput() throws Exception {
        assertTrue(errors(def(SRC + "," + MAP + "," + SINK, edge("e1", "m", "out", "s", "in", "DATA") + "," + edge("e2", "s", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.code().equals("EDGE_INCOMPATIBLE_PORT")));
    }
    @Test void transformWithTwoDataInputsRequiresMerge() throws Exception {
        var src2 = node("s2", "source.mysql", "{\"sourceId\":1,\"readMode\":\"TABLE\",\"table\":\"src2\"}");
        assertTrue(errors(def(SRC + "," + src2 + "," + MAP + "," + SINK,
                edge("e1", "s", "out", "m", "in", "DATA") + "," + edge("e2", "s2", "out", "m", "in", "DATA") + "," + edge("e3", "m", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.targetId().equals("m")));
    }
    @Test void branchRequiresConnectedDefaultAndUniqueKeys() throws Exception {
        var branch = node("b", "control.branch", "{\"rules\":[{\"key\":\"high\",\"expression\":\"amount >= 1000\"},{\"key\":\"high\",\"expression\":\"id > 0\"}],\"defaultKey\":\"high\"}");
        var issues = errors(def(SRC + "," + branch + "," + SINK, edge("e1", "s", "out", "b", "in", "DATA") + "," + edge("e2", "b", "high", "k", "in", "DATA")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("NODE_REQUIRED_FIELD")));
    }
    @Test void missingRequiredSourceIdLocalized() throws Exception {
        var bad = node("s", "source.mysql", "{\"readMode\":\"TABLE\",\"table\":\"src\"}");
        var issues = errors(def(bad + "," + SINK, edge("e1", "s", "out", "k", "in", "DATA")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("NODE_REQUIRED_FIELD") && "config.sourceId".equals(i.field())));
    }
    @Test void datasourceTypeMismatchRejected() throws Exception {
        var pgSink = node("k", "sink.postgresql", "{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}");
        assertTrue(errors(def(SRC + "," + pgSink, edge("e1", "s", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.code().equals("NODE_REQUIRED_FIELD") && "config.sourceId".equals(i.field())));
    }
    @Test void undeclaredSqlPlaceholderRejected() throws Exception {
        var sql = node("q", "transform.sql", "{\"sourceId\":1,\"sqlMode\":\"SELECT\",\"sql\":\"SELECT id FROM t WHERE x = :nope\"}");
        assertTrue(errors(def(SRC + "," + sql + "," + SINK, edge("e0", "s", "out", "q", "dep", "CONTROL") + "," + edge("e1", "q", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.code().equals("SQL_PARAM_UNDECLARED")));
    }
    @Test void ddlForbiddenInDmlMode() throws Exception {
        var sql = node("q", "transform.sql", "{\"sourceId\":1,\"sqlMode\":\"DML\",\"sql\":\"DROP TABLE t\"}");
        assertTrue(errors(def(sql, "")).stream().anyMatch(i -> i.code().equals("SQL_DDL_FORBIDDEN")));
    }
    @Test void multiStatementRejected() throws Exception {
        var sql = node("q", "transform.sql", "{\"sourceId\":1,\"sqlMode\":\"DML\",\"sql\":\"DELETE FROM t; DROP TABLE t\"}");
        assertTrue(errors(def(sql, "")).stream().anyMatch(i -> i.code().equals("SQL_MULTI_STATEMENT")));
    }
    @Test void writeRetryWithoutAckRejected() throws Exception {
        var sink = node("k", "sink.mysql", "{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}");
        var withRetry = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" + SRC + "," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"k\",\"enabled\":true,\"retry\":{\"enabled\":true,\"maxAttempts\":3,\"intervalSeconds\":5,\"backoff\":\"FIXED\",\"maxIntervalSeconds\":60,\"acknowledgeDuplicateRisk\":false},\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" + edge("e1", "s", "out", "k", "in", "DATA") + "]}";
        assertTrue(errors(withRetry).stream().anyMatch(i -> i.code().equals("NON_IDEMPOTENT_RETRY")));
    }
    @Test void disabledComponentRejected() throws Exception {
        var py = node("p", "transform.python", "{}");
        assertTrue(errors(def(py, "")).stream().anyMatch(i -> i.code().equals("COMPONENT_NOT_SUPPORTED")));
    }
    @Test void mergeNeedsTwoInputs() throws Exception {
        var merge = node("mg", "control.merge", "{\"strategy\":\"UNION\"}");
        assertTrue(errors(def(SRC + "," + merge + "," + SINK, edge("e1", "s", "out", "mg", "in", "DATA") + "," + edge("e2", "mg", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.targetId().equals("mg")));
    }
    @Test void danglingNodeRejected() throws Exception {
        var orphan = node("o", "transform.filter", "{\"expression\":\"id > 0\"}");
        assertTrue(errors(def(SRC + "," + SINK + "," + orphan, edge("e1", "s", "out", "k", "in", "DATA")))
                .stream().anyMatch(i -> i.code().equals("NODE_NO_TERMINAL") || i.code().equals("NODE_REQUIRED_FIELD")));
    }
    @Test void emptyWorkflowRejected() throws Exception {
        assertTrue(errors(def("", "")).stream().anyMatch(i -> i.code().equals("WORKFLOW_EMPTY")));
    }
    @Test void controlEdgeToSqlScriptIsValidDependency() throws Exception {
        var sql = node("q", "transform.sql", "{\"sourceId\":1,\"sqlMode\":\"SELECT\",\"sql\":\"SELECT id FROM t\"}");
        assertTrue(errors(def(SRC + "," + sql + "," + SINK, edge("e0", "s", "out", "q", "dep", "CONTROL") + "," + edge("e1", "q", "out", "k", "in", "DATA"))).isEmpty());
    }
}