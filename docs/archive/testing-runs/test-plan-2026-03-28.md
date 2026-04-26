# 批量调度平台测试方案

> 文档日期：2026-03-28
> 适用分支：main
>
> 状态更新：截至 2026-03-28，本文第 2-3 节列出的核心缺口已补齐，当前内容用于记录现状和留作后续增量参考。

---

## 1. 现状盘点

### 1.1 已有测试总览

| 类型 | 数量 | 覆盖模块 |
|------|------|---------|
| 单元测试 (`*Test.java`) | 156 | common、orchestrator、worker-core、worker-import、worker-export、worker-dispatch、console-api |
| 集成测试 (`*IT.java` / `*IntegrationTest.java`) | 76 | orchestrator、worker-import、worker-export、worker-dispatch、console-api |
| 端到端测试 (`*E2eIT.java`) | 27 | e2e（全链路） |
| **合计** | **259** | |

### 1.2 测试基础设施

- **Testcontainers**：PostgreSQL 16（platform + business 两库）、Kafka 4.1.2、MinIO
- **WireMock 3.9.1**：外部 HTTP 依赖隔离
- **Awaitility**：E2E 异步断言
- **`AbstractIntegrationTest`**：全局单例容器 + `@DynamicPropertySource`，所有集成测试/E2E 共享

### 1.3 已覆盖的关键场景

- Import / Export / Dispatch 主链路成功 E2E
- Import / Export / Dispatch 失败链路 E2E
- 多租户隔离（`MultiTenantIsolationIntegrationTest`、`MultiTenantConcurrentE2eIT`）
- 审批工作流状态机（`ApprovalWorkflowIntegrationTest`）
- 并发分区晋升 / 并发任务完成（`ConcurrentPartition*`、`ConcurrentTaskFinish*`）
- 配额重置调度（`QuotaResetSchedulerIntegrationTest`）
- 重试治理（`RetryScheduleIntegrationTest`）
- Outbox 发布链路（`OutboxPublish*`、`OutboxForwarder*E2eIT`）
- 新增：ShedLock 表建表 + Provider 类型验证（4 个模块 IT，本次变更）
- 新增：`ConsoleHttpIntegrationIT` 补齐 console-api 核心写入口 HTTP smoke（jobs / workers / approvals / files / config / AI / download）

---

## 2. 现状核对（已补齐）

### 2.1 `batch-trigger` 已补齐（优先级 P0）

该模块已不再是空白区，当前已有 smoke / unit / integration / REST 覆盖。

| 待测类 | 类型 | 关注点 |
|--------|------|--------|
| `DefaultTriggerService` | 单元 | Quartz Job 注册/注销/更新逻辑 |
| `DefaultLaunchAdapterService` | 单元 | 调用 orchestrator HTTP 的请求构建 |
| `TriggerRegistrationStartup` | 集成 | 启动时从 DB 加载触发器定义并向 Quartz 注册 |
| `QuartzLaunchJob` | 集成 | Quartz 调度触发 → `LaunchAdapterService.launch()` |
| `MisfireHandler` / `QuartzMisfireListener` | 集成 | Misfire 补偿逻辑（跳过/追补） |
| `DatabaseTriggerDefinitionLoader` | 集成 | Mapper 查询触发器定义正确性 |
| `TriggerController` | REST | 手动触发接口、catch-up 接口参数校验 |
| `BatchTriggerApplication` | Smoke | Spring 上下文加载 |

当前对应测试已存在：`BatchTriggerApplicationIT`、`DefaultTriggerServiceTest`、`DefaultLaunchAdapterServiceTest`、`TriggerRegistrationStartupIT`、`QuartzLaunchJobIT`、`MisfireHandlerIT`、`TriggerControllerTest`。

### 2.2 Controller / REST 层已补齐（P1）

目前已有 MockMvc 单测与 HTTP 集成烟囱测试，核心 controller 已不再是空白。现状如下：

