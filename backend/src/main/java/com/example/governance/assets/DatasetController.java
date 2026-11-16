package com.example.governance.assets;

import java.security.GeneralSecurityException;
import com.example.governance.security.AuditLog;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DatasetController {
    private final DataSourceStore sources;
    private final DatasetStore datasets;
    private final DatabaseInspector inspector;
    private final AuditLog audit;
    public DatasetController(DataSourceStore sources, DatasetStore datasets, DatabaseInspector inspector, AuditLog audit) {
        this.sources = sources; this.datasets = datasets; this.inspector = inspector; this.audit = audit;
    }
    record CreateRequest(long sourceId, String name, String catalog, String schema, String table) {}
    @GetMapping("/api/datasets")
    List<DatasetStore.Dataset> list() { return datasets.list(); }
    @PostMapping("/api/datasets")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request, Authentication authentication) throws GeneralSecurityException, DatabaseInspector.InspectionException {
        var source = sources.find(request.sourceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            var columns = inspector.columns(source, sources.passwordForExecution(request.sourceId()), request.catalog(), request.schema(), request.table());
            var fields = columns.stream().map(c -> new DatasetStore.Field(c.name(), c.type(), c.nullable())).toList();
            long id = datasets.create(request.sourceId(), request.name(), request.catalog(), request.schema(), request.table(), fields);
            audit.success(authentication, "dataset.create", "dataset", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid dataset metadata"));
        }
    }
    @PostMapping("/api/datasets/{id}/refresh") @Transactional
    ResponseEntity<Map<String, Integer>> refresh(@PathVariable long id, Authentication authentication) throws GeneralSecurityException, DatabaseInspector.InspectionException {
        var dataset = datasets.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!sources.lockSources(List.of(dataset.sourceId()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var source = sources.credentialsForLockedRefresh(dataset.sourceId());
        try {
            var columns = inspector.columns(source.definition(), source.password(), dataset.catalog(), dataset.schema(), dataset.table());
            int added = datasets.refreshFields(id, columns.stream().map(c -> new DatasetStore.Field(c.name(), c.type(), c.nullable())).toList());
            audit.success(authentication, "dataset.refresh", "dataset", id);
            return ResponseEntity.ok(Map.of("added", added));
        } catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing fields changed; review dependencies before re-registering"); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discovered dataset metadata"); }
    }
    @DeleteMapping("/api/datasets/{id}") @Transactional
    ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        try {
            if (!datasets.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(authentication, "dataset.delete", "dataset", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dataset ID"); }
        catch (org.springframework.dao.DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Dataset has existing policies, reports or services"); }
    }
}
