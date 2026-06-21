# 测试覆盖率缺口分析与补测计划

> 分析基准日期：2026-04-08
> 全量测试状态：21 E2E + 190 单元/集成，全部通过
> 工具说明：JaCoCo 0.8.12 不支持 Java 25 bytecode（class file version 69），采用静态分析替代

---

## 一、整体覆盖率概览

| 模块 | 源文件 | 测试文件 | 文件覆盖率 | 状态 |
|------|--------|----------|-----------|------|
| batch-common | 109 | 27 | 24.8% | 🟡 一般 |
| batch-trigger | 39 | 14 | 35.9% | 🟢 较好 |
| batch-orchestrator | 245 | 66 | 26.9% | 🟡 一般 |
| batch-worker-core | 53 | 10 | 18.9% | 🔴 较差 |
| batch-worker-import | 38 | 12 | 31.6% | 🟡 一般 |
| batch-worker-export | 40 | 13 | 32.5% | 🟡 一般 |
| batch-worker-dispatch | 52 | 11 | 21.2% | 🔴 较差 |
| batch-console-api | 400 | 58 | 14.5% | 🔴 严重 |
| **合计** | **976** | **211** | **~21.6%** | |

---

## 二、按模块详细覆盖分类

图例：✅ 直接单元测试 / 🔶 集成测试覆盖 / 🔷 E2E 覆盖 / ❌ 无测试

### batch-common

**已覆盖（27 个）：**
- ✅ FileStateMachine、IdGenerator、JsonUtils、AlertFingerprints、ContentMaskingUtils、PathSanitizer（6 个工具类全覆盖）
- ✅ BatchObjectCryptoService、ShedLockProviderFactory、PageRequest
- ✅ 11 个枚举类（TaskStatus、WorkflowJoinMode、PartitionStatus 等）
- ✅ JdbcMappedSqlValidator

**无测试（重要类）：**
- ❌ DatabaseIdempotencyGuard（系统广泛使用，仅有 E2E 覆盖）
- ❌ TenantContext / TraceContext / AuditContext / ExecutionContext / StageExecutionContext
- ❌ LaunchRequest / LaunchResponse / TaskMessage / WorkerHeartbeatDto 等 DTO 类
- ❌ 35+ 枚举类

---

### batch-trigger

**已覆盖（14 个）：**
- ✅ DefaultTriggerService、DefaultLaunchAdapterService
- ✅ BatchDayCutoffScheduler、TriggerSchedulerFacade
- ✅ QuartzLaunchJob（HttpOrchestratorTriggerAdapter 已于 2026-05-02 删除）
- 🔶 TriggerRegistrationService（集成测试）

**无测试（重要类）：**
- ❌ TriggerDefinitionLoader / DatabaseTriggerDefinitionLoader
- ❌ CalendarBizDateResolver
- ❌ TriggerManagementController
- ❌ 所有 Mapper 类（4 个）

---

### batch-orchestrator

**已覆盖（66 个）：**
- ✅ DefaultApprovalWorkflowService、DefaultCompensationService
- ✅ DefaultRetryGovernanceService、DefaultTaskOutcomeService
- ✅ DefaultWorkerDrainGovernanceService、DefaultWorkflowDagService
- ✅ DefaultLaunchService、DefaultScheduleForwarder
- ✅ KafkaOutboxPublisher、DatabaseIdempotencyGuard（单元测试）
- ✅ DefaultStateMachine、DefaultStepRegistry、DefaultPipelineExecutor
- ✅ 5 个 Web Controller、2 个缓存服务
- ✅ PartitionLeaseReclaimScheduler、OutboxPollScheduler 等 8 个 Scheduler
- 🔶 25 个集成测试覆盖大量场景

**无测试（核心服务 11 个）：**
- ❌ DefaultFileGovernanceService — 文件生命周期治理
- ❌ DefaultPartitionDispatchService — 分区分发核心逻辑
- ❌ DefaultPartitionLifecycleService — 分区状态机
- ❌ DefaultTaskAssignmentService — 任务分配策略
- ❌ DefaultTaskCreationService — **任务创建主链路**
- ❌ DefaultTaskExecutionService — **任务执行主链路**
- ❌ DefaultWorkflowOrchestrationService — **工作流编排核心**
- ❌ DefaultAlertEventService — 告警事件服务
- ❌ DefaultLaunchValidationService — 启动前验证
- ❌ DefaultWorkerRegistryService — Worker 注册管理
- ❌ DefaultWorkflowNodeDispatchService — 节点分发（仅集成测试）

