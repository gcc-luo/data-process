package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:flow_test_run_api;DB_CLOSE_DELAY=-1")
class FlowTestRunApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;
    @MockitoBean com.example.governance.assets.ExecutionDataSourceProvider provider;

    private MockHttpSession login() throws Exception {
        // 两个用例共享同一 H2 库：种子数据只插入一次；权限用 MERGE 恢复（用例一会删除 execute 权限）
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=811", Integer.class) == 0) {
            jdbc.update("INSERT INTO departments(id,name) VALUES (811,'dept')");
            jdbc.update("INSERT INTO roles(id,name) VALUES (811,'role')");
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (811,'flow-user',?,811,TRUE,'v')", encoder.encode("password"));
            jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (811,811)");
        }
        jdbc.update("MERGE INTO role_permissions(role_id,permission) KEY(role_id,permission) VALUES (811,'workflow.task.execute'),(811,'workflow.task.read')");
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "flow-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void testRunPreviewsWithoutWritingAndRequiresPermission() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:flow_src_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var src = new JdbcTemplate(ds);
        src.update("CREATE TABLE orders(id INT PRIMARY KEY, amount DECIMAL(10,2))");
        src.update("INSERT INTO orders VALUES (1,10),(2,-1),(3,20)");
        src.update("CREATE TABLE orders_copy(id INT PRIMARY KEY, amount DECIMAL(10,2))");
        Mockito.when(provider.get(Mockito.anyLong())).thenReturn(ds);
        var session = login();
        String config = "{\"nodes\":[{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},{\"key\":\"f\",\"component\":\"FIELD_FILTER\",\"config\":{\"expression\":\"amount > 0\"}},{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}}],\"edges\":[{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"}]}";
        mvc.perform(post("/api/tasks/flow/test-run").session(session).with(csrf()).contentType("application/json").content("{\"configuration\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previews.t.rows.length()").value(2))
                .andExpect(jsonPath("$.stats.length()").value(3));
        assertEquals(0, src.queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (812,'flow-none',?,811,TRUE,'v2')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (812,811)");
        jdbc.update("DELETE FROM role_permissions WHERE role_id=811 AND permission='workflow.task.execute'");
        var none = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "flow-none").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/tasks/flow/test-run").session(none).with(csrf()).contentType("application/json").content("{\"configuration\":" + config + "}"))
                .andExpect(status().isForbidden());
    }

    @Test void timeoutCancelsAndReturns408() throws Exception {
        var session = login();
        // 源端 getConnection 阻塞于可中断 latch：只有 cancel 前置 + worker interrupt 才能迅速退出
        var gate = new java.util.concurrent.CountDownLatch(1);
        var blocking = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{javax.sql.DataSource.class}, (p, m, a) -> {
            if (m.getName().equals("getConnection")) { gate.await(); return null; }
            throw new UnsupportedOperationException(m.getName());
        });
        Mockito.when(provider.get(Mockito.anyLong())).thenReturn(blocking);
        String config = "{\"nodes\":[{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}}],\"edges\":[{\"from\":\"s\",\"to\":\"t\"}]}";
        // 请求放独立守护线程并加 45s 上限：缺陷现状（close 先于 cancel）会永久挂起，红阶段有界失败
        var request = new java.util.concurrent.FutureTask<Object[]>(() -> {
            long start = System.nanoTime();
            var result = mvc.perform(post("/api/tasks/flow/test-run").session(session).with(csrf()).contentType("application/json").content("{\"configuration\":" + config + "}")).andReturn();
            return new Object[]{result, (System.nanoTime() - start) / 1_000_000};
        });
        var thread = new Thread(request);
        thread.setDaemon(true);
        thread.start();
        try {
            Object[] pair;
            try { pair = request.get(45, java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException hung) { request.cancel(true); throw new AssertionError("请求挂起超过 45s：close 先于 cancel，30s 上限落空", hung); }
            var result = (org.springframework.test.web.servlet.MvcResult) pair[0];
            long elapsed = (Long) pair[1];
            assertEquals(408, result.getResponse().getStatus());
            assertTrue(elapsed < 40_000, "应取消后迅速返回而非等流程跑完, elapsed=" + elapsed + "ms");
        } finally { gate.countDown(); } // 失败路径释放 worker，避免泄漏
    }

    @Test void invalidFlowConfigurationRejected() throws Exception {
        var session = login();
        mvc.perform(post("/api/tasks/flow/test-run").session(session).with(csrf()).contentType("application/json").content("{\"configuration\":{\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isBadRequest());
    }
}
