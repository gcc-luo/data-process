# 数据处理 · 处理任务（一期）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增“数据处理 / 处理任务”菜单，交付 dip 风格的任务目录树 + 列表页（查询、启动/停止、复制、删除、运行记录），复用现有任务引擎。

**架构：** 后端在现有 `processing_tasks` 上扩展目录与描述字段，新增 `task_directories` 表与目录 API；运行状态由 `task_runs` 实时推导；停止用协作式取消（取消标志 + Statement.cancel + 线程中断）。前端新建 `ProcessingTasks.vue`（树 + 列表）与两个对话框组件，复用 `task-edit.js` 表单映射，不改 TaskStudio。

**技术栈：** Java 21 / Spring Boot / Flyway（H2 测试 + MySQL 生产双份迁移）、Vue 3 / Element Plus、node:test。

**规格：** `docs/superpowers/specs/2026-11-20-processing-tasks-design.md` 一期部分。

**环境注意：**
- 本机默认 JDK 为 17，后端命令必须带 `JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"`（bash 写法）。
- 后端测试：`cd backend && JAVA_HOME="..." mvn test -Dtest=<类名> -DfailIfNoTests=false`；全量 `mvn test`。
- 前端测试：`cd frontend && node --test "tests/*.test.js"`；构建 `npm run build`。
- 建议在独立 worktree 中执行（using-git-worktrees），完成后合回 main。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| 创建 `backend/src/main/resources/db/migration/V32__processing_menu_and_directories.sql` | H2：菜单 3/31、目录表、任务表扩展 |
| 创建 `backend/src/main/resources/db/mysql/V32__processing_menu_and_directories.sql` | MySQL 同内容 |
| 创建 `backend/src/main/resources/db/migration/V33__task_delete_permission.sql` + `db/mysql/` 同名 | 注册 workflow.task.delete 权限码并授予 role 1（V30 模式） |
| 修改 `backend/src/main/java/com/example/governance/security/PermissionCatalog.java` | 组件→模块映射、新端点路由守卫 |
| 创建 `backend/src/main/java/com/example/governance/workflow/TaskDirectoryStore.java` | 目录树 CRUD + 排序（环校验、保守删除） |
| 创建 `backend/src/main/java/com/example/governance/workflow/TaskDirectoryController.java` | 目录 REST 端点 + 审计 |
| 修改 `backend/src/main/java/com/example/governance/workflow/WorkflowConfiguration.java` | 注册 TaskDirectoryStore Bean |
| 修改 `backend/src/main/java/com/example/governance/workflow/TaskVersionStore.java` | Task 记录扩展、create 扩展、基本信息更新、删除 |
| 修改 `backend/src/main/java/com/example/governance/workflow/TaskController.java` | 列表富化、创建扩展、PUT/DELETE/stop 端点 |
| 创建 `backend/src/main/java/com/example/governance/workflow/RunCancelContext.java` | 协作式取消上下文 |
| 修改 `backend/src/main/java/com/example/governance/workflow/TaskRunService.java` | 运行状态查询、重复启动守卫、stop、取消接线 |
| 修改 `backend/src/main/java/com/example/governance/workflow/CamelTaskRunner.java` | 传递取消上下文 |
| 修改 `backend/src/main/java/com/example/governance/workflow/SqlTaskExecutor.java` | 注册 Statement、取消检查 |
| 修改 `backend/src/main/java/com/example/governance/workflow/DatabaseSyncExecutor.java` | 行循环取消检查 |
| 修改 `backend/src/test/java/com/example/governance/organization/OrganizationNavigationTest.java` | 新菜单可见性 |
| 创建 `backend/src/test/java/com/example/governance/workflow/TaskDirectoryApiTest.java` | 目录 API |
| 创建 `backend/src/test/java/com/example/governance/workflow/TaskMetadataApiTest.java` | 列表富化/创建/PUT |
| 创建 `backend/src/test/java/com/example/governance/workflow/TaskStopApiTest.java` | 停止/守卫 |
| 创建 `backend/src/test/java/com/example/governance/workflow/TaskDeleteApiTest.java` | 删除守卫/级联 |
| 修改 `frontend/src/menu-icons.js` | processing 图标 |
| 创建 `frontend/src/processing-tasks.js` | 子树过滤/筛选/批量汇总/树扁平化纯逻辑 |
| 创建 `frontend/tests/processing-tasks.test.js` | 上述纯逻辑测试 |
| 创建 `frontend/src/TaskFormDialog.vue` | 任务表单对话框（复用 task-edit.js） |
| 创建 `frontend/src/TaskRunsDialog.vue` | 运行记录对话框 |
| 创建 `frontend/src/ProcessingTasks.vue` | 目录树 + 列表 + 工具栏 + 启停/复制/删除编排 |
| 修改 `frontend/src/App.vue` | processing section 分支 |

---

### 任务 1：V32 迁移 + 菜单可见性

**文件：**
- 创建：`backend/src/main/resources/db/migration/V32__processing_menu_and_directories.sql`
- 创建：`backend/src/main/resources/db/mysql/V32__processing_menu_and_directories.sql`
- 修改：`backend/src/main/java/com/example/governance/security/PermissionCatalog.java`（moduleForComponent，约 33-40 行 switch）
- 修改：`frontend/src/menu-icons.js`
- 测试：`backend/src/test/java/com/example/governance/organization/OrganizationNavigationTest.java`

- [x] **步骤 1：更新导航测试（先写失败测试）**

在 `OrganizationNavigationTest.java` 中做三处修改：

`fullGrantsSeeAssetDirectoryAndSystemManagement` 内：
```java
        assertEquals(List.of("assets", "processing", "system"), roots);
        var components = components(f, "admin");
        assertEquals(12, components.size());
        assertTrue(components.containsAll(List.of("assets", "assets/sources", "assets/drivers", "processing", "processing/tasks", "system/departments", "system/roles", "system/users", "system/menus", "system/permissions", "system/audit")));
        assertFalse(components.contains("assets/datasets"));
```

`platformGrantsOpenMatchingAssetPages` 内（该用户有 workflow.task.execute，现在应同时看到处理任务）：
```java
        assertEquals(4, components.size());
        assertTrue(components.containsAll(List.of("assets", "assets/sources", "processing", "processing/tasks")));
```

新增测试方法：
```java
    @Test void taskGrantOpensProcessingPageOnly() {
        var f = fixture();
        grantUser(f, 10, "task", 10, true, Set.of("workflow.task.read"));
        var components = components(f, "task");
        assertEquals(2, components.size());
        assertTrue(components.containsAll(List.of("processing", "processing/tasks")));
    }
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=OrganizationNavigationTest -DfailIfNoTests=false`
预期：FAIL（roots 缺少 processing、组件数不符）。

- [x] **步骤 3：编写迁移与映射**

`db/migration/V32__processing_menu_and_directories.sql`：
```sql
-- Processing directory menu plus task directory tree and task metadata columns.
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (3,NULL,'数据处理','D','','processing','','processing',30,TRUE,TRUE);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (31,3,'处理任务','M','/processing/tasks','processing/tasks','','workflow',10,TRUE,TRUE);
CREATE TABLE task_directories (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    parent_id BIGINT,
    name VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (parent_id) REFERENCES task_directories(id)
);
ALTER TABLE processing_tasks ADD COLUMN directory_id BIGINT;
ALTER TABLE processing_tasks ADD CONSTRAINT fk_tasks_directory FOREIGN KEY (directory_id) REFERENCES task_directories(id);
ALTER TABLE processing_tasks ADD COLUMN description VARCHAR(500);
```

`db/mysql/V32__processing_menu_and_directories.sql`：同上，仅主键改为 `id BIGINT AUTO_INCREMENT PRIMARY KEY`。

`PermissionCatalog.java` 的 `moduleForComponent` switch 中增加：
```java
            case "processing/tasks" -> "workflow.task";
```

`menu-icons.js` 的 `menuIconPaths` 增加：
```js
  processing: 'M4 5h16l-6 7v5l-4 2v-7L4 5Z',
```
`labels` 增加：
```js
  processing: '数据处理',
```

- [x] **步骤 4：运行测试验证通过**

运行：同步骤 2 命令。
预期：`Tests run: 7, Failures: 0`（原 6 个 + 新增 1 个），BUILD SUCCESS。

- [x] **步骤 5：Commit**

```bash
git add backend/src/main/resources/db/migration/V32__processing_menu_and_directories.sql backend/src/main/resources/db/mysql/V32__processing_menu_and_directories.sql backend/src/main/java/com/example/governance/security/PermissionCatalog.java backend/src/test/java/com/example/governance/organization/OrganizationNavigationTest.java frontend/src/menu-icons.js
git commit -m "feat(processing): 数据处理菜单与任务目录表迁移

新增一级目录数据处理与二级菜单处理任务，复用 workflow.task 权限；
任务表扩展目录与描述字段，为 dip 风格任务列表做准备。"
```

---

### 任务 2：目录存储与 API

**文件：**
- 创建：`backend/src/main/java/com/example/governance/workflow/TaskDirectoryStore.java`
- 创建：`backend/src/main/java/com/example/governance/workflow/TaskDirectoryController.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/WorkflowConfiguration.java`
- 修改：`backend/src/main/java/com/example/governance/security/PermissionCatalog.java`（routes 段，约 208 行附近）
- 测试：`backend/src/test/java/com/example/governance/workflow/TaskDirectoryApiTest.java`

- [x] **步骤 1：编写失败的 API 测试**

