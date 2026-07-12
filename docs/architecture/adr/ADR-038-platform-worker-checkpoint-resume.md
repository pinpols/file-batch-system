# ADR-038 · 平台 Worker 分片级断点续跑与 chunk 位点补偿式续跑(Import / Export)

> **措辞校正(2026-07,P0 R-1)**:本 ADR 早期在标题、范围边界和决策二里写「chunk 业务写与位点更新**同事务**」。
> 实施确认 Import 业务数据落**租户业务库**、位点落**平台库**,**跨库无 1PC**,该措辞失真。真实语义是
> **业务先 commit → 位点后 advance + 插件幂等** 的**补偿式最终一致**(崩溃窗口重做 ≤1 chunk,由 plugin 幂等约束吸收,
> 不双写)。下文凡出现"同事务"处均以 §决策二「实施修正」为准;不改任何运行行为,只校正文档措辞。

- **Status**: Accepted(2026-06-02 改判,详见 §评估记录)
- **Date**: 2026-06-01(初评) · 2026-06-02(改判)
- **Related**: ADR-035 / ADR-036 / ADR-037(SDK 侧同名能力)/ ADR-020 batch-day-replay
- **Refines**: `batch-worker-core` 阶段执行模型;`batch-worker-import` LOAD 阶段;`batch-worker-export` GENERATE 阶段
- **Plan**: 见本 ADR §实施分阶段(roadmap Phase 4.5,已解锁)

> **实施勘误（2026-07-11）**：P1（位点模型）、P2（Import LOAD）和 P3（Export GENERATE）已交付；
> Import 的真实进程崩溃、lease 回收和同实例续跑已由 `scripts/sim/25-import-stage2e-checkpoint-crash.sh`
> 验证。P4 阶段级续跑未实现，因与 ADR-020 replay 语义重叠而冻结，除非真实负载证明跨阶段重复计算已成为主要成本，
> 否则不启动。运行现状以 `docs/runbook/platform-worker-checkpoint-howto.md` 为准。

## 评估记录

**2026-06-02 · 结论:改判 Accepted,进入 Phase 4.5 实施排队。** 两条重启条件已同时满足:

1. **数据量证据**:Import LOAD 与 Export GENERATE 两条路径都已踩到**百万行级**真实任务,初评时"5000 行级 fixture"的论据失效。
2. **崩溃 / 重派证据**:已出现真实的任务崩溃 + 重派记录(非偶发);第 0 行重跑的累计代价(biz DB 重压 + 幂等检查 + 长时间占用 worker slot)已远超续跑实现成本。

初评(2026-06-01)依据的"成本 >> 收益"假设里,**收益项被低估**了一个量级:
- Import LOAD 即使"数据安全"(`ON CONFLICT` 回退),百万行重跑等于把业务库再来一次 INSERT/UPDATE 风暴,影响业务库 QPS;
- Export GENERATE 临时文件"重入安全"不等于"重跑廉价",大结果集的 cursor 分页 + 文件 IO 在百万行级是分钟级延迟,SLA 已被冲击;
- 续跑实现成本(位点表 + archive 镜像 + 同事务边界改动)只是一次性的,而重跑代价是**按每次崩溃叠加**。

