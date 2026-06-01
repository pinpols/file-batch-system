# ADR-037 · batch-worker-sdk 分片级断点续跑与可靠提交协议(checkpoint / commit / 协作 cancel / 流式分片模板)

- **Status**: Accepted(2026-06-01)— 全量 P1~P5,折入 roadmap Phase 5 支柱 A 之后(接口冻结再上 parallel-stream)
- **Date**: 2026-06-01
- **Related**: ADR-035 租户自托管 Worker SDK / ADR-036 SDK 五大业务模板 / ADR-020 batch-day-replay / ADR-038(平台侧同类能力,仍 🅿️ 挂起)
- **Refines**: ADR-035 §SDK API 表面;ADR-036(本 ADR 给模板补"可分片 / 可续跑"能力)
- **Plan**: 见本 ADR §实施分阶段;P1~P3 跟在支柱 A 接口冻结后,P4 parallel-stream 接口稳定后上

## 评估记录

**2026-06-01 · 初评 🔴 YAGNI → 复核后 🟢 Accepted,全量实施。**

初次基于仓库静态扫描(唯一租户 worker 是 `examples/sample-tenant-worker` 的 echo/sleep 示范,fixture 全在 ~5000 行级)判为 YAGNI。**该论据被业务负责人推翻**:SDK 侧已有 / 即将有**真实租户自托管 worker**,其 Import/Export/Process 任务实测**既 >10min 又 ≥百万行**——示范 worker 和测试 fixture 不反映真实租户任务画像。重跑代价(浪费十分钟级 CPU + 大数据量内存/重复写压力)已远超实现成本,roadmap 决策 #12 的"真实租户 + >10min"门槛已达到。

**实施决定**:全量 P1~P5。P1(SdkCheckpoint 协议)+ P2(`ctx.commit` 同事务)+ P3(取消落进 commit 安全点)跟在 Phase 5 支柱 A 接口冻结后启动;P4(`SdkAbstractParallelStreamHandler` 分片模板)待支柱 A typed handler 接口稳定再上;P5 文档 + 测试收口。

**注意**:平台侧 ADR-038 维持 🅿️ 挂起 —— 平台 Import/Export 已有 STORE 边界临时文件 + 生产强制幂等兜底,且其任务画像未被推翻;本翻案仅针对 SDK / 租户侧。

## 范围边界

- 「**SDK 在租户进程内提供续跑 / 提交原语 + 模板**」√
- 「**平台替租户托管断点状态 / 业务控制表**」✗ —— 断点的**持久化由租户 business 实现**,SDK 只定义协议与默认实现示例
- 「**worker 直连业务库由平台编排**」✗ —— 维持 ADR-035 §7 边界,平台仍只走 `DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`,不感知租户库
- 「**分布式协调 / 选主**」✗ —— 不引入额外协调组件,派发与心跳仍走现有 Kafka push + HTTP claim/heartbeat

❌ 不做:不提供跨 worker 的全局事务、不提供平台侧断点存储、不引入第二套协调总线、不抽象单条记录级 Savepoint 回滚(租户需要时自用 JDBC 原生能力)。

## 背景

ADR-035 / ADR-036 之后,SDK 的 handler 是"一次 `execute(ctx)` 跑完即定终态"的模型:

```java
public interface SdkTaskHandler {
  String taskType();
  SdkTaskResult execute(SdkTaskContext ctx);
  default void cancel(String taskId) {}
}
```

对**长跑、大数据量**任务,这个模型有三个硬缺口:

1. **无断点**:任务跑到一半进程崩 / lease 被回收 / 被取消,重派后**整个 task 从头重跑**,已处理的数据要么重复、要么靠业务自己去重,代价高且易错。
2. **提交与进度不一致**:业务数据 commit、进度上报、"我跑到哪了"这三件事各写各的,崩在中间会出现"业务提交了但进度没更新"或反之,重跑判断失真。
3. **取消不安全**:`cancel` 只能让 handler 自己感知(ADR Phase 4 的 `CancellationSignal`),但没有约定"停在哪个安全点",容易停在半个批次中间留下脏数据。

