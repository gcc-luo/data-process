package com.example.governance.serviceapi;

import com.example.governance.assets.ExecutionDataSourceProvider;
import com.example.governance.query.DatasetPolicyStore;
import com.example.governance.query.DatasetQueryEngine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ExternalServiceController {
    private final ServiceCatalogStore services;
    private final DatasetPolicyStore policies;
    private final DatasetQueryEngine engine;
    private final ExecutionDataSourceProvider sources;
    public ExternalServiceController(ServiceCatalogStore services, DatasetPolicyStore policies, DatasetQueryEngine engine, ExecutionDataSourceProvider sources) { this.services = services; this.policies = policies; this.engine = engine; this.sources = sources; }
    @GetMapping("/api/service-call/{id}")
    List<Map<String, Object>> call(@PathVariable long id, @RequestHeader(value="Authorization", required=false) String header) {
        ServiceCatalogStore.Call call;
        try {
            if (header == null || !header.startsWith("Bearer ")) throw new SecurityException();
            call = services.begin(header.substring(7), id, Instant.now());
        } catch (ServiceCatalogStore.RateLimitException ex) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS); }
        catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        long start = System.nanoTime(); boolean success = false;
        try {
            var access = policies.resolve(call.userId(), call.datasetId());
            var result = engine.query(sources.get(access.sourceId()), access.dataset(), access.policy(), call.fields(), call.limit());
            success = true;
            return result;
        } catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Service query failed"); }
        finally { services.complete(call.invocationId(), success, Math.max(0, (System.nanoTime() - start) / 1_000_000)); }
    }
}
