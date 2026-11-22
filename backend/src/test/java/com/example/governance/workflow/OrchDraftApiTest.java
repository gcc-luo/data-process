package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:orch_draft_api;DB_CLOSE_DELAY=-1")
class OrchDraftApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;
    @MockitoBean CamelTaskRunner runner;
    private static final ObjectMapper M = new ObjectMapper();

    private MockHttpSession login(String username, long id, boolean publish) throws Exception {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, id) == 0) {
            jdbc.update("INSERT INTO departments(id,name) VALUES (?,?)", id, "dept"+id);
            jdbc.update("INSERT INTO roles(id,name) VALUES (?,?)", id, "role"+id);
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (?,?,?, ?,TRUE,'v')", id, username, encoder.encode("password"), id);
            jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", id, id);
            for (String p : new String[]{"workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.execute", "workflow.task.delete", "workflow.task.retry", "datasource.source.read"})
                jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,?)", id, p);
            if (publish) jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,'workflow.task.publish')", id);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=1", Integer.class) == 0) {
            jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'src','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
            jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (2,'dst','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        }
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", username).param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private long create(MockHttpSession s, String name) throws Exception {
        var res = mvc.perform(post("/api/tasks").session(s).with(csrf()).contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(1)).andReturn();
        return M.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test void draftRevisionConflictPublishAndVersionImmutability() throws Exception {
        var s = login("orch-dev", 701, true);
        long id = create(s, "订单同步");
        // draft starts empty
        mvc.perform(get("/api/tasks/" + id + "/draft").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.publishedVersion").value(0));
        String def = OrchFixtures.sync(1, 2, "SELECT id FROM src", "dst", java.util.List.of("id"), "APPEND", java.util.List.of());
        mvc.perform(put("/api/tasks/" + id + "/draft").session(s).with(csrf()).contentType("application/json")
                .content("{\"expectedRevision\":1,\"definition\":" + def + "}")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        // stale revision → 409 with currentRevision
        mvc.perform(put("/api/tasks/" + id + "/draft").session(s).with(csrf()).contentType("application/json")
                .content("{\"expectedRevision\":1,\"definition\":" + def + "}")).andExpect(status().isConflict()).andExpect(jsonPath("$.currentRevision").value(2));
        // validate current draft → valid
        mvc.perform(post("/api/tasks/" + id + "/validate").session(s).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        // publish freezes version 1
        mvc.perform(post("/api/tasks/" + id + "/publish").session(s).with(csrf()).contentType("application/json").content("{\"expectedRevision\":2,\"comment\":\"首发\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/tasks/" + id + "/versions").session(s)).andExpect(jsonPath("$[0].version").value(1)).andExpect(jsonPath("$[0].comment").value("首发"));
        String published = mvc.perform(get("/api/tasks/" + id + "/versions/1").session(s)).andReturn().getResponse().getContentAsString();
        // further draft edits do not alter published version
        mvc.perform(put("/api/tasks/" + id + "/draft").session(s).with(csrf()).contentType("application/json")
                .content("{\"expectedRevision\":2,\"definition\":" + OrchFixtures.dml(1, "UPDATE t SET x=1") + "}")).andExpect(jsonPath("$.revision").value(3));
        String after = mvc.perform(get("/api/tasks/" + id + "/versions/1").session(s)).andReturn().getResponse().getContentAsString();
        assertEquals(M.readTree(published).get("definition"), M.readTree(after).get("definition"));
    }

    @Test void invalidDraftCannotPublish() throws Exception {
        var s = login("orch-dev2", 702, true);
        long id = create(s, "坏流程");
        String cyclic = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"a\",\"component\":\"transform.filter\",\"name\":\"a\",\"enabled\":true,\"config\":{\"expression\":\"id > 0\"}}," +
                "{\"id\":\"b\",\"component\":\"transform.filter\",\"name\":\"b\",\"enabled\":true,\"config\":{\"expression\":\"id > 1\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"a\",\"fromPort\":\"out\",\"to\":\"b\",\"toPort\":\"in\",\"kind\":\"DATA\"},{\"id\":\"e2\",\"from\":\"b\",\"fromPort\":\"out\",\"to\":\"a\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        mvc.perform(put("/api/tasks/" + id + "/draft").session(s).with(csrf()).contentType("application/json").content("{\"expectedRevision\":1,\"definition\":" + cyclic + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/validate").session(s).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.errors[0].code").value("WORKFLOW_CYCLE_DETECTED"));
        mvc.perform(post("/api/tasks/" + id + "/publish").session(s).with(csrf()).contentType("application/json").content("{\"expectedRevision\":2}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void publishRequiresPermission() throws Exception {
        var s = login("orch-nopub", 703, false);
        long id = create(s, "无发布权");
        mvc.perform(put("/api/tasks/" + id + "/draft").session(s).with(csrf()).contentType("application/json")
                .content("{\"expectedRevision\":1,\"definition\":" + OrchFixtures.dml(1, "UPDATE t SET x=1") + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/publish").session(s).with(csrf()).contentType("application/json").content("{\"expectedRevision\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test void runPinsVersionAndExposesNodeStateAndLogs() throws Exception {
        var s = login("orch-run", 704, true);
        long id = create(s, "运行任务");
        mvc.perform(put("/api/tasks/" + id + "/draft").session(s).with(csrf()).contentType("application/json")
                .content("{\"expectedRevision\":1,\"definition\":" + OrchFixtures.dml(1, "UPDATE t SET x=1") + "}")).andExpect(status().isOk());
        // unpublished run → 409
        mvc.perform(post("/api/tasks/" + id + "/runs").session(s).with(csrf()).contentType("application/json").content("{\"businessDate\":\"2026-11-21\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tasks/" + id + "/publish").session(s).with(csrf()).contentType("application/json").content("{\"expectedRevision\":2}")).andExpect(status().isOk());
        var res = mvc.perform(post("/api/tasks/" + id + "/runs").session(s).with(csrf()).contentType("application/json").content("{\"businessDate\":\"2026-11-21\"}"))
                .andExpect(status().isAccepted()).andReturn();
        long runId = M.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 200 && !"SUCCEEDED".equals(jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId)); i++) Thread.sleep(20);
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId));
        // seed node state + log lines, verify console APIs
        jdbc.update("INSERT INTO task_node_runs(run_id,attempt_no,node_key,state,rows_in,rows_out,duration_ms,started_at,finished_at) VALUES (?,1,'n_sql','SUCCEEDED',0,1,5,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", runId);
        jdbc.update("INSERT INTO execution_logs(owner_type,owner_id,node_key,attempt_no,line_no,level,message) VALUES ('TASK_RUN',?,'n_sql',1,1,'INFO','节点 n_sql 完成')", runId);
        mvc.perform(get("/api/task-runs/" + runId).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED")).andExpect(jsonPath("$.nodes[0].nodeKey").value("n_sql")).andExpect(jsonPath("$.nodes[0].rowsOut").value(1));
        mvc.perform(get("/api/task-runs/" + runId + "/logs").session(s).param("nodeKey", "n_sql")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("节点 n_sql 完成"));
        mvc.perform(get("/api/task-runs/" + runId + "/logs").session(s).param("nodeKey", "other")).andExpect(jsonPath("$").isEmpty());
    }

    @Test void componentsRegistryMarksUnsupportedDisabled() throws Exception {
        var s = login("orch-comp", 705, false);
        var res = mvc.perform(get("/api/orch/components").session(s)).andExpect(status().isOk()).andReturn();
        var arr = M.readTree(res.getResponse().getContentAsString());
        assertTrue(arr.isArray());
        boolean foundKafkaDisabled = false, foundJdbcEnabled = false;
        for (var c : arr) {
            if (c.path("type").asText().equals("sink.kafka")) foundKafkaDisabled = !c.path("enabled").asBoolean();
            if (c.path("type").asText().equals("source.mysql")) foundJdbcEnabled = c.path("enabled").asBoolean();
        }
        assertTrue(foundKafkaDisabled);
        assertTrue(foundJdbcEnabled);
    }
}