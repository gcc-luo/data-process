package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.governance.security.AccountPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class WorkflowValidationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void setUpUser() {
        jdbc.update("DELETE FROM user_roles WHERE user_id=9999");
        jdbc.update("DELETE FROM role_permissions WHERE role_id=9999");
        jdbc.update("DELETE FROM roles WHERE id=9999");
        jdbc.update("DELETE FROM users WHERE username='user'");
        jdbc.update("DELETE FROM departments WHERE id=9999");
        jdbc.update("INSERT INTO departments(id,name) VALUES(9999,'Workflow test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES(9999,'user','unused',9999,TRUE,'149870bb-9168-469d-9139-d4656b295773')");
        jdbc.update("INSERT INTO roles(id,name) VALUES(9999,'Workflow test role')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES(9999,9999)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES(9999,'workflow.validate')");
    }

    @Test void acceptsValidDependencies() throws Exception {
        mvc.perform(post("/api/workflows/validate").with(user(new AccountPrincipal(9999, "149870bb-9168-469d-9139-d4656b295773", "user", "unused", true, java.util.List.of(new SimpleGrantedAuthority("workflow.validate"))))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"tasks":["a","b"],"edges":[{"from":"a","to":"b"}]}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.ready[0]").value("a"));
    }

    @Test void rejectsCycleAndDuplicateTasks() throws Exception {
        mvc.perform(post("/api/workflows/validate").with(user(new AccountPrincipal(9999, "149870bb-9168-469d-9139-d4656b295773", "user", "unused", true, java.util.List.of(new SimpleGrantedAuthority("workflow.validate"))))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"tasks":["a","b"],"edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}]}
                """))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/workflows/validate").with(user(new AccountPrincipal(9999, "149870bb-9168-469d-9139-d4656b295773", "user", "unused", true, java.util.List.of(new SimpleGrantedAuthority("workflow.validate"))))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"tasks":["a","a"],"edges":[]}
                """))
            .andExpect(status().isBadRequest());
    }
}
