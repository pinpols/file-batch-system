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
| **Facade** | `DefaultLaunchService`（636行）、`DefaultTaskExecutionService`（769行）、`DefaultCompensationService`（450行） | 对调用方隐藏内部子系统的协调复杂性，提供统一入口 |

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

#### ① Export 格式选择 → Strategy

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

---

#### ② 三个 StageExecutor 的重复 while 循环 → Template Method

**现状**：`DefaultImportStageExecutor`、`DefaultExportStageExecutor`、`DefaultDispatchStageExecutor` 各自维护 40-60 行几乎相同的阶段推进循环（while loop + `PipelineStepFlowSupport.resolveNextStep()` + 成功/失败汇总）。

**问题**：三份拷贝，逻辑修复需同步三处。

**方案**：抽 `AbstractStageExecutor<S extends StageStep, R extends StageResult>` 基类，封装通用循环骨架，子类只需提供 `stageSupplier()` 和 `onStageComplete()` 钩子。

---

#### ③ 补偿类型路由 → Strategy（取代 switch）

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

---

#### ④ 分区数量解析 → Strategy

**现状**：`DefaultSchedulePlanBuilder.resolveDynamicPartitionCount()` 含 5 层嵌套条件（固定值 / SIZE_BASED / RUNTIME_BASED / WORKER_BASED / 兜底）。

**方案**：抽 `PartitionCountResolver` 策略接口，按 `shard_strategy` 枚举选取具体 Resolver，各自独立测试，消除嵌套。

---

### P2 — 收益中等，适合专项优化

#### ⑤ DefaultStateMachine 反射调用 → 泛型接口

**现状**：通过反射调用 `getInstanceStatus` / `getPartitionStatus` / `getTaskStatus`，无编译期保护，字段改名即运行时崩溃。

**方案**：定义 `Stateful<S>` 泛型接口，各实体实现后直接调用，编译期即可发现问题。

---

#### ⑥ FileGovernanceScheduler → 单一职责拆分

**现状**：`FileGovernanceScheduler`（472行）合并了 latency 扫描、arrival group 处理、reconcile、archive cleanup 四个相互独立的调度职责。

**方案**：拆为 `FileLatencyScheduler`、`FileArrivalGroupScheduler`、`FileReconcileScheduler`、`FileArchiveScheduler`，各自独立开关（`enabled` 配置项），独立测试，与已有的 `OutboxPollScheduler`、`RetryScheduleScheduler` 风格一致。

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
| **God Class — 任务执行服务** | 🔴 高 | `DefaultTaskExecutionService`（769行）：任务创建、worker 分配、结果回报、partition 状态、workflow 节点分发全混在一处 | 拆分为 `TaskCreationService`、`TaskAssignmentService`、`TaskOutcomeService` |
| **God Class — 启动服务** | 🔴 高 | `DefaultLaunchService`（636行）：触发校验、定义查找、dedup、计划构建、分区创建、任务分发、workflow 初始化全在一处，13 个注入依赖 | 拆分为 `LaunchValidationService`、`SchedulePlanService`、`PartitionDispatchService` |
| **大事务** | 🔴 高 | `launch()` 在单个 `@Transactional` 内执行分区创建 + 任务分发 + workflow 初始化，长时间持锁 | 拆分为 prepare 事务（定义查询 + 实例创建）和 dispatch 事务（分区 + outbox），通过 outbox 解耦 |
| **`@Transactional` 嵌套调用链** | 🟡 中 | `CompensationService → LaunchService → RetryGovernanceService`，嵌套事务传播可能掩盖死锁或导致意外回滚扩散 | 审查传播级别，关键分支改为 `REQUIRES_NEW` 或异步解耦 |
| **反射调用状态 getter** | 🟡 中 | `DefaultStateMachine` 通过反射取 `instanceStatus` / `taskStatus`，字段改名即运行时崩溃 | 改为泛型接口（见建议⑤） |
| **Stage Executor 三份拷贝** | 🟡 中 | `DefaultImportStageExecutor` / `DefaultExportStageExecutor` / `DefaultDispatchStageExecutor` 的 while 循环几乎相同 | 抽 `AbstractStageExecutor` 基类（见建议②） |
| **Export 格式 if/else 链** | 🟡 中 | `GenerateStep.generate()` 中按字符串分派格式 | 改用 Strategy + Registry（见建议①） |
| **`WorkerRegistryService` 同名** | 🟢 低 | orchestrator（服务端接口）和 worker-core（客户端接口）各有一个同名接口，跨模块歧义 | 改名：`WorkerRegistryServerService`（orchestrator）/ `WorkerSelfRegistrationService`（worker-core） |

---

## 五、总结

```
已合理使用设计模式：15 个
建议引入（P1 高优先）：4 处
建议引入（P2 中优先）：2 处
不需要引入（保持简单）：6 处
需要重构的反模式：7 处（其中 3 处高风险）
```

### 架构整体评价

系统核心设计质量较高。**Outbox + Saga + Template Method + Strategy + Chain of Responsibility** 的组合构成了系统的核心骨架，设计合理且经过验证。

**最值得优先处理的两件事**：

1. **God Class 拆分**：`DefaultTaskExecutionService`（769行）和 `DefaultLaunchService`（636行）是最大的可维护性风险，随着业务增长会持续变大。
2. **大事务拆分**：`launch()` 的单事务边界在高并发下是锁竞争热点，结合 Outbox 模式已有基础，拆分代价较低且收益显著。

其余模式引入建议（Export Strategy、StageExecutor 基类、补偿路由 Strategy）属于代码质量提升，可在日常迭代中逐步完成，不需要集中专项重构。
