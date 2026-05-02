# 持久层 + 测试架构审计报告 — 2026-05-02

> 范围：全仓 9 个生产模块 + 374 个测试文件
> 触发：审视"JdbcTemplate 是否限定在测试 / 基础设施"、"测试代码风格一致性"、"测试夹具是否可抽出共用工具"
> 验证：cross-module compile + 4 类工具命中分类

---

## 一、JdbcTemplate 在生产代码的使用 — 11 个文件，1 处违规

CLAUDE.md §架构硬约束：
> 全业务模块持久层**统一 MyBatis**（运行态与配置态均 Mapper）+ `JdbcTemplate` 基础设施

按这条线把 11 个文件分类：

### 1.1 ✅ 合规：基础设施 5 个

| 文件 | 用途 | JdbcTemplate 合理性 |
|---|---|---|
| [ShedLockProviderFactory.java](batch-common/src/main/java/com/example/batch/common/config/ShedLockProviderFactory.java) | JDBC LockProvider 兜底（trigger / worker 用） | ShedLock JDBC 实现要求 |
| [BatchStartupSelfCheck.java](batch-common/src/main/java/com/example/batch/common/health/BatchStartupSelfCheck.java) | `information_schema.tables/columns` 启动健康检查 | 系统表查询无 mapper 必要 |
| [ArchiveSchemaDriftCheck.java](batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/archive/ArchiveSchemaDriftCheck.java) | 归档表 schema 漂移检测 | `information_schema` 查询 |
| [OrchestratorStartupLeaseAudit.java](batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/startup/OrchestratorStartupLeaseAudit.java) | 启动 lease 审计 | 一次性诊断查询 |
| [ProcessStagingOrphanCleaner.java](batch-worker-process/src/main/java/com/example/batch/worker/processes/cleanup/ProcessStagingOrphanCleaner.java) | staging 表 orphan 清理 | 跨表 cleanup SQL，业务 mapper 不暴露 |

### 1.2 ✅ 合规：ADR-009 SQL plugin 4 个

| 文件 | 用途 |
|---|---|
| [GenericJdbcMappedExportDataPlugin.java](batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java) | 用户配置 SQL 导出 |
| [SqlTemplateExportDataPlugin.java](batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java) | 同上 |
| [GenericJdbcMappedImportLoadPlugin.java](batch-worker-import/src/main/java/com/example/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java) | 用户配置 SQL 导入 |
| [SqlTransformComputePlugin.java](batch-worker-process/src/main/java/com/example/batch/worker/processes/sql/SqlTransformComputePlugin.java) | 用户配置 SQL 加工 |

ADR-009 设计上必须 `NamedParameterJdbcTemplate`（用户运行时输入的 SQL，不能预先 mapper 化）。

### 1.3 ✅ 已修复：1 处（2026-05-02 当日完成）

`WorkflowNodePayloadBuilder` 原本用 `NamedParameterJdbcTemplate` 反查 `file_record`，已迁到 `FileRecordLookupMapper`。详见 commit `322ba399`。

### 1.4 🟡 false positive：1 处

[SqlTemplateExportSqlValidator.java:185](batch-worker-export/src/main/java/com/example/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java:185) — 仅注释里出现 `NamedParameterJdbcTemplate` 字符串，没实际引用。

---

## 二、测试代码风格一致性 — 整体一致，1 处分裂

### 2.1 风格统一项

| 维度 | 现状 | 评价 |
|---|---|---|
| 断言库 | 100% AssertJ（344 处 `import static org.assertj`） | ✅ |
| Mock 框架 | 100% Mockito（无 PowerMock / EasyMock 混用） | ✅ |
| Mock 模式 | `@Mock`（50） + `@ExtendWith(MockitoExtension.class)`（50） | ✅ 统一 |
| Spring mock | `@MockitoBean`（9，Spring Boot 3.4+ 新名） + 0 处 `@MockBean`（已弃） | ✅ 跟新 |
| `@Spy` | 0 处 | ✅ 避免部分 mock 复杂度 |
| 生命周期 | `@BeforeEach`（179 处） | ✅ JUnit5 标准 |

### 2.2 唯一分裂点：5 个集成测试不继承 `AbstractIntegrationTest`

[AbstractIntegrationTest.java](batch-common/src/test/java/com/example/batch/testing/AbstractIntegrationTest.java) 已统一管理 PG / Kafka / MinIO / Redis 容器，但下面 5 个测试自己起 `PostgreSQLContainer`：

