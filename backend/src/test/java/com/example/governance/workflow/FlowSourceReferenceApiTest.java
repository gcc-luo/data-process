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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:flow_source_ref_api;DB_CLOSE_DELAY=-1")
class FlowSourceReferenceApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;

    private static final String FLOW_CONFIG = """
        {"nodes":[
          {"key":"s","component":"SOURCE_TABLE","config":{"sourceId":1,"table":"orders"}},
          {"key":"t","component":"SINK_TABLE","config":{"sourceId":2,"table":"orders_copy","writeMode":"APPEND"}}],
         "edges":[{"from":"s","to":"t"}]}
        """;

    private MockHttpSession login() throws Exception {
        // 多个用例共享同一 H2 库：种子数据只插入一次，登录每个用例各自执行
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=731", Integer.class) == 0) {
            jdbc.update("INSERT INTO departments(id,name) VALUES (731,'dept')");
            jdbc.update("INSERT INTO roles(id,name) VALUES (731,'role')");
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (731,'flow-user',?,731,TRUE,'v')", encoder.encode("password"));
            jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (731,731)");
            for (String permission : new String[]{"workflow.task.create", "workflow.task.update", "workflow.task.read", "datasource.source.read", "datasource.source.delete"})
                jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (731,?)", permission);
        }
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "flow-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void flowSourceAndSinkReferencesGuardDataSourceDeletion() throws Exception {
        var session = login();
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'src','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher'),(2,'dst','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher'),(3,'free','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        // FLOW 创建：source 节点 sourceId=1、sink sourceId=2 均接线锁定
        var created = mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
                .content("{\"name\":\"flow-copy\",\"type\":\"FLOW\",\"configuration\":" + FLOW_CONFIG + "}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1)).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/tasks/" + id + "/versions").session(session).with(csrf()).contentType("application/json")
                .content("{\"configuration\":" + FLOW_CONFIG + "}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        // 来源与目标数据源均被 FLOW 版本引用 → 409
        mvc.perform(delete("/api/data-sources/1").session(session).with(csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/data-sources/2").session(session).with(csrf())).andExpect(status().isConflict());
        // 另插一个 SQL 任务引用 id=1，不影响上述结论
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (731,'sql-ref','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (731,1,'{\"sourceId\":1,\"sql\":\"SELECT 1\",\"parameters\":[]}')");
        mvc.perform(delete("/api/data-sources/1").session(session).with(csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/data-sources/2").session(session).with(csrf())).andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id IN (1,2)", Integer.class));
        // 无引用数据源可删 → 204
        mvc.perform(delete("/api/data-sources/3").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=3", Integer.class));
    }

    @Test void interceptorNodesDoNotBlockUnrelatedSourceDeletion() throws Exception {
        var session = login();
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (11,'filter-src','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher'),(12,'filter-dst','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher'),(13,'filter-free','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        // 含 FIELD_FILTER 的三节点 FLOW：拦截器合法地不含 sourceId，不应影响无关源的删除
        String flowWithFilter = """
            {"nodes":[
              {"key":"s","component":"SOURCE_TABLE","config":{"sourceId":11,"table":"orders"}},
              {"key":"f","component":"FIELD_FILTER","config":{"expression":"amount > 0"}},
              {"key":"t","component":"SINK_TABLE","config":{"sourceId":12,"table":"orders_copy","writeMode":"APPEND"}}],
             "edges":[{"from":"s","to":"f"},{"from":"f","to":"t"}]}
            """;
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
                .content("{\"name\":\"flow-filter\",\"type\":\"FLOW\",\"configuration\":" + flowWithFilter + "}"))
            .andExpect(status().isCreated());
        // 被引用的来源/目标仍 409
        mvc.perform(delete("/api/data-sources/11").session(session).with(csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/data-sources/12").session(session).with(csrf())).andExpect(status().isConflict());
        // 无引用源可删 → 204（缺陷现状：拦截器无 sourceId 被误判 Cannot check → 409）
        mvc.perform(delete("/api/data-sources/13").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=13", Integer.class));
    }

    @Test void corruptedFlowVersionFailsSafeOnDeletion() throws Exception {
        var session = login();
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (21,'corrupt-src','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (733,'corrupt-flow','FLOW',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (733,1,'{\"nodes\":[{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":21,\"table\":\"orders\"}},{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":21,\"table\":\"c\"}}],\"edges\":[{\"from\":\"s\",\"to\":\"t\"}]}')");
        // 损坏历史行：来源节点缺 sourceId → 真 fail-safe，删除任意源 409
        jdbc.update("UPDATE processing_task_versions SET configuration='{\"nodes\":[{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"table\":\"orders\"}},{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":21,\"table\":\"c\"}}],\"edges\":[{\"from\":\"s\",\"to\":\"t\"}]}' WHERE task_id=733 AND version=1");
        mvc.perform(delete("/api/data-sources/21").session(session).with(csrf())).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_sources WHERE id=21", Integer.class));
        // 清理损坏行后恢复可删，证明 409 确由损坏行触发（也避免污染其他用例）
        jdbc.update("DELETE FROM processing_task_versions WHERE task_id=733");
        jdbc.update("DELETE FROM processing_tasks WHERE id=733");
        mvc.perform(delete("/api/data-sources/21").session(session).with(csrf())).andExpect(status().isNoContent());
    }

    @Test void flowSaveErrorPassesThroughNodeMessage() throws Exception {
        var session = login();
        String badConfig = """
            {"nodes":[
              {"key":"s","component":"SOURCE_TABLE","config":{"sourceId":1,"table":"orders"}},
              {"key":"f","component":"FIELD_FILTER","config":{"expression":"1 +"}},
              {"key":"t","component":"SINK_TABLE","config":{"sourceId":2,"table":"orders_copy","writeMode":"APPEND"}}],
             "edges":[{"from":"s","to":"f"},{"from":"f","to":"t"}]}
            """;
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
                .content("{\"name\":\"bad-flow\",\"type\":\"FLOW\",\"configuration\":" + badConfig + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertTrue(((org.springframework.web.server.ResponseStatusException) result.getResolvedException()).getReason().contains("节点 f")));
        // 非 FLOW 类型保持通用报错，不放行内部消息
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
                .content("{\"name\":\"bad-sql\",\"type\":\"SQL\",\"configuration\":{\"sourceId\":1}}"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertEquals("Invalid task configuration", ((org.springframework.web.server.ResponseStatusException) result.getResolvedException()).getReason()));
        // 合法 FLOW 但引用不存在的数据源 → 400 reason 有文案（FLOW 放行）
        String missingSource = FLOW_CONFIG.replace("\"sourceId\":1", "\"sourceId\":99").replace("\"sourceId\":2", "\"sourceId\":100");
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
                .content("{\"name\":\"missing-src\",\"type\":\"FLOW\",\"configuration\":" + missingSource + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertEquals("数据源不存在", ((org.springframework.web.server.ResponseStatusException) result.getResolvedException()).getReason()));
    }
}
