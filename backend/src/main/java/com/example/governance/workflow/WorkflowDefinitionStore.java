package com.example.governance.workflow;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class WorkflowDefinitionStore {
    public record Node(String key, long taskId, int taskVersion, Double x, Double y) {
        public Node(String key, long taskId, int taskVersion) { this(key, taskId, taskVersion, null, null); }
    }
    public record Definition(long id, int version, String name, List<Node> nodes, List<WorkflowGraph.Edge> edges) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public WorkflowDefinitionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; transaction = new TransactionTemplate(manager); }

    public long create(String name, List<Node> nodes, List<WorkflowGraph.Edge> edges) {
        validate(name, nodes, edges);
        return transaction.execute(status -> {
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO workflow_definitions(name,current_version) VALUES (?,1)", new String[]{"id"});
                statement.setString(1, name); return statement;
            }, key);
            Number id = key.getKey();
            if (id == null) throw new IllegalStateException("Workflow ID was not generated");
            long workflowId = id.longValue();
            insertVersion(workflowId, 1, nodes, edges);
            return workflowId;
        });
    }
    public int addVersion(long id, List<Node> nodes, List<WorkflowGraph.Edge> edges) {
        validate("version", nodes, edges);
        return transaction.execute(status -> {
            var current = jdbc.queryForList("SELECT current_version FROM workflow_definitions WHERE id=? FOR UPDATE", Integer.class, id);
            if (current.isEmpty()) throw new IllegalArgumentException("Unknown workflow");
            int next = Math.addExact(current.get(0), 1);
            insertVersion(id, next, nodes, edges);
            jdbc.update("UPDATE workflow_definitions SET current_version=? WHERE id=?", next, id);
            return next;
        });
    }
    private void insertVersion(long id, int version, List<Node> nodes, List<WorkflowGraph.Edge> edges) {
        jdbc.update("INSERT INTO workflow_versions(workflow_id,version) VALUES (?,?)", id, version);
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            jdbc.update("INSERT INTO workflow_nodes(workflow_id,workflow_version,node_key,ordinal_no,task_id,task_version,position_x,position_y) VALUES (?,?,?,?,?,?,?,?)", id, version, node.key(), i, node.taskId(), node.taskVersion(), node.x(), node.y());
        }
        for (int i = 0; i < edges.size(); i++) {
            var edge = edges.get(i);
            jdbc.update("INSERT INTO workflow_edges(workflow_id,workflow_version,ordinal_no,from_key,to_key) VALUES (?,?,?,?,?)", id, version, i, edge.from(), edge.to());
        }
    }
    public record Summary(long id, String name, int version) {}
    public List<Summary> list() {
        return jdbc.query("SELECT id,name,current_version FROM workflow_definitions ORDER BY id", (rs, row) -> new Summary(rs.getLong(1), rs.getString(2), rs.getInt(3)));
    }
    public Optional<Definition> get(long id, int version) {
        var names = jdbc.query("SELECT d.name FROM workflow_definitions d JOIN workflow_versions v ON d.id=v.workflow_id WHERE d.id=? AND v.version=?", (rs, row) -> rs.getString(1), id, version);
        if (names.isEmpty()) return Optional.empty();
        var nodes = jdbc.query("SELECT node_key,task_id,task_version,position_x,position_y FROM workflow_nodes WHERE workflow_id=? AND workflow_version=? ORDER BY ordinal_no", (rs, row) -> new Node(rs.getString(1), rs.getLong(2), rs.getInt(3), (Double) rs.getObject(4), (Double) rs.getObject(5)), id, version);
        var edges = jdbc.query("SELECT from_key,to_key FROM workflow_edges WHERE workflow_id=? AND workflow_version=? ORDER BY ordinal_no", (rs, row) -> new WorkflowGraph.Edge(rs.getString(1), rs.getString(2)), id, version);
        return Optional.of(new Definition(id, version, names.get(0), List.copyOf(nodes), List.copyOf(edges)));
    }
    private void validate(String name, List<Node> nodes, List<WorkflowGraph.Edge> edges) {
        if (name == null || name.isBlank() || name.length() > 160 || nodes == null || nodes.isEmpty() || nodes.size() > 1000 || edges == null || edges.size() > 10000 || nodes.stream().anyMatch(n -> n == null || n.key() == null || n.key().isBlank() || n.key().length() > 128 || n.taskId() < 1 || n.taskVersion() < 1 || (n.x() != null && (!Double.isFinite(n.x()) || Math.abs(n.x()) > 100000)) || (n.y() != null && (!Double.isFinite(n.y()) || Math.abs(n.y()) > 100000))) || edges.stream().anyMatch(e -> e == null || e.from() == null || e.to() == null)) throw new IllegalArgumentException("Invalid workflow definition");
        var keys = nodes.stream().map(Node::key).collect(Collectors.toSet());
        if (keys.size() != nodes.size() || new HashSet<>(edges).size() != edges.size()) throw new IllegalArgumentException("Duplicate workflow nodes or edges");
        new WorkflowGraph(keys, edges);
    }
}
