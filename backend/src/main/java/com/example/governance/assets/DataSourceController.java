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
    @PostMapping("/api/data-sources/{id}/test")
    Map<String, Boolean> test(@PathVariable long id) throws GeneralSecurityException {
        var definition = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Map.of("connected", inspector.test(definition, store.passwordForExecution(id)));
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