**无测试（Scheduler 6 个）：**
- ❌ WaitingPartitionDispatchScheduler — 等待分区定时扫描
- ❌ FileGovernanceArchiveCleanupScheduler — 文件归档清理
- ❌ FileGovernanceArrivalGroupScheduler — 文件到达分组
- ❌ FileGovernanceLatencyScheduler — 文件延迟监控
- ❌ FileGovernanceReconcileScheduler — 文件协调
- ❌ DefaultResourceScheduler — 资源调度

---

### batch-worker-core

**已覆盖（10 个）：**
- ✅ HttpTaskExecutionClient（含韧性层测试）
- ✅ HttpWorkerRegistryClient
- ✅ 部分基础设施和支持类

**无测试（关键类）：**
- ❌ **AbstractTaskConsumer** — 三 Worker 共用消费者基类，一处 bug 全体受影响
- ❌ AbstractPipelineStepExecutionAdapter / DefaultStepExecutionAdapter
- ❌ WorkerSelfRegistrationService / DefaultWorkerRegistryService
- ❌ KafkaConsumerConfiguration

---

### batch-worker-import

**已覆盖（12 个）：**
- ✅ ImportIngressScanner、JdbcMappedImportSpec、ImportLoadPluginRegistry
- ✅ ParseStep（含流式测试）、ReceiveStep（边界测试）
- 🔷 ImportPipelineE2eIT、ImportFailureE2eIT、ImportFailurePipelineE2eIT

**无测试（Pipeline Steps）：**
- ❌ ValidateStep — 字段/规则验证逻辑
- ❌ LoadStep — 数据写入数据库逻辑
- ❌ FeedbackStep — 回报反馈逻辑
- ❌ ImportStageStep — Stage 包装器
- ❌ ImportTaskConsumer — Kafka 消费者
- ❌ ImportDataQualityService、ImportRecordGovernanceService

---

### batch-worker-export

**已覆盖（13 个）：**
- ✅ PrepareStep、GenerateStep、StoreStep、RegisterStep（4/6 步骤）
- ✅ DefaultExportStageExecutor
- ✅ SqlTemplateExportSqlValidator（含安全修复验证）
- ✅ 6 个基础设施类（含 MinioExportStorage 集成测试）
- 🔷 4 个 E2E 测试

**无测试：**
- ❌ CompleteStep — 完成步骤
- ❌ ExportStageStep — Stage 包装器
- ❌ ExportTaskConsumer — Kafka 消费者

---

### batch-worker-dispatch

**已覆盖（11 个）：**
- ✅ 5 个基础设施类（ChannelConfigMerge、DispatchFileContentResolver 等）
- ✅ 2 个 Scheduler（部分）
- ✅ SftpDispatchChannelAdapter（含 Testcontainers 集成测试）
- 🔷 DispatchPipelineE2eIT、DispatchFailurePipelineE2eIT

**无测试（Pipeline Steps 7 个，100% 缺失）：**
- ❌ PrepareDispatchStep — 分发准备
- ❌ DeliverDispatchStep — 文件投递
- ❌ AckDispatchStep — 回执确认
- ❌ RetryDispatchStep — 重试逻辑
- ❌ CompensateDispatchStep — 补偿处理
- ❌ CompleteDispatchStep — 完成步骤
- ❌ DispatchStageStep — Stage 包装器
- ❌ DispatchTaskConsumer — Kafka 消费者
- ❌ DispatchChannelHealthService

---

### batch-console-api

**已覆盖（58 个）：**
- ✅ 20+ Web Controller（全覆盖）
- ✅ ConsoleTenantGuard、ConsoleRateLimitFilter、SlidingWindowRateLimiter
- ✅ ConsoleJwtService（部分）
- ✅ ConsoleAiPromptGuard
- ✅ 6 个 Excel 导入/导出服务
- 🔶 13 个集成测试

**无测试（应用服务层 30+ 个）：**
- ❌ **ConsoleAuthApplicationService** — 登录/JWT 签发（安全边界）
- ❌ **ConsoleAuthenticationFilter** — 认证主流程（安全边界）
- ❌ ConsoleApprovalApplicationService
- ❌ ConsoleWorkflowOrchestrationService
- ❌ ConsoleOrchestratorProxyService / ConsoleTriggerProxyService
- ❌ ConsoleFileGovernanceService
- ❌ ConsoleJobDefinitionApplicationService
- ❌ 20+ 其余查询/配置服务

---

## 三、E2E 覆盖场景（15 个）

