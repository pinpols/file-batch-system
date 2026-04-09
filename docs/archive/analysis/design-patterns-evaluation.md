# 设计模式评估报告

> 分析范围：全部模块（batch-common / batch-orchestrator / batch-worker-core / batch-worker-import / batch-worker-export / batch-worker-dispatch / batch-trigger / batch-console-api）
> 评估日期：2026-03-24

---

## 一、已使用的设计模式

### 创建型

| 模式 | 位置 | 说明 |
|---|---|---|
| **Builder** | `application/plan/SchedulePlanBuilder` / Minio `*Args.builder()` | 复杂调度计划对象的分步构造；Minio SDK 的链式 builder |
| **Factory Method** | `DefaultSchedulePlanBuilder.resolve*PartitionCount()` | 根据 `shard_strategy` 类型分派分区计算策略（FIXED / SIZE_BASED / RUNTIME_BASED / WORKER_BASED） |
| **Registry（工厂变体）** | `ImportLoadPluginRegistry`、`ExportDataPluginRegistry` | `Map<String, Plugin>` 按插件 ID 动态选取，支持运行时扩展 |

### 结构型

| 模式 | 位置 | 说明 |
|---|---|---|
| **Repository** | `*Repository`（Spring Data JDBC）+ `*Mapper`（MyBatis）双层 | 数据访问抽象；`*Record` 走 Spring Data，`*Entity` 走 MyBatis，持久化与业务逻辑解耦 |
| **Adapter** | `StepExecutionAdapter` → `ImportStepExecutionAdapter` / `ExportStepExecutionAdapter` / `DispatchStepExecutionAdapter` | 将泛型 Step 接口与各 Worker 特定管道桥接，核心框架不感知具体业务 |
| **Decorator** | `DispatchChannelGateway` 包裹所有 `DispatchChannelAdapter` | 在 adapter 调用前后透明注入健康检查、熔断器、指标采集，不修改 adapter 本身 |
| **Facade** | `DefaultLaunchService`（协调器，~280行）、`DefaultTaskExecutionService`（Facade，~60行，委托 `TaskCreationService` / `TaskAssignmentService` / `TaskOutcomeService`）、`DefaultCompensationService`（450行） | 对调用方隐藏内部子系统的协调复杂性，提供统一入口 |

### 行为型

| 模式 | 位置 | 说明 |
|---|---|---|
| **Template Method** | `AbstractPipelineStepExecutionAdapter<C,R>` → 3 个具体 Worker | 定义管道执行骨架（claim → buildContext → executeStages → report），子类实现 10+ 钩子方法 |
| **Strategy** | `ImportStageStep` / `ExportStageStep` / `DispatchStageStep` 各自多实现；`DispatchChannelAdapter`（HTTP / LOCAL / NAS / OSS / SFTP / SMTP 6 种实现） | 运行时按类型选取具体实现；新增策略不修改调用方 |
| **Chain of Responsibility** | `DefaultImportStageExecutor` / `DefaultExportStageExecutor` / `DefaultDispatchStageExecutor` + `PipelineStepFlowSupport.resolveNextStep()` | 管道阶段链式处理（PREPROCESS → PARSE → VALIDATE → LOAD 等），每个阶段可终止或将控制权传给下一阶段 |
| **Command** | `CompensationSubmitCommand`、`TaskOutcomeCommand`、`FileGovernanceCommand`、`DispatchCommand`（Java record） | 将操作参数封装为不可变对象，解耦调用方与执行方，便于审计与回放 |
| **State Machine** | `DefaultStateMachine` + 各 `*Status` 枚举（JobStatus、PartitionStatus、TaskStatus 等） | 显式状态转换：`READY → RUNNING → SUCCESS / FAILED / TERMINATED`；枚举保证类型安全 |
| **Outbox（事务发件箱）** | `TaskDispatchOutboxService` → `OutboxPollScheduler` → `KafkaOutboxPublisher` | 同一事务写 DB + outbox 事件，定时轮询后发 Kafka，保证 DB 与消息队列的最终一致性 |
| **Saga（编排型）** | `DefaultWorkflowDagService`（DAG 节点编排）、`DefaultApprovalWorkflowService`（审批多步骤流转） | 分布式长事务多步骤协调；每个节点状态持久化，支持故障重入 |
| **Circuit Breaker** | `DispatchChannelCircuitBreaker` | 对外部 dispatch 通道按失败次数熔断，`cooldownMillis` 后半开恢复，防止级联失败 |
| **Iterator（流式游标）** | `LoadStep`（逐行 BufferedReader 流式读取）、`GenerateStep`（游标分页 `ExportDataPlugin.DetailPage`） | 大文件 / 大数据集分批处理，避免全量加载导致 OOM |