| 模块 | 待测 Controller | 关注点 |
|------|----------------|--------|
| batch-orchestrator | `LaunchController` | 请求体校验（jobCode 必填、tenant header）、返回 `CommonResponse` 结构 |
| batch-orchestrator | `TaskController` | 任务操作幂等性、错误码映射 |
| batch-orchestrator | `ApprovalController` | 审批操作幂等头、状态非法转换 → 4xx |
| batch-orchestrator | `DeadLetterController` | 死信重放请求参数 |
| batch-console-api | `ConsoleJobController` / `ConsoleWorkerController` / `ConsoleApprovalController` / `ConsoleFileController` / `ConsoleConfigController` / `ConsoleAiController` / `ConsoleFileDownloadController` | 已由 `ConsoleHttpIntegrationIT` 覆盖核心 HTTP 序列化与授权路径 |
| batch-trigger | `TriggerController` | 手动触发 / catch-up 请求 |

### 2.3 ShedLock 锁竞争行为已补齐（P1）

现有 4 个 `ShedLockConfigurationIT` 已覆盖：

- 同一锁名并发竞争时只有一个成功
- 锁超时后可重新获取
- 多模块（orchestrator / worker-import / worker-export / worker-dispatch）均有相同行为验证

### 2.4 `batch-console-api` 写操作集成测试已补齐（P1）

现有测试已覆盖 Query（查询）与核心写入口的 HTTP smoke：

| 待补测试场景 | 对应类 |
|-------------|--------|
| 触发 / 重跑 / 补偿 / 死信重放 | `ConsoleJobController` |
| Worker 强制下线 / Drain | `ConsoleWorkerController` |
| 审批提交 / 审批动作 / 批量审批 | `ConsoleApprovalController` |
| 秘钥版本轮换 / 版本发布 | `ConsoleConfigController` |
| 文件归档 / 删除 / 重派发 / 预签名下载 | `ConsoleFileController` / `ConsoleFileDownloadController` |
| AI chat | `ConsoleAiController` |

### 2.5 文件治理子流程集成测试已补齐（P2）

`FileGovernanceService` 的核心子流程已有集成测试，`FileGovernanceIntegrationTest` 已覆盖：

- 文件延迟告警触发验证
- 文件自动归档流程（状态 ACTIVE → ARCHIVED）
- 文件对账：孤儿文件检测
- 文件到达组聚合触发逻辑

### 2.6 Worker 优雅下线完整流程已补齐（P2）

`DefaultWorkerDrainGovernanceService` 已有 E2E 覆盖（`WorkerDrainE2eIT`）：

- 下线信号下发 → Worker 停止接单 → 飞行中任务排空 → 状态变为 DRAINED
- 超时强制下线（`default-timeout-seconds: 600`）

### 2.7 `batch-worker-export` MinIO 存储故障链路已补齐（P2）

已有 `ExportStorageFailureE2eIT` 与 `MinioExportStorageIntegrationTest`，当前已覆盖：

- MinIO 写入故障链路
- 预签名下载 URL 与下载控制器链路

---

## 3. 补充测试计划

### 阶段一：补齐 P0 盲区（batch-trigger）

**目标**：batch-trigger 模块从 0 测试到有基本保障。

#### 3.1.1 Smoke 测试

```
文件：batch-trigger/src/test/java/.../BatchTriggerApplicationIT.java
类型：@SpringBootTest + AbstractIntegrationTest
内容：
  - contextLoads()：Spring 上下文加载，Quartz Scheduler 已启动
  - quartzSchedulerStarted()：SchedulerFactory Bean 注入，isStarted() == true
```

#### 3.1.2 单元测试

```
文件：DefaultTriggerServiceTest.java
Mocks：TriggerSchedulerFacade、TriggerDefinitionLoader
场景：
  - 注册新触发器 → scheduleJob() 被调用一次，cron 表达式正确传入
  - 更新触发器 → rescheduleJob() 被调用，旧 trigger key 被替换
  - 暂停触发器 → pauseJob() 被调用
  - 恢复触发器 → resumeJob() 被调用
  - 删除触发器 → deleteJob() 被调用

文件：DefaultLaunchAdapterServiceTest.java
Mocks：WireMock（模拟 orchestrator HTTP /launch）
场景：
  - launch 成功 → 返回 LaunchResponse，jobInstanceId 非空
  - orchestrator 返回 4xx → 抛业务异常（不重试）
  - orchestrator 超时 → 抛连接超时异常
```

