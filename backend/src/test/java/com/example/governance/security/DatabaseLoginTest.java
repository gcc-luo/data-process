package com.example.governance.security;

import com.example.governance.GovernanceApplication;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class DatabaseLoginTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountCredentials credentials;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES(101,'test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(101,'alice',?,101,TRUE,'37b13c71-5d65-46b7-9ce5-564df7822742')", new BCryptPasswordEncoder().encode("correct-password"));
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(102,'disabled',?,101,FALSE,'d1ee1c52-1210-4c81-a723-20c0615bbf4a')", new BCryptPasswordEncoder().encode("correct-password"));
    }
    @Test void loginRequiresCsrfAndCorrectEnabledAccount() throws Exception {
        mvc.perform(post("/api/login").param("username","alice").param("password","correct-password")).andExpect(status().isForbidden());
        mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").with(csrf()).param("username","disabled").param("password","correct-password"))
                .andExpect(status().isUnauthorized());
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/session").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void changedPermissionsApplyImmediatelyAndRecreatedUserCannotReuseSession() throws Exception {
        jdbc.update("INSERT INTO roles(id,name) VALUES (101,'test')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (101,101)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (101,'workflow.validate')");
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        String body = "{\"tasks\":[\"a\"],\"edges\":[]}";
        mvc.perform(post("/api/workflows/validate").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        jdbc.update("DELETE FROM role_permissions WHERE role_id=101");
        mvc.perform(post("/api/workflows/validate").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM user_roles WHERE user_id=101");
        jdbc.update("DELETE FROM audit_events WHERE actor_id=101");
        jdbc.update("DELETE FROM users WHERE id=101");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(103,'alice',?,101,TRUE,'37b13c71-5d65-46b7-9ce5-564df7822742')",new BCryptPasswordEncoder().encode("correct-password"));
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void securityVersionChangeRevokesExistingSessionEvenIfIdIsReused() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        credentials.resetPassword(101, "a-new-password");
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void recreatedAccountWithSameIdCannotReuseOldSession() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        jdbc.update("DELETE FROM audit_events WHERE actor_id=101");
        jdbc.update("DELETE FROM users WHERE id=101");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(101,'alice',?,101,TRUE,?)", new BCryptPasswordEncoder().encode("correct-password"), java.util.UUID.randomUUID().toString());
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void disablingUserRevokesExistingSession() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        jdbc.update("UPDATE users SET enabled=FALSE WHERE username='alice'");
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void disablingAndReenablingRevokesUnseenOldSession() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        credentials.setEnabled(101, false);
        credentials.setEnabled(101, true);
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void loginAttemptsAreAuditedWithoutCredentials() throws Exception {
        mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","wrong-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").with(csrf()).param("username","alice").param("password","correct-password"))
                .andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='auth.login' AND result='SUCCESS' AND actor_id=101", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='auth.login' AND result='FAILURE' AND actor_id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE object_id LIKE '%password%' OR object_id LIKE '%secret%'", Integer.class));
    }
}
