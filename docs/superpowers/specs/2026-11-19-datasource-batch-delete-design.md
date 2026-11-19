# 数据源批量删除设计

日期：2026-11-19　状态：已确认

## 背景

数据源管理页已有单条删除：`DELETE /api/data-sources/{id}`，后端 `DataSourceStore.deleteUnused` 在同一事务内锁定数据源、扫描历史任务版本 JSON 引用，并依赖数据集 / 实时任务外键兜底；被引用或历史配置无法安全检查时返回 409。批量清理多个数据源目前只能逐条点击，需要批量删除。

## 语义

部分成功：能删的删掉，被引用或不存在的保留并逐条报告原因。与用户 CSV 导入“有效行创建、无效行报告、不中断整批”的风格一致。整批在一个事务内提交成功删除的部分；失败条目不影响其他条目。

## 后端 API

- 路由：`POST /api/data-sources/batch-delete`，在 `PermissionCatalog` 登记，复用 `datasource.source.delete` 权限。
- 请求体：`{"ids":[…]}`。校验：非空、不超过 100 个、元素为正整数；重复 id 去重。不合法返回 400。
- 处理：逐 id 复用 `store.deleteUnused(id)`。成功记审计 `datasource.delete`（object 为 datasource/id）；`deleteUnused` 返回 false 记 `NOT_FOUND`；抛出 `IllegalStateException` / `DataIntegrityViolationException` 记 `REFERENCED`。
- 响应 200：`{"deleted":[id,…],"failed":[{"id":…,"reason":"REFERENCED"|"NOT_FOUND"}…]}`。failed 顺序与请求去重后的顺序一致。

## 前端

`frontend/src/DataSources.vue`（sources 视图）：

- 表格增加多选列，仅 `can('datasource.source.delete')` 时渲染；`selection-change` 维护 `selectedSources`。
- 工具栏增加“批量删除”按钮（danger），无选中或删除请求进行中时禁用；`el-popconfirm` 二次确认，标题含选中数量并提示被引用的数据源将保留。
- 结果展示：成功提示“已删除 N 个数据源”；存在失败时错误条列出名称与原因（被引用，无法删除 / 不存在）。汇总文案抽为 `datasource-form.js` 的纯函数 `batchDeleteSummary(result, items)`，便于 `node --test` 覆盖。
- 删除后清空选中；若“查看表”对话框当前数据源被删除，重置目录状态；重新加载列表。

## 测试

- 后端 `DataSourceBatchDeletionApiTest`：无权限 403；空 / 非法 / 超量 ids 400；混合批量（可删 + 数据集引用 + 任务版本引用 + 不存在 + 重复 id）200 且逐条报告、审计仅记成功项；全部被引用时保留全部且无审计。
- 前端 `frontend/tests/datasource-form.test.js` 增加 `batchDeleteSummary` 用例。
- 验证：`mvn -f backend/pom.xml test`、`node --test frontend/tests/*.test.js`、`npm --prefix frontend run build`。