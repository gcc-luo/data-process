package com.example.governance.assets;

import com.example.governance.assets.driver.DatabaseProfileRegistry;
import com.example.governance.assets.driver.DriverStore;
import com.example.governance.security.AccountPrincipal;
import com.example.governance.security.AuditLog;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Admin-only JDBC driver repository endpoints; every mutation is audited. */
@RestController
public class DriverController {
    private final DriverStore drivers;
    private final DatabaseProfileRegistry registry;
    private final AuditLog audit;
    public DriverController(DriverStore drivers, DatabaseProfileRegistry registry, AuditLog audit) { this.drivers = drivers; this.registry = registry; this.audit = audit; }
    record Item(long id, String name, String dbType, String driverClass, String versionLabel, boolean builtin, Long uploadedBy, String createdAt, String sha256, String originalFilename) {}
    @GetMapping("/api/drivers")
    List<Item> list() {
        return drivers.list().stream().map(d -> new Item(d.id(), d.name(), d.dbType(), d.driverClass(), d.versionLabel(), d.builtin(), d.uploadedBy(), String.valueOf(d.createdAt()), d.sha256(), d.originalFilename())).toList();
    }
    @PostMapping("/api/drivers")
    ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file, @RequestParam String name, @RequestParam String dbType, @RequestParam String driverClass, @RequestParam(required = false) String versionLabel, Authentication authentication) {
        try {
            long actorId = ((AccountPrincipal) authentication.getPrincipal()).id();
            var record = drivers.upload(name, dbType, driverClass, versionLabel, file.getOriginalFilename(), file.getBytes(), actorId, registry.types());
            audit.success(authentication, "driver.upload", "driver", record.id());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", record.id(), "name", record.name()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (IOException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "驱动文件读取失败"));
        }
    }
    @DeleteMapping("/api/drivers/{id}")
    ResponseEntity<Map<String, Object>> delete(@PathVariable long id, Authentication authentication) {
        try {
            drivers.delete(id);
            audit.success(authentication, "driver.delete", "driver", id);
            return ResponseEntity.noContent().build();
        } catch (DriverStore.DriverInUseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "驱动仍被数据源引用", "references", ex.references()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "驱动不存在"));
        }
    }
}
