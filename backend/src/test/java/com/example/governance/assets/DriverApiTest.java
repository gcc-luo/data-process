package com.example.governance.assets;

import com.example.governance.GovernanceApplication;
import com.example.governance.realtime.IncrementalSyncExecutor;
import com.example.governance.workflow.CamelTaskRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class DriverApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean DatabaseInspector inspector;
    @MockitoBean CamelTaskRunner runner;
    @MockitoBean ExecutionDataSourceProvider executionSources;
    @MockitoBean IncrementalSyncExecutor incremental;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM realtime_jobs");
        jdbc.update("DELETE FROM dataset_role_fields"); jdbc.update("DELETE FROM dataset_role_rules"); jdbc.update("DELETE FROM dataset_fields"); jdbc.update("DELETE FROM datasets");
        jdbc.update("DELETE FROM data_sources");
        jdbc.update("DELETE FROM jdbc_drivers WHERE builtin=FALSE");
        jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions");
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES (10,'test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (10,'manager',?,10,TRUE,'version')", encoder.encode("password"));
        jdbc.update("INSERT INTO roles(id,name) VALUES (10,'manager')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (10,10)");
    }
    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "manager").param("password", "password"))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private void grant(String... permissions) {
        for (String permission : permissions) jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,?)", permission);
    }
    private static byte[] h2Bytes() throws Exception {
        return Files.readAllBytes(Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }
    private MockMultipartFile jar(String filename) throws Exception {
        return new MockMultipartFile("file", filename, "application/java-archive", h2Bytes());
    }

    @Test void driverEndpointsRequirePermission() throws Exception {
        var session = login();
        mvc.perform(get("/api/drivers").session(session)).andExpect(status().isForbidden());
        grant("datasource.driver.read");
        mvc.perform(get("/api/drivers").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[?(@.builtin==true)]", org.hamcrest.Matchers.hasSize(5)));
    }
    @Test void uploadListAndDeleteUploadedDriverWithAudit() throws Exception {
        grant("datasource.driver.read", "datasource.driver.upload", "datasource.driver.delete");
        var session = login();
        var created = mvc.perform(multipart("/api/drivers").file(jar("h2.jar")).session(session).with(csrf())
                .param("name", "H2 测试驱动").param("dbType", "MYSQL").param("driverClass", "org.h2.Driver"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/drivers").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[5].name").value("H2 测试驱动"))
            .andExpect(jsonPath("$[5].builtin").value(false))
            .andExpect(jsonPath("$[5].uploadedBy").value(10));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='driver.upload' AND actor_id=10", Integer.class));
        mvc.perform(delete("/api/drivers/" + id).session(session).with(csrf())).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='driver.delete' AND actor_id=10", Integer.class));
    }
    @Test void uploadRejectsBadInput() throws Exception {
        grant("datasource.driver.read", "datasource.driver.upload");
        var session = login();
        mvc.perform(multipart("/api/drivers").file(new MockMultipartFile("file", "bad.jar", "application/java-archive", "not a jar".getBytes())).session(session).with(csrf())
                .param("name", "bad").param("dbType", "MYSQL").param("driverClass", "org.h2.Driver"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(multipart("/api/drivers").file(jar("h2.jar")).session(session).with(csrf())
                .param("name", "missing class").param("dbType", "MYSQL").param("driverClass", "com.example.Missing"))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/drivers").file(jar("h2.jar")).session(session).with(csrf())
                .param("name", "unknown type").param("dbType", "DB2").param("driverClass", "org.h2.Driver"))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/drivers").file(jar("h2.jar")).session(session).with(csrf())
                .param("name", "ok").param("dbType", "MYSQL").param("driverClass", "org.h2.Driver"))
            .andExpect(status().isCreated());
        mvc.perform(multipart("/api/drivers").file(jar("h2.jar")).session(session).with(csrf())
                .param("name", "duplicate").param("dbType", "MYSQL").param("driverClass", "org.h2.Driver"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("相同内容的驱动已存在"));
    }
    @Test void builtinDriverCannotBeDeleted() throws Exception {
        grant("datasource.driver.read", "datasource.driver.delete");
        var session = login();
        long builtinId = jdbc.queryForObject("SELECT id FROM jdbc_drivers WHERE driver_key='builtin-mysql-8'", Long.class);
        mvc.perform(delete("/api/drivers/" + builtinId).session(session).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("内置驱动不可删除"));
    }
    @Test void referencedDriverCannotBeDeleted() throws Exception {
        grant("datasource.driver.read", "datasource.driver.upload", "datasource.driver.delete");
        var session = login();
        var created = mvc.perform(multipart("/api/drivers").file(jar("h2.jar")).session(session).with(csrf())
                .param("name", "referenced").param("dbType", "MYSQL").param("driverClass", "org.h2.Driver"))
            .andExpect(status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,driver_id,password_cipher) VALUES (60,'引用库','MYSQL','MYSQL:8.x','localhost',3306,'db','u',?,'cipher')", id);
        mvc.perform(delete("/api/drivers/" + id).session(session).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.references[0]").value("引用库"));
    }
    @Test void profilesCatalogRequiresSourceRead() throws Exception {
        var session = login();
        mvc.perform(get("/api/data-sources/profiles").session(session)).andExpect(status().isForbidden());
        grant("datasource.source.read");
        mvc.perform(get("/api/data-sources/profiles").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[1].id").value("MYSQL:8.x"))
            .andExpect(jsonPath("$[1].extraParams[?(@.key=='allowPublicKeyRetrieval')]").exists())
            .andExpect(jsonPath("$[2].id").value("ORACLE:11g"))
            .andExpect(jsonPath("$[2].extraFields[0].defaultValue").value("SID"));
    }
    @Test void createWithProfileAndOptionsPersistsAndValidates() throws Exception {
        grant("datasource.source.read", "datasource.source.create");
        var session = login();
        mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"versioned","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"secret","options":{"serverTimezone":"Asia/Shanghai"}}
            """)).andExpect(status().isCreated());
        mvc.perform(get("/api/data-sources").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].profileId").value("MYSQL:8.x"))
            .andExpect(jsonPath("$[0].profileName").value("MySQL 8.x"))
            .andExpect(jsonPath("$[0].type").value("MYSQL"))
            .andExpect(jsonPath("$[0].options.serverTimezone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$[0].password").doesNotExist());
        mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"bad-param","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"secret","options":{"allowLoadLocalInfile":"true"}}
            """)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"bad-profile","profileId":"DB2:1","host":"localhost","port":50000,"database":"orders","username":"reader","password":"secret"}
            """)).andExpect(status().isBadRequest());
    }
}
