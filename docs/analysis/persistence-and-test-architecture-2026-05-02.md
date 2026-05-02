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

### 1.3 ⚠️ 真违规：1 处

**[WorkflowNodePayloadBuilder.java:200](batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java:200)**：

```java
return jdbcTemplate.queryForObject(
    "select id from batch.file_record where tenant_id = :tenantId "
    + "and trace_id = :traceId and source_type = 'GENERATED' "
    + "order by id desc limit 1",
    params, Long.class);
```

这是**业务持久查询**（按 traceId 反查 file_record），按规范应放 `FileRecordMapper`。

**修复方案**（建议下次动 workflow 这块时顺手做）：

```java
// 在 batch-orchestrator/.../mapper/FileRecordMapper.java 加：
Long selectIdByTenantAndTraceId(@Param("tenantId") String tenantId,
                                 @Param("traceId") String traceId);
Long selectIdByTenantAndSourceRef(@Param("tenantId") String tenantId,
                                  @Param("sourceRef") String sourceRef);

// FileRecordMapper.xml 加对应 select
```

`WorkflowNodePayloadBuilder` 改为注入 `FileRecordMapper` 替换 `NamedParameterJdbcTemplate`。

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

## 三、测试夹具可抽取 — 三个方向，按 ROI 排序

### 3.1 方向 A：测试夹具 SQL builder（**最大重复**，强烈推荐）

8 个 console / orchestrator integration test 各自写 `insertXxx(jdbcTemplate, ...)`，目测重复 200+ 行 SQL 字符串：

| 测试文件 | 表 | 估行 |
|---|---|---|
| [AlertEventIntegrationTest](batch-console-api/src/test/java/com/example/batch/console/integration/AlertEventIntegrationTest.java) | `alert_event` | ~20 |
| [AlertEventActionIntegrationTest](batch-console-api/src/test/java/com/example/batch/console/integration/AlertEventActionIntegrationTest.java) | `alert_event` | inline 重复 |
| [ApprovalCommandQueryIntegrationTest](batch-console-api/src/test/java/com/example/batch/console/integration/ApprovalCommandQueryIntegrationTest.java) | `approval_command` | ~20 |
| [ConsoleRetryScheduleQueryIntegrationTest](batch-console-api/src/test/java/com/example/batch/console/integration/ConsoleRetryScheduleQueryIntegrationTest.java) | `retry_schedule` | ~25 |
| [DeadLetterQueryIntegrationTest](batch-console-api/src/test/java/com/example/batch/console/integration/DeadLetterQueryIntegrationTest.java) | `dead_letter` | ~25 |
| [JobInstanceQueryIntegrationTest](batch-console-api/src/test/java/com/example/batch/console/integration/JobInstanceQueryIntegrationTest.java) | `job_instance` | ~30 |
| [ConcurrentTaskFinishIntegrationTest](batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/ConcurrentTaskFinishIntegrationTest.java) | `job_task` | ~15 |
| [ConcurrentPartitionPromoteIntegrationTest](batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/ConcurrentPartitionPromoteIntegrationTest.java) | `job_partition` | ~15 |

**抽取方案**：

```
batch-common/src/test/java/com/example/batch/testing/fixtures/
├─ AlertEventFixtures.java
├─ ApprovalCommandFixtures.java
├─ DeadLetterFixtures.java
├─ RetryScheduleFixtures.java
├─ JobInstanceFixtures.java
├─ JobTaskFixtures.java
└─ JobPartitionFixtures.java
```

API 设计建议（builder + 默认值）：

```java
public final class AlertEventFixtures {
  private AlertEventFixtures() {}

  @Builder
  public record AlertEventSpec(
      String tenantId,
      String alertType,        // 默认 "SLA_BREACH"
      String severity,         // 默认 "CRITICAL"
      String status,           // 默认 "OPEN"
      String title,            // 默认 "test-alert-" + uuid
      String dedupFingerprint, // 默认 tenantId:alertType:nanoTime
      Instant firstSeenAt) {}  // 默认 Instant.now()

  public static long insert(JdbcTemplate jdbc, AlertEventSpec spec) {
    // 应用默认值 + 执行 INSERT + 返回 id
  }
}
```

