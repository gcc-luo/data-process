package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** PENDING 停止路径：独立测试类 mock workflowExecutor（TaskStopApiTest 其他用例依赖真实派发，@MockitoBean 全类共享会冲突）。 */
@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_stop_pending;DB_CLOSE_DELAY=-1")
class TaskStopPendingTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;
    @MockitoBean(name = "workflowExecutor") ThreadPoolTaskExecutor executor;
    @MockitoBean CamelTaskRunner runner;

    @Test void stopPendingRunPreventsWorkerFromExecutingBusiness() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (641,'dept')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (641,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (641,'pending-user',?,641,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (641,641)");
        for (String permission : new String[]{"workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.execute"})
            jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (641,?)", permission);
        var session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "pending-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (641,'pending-task','ORCH',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (641,1,'{}')");
        // executor 被 mock：派发被捕获但不执行，run 停留 PENDING
        mvc.perform(post("/api/tasks/641/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-20\"}"))
                .andExpect(status().isAccepted());
        long runId = jdbc.queryForObject("SELECT id FROM task_runs WHERE task_id=641", Long.class);
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId));
        ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);
        Mockito.verify(executor).execute(worker.capture());
        // PENDING 停止 → 直接落终态 INTERRUPTED+手动停止
        mvc.perform(post("/api/tasks/641/stop").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.stopped").value(1));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId));
        assertEquals("手动停止", jdbc.queryForObject("SELECT error_message FROM task_runs WHERE id=?", String.class, runId));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_run_attempts WHERE run_id=? AND attempt_no=1", String.class, runId));
        // 手动执行捕获的 worker：claim 失败不执行业务，也不覆盖终态
        worker.getValue().run();
        Mockito.verifyNoInteractions(runner);
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId));
        assertEquals("手动停止", jdbc.queryForObject("SELECT error_message FROM task_runs WHERE id=?", String.class, runId));
    }
}
