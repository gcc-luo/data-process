package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Mutable draft with revision optimistic locking; publish freezes an immutable version snapshot. */
public final class OrchDraftStore {
    public static final class RevisionConflictException extends RuntimeException {
        private final long currentRevision;
        public RevisionConflictException(long currentRevision) { super("草稿已被他人修改"); this.currentRevision = currentRevision; }
        public long currentRevision() { return currentRevision; }
    }
    public record Draft(long taskId, long revision, String definition, String updatedBy, LocalDateTime updatedAt) {}
    public record VersionView(int version, String comment, String publishedBy, LocalDateTime publishedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrchDraftStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }

    public void initDraft(long taskId, String definition, String actor) {
        jdbc.update("INSERT INTO task_drafts(task_id,revision,definition,updated_by,updated_at) VALUES (?,1,?,?,CURRENT_TIMESTAMP)", taskId, definition, actor);
    }

    public Optional<Draft> find(long taskId) {
        return jdbc.query("SELECT task_id,revision,definition,updated_by,updated_at FROM task_drafts WHERE task_id=?",
                (rs, row) -> new Draft(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toLocalDateTime()), taskId)
                .stream().findFirst();
    }

    /** Shape-gated draft save; expectedRevision mismatch raises {@link RevisionConflictException}. */
    public long saveDraft(long taskId, long expectedRevision, JsonNode definition, String actor) {
        String json;
        try {
            if (definition == null || !definition.isObject()) throw new IllegalArgumentException("编排定义必须是 JSON 对象");
            json = mapper.writeValueAsString(definition);
            if (json.length() > OrchDefinition.MAX_DEFINITION_CHARS) throw new IllegalArgumentException("编排定义过大");
            OrchDefinition.parse(definition);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("编排定义序列化失败"); }
        Long revision = tx.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, taskId).isEmpty())
                throw new IllegalArgumentException("任务不存在");
            var rows = jdbc.query("SELECT revision FROM task_drafts WHERE task_id=? FOR UPDATE", (rs, row) -> rs.getLong(1), taskId);
            long current = rows.isEmpty() ? 0 : rows.get(0);
            if (current != expectedRevision) throw new RevisionConflictException(current);
            jdbc.update("UPDATE task_drafts SET revision=?,definition=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE task_id=?",
                    current + 1, json, actor, taskId);
            return current + 1;
        });
        if (revision == null) throw new IllegalStateException("草稿保存失败");
        return revision;
    }

    /** Freeze the current draft as an immutable published version; revision re-checked under the task row lock. */
    public int publish(long taskId, long expectedRevision, String comment, String actor, TaskVersionStore tasks) {
        Integer version = tx.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, taskId).isEmpty())
                throw new IllegalArgumentException("任务不存在");
            var rows = jdbc.query("SELECT revision,definition FROM task_drafts WHERE task_id=? FOR UPDATE",
                    (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)}, taskId);
            if (rows.isEmpty()) throw new IllegalArgumentException("任务没有草稿");
            long current = (Long) rows.get(0)[0];
            if (current != expectedRevision) throw new RevisionConflictException(current);
            return tasks.addVersion(taskId, (String) rows.get(0)[1], comment, actor);
        });
        if (version == null) throw new IllegalStateException("发布失败");
        return version;
    }

    public List<VersionView> versions(long taskId) {
        return jdbc.query("SELECT version,comment,published_by,published_at FROM processing_task_versions WHERE task_id=? ORDER BY version DESC",
                (rs, row) -> new VersionView(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime()), taskId);
    }

    /** Definition templates for new tasks. */
    public static String template(String kind) {
        return switch (kind == null ? "BLANK" : kind) {
            case "SQL" -> """
                    {"schemaVersion":1,"variables":[],"nodes":[{"id":"n_sql","component":"transform.sql","name":"SQL 脚本","description":"","enabled":true,"position":{"x":160,"y":160},"timeoutSeconds":300,"config":{"sourceId":null,"sqlMode":"DML","sql":"","parameters":[]}}],"edges":[],"layout":{"zoom":1,"viewport":{"x":0,"y":0}}}""";
            case "SYNC" -> """
                    {"schemaVersion":1,"variables":[],"nodes":[{"id":"n_source","component":"source.mysql","name":"MySQL 来源","description":"","enabled":true,"position":{"x":120,"y":160},"timeoutSeconds":300,"config":{"sourceId":null,"readMode":"TABLE","table":"","fetchSize":500}},{"id":"n_sink","component":"sink.mysql","name":"MySQL 写入","description":"","enabled":true,"position":{"x":520,"y":160},"timeoutSeconds":300,"config":{"sourceId":null,"table":"","writeMode":"APPEND","batchSize":500}}],"edges":[{"id":"e_1","from":"n_source","fromPort":"out","to":"n_sink","toPort":"in","kind":"DATA"}],"layout":{"zoom":1,"viewport":{"x":0,"y":0}}}""";
            default -> "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[],\"edges\":[],\"layout\":{\"zoom\":1,\"viewport\":{\"x\":0,\"y\":0}}}";
        };
    }
}