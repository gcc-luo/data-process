package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:orch_testrun_api;DB_CLOSE_DELAY=-1")
class OrchTestRunApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;

    private MockHttpSession login() throws Exception {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=711", Integer.class) == 0) {
            jdbc.update("INSERT INTO departments(id,name) VALUES (711,'dept')");
            jdbc.update("INSERT INTO roles(id,name) VALUES (711,'role')");
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (711,'tester',?,711,TRUE,'v')", encoder.encode("password"));
            jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (711,711)");
            for (String p : new String[]{"workflow.task.read", "workflow.task.update", "workflow.task.execute"})
                jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (711,?)", p);
            jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'src','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        }
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "tester").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void dryRunDmlSkipsWriteAndReturnsNodeStates() throws Exception {
        var s = login();
        mvc.perform(post("/api/orch/test-run").session(s).with(csrf()).contentType("application/json")
                .content("{\"definition\":" + OrchFixtures.dml(1, "UPDATE t SET x=1") + ",\"businessDate\":\"2026-11-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeStates[0].nodeKey").value("n_sql"))
                .andExpect(jsonPath("$.nodeStates[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.logs").isArray());
    }

    @Test void dryRunRejectsInvalidDefinition() throws Exception {
        var s = login();
        mvc.perform(post("/api/orch/test-run").session(s).with(csrf()).contentType("application/json")
                .content("{\"definition\":{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.validation.valid").value(false));
    }
}