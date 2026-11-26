package com.example.governance.masterdata;

import com.example.governance.dataobject.ObjectStore;
import com.example.governance.security.AccountPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Platform-database history for record-level master-data changes. */
@Service
public class MasterDataHistoryStore {
    public record HistoryPage(List<Entry> rows, long total, int page, int size) {}
    public record Entry(long id, long objectId, String tableName, String operation, String status,
                        Map<String, Object> key, Map<String, Object> before, Map<String, Object> after,
                        Long actorId, String actorName, LocalDateTime occurredAt, String error) {}
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MasterDataHistoryStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Writes a pending event before touching the external database. */
    public long prepare(MasterDataService.ObjectDetail object, String operation, Map<String, Object> key, Authentication authentication) {
        if (object == null || !List.of("CREATE", "UPDATE", "DELETE", "IMPORT").contains(operation))
            throw new IllegalArgumentException("主数据历史操作无效");
        AccountPrincipal actor = principal(authentication);
        var identity = object.object().identity();
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO master_data_history(object_id,source_id,identity_hash,catalog_name,schema_name,table_name,operation_type,operation_status,record_key_json,record_key_hash,actor_id,actor_name) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?,?)", new String[]{"id"});
            statement.setLong(1, object.object().id());
            statement.setLong(2, identity.sourceId());
            statement.setString(3, ObjectStore.identityHash(identity));
            statement.setString(4, value(identity.catalog()));
            statement.setString(5, value(identity.schema()));
            statement.setString(6, identity.table());
            statement.setString(7, operation);
            statement.setString(8, encode(key));
            statement.setString(9, keyHash(key));
            statement.setLong(10, actor.id());
            statement.setString(11, actor.nickname() == null || actor.nickname().isBlank() ? actor.getUsername() : actor.nickname());
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("无法创建主数据历史记录");
        return holder.getKey().longValue();
    }

    public void succeed(long id, Map<String, Object> key, Map<String, Object> before, Map<String, Object> after) {
        int changed = jdbc.update("UPDATE master_data_history SET operation_status='SUCCESS',record_key_json=?,record_key_hash=?,before_json=?,after_json=? WHERE id=? AND operation_status='PENDING'",
                encode(key), keyHash(key), nullableJson(before), nullableJson(after), id);
        if (changed != 1) throw new IllegalStateException("主数据已变更，但历史记录状态未能确认，请刷新并核对版本记录");
    }

    public void fail(long id, Map<String, Object> key, String error) {
        int changed = jdbc.update("UPDATE master_data_history SET operation_status='FAILURE',record_key_json=?,record_key_hash=?,error_message=? WHERE id=? AND operation_status='PENDING'",
                encode(key), keyHash(key), truncate(error == null ? "操作失败" : error, 1000), id);
        if (changed != 1) throw new IllegalStateException("主数据操作失败，且历史记录状态未能确认");
    }

    public HistoryPage list(long objectId, Map<String, Object> key, int page, int size) {
        validatePage(objectId, page, size);
        String where = " FROM master_data_history WHERE object_id=?" + (key == null ? "" : " AND record_key_hash=?");
        Object[] args = key == null ? new Object[]{objectId} : new Object[]{objectId, keyHash(key)};
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args);
        List<Object> params = new ArrayList<>(List.of(args));
        params.add(size);
        params.add((page - 1) * size);
        var rows = jdbc.query("SELECT id,object_id,table_name,operation_type,operation_status,record_key_json,before_json,after_json,actor_id,actor_name,occurred_at,error_message" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", (rs, index) -> entry(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getObject(9, Long.class), rs.getString(10), rs.getTimestamp(11), rs.getString(12)), params.toArray());
        return new HistoryPage(List.copyOf(rows), total == null ? 0 : total, page, size);
    }

    public Entry find(long historyId) {
        if (historyId < 1) throw new IllegalArgumentException("版本记录不存在");
        return jdbc.query("SELECT id,object_id,table_name,operation_type,operation_status,record_key_json,before_json,after_json,actor_id,actor_name,occurred_at,error_message FROM master_data_history WHERE id=?",
                (rs, index) -> entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, Long.class), rs.getString(10), rs.getTimestamp(11), rs.getString(12)), historyId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("版本记录不存在"));
    }

    public static Map<String, Object> normalizedKey(Map<String, Object> key) {
        if (key == null) return null;
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(key)));
    }

    private Entry entry(long id, long objectId, String table, String operation, String status, String keyJson,
                       String beforeJson, String afterJson, Long actorId, String actor, Timestamp occurredAt, String error) {
        return new Entry(id, objectId, table, operation, status, decode(keyJson), decodeNullable(beforeJson), decodeNullable(afterJson), actorId,
                actor, occurredAt == null ? null : occurredAt.toLocalDateTime(), error);
    }
    private String encode(Map<String, Object> value) {
        try { return json.writeValueAsString(normalizedKey(value == null ? Map.of() : value)); }
        catch (Exception ex) { throw new IllegalArgumentException("无法保存主数据版本内容", ex); }
    }
    private String keyHash(Map<String, Object> value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("无法计算主键摘要", ex); }
    }
    private String nullableJson(Map<String, Object> value) { return value == null ? null : encode(value); }
    private Map<String, Object> decodeNullable(String value) { return value == null ? null : decode(value); }
    private Map<String, Object> decode(String value) {
        try { return Collections.unmodifiableMap(json.readValue(value, MAP)); }
        catch (Exception ex) { throw new IllegalStateException("主数据版本记录格式无效", ex); }
    }
    private static AccountPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal actor))
            throw new IllegalStateException("无法识别当前操作人");
        return actor;
    }
    private static void validatePage(long objectId, int page, int size) {
        if (objectId < 1 || page < 1 || page > 10000 || size < 1 || size > 100 || (long) (page - 1) * size > 100000)
            throw new IllegalArgumentException("版本记录分页参数无效");
    }
    private static String value(String value) { return value == null ? "" : value; }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
