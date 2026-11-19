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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_retry_api;DB_CLOSE_DELAY=-1")
class StandaloneTaskRetryApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;
    @MockitoBean CamelTaskRunner runner;
    @Test void retryRequiresPermissionMatchesTaskAndPreservesAttempts() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (501,'dept')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (501,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (501,'retry-user',?,501,TRUE,'v')",encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (501,501)");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (501,'task','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (501,1,'{}')");
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,attempt_no) VALUES (501,501,1,DATE '2026-11-16','FAILED',CURRENT_TIMESTAMP,1)");
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state,finished_at,error_message) VALUES (501,1,'FAILED',CURRENT_TIMESTAMP,'任务执行失败')");
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username","retry-user").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/tasks/501/runs/501/retry").session(session).with(csrf())).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (501,'workflow.task.read'),(501,'workflow.task.create'),(501,'workflow.task.update'),(501,'workflow.task.execute'),(501,'workflow.task.retry'),(501,'workflow.workflow.read'),(501,'workflow.workflow.create'),(501,'workflow.workflow.update'),(501,'workflow.workflow.execute'),(501,'workflow.workflow.retry'),(501,'workflow.workflow.validate'),(501,'workflow.schedule.read'),(501,'workflow.schedule.create'),(501,'workflow.schedule.update'),(501,'workflow.schedule.backfill'),(501,'workflow.realtime.read'),(501,'workflow.realtime.create'),(501,'workflow.realtime.update'),(501,'workflow.realtime.start'),(501,'workflow.realtime.stop')");
        mvc.perform(post("/api/tasks/502/runs/501/retry").session(session).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/501/runs/501/retry").session(session).with(csrf())).andExpect(status().isAccepted());
        for (int i=0;i<100 && !"SUCCEEDED".equals(jdbc.queryForObject("SELECT state FROM task_runs WHERE id=501",String.class));i++) Thread.sleep(20);
        mvc.perform(get("/api/tasks/501/runs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("SUCCEEDED")).andExpect(jsonPath("$[0].attempts[0].state").value("FAILED")).andExpect(jsonPath("$[0].attempts[1].state").value("SUCCEEDED"));
        mvc.perform(post("/api/tasks/501/runs/501/retry").session(session).with(csrf())).andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.retry' AND actor_id=501",Integer.class));
    }
}
