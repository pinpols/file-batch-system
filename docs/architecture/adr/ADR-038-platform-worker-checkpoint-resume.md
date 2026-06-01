# ADR-038 · 平台 Worker 分片级断点续跑与 chunk 位点同事务(Import / Export)

- **Status**: Proposed · 🅿️ 暂缓(2026-06-01 评估为 YAGNI,详见 §评估记录)
- **Date**: 2026-06-01
- **Related**: ADR-035 / ADR-036 / ADR-037(SDK 侧同名能力)/ ADR-020 batch-day-replay
- **Refines**: `batch-worker-core` 阶段执行模型;`batch-worker-import` LOAD 阶段;`batch-worker-export` GENERATE 阶段
- **Plan**: 见本 ADR §实施分阶段(roadmap Phase 4.5,🅿️ 已登记/待评估)

## 评估记录

**2026-06-01 · 结论:🔴 继续挂起(YAGNI),暂不实施。** 触发大数据量崩溃重跑代价的前提在当前代码与数据规模下不成立:

1. **Export GENERATE 已天然安全** —— 分页写的是临时文件,只在 STORE 阶段边界 `.part → copy` 落正式文件,崩溃重跑不产生半成品,本就可重入。
2. **Import LOAD 生产强制幂等** —— `strict-idempotency=true` + `ON CONFLICT DO NOTHING/UPDATE`(多租 `UNIQUE(tenant_id, ...)` 兜底),从第 0 行重跑数据安全,仅浪费 CPU。
3. **无大数据量证据** —— 现有 fixture / load-test 全在 ~5000 行级(chunk 500 / page 1000 / maxStagedRows 10000),无千万/亿行真实场景。
4. **成本 >> 收益** —— 实施需新增位点载体 + archive 镜像 migration + 改穿 RLS 路径的同事务写,远大于"重跑一遍"的代价。

**重启条件**:出现真实的大数据量(≥百万行)+ 高崩溃 / 重派频率证据时再重评。在此之前保持 Proposed,不进 Phase 4.5 实施。

## 范围边界

- 「**平台 worker 在重派 / 崩溃后从已处理位点续跑**」√
- 「**chunk 业务写与位点更新同事务**」√
- 「**worker 直接写 `job_instance` / `pipeline_instance` 状态**」✗ —— 维持 CLAUDE.md 架构硬约束:Orchestrator 是唯一状态主机,worker 仍只 HTTP REPORT;本 ADR 的"位点"持久化落在 **worker 自己可写的 pipeline 内部记录 / file_record**,不碰 orchestrator 状态机
- 「**worker 内并行**」✗ —— 已有 orchestrator 层 `lineNo % partitionCount` 逻辑分区(ParseStep),不重复造 worker 内并行
- 「**orchestrator 主动取消信号**」✗ —— 现有超时 → `Thread.interrupt()` → watchdog(`cancelGraceSeconds`)已足够,不在本 ADR 范围

❌ 不做:不引入 worker 内线程池并行、不改 worker→orchestrator 的 REPORT 异步语义(HTTP + report-outbox 兜底是设计如此)、不做单条记录级回滚。

## 背景

平台 pipeline worker 当前是**全量重跑**模型:

- **Import LOAD**(`LoadStep.executeStreaming`):`BufferedReader` 逐行读 → 攒满 `chunkSize` → `flushChunk()`。流式、内存有界,但:
  - "已加载到第几行"只存在内存变量 `loadedCount` 里,**不落库**;
  - 每个 chunk 是**独立事务**(plugin 内 `loadChunk` 各自提交),chunk N 提交后 chunk N+1 失败,N 不回滚;
  - 任务超时 / 进程崩 / lease 被回收后重派,下一个 worker 从**第 0 行**重头跑,已写入的数据要么重复、要么靠业务自己去重。
- **Export GENERATE**(`AbstractExportFormat.generatePaged`):cursor 分页 `while(true)` 跟 `nextCursor` 写文件。同样**不存位点**,崩了从 `cursor=null` 重头分页。
- **阶段层**(`AbstractStageExecutor.runStageLoop`):阶段顺序执行,首个失败即停;重试从 INITIAL 阶段重来,不从"上次成功的阶段"续。