创建 `TaskDirectoryApiTest.java`：
```java
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
import static org.springframework.security.test.web.servlet.request.MockMvcRequestBuilders.*;
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
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version,directory_id) VALUES (601,'t','SQL',1,?)", child);
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (601,1,'{}')");
        mvc.perform(delete("/api/task-directories/" + child).session(session).with(csrf())).andExpect(status().isConflict());
        // 移入自身子孙禁止（root→child→root 真环）
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
    }

    @Test void directoryEndpointsRequirePermission() throws Exception {
        var session = login("dir-none", 602);
        mvc.perform(get("/api/task-directories").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/task-directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/task-directories/sort").session(session).with(csrf()).contentType("application/json").content("{\"items\":[]}")).andExpect(status().isForbidden());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=TaskDirectoryApiTest -DfailIfNoTests=false`
预期：FAIL（404，端点不存在）。

- [x] **步骤 3：实现存储**

创建 `TaskDirectoryStore.java`：
```java
package com.example.governance.workflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Task directory tree stored as flat parent-linked rows; deletion is conservative. */
public final class TaskDirectoryStore {
    public record Directory(long id, Long parentId, String name, int sortOrder) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public TaskDirectoryStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); }

    public List<Directory> list() {
        return jdbc.query("SELECT id,parent_id,name,sort_order FROM task_directories ORDER BY sort_order,id",
                (rs, row) -> new Directory(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3), rs.getInt(4)));
    }

    public long create(String name, Long parentId) {
        String clean = validName(name);
        if (parentId != null && jdbc.queryForList("SELECT id FROM task_directories WHERE id=?", Long.class, parentId).isEmpty()) throw new IllegalArgumentException("Unknown parent directory");
        Integer max = parentId == null
                ? jdbc.queryForObject("SELECT COALESCE(MAX(sort_order),-1) FROM task_directories WHERE parent_id IS NULL", Integer.class)
                : jdbc.queryForObject("SELECT COALESCE(MAX(sort_order),-1) FROM task_directories WHERE parent_id=?", Integer.class, parentId);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO task_directories(parent_id,name,sort_order) VALUES (?,?,?)", new String[]{"id"});
            if (parentId == null) statement.setNull(1, java.sql.Types.BIGINT); else statement.setLong(1, parentId);
            statement.setString(2, clean); statement.setInt(3, max + 1); return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Directory ID was not generated");
        return key.getKey().longValue();
    }

    public void rename(long id, String name) {
        String clean = validName(name);
        if (jdbc.update("UPDATE task_directories SET name=? WHERE id=?", clean, id) != 1) throw new IllegalArgumentException("Unknown directory");
    }

    public void delete(long id) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM task_directories WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) throw new IllegalArgumentException("Unknown directory");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM task_directories WHERE parent_id=?", Integer.class, id) > 0
                    || jdbc.queryForObject("SELECT COUNT(*) FROM processing_tasks WHERE directory_id=?", Integer.class, id) > 0)
                throw new IllegalStateException("目录包含子目录或任务，不能删除");
            jdbc.update("DELETE FROM task_directories WHERE id=?", id);
        });
    }

    /** Replaces parent links and order for the whole tree in one transaction; rejects cycles. */
    public void sort(List<Directory> items) {
        if (items == null) throw new IllegalArgumentException("Invalid sort payload");
        transaction.executeWithoutResult(status -> {
            Set<Long> existing = new HashSet<>(jdbc.queryForList("SELECT id FROM task_directories FOR UPDATE", Long.class));
            Map<Long, Directory> byId = new HashMap<>();
            for (Directory item : items) {
                if (!existing.contains(item.id()) || byId.put(item.id(), item) != null) throw new IllegalArgumentException("Invalid directory sort payload");
            }
            if (!byId.keySet().equals(existing)) throw new IllegalArgumentException("Sort payload must cover all directories");
            for (Directory item : items) {
                Set<Long> seen = new HashSet<>();
                Directory current = item;
                while (current.parentId() != null) {
                    if (!seen.add(current.id())) throw new IllegalStateException("目录不能移入自身子孙");
                    current = byId.get(current.parentId());
                    if (current == null) throw new IllegalArgumentException("Unknown parent directory");
                }
            }
            for (Directory item : items) {
                jdbc.update("UPDATE task_directories SET parent_id=?,sort_order=? WHERE id=?", item.parentId(), item.sortOrder(), item.id());
            }
        });
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Invalid directory name");
        return name.trim();
    }
}
```

- [x] **步骤 4：实现控制器与装配**

创建 `TaskDirectoryController.java`：
```java
package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TaskDirectoryController {
    record DirectoryRequest(String name, Long parentId) {}
    record SortItem(long id, Long parentId, int sortOrder) {}
    record SortRequest(List<SortItem> items) {}
    private final TaskDirectoryStore directories;
    private final AuditLog audit;
    public TaskDirectoryController(TaskDirectoryStore directories, AuditLog audit) { this.directories = directories; this.audit = audit; }

    @GetMapping("/api/task-directories")
    List<TaskDirectoryStore.Directory> list() { return directories.list(); }

    @PostMapping("/api/task-directories")
    ResponseEntity<Map<String, Long>> create(@RequestBody DirectoryRequest request, Authentication auth) {
        try {
            long id = directories.create(request.name(), request.parentId());
            audit.success(auth, "directory.create", "task_directory", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录名称或父目录无效"); }
    }

    @PutMapping("/api/task-directories/{id}")
    ResponseEntity<Void> rename(@PathVariable long id, @RequestBody DirectoryRequest request, Authentication auth) {
        try { directories.rename(id, request.name()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录名称无效"); }
        audit.success(auth, "directory.update", "task_directory", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/task-directories/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        try { directories.delete(id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目录不存在"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
        audit.success(auth, "directory.delete", "task_directory", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/task-directories/sort")
    ResponseEntity<Void> sort(@RequestBody SortRequest request, Authentication auth) {
        try { directories.sort(request.items().stream().map(item -> new TaskDirectoryStore.Directory(item.id(), item.parentId(), null, item.sortOrder())).toList()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序数据无效"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
        audit.success(auth, "directory.sort", "task_directory", 0);
        return ResponseEntity.noContent().build();
    }
}
```

`WorkflowConfiguration.java` 增加 Bean：
```java
    @Bean TaskDirectoryStore taskDirectoryStore(JdbcTemplate jdbc, PlatformTransactionManager tx) { return new TaskDirectoryStore(jdbc, tx); }
```

`PermissionCatalog.java` 还需注册删除权限码（原 catalog 的 workflow.task 模块无 delete 动作，route() 会强校验未注册权限）：`buildEntries` 中增加
```java
        add(values, "workflow.task", "任务管理", "删除", "删除任务");
```
并创建双份迁移 `V33__task_delete_permission.sql`（H2 与 MySQL 内容一致，参照 V30 模式）：
```sql
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'workflow.task.delete','workflow.task','任务管理','delete','删除','删除任务与任务目录',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO role_permissions (role_id, permission)
SELECT 1, 'workflow.task.delete' FROM permission_catalog
WHERE EXISTS (SELECT 1 FROM roles WHERE id=1)
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=1 AND rp.permission='workflow.task.delete');
```

routes 段（`/api/tasks` 路由附近）增加：
```java
        route(routes, "GET", "/api/task-directories", "workflow.task.read");
        route(routes, "POST", "/api/task-directories", "workflow.task.create");
        route(routes, "POST", "/api/task-directories/sort", "workflow.task.update");
        route(routes, "PUT", "/api/task-directories/*", "workflow.task.update");
        route(routes, "DELETE", "/api/task-directories/*", "workflow.task.delete");
```

- [x] **步骤 5：运行测试验证通过**

运行：同步骤 2 命令。
预期：`Tests run: 2, Failures: 0`，BUILD SUCCESS。

- [x] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/TaskDirectoryStore.java backend/src/main/java/com/example/governance/workflow/TaskDirectoryController.java backend/src/main/java/com/example/governance/workflow/WorkflowConfiguration.java backend/src/main/java/com/example/governance/security/PermissionCatalog.java backend/src/main/resources/db/migration/V33__task_delete_permission.sql backend/src/main/resources/db/mysql/V33__task_delete_permission.sql backend/src/test/java/com/example/governance/workflow/TaskDirectoryApiTest.java
git commit -m "feat(processing): 任务目录树 CRUD 与拖拽排序接口

目录非空保守删除、排序全量提交并校验环；权限复用 workflow.task，
增删改移记审计；注册 workflow.task.delete 权限码（V33）。"
```

---

### 任务 3：任务列表富化、创建扩展与基本信息更新

**文件：**
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskVersionStore.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskRunService.java`（新增 summary/hasActiveRun）
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskController.java`
- 修改：`backend/src/main/java/com/example/governance/security/PermissionCatalog.java`（PUT /api/tasks/* 路由）
- 测试：`backend/src/test/java/com/example/governance/workflow/TaskMetadataApiTest.java`

- [x] **步骤 1：编写失败的 API 测试**

创建 `TaskMetadataApiTest.java`（login 辅助与任务 2 相同模式，此处完整给出）：
```java
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
import static org.springframework.security.test.web.servlet.request.MockMvcRequestBuilders.*;
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
        // 列表富化：无运行时 STOPPED、lastRun 为空（本测试库仅此一任务，用 $[0]）
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
                .andExpect(jsonPath("$[0].directoryId").value(directory))
                .andExpect(jsonPath("$[0].description").value("新描述"))
                .andExpect(jsonPath("$[0].runState").value("STOPPED"));
        // 名称 null/非文本 → 400（不得落库字面 "null"）
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"name\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"name\":123}"))
                .andExpect(status().isBadRequest());
        // 清空目录（显式 null）
        mvc.perform(put("/api/tasks/" + taskId).session(session).with(csrf()).contentType("application/json").content("{\"directoryId\":null}"))
                .andExpect(status().isNoContent());
        assertNull(jdbc.queryForObject("SELECT directory_id FROM processing_tasks WHERE id=" + taskId, Long.class));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=TaskMetadataApiTest -DfailIfNoTests=false`
预期：FAIL（创建请求多余字段被忽略、列表无 runState、PUT 404）。

- [x] **步骤 3：扩展 TaskVersionStore**

`Task` 记录与 `list()` 替换为：
```java
    public record Task(long id, String name, String type, int version, String configuration, Long directoryId, String description) {}
    public java.util.List<Task> list() {
        return jdbc.query("SELECT t.id,t.name,t.task_type,t.current_version,v.configuration,t.directory_id,t.description FROM processing_tasks t JOIN processing_task_versions v ON v.task_id=t.id AND v.version=t.current_version ORDER BY t.id", (rs, row) -> new Task(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getObject(6, Long.class), rs.getString(7)));
    }
