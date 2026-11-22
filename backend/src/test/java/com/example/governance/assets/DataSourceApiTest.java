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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.governance.workflow.CamelTaskRunner;
import com.example.governance.realtime.IncrementalSyncExecutor;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
class DataSourceApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean DatabaseInspector inspector;
    @MockitoBean CamelTaskRunner runner;
    @MockitoBean ExecutionDataSourceProvider executionSources;
    @MockitoBean IncrementalSyncExecutor incremental;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM task_node_runs"); jdbc.update("DELETE FROM task_runs"); jdbc.update("DELETE FROM workflow_node_attempts"); jdbc.update("DELETE FROM workflow_run_nodes"); jdbc.update("DELETE FROM workflow_runs"); jdbc.update("DELETE FROM workflow_schedules"); jdbc.update("DELETE FROM workflow_edges"); jdbc.update("DELETE FROM workflow_nodes"); jdbc.update("DELETE FROM workflow_versions"); jdbc.update("DELETE FROM workflow_definitions"); jdbc.update("DELETE FROM task_drafts"); jdbc.update("DELETE FROM task_watermarks"); jdbc.update("DELETE FROM task_schedules"); jdbc.update("DELETE FROM processing_task_versions"); jdbc.update("DELETE FROM processing_tasks"); jdbc.update("DELETE FROM service_invocations"); jdbc.update("DELETE FROM service_grants"); jdbc.update("DELETE FROM service_callers"); jdbc.update("DELETE FROM data_services"); jdbc.update("DELETE FROM dashboard_reports"); jdbc.update("DELETE FROM dashboards"); jdbc.update("DELETE FROM reports"); jdbc.update("DELETE FROM realtime_jobs"); jdbc.update("DELETE FROM dataset_role_fields"); jdbc.update("DELETE FROM dataset_role_rules"); jdbc.update("DELETE FROM dataset_fields"); jdbc.update("DELETE FROM datasets"); jdbc.update("DELETE FROM data_sources"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions");
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
        jdbc.update("INSERT INTO departments(id,name) VALUES (10,'test')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (10,'manager',?,10,TRUE,'version')", encoder.encode("password"));
        jdbc.update("INSERT INTO roles(id,name) VALUES (10,'manager')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (10,10)");
    }
    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","manager").param("password","password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    @Test void createDoesNotReturnPassword() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        mvc.perform(post("/api/data-sources").session(login()).with(csrf()).contentType("application/json").content("""
            {"name":"db1","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.password").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='datasource.create' AND actor_id=10", Integer.class));
    }
    @Test void unauthorizedRoleIsDenied() throws Exception {
        mvc.perform(post("/api/data-sources").session(login()).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void listReturnsMetadataWithoutSecrets() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        var session = login();
        mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"db2","profileId":"POSTGRESQL:general","host":"localhost","port":5432,"database":"warehouse","username":"reader","password":"sensitive"}
            """)).andExpect(status().isCreated());
        mvc.perform(get("/api/data-sources").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("db2"))
            .andExpect(jsonPath("$[0].password").doesNotExist())
            .andExpect(jsonPath("$[0].password_cipher").doesNotExist());
    }
    @Test void authorizedConnectionTestDoesNotExposeCredentials() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        var session = login();
        var result = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"db3","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """)).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        when(inspector.probe(any(), eq("sensitive"))).thenReturn(new DatabaseInspector.TestResult(true, null));
        mvc.perform(post("/api/data-sources/" + json.get("id").asLong() + "/test").session(session).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.password").doesNotExist());
    }
    @Test void connectionTestFailureReturnsErrorMessage() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.test')");
        var session = login();
        var result = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"db-err","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """)).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asLong();
        when(inspector.probe(any(), eq("sensitive"))).thenReturn(new DatabaseInspector.TestResult(false, "Access denied for user 'reader'"));
        mvc.perform(post("/api/data-sources/" + id + "/test").session(session).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.error").value("Access denied for user 'reader'"));
    }
    @Test void draftConnectionTestReusesStoredPasswordWhenBlank() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.test')");
        var session = login();
        var result = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"db-draft","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """)).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asLong();
        when(inspector.probe(argThat(d -> d != null && d.port() == 3307), eq("sensitive"))).thenReturn(new DatabaseInspector.TestResult(true, null));
        mvc.perform(post("/api/data-sources/test-config").session(session).with(csrf()).contentType("application/json").content("""
            {"id":%d,"name":"db-draft","profileId":"MYSQL:8.x","host":"localhost","port":3307,"database":"orders","username":"reader","password":null}
            """.formatted(id)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.password").doesNotExist());
        verify(inspector).probe(argThat(d -> d != null && d.port() == 3307), eq("sensitive"));
    }
    @Test void draftConnectionTestRequiresPasswordForNewSourceAndPermission() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create')");
        var session = login();
        mvc.perform(post("/api/data-sources/test-config").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"x","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader"}
            """)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.test')");
        mvc.perform(post("/api/data-sources/test-config").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"x","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":""}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false)).andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(post("/api/data-sources/test-config").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"x","profileId":"MYSQL:8.x","host":"bad host!","port":3306,"database":"orders","username":"reader","password":"p"}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false)).andExpect(jsonPath("$.error").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources", Integer.class));
    }
    @Test void authorizedUserCanReadDiscoveredTables() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        var session = login();
        var result = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"db4","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """)).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asLong();
        when(inspector.tables(any(), eq("sensitive"), isNull(), eq("sales"))).thenReturn(java.util.List.of(new DatabaseInspector.Table(null, "sales", "orders", "TABLE")));
        mvc.perform(get("/api/data-sources/" + id + "/tables").session(session).param("schema", "sales"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("orders"));
    }
    @Test void datasetRegistrationUsesDiscoveredFields() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        var session = login();
        var result = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"db5","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """)).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asLong();
        when(inspector.columns(any(), eq("sensitive"), isNull(), eq("sales"), eq("orders")))
            .thenReturn(java.util.List.of(new DatabaseInspector.Column("id", "BIGINT", 64, false)));
        mvc.perform(post("/api/datasets").session(session).with(csrf()).contentType("application/json")
            .content("{\"sourceId\":" + id + ",\"name\":\"orders dataset\",\"schema\":\"sales\",\"table\":\"orders\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dataset.create' AND actor_id=10", Integer.class));
        mvc.perform(get("/api/datasets").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("orders dataset"))
            .andExpect(jsonPath("$[0].fields[0].name").value("id"));
    }
    @Test void taskCreationRequiresPermissionAndValidConfiguration() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read'),(10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        var session = login();
        var created = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"tasks-db","profileId":"MYSQL:8.x","host":"localhost","port":3306,"database":"orders","username":"reader","password":"sensitive"}
            """)).andReturn();
        long source = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
            .content("{\"name\":\"daily\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(1));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.create' AND actor_id=10 AND result='SUCCESS'", Integer.class));
        mvc.perform(get("/api/tasks").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("daily"));
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
            .content("{\"name\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void savesWorkflowWithPinnedTaskVersion() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (10,'task','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (10,1,'{}')");
        var session = login();
        mvc.perform(post("/api/workflows").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"daily","nodes":[{"key":"a","taskId":10,"taskVersion":1}],"edges":[]}
            """)).andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/workflows").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("daily"));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='workflow.create' AND actor_id=10", Integer.class));
    }
    @Test void workflowRunCanBeSubmittedAndObserved() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (10,'task','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (10,1,'{}')");
        var session = login();
        var created = mvc.perform(post("/api/workflows").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"daily","nodes":[{"key":"a","taskId":10,"taskVersion":1}],"edges":[]}
            """)).andReturn();
        long workflow = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var submitted = mvc.perform(post("/api/workflows/" + workflow + "/runs").session(session).with(csrf()).contentType("application/json")
            .content("{\"version\":1,\"businessDate\":\"2026-11-16\"}"))
            .andExpect(status().isAccepted()).andReturn();
        long run = new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted.getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(jdbc.queryForObject("SELECT state FROM workflow_runs WHERE id=?", String.class, run)); i++) Thread.sleep(20);
        mvc.perform(get("/api/runs/" + run).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SUCCEEDED")).andExpect(jsonPath("$.nodes[0].state").value("SUCCEEDED"));
        verify(runner, times(1)).execute(any(), eq(java.time.LocalDate.of(2026, 11, 16)), any(), any());
    }
    @Test void failedRunCanBeRetriedWithAnotherAttempt() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (10,'task','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (10,1,'{}')");
        doThrow(new IllegalStateException()).doNothing().when(runner).execute(any(), any(), any(), any());
        var session = login();
        var created = mvc.perform(post("/api/workflows").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"daily","nodes":[{"key":"a","taskId":10,"taskVersion":1}],"edges":[]}
            """)).andReturn();
        long workflow = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var submitted = mvc.perform(post("/api/workflows/" + workflow + "/runs").session(session).with(csrf()).contentType("application/json")
            .content("{\"version\":1,\"businessDate\":\"2026-11-16\"}")).andExpect(status().isAccepted()).andReturn();
        long run = new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted.getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 100 && !"FAILED".equals(jdbc.queryForObject("SELECT state FROM workflow_runs WHERE id=?", String.class, run)); i++) Thread.sleep(20);
        mvc.perform(post("/api/runs/" + run + "/retry").session(session).with(csrf())).andExpect(status().isAccepted());
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(jdbc.queryForObject("SELECT state FROM workflow_runs WHERE id=?", String.class, run)); i++) Thread.sleep(20);
        mvc.perform(get("/api/runs/" + run).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"))
            .andExpect(jsonPath("$.nodes[0].attempts.length()").value(2)).andExpect(jsonPath("$.nodes[0].attempts[0].errorMessage").value("任务执行失败"));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_attempts WHERE run_id=?", Integer.class, run));
    }
    @Test void scheduleCanBeCreatedAndPaused() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (10,'task','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (10,1,'{}')");
        var session = login();
        var created = mvc.perform(post("/api/workflows").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"daily","nodes":[{"key":"a","taskId":10,"taskVersion":1}],"edges":[]}
            """)).andReturn();
        long workflow = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var scheduled = mvc.perform(post("/api/workflows/" + workflow + "/schedules").session(session).with(csrf()).contentType("application/json")
            .content("{\"version\":1,\"cron\":\"0 0 0 * * *\",\"zone\":\"Asia/Shanghai\"}"))
            .andExpect(status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(scheduled.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/schedules").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].enabled").value(true));
        mvc.perform(post("/api/schedules/" + id + "/enabled").session(session).with(csrf()).contentType("application/json").content("{\"enabled\":false}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/schedules").session(session)).andExpect(jsonPath("$[0].enabled").value(false));
    }
    @Test void backfillRequiresBusinessDateParametersAndCreatesOneRunPerDay() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        // backfill 仍通过顶层 sourceParameters 检测业务日期；ORCH 解析忽略额外顶层字段，节点内用 :business_date 占位符
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (10,'task','ORCH',1)");
        String orchWithDate = com.example.governance.workflow.OrchFixtures.dml(1, "UPDATE x SET day=:business_date");
        orchWithDate = orchWithDate.substring(0, orchWithDate.length() - 1) + ",\"sourceParameters\":[{\"type\":\"BUSINESS_DATE\"}]}";
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (10,1,?)", orchWithDate);
        var session = login();
        var created = mvc.perform(post("/api/workflows").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"daily","nodes":[{"key":"a","taskId":10,"taskVersion":1}],"edges":[]}
            """)).andReturn();
        long workflow = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/workflows/" + workflow + "/backfill").session(session).with(csrf()).contentType("application/json")
            .content("{\"version\":1,\"startDate\":\"2026-11-15\",\"endDate\":\"2026-11-16\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.ids.length()").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs", Integer.class));
        jdbc.update("UPDATE processing_task_versions SET configuration='{}' WHERE task_id=10 AND version=1");
        mvc.perform(post("/api/workflows/" + workflow + "/backfill").session(session).with(csrf()).contentType("application/json")
            .content("{\"version\":1,\"startDate\":\"2026-11-15\",\"endDate\":\"2026-11-16\"}"))
            .andExpect(status().isBadRequest());
        for (int i=0;i<100 && jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs WHERE state IN ('PENDING','RUNNING')", Integer.class)>0;i++) Thread.sleep(20);
    }
    @Test void datasetPreviewEnforcesDepartmentAndFieldMasking() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read'),(10,'report.report.read'),(10,'report.report.create'),(10,'report.report.update'),(10,'report.report.delete'),(10,'report.report.execute'),(10,'report.dashboard.read'),(10,'report.dashboard.create'),(10,'report.dashboard.update'),(10,'report.dashboard.delete')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,schema_name,table_name) VALUES (15,15,'clients','PUBLIC','CLIENTS')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (15,0,'DEPARTMENT_ID','BIGINT',FALSE),(15,1,'ID','INTEGER',FALSE),(15,2,'PHONE','VARCHAR',TRUE)");
        var business = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(business).execute("CREATE TABLE CLIENTS (ID INT, DEPARTMENT_ID BIGINT, PHONE VARCHAR(20))");
        new JdbcTemplate(business).execute("INSERT INTO CLIENTS VALUES (1,10,'13800000000'),(2,11,'13900000000')");
        when(executionSources.get(15)).thenReturn(business);
        var session = login();
        mvc.perform(post("/api/datasets/15/policies").session(session).with(csrf()).contentType("application/json").content("""
            {"departmentField":"DEPARTMENT_ID","roleId":10,"scope":"OWN","fields":{"ID":"FULL","PHONE":"MASKED"}}
            """)).andExpect(status().isNoContent());
        mvc.perform(post("/api/datasets/15/preview").session(session).with(csrf()).contentType("application/json").content("""
            {"fields":["ID","PHONE"],"limit":100}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].ID").value(1)).andExpect(jsonPath("$[0].PHONE").value("****"));
        mvc.perform(post("/api/datasets/15/preview").session(session).with(csrf()).contentType("application/json").content("""
            {"fields":["DEPARTMENT_ID"],"limit":100}
            """)).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM role_permissions WHERE role_id=10 AND permission='datasource.dataset.read'");
        mvc.perform(get("/api/datasets").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/accessible-datasets").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("clients"));
        mvc.perform(post("/api/datasets/15/analysis").session(session).with(csrf()).contentType("application/json").content("""
            {"groups":["ID"],"metrics":[{"operation":"COUNT"}],"filters":[],"limit":100}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$[0].ID").value(1)).andExpect(jsonPath("$[0].metric_0").value(1));
        mvc.perform(post("/api/datasets/15/analysis").session(session).with(csrf()).contentType("application/json").content("""
            {"groups":["PHONE"],"metrics":[{"operation":"COUNT"}],"filters":[],"limit":100}
            """)).andExpect(status().isForbidden());
        var report = mvc.perform(post("/api/reports").session(session).with(csrf()).contentType("application/json").content("""
            {"datasetId":15,"name":"客户统计","chartType":"BAR","kind":"ANALYSIS","configuration":{"groups":["ID"],"metrics":[{"operation":"COUNT"}],"filters":[],"limit":100}}
            """)).andExpect(status().isCreated()).andReturn();
        long reportId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(report.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/reports/" + reportId + "/run").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$[0].metric_0").value(1));
        jdbc.update("DELETE FROM role_permissions WHERE role_id=10 AND permission='datasource.query.read'");
        mvc.perform(post("/api/reports/" + reportId + "/run").session(session).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void organizationManagementRequiresPermissionAndNeverReturnsPassword() throws Exception {
        var session = login();
        mvc.perform(get("/api/organization").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.users").isEmpty());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'system.department.read'),(10,'system.department.create'),(10,'system.department.update'),(10,'system.department.delete'),(10,'system.role.read'),(10,'system.role.create'),(10,'system.role.update'),(10,'system.role.delete'),(10,'system.role.assign_permissions'),(10,'system.user.read'),(10,'system.user.create'),(10,'system.user.update'),(10,'system.user.delete'),(10,'system.user.assign_roles'),(10,'system.user.set_status'),(10,'system.user.reset_password'),(10,'system.menu.read'),(10,'system.menu.create'),(10,'system.menu.update'),(10,'system.menu.delete'),(10,'system.permission.read'),(10,'system.audit.read')");
        var department = mvc.perform(post("/api/organization/departments").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"Sales\",\"parentId\":10}"))
            .andExpect(status().isCreated()).andReturn();
        long departmentId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(department.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/organization/users").session(session).with(csrf()).contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"Very-long-password1\",\"departmentId\":" + departmentId + "}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/organization").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.users[?(@.username=='alice')].password").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='user.create'", Integer.class));
    }
    @Test void externalServiceUsesCallerPolicyAndRateLimit() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read'),(10,'service.service.read'),(10,'service.service.create'),(10,'service.service.submit'),(10,'service.service.approve'),(10,'service.caller.read'),(10,'service.caller.create'),(10,'service.caller.manage_grant'),(10,'service.caller.disable')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,schema_name,table_name) VALUES (15,15,'clients','PUBLIC','CLIENTS')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (15,0,'DEPARTMENT_ID','BIGINT',FALSE),(15,1,'ID','INTEGER',FALSE),(15,2,'PHONE','VARCHAR',TRUE)");
        var business = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(business).execute("CREATE TABLE CLIENTS (ID INT, DEPARTMENT_ID BIGINT, PHONE VARCHAR(20))");
        new JdbcTemplate(business).execute("INSERT INTO CLIENTS VALUES (1,10,'13800000000'),(2,11,'13900000000')");
        when(executionSources.get(15)).thenReturn(business);
        var session = login();
        mvc.perform(post("/api/datasets/15/policies").session(session).with(csrf()).contentType("application/json").content("""
            {"departmentField":"DEPARTMENT_ID","roleId":10,"scope":"OWN","fields":{"ID":"FULL","PHONE":"MASKED"}}
            """)).andExpect(status().isNoContent());
        var serviceResponse = mvc.perform(post("/api/services").session(session).with(csrf()).contentType("application/json").content("""
            {"datasetId":15,"name":"clients","fields":["ID","PHONE"],"limit":20}
            """)).andExpect(status().isCreated()).andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        long service = mapper.readTree(serviceResponse.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/services/" + service + "/submit").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/services/" + service + "/approve").session(session).with(csrf())).andExpect(status().isNoContent());
        var callerResponse = mvc.perform(post("/api/service-callers").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"app\",\"userId\":10,\"limitPerMinute\":1}"))
            .andExpect(status().isCreated()).andReturn();
        var caller = mapper.readTree(callerResponse.getResponse().getContentAsString());
        long callerId = caller.get("id").asLong(); String token = caller.get("token").asText();
        mvc.perform(post("/api/services/" + service + "/callers/" + callerId).session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/service-call/" + service)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/service-call/" + service).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].PHONE").value("****"));
        mvc.perform(get("/api/service-call/" + service).header("Authorization", "Bearer " + token)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/service-callers").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].token").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS", jdbc.queryForObject("SELECT outcome FROM service_invocations", String.class));
    }
    @Test void realtimeJobCanBeStartedAndStoppedByAuthorizedUser() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (15,'source','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher'),(16,'target','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var session = login();
        var response = mvc.perform(post("/api/realtime-jobs").session(session).with(csrf()).contentType("application/json").content("""
          {"name":"incremental","config":{"sourceId":15,"targetId":16,"intervalSeconds":10,"mapping":{"sourceTable":"src","targetTable":"dst","sourceColumns":["id"],"targetColumns":["id"],"watermarkColumn":"seq","tieColumn":"id","targetKeys":["id"],"watermarkType":"LONG","batchSize":100}}}
          """)).andExpect(status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/realtime-jobs/" + id + "/start").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/realtime-jobs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("RUNNING"));
        mvc.perform(post("/api/realtime-jobs/" + id + "/stop").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/realtime-jobs").session(session)).andExpect(jsonPath("$[0].state").value("STOPPED"));
    }
    @Test void workflowAndTaskCanReceiveNewImmutableVersions() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var session = login();
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.publish')");
        var task = mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json").content("""
          {"name":"daily"}
          """)).andExpect(status().isCreated()).andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); long id = mapper.readTree(task.getResponse().getContentAsString()).get("id").asLong();
        // 旧 POST /versions 已删除：草稿+发布产生不可变版本 1、2
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/" + id + "/draft").session(session).with(csrf()).contentType("application/json")
          .content("{\"expectedRevision\":1,\"definition\":" + com.example.governance.workflow.OrchFixtures.dml(15, "UPDATE t SET x=1") + "}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(post("/api/tasks/" + id + "/publish").session(session).with(csrf()).contentType("application/json").content("{\"expectedRevision\":2,\"comment\":\"v1\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/" + id + "/draft").session(session).with(csrf()).contentType("application/json")
          .content("{\"expectedRevision\":2,\"definition\":" + com.example.governance.workflow.OrchFixtures.dml(15, "UPDATE t SET x=2") + "}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        mvc.perform(post("/api/tasks/" + id + "/publish").session(session).with(csrf()).contentType("application/json").content("{\"expectedRevision\":3,\"comment\":\"v2\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        var created = mvc.perform(post("/api/workflows").session(session).with(csrf()).contentType("application/json")
          .content("{\"name\":\"flow\",\"nodes\":[{\"key\":\"a\",\"taskId\":" + id + ",\"taskVersion\":1}],\"edges\":[]}"))
          .andExpect(status().isCreated()).andReturn();
        long workflow = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/workflows/" + workflow + "/versions").session(session).with(csrf()).contentType("application/json")
          .content("{\"nodes\":[{\"key\":\"a\",\"taskId\":" + id + ",\"taskVersion\":2}],\"edges\":[]}"))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/workflows/" + workflow + "/versions/1").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.nodes[0].taskVersion").value(1));
    }
    @Test void standaloneTaskRunPinsVersionAndRequiresPermission() throws Exception {
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (77,'single','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (77,1,?)", com.example.governance.workflow.OrchFixtures.dml(1, "UPDATE t SET x=1"));
        var session = login();
        mvc.perform(post("/api/tasks/77/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-16\"}"))
            .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        var response = mvc.perform(post("/api/tasks/77/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-16\"}"))
            .andExpect(status().isAccepted()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 100 && jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE id=? AND state='SUCCEEDED'", Integer.class, id) == 0; i++) Thread.sleep(20);
        mvc.perform(get("/api/tasks/77/runs").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].taskVersion").value(1)).andExpect(jsonPath("$[0].businessDate").value("2026-11-16")).andExpect(jsonPath("$[0].state").value("SUCCEEDED"));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.run' AND actor_id=10", Integer.class));
    }
    @Test void updatingSourceRetainsPasswordWhenOmittedAndAuditsChange() throws Exception {
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        var session = login();
        var created = mvc.perform(post("/api/data-sources").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"original\",\"profileId\":\"MYSQL:8.x\",\"host\":\"localhost\",\"port\":3306,\"database\":\"db\",\"username\":\"u\",\"password\":\"secret\"}")).andExpect(status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        String before = jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/data-sources/"+id).session(session).with(csrf()).contentType("application/json").content("{\"name\":\"updated\",\"profileId\":\"MYSQL:8.x\",\"host\":\"localhost\",\"port\":3306,\"database\":\"db\",\"username\":\"u\"}")).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(before, jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id));
        org.junit.jupiter.api.Assertions.assertEquals("updated", jdbc.queryForObject("SELECT name FROM data_sources WHERE id=?", String.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='datasource.update'", Integer.class));
    }
    @Test void workflowRunHistoryRequiresPermissionAndFiltersByDate() throws Exception {
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (77,'task','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (77,1,'{}')");
        jdbc.update("INSERT INTO workflow_definitions(id,name,current_version) VALUES (77,'flow',1)");
        jdbc.update("INSERT INTO workflow_versions(workflow_id,version) VALUES (77,1)");
        jdbc.update("INSERT INTO workflow_runs(workflow_id,workflow_version,business_date,state,created_at) VALUES (77,1,DATE '2026-11-16','FAILED',CURRENT_TIMESTAMP)");
        var session = login();
        mvc.perform(get("/api/workflows/77/runs").session(session)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'workflow.task.read'),(10,'workflow.task.create'),(10,'workflow.task.update'),(10,'workflow.task.execute'),(10,'workflow.task.retry'),(10,'workflow.workflow.read'),(10,'workflow.workflow.create'),(10,'workflow.workflow.update'),(10,'workflow.workflow.execute'),(10,'workflow.workflow.retry'),(10,'workflow.workflow.validate'),(10,'workflow.schedule.read'),(10,'workflow.schedule.create'),(10,'workflow.schedule.update'),(10,'workflow.schedule.backfill'),(10,'workflow.realtime.read'),(10,'workflow.realtime.create'),(10,'workflow.realtime.update'),(10,'workflow.realtime.start'),(10,'workflow.realtime.stop')");
        mvc.perform(get("/api/workflows/77/runs?state=FAILED&date=2026-11-16").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("FAILED"));
        mvc.perform(get("/api/workflows/77/runs?state=OTHER").session(session)).andExpect(status().isBadRequest());
    }
    @Test void auditHistoryRequiresOrganizationPermissionAndSupportsActionFilter() throws Exception {
        var session = login();
        mvc.perform(get("/api/organization/audit").session(session)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'system.department.read'),(10,'system.department.create'),(10,'system.department.update'),(10,'system.department.delete'),(10,'system.role.read'),(10,'system.role.create'),(10,'system.role.update'),(10,'system.role.delete'),(10,'system.role.assign_permissions'),(10,'system.user.read'),(10,'system.user.create'),(10,'system.user.update'),(10,'system.user.delete'),(10,'system.user.assign_roles'),(10,'system.user.set_status'),(10,'system.user.reset_password'),(10,'system.menu.read'),(10,'system.menu.create'),(10,'system.menu.update'),(10,'system.menu.delete'),(10,'system.permission.read'),(10,'system.audit.read')");
        jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (10,CURRENT_TIMESTAMP,'role.permissions','role','42','SUCCESS')");
        mvc.perform(get("/api/organization/audit?action=role.permissions").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].action").value("role.permissions")).andExpect(jsonPath("$[0].username").value("manager")).andExpect(jsonPath("$[0].password").doesNotExist());
        mvc.perform(get("/api/organization/audit?limit=101").session(session)).andExpect(status().isBadRequest());
    }
    @Test void datasetPoliciesCanBeInspectedAndRevokedOnlyByManagers() throws Exception {
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (10,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name,department_field) VALUES (10,10,'data','t','DEPT')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (10,0,'DEPT','BIGINT',FALSE),(10,1,'ID','INT',FALSE)");
        jdbc.update("INSERT INTO dataset_role_rules(dataset_id,role_id,scope) VALUES (10,10,'OWN')");
        jdbc.update("INSERT INTO dataset_role_fields(dataset_id,role_id,field_name,access_mode) VALUES (10,10,'ID','FULL')");
        var session = login();
        mvc.perform(get("/api/datasets/10/policies").session(session)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        mvc.perform(get("/api/datasets/10/policies").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.departmentField").value("DEPT")).andExpect(jsonPath("$.rules[0].fields.ID").value("FULL"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/datasets/10/policies/10").session(session).with(csrf())).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dataset.policy.revoke' AND object_type='dataset_role_policy' AND object_id='10:10'", Integer.class));
        mvc.perform(get("/api/datasets/10/policies").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.rules").isEmpty());
    }
}
