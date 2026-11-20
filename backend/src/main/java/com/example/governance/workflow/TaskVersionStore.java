package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores immutable snapshots; configuration validation for each task type is performed before execution. */
public final class TaskVersionStore {
    public record Version(long taskId, int version, String type, String configuration) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper = new ObjectMapper();
    public TaskVersionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); }
    public long create(String name, String type, String configuration) { return create(name, type, configuration, null, null); }
    public long create(String name, String type, String configuration, Long directoryId, String description) {
        if (name == null || name.isBlank() || name.length() > 160 || type == null || !Set.of("SQL", "SYNC").contains(type)) throw new IllegalArgumentException("Invalid task");
        if (directoryId != null && jdbc.queryForList("SELECT id FROM task_directories WHERE id=?", Long.class, directoryId).isEmpty()) throw new IllegalArgumentException("Unknown directory");
        if (description != null && description.length() > 500) throw new IllegalArgumentException("Invalid description");
        validate(configuration);
        return transaction.execute(status -> {
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO processing_tasks(name,task_type,current_version,directory_id,description) VALUES (?,?,1,?,?)", new String[]{"id"});
                statement.setString(1, name); statement.setString(2, type);
                if (directoryId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, directoryId);
                statement.setString(4, description); return statement;
            }, key);
            Number id = key.getKey();
            if (id == null) throw new IllegalStateException("Task ID was not generated");
            jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (?,1,?)", id.longValue(), configuration);
            return id.longValue();
        });
    }
    public int addVersion(long taskId, String configuration) {
        validate(configuration);
        return transaction.execute(status -> {
            var versions = jdbc.query("SELECT current_version FROM processing_tasks WHERE id=? FOR UPDATE", (rs, row) -> rs.getInt(1), taskId);
            if (versions.isEmpty()) throw new IllegalArgumentException("Unknown task");
            int next = Math.addExact(versions.getFirst(), 1);
            jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (?,?,?)", taskId, next, configuration);
            jdbc.update("UPDATE processing_tasks SET current_version=? WHERE id=?", next, taskId);
            return next;
        });
    }
    public record Task(long id, String name, String type, int version, String configuration, Long directoryId, String description) {}
    public java.util.List<Task> list() {
        return jdbc.query("SELECT t.id,t.name,t.task_type,t.current_version,v.configuration,t.directory_id,t.description FROM processing_tasks t JOIN processing_task_versions v ON v.task_id=t.id AND v.version=t.current_version ORDER BY t.id", (rs, row) -> new Task(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getObject(6, Long.class), rs.getString(7)));
    }
    public void updateMetadata(long id, com.fasterxml.jackson.databind.JsonNode changes) {
        if (changes == null || !changes.isObject()) throw new IllegalArgumentException("Invalid metadata");
        transaction.executeWithoutResult(status -> {
            var rows = jdbc.query("SELECT name,directory_id,description FROM processing_tasks WHERE id=? FOR UPDATE",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getObject(2, Long.class), rs.getString(3)}, id);
            if (rows.isEmpty()) throw new IllegalArgumentException("Unknown task");
            Object[] current = rows.getFirst();
            String name = (String) current[0];
            if (changes.has("name")) {
                com.fasterxml.jackson.databind.JsonNode node = changes.get("name");
                name = node.isTextual() ? node.asText() : null;
            }
            if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Invalid task name");
            Long directoryId = changes.has("directoryId") ? (changes.get("directoryId").isNull() ? null : changes.get("directoryId").asLong()) : (Long) current[1];
            if (directoryId != null && jdbc.queryForList("SELECT id FROM task_directories WHERE id=?", Long.class, directoryId).isEmpty()) throw new IllegalArgumentException("Unknown directory");
            String description = (String) current[2];
            if (changes.has("description")) {
                com.fasterxml.jackson.databind.JsonNode node = changes.get("description");
                if (node.isNull()) description = null;
                else if (node.isTextual()) description = node.asText();
                else throw new IllegalArgumentException("Invalid description");
            }
            if (description != null && description.length() > 500) throw new IllegalArgumentException("Invalid description");
            jdbc.update("UPDATE processing_tasks SET name=?,directory_id=?,description=? WHERE id=?", name, directoryId, description, id);
        });
    }
    public Optional<Version> findVersion(long taskId, int version) {
        return jdbc.query("SELECT v.task_id,v.version,t.task_type,v.configuration FROM processing_task_versions v JOIN processing_tasks t ON t.id=v.task_id WHERE v.task_id=? AND v.version=?", (rs, row) -> new Version(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)), taskId, version).stream().findFirst();
    }
    private void validate(String configuration) {
        if (configuration == null || configuration.length() > 100000) throw new IllegalArgumentException("Invalid task configuration");
        try { if (!mapper.readTree(configuration).isObject()) throw new IllegalArgumentException("Task configuration must be a JSON object"); }
        catch (java.io.IOException | NullPointerException ex) { throw new IllegalArgumentException("Invalid task configuration"); }
    }
}
