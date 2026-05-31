# ADR-036 · batch-worker-sdk 五大业务模板抽象类(Atomic / Import / Export / Process / Dispatch)

- **Status**: Proposed(2026-05-31)
- **Date**: 2026-05-31
- **Related**: ADR-035 租户自托管 Worker SDK(本 ADR 是 §SDK API 表面的细化)/ ADR-029 dedicated atomic worker / `docs/design/task-spi-design.md`
- **Refines**: ADR-035 §6(SDK 黑盒边界)+ §决策(SDK 是 Java 友好封装)
- **Plan**: 见本 ADR §实施分阶段

## 背景

ADR-035 把租户自托管 worker 的 SDK 入口固定为 `SdkTaskHandler`:

```java
public interface SdkTaskHandler {
  String taskType();
  SdkTaskResult execute(SdkTaskContext ctx);
  default void cancel(String taskId) {}
}
```

裸接口的问题:

1. **样板重复**:每个 handler 都自己 try/catch 包 `SdkTaskResult.fail(e)`、自己手写 retry/backoff、自己写进度上报
2. **执行序不一致**:有人 validate 在 execute 头部、有人在外面预校验,出错时机不同导致 fail 信号失真
3. **租户写法发散**:同样是"DB 查 → 算 → 写回"的业务,十个租户写出十种代码骨架,平台帮排查问题成本高
4. **跟平台内建 worker 概念断层**:平台有 Import/Export/Process/Dispatch 四类 pipeline worker + Atomic(`batch-worker-atomic`)单 shot worker,**5 种业务 shape** 是平台域内已稳定的划分;租户 SDK 这边没有对应的"形状"概念,迁移路径不直观

## 决策

在 `SdkTaskHandler` 之上,提供 **5 个抽象基类**对应 5 种业务 shape,每个用 **template method 模式**(`final execute` + protected 钩子)锁住执行序,把租户业务代码限制在"填钩子"层。租户**任选其一** extends,跳过裸接口的样板。

### 选 abstract class 不选 interface default 的理由

