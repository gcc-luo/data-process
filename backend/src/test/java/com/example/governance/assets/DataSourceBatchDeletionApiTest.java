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
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:source_batch_deletion_api;DB_CLOSE_DELAY=-1", "governance.realtime-poll-ms=3600000", "governance.schedule-poll-ms=3600000"})
class DataSourceBatchDeletionApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM dataset_fields"); jdbc.update("DELETE FROM datasets"); jdbc.update("DELETE FROM realtime_jobs"); jdbc.update("DELETE FROM processing_task_versions"); jdbc.update("DELETE FROM processing_tasks"); jdbc.update("DELETE FROM data_sources"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES (10,'test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (10,'manager',?,10,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO roles(id,name) VALUES (10,'manager')"); jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (10,10)");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (16,'referenced-by-dataset','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (17,'referenced-by-task','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
    }
    private MockHttpSession login() throws Exception { return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","manager").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false); }
    private void grantDelete() { jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.delete')"); }
    private ResultActions batchDelete(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post("/api/data-sources/batch-delete").session(session).with(csrf()).contentType("application/json").content(body));
    }
    @Test void requiresDeletePermission() throws Exception {
        var session = login();
        batchDelete(session, "{\"ids\":[15]}").andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=15", Integer.class));
    }
    @Test void rejectsInvalidIds() throws Exception {
        grantDelete(); var session = login();
        batchDelete(session, "{}").andExpect(status().isBadRequest());
        batchDelete(session, "{\"ids\":[]}").andExpect(status().isBadRequest());
        batchDelete(session, "{\"ids\":[0]}").andExpect(status().isBadRequest());
        String oversized = "{\"ids\":[" + java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(",")) + "]}";
        batchDelete(session, oversized).andExpect(status().isBadRequest());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources", Integer.class));
    }
    @Test void deletesUnreferencedAndReportsReferencedAndMissing() throws Exception {
        grantDelete(); var session = login();
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,16,'dataset','orders')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (1,'task','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (1,1,?)", "{\"sourceId\":17}");
        batchDelete(session, "{\"ids\":[15,16,17,999,15]}").andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted.length()").value(1))
            .andExpect(jsonPath("$.deleted[0]").value(15))
            .andExpect(jsonPath("$.failed.length()").value(3))
            .andExpect(jsonPath("$.failed[0].id").value(16)).andExpect(jsonPath("$.failed[0].reason").value("REFERENCED"))
            .andExpect(jsonPath("$.failed[1].id").value(17)).andExpect(jsonPath("$.failed[1].reason").value("REFERENCED"))
            .andExpect(jsonPath("$.failed[2].id").value(999)).andExpect(jsonPath("$.failed[2].reason").value("NOT_FOUND"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=15", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=16", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=17", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='datasource.delete' AND object_id='15'", Integer.class));
    }
    @Test void keepsEverythingWhenAllReferenced() throws Exception {
        grantDelete(); var session = login();
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,16,'dataset','orders')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (2,17,'dataset2','items')");
        batchDelete(session, "{\"ids\":[16,17]}").andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted.length()").value(0))
            .andExpect(jsonPath("$.failed.length()").value(2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id IN (16,17)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='datasource.delete'", Integer.class));
    }
}