```
（注意 `java.util.List` 全限定，保持原文件风格。）

`create` 改为重载：
```java
    public long create(String name, String type, String configuration) { return create(name, type, configuration, null, null); }
    public long create(String name, String type, String configuration, Long directoryId, String description) {
        if (name == null || name.isBlank() || name.length() > 160 || type == null || !Set.of("SQL", "SYNC").contains(type)) throw new IllegalArgumentException("Invalid task");
        if (description != null && description.length() > 500) throw new IllegalArgumentException("Invalid description");
        validate(configuration);
        return transaction.execute(status -> {
            // 目录存在性检查在事务内并锁目录行，与目录删除串行化
            if (directoryId != null && jdbc.queryForList("SELECT id FROM task_directories WHERE id=? FOR UPDATE", Long.class, directoryId).isEmpty()) throw new IllegalArgumentException("Unknown directory");
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO processing_tasks(name,task_type,current_version,directory_id,description) VALUES (?,?,1,?,?)", new String[]{"id"});
                statement.setString(1, name); statement.setString(2, type);
                if (directoryId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, directoryId);
                statement.setString(4, description); return statement;
            }, key);
            Number id = key.getKey();
            if (id == null) throw new IllegalStateException("Task ID was not generated");
            jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (?,1,?)", id.longValue(), configuration);
            return id.longValue();
        });
    }
```

新增基本信息更新（JsonNode 区分“未传”与“显式 null”）：
```java
    public void updateMetadata(long id, com.fasterxml.jackson.databind.JsonNode changes) {
        if (changes == null || !changes.isObject()) throw new IllegalArgumentException("Invalid metadata");
        transaction.executeWithoutResult(status -> {
            var rows = jdbc.query("SELECT name,directory_id,description FROM processing_tasks WHERE id=? FOR UPDATE",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getObject(2, Long.class), rs.getString(3)}, id);
            if (rows.isEmpty()) throw new java.util.NoSuchElementException("Unknown task");
            Object[] current = rows.getFirst();
            String name = (String) current[0];
            if (changes.has("name")) {
                com.fasterxml.jackson.databind.JsonNode node = changes.get("name");
                name = node.isTextual() ? node.asText() : null;   // null/非文本 → 后续校验拒为 400
            }
            if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Invalid task name");
            Long directoryId = (Long) current[1];
            if (changes.has("directoryId")) {
                com.fasterxml.jackson.databind.JsonNode node = changes.get("directoryId");
                if (node.isNull()) directoryId = null;
                else if (node.isIntegralNumber() && node.canConvertToLong()) directoryId = node.asLong();
                else throw new IllegalArgumentException("Invalid directory");
            }
            if (directoryId != null && jdbc.queryForList("SELECT id FROM task_directories WHERE id=? FOR UPDATE", Long.class, directoryId).isEmpty()) throw new IllegalArgumentException("Unknown directory");
            String description = (String) current[2];
            if (changes.has("description")) {
                com.fasterxml.jackson.databind.JsonNode node = changes.get("description");
                if (node.isNull()) description = null;
                else if (node.isTextual()) description = node.asText();
                else throw new IllegalArgumentException("Invalid description");
            }
            if (description != null && description.length() > 500) throw new IllegalArgumentException("Invalid description");
            jdbc.update("UPDATE processing_tasks SET name=?,directory_id=?,description=? WHERE id=?", name, directoryId, description, id);
        });
    }
```

- [x] **步骤 4：TaskRunService 增加状态查询**

在 `TaskRunService` 中新增：
```java
    public record LastRun(String state, LocalDateTime startedAt, LocalDateTime finishedAt) {}
    public record TaskRunSummary(boolean running, LastRun lastRun) {}
    public boolean hasActiveRun(long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=? AND state IN ('PENDING','RUNNING')", Integer.class, taskId) > 0;
    }
    private record RunRow(long taskId, String state, LocalDateTime startedAt, LocalDateTime finishedAt) {}
    /** Latest run per task (MAX(id) join) plus active flag (DISTINCT active task_id); O(tasks+active), not O(history). */
    public java.util.Map<Long, TaskRunSummary> summary() {
        var latest = jdbc.query("SELECT r.task_id,r.state,r.started_at,r.finished_at FROM task_runs r JOIN (SELECT task_id,MAX(id) AS max_id FROM task_runs GROUP BY task_id) m ON m.max_id=r.id",
                (rs, row) -> { var s = rs.getTimestamp(3); var f = rs.getTimestamp(4); return new RunRow(rs.getLong(1), rs.getString(2), s == null ? null : s.toLocalDateTime(), f == null ? null : f.toLocalDateTime()); });
        var active = new java.util.HashSet<>(jdbc.queryForList("SELECT DISTINCT task_id FROM task_runs WHERE state IN ('PENDING','RUNNING')", Long.class));
        var result = new java.util.HashMap<Long, TaskRunSummary>();
        for (RunRow row : latest) result.put(row.taskId(), new TaskRunSummary(active.contains(row.taskId()), new LastRun(row.state(), row.startedAt(), row.finishedAt())));
        return result;
    }
```

- [x] **步骤 5：TaskController 列表富化 + 创建扩展 + PUT**

`TaskController` 中：

`CreateRequest` 替换为：
```java
    record CreateRequest(String name, String type, JsonNode configuration, Long directoryId, String description) {}
```
`create` 方法内 `tasks.create(request.name(), request.type(), config)` 改为：
```java
            long id = tasks.create(request.name(), request.type(), config, request.directoryId(), request.description());
```
并在 catch 中保留 400 映射。

`list()` 替换为：
```java
    public record LastRunView(String state, java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt) {}
    public record TaskView(long id, String name, String type, int version, String configuration, Long directoryId, String description, String runState, LastRunView lastRun) {}
    @GetMapping("/api/tasks")
    List<TaskView> list() {
        var summaries = runs.summary();
        return tasks.list().stream().map(task -> {
            var summary = summaries.get(task.id());
            boolean running = summary != null && summary.running();
            LastRunView last = summary == null || summary.lastRun() == null ? null : new LastRunView(summary.lastRun().state(), summary.lastRun().startedAt(), summary.lastRun().finishedAt());
            return new TaskView(task.id(), task.name(), task.type(), task.version(), task.configuration(), task.directoryId(), task.description(), running ? "RUNNING" : "STOPPED", last);
        }).toList();
    }