#### 3.1.3 集成测试

```
文件：TriggerRegistrationStartupIT.java
类型：@SpringBootTest(WebEnvironment.NONE) + AbstractIntegrationTest
数据：@Sql 插入 trigger_definition 测试数据
场景：
  - 启动后 Quartz 中触发器数量 == DB 中 active 定义数量
  - 触发器 cron 表达式与 DB 中配置一致

文件：QuartzLaunchJobIT.java
Mocks：WireMock 模拟 orchestrator
场景：
  - Quartz 调度触发 → WireMock 收到 POST /internal/launch 请求
  - 请求体包含正确的 tenantId、jobCode

文件：MisfireHandlerIT.java
场景：
  - 配置 misfire 策略为 SKIP → trigger 过期后不补偿执行
  - 配置 misfire 策略为 CATCH_UP → trigger 过期后触发一次追补
```

#### 3.1.4 REST 层测试

```
文件：TriggerControllerTest.java
类型：@WebMvcTest(TriggerController.class)
场景：
  - POST /trigger/launch 缺少 tenantId → 400 Bad Request
  - POST /trigger/launch 正常请求 → 200，body 有 jobInstanceId
  - POST /trigger/catch-up startDate > endDate → 400
  - POST /trigger/catch-up 正常 → 200，返回追补批次数
```

---

### 阶段二：Controller 层 REST 测试（P1）

对每个 Controller 建立 `@WebMvcTest` 测试类，重点验证：
1. 必填参数缺失 → 400
2. 状态非法操作 → 业务错误码（非 500）
3. 幂等头重复提交 → 正常幂等返回
4. 正常路径 → 200 + 响应结构正确

优先顺序：`LaunchController` → `ApprovalController` → `ConsoleOpsController` → `ConsoleApprovalController` → 其余。

**示例结构**（LaunchController）：

```java
@WebMvcTest(LaunchController.class)
class LaunchControllerTest {
    @MockitoBean LaunchService launchService;

    @Test void shouldReturn400WhenJobCodeMissing() { ... }
    @Test void shouldReturn200WithJobInstanceId() { ... }
    @Test void shouldReturn409WhenDuplicateIdempotencyKey() { ... }
}
```

---

### 阶段三：ShedLock 锁竞争验证（P1）

在现有 `ShedLockConfigurationIT` 基础上，**各模块均添加**以下测试方法：

```java
@Test
void shouldEnforceMutualExclusion() throws InterruptedException {
    // 同一锁名，两线程并发尝试获取，断言只有一个成功
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(2);
    Runnable tryLock = () -> {
        boolean acquired = lockProvider.lock(
            LockConfiguration.atMostFor("test-lock", Duration.ofSeconds(5)));
        if (acquired) successCount.incrementAndGet();
        latch.countDown();
    };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    pool.submit(tryLock);
    pool.submit(tryLock);
    latch.await(3, TimeUnit.SECONDS);
    assertThat(successCount.get()).isEqualTo(1);
}

@Test
void shouldAllowReacquireAfterExpiry() {
    // 锁超时后可被重新获取
}
```

---

### 阶段四：console-api 写操作集成测试（已完成）

已落地为 `batch-console-api/src/test/java/com/example/batch/console/integration/ConsoleHttpIntegrationIT.java`：

| 新测试文件 | 覆盖场景 |
|-----------|---------|
| `ConsoleHttpIntegrationIT.java` | trigger、drain、approve、archive、secret rotate、AI chat、download |
| `ConsoleJobDefinitionExcelControllerTest.java` | job definition Excel export / upload / preview / apply |
| `ConsoleWorkflowExcelControllerTest.java` | workflow Excel export / upload / preview / apply |
| `ConsoleReportExcelControllerTest.java` | report Excel export-only routes |

