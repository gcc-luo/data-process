package com.example.governance.security;

import com.example.governance.GovernanceApplication;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class P1SecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditLog audit;
    @Autowired AuditRetention retention;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM role_menus");
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES(101,'test')");
        var hash = new BCryptPasswordEncoder().encode("Correct-Pass1!");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(101,'alice',?,101,TRUE,'v-alice')", hash);
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(1,'admin',?,101,TRUE,'v-admin')", hash);
        jdbc.update("INSERT INTO roles(id,name,role_key) VALUES(1,'管理员','platform_admin')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES(1,1)");
        for (String code : PermissionCatalog.codes()) jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES(1,?)", code);
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", username).param("password", "Correct-Pass1!"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void auditFiltersNarrowByActorResultAndTimeRange() {
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result,ip,user_agent,message) VALUES (101,'2026-11-01 10:00:00','menu.update','menu','9','SUCCESS','','',''),(101,'2026-11-02 10:00:00','auth.login','account','redacted','FAILURE','','','用户名或密码错误'),(1,'2026-11-03 10:00:00','menu.update','menu','9','SUCCESS','','','')");
        assertEquals(3, audit.count(new AuditLog.Filter(null, null, null, null, null, null)));
        assertEquals(1, audit.count(new AuditLog.Filter(null, "alice", "FAILURE", null, null, null)));
        assertEquals(2, audit.count(new AuditLog.Filter("menu.update", null, null, LocalDateTime.parse("2026-11-01T00:00:00"), null, null)));
        assertEquals(1, audit.count(new AuditLog.Filter(null, null, null, LocalDateTime.parse("2026-11-02T00:00:00"), LocalDateTime.parse("2026-11-02T23:59:59"), null)));
        assertThrows(IllegalArgumentException.class, () -> new AuditLog.Filter(null, null, null, LocalDateTime.parse("2026-11-03T00:00:00"), LocalDateTime.parse("2026-11-01T00:00:00"), null));
        assertThrows(IllegalArgumentException.class, () -> new AuditLog.Filter(null, null, "MAYBE", null, null, null));
    }

    @Test void auditExportReturnsBomCsvWithChineseHeaders() throws Exception {
        var session = login("admin");
        var result = mvc.perform(get("/api/organization/audit/export").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8")).andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.startsWith("\uFEFF时间,操作者,操作"));
    }

    @Test void onlineInventoryAndKickReviveSessionExpiry() throws Exception {
        var alice = login("alice");
        var admin = login("admin");
        var online = mvc.perform(get("/api/organization/online").session(admin)).andExpect(status().isOk()).andReturn();
        String body = online.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("alice"));
        String sessionId = null;
        for (var node : com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body))
            if ("alice".equals(node.get("username").asText())) sessionId = node.get("sessionId").asText();
        assertNotNull(sessionId);
        mvc.perform(post("/api/organization/online/" + sessionId + "/kick").session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/session").session(alice)).andExpect(status().isUnauthorized());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='user.kick'", Integer.class));
    }

    @Test void retentionPurgesOldRowsAndAuditsItself() {
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result,ip,user_agent,message) VALUES (NULL,'2020-01-01 00:00:00','auth.login','account','redacted','SUCCESS','','',''),(NULL,CURRENT_TIMESTAMP,'auth.login','account','redacted','SUCCESS','','','')");
        retention.purge();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='auth.login'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='audit.purge'", Integer.class));
    }
}