---

## 二、建议引入的设计模式

### P1 — 收益高，改动范围可控

#### ① Export 格式选择 → Strategy ✅ 已完成

**现状**：`GenerateStep` 中的 `if/else` 链按 `fileFormatType` 字符串分派：

```java
if ("DELIMITED".equalsIgnoreCase(fileFormatType)) {
    recordCount = generateDelimited(...);
} else if ("FIXED_WIDTH".equalsIgnoreCase(fileFormatType)) {
    recordCount = generateFixedWidth(...);
} else if ("EXCEL".equalsIgnoreCase(fileFormatType)) {
    recordCount = generateExcel(...);
} else {
    recordCount = generateJson(...);
}
```
**问题**：新增格式必须修改 `GenerateStep`，违反开闭原则。

**方案**：参照已有的 `ImportLoadPluginRegistry` 风格，抽 `ExportFormatStrategy` 接口 + `JsonExportFormat` / `DelimitedExportFormat` / `ExcelExportFormat` 实现，通过 Registry 按 `fileFormatType` 选取。`GenerateStep` 只调用 `strategy.generate(context)`。

**实现说明**：新增 `batch-worker-export/stage/format/` 包：`ExportFormatStrategy` 接口、`ExportFormatContext` 参数容器、`AbstractExportFormat` 基类（含全部共享辅助方法和内部类型）、`JsonExportFormat` / `DelimitedExportFormat` / `ExcelExportFormat` / `FixedWidthExportFormat` 四个 `@Component` 实现、`ExportFormatStrategyRegistry` 注册表。`GenerateStep` 从 766 行缩减至 100 行，if/else 替换为 `formatStrategyRegistry.resolve(fileFormatType).generate(ctx)`。

---

#### ② 三个 StageExecutor 的重复 while 循环 → Template Method ✅ 已完成

**现状**：`DefaultImportStageExecutor`、`DefaultExportStageExecutor`、`DefaultDispatchStageExecutor` 各自维护 40-60 行几乎相同的阶段推进循环（while loop + `PipelineStepFlowSupport.resolveNextStep()` + 成功/失败汇总）。

**问题**：三份拷贝，逻辑修复需同步三处。

**方案**：抽 `AbstractStageExecutor<S extends StageStep, R extends StageResult>` 基类，封装通用循环骨架，子类只需提供 `stageSupplier()` 和 `onStageComplete()` 钩子。

**实现说明**：`batch-worker-core` 新增 `ExecutionContext` SPI（历史名 `PipelineContext`，接口方法为 `getTenantId/getJobCode/getWorkerId/getAttributes`）和 `StageExecutionResult` 接口（`success/code/message`）；三个 `*JobContext` 类实现 `ExecutionContext`，三个 `*StageResult` record 实现 `StageExecutionResult`；`AbstractStageExecutor<C,R>` 封装完整 while 循环（guard、updatePipelineStage、startStepRun、executeOneStep、finishStepRun、resolveNextStep），子类只需实现 6 个模板方法；三个具体 Executor 继承基类，`execute()` 改为一行 `return runStageLoop(context)`（Import 保留 try/finally 用于 finalizeErrorOutput）。

---

#### ③ 补偿类型路由 → Strategy（取代 switch）✅ 已完成

**现状**：`DefaultCompensationService.execute()` 的 6-way switch：

```java
switch (compensationType) {
    case "JOB"       -> rerunJob(...);
    case "STEP"      -> rerunStep(...);
    case "PARTITION" -> rerunPartition(...);
    case "FILE"      -> reprocessFile(...);
    case "BATCH"     -> rerunBatch(...);
    case "DLQ"       -> replayDeadLetter(...);
}
```

每个分支 30-50 行，合计近 200 行集中在一处。

**方案**：`CompensationHandler` 接口 + 按 `compensationType` 注册到 Map，`CompensationService` 只做路由查找。新增补偿类型无需修改核心 switch。

**实现说明**：新增 `CompensationHandler` `@FunctionalInterface`；`DefaultCompensationService` 改为显式构造器，在构造器中用方法引用初始化 `handlersByType`（`Map.of("JOB", this::rerunJob, ...)`）；`execute()` 方法替换 switch 为 `handlersByType.get(compensationType)` O(1) 路由查找；6 个私有业务方法保持不变。

---

#### ④ 分区数量解析 → Strategy ✅ 已完成

**现状**：`DefaultSchedulePlanBuilder.resolveDynamicPartitionCount()` 含 5 层嵌套条件（固定值 / SIZE_BASED / RUNTIME_BASED / WORKER_BASED / 兜底）。