| 维度 | interface + default | abstract class |
|---|---|---|
| 锁住执行序(`validate → before → doExec → after → cleanup`)| ✗ default 可被覆盖,租户能改序 | ✓ `final execute()` + abstract 钩子 |
| 提供共享基础设施(retry / progress / metrics)| △ 只能静态工具 + 多接口组合,API 散 | ✓ `protected` 实例方法直接给子类调 |
| 跟 atomic 侧 `AbstractBatchTaskExecutor`(#171)对齐 | — | ✓ 架构一致,平台 / SDK 双侧统一 |
| 加新钩子不破现有实现 | ✗ 加 default 全实现强制重看 | ✓ 加 `protected` hook 默认 no-op |
| 多继承(租户已 extends 自家基类)| ✓ | ✗ — 但 SDK handler 一般是 leaf class,实际无冲突 |

**抽象类胜出**。仍然 `implements SdkTaskHandler`(协议层不动),只是给租户一个更友好的写法层。

## 层次结构

```
SdkTaskHandler                                  ← ADR-035 协议契约,不动
   ▲
   │ implements
   │
SdkAbstractTaskHandler                          ← 共同基类:template 序 + 共享工具
   ▲
   │ extends
   ├── SdkAbstractAtomicHandler<R>              单次原子调用
   ├── SdkAbstractImportHandler<R>              external → tenant
   ├── SdkAbstractExportHandler<R>              tenant → external (file)
   ├── SdkAbstractProcessHandler<I,O>           tenant → tenant (transform)
   └── SdkAbstractDispatchHandler<R>            tenant → external (push)
```

### 共同基类 `SdkAbstractTaskHandler`

锁定模板序:

```java
@Slf4j
public abstract class SdkAbstractTaskHandler implements SdkTaskHandler {

  @Override
  public final SdkTaskResult execute(SdkTaskContext ctx) {
    boolean started = false;
    try {
      validate(ctx);
      before(ctx);
      started = true;
      SdkTaskResult r = doExecute(ctx);
      after(ctx, r);
      return r == null ? SdkTaskResult.fail("handler returned null") : r;
    } catch (Throwable t) {
      log.error("SDK handler {} failed (taskType={}, taskId={})",
          getClass().getSimpleName(), taskType(), ctx == null ? null : ctx.taskId(), t);
      return SdkTaskResult.fail(t);
    } finally {
      if (started) {
        try { cleanup(ctx); } catch (Throwable c) { log.warn("cleanup failed: {}", c.getMessage()); }
      }
    }
  }

  protected void validate(SdkTaskContext ctx) {}
  protected void before(SdkTaskContext ctx) {}
  protected abstract SdkTaskResult doExecute(SdkTaskContext ctx);
  protected void after(SdkTaskContext ctx, SdkTaskResult result) {}
  protected void cleanup(SdkTaskContext ctx) {}

  // 共享基础设施(下方共享工具节)
  protected SdkRetryPolicy retryPolicy() { return SdkRetryPolicy.fixed(3, Duration.ofSeconds(2)); }
  protected void reportProgress(SdkTaskContext ctx, int processed, int total) { ... }
}
```

### 5 个业务 shape 子类

#### 1. `SdkAbstractAtomicHandler<R>` — 单次原子调用

```java
public abstract class SdkAbstractAtomicHandler<R> extends SdkAbstractTaskHandler {

  /** 子类实现单次原子调用 — 不用拼 SdkTaskResult,异常自动转 fail。 */
  protected abstract R doInvoke(SdkTaskContext ctx) throws Exception;

  /** 可选:把 doInvoke 返回值映射到 SdkTaskResult.output(默认 {"result": r})。 */
  protected Map<String, Object> asOutput(R result) {
    return result == null ? Map.of() : Map.of("result", result);
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    try {
      return SdkTaskResult.ok("invoked", asOutput(doInvoke(ctx)));
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }
}
```

**适用**:租户在自家 DB 跑 SQL DML / 调外部 HTTP / 跑 `ProcessBuilder` / 纯 Java 计算 — 短时、单调用、无 stage。对照平台 `batch-worker-atomic`(shell/sql/stored-proc/http)由租户在 SDK 自托管侧实现。

#### 2. `SdkAbstractImportHandler<R>` — external → tenant

```java
public abstract class SdkAbstractImportHandler<R> extends SdkAbstractTaskHandler {
  protected abstract void openSource(SdkTaskContext ctx);
  protected abstract Iterator<R> readRows(SdkTaskContext ctx);
  protected abstract void loadBatch(SdkTaskContext ctx, List<R> batch);
  protected int batchSize() { return 1000; }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    openSource(ctx);
    SdkRowResult counts = new SdkRowResult();
    List<R> buf = new ArrayList<>(batchSize());
    Iterator<R> it = readRows(ctx);
    while (it.hasNext()) {
      buf.add(it.next());
      if (buf.size() >= batchSize()) { flush(ctx, buf, counts); }
    }
    if (!buf.isEmpty()) flush(ctx, buf, counts);
    return SdkTaskResult.ok("imported " + counts.success(), counts.toOutput());
  }
  private void flush(SdkTaskContext ctx, List<R> buf, SdkRowResult counts) { ... }
}
```

**适用**:SFTP / S3 / HTTP 拉文件 → 解析 → 批量写租户自家 staging table。

#### 3. `SdkAbstractExportHandler<R>` — tenant → external

```java
public abstract class SdkAbstractExportHandler<R> extends SdkAbstractTaskHandler {
  protected abstract void openSink(SdkTaskContext ctx);
  protected abstract String buildQuery(SdkTaskContext ctx);
  protected abstract Iterator<R> streamRows(SdkTaskContext ctx, String query);
  protected abstract void formatRow(SdkTaskContext ctx, R row);
  protected abstract SdkTaskResult writeOut(SdkTaskContext ctx, SdkRowResult counts);

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    openSink(ctx);
    String q = buildQuery(ctx);
    SdkRowResult counts = new SdkRowResult();
    Iterator<R> it = streamRows(ctx, q);
    while (it.hasNext()) { formatRow(ctx, it.next()); counts.incSuccess(); }
    return writeOut(ctx, counts);
  }
}
```

**适用**:租户表 → S3 Parquet / SFTP CSV / 报表文件。

#### 4. `SdkAbstractProcessHandler<I,O>` — tenant → tenant transform

```java
public abstract class SdkAbstractProcessHandler<I, O> extends SdkAbstractTaskHandler {
  protected abstract Iterator<I> selectInput(SdkTaskContext ctx);
  protected abstract O transform(SdkTaskContext ctx, I input);
  protected abstract void upsert(SdkTaskContext ctx, List<O> batch);
  protected int batchSize() { return 500; }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    SdkRowResult counts = new SdkRowResult();
    List<O> buf = new ArrayList<>(batchSize());
    Iterator<I> it = selectInput(ctx);
    while (it.hasNext()) {
      O out = transform(ctx, it.next());
      if (out != null) buf.add(out);
      counts.incSuccess();
      if (buf.size() >= batchSize()) { upsert(ctx, buf); buf.clear(); }
    }
    if (!buf.isEmpty()) upsert(ctx, buf);
    return SdkTaskResult.ok("processed " + counts.success(), counts.toOutput());
  }
}
```

**适用**:租户表 → 业务计算(报表预聚合 / 维表 join / 业务规则推导)→ 写回租户表。

#### 5. `SdkAbstractDispatchHandler<R>` — tenant → external push

```java
public abstract class SdkAbstractDispatchHandler<R> extends SdkAbstractTaskHandler {
  protected abstract List<R> selectPayload(SdkTaskContext ctx);
  protected abstract Object buildRequest(SdkTaskContext ctx, R item);
  protected abstract Object push(SdkTaskContext ctx, Object request);
  protected void onResponse(SdkTaskContext ctx, R item, Object response) {}

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    SdkRowResult counts = new SdkRowResult();
    for (R item : selectPayload(ctx)) {
      try {
        Object req = buildRequest(ctx, item);
        Object resp = push(ctx, req);
        onResponse(ctx, item, resp);
        counts.incSuccess();
      } catch (Exception e) {
        counts.incFailed();
        log.warn("dispatch item failed: {}", e.getMessage());
      }
    }
    return SdkTaskResult.ok("dispatched " + counts.success() + "/" + counts.total(), counts.toOutput());
  }
}
```

**适用**:租户表 → 推外部 HTTP / SFTP / 第三方 API。

## 共享工具(`SdkAbstractTaskHandler` 之上 utilities)

| 工具 | 作用 | 默认 |
|---|---|---|
| `SdkRetryPolicy` | `fixed(n, delay)` / `exponential(n, base, max)`,基类 `withRetry(policy, op)` 调用 | fixed(3, 2s) |
| `SdkProgressReporter` | 中间进度写 `runtimeAttributes`,REPORT 时回带进度元数据 | 长任务(Import/Export/Process)建议手动调用 |
| `SdkRowResult` | success/skipped/failed/reject 计数 → 写 `SdkTaskResult.output.{success,failed,...}` | 4 个长任务模板默认用 |
| `SdkBatchedProcessor<T>` | 配 batchSize + flush 回调,自动按批 commit,跨 stage 内存安全 | Process/Import 默认用 |

工具都 `protected` 暴露到基类,租户在子类业务方法里直接调。

## 与平台 pipeline 的边界(再次明确)

| 平台路径 | SDK 抽象类提供吗? |
|---|---|
| `file_record` / `pipeline_instance` / `pipeline_step_run` 写入 | ❌ 不提供 — 平台 orchestrator 状态主机职责(ADR-035 §6 路径 3 已禁) |
| `batch_day` 追溯 / 行级 reject 表 / pipeline retry 状态机 | ❌ 不提供 — 租户进程没这套表,SDK 不下放 |
| Stage-级 plugin SPI(`ImportLoadPlugin` 等) | ❌ 不提供 — 那是部署期 jar 形态(ADR-035 §6 路径 2),与 SDK 自托管平行 |
| 进度上报字段进入平台 task instance metadata | ✅ 通过 `SdkProgressReporter` 写 `runtimeAttributes`,REPORT 透传 |
| 结果计数(success/failed/skipped/reject)进 REPORT output | ✅ 通过 `SdkRowResult` 写 `SdkTaskResult.output` |

SDK 抽象类是**纯代码模板** + **运行时基础设施**,**不是**平台 pipeline 状态机的 SDK 化(那条路被 ADR-035 §6 显式否决)。

## 命名 / 规范

- 类名固定 `SdkAbstract{Shape}Handler`,与 atomic 侧 `AbstractBatchTaskExecutor` 一致(都是 abstract + template)
- 钩子用 `protected` + 动词式命名(`openSource` / `readRows` / `loadBatch`),不用 `do` 前缀(`doExecute` 留给基类锁住)
- 类型参数 `R`(row)/ `I,O`(input/output for Process)— 全 SDK 命名一致
- 所有钩子允许抛 `Exception`,基类 `execute` catch-all 转 `SdkTaskResult.fail(throwable)`(对齐 #171 atomic 侧)
- 不允许子类覆盖 `execute(SdkTaskContext)` — `final`

## 实施分阶段

| Phase | 内容 | 状态(2026-05-31)| PR |
|---|---|---|---|
| **P1** | `SdkAbstractTaskHandler` 共同基类 + `SdkRowResult` + `SdkRetryPolicy` + 5 子类骨架 | ✅ 完成 | #185 |
| **P2** | 5 子类各 7-9 单测(模板序 / 异常 / null / cleanup / 分批)40 测试 | ✅ 完成 | #185 |
| **P3** | `examples/sample-tenant-worker` 5 echo sample(各 shape 一个) | ✅ 完成 | #185 |
| **P4** | 本 ADR 实施分阶段 / 验收状态化 + ADR-035 §SDK API 指针 + sample README 文档 | ✅ 完成 | 本 PR |
| **P5(扩)** | **4 类原子操作开箱即用**(`sdk.handler.atomic`:SqlAtomicHandler / StoredProcAtomicHandler / HttpAtomicHandler / ShellAtomicHandler)| ✅ 完成 | #186 |

`SdkProgressReporter` / `SdkBatchedProcessor` 未落地 — 真长任务出现再加(目前 4 长任务模板内联批处理够用)。

## P5 — 4 类原子操作开箱即用实现(sdk.handler.atomic)

ADR-035 §8.B 留口 + 用户决策(2026-05-31):除了 `SdkAbstractAtomicHandler<R>` 抽象模板,SDK 直接给 **4 个现成实现**,租户配置驱动开箱即用,无需从裸接口重写。**零新依赖**(全 JDK 内建:`java.sql` / `java.net.http` / `ProcessBuilder`),SDK jar 仍只依赖 jackson + kafka-clients + slf4j。

| 类 | 配置 record | 安全闸 | 用法 |
|---|---|---|---|
| `SqlAtomicHandler` | `SqlAtomicConfig`(taskType / timeout / maxResultRows / forbidOsCapableRole) | 角色闸 forbidOsCapableRole + statement timeout + 结果行数截断 | `new SqlAtomicHandler(SqlAtomicConfig.defaults("my_sql"), dataSource)` |
| `StoredProcAtomicHandler` | `StoredProcAtomicConfig`(+ allowedSchemas + allowSecurityDefiner) | **三道闸**:forbidOsCapableRole + allowedSchemas 白名单 + allowSecurityDefiner=false(查 `pg_proc.prosecdef` 防借 owner 提权)| `new StoredProcAtomicHandler(cfg, dataSource)` |
| `HttpAtomicHandler` | `HttpAtomicConfig`(blockPrivateIps / blockedHostPatterns / allowedMethods / maxResponseBytes) | SSRF 闸(拦私网/环回/链路本地 IP + host 黑名单)+ method 白名单 + 响应截断 | `new HttpAtomicHandler(HttpAtomicConfig.defaults("my_http"))` |
| `ShellAtomicHandler` | `ShellAtomicConfig`(allowedCommands / timeout / workdir / maxOutputBytes) | allowedCommands 白名单 + **ProcessBuilder 直 execve 不走 shell**(`;` `|` `&&` 字面量无注入)+ timeout destroyForcibly + 临时 workdir 隔离 | `new ShellAtomicHandler(ShellAtomicConfig.defaults("my_shell"))` |

### 安全模型差异(SDK vs 平台 batch-worker-atomic)

平台版多租共享进程,需完整三道闸防租户间越权;SDK 版跑租户**自家进程**(blast radius 在租户侧,ADR-035 §安全模型),保留:
- 资源限制(timeout / row cap / byte cap)— 防租户业务自伤
- SSRF / 角色闸 — 防租户配置错误打到内网 / 用高权限 DB 角色
- 白名单(allowedSchemas / allowedCommands)默认空 = dev 全放行,**prod 必配**

参数全从 `ctx.parameters()` 读(sql / procedureName / url / command 等),配置从构造器注入(DataSource / 白名单 / 限额)。

### SQL / StoredProc 约束完整对齐平台(2026-05-31 补)

四类实现初版后,逐项核对平台 `SqlExecutorProperties` / `StoredProcExecutorProperties`,补齐缺口:

**SqlAtomicHandler** — 补 `maxStatementsPerJob`(默认 50)+ `defaultAutoCommit`(默认 false):
- 之前 `st.execute(sql)` 直执行,PG simple query protocol 允许 `;` 分隔多语句一次跑且**无上限** → 现拆语句 + 超 maxStatementsPerJob 拒绝
- 显式事务(全部成功 commit / 任一失败 rollback)
- `allowedDataSourceBeans` 结构上 N/A(SDK DataSource 构造器注入,无 bean 名覆盖攻击面)

**StoredProcAtomicHandler** — 补 `verifyExecutePrivilege`(opt-in 第 4 闸)+ `maxOutBytesPerParam`(默认 64k)+ `defaultAutoCommit`:
- 第 4 闸:执行前 `has_function_privilege(current_user, proc, 'EXECUTE')` 校验,无权限拒
- OUT 值超 64k 字节截断 + 标记
- 显式事务
- `maxRefCursorRows` N/A(SDK 不支持 REF_CURSOR);`allowedDataSourceBeans` N/A

对齐后 SQL = 角色闸 + 资源限制(语句类型白名单两边都已被 #182 收敛删除);StoredProc = 三道闸 + 第 4 闸 EXECUTE 校验 + 资源限制,与平台一致。

## 验收

- [x] `SdkAbstractTaskHandler` + 5 个 `SdkAbstract*Handler` 类落地
- [x] 各类单测覆盖模板序 / 异常路径 / null result / cleanup 必跑(SDK 121 测试 ✓)
- [x] `examples/sample-tenant-worker` 5 个 echo sample 可 `mvn package` 跑通
- [x] ADR-035 §SDK API + sample-tenant-worker README 引用本 ADR
- [x] 既有 `SdkTaskHandler` 裸实现路径**不破坏**(EchoHandler / SleepHandler 保留)
- [x] P5 — 4 类原子操作开箱即用实现 + 36 单测(sql 8 / proc 10 / http 10 / shell 8)

## 范围边界(不做)

- ❌ **不**强制租户走抽象类 — 裸 `SdkTaskHandler` 实现路径继续支持,抽象类是 opt-in 便利
- ❌ **不**做 stage-级生命周期事件 hook(`onStageStart` / `onStageComplete`)— 那会重新把 pipeline 状态机的形状暴露给租户,撞 ADR-035 §6 红线
- ❌ **不**做"transactional template"(自动跨 stage 事务包裹)— 租户业务 DB 事务边界归租户管,SDK 不假设
- ❌ **不**做 DAG / sub-task 编排 — 那是 workflow 范畴,平台 console 已覆盖,租户业务多 step 用 workflow 串多个 SDK task

## 兼容性 / 风险

| 风险 | 缓解 |
|---|---|
| 抽象类越大,5 个 stage hook 命名分歧难统一 | 每个子类各自 Generic + hooks 独立,基类只放 template 序 + 共享工具,**不强行共享 stage 命名** |
| API 早稳定难调,租户落地后 rename 破坏其代码 | P1 类签名标 `@Beta`(`com.example.batch.sdk.annotation`,自定义注解);P3 sample 跑通 + P4 文档稳定后再去 `@Beta` |
| 租户误以为"SDK 抽象类 = 平台 pipeline" | Javadoc 顶部明确"租户自己的 DB 状态,平台只看终态 + counts";本 ADR §与平台 pipeline 的边界节明示 |
| 5 子类全发布后真业务可能只用 3 个 | 不做未来证明 — 真用不到的子类 P5 评估 deprecate(SemVer minor),不影响发布 |

## 关联文档

- ADR-035 §决策 / §6 路径 3 / §SDK API:本 ADR 是其 SDK API 表面层细化
- ADR-029 dedicated atomic worker:平台侧 `batch-worker-atomic` 4 类内建 executor,**SdkAbstractAtomicHandler 是其 SDK 自托管对应**
- #171 `AbstractBatchTaskExecutor`(atomic 侧):本 ADR 共同基类与之同源,统一 SPI 模板模式
- `docs/design/task-spi-design.md`:SPI 协议设计(`BatchTaskExecutor` 接口),本 ADR 不动协议
