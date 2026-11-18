package com.example.governance.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AuditDisplayTest {
    @Test void eventsCarryChineseLabelsAndSpaceSeparatedTime() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (NULL,'2026-11-18 15:01:22','menu.reorder','menu','9','SUCCESS'),(NULL,'2026-11-18 15:02:00','auth.login','account','redacted','FAILURE'),(NULL,'2026-11-18 15:03:00','future.unknown','widget','1','SUCCESS')");
        var events = new AuditLog(jdbc).list(null, null, 0, 10);
        assertEquals(3, events.size());
        var unknown = events.get(0);
        assertEquals("future.unknown", unknown.actionLabel());
        assertEquals("widget", unknown.objectTypeLabel());
        var failure = events.get(1);
        assertEquals("登录", failure.actionLabel());
        assertEquals("账号", failure.objectTypeLabel());
        assertEquals("失败", failure.resultLabel());
        var reorder = events.get(2);
        assertEquals("2026-11-18 15:01:22", reorder.occurredAtText());
        assertFalse(reorder.occurredAtText().contains("T"));
        assertEquals("菜单排序", reorder.actionLabel());
        assertEquals("菜单", reorder.objectTypeLabel());
        assertEquals("成功", reorder.resultLabel());
    }

    @Test void catalogCoversEveryEmittedActionAndObjectType() {
        for (String code : List.of("auth.login", "department.create", "department.update", "department.move", "department.delete", "role.create", "role.update", "role.delete", "role.permissions", "user.create", "user.update", "user.delete", "user.roles", "user.enabled", "user.password", "menu.create", "menu.update", "menu.delete", "datasource.create", "datasource.update", "datasource.delete", "dataset.create", "dataset.delete", "dataset.refresh", "dataset.policy", "dataset.policy.revoke", "task.create", "task.version", "task.run", "task.retry", "workflow.create", "workflow.version", "workflow.run", "workflow.retry", "workflow.backfill", "workflow.schedule.fire", "schedule.create", "realtime.create", "realtime.settings", "realtime.start", "realtime.stop", "service.create", "service.submit", "service.withdraw", "service.approve", "service.grant", "service.revoke", "service.caller.create", "service.caller.disable", "report.create", "report.update", "report.delete", "dashboard.create", "dashboard.update", "dashboard.delete"))
            assertNotEquals(code, AuditCatalog.actionLabel(code), "missing action label: " + code);
        for (String code : List.of("user", "account", "department", "role", "menu", "datasource", "dataset", "task", "workflow", "run", "schedule", "realtime", "service", "caller", "report", "dashboard"))
            assertNotEquals(code, AuditCatalog.objectTypeLabel(code), "missing object type label: " + code);
        assertEquals("成功", AuditCatalog.resultLabel("SUCCESS"));
        assertEquals("失败", AuditCatalog.resultLabel("FAILURE"));
    }
}
