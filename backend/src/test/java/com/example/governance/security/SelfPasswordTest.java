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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class SelfPasswordTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM role_menus");
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES(101,'test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version,must_change_password) VALUES(101,'alice',?,101,TRUE,'v-alice',TRUE)", new BCryptPasswordEncoder().encode("Initial-Pass1!"));
    }

    private MockHttpSession login(String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "alice").param("password", password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private org.springframework.test.web.servlet.ResultActions change(MockHttpSession session, String oldPassword, String newPassword) throws Exception {
        return mvc.perform(post("/api/session/password").session(session).with(csrf()).contentType("application/json")
                .content("{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}"));
    }

    @Test void forcedInitialChangeBlocksApisUntilPasswordRotated() throws Exception {
        var session = login("Initial-Pass1!");
        mvc.perform(get("/api/session").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/navigation").session(session)).andExpect(status().isForbidden());
        change(session, "wrong-old", "New-Pass2026!").andExpect(status().isBadRequest());
        change(session, "Initial-Pass1!", "weak").andExpect(status().isBadRequest());
        change(session, "Initial-Pass1!", "New-Pass2026!").andExpect(status().isNoContent());
        mvc.perform(get("/api/navigation").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/session").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(post("/api/login").with(csrf()).param("username", "alice").param("password", "Initial-Pass1!")).andExpect(status().isUnauthorized());
        assertNotEquals("v-alice", jdbc.queryForObject("SELECT security_version FROM users WHERE id=101", String.class));
        mvc.perform(post("/api/login").with(csrf()).param("username", "alice").param("password", "New-Pass2026!")).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='user.password.self' AND ip<>''", Integer.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT must_change_password FROM users WHERE id=101", Boolean.class));
    }
}
