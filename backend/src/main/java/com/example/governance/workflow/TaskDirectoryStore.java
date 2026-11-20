package com.example.governance.workflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Task directory tree stored as flat parent-linked rows; deletion is conservative. */
public final class TaskDirectoryStore {
    public record Directory(long id, Long parentId, String name, int sortOrder) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public TaskDirectoryStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); }

    public List<Directory> list() {
        return jdbc.query("SELECT id,parent_id,name,sort_order FROM task_directories ORDER BY sort_order,id",
                (rs, row) -> new Directory(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3), rs.getInt(4)));
    }

    public long create(String name, Long parentId) {
        String clean = validName(name);
        if (parentId != null && jdbc.queryForList("SELECT id FROM task_directories WHERE id=?", Long.class, parentId).isEmpty()) throw new IllegalArgumentException("Unknown parent directory");
        Integer max = parentId == null
                ? jdbc.queryForObject("SELECT COALESCE(MAX(sort_order),-1) FROM task_directories WHERE parent_id IS NULL", Integer.class)
                : jdbc.queryForObject("SELECT COALESCE(MAX(sort_order),-1) FROM task_directories WHERE parent_id=?", Integer.class, parentId);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO task_directories(parent_id,name,sort_order) VALUES (?,?,?)", new String[]{"id"});
            if (parentId == null) statement.setNull(1, java.sql.Types.BIGINT); else statement.setLong(1, parentId);
            statement.setString(2, clean); statement.setInt(3, max + 1); return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Directory ID was not generated");
        return key.getKey().longValue();
    }

    public void rename(long id, String name) {
        String clean = validName(name);
        if (jdbc.update("UPDATE task_directories SET name=? WHERE id=?", clean, id) != 1) throw new java.util.NoSuchElementException("Unknown directory");
    }

    public void delete(long id) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM task_directories WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) throw new IllegalArgumentException("Unknown directory");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM task_directories WHERE parent_id=?", Integer.class, id) > 0
                    || jdbc.queryForObject("SELECT COUNT(*) FROM processing_tasks WHERE directory_id=?", Integer.class, id) > 0)
                throw new IllegalStateException("目录包含子目录或任务，不能删除");
            jdbc.update("DELETE FROM task_directories WHERE id=?", id);
        });
    }

    /** Replaces parent links and order for the whole tree in one transaction; rejects cycles. */
    public void sort(List<Directory> items) {
        if (items == null) throw new IllegalArgumentException("Invalid sort payload");
        transaction.executeWithoutResult(status -> {
            Set<Long> existing = new HashSet<>(jdbc.queryForList("SELECT id FROM task_directories FOR UPDATE", Long.class));
            Map<Long, Directory> byId = new HashMap<>();
            for (Directory item : items) {
                if (!existing.contains(item.id()) || byId.put(item.id(), item) != null) throw new IllegalArgumentException("Invalid directory sort payload");
            }
            if (!byId.keySet().equals(existing)) throw new IllegalArgumentException("Sort payload must cover all directories");
            for (Directory item : items) {
                Set<Long> seen = new HashSet<>();
                Directory current = item;
                while (current.parentId() != null) {
                    if (!seen.add(current.id())) throw new IllegalStateException("目录不能移入自身子孙");
                    current = byId.get(current.parentId());
                    if (current == null) throw new IllegalArgumentException("Unknown parent directory");
                }
            }
            for (Directory item : items) {
                jdbc.update("UPDATE task_directories SET parent_id=?,sort_order=? WHERE id=?", item.parentId(), item.sortOrder(), item.id());
            }
        });
    }

    private static String validName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 120) throw new IllegalArgumentException("Invalid directory name");
        return clean;
    }
}
