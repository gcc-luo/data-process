package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_stop_api;DB_CLOSE_DELAY=-1")
class TaskStopApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;
    @MockitoBean CamelTaskRunner runner;
    @MockitoBean TaskExecutionLimiter limiter;

    private MockHttpSession login() throws Exception {
        // 三个用例共享同一 H2 库：种子数据只插入一次，登录每个用例各自执行
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=631", Integer.class) == 0) {
            jdbc.update("INSERT INTO departments(id,name) VALUES (631,'dept')");
            jdbc.update("INSERT INTO roles(id,name) VALUES (631,'role')");
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (631,'stop-user',?,631,TRUE,'v')", encoder.encode("password"));
            jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (631,631)");
            for (String permission : new String[]{"workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.execute", "workflow.task.delete"})
                jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (631,?)", permission);
        }
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "stop-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private void insertTask(long id) {
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (?,'stop-task','SQL',1)", id);
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (?,1,'{}')", id);
    }

    private void awaitState(long runId, String state) throws InterruptedException {
        for (int i = 0; i < 200 && !state.equals(jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId)); i++) Thread.sleep(20);
        assertEquals(state, jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, runId));
    }

    @Test void stopInterruptsRunningTaskAndGuardsWhileActive() throws Exception {
        var session = login();
        insertTask(631);
        Mockito.when(limiter.acquire()).thenAnswer(invocation -> new TaskExecutionLimiter(8).acquire());
        CountDownLatch started = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            RunCancelContext cancel = invocation.getArgument(2);
            started.countDown();
            while (!cancel.isCancelled()) Thread.sleep(10);
            throw new InterruptedException("手动停止");
        }).when(runner).execute(Mockito.any(), Mockito.any(), Mockito.any());
        mvc.perform(post("/api/tasks/631/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-20\"}"))
                .andExpect(status().isAccepted());
        long runId = jdbc.queryForObject("SELECT id FROM task_runs WHERE task_id=631", Long.class);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        awaitState(runId, "RUNNING");
        // 运行中：重复启动 / 改基本信息 均 409（DELETE 守卫由任务 5 补测）
        mvc.perform(post("/api/tasks/631/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-20\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/tasks/631").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isConflict());
        // 停止
        mvc.perform(post("/api/tasks/631/stop").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.stopped").value(1));
        awaitState(runId, "INTERRUPTED");
        assertEquals("手动停止", jdbc.queryForObject("SELECT error_message FROM task_runs WHERE id=?", String.class, runId));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_run_attempts WHERE run_id=? AND attempt_no=1", String.class, runId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.stop' AND actor_id=631 AND object_type='task' AND object_id='631'", Integer.class));
        // 停止后可编辑
        mvc.perform(put("/api/tasks/631").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"改名\"}"))
                .andExpect(status().isNoContent());
    }

    @Test void stopCancelsRunQueuedOnLimiter() throws Exception {
        var session = login();
        insertTask(632);
        CountDownLatch acquired = new CountDownLatch(1);
        Mockito.when(limiter.acquire()).thenAnswer(invocation -> {
            acquired.countDown();
            try { Thread.sleep(60000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
            return new TaskExecutionLimiter(8).acquire();
        });
        Mockito.doAnswer(invocation -> null).when(runner).execute(Mockito.any(), Mockito.any(), Mockito.any());
        mvc.perform(post("/api/tasks/632/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-20\"}"))
                .andExpect(status().isAccepted());
        long runId = jdbc.queryForObject("SELECT id FROM task_runs WHERE task_id=632", Long.class);
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        awaitState(runId, "RUNNING");
        mvc.perform(post("/api/tasks/632/stop").session(session).with(csrf())).andExpect(status().isOk());
        awaitState(runId, "INTERRUPTED");
        assertEquals("手动停止", jdbc.queryForObject("SELECT error_message FROM task_runs WHERE id=?", String.class, runId));
    }

    @Test void stopRaceAfterBusinessCompletionMarksInterrupted() throws Exception {
        var session = login();
        insertTask(634);
        Mockito.when(limiter.acquire()).thenAnswer(invocation -> new TaskExecutionLimiter(8).acquire());
        // 模拟 stop 与业务完成竞态：runner 内置取消（含中断 worker）后正常返回，终态应为 INTERRUPTED+手动停止
        Mockito.doAnswer(invocation -> { RunCancelContext cancel = invocation.getArgument(2); cancel.cancel(); return null; })
                .when(runner).execute(Mockito.any(), Mockito.any(), Mockito.any());
        mvc.perform(post("/api/tasks/634/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-20\"}"))
                .andExpect(status().isAccepted());
        long runId = jdbc.queryForObject("SELECT id FROM task_runs WHERE task_id=634", Long.class);
        awaitState(runId, "INTERRUPTED");
        assertEquals("手动停止", jdbc.queryForObject("SELECT error_message FROM task_runs WHERE id=?", String.class, runId));
    }

    @Test void stopWithoutActiveRunConflicts() throws Exception {
        var session = login();
        insertTask(633);
        mvc.perform(post("/api/tasks/633/stop").session(session).with(csrf())).andExpect(status().isConflict());
    }
}
