package com.example.governance.assets;

import java.security.GeneralSecurityException;
import com.example.governance.security.AuditLog;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DataSourceController {
    private final DataSourceStore store;
    private final DatabaseInspector inspector;
    private final AuditLog audit;
    private final com.example.governance.assets.driver.ConnectionFactory factory;
    private final com.example.governance.assets.driver.DatabaseProfileRegistry registry;
    public DataSourceController(DataSourceStore store, DatabaseInspector inspector, AuditLog audit, com.example.governance.assets.driver.ConnectionFactory factory, com.example.governance.assets.driver.DatabaseProfileRegistry registry) { this.store = store; this.inspector = inspector; this.audit = audit; this.factory = factory; this.registry = registry; }
    record CreateRequest(String name, String profileId, String host, int port, String database, String username, String password, Long driverId, Map<String, String> options) {}
    record Item(long id, String name, String type, String profileId, String profileName, String host, int port, String database, String username, Long driverId, Map<String, String> options) {}
    @GetMapping("/api/data-sources/profiles")
    List<com.example.governance.assets.driver.DatabaseProfileRegistry.ProfileView> profiles() { return registry.views(); }
    @GetMapping("/api/data-sources")
    List<Item> list() {
        return store.list().stream().map(entry -> {
            var d = entry.definition();
            String profileName;
            try { profileName = registry.resolve(d.profileId()).displayName(); } catch (IllegalArgumentException unknown) { profileName = d.profileId(); }
            return new Item(entry.id(), d.name(), d.type(), d.profileId(), profileName, d.host(), d.port(), d.database(), d.username(), d.driverId(), d.options());
        }).toList();
    }
    @PostMapping("/api/data-sources")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request, Authentication authentication) throws GeneralSecurityException {
        try {
            var definition = new DataSourceDefinition(request.name(), request.profileId(), request.host(), request.port(), request.database(), request.username(), request.driverId(), request.options());
            factory.validate(definition);
            long id = store.create(definition, request.password());
            audit.success(authentication, "datasource.create", "datasource", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "name", definition.name()));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data source configuration"));
        }
    }
    @PutMapping("/api/data-sources/{id}") @Transactional
    ResponseEntity<Void> update(@PathVariable long id, @RequestBody CreateRequest request, Authentication authentication) throws GeneralSecurityException {
        try {
            var definition = new DataSourceDefinition(request.name(), request.profileId(), request.host(), request.port(), request.database(), request.username(), request.driverId(), request.options());
            factory.validate(definition);
            if (!store.update(id, definition, request.password())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(authentication, "datasource.update", "datasource", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | NullPointerException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid data source configuration"); }
    }
    @DeleteMapping("/api/data-sources/{id}") @Transactional
    ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        try {
            if (!store.deleteUnused(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(authentication, "datasource.delete", "datasource", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException | org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Data source is referenced or references cannot be verified");
        }
    }
    record BatchDeleteRequest(List<Long> ids) {}
    @PostMapping("/api/data-sources/batch-delete") @Transactional
    ResponseEntity<Map<String, Object>> batchDelete(@RequestBody BatchDeleteRequest request, Authentication authentication) {
        List<Long> ids = request == null ? null : request.ids();
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1))
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data source IDs"));
        List<Long> deleted = new java.util.ArrayList<>();
        List<Map<String, Object>> failed = new java.util.ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            try {
                if (!store.deleteUnused(id)) { failed.add(Map.of("id", id, "reason", "NOT_FOUND")); continue; }
                audit.success(authentication, "datasource.delete", "datasource", id);
                deleted.add(id);
            } catch (IllegalStateException | org.springframework.dao.DataIntegrityViolationException ex) {
                failed.add(Map.of("id", id, "reason", "REFERENCED"));
            }
        }
        return ResponseEntity.ok(Map.of("deleted", deleted, "failed", failed));
    }
    @PostMapping("/api/data-sources/{id}/test")
    Map<String, Object> test(@PathVariable long id) throws GeneralSecurityException {
        var definition = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return testBody(inspector.probe(definition, store.passwordForExecution(id)));
    }
    record DraftTestRequest(Long id, String name, String profileId, String host, int port, String database, String username, String password, Long driverId, Map<String, String> options) {}
    /** Tests an unsaved form configuration; a blank password with an existing id reuses the stored credential. */
    @PostMapping("/api/data-sources/test-config")
    Map<String, Object> testConfig(@RequestBody DraftTestRequest request) throws GeneralSecurityException {
        String password = request.password();
        if (password == null || password.isEmpty()) {
            if (request.id() == null) return Map.of("connected", false, "error", "请填写密码后再测试连接");
            if (store.find(request.id()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            password = store.passwordForExecution(request.id());
        }
        String name = request.name() == null || request.name().isBlank() ? "connection-test" : request.name();
        DataSourceDefinition definition;
        try {
            definition = new DataSourceDefinition(name, request.profileId(), request.host(), request.port(), request.database(), request.username(), request.driverId(), request.options());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Map.of("connected", false, "error", "连接配置无效：" + ex.getMessage());
        }
        return testBody(inspector.probe(definition, password));
    }
    private static Map<String, Object> testBody(DatabaseInspector.TestResult result) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("connected", result.connected());
        if (result.error() != null) body.put("error", result.error());
        return body;
    }
    @GetMapping("/api/data-sources/{id}/tables")
    List<DatabaseInspector.Table> tables(@PathVariable long id, @RequestParam(required=false) String catalog,
            @RequestParam(required=false) String schema) throws GeneralSecurityException, DatabaseInspector.InspectionException {
        var definition = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return inspector.tables(definition, store.passwordForExecution(id), catalog, schema);
    }
    @GetMapping("/api/data-sources/{id}/columns")
    List<DatabaseInspector.Column> columns(@PathVariable long id, @RequestParam(required=false) String catalog,
            @RequestParam(required=false) String schema, @RequestParam String table) throws GeneralSecurityException, DatabaseInspector.InspectionException {
        var definition = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return inspector.columns(definition, store.passwordForExecution(id), catalog, schema, table);
    }
}