**Phase 4.5 启动条件**:Phase 4(#215–#218 已合)已交付 cancel / timeout / heartbeat details 基础,位点表的同事务持久化没有阻塞依赖。建议按本 ADR §实施分阶段 P1 → P5 顺序推进。

---

**2026-06-01 · 初评结论:继续挂起(YAGNI)** _(历史记录,已被 2026-06-02 改判推翻)_

初评 4 条论据中,第 3 条已被实际数据推翻、第 2 / 第 4 条结论被重新校准:

1. **Export GENERATE 已天然安全** —— 分页写的是临时文件,只在 STORE 阶段边界 `.part → copy` 落正式文件,崩溃重跑不产生半成品,本就可重入。 _(成立,但"重入安全 ≠ 重跑廉价",百万行级 cursor 重头分页是 SLA 风险)_
2. **Import LOAD 生产强制幂等** —— `strict-idempotency=true` + `ON CONFLICT DO NOTHING/UPDATE`,从第 0 行重跑数据安全,仅浪费 CPU。 _(数据安全成立,但"浪费 CPU"在百万行 + 业务库压力下不是"仅"了)_
3. **无大数据量证据** —— 现有 fixture / load-test 全在 ~5000 行级。 _(2026-06-02 推翻:已有百万行真实任务)_
4. **成本 >> 收益** —— 实施需新增位点载体 + archive 镜像 migration + 改穿 RLS 路径的同事务写。 _(成本不变,但每次崩溃的重跑代价 × 实际崩溃频率 > 一次性实施成本)_

## 范围边界

- 「**平台 worker 在重派 / 崩溃后从已处理位点续跑**」√
- 「**chunk 业务写与位点更新补偿式一致**」√ —— 跨库无 1PC:业务先 commit、位点后 advance,崩溃窗口靠插件幂等吸收(详见 §决策二实施修正)。**不是**单事务原子。
- 「**worker 直接写 `job_instance` / `pipeline_instance` 状态**」✗ —— 维持 CLAUDE.md 架构硬约束:Orchestrator 是唯一状态主机,worker 仍只 HTTP REPORT;本 ADR 的"位点"持久化落在 **worker 自己可写的 pipeline 内部记录 / file_record**,不碰 orchestrator 状态机
- 「**worker 内并行**」✗ —— 已有 orchestrator 层 `lineNo % partitionCount` 逻辑分区(ParseStep),不重复造 worker 内并行
- 「**orchestrator 主动取消信号**」✗ —— 现有超时 → `Thread.interrupt()` → watchdog(`cancelGraceSeconds`)已足够,不在本 ADR 范围

❌ 不做:不引入 worker 内线程池并行、不改 worker→orchestrator 的 REPORT 异步语义(HTTP + report-outbox 回退是设计如此)、不做单条记录级回滚。

## 背景

平台 pipeline worker 当前是**全量重跑**模型:

- **Import LOAD**(`LoadStep.executeStreaming`):`BufferedReader` 逐行读 → 攒满 `chunkSize` → `flushChunk()`。流式、内存有界,但:
  - "已加载到第几行"只存在内存变量 `loadedCount` 里,**不写入数据库**;
  - 每个 chunk 是**独立事务**(plugin 内 `loadChunk` 各自提交),chunk N 提交后 chunk N+1 失败,N 不回滚;
  - 任务超时 / 进程崩 / lease 被回收后重派,下一个 worker 从**第 0 行**重头跑,已写入的数据要么重复、要么靠业务自己去重。
- **Export GENERATE**(`AbstractExportFormat.generatePaged`):cursor 分页 `while(true)` 跟 `nextCursor` 写文件。同样**不存位点**,崩了从 `cursor=null` 重头分页。
- **阶段层**(`AbstractStageExecutor.runStageLoop`):阶段顺序执行,首个失败即停;重试从 INITIAL 阶段重来,不从"上次成功的阶段"续。

对**大数据量**任务(千万行导入 / 大结果集导出),崩溃重跑的代价最高 —— 这是平台 worker 当前最值得补的洞。其余能力已具备(流式、超时取消、跨 worker 分区),不在本 ADR 处理。

> 注:本 ADR 与 ADR-037(SDK 侧)是**同一类能力在两侧的独立落地**。平台 worker 由平台代码实现位点持久化(可写 pipeline 内部表);SDK 侧由租户 business 实现。两者协议不强制共享,但设计理念一致。

## 决策

给 Import LOAD 与 Export GENERATE 增加**位点持久化 + 续跑**。原设计意图是把 **chunk 业务写与位点更新合到同一事务**,
但实施确认 Import 跨库无 1PC,已在 §决策二修正为**补偿式一致(业务先 commit → 位点后 advance + 插件幂等)**。

### 决策一:位点模型 `ProcessingPosition`

位点是**数据自身的进度标记**(行号 / cursor),按 pipeline 维度持久化在 worker 可写的内部记录(`file_record` 扩展列或新增 `pipeline_progress` 内部表,二选一,实现时定):

```
ProcessingPosition {
  String  taskKey;          // pipeline_instance / file_record 维度键
  String  stage;            // LOAD / GENERATE
  long    processedCount;   // 已成功处理记录数
  String  positionMarker;   // 续跑位点:Import=行号; Export=序列化 cursor
  boolean completed;        // 该阶段是否已整体完成
}
```

启动 LOAD / GENERATE 阶段时:

```
pos = positionStore.load(taskKey, stage)
if (pos.completed) -> 跳过该阶段(幂等)
resumeMarker  = pos.positionMarker     // Import: 跳过前 N 行; Export: 从该 cursor 续页
processedCount = pos.processedCount     // 计数不归零
```

### 决策二:chunk 写与位点提交顺序(实施修正)

原设计要求每个 chunk(Import)或每页(Export)在**同一事务内**完成业务写和位点推进。实施时确认 Import
业务数据落租户业务库、位点落平台库，无法通过单数据源事务满足该要求；项目明确不引入 XA/JTA。
因此实际约束修正为：

1. 业务数据先提交，位点随后推进，禁止反向排序，避免位点超前造成漏数；
2. 两次提交之间崩溃时，位点最多落后一个 chunk，由插件幂等能力保证重放不重复写；
3. 开启 checkpoint 前强制校验 `ImportLoadPlugin#idempotencyCapability()`，不具备幂等能力时拒跑；
4. 同一平台数据源内的位点更新仍参与调用方事务，但不宣称跨库原子性。

同库部署或未来具备同事务资源时，仍应在一个事务内完成：

1. 业务数据写入(plugin 的 `loadChunk` / 写文件 + 记录已写行)
2. 位点更新(`positionMarker` 推进到本 chunk 末尾,`processedCount += chunk.size()`)

```
@Transactional  // 同一事务边界
flushChunkWithPosition(plugin, chunk, position):
    plugin.loadChunk(ctx, chunk);                 // 业务写
    positionStore.advance(taskKey, stage,
        newMarker, processedCount + chunk.size()); // 位点推进
    // 二者要么都提交、要么都回滚
```

**为什么禁止位点先行**：若位点推进后业务提交失败，重派会跳过未写数据，造成永久漏数。业务先提交而位点未推进只会重做一个 chunk，
可由强制幂等约束安全吸收。Export 文件同样是非事务资源，采用临时文件 fsync、字节位点与 truncate 补偿，不强行用 DB 事务包装文件 IO。

### 决策三:Import / Export 续跑差异

| | Import LOAD | Export GENERATE |
|---|---|---|
| 位点 | 已处理**行号**(staging file 是 append-only,行号稳定可定位) | 序列化的 **cursor**(plugin 的 `nextCursor`) |
| 续跑 | 重读 staging file,`skip` 到 `行号` 后继续 | 从存的 cursor 调 `loadDetailPage(cursor)` 续页 |
| 一致性 | △ 业务写落**租户业务库**、行号位点落**平台库**,跨库无 1PC;业务先 commit → 位点后 advance,崩溃窗口重做 ≤1 chunk,靠插件幂等吸收(见 §决策二) | △ 文件写非事务;按"分片临时文件 + 已确认页位点"补偿,续跑时丢弃未确认的尾部重写 |
| 幂等前提 | plugin `loadChunk` 需幂等或目标表有唯一键(多租已强制 `UNIQUE(tenant_id, ...)`)防重复 | 续跑重写从 cursor 起,临时文件覆盖,最终 STORE 阶段才落正式文件 |

Import 的幂等天然有多租唯一约束回退;Export 因为只在 STORE 阶段才产出正式文件,GENERATE 续跑重写临时分片是安全的。

### 决策四:阶段级续跑(轻量)

`AbstractStageExecutor.runStageLoop` 重派时,先读 pipeline_instance 已完成到的阶段,从**下一个未完成阶段**起跑(已完成阶段幂等跳过),而非每次从 INITIAL。位点续跑(决策一~三)解决"阶段内部"续,本决策解决"阶段之间"续,二者叠加。

## 实施分阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | `ProcessingPosition` + `pipeline_progress`（含 archive 镜像） | ✅ 已完成 |
| P2 | Import LOAD 行号位点、幂等前置校验、完成标记 | ✅ 已完成 |
| P3 | Export GENERATE cursor/字节位点、truncate 补偿续跑 | ✅ 已完成 |
| P4 | 阶段级续跑（从未完成 stage 起跑） | ⏸ 冻结；等待真实重复计算成本证据，且须先对齐 ADR-020 |
| P5 | 单测、Import 真实崩溃重派 sim、运行 howto | ✅ 当前范围已完成；阶段跳过测试随 P4 冻结 |

## 后果

**收益**:大数据量 Import/Export 崩溃 / 重派后从断点续,不重头跑；Import 依靠“业务先提交 + 位点后推进 + 插件强幂等”保证不漏，
Export 依靠临时文件截断补偿保证不重复、不丢行。阶段级续跑尚未交付，不计入当前收益。

**成本**:新增位点持久化载体(表 / 列 + archive 镜像,受 CLAUDE.md "archive 冷表对齐" 约束);`flushChunk` 事务边界变化需回归;Export 文件续跑用补偿而非严格事务,有"重写未确认尾部"的少量重复写。

**不破坏**:Orchestrator 状态主机地位不变(位点落 worker 内部记录,不碰状态机);流式 / 超时取消 / 跨 worker 分区 不动;worker→orchestrator REPORT 异步语义不变。
