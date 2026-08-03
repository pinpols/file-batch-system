# ADR-008: God Class 分解为子服务 + Facade 模式

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

随着功能迭代，两个核心服务类膨胀为 God Class：

| 类 | 行数 | 注入依赖数 | 问题 |
|---|---|---|---|
| `DefaultTaskExecutionService` | 774 | 11 | 任务创建、认领/续租、结果处理、日志追加、工作流节点记录全混在一起 |
| `DefaultLaunchService` | 636 | 13 | 校验、去重、参数合并、分区分发、DAG 初始化全混在一起；单一 `@Transactional` 导致幂等性问题 |

God Class 的直接影响：
- 单元测试需要 mock 11–13 个依赖，测试用例设置代码比断言多。
- 新增功能时难以判断改动影响范围，频繁出现合并冲突。
- `@Transactional` 跨越所有逻辑，事务边界不清晰（见 ADR-003）。

## 决策

**`DefaultTaskExecutionService` → Sub-service + Facade**：

```
DefaultTaskExecutionService（~60 行 Facade）
  ├─ TaskCreationService      ← createTask()
  ├─ TaskAssignmentService    ← assignWorker(), renewTaskLease(), updateTaskStatus(),
  │                              appendLog(), listLogs(), markRunning()
  └─ TaskOutcomeService       ← recordNodeRunReady/Start/Finish(), applyTaskOutcome()
```

Facade 实现 `TaskExecutionService` 接口，对外 API 不变，调用方零改动。

**`DefaultLaunchService` → 职责分离 + T1/T2 拆分**：

```
DefaultLaunchService（~280 行协调器）
  ├─ LaunchValidationService  ← load(), dedup 判断、参数校验
  └─ PartitionDispatchService ← dispatch()，独立 @Transactional bean
```

`DefaultLaunchService` 注入依赖从 13 个降至 7 个；T1/T2 事务拆分见 ADR-003。

## 实施结果

| 指标 | 重构前 | 重构后 |
|---|---|---|
| `DefaultTaskExecutionService` 行数 | 774 | ~60（Facade）|
| `DefaultLaunchService` 行数 | 636 | ~280 |
| 最大注入依赖数 | 13 | 7 |
| 单元测试 mock 数量（最大） | 11 | 4 |
| 集成测试通过数 | 245 | 245（零回归）|

## 后果

**正面**：
- 每个子服务职责单一，可独立测试。
- `@Transactional` 标注精确到业务边界，不再跨越整个流程。
- 新增任务类型时只需修改对应子服务，不触及 Facade。

**负面**：
- 类数量增加（+4 个子服务 + 1 个 record `PreparedLaunch`）。
- Facade 模式引入额外的间接层，IDE 调用栈多一跳。
- `LaunchValidationService` 和 `PartitionDispatchService` 目前是 package-private 实现，如需跨模块测试需要调整可见性。

## 参考

- ADR-003（T1/T2 事务拆分）：与本次重构紧密关联，`PartitionDispatchService` 独立为 bean 是 T1/T2 拆分的前提。
- [maturity-assessment.md](../maturity-assessment.md)：包含工程成熟度与重构相关的评估和度量。

## 2026-08 边界复核与增量收口

本轮没有以“减少行数”为目标继续拆分副作用密集的方法，避免把状态更新、事务和 DAG 派发拆散后产生新的事务边界。已落地的低风险收口如下：

- `NodePartitionProgressCalculator`：抽出纯的分区进度计算，输入输出稳定，可独立单测。
- `ParentVirtualTaskIdResolver`：抽出子 Job 参数快照解析，保留坏 JSON 静默降级、异常结构抛出的原语义。
- outbox 运维内部接口：使用稳定 record 表达 cleanup/republish 统计，保留原 JSON 字段名和 `dryRun` 数值字段，避免 console 通过字符串 key 取值。
- `ShellTaskExecutor`：补齐已声明的 `cancellable=true` 契约，登记运行进程并按进程树取消；取消后的资源清理仍由执行器自身负责。

以下内容明确暂不拆分：

- `DefaultTaskOutcomeService` 的 `advanceDagNodes`、实例终态推进和分区状态更新：这些逻辑共享同一事务和乐观锁语义，拆分前必须先定义新的事务边界并补真 PG 并发 IT。
- Console 中真正动态的 Map：配置字典、诊断结果、JSONB 文件明细和测试数据清理的“按表计数”结果。它们不是固定响应 schema，强行 DTO 化会制造伪稳定契约。
- Shell、Spark、HTTP 的进程/网络执行器：超时、取消、输出截断和外部资源语义不同，不建立空泛的共享基类。

后续重构判据：只有在能给出稳定输入输出契约、独立测试和明确事务边界时才新增抽象；单纯为了类变短或统一风格不启动重构。