测试模式：使用 `@SpringBootTest(WebEnvironment.RANDOM_PORT)` + `WebTestClient` + `@MockitoBean`，同时覆盖 Controller 序列化层与授权过滤器；Excel 相关 controller 测试额外覆盖 export / upload / preview / apply 路由和报表导出路由。

---

### 阶段五：文件治理集成测试（已完成）

```
文件：FileGovernanceIntegrationTest.java
前置：@Sql 插入具有不同状态/时间戳的 file 记录
场景：
  - 延迟文件检测：插入 created_at = now()-2h 的 PENDING 文件 → 调度器触发告警事件
  - 自动归档：插入 completed_at = now()-8d 的 SUCCESS 文件 → 归档调度器将其状态更新为 ARCHIVED
  - 孤儿文件对账：MinIO 中存在但 DB 无记录的文件 → 对账调度器记录告警
  - 文件到达组聚合：所有预期文件到达 → 触发下游 launch
```

---

### 阶段六：Worker 优雅下线端到端（已完成）

现有 `WorkerDrainE2eIT.java` 已覆盖：

```
场景：
  1. Worker 有飞行中任务时发起 drain → Worker 停止拉取新任务
  2. 飞行中任务完成后 → Worker 状态变为 DRAINED
  3. drain 超时（注入延迟）→ 强制下线，任务补偿
```

---

## 4. 测试执行策略

### 4.1 分层运行

| 层级 | 命令 | 耗时预估 | CI 频率 |
|------|------|---------|---------|
| 单元测试 | `mvn test -pl batch-common,batch-orchestrator,...` | < 2 min | 每次 push |
| 集成测试 | `mvn verify -Dit.test="*IT,*IntegrationTest"` | 5–10 min | 每次 PR |
| E2E 测试 | `mvn verify -Dtags=e2e` | 10–20 min | 合并到 main 前 |

### 4.2 测试标签约定

```java
@Tag("unit")      // 纯单元测试，无容器依赖
@Tag("integration") // 集成测试，依赖 Testcontainers
@Tag("e2e")       // 端到端测试
```

新增 `@Tag("shedlock")` 用于独立运行分布式锁验证套件。

### 4.3 测试数据管理

- 集成测试：`@Transactional` + `@Rollback` 或 `@Sql` + `@AfterEach` 清理
- E2E：使用租户隔离（每个测试使用唯一 `tenantId`），避免测试间数据干扰
- 禁止在集成测试中使用固定 ID（使用 `IdGenerator` 生成），防止并发执行冲突

---

## 5. 优先级与排期回顾

| 优先级 | 内容 | 状态 |
|--------|------|------|
| **P0** | batch-trigger 全部测试（Smoke + 单元 + 集成 + REST） | 已完成 |
| **P1** | Controller 层 @WebMvcTest / HTTP smoke | 已完成 |
| **P1** | ShedLock 锁竞争测试（4 个模块各加方法） | 已完成 |
| **P1** | console-api 写操作集成测试 | 已完成 |
| **P2** | 文件治理集成测试 | 已完成 |
| **P2** | Worker Drain E2E | 已完成 |

---

## 6. 验收结果

| 指标 | 结果 |
|------|------|
| batch-trigger 测试文件数 | 已达成 |
| Controller 层 HTTP / REST 覆盖 | 已达成 |
| ShedLock 锁竞争测试 | 已达成 |
| console-api 写操作集成覆盖 | 已达成 |
| 文件治理集成覆盖 | 已达成 |
| Worker Drain E2E | 已达成 |

---

## 附录：当前测试文件分布图

```
batch-common            15 个单元测试 + 2 个测试基础类
batch-orchestrator      26 个单元测试 + 17 个集成测试 ← 覆盖最完整
batch-worker-core        7 个单元测试
batch-worker-import      8 个单元测试 + 2 个集成测试
batch-worker-export      9 个单元测试 + 3 个集成测试
batch-worker-dispatch    6 个单元测试 + 3 个集成测试
batch-console-api        已补齐 HTTP smoke + 单测
batch-trigger            已补齐 smoke / unit / integration / REST
batch-e2e-tests         14 个 E2E 测试
```