此外,ADR-036 五模板里没有"**可分片**"形状 —— 一个大文件 / 大结果集只能单线程顺序跑,无法在 worker 内并行,也无法分片各自续跑。

## 决策

在 SDK 增加**一组续跑原语** + **一个可分片流式模板**,全部建立在"**断点是数据自身的主键 / 范围,不是 offset**"这一前提上。

### 决策一:断点续跑协议 `SdkCheckpoint`

断点的**语义由 SDK 定义,持久化由 business 实现**。SDK 不规定存到哪(可以是租户自己的控制表、KV、对象存储),只要求实现读 / 写两个动作:

```java
public interface SdkCheckpoint {
  /** 启动时读回上次断点;首次运行返回 empty。 */
  Optional<SdkCheckpointState> load(String taskId);

  /** 保存断点(必须与业务数据同事务,见决策二)。 */
  void save(String taskId, SdkCheckpointState state);
}

public record SdkCheckpointState(
    Map<String, Object> breakPosition,  // 断点:已处理到的记录主键 / 范围键
    long succeedCount,
    long failCount,
    boolean completed) {}
```

`SdkTaskContext` 暴露 `checkpoint()` 入口。续跑模板在 `execute` 开头:

```
state = ctx.checkpoint().load(taskId)
if (state.completed) return SdkTaskResult.success();  // 幂等:已完成直接跳过
resumeFrom = state.breakPosition;                     // 从断点续,不是从头
succeed = state.succeedCount; fail = state.failCount;  // 恢复计数,进度不归零
```

### 决策二:三合一可靠提交 `ctx.commit(breakPosition)`(强约束)

每个业务批次结束时,**一次调用**原子完成三件事:

1. 提交业务数据
2. 保存断点(`SdkCheckpointState`)
3. 上报进度(限流,见下)

```java
public interface SdkTaskContext {
  // ... 现有
  /**
   * 提交一个业务批次。
   * 强约束:实现必须保证「业务数据提交」与「断点/计数保存」在同一个事务边界内,
   * 二者要么都成功、要么都回滚 —— 否则崩溃后断点与业务数据会撕裂,续跑不可靠。
   */
  void commit(Map<String, Object> breakPosition);
}
```

**为什么是强约束**:这是续跑可靠性的根。如果业务数据提交了而断点没更新,重跑会重复处理;反之会丢数据。SDK 提供的默认实现(针对 JDBC business)在**同一个 `Connection`**里先 `update 断点行(不单独 commit)` 再 `connection.commit()`,把两者合成一个事务。租户用别的存储时,必须自行保证同等原子性,文档与 code review 卡这一点。

进度上报搭 `commit` 的车,按**批次计数取模**限流,避免每批都打满网络:

```
commitCounter++
if (commitCounter % reportIntervalBatches == 0) ctx.reportProgress(succeed, fail, breakPosition)
```

`reportIntervalBatches` / 批次大小由 `SdkTaskContext` 参数提供,租户可调;也允许 business 关掉自动上报、自己控制(`selfReport` 开关)。

### 决策三:协作式两阶段取消(织入 commit 点)

复用 ADR Phase 4 的 `CancellationSignal` / `ctx.isCancelled()`,把检查**织进 `commit`**:每次 `commit` 成功后检查取消标志,命中则在**已提交的安全点**抛 `SdkTaskStoppedException`,模板顶层捕获 → 上报 cancelled 终态。

```
ctx.commit(breakPosition):
   ... 同事务提交业务 + 断点 ...
   if (ctx.isCancelled()) throw new SdkTaskStoppedException(breakPosition);
```

约定:**业务代码不得吞掉 `SdkTaskStoppedException`**(吞了就停不下来);模板的 `final execute` 统一捕获并落 cancelled 终态。取消总是停在两个批次之间的边界,不会留半个批次的脏数据。