**方案**：抽 `PartitionCountResolver` 策略接口，按 `shard_strategy` 枚举选取具体 Resolver，各自独立测试，消除嵌套。

**实现说明**：新增 `PartitionCountResolver` 接口；提取 4 个 `@Component` + `@Order` 实现：`ExplicitPartitionCountResolver`（Order=1）、`SizeBasedPartitionCountResolver`（Order=2）、`RuntimeBasedPartitionCountResolver`（Order=3）、`WorkerBasedPartitionCountResolver`（Order=4，注入 `WorkerRegistryRepository`）；`DefaultSchedulePlanBuilder` 移除 `workerRegistryRepository` 直接依赖，改为注入 `List<PartitionCountResolver> dynamicResolvers`；`resolveDynamicPartitionCount` 简化为 for 循环遍历 resolvers，首个正值即返回。

---

### P2 — 收益中等，适合专项优化

#### ⑤ DefaultStateMachine 反射调用 → 泛型接口 ✅ 已完成

**现状**：通过反射调用 `getInstanceStatus` / `getPartitionStatus` / `getTaskStatus`，无编译期保护，字段改名即运行时崩溃。

**方案**：定义 `Stateful<S>` 泛型接口，各实体实现后直接调用，编译期即可发现问题。

**实现说明**：新增 `Stateful` 接口（`domain/statemachine` 包，`String getStatus()`）；`JobInstanceEntity`（返回 `instanceStatus`）、`JobPartitionEntity`（返回 `partitionStatus`）、`JobTaskEntity`（返回 `taskStatus`）、`JobStepInstanceEntity`（返回 `stepStatus`）四个实体类实现 `Stateful`；`DefaultStateMachine.resolveState()` 在反射循环之前优先检查 `instanceof Stateful`，反射作为其他类型的兜底保留。

---

#### ⑥ FileGovernanceScheduler → 单一职责拆分 ✅ 已完成（早于本轮）

**现状**：`FileGovernanceScheduler`（472行）合并了 latency 扫描、arrival group 处理、reconcile、archive cleanup 四个相互独立的调度职责。

**方案**：拆为 `FileLatencyScheduler`、`FileArrivalGroupScheduler`、`FileReconcileScheduler`、`FileArchiveScheduler`，各自独立开关（`enabled` 配置项），独立测试，与已有的 `OutboxPollScheduler`、`RetryScheduleScheduler` 风格一致。

**实现说明**：`FileGovernanceScheduler` 保留为共享实现（`@Service`，无 `@Scheduled`），注释说明其角色；4 个独立 `@Component` 调度包装器（`FileGovernanceLatencyScheduler`、`FileGovernanceArrivalGroupScheduler`、`FileGovernanceArchiveCleanupScheduler`、`FileGovernanceReconcileScheduler`）各持有 `FileGovernanceScheduler` 引用，各自 `@Scheduled` + 独立 `fixedDelayString` 配置项。

---

## 三、不需要设计模式的地方

以下场景引入模式反而增加复杂度，保持现状更好：

| 场景 | 理由 |
|---|---|
| `WorkerRoutingPolicy` 接口（当前只有 1 个实现） | 单实现的接口是过早抽象；等第二种路由策略出现时再引入 |
| `SchedulePlanCommand` record | 纯参数容器，无行为，直接用 record 即可，Builder 是过度设计 |
| 各 Worker 的 `Default*WorkerRouteAdapter` | 逻辑简单（参数提取 + 转发），抽基类反而引入不必要的继承链 |
| `OutboxPollScheduler` 的单轮询逻辑 | 结构已足够简单，加 Observer / Event Bus 是过度工程 |
| `QuotaRuntimeStateService` | 纯数学计算 + DB 操作，逻辑清晰，State Machine 或 Strategy 反而模糊意图 |
| E2E 测试辅助类（`E2eScenarioFixture`、`E2eOutboxPublishSupport`） | 测试工具类，不需要生产代码的抽象层级 |

---

## 四、反模式与风险点

