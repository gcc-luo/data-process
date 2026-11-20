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
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_metadata_api;DB_CLOSE_DELAY=-1")
class TaskMetadataApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;

    private MockHttpSession login() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (621,'dept')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (621,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (621,'meta-user',?,621,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (621,621)");
        for (String permission : new String[]{"workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.execute", "datasource.source.read"})
            jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (621,?)", permission);
        jdbc.update("INSERT INTO task_directories(id,parent_id,name,sort_order) VALUES (621,NULL,'订单域',0)");
        // 创建任务时 verify() 会锁定 sourceId 对应的数据源，测试库需先存在 id=1 的数据源
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "meta-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void listCreateAndMetadataUpdate() throws Exception {
        var session = login();
        long directory = 621;
        // 创建带目录与描述
        mvc.perform(post("/api/tasks").session(session).with(csrf()).contentType("application/json")
                .content("{\"name\":\"加工任务\",\"type\":\"SQL\",\"configuration\":{\"sourceId\":1,\"sql\":\"SELECT 1\",\"parameters\":[]},\"directoryId\":" + directory + ",\"description\":\"每日加工\"}"))
                .andExpect(status().isCreated());
        long taskId = jdbc.queryForObject("SELECT id FROM processing_tasks WHERE name='加工任务'", Long.class);
        // 列表富化：无运行时 STOPPED（本测试库仅此一任务，用 $[0]）
        mvc.perform(get("/api/tasks").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runState").value("STOPPED"))
                .andExpect(jsonPath("$[0].directoryId").value(directory))
                .andExpect(jsonPath("$[0].description").value("每日加工"));
        // 插入运行中实例 → RUNNING + lastRun
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,started_at,attempt_no) VALUES (621,?,1,DATE '2026-11-20','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)", taskId);
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state,started_at) VALUES (621,1,'RUNNING',CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/tasks").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runState").value("RUNNING"))
                .andExpect(jsonPath("$[0].lastRun.state").value("RUNNING"));
        // 运行中禁止改基本信息
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"name\":\"改名\"}"))
                .andExpect(status().isConflict());
        // 结束运行后可编辑：仅改描述，名称与目录保持
        jdbc.update("UPDATE task_runs SET state='SUCCEEDED',finished_at=CURRENT_TIMESTAMP WHERE id=621");
        jdbc.update("UPDATE task_run_attempts SET state='SUCCEEDED',finished_at=CURRENT_TIMESTAMP WHERE run_id=621");
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"description\":\"新描述\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("加工任务"))
                .andExpect(jsonPath("$[0].description").value("新描述"))
                .andExpect(jsonPath("$[0].directoryId").value(directory))
                .andExpect(jsonPath("$[0].runState").value("STOPPED"));
        // 停止状态下非法类型仍拒绝：name 不可为 null/数字，description 不可为数字
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"name\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"name\":123}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"description\":123}"))
                .andExpect(status().isBadRequest());
        // 清空目录（显式 null）
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"directoryId\":null}"))
                .andExpect(status().isNoContent());
        assertNull(jdbc.queryForObject("SELECT directory_id FROM processing_tasks WHERE id=" + taskId, Long.class));
    }
}