### 决策四:可分片流式模板 `SdkAbstractParallelStreamHandler`

作为 ADR-036 的第 6 个模板,给"大文件 / 大结果集"提供 worker 内分片 + 流式 + 各自续跑:

```
SdkAbstractTaskHandler
   └── SdkAbstractParallelStreamHandler<I,O>
         abstract List<SdkPartition> split(SdkTaskContext ctx);   // 幂等切分
         abstract void process(SdkPartition part, SdkTaskContext ctx);  // 单分片执行
         boolean isPartitionable() default true;
```

```java
public record SdkPartition(
    Map<String, Object> startKey,   // 区间起(主键 / 行号 / 排序键)
    Map<String, Object> endKey,     // 区间止
    long estimatedRecords) {}
```

要点(写进模板契约与文档):

- **切分键 = 数据自身范围**(主键 / 排序键 / 行号),不是 offset/limit;与断点同一坐标系,所以分片内能直接用 `commit(breakKey)` 续跑。
- **切分幂等**:`split` 可被重复调用(重派后),实现按"已切分标记"判断,不重复产生分片。
- **流式读**:`process` 内若走 JDBC 游标,必须 `connection.setAutoCommit(false)` + `statement.setFetchSize(N)` 做服务端游标(PostgreSQL 默认会一次性拉全量,关 autoCommit 才真流式),逐行处理,每攒一批 `ctx.commit(breakKey)`。
- **空分片守卫**:`split` 可产出"无数据"分片或 `process` 内短路,直接标该分片完成。
- 分片之间相互独立续跑:某分片崩了只重跑该分片、从它自己的断点续,不影响其它分片。

## 层次结构(与 ADR-036 合并视图)

```
SdkTaskHandler                                   ← ADR-035 协议,不动
   ▲ implements
SdkAbstractTaskHandler                           ← 共同基类:template 序 + commit/checkpoint/cancel 工具
   ▲ extends
   ├── SdkAbstractAtomicHandler<R>               (ADR-036)
   ├── SdkAbstractImportHandler<R>               (ADR-036)
   ├── SdkAbstractExportHandler<R>               (ADR-036)
   ├── SdkAbstractProcessHandler<I,O>            (ADR-036)
   ├── SdkAbstractDispatchHandler<R>             (ADR-036)
   └── SdkAbstractParallelStreamHandler<I,O>     (本 ADR · 决策四)
```

`SdkCheckpoint` / `ctx.commit()` / `ctx.isCancelled()` 是**横切能力**,任何模板(尤其长跑的 Import/Export/Process 与本 ADR 的 Parallel)都能用;短小的 Atomic 一般用不到。

## 实施分阶段

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P1 | `SdkCheckpoint` / `SdkCheckpointState` 接口 + `SdkTaskContext.checkpoint()` + JDBC 默认实现示例 | — |
| P2 | `ctx.commit(breakPosition)` 三合一 + 同事务强约束 + 进度限流 + `selfReport` 开关 | P1 |
| P3 | 取消织入 commit 点(`SdkTaskStoppedException` + 模板顶层捕获) | P2 · 复用 Phase 4 `CancellationSignal` |
| P4 | `SdkAbstractParallelStreamHandler` + `SdkPartition` + 流式 / 幂等切分 / 空分片守卫 | P1~P3 |
| P5 | 文档:续跑 howto + 同事务约束反例 + PG 流式注意事项;模板单测覆盖断点恢复 / 取消安全点 / 切分幂等 | P1~P4 |

## 后果

**收益**:长跑任务可断点续跑(崩溃 / 重派不重复、不丢)、提交与进度强一致、取消停在安全点、大数据量可在 worker 内分片并行流式。

**成本**:断点持久化的正确性(同事务)落在租户实现上,需要文档 + review 把关;`commit` 语义比"一把梭 execute"复杂,Atomic 类任务不必引入。

**不破坏**:协议层 `SdkTaskHandler` 不动;现有五模板不强制改;平台编排边界(ADR-035 §7)不变。
