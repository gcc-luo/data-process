package com.example.governance.workflow;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists log lines with per-attempt numbering; caps at 2000 lines plus one truncation marker. */
public final class DbRunLogSink implements RunLogSink {
    private final JdbcTemplate jdbc;
    private final String ownerType;
    private final long ownerId;
    private final String nodeKey;
    private final int attempt;
    private final AtomicInteger lines = new AtomicInteger();
    public DbRunLogSink(JdbcTemplate jdbc, String ownerType, long ownerId, String nodeKey, int attempt) {
        this.jdbc = jdbc; this.ownerType = ownerType; this.ownerId = ownerId; this.nodeKey = nodeKey; this.attempt = attempt;
    }
    @Override public void log(String level, String message) {
        int line = lines.incrementAndGet();
        if (line > 2001) return;
        boolean marker = line == 2001;
        String text = marker ? "日志超过 2000 行，已截断" : message;
        if (text.length() > 2000) {
            int end = 2000;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, end);
        }
        String lv = marker ? "WARN" : (level == null ? "INFO" : level.length() > 10 ? level.substring(0, 10) : level);
        try {
            jdbc.update("INSERT INTO execution_logs(owner_type,owner_id,node_key,attempt_no,line_no,level,message) VALUES (?,?,?,?,?,?,?)",
                    ownerType, ownerId, nodeKey, attempt, line, lv, text);
        } catch (org.springframework.dao.DataAccessException ex) { /* 日志 best-effort：记日志失败不击穿业务执行 */ }
    }
}
