package com.example.governance.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A metadata row lock serializes each poll and persists the cursor only after target commit. */
public final class RealtimeJobStore {
    public record JobConfig(long sourceId, long targetId, IncrementalSyncExecutor.Config mapping, int intervalSeconds) {}
    public record View(long id, String name, String state, long sourceId, long targetId, String watermark, Long tie, long processed, LocalDateTime lastSuccess, String lastError, int intervalSeconds, Integer batchSize) {}
    @FunctionalInterface public interface Processor { IncrementalSyncExecutor.Result poll(JobConfig config, IncrementalSyncExecutor.Cursor cursor) throws Exception; }
    private record Row(long id, String name, String state, long source, long target, String config, int interval, long next, String watermark, Long tie, long processed, LocalDateTime lastSuccess, String error) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Processor processor;
    private final ObjectMapper mapper = new ObjectMapper();
    public RealtimeJobStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Processor processor) { this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.processor = processor; }
    public long create(String name, JobConfig config) {
        if (name == null || name.isBlank() || name.length() > 160 || config == null || config.sourceId() < 1 || config.targetId() < 1 || config.intervalSeconds() < 5 || config.intervalSeconds() > 3600) throw new IllegalArgumentException("Invalid realtime job");
        IncrementalSyncExecutor.validateConfig(config.mapping(), null);
        String json;
        try { json = mapper.writeValueAsString(config); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid realtime job"); }
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> { var st = connection.prepareStatement("INSERT INTO realtime_jobs(name,source_id,target_id,config,interval_seconds,state,next_poll_epoch,processed) VALUES (?,?,?,?,?,'STOPPED',0,0)", new String[]{"id"}); st.setString(1,name); st.setLong(2,config.sourceId()); st.setLong(3,config.targetId()); st.setString(4,json); st.setInt(5,config.intervalSeconds()); return st; }, key);
        if (key.getKey() == null) throw new IllegalStateException("Job ID unavailable"); return key.getKey().longValue();
    }
    /** Only operational controls may change; identity, mapping and cursor remain untouched. */
    public void updateSettings(long id, int intervalSeconds, int batchSize) {
        if (intervalSeconds < 5 || intervalSeconds > 3600 || batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid realtime settings");
        tx.executeWithoutResult(status -> {
            var row = lock(id);
            if (!java.util.Set.of("STOPPED", "ERROR").contains(row.state())) throw new IllegalStateException("Stop realtime job before editing");
            try {
                var node = mapper.readTree(row.config());
                if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode root) || !(root.get("mapping") instanceof com.fasterxml.jackson.databind.node.ObjectNode mapping)) throw new IllegalStateException("Invalid stored job configuration");
                root.put("intervalSeconds", intervalSeconds); mapping.put("batchSize", batchSize);
                var updated = mapper.treeToValue(root, JobConfig.class);
                if (updated.sourceId() != row.source() || updated.targetId() != row.target()) throw new IllegalStateException("Inconsistent data source metadata");
                var cursor = row.watermark() == null ? null : new IncrementalSyncExecutor.Cursor(row.watermark(), row.tie());
                IncrementalSyncExecutor.validateConfig(updated.mapping(), cursor);
                jdbc.update("UPDATE realtime_jobs SET interval_seconds=?,config=? WHERE id=?", intervalSeconds, mapper.writeValueAsString(root), id);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid stored job configuration", ex); }
        });
    }
    public void start(long id, Instant now) {
        if (now == null) throw new IllegalArgumentException("Time required");
        tx.executeWithoutResult(status -> { var row = lock(id); if (row.state().equals("RUNNING")) throw new IllegalStateException("Job already running"); jdbc.update("UPDATE realtime_jobs SET state='RUNNING',next_poll_epoch=?,last_error=NULL WHERE id=?", now.toEpochMilli(), id); });
    }
    public void stop(long id) { tx.executeWithoutResult(status -> { lock(id); jdbc.update("UPDATE realtime_jobs SET state='STOPPED' WHERE id=?", id); }); }
    public List<Long> due(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid due query");
        return jdbc.query("SELECT id FROM realtime_jobs WHERE state='RUNNING' AND next_poll_epoch<=? ORDER BY next_poll_epoch,id LIMIT " + limit + "", (rs, row) -> rs.getLong(1), now.toEpochMilli());
    }
    public boolean poll(long id, Instant now) {
        if (now == null) throw new IllegalArgumentException("Time required");
        return tx.execute(status -> {
            var row = lock(id);
            if (!row.state().equals("RUNNING") || row.next() > now.toEpochMilli()) return false;
            JobConfig config;
            try { config = mapper.readValue(row.config(), JobConfig.class); } catch (Exception ex) { throw new IllegalStateException("Invalid stored job configuration"); }
            var previous = row.watermark() == null ? null : new IncrementalSyncExecutor.Cursor(row.watermark(), row.tie());
            IncrementalSyncExecutor.Result result;
            try {
                result = processor.poll(config, previous);
                if (result == null || result.count() < 0 || result.count() > config.mapping().batchSize() || (result.count() == 0 && !java.util.Objects.equals(result.cursor(), previous)) || (result.count() > 0 && (result.cursor() == null || !after(previous, result.cursor(), config.mapping().watermarkType())))) throw new IllegalStateException("Invalid poll result");
                IncrementalSyncExecutor.validateConfig(config.mapping(), result.cursor());
            } catch (Exception ex) {
                jdbc.update("UPDATE realtime_jobs SET state='ERROR',last_error='同步失败，请检查连接、主键和任务配置' WHERE id=?", id);
                return false;
            }
            var next = result.cursor();
            jdbc.update("UPDATE realtime_jobs SET cursor_watermark=?,cursor_tie=?,processed=processed+?,last_success=CURRENT_TIMESTAMP,last_error=NULL,next_poll_epoch=? WHERE id=?", next == null ? null : next.watermark(), next == null ? null : next.tie(), result.count(), now.plusSeconds(row.interval()).toEpochMilli(), id);
            return true;
        });
    }
    public Optional<View> view(long id) { return rows("SELECT id,name,state,source_id,target_id,config,interval_seconds,next_poll_epoch,cursor_watermark,cursor_tie,processed,last_success,last_error FROM realtime_jobs WHERE id=?", id).stream().findFirst().map(this::view); }
    public List<View> list() { return rows("SELECT id,name,state,source_id,target_id,config,interval_seconds,next_poll_epoch,cursor_watermark,cursor_tie,processed,last_success,last_error FROM realtime_jobs ORDER BY id").stream().map(this::view).toList(); }
    private View view(Row row) {
        Integer batchSize = null;
        try { var batch = mapper.readTree(row.config()).path("mapping").path("batchSize"); if (batch.isInt()) batchSize = batch.intValue(); }
        catch (java.io.IOException | NullPointerException ignored) { /* Invalid saved configuration is not exposed. */ }
        return new View(row.id(), row.name(), row.state(), row.source(), row.target(), row.watermark(), row.tie(), row.processed(), row.lastSuccess(), row.error(), row.interval(), batchSize);
    }
    private Row lock(long id) { return rows("SELECT id,name,state,source_id,target_id,config,interval_seconds,next_poll_epoch,cursor_watermark,cursor_tie,processed,last_success,last_error FROM realtime_jobs WHERE id=? FOR UPDATE", id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown realtime job")); }
    private List<Row> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> { long tie = rs.getLong(10); Long nullableTie = rs.wasNull() ? null : tie; var last = rs.getTimestamp(12); return new Row(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getInt(7),rs.getLong(8),rs.getString(9),nullableTie,rs.getLong(11),last == null ? null : last.toLocalDateTime(),rs.getString(13)); }, args);
    }
    private static boolean after(IncrementalSyncExecutor.Cursor before, IncrementalSyncExecutor.Cursor after, String type) {
        if (before == null) return true;
        int comparison = "LONG".equals(type) ? Long.compare(Long.parseLong(after.watermark()), Long.parseLong(before.watermark())) : LocalDateTime.parse(after.watermark()).compareTo(LocalDateTime.parse(before.watermark()));
        return comparison > 0 || (comparison == 0 && after.tie() > before.tie());
    }
}
