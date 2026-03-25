# ADR-003: launch() 的 T1/T2 事务拆分与 CGLIB 自注入

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

`LaunchService.launch()` 需要完成两个逻辑阶段：

- **T1**：创建 `job_instance` 和 `workflow_run`，写 `trigger_request`。这是幂等键（dedup key）的锚点，必须尽早提交，以便后续重试能命中去重路径。
- **T2**：创建 `job_partition`、`job_task`、`outbox_event`，标记作业 RUNNING。T2 依赖 T1 已提交的 `job_instance.id`（外键约束），且 T2 失败后应可单独重试，不回滚 T1。

最初实现将整个 `launch()` 包在一个 `@Transactional` 中，导致：
1. T2 异常时 T1 也回滚，`job_instance` 消失，下次带相同 `requestId` 的重试无法走去重路径，造成幂等失效。
2. 长事务持有行锁，高并发下锁争用加剧。

## 决策

将 `launch()` 拆分为两个独立事务：

```
launch()                        ← 不加 @Transactional（非事务方法）
  ├─ self.prepareJobInstance()  ← T1，@Transactional，提交后返回
  └─ partitionDispatchService   ← T2，独立 @Transactional bean
       .dispatch(...)
```

**CGLIB 自注入**解决 Spring AOP 无法拦截内部调用的问题：

```java
@Service
public class DefaultLaunchService implements LaunchService {

    @Lazy @Autowired
    private DefaultLaunchService self;  // 注入 CGLIB 代理

    @Override
    public LaunchResponse launch(LaunchRequest request) {
        // ...
        PreparedLaunch prepared = self.prepareJobInstance(...);  // 触发 @Transactional advice
        partitionDispatchService.dispatch(...);                   // T2 在其自己的事务中
        // ...
    }

    @Transactional
    public PreparedLaunch prepareJobInstance(...) {
        // 写 job_instance + workflow_run，返回后事务提交
    }
}
```

`@Lazy` 打破循环依赖（`DefaultLaunchService` 注入自身）；代理在首次调用时初始化。

## 为什么不用 REQUIRES_NEW

在 T2 上使用 `@Transactional(propagation = REQUIRES_NEW)` 无法解决问题：如果 T1 和 T2 在同一个外层事务中，T2 的 `REQUIRES_NEW` 会挂起外层事务，但 T1 的写入仍然未提交，T2 开始时无法读到 `job_instance`（数据库默认 READ COMMITTED 隔离级别）。必须让 T1 在 T2 开始前完全提交。

## 后果

**正面**：
- T2 失败时 T1 已提交，下次重试直接命中 dedup 路径，幂等性得到保证。
- 事务粒度缩小，锁持有时间减少。
- `LaunchT2FailureIntegrationTest` 验证了该行为。

**负面**：
- `@Lazy @Autowired` 字段注入是非标准模式，需要代码注释说明原因，防止后续开发者误删。
- `prepareJobInstance()` 是 `public` 方法但语义上属于内部 API，需注释标记"仅供 CGLIB 代理调用"。
- 如果未来将 `DefaultLaunchService` 声明为 `final`，CGLIB 代理将无法生成，需改用接口代理或 `@Transactional` 注解到独立 bean。

## 测试覆盖

`LaunchT2FailureIntegrationTest.t1CommitsAndDedupWorksWhenT2Throws()`：
1. 模拟 T2（`PartitionDispatchService.dispatch`）抛出异常。
2. 断言 `job_instance` 行存在（T1 已提交）。
3. 断言 `job_partition` / `job_task` 行为零（T2 已回滚）。
4. 用相同 `requestId` 重试，断言返回相同 `instanceNo`（dedup 生效）。