```

新增 PUT 端点：
```java
    @PutMapping("/api/tasks/{id}")
    ResponseEntity<Void> metadata(@PathVariable long id, @RequestBody JsonNode changes, Authentication auth) {
        if (runs.hasActiveRun(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务运行中，不能修改");
        try { tasks.updateMetadata(id, changes); }
        catch (java.util.NoSuchElementException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基本信息无效"); }
        audit.success(auth, "task.update", "task", id);
        return ResponseEntity.noContent().build();
    }
```
（`PutMapping` 需加入 import。）

`PermissionCatalog.java` routes 增加：
```java
        route(routes, "PUT", "/api/tasks/*", "workflow.task.update");
```

- [x] **步骤 6：运行测试验证通过**

运行：同步骤 2 命令；再跑 `mvn test -Dtest=TaskVersionStoreTest,StandaloneTaskRetryApiTest -DfailIfNoTests=false` 确认旧测试不回归。
预期：全部 PASS。

- [x] **步骤 7：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/TaskVersionStore.java backend/src/main/java/com/example/governance/workflow/TaskRunService.java backend/src/main/java/com/example/governance/workflow/TaskController.java backend/src/main/java/com/example/governance/security/PermissionCatalog.java backend/src/test/java/com/example/governance/workflow/TaskMetadataApiTest.java
git commit -m "feat(processing): 任务列表运行状态富化与基本信息维护

列表返回 runState/lastRun/目录/描述；创建支持目录与描述；
PUT 维护基本信息，运行中禁止修改。"
```

---

### 任务 4：重复启动守卫与停止运行

**文件：**
- 创建：`backend/src/main/java/com/example/governance/workflow/RunCancelContext.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskRunService.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/CamelTaskRunner.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/SqlTaskExecutor.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/DatabaseSyncExecutor.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskController.java`
- 修改：`backend/src/main/java/com/example/governance/security/PermissionCatalog.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/TaskStopApiTest.java`

- [x] **步骤 1：编写失败的 API 测试**

创建 `TaskStopApiTest.java`：
```java
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
import static org.springframework.security.test.web.servlet.request.MockMvcRequestBuilders.*;
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
        jdbc.update("INSERT INTO departments(id,name) VALUES (631,'dept')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (631,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (631,'stop-user',?,631,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (631,631)");
        for (String permission : new String[]{"workflow.task.read", "workflow.task.create", "workflow.task.update", "workflow.task.execute", "workflow.task.delete"})
            jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (631,?)", permission);
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
        // 运行中：重复启动 / 改基本信息 / 删除 均 409
        mvc.perform(post("/api/tasks/631/runs").session(session).with(csrf()).contentType("application/json").content("{\"version\":1,\"businessDate\":\"2026-11-20\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/tasks/631").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/tasks/631").session(session).with(csrf())).andExpect(status().isConflict());
        // 停止
        mvc.perform(post("/api/tasks/631/stop").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.stopped").value(1));
        awaitState(runId, "INTERRUPTED");
        assertEquals("手动停止", jdbc.queryForObject("SELECT error_message FROM task_runs WHERE id=?", String.class, runId));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_run_attempts WHERE run_id=? AND attempt_no=1", String.class, runId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.stop' AND actor_id=631", Integer.class));
        // 停止后可编辑、可删除
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

    @Test void stopWithoutActiveRunConflicts() throws Exception {
        var session = login();
        insertTask(633);
        mvc.perform(post("/api/tasks/633/stop").session(session).with(csrf())).andExpect(status().isConflict());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=TaskStopApiTest -DfailIfNoTests=false`
预期：FAIL（编译错误：RunCancelContext 不存在 / stop 404）。

- [x] **步骤 3：实现 RunCancelContext**

创建 `RunCancelContext.java`：
```java
package com.example.governance.workflow;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation shared between the stop endpoint and the executing worker. */
public final class RunCancelContext {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Statement statement;
    private volatile Thread worker;
    public boolean isCancelled() { return cancelled.get(); }
    public void worker(Thread thread) { this.worker = thread; }
    public void register(Statement active) { this.statement = active; }
    /** 终态后调用：迟到 cancel 只置标志，不再 interrupt 复用池线程或 cancel 已关闭语句。 */
    public void detach() { this.worker = null; this.statement = null; }
    public void cancel() {
        cancelled.set(true);
        Statement active = statement;
        if (active != null) try { active.cancel(); } catch (SQLException ignored) { }
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }
}
```

- [x] **步骤 4：TaskRunService 接线停止与守卫**

新增字段与记录：
```java
    private final java.util.concurrent.ConcurrentHashMap<Long, RunCancelContext> active = new java.util.concurrent.ConcurrentHashMap<>();
    private record ActiveRun(long id, String state, int attempt) {}
```

`start` 方法在版本校验后追加（行锁内守卫，与并发 start/stop 串行化）：
```java
        jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, taskId);
        if (hasActiveRun(taskId)) throw new IllegalStateException("任务已在运行");
```

`execute` 方法重构：ctx 注册提前到 claim 之前（闭合 claim→put 窗口）；终态 finish 完成后才 detach+remove（闭合 remove→finish 窗口）；InterruptedException 先 finish 再恢复中断标志（避免带标志取连接失败）；成功路径 finish 前查取消标志：
```java
        var cancel = new RunCancelContext();
        cancel.worker(Thread.currentThread());
        active.put(id, cancel);
        try {
            boolean claimed = ...原有 claim 事务...;
            if (!claimed) return;   // finally 会 detach+remove
            try (var permit = limiter.acquire()) { runner.execute(tasks.findVersion(taskId, version).orElseThrow(), date, cancel); }
            catch (InterruptedException ex) { finish(id, attempt, "INTERRUPTED", cancel.isCancelled() ? "手动停止" : null); Thread.currentThread().interrupt(); return; }
            catch (Exception ex) { finish(id, attempt, cancel.isCancelled() ? "INTERRUPTED" : "FAILED", cancel.isCancelled() ? "手动停止" : "任务执行失败"); return; }
            if (cancel.isCancelled()) { finish(id, attempt, "INTERRUPTED", "手动停止"); return; }
            // Failure recording a successful business write is ambiguous and must not be classified as a task failure.
            try { finish(id, attempt, "SUCCEEDED", null); }
            catch (RuntimeException ex) { finish(id, attempt, "INTERRUPTED", null); }
        } finally { cancel.detach(); active.remove(id); }
```

新增 stop：
```java
    @Transactional
    public int stop(long taskId, Authentication auth) {
        var rows = jdbc.query("SELECT id,state,attempt_no FROM task_runs WHERE task_id=? AND state IN ('PENDING','RUNNING') FOR UPDATE",
                (rs, row) -> new ActiveRun(rs.getLong(1), rs.getString(2), rs.getInt(3)), taskId);
        if (rows.isEmpty()) throw new IllegalStateException("任务未在运行");
        for (ActiveRun run : rows) {
            RunCancelContext cancel = active.get(run.id());
            if ("PENDING".equals(run.state()) || cancel == null) finish(run.id(), run.attempt(), "INTERRUPTED", "手动停止");
            else cancel.cancel();
        }
        audit.success(auth, "task.stop", "task", taskId);
        return rows.size();
    }
```

- [x] **步骤 5：执行器传递取消上下文**

`CamelTaskRunner.java`：原 `execute(Version, LocalDate)` 方法体首行改为委托，并新增三参重载：
```java
    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate) throws Exception { execute(task, businessDate, new RunCancelContext()); }
    public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel) throws Exception {
```
方法体内 `sql.execute(provider.get(sourceId), statement, values)` 改为 `sql.execute(provider.get(sourceId), statement, values, cancel)`；`sync.append(...)` 与 `sync.upsert(...)` 调用末尾各追加 `, cancel` 参数。

`SqlTaskExecutor.java`：
- `Request` 记录改为 `private record Request(DataSource source, String sql, List<?> parameters, RunCancelContext cancel) {}`
- 路由内 `statement.setQueryTimeout(30);` 之后追加：
```java
                        request.cancel().register(statement);
                        if (request.cancel().isCancelled()) throw new InterruptedException("手动停止");
```
- 原三参 `execute` 改为委托并新增四参：
```java
    public int execute(DataSource source, String sql, List<?> parameters) { return execute(source, sql, parameters, new RunCancelContext()); }
    public int execute(DataSource source, String sql, List<?> parameters, RunCancelContext cancel) {
        if (source == null || sql == null || sql.isBlank() || parameters == null) throw new IllegalArgumentException("SQL task is incomplete");
        return producer.requestBody("direct:execute-sql", new Request(source, sql, Collections.unmodifiableList(new ArrayList<>(parameters)), cancel), Integer.class);
    }
```

`DatabaseSyncExecutor.java`：
- `Request` 记录末尾追加 `RunCancelContext cancel` 分量。
- 路由内 `appendRows(rows, write, request.columns().size(), request.batchSize())` 改为 `appendRows(rows, write, request.columns().size(), request.batchSize(), request.cancel())`；`upsertRows(rows, write, target, request)` 不变（request 已含 cancel）。
- 路由内 read/write prepareStatement 后 `request.cancel().register(write)`（接线 Statement.cancel，停止即时化）；`target.commit()` 之前补 `if (request.cancel().isCancelled()) throw new InterruptedException("手动停止");`（末批/commit 窗口走 rollback）。
- `appendRows` 签名追加 `RunCancelContext cancel`，`while (rows.next()) {` 后首行追加：
```java
            if (cancel.isCancelled()) throw new InterruptedException("手动停止");
```
- `upsertRows` 内 `while (rows.next()) {` 后首行追加同样检查（用 `request.cancel()`）。
- `append`/`upsert`/`execute` 各保留旧签名委托新签名（末尾追加 `new RunCancelContext()`），新签名带 `RunCancelContext cancel` 并传入 `Request`。私有 `execute` 新签名：
```java
    private int execute(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, List<String> keys, int batchSize, RunCancelContext cancel) {
        if (source == null || target == null || query == null || query.isBlank() || parameters == null || parameters.size() > 1000 || !validQualifiedIdentifier(table) || columns == null || columns.isEmpty() || columns.size() > 1000 || columns.stream().anyMatch(c -> !validIdentifier(c)) || columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).distinct().count() != columns.size() || batchSize < 1 || batchSize > 10000) throw new IllegalArgumentException("Invalid synchronization configuration");
        return producer.requestBody("direct:sync-data", new Request(source, target, query, Collections.unmodifiableList(new ArrayList<>(parameters)), table, List.copyOf(columns), keys, batchSize, cancel), Integer.class);
    }
```

- [x] **步骤 6：控制器 stop 端点与路由**

`TaskController.java` 新增：
```java
    @PostMapping("/api/tasks/{id}/stop")
    ResponseEntity<Map<String, Integer>> stop(@PathVariable long id, Authentication auth) {
        try { return ResponseEntity.ok(Map.of("stopped", runs.stop(id, auth))); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
    }
```
`run` 方法 catch 段追加 `IllegalStateException` 映射：
```java
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
```

`PermissionCatalog.java` routes 增加：
```java
        route(routes, "POST", "/api/tasks/*/stop", "workflow.task.execute");
```

- [x] **步骤 7：运行测试验证通过**

运行：`cd backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=TaskStopApiTest,TaskConcurrencyIntegrationTest,SqlTaskExecutorTest,DatabaseSyncExecutorTest,CamelTaskRunnerTest,WorkflowWorkerTest -DfailIfNoTests=false`
预期：全部 PASS（旧执行器测试经旧签名委托不受影响）。

- [x] **步骤 8：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/RunCancelContext.java backend/src/main/java/com/example/governance/workflow/TaskRunService.java backend/src/main/java/com/example/governance/workflow/CamelTaskRunner.java backend/src/main/java/com/example/governance/workflow/SqlTaskExecutor.java backend/src/main/java/com/example/governance/workflow/DatabaseSyncExecutor.java backend/src/main/java/com/example/governance/workflow/TaskController.java backend/src/main/java/com/example/governance/security/PermissionCatalog.java backend/src/test/java/com/example/governance/workflow/TaskStopApiTest.java
git commit -m "feat(processing): 任务停止与重复启动守卫

协作式取消：批次/行循环检查标志、SQL Statement.cancel、
排队中断线程；运行中禁止重复启动、修改与删除；停止记审计。"
```

---

### 任务 5：任务删除（引用守卫 + 级联）

**文件：**
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskVersionStore.java`
- 修改：`backend/src/main/java/com/example/governance/workflow/TaskController.java`
- 修改：`backend/src/main/java/com/example/governance/security/PermissionCatalog.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/TaskDeleteApiTest.java`

- [x] **步骤 1：编写失败的 API 测试**

创建 `TaskDeleteApiTest.java`：
```java
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
import static org.springframework.security.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:task_delete_api;DB_CLOSE_DELAY=-1")
class TaskDeleteApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;

    private MockHttpSession login() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (641,'dept')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (641,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (641,'delete-user',?,641,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (641,641)");
        for (String permission : new String[]{"workflow.task.read", "workflow.task.delete"})
            jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (641,?)", permission);
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "delete-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void deleteGuardsWorkflowReferencesAndCascadesRuns() throws Exception {
        var session = login();
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (641,'被引用','SQL',1),(642,'可删除','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (641,1,'{}'),(642,1,'{}')");
        jdbc.update("INSERT INTO workflow_definitions(id,name,current_version) VALUES (641,'wf',1)");
        jdbc.update("INSERT INTO workflow_versions(workflow_id,version) VALUES (641,1)");
        jdbc.update("INSERT INTO workflow_nodes(workflow_id,workflow_version,node_key,ordinal_no,task_id,task_version) VALUES (641,1,'n1',0,641,1)");
        // 被工作流引用 → 409（reason 区分双 409 源），且被拒任务完好
        mvc.perform(delete("/api/tasks/641").session(session).with(csrf())).andExpect(status().isConflict())
                .andExpect(result -> assertEquals("任务被工作流引用，不能删除", ((org.springframework.web.server.ResponseStatusException) result.getResolvedException()).getReason()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processing_tasks WHERE id=641", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processing_task_versions WHERE task_id=641", Integer.class));
        // 有运行历史但无引用 → 级联删除
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,attempt_no) VALUES (641,642,1,DATE '2026-11-20','SUCCEEDED',CURRENT_TIMESTAMP,1)");
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state,finished_at) VALUES (641,1,'SUCCEEDED',CURRENT_TIMESTAMP)");
        mvc.perform(delete("/api/tasks/642").session(session).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_tasks WHERE id=642", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_task_versions WHERE task_id=642", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=642", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_run_attempts WHERE run_id=641", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='task.delete' AND actor_id=641", Integer.class));
        // 不存在 → 404
        mvc.perform(delete("/api/tasks/999").session(session).with(csrf())).andExpect(status().isNotFound());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=TaskDeleteApiTest -DfailIfNoTests=false`
预期：FAIL（DELETE 404/405）。

- [x] **步骤 3：实现删除**

`TaskVersionStore.java` 新增：
```java
    /** Conservative delete: workflows pin task versions, so referenced tasks stay. Own run history is removed with the task. */
    public void delete(long id) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) throw new IllegalArgumentException("Unknown task");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM workflow_nodes WHERE task_id=?", Integer.class, id) > 0) throw new IllegalStateException("任务被工作流引用，不能删除");
            // 锁内复查活跃 run，闭合控制器守卫与 start/retry 的并发窗口（权威守卫；控制器前置检查仅快速失败）
            var states = jdbc.queryForList("SELECT state FROM task_runs WHERE task_id=? FOR UPDATE", String.class, id);
            if (states.stream().anyMatch(s -> s.equals("PENDING") || s.equals("RUNNING"))) throw new IllegalStateException("任务运行中，不能删除");
            jdbc.update("DELETE FROM task_runs WHERE task_id=?", id);
            jdbc.update("DELETE FROM processing_task_versions WHERE task_id=?", id);
            jdbc.update("DELETE FROM processing_tasks WHERE id=?", id);
        });
    }
```

`TaskController.java` 新增：
```java
    @DeleteMapping("/api/tasks/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        if (runs.hasActiveRun(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务运行中，不能删除");
        try { tasks.delete(id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
        audit.success(auth, "task.delete", "task", id);
        return ResponseEntity.noContent().build();
    }
```
（`DeleteMapping` 加入 import。）

`PermissionCatalog.java` routes 增加：
```java
        route(routes, "DELETE", "/api/tasks/*", "workflow.task.delete");
```

- [x] **步骤 4：运行测试验证通过**

运行：同步骤 2 命令。
预期：`Tests run: 1, Failures: 0`。

- [x] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/TaskVersionStore.java backend/src/main/java/com/example/governance/workflow/TaskController.java backend/src/main/java/com/example/governance/security/PermissionCatalog.java backend/src/test/java/com/example/governance/workflow/TaskDeleteApiTest.java
git commit -m "feat(processing): 任务删除接口与引用守卫

被工作流引用的任务拒绝删除；无引用任务级联清除自身运行历史；
运行中禁止删除，删除记审计。"
```

---

### 任务 6：前端纯逻辑模块

**文件：**
- 创建：`frontend/src/processing-tasks.js`
- 测试：`frontend/tests/processing-tasks.test.js`

- [x] **步骤 1：编写失败的测试**

创建 `frontend/tests/processing-tasks.test.js`：
```js
import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { subtreeIds, filterTasks, batchSummary, flattenTree } from '../src/processing-tasks.js'

const directories = [
  { id: 1, parentId: null, name: '订单域', sortOrder: 0 },
  { id: 2, parentId: 1, name: '明细', sortOrder: 0 },
  { id: 3, parentId: 2, name: '日明细', sortOrder: 0 },
  { id: 4, parentId: null, name: '用户域', sortOrder: 1 }
]
const tasks = [
  { id: 10, name: '订单加工', directoryId: 2, runState: 'STOPPED' },
  { id: 11, name: '日汇总', directoryId: 3, runState: 'RUNNING' },
  { id: 12, name: '用户同步', directoryId: 4, runState: 'STOPPED' },
  { id: 13, name: '未分类任务', directoryId: null, runState: 'STOPPED' }
]

test('subtree includes descendants and root filter means all', () => {
  assert.deepEqual([...subtreeIds(directories, 1)].sort(), [1, 2, 3])
  assert.equal(subtreeIds(directories, null), null)
})

test('directory filter includes subtree and uncategorized only under root', () => {
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: 1 }).map(t => t.id), [10, 11])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null }).map(t => t.id), [10, 11, 12, 13])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: 4 }).map(t => t.id), [12])
})

