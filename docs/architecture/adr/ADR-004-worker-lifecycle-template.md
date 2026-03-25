# ADR-004: Worker 生命周期使用模板方法模式（AbstractWorkerLoop）

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

平台支持多种 Worker 类型（IMPORT、EXPORT、TRANSFORM 等），每种 Worker 的任务认领、心跳续租、状态上报流程完全相同，只有"执行具体业务逻辑"一步不同。

最初每个 Worker 模块各自实现完整的轮询循环，导致：
- 心跳续租逻辑在 3 个 Worker 模块中重复。
- 错误处理（超时重试、任务放弃）策略不一致。
- 新增 Worker 类型时需要复制大量框架代码。

## 决策

在 `batch-worker-core` 模块中提取抽象基类 `AbstractWorkerLoop`，使用**模板方法模式**：

```
AbstractWorkerLoop
  ├─ run()                     ← final，主循环框架
  ├─ claimNextTask()           ← final，调用 Orchestrator API 认领任务
  ├─ startHeartbeat()          ← final，启动后台续租线程
  ├─ reportOutcome()           ← final，调用 Orchestrator API 上报结果
  └─ executeTask(task)         ← abstract，子类实现具体业务逻辑
```

子类只需实现 `executeTask(JobTaskEntity task)` 方法，框架层处理所有生命周期管控。

具体 Worker 实现示例：

```java
@Component
public class ImportWorkerLoop extends AbstractWorkerLoop {
    @Override
    protected TaskOutcome executeTask(JobTaskEntity task) {
        // 具体导入逻辑
    }
}
```

## 后果

**正面**：
- 心跳续租、超时控制、结果上报代码集中在 `AbstractWorkerLoop`，单点维护。
- 新增 Worker 类型只需实现一个方法，框架强制遵循协议。
- `AbstractWorkerLoop` 可以被单独单元测试（mock `executeTask`）。

**负面**：
- 模板方法与策略模式相比灵活性较低；如果某 Worker 需要定制认领逻辑（如优先级队列），需要 override 更多方法。
- 抽象基类的继承层次增加了阅读跳转成本。

## 替代方案

**策略模式**（组合优于继承）：将 `executeTask` 提取为 `TaskExecutor` 接口，`WorkerLoop` 持有一个 `TaskExecutor` 实例。优点是更灵活，但需要额外的 bean 配置。当前场景下模板方法足够，如有需求可迁移。
