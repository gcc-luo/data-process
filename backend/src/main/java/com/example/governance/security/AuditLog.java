package com.example.governance.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Records structured metadata plus request context; never accepts SQL, credentials or payloads. */
@Service
public class AuditLog {
    private static final java.time.format.DateTimeFormatter DISPLAY_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SELECT = "SELECT e.id,e.actor_id,u.username,e.occurred_at,e.action,e.object_type,e.object_id,e.result,e.ip,e.user_agent,e.message FROM audit_events e LEFT JOIN users u ON u.id=e.actor_id WHERE 1=1";
    private static final RowMapper<Event> MAPPER = (rs, row) -> {
        long actor = rs.getLong(2); Long id = rs.wasNull() ? null : actor;
        var occurredAt = rs.getTimestamp(4).toLocalDateTime();
        String actionCode = rs.getString(5), objectTypeCode = rs.getString(6), resultCode = rs.getString(8);
        return new Event(rs.getLong(1), id, rs.getString(3), occurredAt, actionCode, objectTypeCode, rs.getString(7), resultCode,
                occurredAt.format(DISPLAY_TIME), AuditCatalog.actionLabel(actionCode), AuditCatalog.objectTypeLabel(objectTypeCode), AuditCatalog.resultLabel(resultCode),
                rs.getString(9), rs.getString(10), rs.getString(11));
    };
    private final JdbcTemplate jdbc;
    public AuditLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void loginFailure(String username, String reason) {
        String message = truncate(reason + (username == null || username.isBlank() ? "" : "（账号：" + username + "）"), 255);
        String[] context = requestContext();
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result,ip,user_agent,message) VALUES (NULL,CURRENT_TIMESTAMP,'auth.login','account','redacted','FAILURE',?,?,?)", context[0], context[1], message);
    }

    public void success(Authentication authentication, String action, String objectType, long objectId) {
        success(authentication, action, objectType, Long.toString(objectId));
    }
    public void success(Authentication authentication, String action, String objectType, String objectId) {
        if (!(authentication.getPrincipal() instanceof AccountPrincipal user)) throw new IllegalStateException("Authenticated account required");
        if (objectId == null || objectId.isBlank() || objectId.length() > 160) throw new IllegalArgumentException("Invalid audit object");
        String[] context = requestContext();
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result,ip,user_agent,message) VALUES (?,CURRENT_TIMESTAMP,?,?,?,'SUCCESS',?,?, '')", user.id(), action, objectType, objectId, context[0], context[1]);
    }

    private static String[] requestContext() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            ip = ip == null || ip.isBlank() ? request.getRemoteAddr() : ip.split(",")[0].trim();
            String agent = request.getHeader("User-Agent");
            return new String[]{truncate(ip == null ? "" : ip, 45), truncate(agent == null ? "" : agent, 255)};
        }
        return new String[]{"", ""};
    }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    public record Event(long id, Long actorId, String username, java.time.LocalDateTime occurredAt, String action, String objectType, String objectId, String result, String occurredAtText, String actionLabel, String objectTypeLabel, String resultLabel, String ip, String userAgent, String message) {}

    /** Validated audit query criteria shared by list, count and export. */
    public record Filter(String action, String actor, String result, java.time.LocalDateTime from, java.time.LocalDateTime to, Long beforeId) {
        public Filter {
            if (action != null && (action.isBlank() || action.length() > 160)) throw new IllegalArgumentException("Invalid audit filter");
            if (actor != null && actor.length() > 160) throw new IllegalArgumentException("Invalid audit filter");
            if (result != null && !result.isBlank() && !java.util.Set.of("SUCCESS", "FAILURE").contains(result)) throw new IllegalArgumentException("Invalid audit filter");
            if (beforeId != null && beforeId < 1) throw new IllegalArgumentException("Invalid audit filter");
            if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        void apply(StringBuilder sql, java.util.List<Object> args) {
            if (action != null) { sql.append(" AND e.action=?"); args.add(action); }
            if (actor != null && !actor.isBlank()) { sql.append(" AND u.username=?"); args.add(actor.trim()); }
            if (result != null && !result.isBlank()) { sql.append(" AND e.result=?"); args.add(result); }
            if (from != null) { sql.append(" AND e.occurred_at>=?"); args.add(java.sql.Timestamp.valueOf(from)); }
            if (to != null) { sql.append(" AND e.occurred_at<=?"); args.add(java.sql.Timestamp.valueOf(to)); }
            if (beforeId != null) { sql.append(" AND e.id<?"); args.add(beforeId); }
        }
    }

    public java.util.List<Event> list(String action, Long beforeId, int limit) { return list(new Filter(action, null, null, null, null, beforeId), 0, limit); }
    public java.util.List<Event> list(String action, Long beforeId, int offset, int limit) { return list(new Filter(action, null, null, null, null, beforeId), offset, limit); }
    public java.util.List<Event> list(Filter filter, int offset, int limit) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid audit filter");
        var sql = new StringBuilder(SELECT);
        var args = new java.util.ArrayList<Object>();
        filter.apply(sql, args);
        sql.append(" ORDER BY e.id DESC LIMIT ").append(limit).append(" OFFSET ").append(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
    public int count(String action) { return count(new Filter(action, null, null, null, null, null)); }
    /** Export-sized read: pages internally so the interactive 100-row cap stays intact. */
    public java.util.List<Event> listAll(Filter filter, int cap) {
        if (cap < 1 || cap > 10000) throw new IllegalArgumentException("Invalid audit filter");
        var all = new java.util.ArrayList<Event>();
        for (int offset = 0; offset < cap; offset += 100) {
            var batch = list(filter, offset, Math.min(100, cap - offset));
            all.addAll(batch);
            if (batch.size() < 100) break;
        }
        return all;
    }
    public int count(Filter filter) {
        var sql = new StringBuilder("SELECT COUNT(*) FROM audit_events e LEFT JOIN users u ON u.id=e.actor_id WHERE 1=1");
        var args = new java.util.ArrayList<Object>();
        filter.apply(sql, args);
        Integer value = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return value == null ? 0 : value;
    }
}
