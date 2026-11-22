package com.example.governance.assets;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Storage primitive. Callers must enforce authorization before invoking it. */
public final class DataSourceStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    public DataSourceStore(JdbcTemplate jdbc, CredentialCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    public long create(DataSourceDefinition definition, String password) throws GeneralSecurityException {
        if (definition == null || password == null || password.isEmpty()) throw new IllegalArgumentException("Connection and password are required");
        String encrypted = cipher.encrypt(password);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO data_sources(name,source_type,version_profile,host,port,database_name,username,driver_id,extra_options,password_cipher) VALUES (?,?,?,?,?,?,?,?,?,?)", new String[] {"id"});
            statement.setString(1, definition.name()); statement.setString(2, definition.type());
            statement.setString(3, definition.profileId());
            statement.setString(4, definition.host()); statement.setInt(5, definition.port());
            statement.setString(6, definition.database()); statement.setString(7, definition.username());
            if (definition.driverId() == null) statement.setNull(8, java.sql.Types.BIGINT); else statement.setLong(8, definition.driverId());
            statement.setString(9, writeOptions(definition.options())); statement.setString(10, encrypted);
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("Data source ID was not generated");
        return id.longValue();
    }

    public boolean update(long id, DataSourceDefinition definition, String password) throws GeneralSecurityException {
        if (definition == null || (password != null && password.isEmpty())) throw new IllegalArgumentException("Invalid connection configuration");
        if (password == null) return jdbc.update("UPDATE data_sources SET name=?,source_type=?,version_profile=?,host=?,port=?,database_name=?,username=?,driver_id=?,extra_options=? WHERE id=?", definition.name(), definition.type(), definition.profileId(), definition.host(), definition.port(), definition.database(), definition.username(), definition.driverId(), writeOptions(definition.options()), id) == 1;
        String encrypted = cipher.encrypt(password);
        return jdbc.update("UPDATE data_sources SET name=?,source_type=?,version_profile=?,host=?,port=?,database_name=?,username=?,driver_id=?,extra_options=?,password_cipher=? WHERE id=?", definition.name(), definition.type(), definition.profileId(), definition.host(), definition.port(), definition.database(), definition.username(), definition.driverId(), writeOptions(definition.options()), encrypted, id) == 1;
    }

    private static String writeOptions(java.util.Map<String, String> options) {
        try { return MAPPER.writeValueAsString(options == null ? java.util.Map.of() : options); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid connection options", ex); }
    }
    private static java.util.Map<String, String> readOptions(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try { return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {}); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Stored connection options are corrupt", ex); }
    }
    private static Long driverId(java.sql.ResultSet rs) throws java.sql.SQLException {
        long value = rs.getLong("driver_id"); return rs.wasNull() ? null : value;
    }
    private static DataSourceDefinition definition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DataSourceDefinition(rs.getString("name"), rs.getString("version_profile"), rs.getString("host"), rs.getInt("port"), rs.getString("database_name"), rs.getString("username"), driverId(rs), readOptions(rs.getString("extra_options")));
    }

    /** Call within the transaction that creates a task version or deletes a source. */
    public boolean lockSources(List<Long> ids) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source reference locks require a transaction");
        if (ids == null || ids.stream().anyMatch(id -> id == null || id < 1)) throw new IllegalArgumentException("Invalid data source IDs");
        for (Long id : ids.stream().distinct().sorted().toList()) {
            if (jdbc.queryForList("SELECT id FROM data_sources WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) return false;
        }
        return true;
    }
    /** Historical task versions reference sources inside JSON and must all remain resolvable. */
    public boolean deleteUnused(long id) {
        if (!lockSources(List.of(id))) return false;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        jdbc.query("SELECT t.task_type,v.configuration FROM processing_task_versions v JOIN processing_tasks t ON t.id=v.task_id FOR UPDATE", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            try {
                var config = mapper.readTree(rs.getString(2)); String type = rs.getString(1);
                if (config == null || !config.isObject()) throw new IllegalStateException("Cannot check task references safely");
                if (!"ORCH".equals(type)) throw new IllegalStateException("Cannot check task references safely");
                for (com.fasterxml.jackson.databind.JsonNode node : config.path("nodes")) {
                    com.fasterxml.jackson.databind.JsonNode sid = node.path("config").path("sourceId");
                    if (sid.isMissingNode() || sid.isNull()) continue;
                    if (!sid.isIntegralNumber() || !sid.canConvertToLong() || sid.longValue() < 1) throw new IllegalStateException("Cannot check task references safely");
                    if (sid.longValue() == id) throw new IllegalStateException("Data source is referenced by a task version");
                }
            } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot check task references safely", ex); }
        });
        return jdbc.update("DELETE FROM data_sources WHERE id=?", id) == 1;
    }

    public Optional<DataSourceDefinition> find(long id) {
        return jdbc.query("SELECT name,version_profile,host,port,database_name,username,driver_id,extra_options FROM data_sources WHERE id=?", (rs, row) -> definition(rs), id).stream().findFirst();
    }

    public record Entry(long id, DataSourceDefinition definition) {}
    public List<Entry> list() {
        return jdbc.query("SELECT id,name,version_profile,host,port,database_name,username,driver_id,extra_options FROM data_sources ORDER BY id", (rs, row) ->
            new Entry(rs.getLong("id"), definition(rs)));
    }

    record LockedCredentials(DataSourceDefinition definition, String password) {}
    /** Current read after acquiring the source lock; avoids stale MySQL REPEATABLE READ snapshots. */
    LockedCredentials credentialsForLockedRefresh(long id) throws GeneralSecurityException {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source lock requires a transaction");
        var rows = jdbc.query("SELECT name,version_profile,host,port,database_name,username,driver_id,extra_options,password_cipher FROM data_sources WHERE id=? FOR UPDATE", (rs, row) -> new java.util.AbstractMap.SimpleImmutableEntry<>(definition(rs), rs.getString("password_cipher")), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown data source");
        return new LockedCredentials(rows.getFirst().getKey(), cipher.decrypt(rows.getFirst().getValue()));
    }
    String passwordForExecution(long id) throws GeneralSecurityException {
        String encrypted = jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id);
        return cipher.decrypt(encrypted);
    }
}
