package com.example.governance.report;

import com.example.governance.assets.ExecutionDataSourceProvider;
import com.example.governance.query.AnalysisQueryEngine;
import com.example.governance.query.DatasetPolicyStore;
import com.example.governance.query.DatasetQueryEngine;
import com.example.governance.security.AccountPrincipal;
import com.example.governance.security.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReportController {
    private final ReportStore reports;
    private final DatasetPolicyStore policies;
    private final DatasetQueryEngine preview;
    private final AnalysisQueryEngine analysis;
    private final ExecutionDataSourceProvider sources;
    private final AuditLog audit;
    private final ObjectMapper mapper;
    public ReportController(ReportStore reports, DatasetPolicyStore policies, DatasetQueryEngine preview, AnalysisQueryEngine analysis, ExecutionDataSourceProvider sources, AuditLog audit, ObjectMapper mapper) {
        this.reports = reports; this.policies = policies; this.preview = preview; this.analysis = analysis; this.sources = sources; this.audit = audit; this.mapper = mapper;
    }
    record CreateReport(long datasetId, String name, String chartType, String kind, JsonNode configuration) {}
    record PreviewConfig(List<String> fields, int limit) {}
    record CreateDashboard(String name, List<Long> reports) {}
    private static long user(Authentication auth) {
        if (!(auth.getPrincipal() instanceof AccountPrincipal principal)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return principal.id();
    }
    @GetMapping("/api/reports") List<ReportStore.Report> reports(Authentication auth) { return reports.reports(user(auth)); }
    @PostMapping("/api/reports") @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    ResponseEntity<Map<String, Long>> create(@RequestBody CreateReport request, Authentication auth) {
        try {
            long owner = user(auth); policies.resolve(owner, request.datasetId());
            if (request.configuration() == null || !request.configuration().isObject()) throw new IllegalArgumentException();
            if ("PREVIEW".equals(request.kind())) mapper.treeToValue(request.configuration(), PreviewConfig.class);
            else if ("ANALYSIS".equals(request.kind())) mapper.treeToValue(request.configuration(), AnalysisQueryEngine.Query.class);
            else throw new IllegalArgumentException();
            long id = reports.createReport(owner, request.datasetId(), request.name(), request.chartType(), request.kind(), request.configuration().toString());
            audit.success(auth, "report.create", "report", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report configuration"); }
    }
    @PutMapping("/api/reports/{id}") @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    ResponseEntity<Void> updateReport(@PathVariable long id, @RequestBody CreateReport request, Authentication auth) {
        try {
            long owner = user(auth);
            if (reports.report(owner, id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            policies.resolve(owner, request.datasetId());
            if (request.configuration() == null || !request.configuration().isObject()) throw new IllegalArgumentException();
            if ("PREVIEW".equals(request.kind())) mapper.treeToValue(request.configuration(), PreviewConfig.class);
            else if ("ANALYSIS".equals(request.kind())) mapper.treeToValue(request.configuration(), AnalysisQueryEngine.Query.class);
            else throw new IllegalArgumentException();
            if (!reports.updateReport(owner,id,request.datasetId(),request.name(),request.chartType(),request.kind(),request.configuration().toString())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(auth, "report.update", "report", id); return ResponseEntity.noContent().build();
        } catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report configuration"); }
    }
    @DeleteMapping("/api/reports/{id}") @Transactional
    ResponseEntity<Void> deleteReport(@PathVariable long id, Authentication auth) {
        try {
            if (!reports.deleteReport(user(auth), id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(auth, "report.delete", "report", id); return ResponseEntity.noContent().build();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Report is used by a dashboard"); }
    }
    @PostMapping("/api/reports/{id}/run")
    List<Map<String, Object>> run(@PathVariable long id, Authentication auth) {
        try {
            long owner = user(auth);
            var report = reports.report(owner, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var access = policies.resolve(owner, report.datasetId());
            if (report.kind().equals("PREVIEW")) {
                var config = mapper.readValue(report.configuration(), PreviewConfig.class);
                return preview.query(sources.get(access.sourceId()), access.dataset(), access.policy(), config.fields(), config.limit());
            }
            var config = mapper.readValue(report.configuration(), AnalysisQueryEngine.Query.class);
            return analysis.query(sources.get(access.sourceId()), access.dataset(), access.policy(), config);
        } catch (ResponseStatusException ex) { throw ex; }
        catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report query"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Report query failed"); }
    }
    @GetMapping("/api/dashboards") List<ReportStore.Dashboard> dashboards(Authentication auth) { return reports.dashboards(user(auth)); }
    @PostMapping("/api/dashboards") @Transactional
    ResponseEntity<Map<String, Long>> dashboard(@RequestBody CreateDashboard request, Authentication auth) {
        try {
            long id = reports.createDashboard(user(auth), request.name(), request.reports());
            audit.success(auth, "dashboard.create", "dashboard", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dashboard"); }
        catch (org.springframework.dao.DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Dashboard references changed"); }
    }
    @PutMapping("/api/dashboards/{id}") @Transactional
    ResponseEntity<Void> updateDashboard(@PathVariable long id, @RequestBody CreateDashboard request, Authentication auth) {
        try {
            if (!reports.updateDashboard(user(auth), id, request.name(), request.reports())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.success(auth, "dashboard.update", "dashboard", id); return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dashboard"); }
        catch (org.springframework.dao.DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Dashboard references changed"); }
    }
    @DeleteMapping("/api/dashboards/{id}") @Transactional
    ResponseEntity<Void> deleteDashboard(@PathVariable long id, Authentication auth) {
        if (!reports.deleteDashboard(user(auth), id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.success(auth, "dashboard.delete", "dashboard", id); return ResponseEntity.noContent().build();
    }

}
