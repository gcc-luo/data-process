package com.example.governance.query;

import com.example.governance.security.AccessPolicy;
import com.example.governance.security.DatasetAccess;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Loads authorization solely from server-side roles, organization and dataset policy. */
public final class DatasetPolicyStore {
    public record Resolved(long sourceId, DatasetQueryEngine.Dataset dataset, DatasetQueryEngine.Policy policy) {}
    private record DatasetRow(long sourceId, String catalog, String schema, String table, String departmentField) {}
    private record UserRow(long department, boolean enabled) {}
    private record Rule(long role, AccessPolicy.Scope scope) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;
    public DatasetPolicyStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.snapshot = new TransactionTemplate(manager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public void configure(long datasetId, String departmentField, long roleId, AccessPolicy.Scope scope, Map<String, DatasetAccess.Field> fields) {
        if (departmentField == null || departmentField.isBlank() || scope == null || fields == null || fields.isEmpty() || fields.size() > 1000 || fields.values().stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Invalid dataset policy");
        tx.executeWithoutResult(status -> {
            var datasets = jdbc.query("SELECT department_field FROM datasets WHERE id=? FOR UPDATE", (rs, row) -> java.util.Optional.ofNullable(rs.getString(1)), datasetId);
            if (datasets.isEmpty()) throw new IllegalArgumentException("Unknown dataset");
            String previousField = datasets.get(0).orElse(null);
            if (previousField != null && !previousField.equals(departmentField) && !jdbc.queryForList("SELECT role_id FROM dataset_role_rules WHERE dataset_id=? AND role_id<>?", Long.class, datasetId, roleId).isEmpty()) throw new IllegalStateException("Other roles depend on the department field");
            var types = jdbc.queryForList("SELECT data_type FROM dataset_fields WHERE dataset_id=? AND field_name=?", String.class, datasetId, departmentField);
            if (types.isEmpty() || !types.get(0).toUpperCase(java.util.Locale.ROOT).matches("(.*INT.*|NUMERIC|DECIMAL|NUMBER)")) throw new IllegalArgumentException("Department field must contain numeric department IDs");
            var existing = new HashSet<>(jdbc.queryForList("SELECT field_name FROM dataset_fields WHERE dataset_id=?", String.class, datasetId));
            if (!existing.containsAll(fields.keySet())) throw new IllegalArgumentException("Unknown dataset field");
            if (jdbc.queryForList("SELECT id FROM roles WHERE id=?", Long.class, roleId).isEmpty()) throw new IllegalArgumentException("Unknown role");
            jdbc.update("UPDATE datasets SET department_field=? WHERE id=?", departmentField, datasetId);
            jdbc.update("DELETE FROM dataset_role_fields WHERE dataset_id=? AND role_id=?", datasetId, roleId);
            jdbc.update("DELETE FROM dataset_role_rules WHERE dataset_id=? AND role_id=?", datasetId, roleId);
            jdbc.update("INSERT INTO dataset_role_rules(dataset_id,role_id,scope) VALUES (?,?,?)", datasetId, roleId, scope.name());
            fields.forEach((name, mode) -> jdbc.update("INSERT INTO dataset_role_fields(dataset_id,role_id,field_name,access_mode) VALUES (?,?,?,?)", datasetId, roleId, name, mode.name()));
        });
    }
    public record RolePolicy(long roleId, AccessPolicy.Scope scope, Map<String, DatasetAccess.Field> fields) {}
    public record PolicyView(String departmentField, List<RolePolicy> rules) {}
    public PolicyView listPolicies(long datasetId) {
        return tx.execute(status -> {
            var datasets = jdbc.query("SELECT department_field FROM datasets WHERE id=? FOR UPDATE", (rs, row) -> java.util.Optional.ofNullable(rs.getString(1)), datasetId);
            if (datasets.isEmpty()) throw new IllegalArgumentException("Unknown dataset");
            var rules = jdbc.query("SELECT role_id,scope FROM dataset_role_rules WHERE dataset_id=? ORDER BY role_id", (rs, row) -> new Rule(rs.getLong(1), AccessPolicy.Scope.valueOf(rs.getString(2))), datasetId);
            var result = new java.util.ArrayList<RolePolicy>();
            for (var rule : rules) {
                var fields = new java.util.LinkedHashMap<String, DatasetAccess.Field>();
                jdbc.query("SELECT field_name,access_mode FROM dataset_role_fields WHERE dataset_id=? AND role_id=? ORDER BY field_name", (org.springframework.jdbc.core.RowCallbackHandler) rs -> fields.put(rs.getString(1), DatasetAccess.Field.valueOf(rs.getString(2))), datasetId, rule.role());
                result.add(new RolePolicy(rule.role(), rule.scope(), Map.copyOf(fields)));
            }
            return new PolicyView(datasets.get(0).orElse(null), List.copyOf(result));
        });
    }
    public boolean revoke(long datasetId, long roleId) {
        if (datasetId < 1 || roleId < 1) throw new IllegalArgumentException("Invalid dataset or role");
        return tx.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM datasets WHERE id=? FOR UPDATE", Long.class, datasetId).isEmpty()) throw new IllegalArgumentException("Unknown dataset");
            jdbc.update("DELETE FROM dataset_role_fields WHERE dataset_id=? AND role_id=?", datasetId, roleId);
            return jdbc.update("DELETE FROM dataset_role_rules WHERE dataset_id=? AND role_id=?", datasetId, roleId) == 1;
        });
    }
    public record Accessible(long id, String name, List<String> fields) {}
    public List<Accessible> accessible(long userId) {
        var rows = jdbc.query("SELECT DISTINCT d.id,d.name FROM datasets d JOIN dataset_role_rules r ON r.dataset_id=d.id JOIN user_roles ur ON ur.role_id=r.role_id JOIN role_permissions p ON p.role_id=r.role_id AND p.permission='datasource.query.read' JOIN users u ON u.id=ur.user_id AND u.enabled=TRUE WHERE u.id=? ORDER BY d.id", (rs, row) -> new Accessible(rs.getLong(1), rs.getString(2), List.of()), userId);
        var result = new java.util.ArrayList<Accessible>();
        for (var row : rows) {
            try { result.add(new Accessible(row.id(), row.name(), resolve(userId, row.id()).policy().fields().keySet().stream().sorted().toList())); }
            catch (SecurityException ignored) { /* Policy may have been revoked concurrently. */ }
        }
        return List.copyOf(result);
    }
    /** Permissions are a consistent snapshot; a query already started may finish after revocation. */
    public Resolved resolve(long userId, long datasetId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            Integer isolation = org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (isolation == null || (isolation != org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ && isolation != org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE)) throw new IllegalStateException("Policy resolution requires repeatable-read isolation");
        }
        return snapshot.execute(status -> resolveSnapshot(userId, datasetId));
    }
    private Resolved resolveSnapshot(long userId, long datasetId) {
        var datasets = jdbc.query("SELECT source_id,catalog_name,schema_name,table_name,department_field FROM datasets WHERE id=?", (rs, row) -> new DatasetRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), datasetId);
        var users = jdbc.query("SELECT department_id,enabled FROM users WHERE id=?", (rs, row) -> new UserRow(rs.getLong(1), rs.getBoolean(2)), userId);
        if (datasets.isEmpty() || users.isEmpty() || !users.get(0).enabled() || datasets.get(0).departmentField() == null) throw new SecurityException("Dataset access denied");
        var rules = jdbc.query("SELECT DISTINCT r.role_id,r.scope FROM dataset_role_rules r JOIN user_roles ur ON ur.role_id=r.role_id JOIN role_permissions p ON p.role_id=r.role_id AND p.permission='datasource.query.read' WHERE r.dataset_id=? AND ur.user_id=?", (rs, row) -> new Rule(rs.getLong(1), AccessPolicy.Scope.valueOf(rs.getString(2))), datasetId, userId);
        if (rules.isEmpty()) throw new SecurityException("Dataset access denied");
        var departments = jdbc.query("SELECT id,parent_id FROM departments", (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2), rs.wasNull() ? 0 : 1});
        Set<String> ids = new HashSet<>(); Map<String, String> parents = new HashMap<>();
        for (long[] item : departments) { ids.add(Long.toString(item[0])); if (item[2] == 1) parents.put(Long.toString(item[0]), Long.toString(item[1])); }
        var policy = new AccessPolicy(ids, parents, Map.of());
        var user = new AccessPolicy.User(Long.toString(userId), Long.toString(users.get(0).department()), Set.of(), true);
        Set<Long> allowedDepartments = new HashSet<>(); Map<String, DatasetAccess.Field> allowedFields = new HashMap<>(); Map<String, Map<Long, DatasetAccess.Field>> grants = new HashMap<>();
        for (var rule : rules) {
            Set<Long> ruleDepartments = new HashSet<>();
            policy.departments(user, rule.scope()).forEach(id -> ruleDepartments.add(Long.parseLong(id)));
            allowedDepartments.addAll(ruleDepartments);
            var permissions = jdbc.query("SELECT field_name,access_mode FROM dataset_role_fields WHERE dataset_id=? AND role_id=?", (rs, row) -> Map.entry(rs.getString(1), DatasetAccess.Field.valueOf(rs.getString(2))), datasetId, rule.role());
            for (var permission : permissions) {
                allowedFields.merge(permission.getKey(), permission.getValue(), DatasetPolicyStore::mostPermissive);
                var byDepartment = grants.computeIfAbsent(permission.getKey(), key -> new HashMap<>());
                for (Long department : ruleDepartments) byDepartment.merge(department, permission.getValue(), DatasetPolicyStore::mostPermissive);
            }
        }
        if (allowedDepartments.isEmpty() || allowedFields.isEmpty()) throw new SecurityException("Dataset access denied");
        var dataset = datasets.get(0);
        var fieldNames = Set.copyOf(jdbc.queryForList("SELECT field_name FROM dataset_fields WHERE dataset_id=?", String.class, datasetId));
        return new Resolved(dataset.sourceId(), new DatasetQueryEngine.Dataset(dataset.catalog(), dataset.schema(), dataset.table(), dataset.departmentField(), fieldNames), new DatasetQueryEngine.Policy(allowedDepartments, allowedFields, grants));
    }
    private static DatasetAccess.Field mostPermissive(DatasetAccess.Field a, DatasetAccess.Field b) {
        return a == DatasetAccess.Field.FULL || b == DatasetAccess.Field.FULL ? DatasetAccess.Field.FULL : DatasetAccess.Field.MASKED;
    }
}