**收益**：
- 同一表多测试夹具集中维护，schema 演进只改一处
- 消除 5 处 dedup_fingerprint 拼接、firstSeen/lastSeen 时间初始化等小重复
- 测试可读性提升（`AlertEventFixtures.insert(jdbc, AlertEventSpec.builder().tenantId(t).build())` 比 30 行 SQL 直观）

### 3.2 方向 B：`@SpringBootTest(properties = {...})` 集中

83 处 inline `@SpringBootTest(properties = ...)`，常见模式重复，例如：

```java
properties = {
  "batch.trigger.async-launch.enabled=false",
  "batch.kafka.enabled=true",
  "spring.data.redis.host=...",
  ...
}
```

**抽取方案**：

```
batch-common/src/test/resources/test-overrides/
├─ sync-trigger-mode.properties     // async-launch=false
├─ redis-only.properties            // 只 Redis 不 Kafka
├─ full-stack.properties            // 全栈
└─ disable-archive-jobs.properties  // 关 archive scheduler
```

测试改用：

```java
@SpringBootTest(classes = BatchOrchestratorApplication.class)
@TestPropertySource("classpath:test-overrides/sync-trigger-mode.properties")
class XxxTest extends AbstractIntegrationTest { ... }
```

**收益**：换 property 改 properties 文件，不必改 80+ 测试。

### 3.3 方向 C：`@MockitoBean` 集合 → `@TestConfiguration`

某些测试反复 mock 同一组 console 安全 bean：

```java
@MockitoBean ConsoleTenantGuard tenantGuard;
@MockitoBean ConsoleRequestMetadataResolver metadataResolver;
@MockitoBean ConsoleAuditService auditService;
```

**抽取方案**：

```java
@TestConfiguration
public class ConsoleSecurityMocksConfiguration {
  @Bean @Primary ConsoleTenantGuard tenantGuard() { return mock(ConsoleTenantGuard.class); }
  @Bean @Primary ConsoleRequestMetadataResolver metadataResolver() { ... }
  @Bean @Primary ConsoleAuditService auditService() { ... }
}
```

测试 `@Import(ConsoleSecurityMocksConfiguration.class)`。

**收益**：5+ 处一致 mock 组合不再 copy。**实际重复度需要再扫一次确认是否值得**。

---

## 四、推荐执行顺序

| 优先级 | 项 | ROI | 改动面 | 建议 |
|---|---|---|---|---|
| **P1** | 方向 A — 抽 `*Fixtures` | 高 | 中（新建 7 个 fixture 类 + 改 8 个测试 import） | **现在做** |
| **P1** | `WorkflowNodePayloadBuilder` 业务 SQL → `FileRecordMapper` | 中 | 小 | **现在做** |
| **P2** | 5 个独立 container 测试迁基类（迁 3 个） | 中 | 小 | 顺手 |
| **P3** | 方向 B — properties 文件抽取 | 中 | 大（80+ 测试） | 等下次动相关测试时顺手 |
| **P4** | 方向 C — mock 集合 `@TestConfiguration` | 低 | 小 | 真出现 5+ 重复时再做 |

---

## 五、相关 ADR / 文档

- [CLAUDE.md §架构硬约束](../../CLAUDE.md) — JdbcTemplate vs MyBatis 边界
- [docs/runbook/distributed-locking-checklist.md](../runbook/distributed-locking-checklist.md) — ShedLock JDBC fallback 设计
- [ADR-001 mybatis-vs-jpa](../architecture/adr/ADR-001-mybatis-vs-jpa.md) — 持久层选型
- [ADR-009 workflow-param-resolver](../architecture/adr/ADR-009-workflow-param-resolver.md) — Workflow 节点参数 DSL
- [docs/coding-conventions.md](../coding-conventions.md) — 整体编码约定
