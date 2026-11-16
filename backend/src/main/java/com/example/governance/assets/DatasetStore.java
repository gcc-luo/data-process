package com.example.governance.assets;

import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists catalog metadata only. This does not grant access to dataset rows. */
public final class DatasetStore {
    public record Field(String name, String dataType, boolean nullable) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public DatasetStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); }
    public long create(long sourceId, String name, String catalog, String schema, String table, List<Field> fields) {
        if (name == null || name.isBlank() || name.length() > 160 || table == null || table.isBlank() || table.length() > 255 || fields == null || fields.isEmpty() || fields.size() > 1000)
            throw new IllegalArgumentException("Invalid dataset metadata");
        var names = new HashSet<String>();
        for (Field field : fields) {
            if (field == null || field.name() == null || field.name().isBlank() || field.name().length() > 255 || field.dataType() == null || field.dataType().isBlank() || field.dataType().length() > 128 || !names.add(field.name()))
                throw new IllegalArgumentException("Invalid dataset fields");
        }
        return transaction.execute(status -> {
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO datasets(source_id,name,catalog_name,schema_name,table_name) VALUES (?,?,?,?,?)", new String[]{"id"});
                statement.setLong(1, sourceId); statement.setString(2, name); statement.setString(3, catalog); statement.setString(4, schema); statement.setString(5, table);
                return statement;
            }, key);
            Number id = key.getKey();
            if (id == null) throw new IllegalStateException("Dataset ID was not generated");
            for (int i = 0; i < fields.size(); i++) {
                var field = fields.get(i);
                jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (?,?,?,?,?)", id.longValue(), i, field.name(), field.dataType(), field.nullable());
            }
            return id.longValue();
        });
    }
    /** Adds discovered columns only; existing columns must be unchanged. New columns receive no grants. */
    public int refreshFields(long id, List<Field> discovered) {
        if (id < 1 || discovered == null || discovered.isEmpty() || discovered.size() > 1000) throw new IllegalArgumentException("Invalid dataset metadata");
        var names = new HashSet<String>();
        for (var field : discovered) {
            if (field == null || field.name() == null || field.name().isBlank() || field.name().length() > 255 || field.dataType() == null || field.dataType().isBlank() || field.dataType().length() > 128 || !names.add(field.name().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid dataset fields");
        }
        return transaction.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM datasets WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) throw new IllegalArgumentException("Unknown dataset");
            var current = jdbc.query("SELECT field_name,data_type,nullable,ordinal_no FROM dataset_fields WHERE dataset_id=? ORDER BY ordinal_no FOR UPDATE", (rs, row) -> new java.util.AbstractMap.SimpleImmutableEntry<>(new Field(rs.getString(1),rs.getString(2),rs.getBoolean(3)),rs.getInt(4)), id);
            var existing = current.stream().map(java.util.Map.Entry::getKey).toList(); var byName = new java.util.HashMap<String, Field>();
            for (var field : discovered) byName.put(field.name(), field);
            for (var field : existing) if (!field.equals(byName.get(field.name()))) throw new IllegalStateException("Existing field metadata changed");
            var known = new HashSet<String>(); existing.forEach(field -> known.add(field.name()));
            int ordinal = current.stream().mapToInt(java.util.Map.Entry::getValue).max().orElse(-1) + 1;
            int added = 0;
            for (var field : discovered) if (!known.contains(field.name())) {
                jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (?,?,?,?,?)", id, ordinal++, field.name(), field.dataType(), field.nullable()); added++;
            }
            return added;
        });
    }
    public java.util.Optional<Dataset> find(long id) {
        return jdbc.query("SELECT id,source_id,name,catalog_name,schema_name,table_name FROM datasets WHERE id=?", (rs, row) -> new Dataset(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),List.of()), id).stream().findFirst();
    }
    /** Removes only catalog metadata. Foreign keys reject removal while policies, reports or services refer to it. */
    public boolean delete(long id) {
        if (id < 1) throw new IllegalArgumentException("Invalid dataset ID");
        return jdbc.update("DELETE FROM datasets WHERE id=?", id) == 1;
    }
    public record Dataset(long id, long sourceId, String name, String catalog, String schema, String table, List<Field> fields) {}
    public List<Dataset> list() {
        var rows = jdbc.query("SELECT id,source_id,name,catalog_name,schema_name,table_name FROM datasets ORDER BY id", (rs, row) ->
                new Dataset(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), List.of()));
        return rows.stream().map(d -> new Dataset(d.id(), d.sourceId(), d.name(), d.catalog(), d.schema(), d.table(), fields(d.id()))).toList();
    }
    public List<Field> fields(long id) {
        return jdbc.query("SELECT field_name,data_type,nullable FROM dataset_fields WHERE dataset_id=? ORDER BY ordinal_no", (rs, row) -> new Field(rs.getString(1), rs.getString(2), rs.getBoolean(3)), id);
    }
}
