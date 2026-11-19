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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:source_deletion_api;DB_CLOSE_DELAY=-1", "governance.realtime-poll-ms=3600000", "governance.schedule-poll-ms=3600000"})
class DataSourceDeletionApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM dataset_fields"); jdbc.update("DELETE FROM datasets"); jdbc.update("DELETE FROM realtime_jobs"); jdbc.update("DELETE FROM processing_task_versions"); jdbc.update("DELETE FROM processing_tasks"); jdbc.update("DELETE FROM data_sources"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES (10,'test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (10,'manager',?,10,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO roles(id,name) VALUES (10,'manager')"); jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (10,10)");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','localhost',3306,'db','u','cipher')");
    }
    private MockHttpSession login() throws Exception { return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","manager").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false); }
    @Test void deleteRequiresPermissionAndAuditsSuccess() throws Exception {
        var session = login();
        mvc.perform(delete("/api/data-sources/15").session(session).with(csrf())).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        mvc.perform(delete("/api/data-sources/15").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=15", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='datasource.delete' AND actor_id=10", Integer.class));
        mvc.perform(delete("/api/data-sources/15").session(session).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void rejectsDatasetAndTaskVersionReferences() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')"); var session = login();
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,15,'dataset','orders')");
        mvc.perform(delete("/api/data-sources/15").session(session).with(csrf())).andExpect(status().isConflict());
        jdbc.update("DELETE FROM datasets WHERE id=1");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (1,'task','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (1,1,?)", "{\"sourceId\":15}");
        mvc.perform(delete("/api/data-sources/15").session(session).with(csrf())).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=15", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='datasource.delete'", Integer.class));
    }
}
