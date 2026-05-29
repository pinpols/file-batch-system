# Task SPI / Plugin 化设计(P0)

> 立项:[p0-p1-p2-roadmap §P0](../architecture/p0-p1-p2-roadmap.md#p0-任务类型-spi--plugin-化)
>
> 目标:让"加新任务类型"从"开新 worker module + 改 BE"降到"实现 1 个 SPI 类 + 1 行注册"。

---

## 1. 现状盘点(必读,决定设计)

### 1.1 已有抽象(不是 SPI,但是有部分契约)

| 抽象 | 包 | 职责 | 实现数 |
|---|---|---|---:|
| `StepExecutionAdapter` | `batch-worker-core.support` | 单步执行入口:`StepExecutionResponse execute(StepExecutionRequest)` | 5(default + 4 worker) |
| `AbstractPipelineStepExecutionAdapter<C,R>` | `batch-worker-core.support` | Pipeline 生命周期模板(确保定义 → 创建实例 → 跑 stage loop → 标记终态) | 4(import/export/process/dispatch) |
| `AbstractStageExecutor<C,R>` | `batch-worker-core.support` | Stage 内部 while loop 模板 | 3(import/export/dispatch 共用) |
| `TaskExecutionWrapper` | `batch-worker-core.support` | CLAIM + execute + report 一站 | 1(`DefaultTaskExecutionWrapper`) |
| `WorkerRouteAdapter` | `batch-worker-core.route` | 注册 worker 元信息(`worker_type` 等) | 4 |
| `PipelineStepTemplateProvider` | `batch-worker-core.support` | 给 pipeline_definition 自动登记默认步骤 | 4 |

**关键发现**:已经有"Step → Stage → Pipeline → Task"四层模型,worker 内部抽象不薄。问题不在抽象**不够**,而在抽象**绑死了 Pipeline 形态**。

### 1.2 Pipeline 形态绑死在哪

- `JobType` 6 枚举(`GENERAL/IMPORT/EXPORT/PROCESS/DISPATCH/WORKFLOW`),硬编码
- worker module 物理拆分(`batch-worker-{import,export,process,dispatch}`),每个 module 一种 pipeline
- `AbstractPipelineStepExecutionAdapter` 强制要求:必须有 `pipelineType()` / `defaultPipelineSteps()` / `executeStages()` —— 这一套是为"文件处理 5-6 stage"设计的
- `JobType.GENERAL` 是占位枚举,**无 worker handler 实现**(grep 0 处)

### 1.3 缺什么

**缺一个"原子任务"的 SPI**——不走 pipeline 生命周期(无 stage / 无 step 循环),直接 `输入 → 执行 → 输出`。Shell / SQL / HTTP / 存过 都是这种形态。

---

## 2. 设计思路:Two-Tier SPI(双层)

不要把 Pipeline SPI 和原子任务 SPI 揉成一个,会变成"什么都是又什么都不是"的接口。

```
                       ┌─────────────────────────┐
                       │  StepExecutionAdapter   │   ← 已有,不动
                       │  (worker 单步入口)       │
                       └───────────┬─────────────┘
                                   │
                      ┌────────────┴────────────┐
                      │                         │
                      ▼                         ▼
        ┌────────────────────────┐    ┌────────────────────────┐
        │ AbstractPipeline       │    │ BatchTaskExecutor SPI  │  ← 本次新加
        │ StepExecutionAdapter   │    │ (原子任务,无 stage)     │
        │ (已有,Pipeline 形态)    │    │                        │
        ├────────────────────────┤    ├────────────────────────┤
        │ Import / Export /      │    │ Shell / SQL /          │
        │ Process / Dispatch     │    │ StoredProc / HTTP /    │
        │ (4 个 worker 实现)      │    │ SFTP / Kafka / ...     │
        │                        │    │ (按 jar 装载)           │
        └────────────────────────┘    └────────────────────────┘
```

### 2.1 分层职责

| 层 | 形态 | 适合的任务 | 谁实现 |
|---|---|---|---|
| **Pipeline SPI**(已有) | 多 stage 状态机 + 文件生命周期 + outbox | 文件处理(IMPORT/EXPORT/PROCESS/DISPATCH)/ 多步业务流程 | worker module 级实现(重) |
| **Atomic Task SPI**(新加) | 单步执行,无生命周期 | Shell / SQL / HTTP / 存过 / 任意脚本 | 一个 Java 类 + ServiceLoader(轻) |

### 2.2 桥接策略

`DefaultStepExecutionAdapter`(已有但是 no-op fallback)改造为**路由器**:

```java
public class DefaultStepExecutionAdapter implements StepExecutionAdapter {
  private final BatchTaskExecutorRegistry registry;
  // 不再 no-op,而是按 taskType 查 registry 路由到 BatchTaskExecutor

  @Override
  public StepExecutionResponse execute(StepExecutionRequest request) {
    BatchTaskExecutor executor = registry.find(request.stepCode());  // stepCode = task type
    if (executor == null) {
      return StepExecutionResponse.failure("no executor for taskType=" + request.stepCode());
    }
    TaskContext ctx = TaskContext.from(request);
    TaskResult result = executor.execute(ctx);
    return result.toStepResponse();
  }
}
```

**`@Primary`** 仍然属于 4 个 worker module 的 `XxxStepExecutionAdapter`(Pipeline 实现优先);只有当 Pipeline 链路不匹配时,fallback 到 atomic task registry。

---

## 3. SPI 契约(放在 `batch-common`)

### 3.1 核心接口

```java
package com.example.batch.common.spi.task;

public interface BatchTaskExecutor {
  /** 全平台唯一标识(snake_case,如 "shell" / "sql" / "stored_proc" / "http" / "sftp_push")。 */
  String taskType();

  /** 资源 / I-O 声明,给 worker registry 路由匹配用。 */
  TaskCapability capability();

  /** 真正的执行入口。实现方负责自己的超时 / 取消语义。 */
  TaskResult execute(TaskContext ctx);

  /** 可选:协作式取消(orchestrator 发取消时调)。默认 no-op。 */
  default void cancel(String taskInstanceId) {}
}

public record TaskCapability(
    Set<ResourceKind> resourceKinds,       // CPU/NET/DISK/DB
    boolean idempotent,                    // 重跑是否安全
    boolean cancellable,                   // 是否支持 cancel
    Duration recommendedTimeout) {}

public enum ResourceKind { CPU, NET, DISK, DB, GPU }
```

### 3.2 `TaskContext`(框架给的执行上下文)

```java
public record TaskContext(
    String tenantId,
    String jobCode,
    String taskInstanceId,
    Map<String, Object> parameters,        // 用户定义(jobDefinition.parameters)
    Map<String, Object> runtimeAttributes, // 框架注入(traceId / bizDate / workerId)
    TaskOutputCollector output,            // 收 stdout / 业务结果
    BatchTimezoneProvider tz,
    MeterRegistry metrics,
    BatchProperties props) {

  public static TaskContext from(StepExecutionRequest req, ApplicationContext appCtx) {
    // adapter:从现有的 StepExecutionRequest + Spring beans 拼出来
    // 这样 BatchTaskExecutor 可写无 Spring 依赖,纯 POJO
  }
}
```

**重要**:`TaskContext` 不是 `@Component` 不是 Spring bean。SPI 实现可以是普通 POJO(便于第三方 jar 不引 Spring 完整体)。

### 3.3 `TaskResult`

```java
public record TaskResult(
    boolean success,
    String message,
    Map<String, Object> output,            // 给下游 step / report 用
    Throwable error) {

  public static TaskResult ok() { return new TaskResult(true, null, Map.of(), null); }
  public static TaskResult ok(Map<String,Object> out) { return new TaskResult(true, null, out, null); }
  public static TaskResult fail(String msg) { return new TaskResult(false, msg, Map.of(), null); }
  public static TaskResult fail(Throwable e) { return new TaskResult(false, e.getMessage(), Map.of(), e); }
}
```

### 3.4 Registry

```java
package com.example.batch.common.spi.task;

@Component
public class BatchTaskExecutorRegistry {
  private final Map<String, BatchTaskExecutor> byType;

  public BatchTaskExecutorRegistry(List<BatchTaskExecutor> springBeans) {
    Map<String, BatchTaskExecutor> merged = new HashMap<>();

    // 1) Spring 容器里所有 BatchTaskExecutor bean
    for (BatchTaskExecutor e : springBeans) put(merged, e);

    // 2) ServiceLoader 扫 classpath META-INF/services
    for (BatchTaskExecutor e : ServiceLoader.load(BatchTaskExecutor.class)) put(merged, e);

    this.byType = Map.copyOf(merged);
  }

  private void put(Map<String, BatchTaskExecutor> m, BatchTaskExecutor e) {
    BatchTaskExecutor prev = m.putIfAbsent(e.taskType(), e);
    if (prev != null && prev != e) {
      throw new IllegalStateException(
          "duplicate BatchTaskExecutor for taskType=" + e.taskType()
          + ": " + prev.getClass() + " vs " + e.getClass());
    }
  }

  public BatchTaskExecutor find(String taskType) { return byType.get(taskType); }
  public Set<String> registeredTypes() { return byType.keySet(); }
}
```

**双路注册**:既支持 Spring `@Component`(主路径,可用 `@RequiredArgsConstructor` + autowire 依赖),又支持 ServiceLoader(给纯 jar 插件用,无 Spring 依赖)。重复 type 启动期 fail-fast。

---

## 4. 迁移路径(分阶段,每阶段单独可发布)

### Phase 1:加 SPI 契约 + Registry + 路由(1 周)

**不动现有 4 worker**,只加新代码 + 改 1 个文件:

| 新增 | 路径 | 作用 |
|---|---|---|
| `BatchTaskExecutor` | `batch-common/.../spi/task/` | 接口 |
| `TaskContext` / `TaskResult` / `TaskCapability` | 同上 | 数据 |
| `BatchTaskExecutorRegistry` | 同上 | Spring bean,启动注册 |
| `BatchTaskExecutorSpiArchUnitTest` | `batch-common test` | 守护:实现类不能 depend Spring core 之外的框架(可选) |

| 修改 | 路径 | 作用 |
|---|---|---|
| `DefaultStepExecutionAdapter` | `batch-worker-core.infrastructure` | 改为 Registry 路由器(本来是 no-op) |

**测试**:
- `BatchTaskExecutorRegistryTest` 单测:Spring beans + ServiceLoader 双路注册;重复 type 启动失败
- `DefaultStepExecutionAdapterRouteTest`:registry 找不到 → 返 failure response
- 守护测试:`@Primary` 优先级不变(Pipeline 实现仍胜出)

**风险**:0(纯加内容,不动现有路径)。

---

### Phase 2:builtin 4 件套(2 周)

**先做 `ShellTaskExecutor`** 作为整个 SPI 的第一只豚鼠 → 验证从设计到落地全链路 → 再批量做 SQL / StoredProc / HTTP。

| Builtin | 模块 | 大小 |
|---|---|---|
| `ShellTaskExecutor` | `batch-worker-core/.../executor/shell/` | ~300 行 |
| `SqlTaskExecutor` | `batch-worker-core/.../executor/sql/` | ~400 行 |
| `StoredProcTaskExecutor` | 同上 | ~300 行 |
| `HttpTaskExecutor` | 同上 | ~250 行 |

放在 `batch-worker-core` 而不是新 module,理由:
- 所有 worker(import/export/...)启动时都会加载 core → 这 4 个 builtin 自动可用
- 用户不需要为了"跑个 shell 任务"开新 worker 进程

**安全 / 隔离硬约束**:见 [roadmap §P0 Phase 3](../architecture/p0-p1-p2-roadmap.md#phase-3通用执行能力-4-件套-builtin)。

---

### Phase 3:现有 4 worker 改造为 SPI 实现(3 周)

把每个 worker module 现有的 `XxxStepExecutionAdapter`(Pipeline 形态)**包装**为 `BatchTaskExecutor`,而不是替换:

```java
// batch-worker-import 内新加
@Component
@RequiredArgsConstructor
public class ImportTaskExecutor implements BatchTaskExecutor {
  private final ImportStepExecutionAdapter delegate;  // 现有 pipeline adapter

  @Override public String taskType() { return "import"; }

  @Override public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.DISK, ResourceKind.DB),
        false, true, Duration.ofMinutes(30));
  }

  @Override public TaskResult execute(TaskContext ctx) {
    StepExecutionRequest req = ctx.toStepExecutionRequest();
    StepExecutionResponse resp = delegate.execute(req);
    return resp.success() ? TaskResult.ok(resp.outputs()) : TaskResult.fail(resp.message());
  }
}
```

这样的好处:
- **Pipeline 的现有契约不动**(`StepExecutionAdapter` 仍是它的入口)
- 同时 4 类业务任务**可通过 SPI registry 被发现 + 路由**(给 orchestrator 一个统一接口看 worker 支持哪些 taskType)
- 不需要把 `AbstractPipelineStepExecutionAdapter` 拆,迁移成本可控

**关键约束**:`@Primary` 仍属于现有 Pipeline adapter,新加的 `*TaskExecutor` 只走 registry 路径,**不进 Spring main bean lookup**(避免双重注入)。

---

### Phase 4:示范第三方扩展(1 周)

写一个**外部 jar**(放 `examples/` 或新 repo)演示:
- 不引 `batch-worker-core`,只 depend `batch-common.spi.task`
- 用 `META-INF/services/com.example.batch.common.spi.task.BatchTaskExecutor` 注册
- 放到 worker classpath → 启动期 ServiceLoader 自动加载
- 业务方可以照这个模板写

**示范任务**:`SftpPushTaskExecutor`(SFTP 推文件,常见但内置不做)。

---

### Phase 5:Worker 打包多任务支持(1 周)

把"worker 进程 = 1 个 module"放宽为"worker 进程 = 一组 taskType":

```yaml
# application.yml
batch.worker.enabled-task-types:
  - import
  - shell
  - http
```

启动期 `BatchTaskExecutorRegistry` 按这个白名单过滤,未启用的不注册 → orchestrator 只看到启用的。

效果:同一 worker 进程可同时跑"业务 import + 通用 shell + HTTP 探针"三类任务,简化部署。

---

## 5. 跟 orchestrator / DB 的衔接(不改 schema)

### 5.1 `job_definition.job_type` 不再固定枚举

- **现在**:`JobType` 6 个枚举值,改字段就要发 PR
- **改后**:仍然是字符串字段(DB 不变),但合法值由 `BatchTaskExecutorRegistry.registeredTypes()` **运行期决定**
- 控制台新建 job 时:下拉框从 `/api/console/meta/task-types`(新增端点)动态拉所有注册的 taskType
- 验证由 worker 启动注册兜底(job 创建时只校验 type 非空,executor 不存在的运行时 fail-fast + 友好错误)

### 5.2 `worker_route` 不变

- 现有按 `worker_type` 路由(等价于 module 级)→ 改后增加按 `task_type` 路由维度
- 多 taskType worker 进程注册多条 worker_route 行(每行一种 taskType)
- 路由策略不变(优先级 / capability 匹配 / quota 限制)

### 5.3 Orchestrator 派发不变

`KafkaConsumerConfiguration` + `TaskDispatchMessage` 不动;消息体的 `taskType` 字段直接对应 SPI 的 `taskType()`,worker 收到后:

1. Pipeline 任务(import/export/...)→ 路由到 `XxxStepExecutionAdapter`(`@Primary` 拦截)
2. 原子任务(shell/sql/...)→ Pipeline `@Primary` 不匹配 → fallback 到 `DefaultStepExecutionAdapter` → 查 registry → 跑 `BatchTaskExecutor`

---

## 6. 风险 + 不做的(给边界)

### 风险

| 风险 | 缓解 |
|---|---|
| 双路抽象造成"什么是 Pipeline 什么是 Task"模糊 | 文档明确:多 stage / 文件生命周期 = Pipeline;单步无生命周期 = Atomic。中间形态不存在,有就硬归一类 |
| 第三方 jar 引错 batch-common 版本 → ServiceLoader 加载 ClassCastException | `BatchTaskExecutorRegistry` 启动期校验 classloader 一致性 + 日志显式提示 |
| Builtin Shell 被滥用执行 `rm -rf` | 安全硬约束(白名单 + 隔离 + 审计)见 [roadmap §P0 Phase 3](../architecture/p0-p1-p2-roadmap.md) |
| Spring bean + ServiceLoader 重复注册 | Registry 启动期 fail-fast,清晰报错 |

### 不做的

- ❌ **不重写** `AbstractPipelineStepExecutionAdapter` —— 它服务的 4 个 worker 都在跑生产,改造成本远大于收益
- ❌ **不动** `JobType` 枚举的 6 个值 —— 兼容性,只是不再当合法值集合的唯一来源
- ❌ **不提供** 跨 BatchTaskExecutor 的事务 / 共享上下文 —— 跨任务编排走 workflow DAG,SPI 内不重复造
- ❌ **不允许** SPI 实现读写 DB / Kafka / outbox 直接的 infrastructure bean —— 通过 `TaskContext` 受控注入(为安全 + 测试性)

---

## 7. 决策待办(开做前确认)

| 决策 | 选项 | 推荐 |
|---|---|---|
| SPI 包路径 | `batch-common.spi.task` / `batch-worker-core.spi` | **`batch-common.spi.task`**(第三方 jar 只 depend common) |
| Spring bean 注册 + ServiceLoader 哪个先 | Spring 优先 / SL 优先 | **Spring 优先**(framework-managed 实现先,SL 是兜底) |
| TaskContext 是否暴露 ApplicationContext | 暴露 / 不暴露 | **不暴露**(强制走显式注入,防滥用) |
| ShellTaskExecutor 默认是否启用 | 默认开 / 默认关 | **默认关**(`batch.worker.executors.shell.enabled=false`,业务方按需开 + 审批 + 配 cgroup) |
| 兼容旧 `JobType` 枚举 | 保留 / 弃用 | **保留**(改为 builtin Pipeline 任务类型的展示别名,新加类型不进枚举) |

---

## 8. 后续 PR 拆分

| PR | 内容 | 行数估计 |
|---|---|---|
| PR-1 | Phase 1:SPI 契约 + Registry + DefaultStepExecutionAdapter 改路由 + 单测 + ArchUnit 守护 | ~800 行 |
| PR-2 | Phase 2.1:`ShellTaskExecutor` + 安全隔离 + 集成测 | ~600 行 |
| PR-3 | Phase 2.2:`SqlTaskExecutor` + DDL 白名单 + IT | ~700 行 |
| PR-4 | Phase 2.3:`StoredProcTaskExecutor` + IN/OUT + IT | ~500 行 |
| PR-5 | Phase 2.4:`HttpTaskExecutor` + retry + IT | ~450 行 |
| PR-6 | Phase 3.1:`ImportTaskExecutor` 包装 + 注册 | ~200 行 |
| PR-7-9 | Phase 3.2-3.4:export / process / dispatch 各自包装 | 每个 ~150 行 |
| PR-10 | Phase 4:示范 `SftpPushTaskExecutor` 独立 jar + 文档 | ~400 行 |
| PR-11 | Phase 5:多 taskType worker 打包 + `enabled-task-types` 配置 | ~300 行 |

**总:11 个 PR,约 4500 行净增,8 周完成**。

---

## 9. 验证 / 上线门槛

每个 PR 必须满足:

- [ ] Spotless / PMD 全过
- [ ] 单测覆盖新增类 ≥ 80%
- [ ] IT 跑通(Phase 2 起每个 builtin 必须有 IT,用 Testcontainers)
- [ ] ArchUnit 守护通过(SPI 实现不依赖禁用包)
- [ ] 文档:`docs/design/builtin-task-executors.md` 同步加新 builtin 用法
- [ ] 性能基线:新 executor 单实例吞吐 ≥ pipeline 任务的 50%(builtin)/ ≥ 100 ops/s(简单任务)

---

## 10. 关联文档

- 路线全景:[p0-p1-p2-roadmap.md](../architecture/p0-p1-p2-roadmap.md)
- 缺陷盘点(P0 对应缺陷 1+2):[deficiencies-2026-05-30.md](../architecture/deficiencies-2026-05-30.md)
- 现有 Pipeline 设计:[file-pipeline-design.md](file-pipeline-design.md)
- Pipeline vs Workflow 边界:[pipeline-vs-workflow-definition.md](pipeline-vs-workflow-definition.md)
- 后续 builtin 用法手册(随 PR-2 落地):`docs/design/builtin-task-executors.md`