| 问题 | 严重度 | 位置 | 建议 |
|---|---|---|---|
| **God Class — 任务执行服务** ✅ 已完成 | 🔴 高 | `DefaultTaskExecutionService`（769行）：任务创建、worker 分配、结果回报、partition 状态、workflow 节点分发全混在一处 | 拆分为 `TaskCreationService` / `DefaultTaskCreationService`、`TaskAssignmentService` / `DefaultTaskAssignmentService`、`TaskOutcomeService` / `DefaultTaskOutcomeService`；`DefaultTaskExecutionService` 改为 ~60 行纯 Facade，全部委托给三个子服务 |
| **God Class — 启动服务** ✅ 已完成 | 🔴 高 | `DefaultLaunchService`（636行）：触发校验、定义查找、dedup、计划构建、分区创建、任务分发、workflow 初始化全在一处，13 个注入依赖 | 拆分为 `LaunchValidationService` / `DefaultLaunchValidationService`（只读校验 + dedup）和 `PartitionDispatchService` / `DefaultPartitionDispatchService`（分区 + 任务 + outbox + RUNNING 状态）；`DefaultLaunchService` 缩减为 ~280 行协调器，依赖从 13 降至 7 |
| **大事务** ✅ 已完成 | 🔴 高 | `launch()` 在单个 `@Transactional` 内执行分区创建 + 任务分发 + workflow 初始化，长时间持锁 | T1（`DefaultLaunchService.prepareJobInstance()`，`@Transactional`）：创建 job_instance + workflow_run + START node run，提交后 partition/task 表完全不持锁；T2（`DefaultPartitionDispatchService.dispatch()`，独立 `@Transactional` bean）：创建分区 + 任务 + outbox + 标记 RUNNING；`launch()` 本身不加 `@Transactional`，通过 `@Lazy self` 自注入触发 T1 的 Spring AOP 事务代理，保证 READ COMMITTED 下 T2 可读 T1 已提交数据 |
| **`@Transactional` 嵌套调用链** ✅ 已完成 | 🟡 中 | `CompensationService → LaunchService → RetryGovernanceService`，嵌套事务传播可能掩盖死锁或导致意外回滚扩散 | `DefaultRetryGovernanceService.retryTask()` / `retryPartition()` / `replayDeadLetter()` 改为 `@Transactional(propagation = REQUIRES_NEW)`，使手动补偿触发的重试在独立事务中运行，防止 partition/task 表锁竞争死锁向上传播到补偿命令记录事务 |
| **反射调用状态 getter** ✅ 已完成 | 🟡 中 | `DefaultStateMachine` 通过反射取 `instanceStatus` / `taskStatus`，字段改名即运行时崩溃 | 见建议⑤：新增 `Stateful` 接口，4 个实体类实现，`DefaultStateMachine` 优先走 `instanceof Stateful` 路径 |
| **Stage Executor 三份拷贝** ✅ 已完成 | 🟡 中 | `DefaultImportStageExecutor` / `DefaultExportStageExecutor` / `DefaultDispatchStageExecutor` 的 while 循环几乎相同 | 见建议②：`AbstractStageExecutor<C,R>` 基类封装完整循环，三个 Executor 继承基类 |
| **Export 格式 if/else 链** ✅ 已完成 | 🟡 中 | `GenerateStep.generate()` 中按字符串分派格式 | 见建议①：`ExportFormatStrategy` + `ExportFormatStrategyRegistry`，`GenerateStep` 从 766 行缩减至 100 行 |
| **`WorkerRegistryService` 同名** ✅ 已完成 | 🟢 低 | orchestrator（服务端接口）和 worker-core（客户端接口）各有一个同名接口，跨模块歧义 | 已改名：orchestrator 接口为 `WorkerRegistryServerService`，worker-core 接口为 `WorkerSelfRegistrationService`，两侧实现类分别加 `@Service("orchestratorWorkerRegistryService")` / `@Service("workerCoreWorkerRegistryService")` 限定 bean 名 |

---

## 五、总结

```
已合理使用设计模式：15 个
建议引入（P1 高优先）：4 处 ✅ 全部完成
建议引入（P2 中优先）：2 处 ✅ 全部完成
不需要引入（保持简单）：6 处
需要重构的反模式：7 处（3 处高风险 ✅ 全部完成；4 处中低风险 ✅ 全部完成）
```

### 架构整体评价

系统核心设计质量较高。**Outbox + Saga + Template Method + Strategy + Chain of Responsibility** 的组合构成了系统的核心骨架，设计合理且经过验证。

**全部反模式已完成重构**（2026-03-25）：

1. **God Class 拆分** ✅：`DefaultTaskExecutionService` → 3 个子服务 + Facade；`DefaultLaunchService` → `LaunchValidationService` + `PartitionDispatchService` + 协调器。
2. **大事务拆分** ✅：`launch()` 拆为 T1（实例创建，自注入事务）+ T2（分区分发，独立事务 bean），高并发下 partition/task 表锁竞争窗口大幅缩小。
3. **中低风险反模式** ✅：`@Transactional(REQUIRES_NEW)` 保护补偿链、`Stateful` 接口替换反射、`AbstractStageExecutor` 消除三份拷贝、Export Strategy 注册表。
