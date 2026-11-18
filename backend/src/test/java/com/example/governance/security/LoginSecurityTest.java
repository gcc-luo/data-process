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
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class LoginSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM role_menus");
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES(101,'test')");
        var hash = new BCryptPasswordEncoder().encode("Correct-Pass1!");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(101,'alice',?,101,TRUE,'v-alice')", hash);
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(102,'bob',?,101,TRUE,'v-bob')", hash);
    }

    private ResultActions login(MockHttpSession session, String username, String password, String captcha) throws Exception {
        var request = post("/api/login").with(csrf()).param("username", username).param("password", password);
        if (session != null) request = request.session(session);
        if (captcha != null) request = request.param("captcha", captcha);
        return mvc.perform(request);
    }
    private String captcha(MockHttpSession session) throws Exception {
        mvc.perform(get("/api/captcha").session(session)).andExpect(status().isOk());
        return (String) session.getAttribute(CaptchaController.SESSION_CODE);
    }

    @Test void progressiveCaptchaThenLockoutAndAuditContext() throws Exception {
        var session = new MockHttpSession();
        login(session, "alice", "wrong", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.captchaRequired").value(false));
        login(session, "alice", "wrong", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.captchaRequired").value(true));
        login(session, "alice", "wrong", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("验证码错误或已失效"));
        login(session, "alice", "wrong", captcha(session)).andExpect(status().isUnauthorized());
        login(session, "alice", "wrong", captcha(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").with(csrf()).session(session).param("username", "alice").param("password", "wrong").param("captcha", captcha(session)).header("User-Agent", "JUnit-Agent"))
                .andExpect(status().isUnauthorized());
        assertEquals(5, jdbc.queryForObject("SELECT failed_attempts FROM users WHERE id=101", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT locked_until FROM users WHERE id=101", java.sql.Timestamp.class));
        login(session, "alice", "Correct-Pass1!", captcha(session)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("锁定")));
        var audit = jdbc.queryForList("SELECT ip,user_agent,message FROM audit_events WHERE result='FAILURE' ORDER BY id DESC");
        assertEquals("127.0.0.1", audit.get(0).get("ip"));
        assertTrue(audit.stream().anyMatch(row -> "JUnit-Agent".equals(row.get("user_agent"))));
        assertTrue(audit.stream().anyMatch(row -> String.valueOf(row.get("message")).contains("用户名或密码错误")));
    }

    @Test void successfulLoginResetsFailureCounters() throws Exception {
        var session = new MockHttpSession();
        login(session, "bob", "wrong", null).andExpect(status().isUnauthorized());
        login(session, "bob", "wrong", null).andExpect(status().isUnauthorized());
        login(session, "bob", "Correct-Pass1!", captcha(session)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM users WHERE id=102", Integer.class));
        assertNull(jdbc.queryForObject("SELECT locked_until FROM users WHERE id=102", java.sql.Timestamp.class));
    }

    @Test void concurrentSessionsLimitedToOne() throws Exception {
        var first = (MockHttpSession) login(null, "alice", "Correct-Pass1!", null).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        var second = (MockHttpSession) login(null, "alice", "Correct-Pass1!", null).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/session").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session").session(second)).andExpect(status().isOk());
    }
}
