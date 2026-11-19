# 数据治理平台

本项目是单节点数据治理平台的开发版本，使用 Spring Boot、Apache Camel、Vue 3 和 Element Plus。它提供组织权限、数据源与数据集、数据库任务与工作流、数据服务和报表的基础功能；尚有设计要求未完成，也没有完成真实数据库兼容性及生产环境验证，请勿用于生产。

目前已实现的主要能力包括：

- 部门、用户、角色与权限管理，以及按部门和字段配置数据访问规则；任务执行使用平台数据源权限。
- 数据源连接检查、表字段发现和数据集登记，受权限约束的数据预览与聚合分析。数据源按“类型+大版本”档案连接（MySQL 5.x/8.x、PostgreSQL、Oracle 11g/12c+），内置常用 JDBC 驱动，管理员可在“驱动管理”页上传驱动 jar（隔离类加载，多版本共存）；连接参数仅限档案白名单，Oracle 支持 SID/服务名切换。
- Camel SQL 加工、数据库追加及按目标主键或唯一索引更新插入；任务独立运行、任务版本编辑、工作流版本、依赖关系、有限并行分支、节点布局、执行记录、定时计划、有限次数的安全重试和按业务日期补跑。
- 基于单调递增水位及主键的数据库轮询同步。它不能捕获删除和迟到数据，不等同于 CDC。
- 基于授权数据集的数据服务、调用方令牌、授权和限流；个人报表与看板。

仍需实现或完善全量覆盖、可配置超时与取消，以及设计中其他管理能力。请以 [子系统计划](docs/superpowers/plans/2026-11-15-subsystems.md) 和 [数据库验证记录](docs/verification/database-matrix.md) 为准。数据库处理和工作流的重试与补跑可能重放数据，使用前必须自行确认写入幂等性和业务日期条件。

## 本地运行

需要 Java 21、Maven、Node.js、npm 和 MySQL 元数据库。MySQL 8.0.33 已通过基础迁移验证。

```sh
export GOVERNANCE_DB_URL=jdbc:mysql://localhost:3306/governance
export GOVERNANCE_DB_USER=your_database_user
export GOVERNANCE_DB_PASSWORD=your_database_password
export GOVERNANCE_CREDENTIAL_KEY=your_base64_encoded_32_byte_key
cd backend
mvn spring-boot:run
```

另开终端：

```sh
cd frontend
npm install
npm run dev
```

前端开发服务器将 `/api` 代理至本机 8080 端口。后端启动时 Flyway 执行元数据库迁移。`GOVERNANCE_CREDENTIAL_KEY` 是 32 字节随机密钥的 Base64 编码，需跨重启保持一致并妥善备份；丢失后无法解密已保存的数据源密码。业务数据源驱动存于 `GOVERNANCE_DRIVER_DIR`（默认 `./drivers`，内置驱动启动时自动释放注册，上传驱动持久保存），需随部署一起备份。部署环境应通过 HTTPS 保护用户会话和数据服务令牌，并限制环境变量及数据库凭据的访问。

验证命令：

```sh
mvn -f backend/pom.xml test
node --test frontend/tests/*.test.js
npm --prefix frontend run build
```

## 首次管理员初始化

先按上方环境变量配置并启动后端一次，确保数据库迁移完成。仅在受信任的交互终端执行以下命令；数据库必须没有用户，密码通过终端无回显输入，不得放入命令参数。

```sh
cd backend
mvn dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime
java -cp "target/classes:$(cat target/runtime-classpath.txt)" com.example.governance.bootstrap.BootstrapCommand
```

初始化使用数据库锁防止并发创建。早期开发数据库中的管理员可能缺少后来新增的权限，需通过现有授权流程补齐。自动化单元及接口测试主要使用 H2；MySQL 8.0.33 已验证元数据库迁移和基础工作流持久化，业务源/目标数据库的完整兼容性仍待验证。

平台元数据库使用 MySQL 专用 Flyway 脚本（`backend/src/main/resources/db/mysql/`），启动时自动升级并记录版本。原 PostgreSQL 脚本保留在 `db/migration/`，供既有 H2 测试使用；切换已有 PostgreSQL 部署时，不要直接复用其 Flyway 历史表或假设数据会自动转移，需要先备份并单独制定和验证数据迁移方案。

受控数据查询、报表及数据服务共用资源上限，可通过环境变量 `GOVERNANCE_QUERY_MAX_CONCURRENCY`（默认 8）、`GOVERNANCE_QUERY_TIMEOUT_SECONDS`（默认 30）和 `GOVERNANCE_QUERY_MAX_ROWS`（默认 1000）配置。超出并发时请求会立即失败，查询超时由 JDBC 驱动执行，实际取消效果依赖数据库和驱动。

数据权限在查询开始时按一致性快照解析。授权撤销后新查询会使用新规则；已经开始的查询可能继续完成。

批处理任务及工作流节点共用执行并发上限，可通过 `GOVERNANCE_EXECUTION_MAX_CONCURRENT` 设置（1–64，默认 8）；该上限不包含持续增量同步。等待执行配额的实例当前可能显示为运行中。
