package com.example.governance.serviceapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tokens are generated once, stored only as SHA-256 digests, and never included in listings. */
public final class ServiceCatalogStore {
    public record CallerSecret(long id, String token) {}
    public record CallerView(long id, String name, long userId, boolean enabled, int limitPerMinute) {}
    public record ServiceView(long id, long datasetId, String name, List<String> fields, int limit, String state) {}
    public record Call(long invocationId, long userId, long datasetId, List<String> fields, int limit) {}
    private record Caller(long id, long userId, boolean enabled, boolean userEnabled, int limit, long window, int count, String issuedVersion, String currentVersion) {}
    public static final class RateLimitException extends RuntimeException {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    public ServiceCatalogStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; tx = new TransactionTemplate(manager); }
    public long createService(long owner, long dataset, String name, List<String> fields, int limit) {
        name(name);
        if (fields == null || fields.isEmpty() || fields.size() > 100 || new HashSet<>(fields).size() != fields.size() || fields.stream().anyMatch(f -> f == null || f.isBlank()) || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid service configuration");
        var allowed = new HashSet<>(jdbc.queryForList("SELECT field_name FROM dataset_fields WHERE dataset_id=?", String.class, dataset));
        if (!allowed.containsAll(fields)) throw new IllegalArgumentException("Unknown dataset field");
        String json;
        try { json = mapper.writeValueAsString(fields); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid fields"); }
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> { var st = connection.prepareStatement("INSERT INTO data_services(creator_id,dataset_id,name,fields_json,row_limit,state,created_at) VALUES (?,?,?,?,?,'DRAFT',CURRENT_TIMESTAMP)", new String[]{"id"}); st.setLong(1, owner); st.setLong(2, dataset); st.setString(3, name); st.setString(4, json); st.setInt(5, limit); return st; }, key);
        return id(key);
    }
    public CallerSecret createCaller(String name, long userId, int limit) {
        name(name);
        if (limit < 1 || limit > 10000 || jdbc.queryForList("SELECT id FROM users WHERE id=? AND enabled=TRUE", Long.class, userId).isEmpty()) throw new IllegalArgumentException("Invalid caller");
        String userVersion = jdbc.queryForObject("SELECT security_version FROM users WHERE id=? AND enabled=TRUE", String.class, userId);
        byte[] bytes = new byte[32]; random.nextBytes(bytes); String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> { var st = connection.prepareStatement("INSERT INTO service_callers(name,user_id,token_hash,enabled,limit_per_minute,window_epoch,window_count,user_security_version) VALUES (?,?,?,TRUE,?,0,0,?)", new String[]{"id"}); st.setString(1, name); st.setLong(2, userId); st.setString(3, hash(token)); st.setInt(4, limit); st.setString(5, userVersion); return st; }, key);
        java.util.Arrays.fill(bytes, (byte) 0);
        return new CallerSecret(id(key), token);
    }
    public void submit(long id) {
        if (jdbc.update("UPDATE data_services SET state='PENDING' WHERE id=? AND state IN ('DRAFT','OFFLINE')", id) != 1) throw new IllegalStateException("Invalid service state");
    }
    public void withdraw(long id) { transition(id, "PUBLISHED", "OFFLINE"); }
    public void publish(long id) { transition(id, "PENDING", "PUBLISHED"); }
    private void transition(long id, String from, String to) {
        if (jdbc.update("UPDATE data_services SET state=? WHERE id=? AND state=?", to, id, from) != 1) throw new IllegalStateException("Invalid service state");
    }
    public void grant(long service, long caller) {
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM data_services WHERE id=? AND state='PUBLISHED' FOR UPDATE", Long.class, service).isEmpty() || jdbc.queryForList("SELECT id FROM service_callers WHERE id=? AND enabled=TRUE", Long.class, caller).isEmpty()) throw new IllegalArgumentException("Service or caller unavailable");
            jdbc.update("INSERT INTO service_grants(service_id,caller_id) VALUES (?,?)", service, caller);
        });
    }
    public record Grant(long serviceId, long callerId) {}
    public List<Grant> grants() { return jdbc.query("SELECT service_id,caller_id FROM service_grants ORDER BY service_id,caller_id", (rs, row) -> new Grant(rs.getLong(1), rs.getLong(2))); }
    public void revoke(long service, long caller) {
        if (jdbc.update("DELETE FROM service_grants WHERE service_id=? AND caller_id=?", service, caller) != 1) throw new IllegalArgumentException("Unknown grant");
    }
    public void disableCaller(long id) { if (jdbc.update("UPDATE service_callers SET enabled=FALSE WHERE id=?", id) != 1) throw new IllegalArgumentException("Unknown caller"); }
    public List<CallerView> callers() { return jdbc.query("SELECT id,name,user_id,enabled,limit_per_minute FROM service_callers ORDER BY id", (rs, row) -> new CallerView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), rs.getInt(5))); }
    public List<ServiceView> services() { return jdbc.query("SELECT id,dataset_id,name,fields_json,row_limit,state FROM data_services ORDER BY id", (rs, row) -> new ServiceView(rs.getLong(1), rs.getLong(2), rs.getString(3), fields(rs.getString(4)), rs.getInt(5), rs.getString(6))); }
    public record Usage(long serviceId, long callerId, long total, long successful, long failed) {}
    public List<Usage> usage() {
        return jdbc.query("SELECT service_id,caller_id,COUNT(*),SUM(CASE WHEN outcome='SUCCESS' THEN 1 ELSE 0 END),SUM(CASE WHEN outcome='FAILED' THEN 1 ELSE 0 END) FROM service_invocations GROUP BY service_id,caller_id ORDER BY service_id,caller_id", (rs, row) -> new Usage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
    }
    public Call begin(String token, long serviceId, Instant now) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || now == null) throw new SecurityException("Access denied");
        return tx.execute(status -> {
            var callers = jdbc.query("SELECT c.id,c.user_id,c.enabled,u.enabled,c.limit_per_minute,c.window_epoch,c.window_count,c.user_security_version,u.security_version FROM service_callers c JOIN users u ON u.id=c.user_id WHERE c.token_hash=? FOR UPDATE", (rs, row) -> new Caller(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), rs.getBoolean(4), rs.getInt(5), rs.getLong(6), rs.getInt(7), rs.getString(8), rs.getString(9)), hash(token));
            if (callers.isEmpty() || !callers.get(0).enabled() || !callers.get(0).userEnabled() || !callers.get(0).issuedVersion().equals(callers.get(0).currentVersion())) throw new SecurityException("Access denied");
            var caller = callers.get(0);
            var services = jdbc.query("SELECT s.id,s.dataset_id,s.name,s.fields_json,s.row_limit,s.state FROM data_services s JOIN service_grants g ON g.service_id=s.id WHERE s.id=? AND g.caller_id=? AND s.state='PUBLISHED'", (rs, row) -> new ServiceView(rs.getLong(1), rs.getLong(2), rs.getString(3), fields(rs.getString(4)), rs.getInt(5), rs.getString(6)), serviceId, caller.id());
            if (services.isEmpty()) throw new SecurityException("Access denied");
            long minute = Math.floorDiv(now.toEpochMilli(), 60000); int count = minute > caller.window() ? 0 : caller.count();
            if (count >= caller.limit()) throw new RateLimitException();
            jdbc.update("UPDATE service_callers SET window_epoch=?,window_count=? WHERE id=?", Math.max(minute, caller.window()), count + 1, caller.id());
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> { var st = connection.prepareStatement("INSERT INTO service_invocations(service_id,caller_id,started_at,outcome) VALUES (?,?,CURRENT_TIMESTAMP,'PENDING')", new String[]{"id"}); st.setLong(1, serviceId); st.setLong(2, caller.id()); return st; }, key);
            var service = services.get(0);
            return new Call(id(key), caller.userId(), service.datasetId(), service.fields(), service.limit());
        });
    }
    public void complete(long invocationId, boolean success, long durationMs) {
        if (durationMs < 0 || jdbc.update("UPDATE service_invocations SET outcome=?,duration_ms=? WHERE id=? AND outcome='PENDING'", success ? "SUCCESS" : "FAILED", durationMs, invocationId) != 1) throw new IllegalStateException("Invocation unavailable");
    }
    private List<String> fields(String json) { try { return List.copyOf(mapper.readValue(json, new TypeReference<List<String>>() {})); } catch (Exception ex) { throw new IllegalStateException("Invalid stored service fields"); } }
    private static void name(String value) { if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("Invalid name"); }
    private static long id(GeneratedKeyHolder key) { if (key.getKey() == null) throw new IllegalStateException("ID unavailable"); return key.getKey().longValue(); }
    private static String hash(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
}
