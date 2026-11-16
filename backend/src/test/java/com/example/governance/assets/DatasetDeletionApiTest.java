package com.example.governance.assets;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:dataset_deletion;DB_CLOSE_DELAY=-1", "governance.realtime-poll-ms=3600000", "governance.schedule-poll-ms=3600000"})
class DatasetDeletionApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM data_services"); jdbc.update("DELETE FROM reports"); jdbc.update("DELETE FROM dataset_role_fields"); jdbc.update("DELETE FROM dataset_role_rules"); jdbc.update("DELETE FROM dataset_fields"); jdbc.update("DELETE FROM datasets"); jdbc.update("DELETE FROM data_sources"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES (10,'department')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (10,'manager',?,10,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO roles(id,name) VALUES (10,'manager')"); jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (10,10)");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (20,15,'dataset','orders')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (20,0,'id','BIGINT',FALSE)");
    }
    private MockHttpSession login() throws Exception { return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","manager").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false); }
    @Test void deletionRequiresPermissionRemovesFieldsAndAuditsWithoutTouchingBusinessData() throws Exception {
        var session=login(); mvc.perform(delete("/api/datasets/20").session(session).with(csrf())).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.manage')");
        mvc.perform(delete("/api/datasets/20").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dataset_fields WHERE dataset_id=20",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=15",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dataset.delete' AND actor_id=10",Integer.class));
        mvc.perform(delete("/api/datasets/20").session(session).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void deletionRejectsExistingPoliciesReportsAndServices() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.manage')"); var session=login();
        jdbc.update("INSERT INTO dataset_role_rules(dataset_id,role_id,scope) VALUES (20,10,'OWN')");
        mvc.perform(delete("/api/datasets/20").session(session).with(csrf())).andExpect(status().isConflict());
        jdbc.update("DELETE FROM dataset_role_rules WHERE dataset_id=20");
        jdbc.update("INSERT INTO reports(owner_id,dataset_id,name,chart_type,query_kind,query_config,created_at) VALUES (10,20,'report','TABLE','PREVIEW','{}',CURRENT_TIMESTAMP)");
        mvc.perform(delete("/api/datasets/20").session(session).with(csrf())).andExpect(status().isConflict());
        jdbc.update("DELETE FROM reports WHERE dataset_id=20");
        jdbc.update("INSERT INTO data_services(creator_id,dataset_id,name,fields_json,row_limit,state,created_at) VALUES (10,20,'service','[]',10,'DRAFT',CURRENT_TIMESTAMP)");
        mvc.perform(delete("/api/datasets/20").session(session).with(csrf())).andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM datasets WHERE id=20",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dataset_fields WHERE dataset_id=20",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dataset.delete'",Integer.class));
    }
}
