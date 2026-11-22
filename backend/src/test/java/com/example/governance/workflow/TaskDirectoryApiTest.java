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
import java.util.List;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_directory_api;DB_CLOSE_DELAY=-1")
class TaskDirectoryApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;

    private MockHttpSession login(String username, long id, String... permissions) throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (?,?)", id, "dept" + id);
        jdbc.update("INSERT INTO roles(id,name) VALUES (?,?)", id, "role" + id);
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (?,?,?,?,TRUE,?)", id, username, encoder.encode("password"), id, "v" + id);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", id, id);
        for (String permission : permissions) jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,?)", id, permission);
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", username).param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void directoryCrudGuardsAndSort() throws Exception {
        var session = login("dir-user", 601, "workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.delete");
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"订单域\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());
        long root = jdbc.queryForObject("SELECT id FROM task_directories WHERE name='订单域'", Long.class);
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"明细\",\"parentId\":" + root + "}"))
                .andExpect(status().isCreated());
        long child = jdbc.queryForObject("SELECT id FROM task_directories WHERE name='明细'", Long.class);
        mvc.perform(put("/api/task-directories/" + child).session(session).with(csrf()).contentType("application/json").content("{\"name\":\"订单明细\"}"))
                .andExpect(status().isNoContent());
        assertEquals("订单明细", jdbc.queryForObject("SELECT name FROM task_directories WHERE id=" + child, String.class));
        // 含子目录禁止删除
        mvc.perform(delete("/api/task-directories/" + root).session(session).with(csrf())).andExpect(status().isConflict());
        // 含任务禁止删除
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version,directory_id) VALUES (601,'t','ORCH',1,?)", child);
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (601,1,'{}')");
        mvc.perform(delete("/api/task-directories/" + child).session(session).with(csrf())).andExpect(status().isConflict());
        // 移入自身子孙禁止
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"id\":" + root + ",\"parentId\":" + child + ",\"sortOrder\":0},{\"id\":" + child + ",\"parentId\":" + root + ",\"sortOrder\":1}]}"))
                .andExpect(status().isConflict());
        // 合法排序：child 提为顶级，root 挂到 child 下
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"id\":" + child + ",\"parentId\":null,\"sortOrder\":0},{\"id\":" + root + ",\"parentId\":" + child + ",\"sortOrder\":1}]}"))
                .andExpect(status().isNoContent());
        assertEquals(child, jdbc.queryForObject("SELECT parent_id FROM task_directories WHERE id=" + root, Long.class));
        // 清空后可删
        jdbc.update("UPDATE processing_tasks SET directory_id=NULL WHERE id=601");
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"id\":" + child + ",\"parentId\":null,\"sortOrder\":0},{\"id\":" + root + ",\"parentId\":null,\"sortOrder\":1}]}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/task-directories/" + root).session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/task-directories/" + child).session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_directories", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='directory.create' AND actor_id=601", Integer.class));
        // 空 payload（items 缺失）应返回 400 而非 500
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        // 名称先 trim 再校验长度：120 字符加尾部空格不应误拒
        String longName = "n".repeat(120);
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"" + longName + "  \"}"))
                .andExpect(status().isCreated());
        assertEquals(longName, jdbc.queryForObject("SELECT name FROM task_directories WHERE name=?", String.class, longName));
    }

    @Test void directoryEndpointsRequirePermission() throws Exception {
        var session = login("dir-none", 602);
        mvc.perform(get("/api/task-directories").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json").content("{\"items\":[]}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/task-directories/1").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/task-directories/1").session(session).with(csrf())).andExpect(status().isForbidden());
    }

    @Test void guardBranchesReturnClientErrors() throws Exception {
        var session = login("dir-guard", 603, "workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.delete");
        // create 名称守卫：空名/纯空格/121 字符
        for (String name : List.of("", "   ", "x".repeat(121))) {
            mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        // create 未知 parentId
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"x\",\"parentId\":999999}"))
                .andExpect(status().isBadRequest());
        // rename 不存在 id → 404；delete 不存在 id → 404
        mvc.perform(put("/api/task-directories/999999").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"ok\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/task-directories/999999").session(session).with(csrf())).andExpect(status().isNotFound());
        // 自建两个目录，覆盖 sort 守卫分支
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"守卫甲\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"守卫乙\"}")).andExpect(status().isCreated());
        long a = jdbc.queryForObject("SELECT id FROM task_directories WHERE name='守卫甲'", Long.class);
        long b = jdbc.queryForObject("SELECT id FROM task_directories WHERE name='守卫乙'", Long.class);
        // rename 空名 → 400
        mvc.perform(put("/api/task-directories/" + a).session(session).with(csrf()).contentType("application/json").content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        // sort 重复 id → 400
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"id\":" + a + ",\"parentId\":null,\"sortOrder\":0},{\"id\":" + a + ",\"parentId\":null,\"sortOrder\":1}]}"))
                .andExpect(status().isBadRequest());
        // sort 覆盖不全（库中至少两个目录，只提交一个）→ 400
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"id\":" + a + ",\"parentId\":null,\"sortOrder\":0}]}"))
                .andExpect(status().isBadRequest());
        // sort 含未知 id → 400
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"id\":999999,\"parentId\":null,\"sortOrder\":0}]}"))
                .andExpect(status().isBadRequest());
        // sort items 含 null 元素 → 400
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json")
                .content("{\"items\":[null]}"))
                .andExpect(status().isBadRequest());
        // 清理，避免污染其他用例的全量断言
        mvc.perform(delete("/api/task-directories/" + a).session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/task-directories/" + b).session(session).with(csrf())).andExpect(status().isNoContent());
    }
}
