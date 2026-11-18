package com.example.governance.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Records structured metadata only; never accepts SQL, credentials or payloads. */
@Service
public class AuditLog {
    private static final java.time.format.DateTimeFormatter DISPLAY_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;
    public AuditLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void loginFailure() {
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (NULL,CURRENT_TIMESTAMP,'auth.login','account','redacted','FAILURE')");
    }
    public void success(Authentication authentication, String action, String objectType, long objectId) {
        success(authentication, action, objectType, Long.toString(objectId));
    }
    public void success(Authentication authentication, String action, String objectType, String objectId) {
        if (!(authentication.getPrincipal() instanceof AccountPrincipal user)) throw new IllegalStateException("Authenticated account required");
        if (objectId == null || objectId.isBlank() || objectId.length() > 160) throw new IllegalArgumentException("Invalid audit object");
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (?,CURRENT_TIMESTAMP,?,?,?,'SUCCESS')", user.id(), action, objectType, objectId);
    }
    public record Event(long id, Long actorId, String username, java.time.LocalDateTime occurredAt, String action, String objectType, String objectId, String result, String occurredAtText, String actionLabel, String objectTypeLabel, String resultLabel) {}
    public java.util.List<Event> list(String action, Long beforeId, int limit) { return list(action, beforeId, 0, limit); }
    public java.util.List<Event> list(String action, Long beforeId, int offset, int limit) {
        if ((action != null && (action.isBlank() || action.length() > 160)) || (beforeId != null && beforeId < 1) || offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid audit filter");
        var sql = new StringBuilder("SELECT e.id,e.actor_id,u.username,e.occurred_at,e.action,e.object_type,e.object_id,e.result FROM audit_events e LEFT JOIN users u ON u.id=e.actor_id WHERE 1=1");
        var args = new java.util.ArrayList<Object>();
        if (action != null) { sql.append(" AND e.action=?"); args.add(action); }
        if (beforeId != null) { sql.append(" AND e.id<?"); args.add(beforeId); }
        sql.append(" ORDER BY e.id DESC LIMIT ").append(limit).append(" OFFSET ").append(offset);
        return jdbc.query(sql.toString(), (rs, row) -> {
            long actor = rs.getLong(2); Long id = rs.wasNull() ? null : actor;
            var occurredAt = rs.getTimestamp(4).toLocalDateTime();
            String actionCode = rs.getString(5), objectTypeCode = rs.getString(6), resultCode = rs.getString(8);
            return new Event(rs.getLong(1), id, rs.getString(3), occurredAt, actionCode, objectTypeCode, rs.getString(7), resultCode,
                    occurredAt.format(DISPLAY_TIME), AuditCatalog.actionLabel(actionCode), AuditCatalog.objectTypeLabel(objectTypeCode), AuditCatalog.resultLabel(resultCode));
        }, args.toArray());
    }
    public int count(String action) {
        if (action != null && (action.isBlank() || action.length() > 160)) throw new IllegalArgumentException("Invalid audit filter");
        Integer value = action == null ? jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class) : jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action=?", Integer.class, action);
        return value == null ? 0 : value;
    }
}
