package com.example.governance.query;

import com.example.governance.assets.ExecutionDataSourceProvider;
import com.example.governance.security.AccessPolicy;
import com.example.governance.security.AccountPrincipal;
import com.example.governance.security.AuditLog;
import com.example.governance.security.DatasetAccess;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DatasetQueryController {
    private final DatasetPolicyStore policies;
    private final DatasetQueryEngine engine;
    private final AnalysisQueryEngine analysis;
    private final ExecutionDataSourceProvider sources;
    private final AuditLog audit;
    public DatasetQueryController(DatasetPolicyStore policies, DatasetQueryEngine engine, AnalysisQueryEngine analysis, ExecutionDataSourceProvider sources, AuditLog audit) {
        this.policies = policies; this.engine = engine; this.analysis = analysis; this.sources = sources; this.audit = audit;
    }
    record PolicyRequest(String departmentField, long roleId, AccessPolicy.Scope scope, Map<String, DatasetAccess.Field> fields) {}
    record PreviewRequest(List<String> fields, int limit) {}
    @GetMapping("/api/accessible-datasets")
    List<DatasetPolicyStore.Accessible> accessible(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return policies.accessible(principal.id());
    }
    @PostMapping("/api/datasets/{id}/policies")
    @Transactional
    ResponseEntity<Void> configure(@PathVariable long id, @RequestBody PolicyRequest request, Authentication authentication) {
        try {
            policies.configure(id, request.departmentField(), request.roleId(), request.scope(), request.fields());
            audit.success(authentication, "dataset.policy", "dataset_role_policy", id + ":" + request.roleId());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dataset policy"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Department field is used by other roles"); }
    }
    @GetMapping("/api/datasets/{id}/policies")
    DatasetPolicyStore.PolicyView policy(@PathVariable long id) {
        try { return policies.listPolicies(id); } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
    @DeleteMapping("/api/datasets/{id}/policies/{roleId}") @Transactional
    ResponseEntity<Void> revoke(@PathVariable long id, @PathVariable long roleId, Authentication authentication) {
        try {
            if (!policies.revoke(id, roleId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(authentication, "dataset.policy.revoke", "dataset_role_policy", id + ":" + roleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
    @PostMapping("/api/datasets/{id}/preview")
    List<Map<String, Object>> preview(@PathVariable long id, @RequestBody PreviewRequest request, Authentication authentication) {
        try {
            if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) throw new SecurityException();
            var resolved = policies.resolve(principal.id(), id);
            return engine.query(sources.get(resolved.sourceId()), resolved.dataset(), resolved.policy(), request.fields(), request.limit());
        } catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid query"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dataset query failed"); }
    }
    @PostMapping("/api/datasets/{id}/analysis")
    List<Map<String, Object>> analysis(@PathVariable long id, @RequestBody AnalysisQueryEngine.Query request, Authentication authentication) {
        try {
            if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) throw new SecurityException();
            var resolved = policies.resolve(principal.id(), id);
            return analysis.query(sources.get(resolved.sourceId()), resolved.dataset(), resolved.policy(), request);
        } catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analysis query"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dataset query failed"); }
    }
}
