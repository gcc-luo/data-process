package com.example.governance.assets;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Storage primitive. Callers must enforce authorization before invoking it. */
public final class DataSourceStore {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    public DataSourceStore(JdbcTemplate jdbc, CredentialCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    public long create(DataSourceDefinition definition, String password) throws GeneralSecurityException {
        if (definition == null || password == null || password.isEmpty()) throw new IllegalArgumentException("Connection and password are required");
        String encrypted = cipher.encrypt(password);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO data_sources(name,source_type,host,port,database_name,username,password_cipher) VALUES (?,?,?,?,?,?,?)", new String[] {"id"});
            statement.setString(1, definition.name()); statement.setString(2, definition.type().name());
            statement.setString(3, definition.host()); statement.setInt(4, definition.port());
            statement.setString(5, definition.database()); statement.setString(6, definition.username()); statement.setString(7, encrypted);
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("Data source ID was not generated");
        return id.longValue();
    }

    public boolean update(long id, DataSourceDefinition definition, String password) throws GeneralSecurityException {
        if (definition == null || (password != null && password.isEmpty())) throw new IllegalArgumentException("Invalid connection configuration");
        if (password == null) return jdbc.update("UPDATE data_sources SET name=?,source_type=?,host=?,port=?,database_name=?,username=? WHERE id=?", definition.name(), definition.type().name(), definition.host(), definition.port(), definition.database(), definition.username(), id) == 1;
        String encrypted = cipher.encrypt(password);
        return jdbc.update("UPDATE data_sources SET name=?,source_type=?,host=?,port=?,database_name=?,username=?,password_cipher=? WHERE id=?", definition.name(), definition.type().name(), definition.host(), definition.port(), definition.database(), definition.username(), encrypted, id) == 1;
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
                if (config == null || !config.isObject() || !java.util.Set.of("SQL", "SYNC").contains(type)) throw new IllegalStateException("Cannot check task references safely");
                var source = config.get("sourceId");
                if (source == null || !source.isIntegralNumber() || !source.canConvertToLong() || source.longValue() < 1) throw new IllegalStateException("Cannot check task references safely");
                var target = config.get("targetId");
                if ("SYNC".equals(type) && (target == null || !target.isIntegralNumber() || !target.canConvertToLong() || target.longValue() < 1)) throw new IllegalStateException("Cannot check task references safely");
                if (source.longValue() == id || ("SYNC".equals(type) && target.longValue() == id)) throw new IllegalStateException("Data source is referenced by a task version");
            } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot check task references safely", ex); }
        });
        return jdbc.update("DELETE FROM data_sources WHERE id=?", id) == 1;
    }

    public Optional<DataSourceDefinition> find(long id) {
        return jdbc.query("SELECT name,source_type,host,port,database_name,username FROM data_sources WHERE id=?", (rs, row) ->
                new DataSourceDefinition(rs.getString(1), DataSourceDefinition.Type.valueOf(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6)), id).stream().findFirst();
    }

    public record Entry(long id, DataSourceDefinition definition) {}
    public List<Entry> list() {
        return jdbc.query("SELECT id,name,source_type,host,port,database_name,username FROM data_sources ORDER BY id", (rs, row) ->
            new Entry(rs.getLong(1), new DataSourceDefinition(rs.getString(2), DataSourceDefinition.Type.valueOf(rs.getString(3)), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7))));
    }

    record LockedCredentials(DataSourceDefinition definition, String password) {}
    /** Current read after acquiring the source lock; avoids stale MySQL REPEATABLE READ snapshots. */
    LockedCredentials credentialsForLockedRefresh(long id) throws GeneralSecurityException {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source lock requires a transaction");
        var rows = jdbc.query("SELECT name,source_type,host,port,database_name,username,password_cipher FROM data_sources WHERE id=? FOR UPDATE", (rs, row) -> new java.util.AbstractMap.SimpleImmutableEntry<>(new DataSourceDefinition(rs.getString(1), DataSourceDefinition.Type.valueOf(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6)), rs.getString(7)), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown data source");
        return new LockedCredentials(rows.getFirst().getKey(), cipher.decrypt(rows.getFirst().getValue()));
    }
    String passwordForExecution(long id) throws GeneralSecurityException {
        String encrypted = jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id);
        return cipher.decrypt(encrypted);
    }
}