| 类别 | 测试类 | 覆盖场景 |
|------|--------|---------|
| 导入 | ImportPipelineE2eIT | 完整导入主链路 |
| 导入 | ImportFailureE2eIT | 字段/模板不匹配失败 |
| 导入 | ImportFailurePipelineE2eIT | Pipeline 失败态推进 |
| 导出 | ExportPipelineE2eIT | 完整导出主链路 |
| 导出 | ExportFailurePipelineE2eIT | 导出 Pipeline 失败 |
| 导出 | ExportContentVerificationE2eIT | 文件内容/行数/金额断言 |
| 导出 | ExportStorageFailureE2eIT | 存储阶段失败 |
| 派发 | DispatchPipelineE2eIT | 完整派发主链路 + 回执验证 |
| 派发 | DispatchFailurePipelineE2eIT | 派发失败/补偿 |
| 编排 | DeadLetterApprovalReplayE2eIT | 死信审批重放全链路 |
| 编排 | DedupJobLaunchE2eIT | 顺序+并发 dedup 幂等 |
| 编排 | WorkerDrainE2eIT | Worker 排空接管 |
| 编排 | MultiTenantConcurrentE2eIT | 多租户并发隔离 |
| Outbox | OutboxForwarderE2eIT | 真实轮询 + 状态机 |
| Outbox | OutboxForwarderRetryE2eIT | 失败重试推进 |

---

## 四、补测优先级计划

### P0 — 必须补（生产风险最高）

#### 4.1 Dispatch Pipeline 7 个 Step

**原因**：派发主链路纯业务逻辑，E2E 只验证整链路成功/失败，不验证每个 Step 内的条件分支。任何分支 bug E2E 极可能抓不住。

目标测试：
- `PrepareDispatchStepTest` — 通道配置合法性、文件记录状态校验
- `DeliverDispatchStepTest` — 各渠道（LOCAL/SFTP/HTTP）投递分支
- `AckDispatchStepTest` — SYNC/ASYNC/POLLING 回执策略
- `RetryDispatchStepTest` — 重试计数、退避间隔、上限判断
- `CompensateDispatchStepTest` — 补偿状态写入、回滚路径
- `CompleteDispatchStepTest` — 完成状态更新、审计记录
- `DispatchStageStepTest` — Stage 路由与异常传播

#### 4.2 AbstractTaskConsumer（batch-worker-core）

**原因**：三个 Worker 共用基类，一处 bug 同时影响 Import/Export/Dispatch。当前无任何单元测试。

目标测试：
- `AbstractTaskConsumerTest` — doConsume 主流程、CLAIM 失败路径、ACK 时机、异常处理

---

### P1 — 高优先级（功能正确性）

#### 4.3 Orchestrator 3 个核心 Service

仅补最关键的 3 个，其余 8 个已有集成测试 + E2E 回退：

- `DefaultTaskCreationServiceTest` — 任务从分区到创建的逻辑分支
- `DefaultTaskExecutionServiceTest` — CLAIM → EXECUTE → REPORT 状态机
- `DefaultWorkflowOrchestrationServiceTest` — 节点推进、DAG 遍历、Join 模式判断

#### 4.4 Console 认证路径

**原因**：安全边界，登录流程无单元测试风险高：

- `ConsoleAuthApplicationServiceTest` — 登录成功/失败、JWT 签发、Token 验证
- `ConsoleAuthenticationFilterTest` — Token 解析、过期处理、403 路径

---

### 暂缓（ROI 低）

| 类别 | 原因 |
|------|------|
| Orchestrator 其余 8 个 Service | 已有集成测试 + E2E 覆盖，逻辑相对稳定 |
| Console 其余 26 个应用服务 | 大多为"查询 → 组装响应"，逻辑薄 |
| Trigger Mapper 类 | MyBatis XML 测试价值低 |
| FileGovernance 6 个 Scheduler | 定时触发逻辑相对独立，E2E 有间接覆盖 |

---

## 五、完成后预期覆盖率提升

| 补测内容 | 新增测试文件数 | 预期覆蓋率提升 |
|---------|--------------|--------------|
| Dispatch 7 Step + Consumer | +8 | dispatch: 21% → 55% |
| AbstractTaskConsumer | +1 | worker-core: 19% → 25% |
| Orchestrator 3 Service | +3 | orchestrator: 27% → 31% |
| Console Auth 2 类 | +2 | console: 14.5% → 16% |
| **合计新增** | **+14** | **整体: 21.6% → ~26%** |

> 说明：文件覆盖率提升幅度有限，因为补测集中在业务逻辑密集的类（行覆盖率提升会更显著，但无法用 JaCoCo 量化）。

---

## 六、补测完成验收标准

1. 所有新增测试文件纳入 CI，与现有测试一起通过
2. Dispatch 每个 Step 的主流程 + 至少 2 个失败分支有用例覆盖
3. AbstractTaskConsumer 的 CLAIM 失败、执行异常、ACK 时机三个关键路径有用例
4. Orchestrator 3 个 Service 核心方法的状态转换分支有用例
5. Console 登录成功、密码错误、Token 过期三个场景有用例