对**大数据量**任务(千万行导入 / 大结果集导出),崩溃重跑的代价最高 —— 这是平台 worker 当前最值得补的洞。其余能力已具备(流式、超时取消、跨 worker 分区),不在本 ADR 处理。

> 注:本 ADR 与 ADR-037(SDK 侧)是**同一类能力在两侧的独立落地**。平台 worker 由平台代码实现位点持久化(可写 pipeline 内部表);SDK 侧由租户 business 实现。两者协议不强制共享,但设计理念一致。

## 决策

给 Import LOAD 与 Export GENERATE 增加**位点持久化 + 续跑**,并把 **chunk 业务写与位点更新合到同一事务**。

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

### 决策二:chunk 写与位点同事务(强约束)

每个 chunk(Import)或每页(Export)提交时,**同一事务内**完成:

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

**为什么强约束**:这是续跑可靠的根。若业务数据提交而位点没推进,重跑会重复处理该 chunk;反之会漏。Export 写文件是非事务资源,无法和 DB 位点严格同事务 —— 对 Export 采用**"先写临时文件分片 + 位点确认"**的补偿策略(见决策三),不强行 DB 事务包文件 IO。

### 决策三:Import / Export 续跑差异

| | Import LOAD | Export GENERATE |
|---|---|---|
| 位点 | 已处理**行号**(staging file 是 append-only,行号稳定可定位) | 序列化的 **cursor**(plugin 的 `nextCursor`) |
| 续跑 | 重读 staging file,`skip` 到 `行号` 后继续 | 从存的 cursor 调 `loadDetailPage(cursor)` 续页 |
| 同事务 | ✓ chunk 业务写 + 行号位点,DB 同事务 | △ 文件写非事务;按"分片临时文件 + 已确认页位点"补偿,续跑时丢弃未确认的尾部重写 |
| 幂等前提 | plugin `loadChunk` 需幂等或目标表有唯一键(多租已强制 `UNIQUE(tenant_id, ...)`)兜底重复 | 续跑重写从 cursor 起,临时文件覆盖,最终 STORE 阶段才落正式文件 |

Import 的幂等天然有多租唯一约束兜底;Export 因为只在 STORE 阶段才产出正式文件,GENERATE 续跑重写临时分片是安全的。

### 决策四:阶段级续跑(轻量)

`AbstractStageExecutor.runStageLoop` 重派时,先读 pipeline_instance 已完成到的阶段,从**下一个未完成阶段**起跑(已完成阶段幂等跳过),而非每次从 INITIAL。位点续跑(决策一~三)解决"阶段内部"续,本决策解决"阶段之间"续,二者叠加。

## 实施分阶段

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P1 | `ProcessingPosition` 模型 + `positionStore`(load/advance/markCompleted),持久化载体选型(file_record 扩展列 vs `pipeline_progress` 内部表)+ migration(含 archive 镜像) | — |
| P2 | Import LOAD:`flushChunkWithPosition` 同事务 + 启动 skip 到行号续跑 + 完成标记 | P1 |
| P3 | Export GENERATE:cursor 位点 + 临时分片补偿续跑 | P1 |
| P4 | 阶段级续跑(`runStageLoop` 从未完成阶段起) | P1 |
| P5 | 测试:崩溃重派续跑(行号 / cursor)、chunk 失败位点不前进、幂等重复 chunk、阶段跳过;文档 howto + 同事务反例 | P1~P4 |

## 后果

**收益**:大数据量 Import/Export 崩溃 / 重派后从断点续,不重头跑;chunk 位点同事务保证不重不漏;阶段级续跑减少重复工作。

**成本**:新增位点持久化载体(表 / 列 + archive 镜像,受 CLAUDE.md "archive 冷表对齐" 约束);`flushChunk` 事务边界变化需回归;Export 文件续跑用补偿而非严格事务,有"重写未确认尾部"的少量重复写。

**不破坏**:Orchestrator 状态主机地位不变(位点落 worker 内部记录,不碰状态机);流式 / 超时取消 / 跨 worker 分区 不动;worker→orchestrator REPORT 异步语义不变。