| 测试 | 是否合理 | 备注 |
|---|---|---|
| [SlidingWindowRateLimiterIT](batch-console-api/src/test/java/com/example/batch/console/support/ratelimit/SlidingWindowRateLimiterIT.java) | 🟡 部分 | 只需 Redis；可继承用 lazy 启动 |
| [SqlConsistencyIntegrationTest](batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/SqlConsistencyIntegrationTest.java) | 🟡 部分 | 校验 SQL 一致性，可能要干净 schema；建议加注释 |
| [BatchDaySqlMigrationsIntegrationTest](batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/BatchDaySqlMigrationsIntegrationTest.java) | ✅ 合理 | Flyway migration 测试**必须**独立 DB |
| [LocalFlywayPlatformMigrationsIntegrationTest](batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/LocalFlywayPlatformMigrationsIntegrationTest.java) | ✅ 合理 | 同上 |
| [SqlTransformComputePluginIntegrationTest](batch-worker-process/src/test/java/com/example/batch/worker/processes/sql/SqlTransformComputePluginIntegrationTest.java) | 🟡 部分 | 业务表测试，可继承 |

**风险**：每个独立 container = 多启动一次 PG（容器启动 ~5s），CI 累计开销大；版本号在多处维护容易漂移。

**建议**：
- migration 类（必须独立 DB）：保持但加注释说明
- 其余 3 个：迁到 `AbstractIntegrationTest` 复用全局容器；如确实需要独立 schema 用 `@Sql(executionPhase = BEFORE_TEST_METHOD)` + `TRUNCATE` 实现

---

## 三、测试夹具抽取 — 三个方向均评估后不做

初稿评估了三种夹具/配置抽取方向，2026-05-02 当日讨论后**全部否决**，理由记录如下，供未来重新评估时参考：

### 3.1 ❌ 方向 A：测试夹具 SQL builder

涉及 8 个 integration test、7 张表（`alert_event`/`approval_command`/`retry_schedule`/`dead_letter`/`job_instance`/`job_task`/`job_partition`）。

**否决原因**：7 张表里 **6 张只有 1 个测试用**，仅 `alert_event` 是 2 个。所谓"共享夹具"实际只服务一个调用方——这是把代码搬位置加抽象层，不是去重。Builder + 默认值会隐藏 severity/status/title 等字段语义，测试不能独立读懂；几个月后改默认会静默改变所有测试的预期。违反 CLAUDE.md "三行重复好过过早抽象"。

**未来重新评估的触发条件**：当某张表的测试调用方涨到 3+ 个，且确认每次都是相同字段组合，再单独抽该表的 fixture（不要一次抽 7 个）。

### 3.2 ❌ 方向 B：`@SpringBootTest(properties = ...)` 集中

83 处 inline properties，初稿建议抽到 `batch-common/src/test/resources/test-overrides/*.properties` 然后用 `@TestPropertySource`。

**否决原因**：单测里 5–10 行 properties 不是真痛点，property key 一年也改不了几次，"schema 演进改 1 处 vs 80 处"是理论收益。改造要动 80+ 文件、`@TestPropertySource` 与 inline properties 优先级语义不同有引入隐性 bug 的风险，ROI 倒挂。

**未来重新评估的触发条件**：integration test 整套 startup 慢到团队抱怨（>5 min），且 grep 后 83 处 unique combination 收敛到 ≤5 种，再走 **Spring profile + `application-<profile>.yml`** 路线（不要 `.properties` 文件，profile 才是 Spring 官方机制，context cache 命中率也更好）。

### 3.3 ❌ 方向 C：`@MockitoBean` 集合 → `@TestConfiguration`

初稿设想把 `ConsoleTenantGuard` / `ConsoleRequestMetadataResolver` / `ConsoleAuditService` 三个 mock 打包成 `ConsoleSecurityMocksConfiguration`。

**否决原因**：当时就标注"实际重复度需要再扫一次确认是否值得"，未实际度量；按 A/B 同样的尺子，没有清晰的高频调用方，不值得提前抽。

**未来重新评估的触发条件**：扫到 5+ 处一致的 `@MockitoBean` 三件套且字段完全相同时再抽。

---

## 四、本次审计后续动作

| 项 | 状态 | 备注 |
|---|---|---|
| `WorkflowNodePayloadBuilder` 业务 SQL → MyBatis | ✅ 已做 | commit `322ba399`，迁 `FileRecordLookupMapper` |
| §2.2 三个非 migration 测试迁 `AbstractIntegrationTest` | 🟡 顺手做 | `SlidingWindowRateLimiterIT` / `SqlConsistencyIntegrationTest` / `SqlTransformComputePluginIntegrationTest`，下次动这些测试时顺手 |
| 方向 A / B / C | ❌ 不做 | 详见 §三 |

---

## 五、相关 ADR / 文档

- [CLAUDE.md §架构硬约束](../../CLAUDE.md) — JdbcTemplate vs MyBatis 边界
- [docs/runbook/distributed-locking-checklist.md](../runbook/distributed-locking-checklist.md) — ShedLock JDBC fallback 设计
- [ADR-001 mybatis-vs-jpa](../architecture/adr/ADR-001-mybatis-vs-jpa.md) — 持久层选型
- [ADR-009 workflow-param-resolver](../architecture/adr/ADR-009-workflow-param-resolver.md) — Workflow 节点参数 DSL
- [docs/coding-conventions.md](../coding-conventions.md) — 整体编码约定