test('name and run state filters combine with directory', () => {
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: 1, name: '日' }).map(t => t.id), [11])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null, runState: 'RUNNING' }).map(t => t.id), [11])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null, name: '订单', runState: 'RUNNING' }), [])
})

test('batch summary lists successes and failures', () => {
  const message = batchSummary([
    { name: '甲', ok: true },
    { name: '乙', ok: false, error: '任务已在运行' },
    { name: '丙', ok: true }
  ])
  assert.equal(message, '成功：甲、丙；失败：乙（任务已在运行）')
  assert.equal(batchSummary([]), '没有可操作的任务')
})

test('flatten tree produces parent links and sibling order', () => {
  const tree = [
    { id: 1, children: [{ id: 2, children: [{ id: 3 }] }] },
    { id: 4 }
  ]
  assert.deepEqual(flattenTree(tree), [
    { id: 1, parentId: null, sortOrder: 0 },
    { id: 2, parentId: 1, sortOrder: 0 },
    { id: 3, parentId: 2, sortOrder: 0 },
    { id: 4, parentId: null, sortOrder: 1 }
  ])
})
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd frontend && node --test "tests/processing-tasks.test.js"`
预期：FAIL（模块不存在）。

- [x] **步骤 3：实现模块**

创建 `frontend/src/processing-tasks.js`：
```js
export function subtreeIds(directories, rootId) {
  if (rootId == null) return null
  const ids = new Set()
  const queue = [rootId]
  while (queue.length) {
    const id = queue.shift()
    if (ids.has(id)) continue
    ids.add(id)
    for (const directory of directories) if (directory.parentId === id) queue.push(directory.id)
  }
  return ids
}

export function filterTasks(tasks, { directories = [], directoryId = null, name = '', runState = '' } = {}) {
  const ids = subtreeIds(directories, directoryId)
  const keyword = String(name ?? '').trim().toLowerCase()
  return tasks.filter(task =>
    (ids === null || (task.directoryId != null && ids.has(task.directoryId))) &&
    (!keyword || String(task.name ?? '').toLowerCase().includes(keyword)) &&
    (!runState || task.runState === runState))
}

export function batchSummary(results) {
  const succeeded = results.filter(result => result.ok).map(result => result.name)
  const failed = results.filter(result => !result.ok)
  const parts = []
  if (succeeded.length) parts.push(`成功：${succeeded.join('、')}`)
  if (failed.length) parts.push(`失败：${failed.map(result => `${result.name}（${result.error || '未知错误'}）`).join('、')}`)
  return parts.join('；') || '没有可操作的任务'
}

