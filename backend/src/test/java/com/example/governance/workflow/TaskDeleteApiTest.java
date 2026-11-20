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
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_delete_api;DB_CLOSE_DELAY=-1")
class TaskDeleteApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;

    private MockHttpSession login() throws Exception {
        // 多个用例共享同一 H2 库：种子数据只插入一次，登录每个用例各自执行
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=641", Integer.class) == 0) {
            jdbc.update("INSERT INTO departments(id,name) VALUES (641,'dept')");
            jdbc.update("INSERT INTO roles(id,name) VALUES (641,'role')");
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (641,'delete-user',?,641,TRUE,'v')", encoder.encode("password"));
            jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (641,641)");
            for (String permission : new String[]{"workflow.task.read", "workflow.task.delete"})
                jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (641,?)", permission);
        }
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "delete-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void deleteGuardsWorkflowReferencesAndCascadesRuns() throws Exception {
        var session = login();
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (641,'被引用','SQL',1),(642,'可删除','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (641,1,'{}'),(642,1,'{}')");
        jdbc.update("INSERT INTO workflow_definitions(id,name,current_version) VALUES (641,'wf',1)");
        jdbc.update("INSERT INTO workflow_versions(workflow_id,version) VALUES (641,1)");
        jdbc.update("INSERT INTO workflow_nodes(workflow_id,workflow_version,node_key,ordinal_no,task_id,task_version) VALUES (641,1,'n1',0,641,1)");
        // 被工作流引用 → 409
        mvc.perform(delete("/api/tasks/641").session(session).with(csrf())).andExpect(status().isConflict());
        // 有运行历史但无引用 → 级联删除
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,attempt_no) VALUES (641,642,1,DATE '2026-11-20','SUCCEEDED',CURRENT_TIMESTAMP,1)");
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state,finished_at) VALUES (641,1,'SUCCEEDED',CURRENT_TIMESTAMP)");
        mvc.perform(delete("/api/tasks/642").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_tasks WHERE id=642", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_task_versions WHERE task_id=642", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=642", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_run_attempts WHERE run_id=641", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.delete' AND actor_id=641 AND object_type='task' AND object_id='642'", Integer.class));
        // 不存在 → 404
        mvc.perform(delete("/api/tasks/999").session(session).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void deleteWhileRunningConflicts() throws Exception {
        var session = login();
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (643,'运行中','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (643,1,'{}')");
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,attempt_no) VALUES (643,643,1,DATE '2026-11-20','PENDING',CURRENT_TIMESTAMP,1)");
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state) VALUES (643,1,'PENDING')");
        // 运行中（PENDING/RUNNING）→ 409
        mvc.perform(delete("/api/tasks/643").session(session).with(csrf())).andExpect(status().isConflict());
        // 终态后可删除，attempts 级联清除
        jdbc.update("UPDATE task_runs SET state='SUCCEEDED',finished_at=CURRENT_TIMESTAMP WHERE id=643");
        jdbc.update("UPDATE task_run_attempts SET state='SUCCEEDED',finished_at=CURRENT_TIMESTAMP WHERE run_id=643");
        mvc.perform(delete("/api/tasks/643").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_tasks WHERE id=643", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_run_attempts WHERE run_id=643", Integer.class));
    }

    @Test void deleteWithoutPermissionForbidden() throws Exception {
        login();
        jdbc.update("INSERT INTO roles(id,name) VALUES (644,'read-only')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (644,'delete-readonly',?,641,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (644,644)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (644,'workflow.task.read')");
        var readOnly = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "delete-readonly").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        // 无 workflow.task.delete 权限 → 403
        mvc.perform(delete("/api/tasks/642").session(readOnly).with(csrf())).andExpect(status().isForbidden());
    }
}