export function flattenTree(nodes, parentId = null) {
  const flat = []
  nodes.forEach((node, index) => {
    flat.push({ id: node.id, parentId, sortOrder: index })
    if (node.children?.length) flat.push(...flattenTree(node.children, node.id))
  })
  return flat
}
```

- [x] **步骤 4：运行测试验证通过**

运行：同步骤 2 命令。
预期：`pass 5, fail 0`。

- [x] **步骤 5：Commit**

```bash
git add frontend/src/processing-tasks.js frontend/tests/processing-tasks.test.js
git commit -m "feat(processing): 任务目录过滤与批量启停汇总纯逻辑

子树过滤、名称/运行状态组合筛选、批量结果汇总、树扁平化，
供处理任务列表页使用并带 node:test 覆盖。"
```

---

### 任务 7：任务表单与运行记录对话框

**文件：**
- 创建：`frontend/src/TaskFormDialog.vue`
- 创建：`frontend/src/TaskRunsDialog.vue`

复用 `task-edit.js`（emptyTask/formFromTask/configurationFromForm/newParameter）与 `auth.js` 的 authorizedRequest，字段语义与 TaskStudio 完全一致。

- [x] **步骤 1：实现 TaskFormDialog.vue**

创建 `frontend/src/TaskFormDialog.vue`：
```vue
<script setup>
import { computed, inject, ref, watch } from 'vue'
import { authorizedRequest } from './auth.js'
import { emptyTask, formFromTask, configurationFromForm, newParameter } from './task-edit.js'
const props = defineProps({ open: Boolean, task: Object, sources: Array, directories: Array, readonly: Boolean })
const emit = defineEmits(['update:open', 'saved'])
const can = inject('canPermission', () => false)
// 复制预填时 task.id 为 null，按新增处理
const editing = computed(() => !!props.task?.id)
const form = ref(emptyTask()), directoryId = ref(null), description = ref(''), error = ref(''), busy = ref(false)
watch(() => props.open, open => {
  if (!open) return
  error.value = ''
  if (props.task) {
    try { form.value = formFromTask(props.task) } catch (e) { error.value = e.message; form.value = emptyTask() }
    directoryId.value = props.task.directoryId ?? null
    description.value = props.task.description ?? ''
  } else { form.value = emptyTask(); directoryId.value = null; description.value = '' }
})
async function save() {
  if (busy.value) return
  error.value = ''; busy.value = true
  try {
    const configuration = configurationFromForm(form.value)
    const metadata = { name: form.value.name, directoryId: directoryId.value, description: description.value }
    if (editing.value) {
      const put = await authorizedRequest(`/api/tasks/${props.task.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(metadata) })
      if (!put.ok) throw new Error(put.status === 409 ? '任务运行中，不能修改' : '基本信息保存失败')
      const response = await authorizedRequest(`/api/tasks/${props.task.id}/versions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ configuration }) })
      if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务修改权限' : '配置保存失败，请检查表单')
    } else {
      const response = await authorizedRequest('/api/tasks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...metadata, type: form.value.type, configuration }) })
      if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务创建权限' : '任务保存失败，请检查表单')
    }
    emit('update:open', false); emit('saved')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <el-dialog :model-value="open" :title="readonly ? '查看任务' : editing ? '编辑任务（保存将创建新版本）' : '新增任务'" width="min(850px, 95vw)" destroy-on-close @update:model-value="emit('update:open', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-form label-position="top" :disabled="readonly" @submit.prevent="save">
      <div class="grid">
        <el-form-item label="任务名称"><el-input v-model="form.name" :disabled="editing"/></el-form-item>
        <el-form-item label="任务类型"><el-select v-model="form.type" :disabled="editing"><el-option label="SQL 加工" value="SQL"/><el-option label="数据库同步" value="SYNC"/></el-select></el-form-item>
        <el-form-item label="所属目录"><el-select v-model="directoryId" clearable placeholder="未分类"><el-option v-for="d in directories" :key="d.id" :label="d.name" :value="d.id"/></el-select></el-form-item>
        <el-form-item label="描述"><el-input v-model="description" maxlength="500" placeholder="可选"/></el-form-item>
        <el-form-item label="源数据源"><el-select v-model="form.sourceId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item>
        <el-form-item v-if="form.type === 'SYNC'" label="目标数据源"><el-select v-model="form.targetId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item>
      </div>
      <template v-if="form.type === 'SQL'">
        <el-form-item label="SQL 语句"><el-input v-model="form.sql" type="textarea" :rows="4"/></el-form-item>
      </template>
      <template v-else>
        <el-form-item label="源查询 SQL"><el-input v-model="form.sourceQuery" type="textarea" :rows="3"/></el-form-item>
        <div class="grid">
          <el-form-item label="目标表"><el-input v-model="form.targetTable" placeholder="例如 schema.table"/></el-form-item>
          <el-form-item label="目标字段，逗号分隔"><el-input v-model="form.columns" placeholder="id, name"/></el-form-item>
          <el-form-item label="写入方式"><el-select v-model="form.writeMode"><el-option label="追加" value="APPEND"/><el-option label="按唯一键更新插入" value="UPSERT"/></el-select></el-form-item>
          <el-form-item v-if="form.writeMode === 'UPSERT'" label="目标唯一键字段，逗号分隔"><el-input v-model="form.keys" placeholder="需与目标表主键或唯一索引完全一致"/></el-form-item>
          <el-form-item label="批次大小"><el-input-number v-model="form.batchSize" :min="1" :max="10000"/></el-form-item>
        </div>
      </template>
      <div class="parameter-editor">
        <div class="row"><strong>SQL 参数</strong><el-button text type="primary" :disabled="form.parameters.length >= 1000" @click="form.parameters.push(newParameter())">添加参数</el-button></div>
        <p>按照 SQL 中占位符的顺序填写。业务日期由每次运行时传入。</p>
        <div v-for="(parameter, index) in form.parameters" :key="index" class="row parameter-row">
          <span>第 {{ index + 1 }} 个</span>
          <el-select :model-value="parameter.kind" aria-label="参数类型" @change="form.parameters[index] = newParameter($event)"><el-option label="文本" value="string"/><el-option label="数字" value="number"/><el-option label="布尔" value="boolean"/><el-option label="空值" value="null"/><el-option label="业务日期" value="date"/></el-select>
          <el-input v-if="parameter.kind === 'string'" v-model="parameter.value" aria-label="文本参数"/>
          <el-input v-else-if="parameter.kind === 'number'" v-model="parameter.value" inputmode="decimal" placeholder="例如 12.5" aria-label="数字参数"/>
          <el-select v-else-if="parameter.kind === 'boolean'" v-model="parameter.value" aria-label="布尔参数"><el-option label="是" :value="true"/><el-option label="否" :value="false"/></el-select>
          <el-button text :disabled="index === 0" @click="[form.parameters[index - 1], form.parameters[index]] = [form.parameters[index], form.parameters[index - 1]]">上移</el-button>
          <el-button text :disabled="index === form.parameters.length - 1" @click="[form.parameters[index + 1], form.parameters[index]] = [form.parameters[index], form.parameters[index + 1]]">下移</el-button>
          <el-button text type="danger" @click="form.parameters.splice(index, 1)">删除</el-button>
        </div>
      </div>
      <div><el-checkbox v-model="form.retrySafe">我已确认任务在工作流中自动重试不会造成重复或错误数据</el-checkbox>
        <div v-if="form.retrySafe" class="row"><span>最多重试</span><el-input-number v-model="form.maxRetries" :min="1" :max="3"/><span>次，间隔（秒）</span><el-input-number v-model="form.retryDelay" :min="1" :max="3600"/></div>
      </div>
      <el-button v-if="!readonly && (editing ? can('workflow.task.update') : can('workflow.task.create'))" type="primary" native-type="submit" :loading="busy">{{ editing ? '保存新版本' : '保存任务' }}</el-button>
      <el-button @click="emit('update:open', false)">{{ readonly ? '关闭' : '取消' }}</el-button>
    </el-form>
  </el-dialog>
</template>
<style scoped>.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.row{display:flex;gap:12px;align-items:center;margin:14px 0}.parameter-editor p{color:#687e7b;font-size:13px}.parameter-row .el-select,.parameter-row .el-input{max-width:200px}@media(max-width:700px){.grid{grid-template-columns:1fr}.row{flex-wrap:wrap}}</style>
```

- [x] **步骤 2：实现 TaskRunsDialog.vue**

创建 `frontend/src/TaskRunsDialog.vue`：
```vue
<script setup>
import { inject, ref, watch } from 'vue'
import { authorizedRequest } from './auth.js'
import { usePagination } from './pagination.js'
import RefreshIcon from './RefreshIcon.vue'
const props = defineProps({ open: Boolean, task: Object })
const emit = defineEmits(['update:open'])
const can = inject('canPermission', () => false)
const runs = ref([]), error = ref(''), message = ref('')
const paging = usePagination(runs)
async function load() {
  if (!props.task) return
  try {
    const response = await fetch(`/api/tasks/${props.task.id}/runs`, { credentials: 'same-origin' })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务读取权限' : '读取运行记录失败')
    runs.value = await response.json(); error.value = ''
  } catch (e) { error.value = e.message }
}
watch(() => props.open, open => { if (open) { message.value = ''; load() } })
async function retry(run) {
  try {
    const response = await authorizedRequest(`/api/tasks/${props.task.id}/runs/${run.id}/retry`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 409 ? '实例状态已变化，无法重试' : '重试失败，请检查权限和实例状态')
    message.value = '任务重试已提交'; await load()
  } catch (e) { error.value = e.message }
}
</script>
<template>
  <el-dialog :model-value="open" :title="`${task?.name || ''} 运行记录`" width="min(1050px, 96vw)" @update:model-value="emit('update:open', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
    <div class="toolbar list-query-toolbar"><div class="list-query-controls"><span>共 {{ runs.length }} 条实例</span></div><div class="list-query-actions"><el-button class="refresh-button" title="刷新" aria-label="刷新" @click="load"><RefreshIcon /></el-button></div></div>
    <el-table :data="paging.visible.value" empty-text="暂无运行记录">
      <el-table-column prop="id" label="实例"/><el-table-column prop="taskVersion" label="版本"/><el-table-column prop="businessDate" label="业务日期"/><el-table-column prop="state" label="状态"/><el-table-column prop="startedAt" label="开始时间"/>
      <el-table-column label="执行记录" min-width="180"><template #default="scope"><div v-for="attempt in scope.row.attempts" :key="attempt.attempt">第 {{ attempt.attempt }} 次：{{ attempt.state }} {{ attempt.errorMessage || '' }}</div></template></el-table-column>
      <el-table-column label="操作"><template #default="scope"><el-popconfirm v-if="can('workflow.task.retry') && ['FAILED','INTERRUPTED'].includes(scope.row.state)" title="重试可能重复写入，确认已检查幂等策略？" @confirm="retry(scope.row)"><template #reference><el-button text type="warning">重试</el-button></template></el-popconfirm></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="paging.page.value" v-model:page-size="paging.pageSize.value" :total="paging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/>
  </el-dialog>
</template>
<style scoped>.el-pagination{margin-top:18px;justify-content:flex-end}</style>
```

- [x] **步骤 3：构建验证**

运行：`cd frontend && npm run build`
预期：构建成功（组件尚未被引用，但语法与导入必须通过）。

- [x] **步骤 4：Commit**

```bash
git add frontend/src/TaskFormDialog.vue frontend/src/TaskRunsDialog.vue
git commit -m "feat(processing): 任务表单与运行记录对话框组件

复用 task-edit.js 表单映射与版本语义；表单支持目录、描述与只读查看；
运行记录支持重试与分页。"
```

---

### 任务 8：处理任务列表页与菜单接线

**文件：**
- 创建：`frontend/src/ProcessingTasks.vue`
- 修改：`frontend/src/App.vue`（import 与 section 分支，约 6 行与 236 行附近）

- [x] **步骤 1：实现 ProcessingTasks.vue**

创建 `frontend/src/ProcessingTasks.vue`：
```vue
<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import { usePagination } from './pagination.js'
import { filterTasks, batchSummary, flattenTree } from './processing-tasks.js'
import TaskFormDialog from './TaskFormDialog.vue'
import TaskRunsDialog from './TaskRunsDialog.vue'
import RefreshIcon from './RefreshIcon.vue'
defineProps({ view: { type: String, default: 'tasks' } })
const can = inject('canPermission', () => false)
const directories = ref([]), tasks = ref([]), sources = ref([]), error = ref(''), message = ref('')
const tree = ref(null), currentDirectory = ref(0)
const nameSearch = ref(''), nameFilter = ref(''), stateSearch = ref(''), stateFilter = ref('')
const selected = ref([]), formOpen = ref(false), formTask = ref(null), formReadonly = ref(false), runsOpen = ref(false), runsTask = ref(null)
const startOpen = ref(false), startTargets = ref([]), startDate = ref(new Date().toLocaleDateString('sv-SE')), batchBusy = ref(false)
const filtered = computed(() => filterTasks(tasks.value, { directories: directories.value, directoryId: currentDirectory.value === 0 ? null : currentDirectory.value, name: nameFilter.value, runState: stateFilter.value }))
const paging = usePagination(filtered)
const currentDirectoryName = computed(() => currentDirectory.value === 0 ? '全部任务' : directories.value.find(d => d.id === currentDirectory.value)?.name ?? '全部任务')
const treeData = computed(() => {
  const build = parentId => directories.value.filter(d => d.parentId === parentId).sort((a, b) => a.sortOrder - b.sortOrder).map(d => ({ id: d.id, name: d.name, children: build(d.id) }))
  return [{ id: 0, name: '全部任务', children: build(null) }]
})
async function get(url) { const response = await fetch(url, { credentials: 'same-origin' }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '读取失败，请检查连接'); return response.json() }
async function load() {
  try {
    const [directoryRows, taskRows, sourceRows] = await Promise.all([
      can('workflow.task.read') ? get('/api/task-directories') : Promise.resolve([]),
      can('workflow.task.read') ? get('/api/tasks') : Promise.resolve([]),
      can('datasource.source.read') ? get('/api/data-sources') : Promise.resolve([])
    ])
    directories.value = directoryRows; tasks.value = taskRows; sources.value = sourceRows
  } catch (e) { error.value = e.message }
}
function applyQuery() { nameFilter.value = nameSearch.value.trim(); stateFilter.value = stateSearch.value; paging.page.value = 1 }
function selectDirectory(node) { currentDirectory.value = node.id; paging.page.value = 1 }
async function submitSort() {
  // 拖拽只改 el-tree 内部节点结构，须从 store 读取；根哨兵节点的子节点即顶级目录
  const toPlain = node => ({ id: node.data.id, children: node.childNodes.map(toPlain) })
  const items = flattenTree(tree.value.store.root.childNodes[0].childNodes.map(toPlain))
  try {
    const response = await authorizedRequest('/api/task-directories/sort', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }) })
    if (!response.ok) throw new Error(response.status === 409 ? '目录不能移入自身子孙' : '目录排序保存失败')
    await load()
  } catch (e) { error.value = e.message; await load() }
}
async function addDirectory(parent) {
  try {
    const { value } = await ElMessageBox.prompt('请输入目录名称', parent ? '新增子目录' : '新增目录', { confirmButtonText: '保存', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
    const response = await authorizedRequest('/api/task-directories', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: value, parentId: parent?.id ?? null }) })
    if (!response.ok) throw new Error('目录创建失败')
    message.value = '目录已创建'; await load()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
async function renameDirectory(node) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名目录', { confirmButtonText: '保存', cancelButtonText: '取消', inputValue: node.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
    const response = await authorizedRequest(`/api/task-directories/${node.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: value }) })
    if (!response.ok) throw new Error('目录重命名失败')
    await load()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
async function deleteDirectory(node) {
  try {
    await ElMessageBox.confirm(`确定删除目录“${node.name}？含任务或子目录时无法删除。`, '删除目录', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    const response = await authorizedRequest(`/api/task-directories/${node.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '目录包含子目录或任务，不能删除' : '目录删除失败')
    if (currentDirectory.value === node.id) currentDirectory.value = 0
    message.value = '目录已删除'; await load()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
function openCreate() { formTask.value = null; formReadonly.value = false; formOpen.value = true }
function openEdit(row) { formTask.value = row; formReadonly.value = row.runState === 'RUNNING'; formOpen.value = true }
function openCopy(row) { formTask.value = { ...row, id: null, name: `${row.name}-副本` }; formReadonly.value = false; formOpen.value = true }
function openRuns(row) { runsTask.value = row; runsOpen.value = true }
function openStart(targets) { startTargets.value = targets; startDate.value = new Date().toLocaleDateString('sv-SE'); startOpen.value = true }
async function confirmStart() {
  if (batchBusy.value) return
  batchBusy.value = true; error.value = ''; message.value = ''
  const results = []
  for (const target of startTargets.value) {
    try {
      const response = await authorizedRequest(`/api/tasks/${target.id}/runs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version: target.version, businessDate: startDate.value }) })
      if (!response.ok) throw new Error(response.status === 409 ? '任务已在运行' : response.status === 403 ? '没有运行权限' : '启动失败')
      results.push({ name: target.name, ok: true })
    } catch (e) { results.push({ name: target.name, ok: false, error: e.message }) }
  }
  batchBusy.value = false; startOpen.value = false; message.value = batchSummary(results); await load()
}
async function stopOne(row) {
  try {
    const response = await authorizedRequest(`/api/tasks/${row.id}/stop`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 409 ? '任务未在运行' : '停止失败')
    message.value = `已停止 ${row.name}`; await load()
  } catch (e) { error.value = e.message }
}
async function stopBatch() {
  try { await ElMessageBox.confirm(`确定停止选中的 ${selected.value.length} 个任务？停止会中断运行，可能已写入部分数据。`, '批量停止', { confirmButtonText: '停止', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  batchBusy.value = true; const results = []
  for (const target of selected.value) {
    try {
      const response = await authorizedRequest(`/api/tasks/${target.id}/stop`, { method: 'POST' })
      if (!response.ok) throw new Error(response.status === 409 ? '任务未在运行' : '停止失败')
      results.push({ name: target.name, ok: true })
    } catch (e) { results.push({ name: target.name, ok: false, error: e.message }) }
  }
  batchBusy.value = false; message.value = batchSummary(results); await load()
}
async function removeTask(row) {
  try {
    const response = await authorizedRequest(`/api/tasks/${row.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '任务运行中或被工作流引用，不能删除' : '删除失败')
    message.value = `已删除 ${row.name}`; await load()
  } catch (e) { error.value = e.message }
}
let pollTimer = null
watch(tasks, rows => {
  const running = rows.some(task => task.runState === 'RUNNING')
  if (running && !pollTimer) pollTimer = setInterval(load, 5000)
  if (!running && pollTimer) { clearInterval(pollTimer); pollTimer = null }
}, { deep: false })
onMounted(load)
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
</script>
<template>
  <section class="processing">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
    <div class="processing-layout">
      <aside class="directory-pane">
        <div class="directory-head"><strong>任务目录</strong><el-button v-if="can('workflow.task.create')" text type="primary" @click="addDirectory(null)">新增</el-button></div>
        <el-tree ref="tree" :data="treeData" node-key="id" :props="{ label: 'name', children: 'children' }" default-expand-all draggable :allow-drag="node => node.data.id !== 0" :allow-drop="() => true" @node-click="selectDirectory" @node-drop="submitSort">
          <template #default="{ data }">
            <span class="tree-node">
              <span>{{ data.name }}</span>
              <span v-if="data.id !== 0" class="tree-actions">
                <el-button v-if="can('workflow.task.create')" text size="small" @click.stop="addDirectory(data)">新增</el-button>
                <el-button v-if="can('workflow.task.update')" text size="small" @click.stop="renameDirectory(data)">重命名</el-button>
                <el-button v-if="can('workflow.task.delete')" text size="small" type="danger" @click.stop="deleteDirectory(data)">删除</el-button>
              </span>
            </span>
          </template>
        </el-tree>
      </aside>
      <div class="list-pane">
        <div class="toolbar list-query-toolbar">
          <div class="list-query-controls">
            <el-input v-model="nameSearch" clearable placeholder="搜索任务名称" @keyup.enter="applyQuery"/>
            <el-select v-model="stateSearch" clearable placeholder="运行状态" style="width:130px"><el-option label="运行中" value="RUNNING"/><el-option label="停止" value="STOPPED"/></el-select>
            <el-button type="primary" @click="applyQuery">查询</el-button>
            <span>当前目录：{{ currentDirectoryName }}</span>
          </div>
          <div class="list-query-actions">
            <el-button v-if="can('workflow.task.create') && can('datasource.source.read')" type="primary" @click="openCreate">新增</el-button>
            <el-button v-if="can('workflow.task.execute')" :disabled="!selected.length || batchBusy" @click="openStart(selected)">批量启动</el-button>
            <el-button v-if="can('workflow.task.execute')" :disabled="!selected.length || batchBusy" @click="stopBatch">批量停止</el-button>
            <el-button class="refresh-button" title="刷新" aria-label="刷新" @click="load"><RefreshIcon /></el-button>
          </div>
        </div>
        <el-table :data="paging.visible.value" empty-text="暂无任务" row-key="id" @selection-change="selected = $event">
          <el-table-column v-if="can('workflow.task.execute')" type="selection" width="45"/>
          <el-table-column type="index" label="序号" width="60"/>
          <el-table-column prop="name" label="任务名称" min-width="160"/>
          <el-table-column label="类型" width="110"><template #default="scope">{{ scope.row.type === 'SQL' ? 'SQL 加工' : '数据库同步' }}</template></el-table-column>
          <el-table-column prop="version" label="版本" width="70"/>
          <el-table-column label="目录" min-width="110"><template #default="scope">{{ directories.find(d => d.id === scope.row.directoryId)?.name ?? '未分类' }}</template></el-table-column>
          <el-table-column label="运行状态" width="90"><template #default="scope"><el-tag :type="scope.row.runState === 'RUNNING' ? 'success' : 'info'" size="small">{{ scope.row.runState === 'RUNNING' ? '运行中' : '停止' }}</el-tag></template></el-table-column>
          <el-table-column label="最近运行" min-width="170"><template #default="scope"><span v-if="scope.row.lastRun">{{ scope.row.lastRun.state }} · {{ scope.row.lastRun.finishedAt || scope.row.lastRun.startedAt || '' }}</span><span v-else class="status-text">未运行</span></template></el-table-column>
          <el-table-column label="操作" min-width="280" fixed="right"><template #default="scope">
            <el-button v-if="can('workflow.task.execute') && scope.row.runState !== 'RUNNING'" text type="primary" @click="openStart([scope.row])">启动</el-button>
            <el-popconfirm v-if="can('workflow.task.execute') && scope.row.runState === 'RUNNING'" title="停止会中断运行，可能已写入部分数据，确定停止？" @confirm="stopOne(scope.row)"><template #reference><el-button text type="warning">停止</el-button></template></el-popconfirm>
            <el-button v-if="can('workflow.task.update') || scope.row.runState === 'RUNNING'" text @click="openEdit(scope.row)">{{ scope.row.runState === 'RUNNING' ? '查看' : '编辑' }}</el-button>
            <el-button v-if="can('workflow.task.create')" text @click="openCopy(scope.row)">复制</el-button>
            <el-popconfirm v-if="can('workflow.task.delete')" :title="`确定删除任务 ${scope.row.name}？运行历史将一并清除。`" @confirm="removeTask(scope.row)"><template #reference><el-button text type="danger" :disabled="scope.row.runState === 'RUNNING'">删除</el-button></template></el-popconfirm>
            <el-button v-if="can('workflow.task.read')" text @click="openRuns(scope.row)">运行记录</el-button>
          </template></el-table-column>
        </el-table>
        <div class="pagination"><el-pagination v-model:current-page="paging.page.value" v-model:page-size="paging.pageSize.value" :total="paging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/></div>
      </div>
    </div>
    <TaskFormDialog v-model:open="formOpen" :task="formTask" :sources="sources" :directories="directories" :readonly="formReadonly" @saved="message = '任务已保存'; load()"/>
    <TaskRunsDialog v-model:open="runsOpen" :task="runsTask"/>
    <el-dialog v-model="startOpen" :title="`启动 ${startTargets.length} 个任务`" width="min(480px, 95vw)">
      <el-form label-position="top"><el-form-item label="业务日期"><el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD"/></el-form-item></el-form>
      <p v-for="target in startTargets" :key="target.id" class="status-text">{{ target.name }}（版本 {{ target.version }}）</p>
      <template #footer><el-button @click="startOpen = false">取消</el-button><el-button type="primary" :loading="batchBusy" @click="confirmStart">启动</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.processing-layout{display:flex;gap:14px;align-items:flex-start}
.directory-pane{flex:0 0 220px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;padding:12px}
.directory-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
.list-pane{flex:1;min-width:0}
.tree-node{display:flex;justify-content:space-between;align-items:center;gap:6px;flex:1;min-width:0}
.tree-actions{display:none;white-space:nowrap}
.el-tree-node__content:hover .tree-actions{display:inline-flex}
.status-text{color:#728582;font-size:12px}
.pagination{margin-top:14px;display:flex;justify-content:flex-end}
@media(max-width:700px){.processing-layout{flex-direction:column}.directory-pane{flex:none;width:100%}}
</style>
```

- [x] **步骤 2：App.vue 接线**

`App.vue` script 段 import 区（TaskStudio import 附近）增加：
```js
import ProcessingTasks from './ProcessingTasks.vue'
```
template 中 assets 分支后增加：
```html
      <div v-else-if="section === 'processing'" class="content"><ProcessingTasks :view="groupPages.processing || 'tasks'" /></div>
```

- [x] **步骤 3：构建与全量测试**

运行：
```bash
cd frontend && npm run build && node --test "tests/*.test.js"
cd ../backend && JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test
```
预期：前端构建成功、48+5 个测试通过；后端全量测试 BUILD SUCCESS。

- [x] **步骤 4：手工验证清单**

启动后端（`mvn spring-boot:run`，环境变量已配置）与前端（`npm run dev`），以具备 `workflow.task.*` 权限的账号登录：
1. 侧边栏出现“数据处理 / 处理任务”，面包屑正确。
2. 目录新增/重命名/拖拽/删除守卫生效；非空目录删除提示 409 文案。
3. 新增 SQL 任务（选目录、填描述）→ 列表出现，目录列正确。
4. 启动（选业务日期）→ 运行状态变“运行中”，5 秒轮询结束后回“停止”；最近运行列更新。
5. 运行中：编辑变“查看”、删除禁用、重复启动提示冲突；停止后恢复。
6. 复制生成“-副本”任务；删除无引用任务成功，被工作流引用任务提示 409。
7. 批量启动/停止汇总消息正确；运行记录弹窗与重试可用。
8. 窄屏（≤700px）目录 pane 置顶、工具栏换行查询区在前。
9. 仅有 `datasource.*` 权限的账号看不到“数据处理”菜单。

- [x] **步骤 5：Commit**

```bash
git add frontend/src/ProcessingTasks.vue frontend/src/App.vue
git commit -m "feat(processing): 处理任务列表页与数据处理菜单接线

目录树 + dip 风格列表：查询、启停、复制、删除、运行记录、
批量启停汇总与运行状态轮询；窄屏自适应。"
```

---

## 自检记录

- **规格覆盖度：** 菜单/权限映射（任务 1）、目录树交互与排序（任务 2、8）、数据模型（任务 1）、列表富化/创建/PUT（任务 3）、stop 与重复启动守卫（任务 4）、删除守卫与级联（任务 5）、复制/批量前端实现（任务 8）、运行记录弹窗（任务 7）、轮询与窄屏（任务 8）、错误处理 409/403 文案（任务 2-8）、测试策略（各任务测试步骤 + 任务 8 全量与手工清单）。规格一期全部条目有对应任务。
- **占位符扫描：** 无 TODO/待定；所有代码步骤含完整代码。
- **类型一致性：** `TaskDirectoryStore.Directory(id,parentId,name,sortOrder)` 在控制器 sort 映射与前端 flattenTree 输出一致；`RunCancelContext` 三参 execute 签名在 runner/sql/sync/测试 mock 一致；`TaskRunSummary/LastRun` 与控制器 `TaskView/LastRunView` 字段对应；前端 `runState/lastRun/directoryId/description` 与后端 JSON 一致；`task-edit.js` 导出名与导入一致。
