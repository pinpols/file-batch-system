# 单文件 / 单 worker 处理"上万个任务"能扛住吗 —— 容量分析

- **日期**: 2026-06-21
- **问题**: 目前单文件、单 worker 处理上万个任务,这个 worker 能扛住吗?
- **依据**: 本文结论全部基于已落地代码 + 真实系统链路实测(非空跑),引用见各节。

## TL;DR

**单 worker 扛"上万"基本是日常,不会被压垮;真正的瓶颈不在 worker 本身,而在它身后的单机 PG 写入 / 控制面串行派发。**

"任务"在本系统有三种含义,答案分档:

| "任务"含义 | 单 worker 扛不扛得住上万 | 真瓶颈 |
|---|---|---|
| 一个 import 文件里的**行 / 记录** | **轻松**(实测扛过 1000 万行) | LOAD 阶段单机 PG 写入 |
| pipeline **分区 partition** | **生成不出上万**(硬上限 256);单文件拆分区反而更慢 | — |
| 派发的 **job_task**(海量小任务) | **不挂**(并发 + 背压),但**消化慢** | 控制面 launch/dispatch 串行 ~20–62 jobs/s |

---

## 一、"任务" = 文件里的行 / 记录(import 上万行)→ 轻松扛,百万千万都扛过

机制是**全程流式、常数内存**,不是整文件读进堆:

- **PreprocessStep**(`batch-worker-import/.../stage/PreprocessStep.java`):大对象(≥16MiB,`SPOOL_THRESHOLD_BYTES`)走 `streamObjectToSpoolAndReturn`,`Files.copy(InputStream, Path)` 8K 缓冲流到 `/tmp` spool 文件,**从不分配整文件 byte[]**;小文件才走内存路径(受 `MAX_OBJECT_BYTES` 默认 512MiB fail-fast)。
- **ParseStep** → 逐行解码 spool → 写 NDJSON staging;**ValidateStep** → `BufferedReader` 逐行按 `chunk_size` 校验;**LoadStep** → `BufferedReader` 逐行,凑满 `chunkSize` 即 `plugin.loadChunk` 落库后 `chunk.clear()`,**任意时刻堆里只有一个 chunk**。
- **chunk 默认 2000 行,上限 10000**(`ImportWorkerConfiguration` `DEFAULT_CHUNK_SIZE=2000`;`BATCH_WORKER_IMPORT_CHUNK_SIZE:2000` / `MAX:10000`)。

**实测(真实链路,非空跑)**:

- `docs/verifications/import-partition-replace-copy-10m-system-2026-06-07.md`:**1000 万行 / ~1GiB CSV 单 worker 单流成功**,端到端 ~240s(PREPROCESS 6.5s + PARSE 67.8s + VALIDATE 39.4s + **LOAD 123.6s**),落库 `count=10,000,000` distinct 全对。
- `docs/verifications/streaming-large-file-import-export-2026-06-06.md`:807MiB / 100 万行宽表,**worker RSS 全程 < 200MB**(处理中 ~180MB,收尾回落 38MB)。

**结论**:上万行对 import 是零头;内存恒定,瓶颈是 **LOAD 阶段的 DB 写入**(逐行 UPSERT + 索引维护 + 单机 PG),不是 worker。

## 二、"任务" = 分区 partition fan-out → 生成不出上万,单文件拆分区是净亏

- partition count 责任链(`DefaultSchedulePlanBuilder`,ADR-005):`Explicit → SizeBased → Runtime → WorkerBased`,且 `normalizePartitionCount` 用 `Math.min(256, …)` 夹死。**"上万 partition" 根本生成不出来,封顶 256。**
- **单个大文件拆分区是反模式**,实测 4 分区比单流**慢 2.6×**(streaming doc §5.1):① 每个分区都要完整下载+解析全量再按 `lineNo % count` 留 1/4 → 4× 冗余 IO/CPU;② 多分区共享同一 `file_record`,并发推状态机撞乐观锁 `state_conflict` 重试卡顿。
  - 对冲:**range-slice**(`PreprocessStep.streamObjectRangeToSpool`)——FIXED_WIDTH 或 opt-in `partition_range_slice=true` 的定宽/单字节编码场景只 range GET 本片,跳过 line-mod;但默认 CSV 不开。
- `PARTITION_REPLACE_COPY` 策略直接 fail-fast 禁止 `partitionCount>1`(`LoadStep.requireSinglePartitionForPartitionReplace`)。

**结论**:分区是给**多个独立文件并发**用的,不是给单文件拆并行。单大文件单流最优。

## 三、"任务" = 派发的 job_task(海量小任务)→ 不挂,但消化慢(瓶颈在控制面)

- **单 worker 并发**:`batch.worker.max-concurrent-tasks` 默认 **8**(`AbstractTaskConsumer`;import worker override 6)。worker **非单线程**:`doConsume` 用 `Semaphore.tryAcquire` 控并发,满了 `pauseContainer()` **背压**(暂停拉 Kafka,不阻塞 consumer 线程防 rebalance)。
- **实测控制面才是瓶颈,不是 worker 执行**:
  - `docs/verifications/multitenant-peak-single-node-ceiling-2026-06-13.md`:单机完成吞吐 **~20–22 jobs/s 封顶**,拐点并发≈64;根因 **launch 单线程顺序消费(有效并发 ~7)**,worker 执行仅 37ms,310ms 全在 dispatch→claim→start 排队。放开 launch 消费并发 **~22→33/s(+50%)**;launch+worker 双开峰值 **~62/s** 仍未饱和。
  - `docs/verifications/control-plane-worker-throughput-2026-06-07.md`:高压 900 launch,worker claim P95 ~1.2–1.4s、exec P95 <100ms、Kafka lag=0、current_load 回 0 → "worker 执行不是瓶颈"。

**结论**:上万小任务,单 worker 靠并发 8 + 背压不会被压垮(满了就 pause,Kafka 兜着),**但端到端被控制面派发封在 ~20–62 jobs/s**——扛得住"不挂",但"消化慢",且这是**控制面**问题。

## 真正的瓶颈 / 风险

1. **单大文件 import**:瓶颈在 **LOAD 的 DB 写入**(逐行 UPSERT + 索引 + 单机 PG),worker 内存恒定 < 200MB。
2. **海量小 task**:瓶颈在 **控制面 launch/dispatch 单线程串行**(~20 jobs/s,有效并发 ~7)。
3. **内存兜底已做满**:spool 落盘、流式逐行、chunk 分块、`MAX_OBJECT_BYTES` 512MiB fail-fast、解密 Path→Path 流式避免 2× 堆峰。
4. **失败兜底**:LoadStep 有 **ADR-038 checkpoint 断点续跑**(`batch.worker.checkpoint.enabled`,按 chunk 推进 `positionStore`,续跑跳已处理行号),**默认关闭**,且要求 plugin 自报幂等否则拒跑;失败时**故意保留 staging 文件**便于重放。

## 要扛得更稳 / 更快该怎么配(按性价比)

**单大文件(行级,90% 场景到此为止)** —— streaming doc §5.3 决策树:

- **别拆分区**,保持 `shard_strategy=NONE` 单流。
- 调 `BATCH_WORKER_IMPORT_CHUNK_SIZE`(2000→5000,上限 `MAX_CHUNK_SIZE`),减少 commit 往返(2–5×)。
- LOAD 用 `PARTITION_REPLACE_COPY`(COPY 替逐行 INSERT,单 PG 数十万行/s);导入期临时去索引、导完重建(2–10×)。
- 换生产 JVM/GC(G1/并行 GC 替 SerialGC + TieredStopAtLevel=1;实测档是快启低优化档,非性能档)。
- 预期单流 182s → 30–60s,**无需任何分布式**。

**多个独立文件并发** —— 各自 file_record、0 冲突、0 冗余(doc §5.2 比单文件 4 分区快 2.3×);单机受 DB 写封顶不提速,**需多 worker 节点 + 分库**才线性加速。

**海量小 task** —— 调 `BATCH_WORKER_MAX_CONCURRENT_TASKS`(8→32)+ Kafka `concurrency`,但**必须同时放开控制面 launch/report 消费并发**(否则 launch=1 时抬 worker 完全无效,ceiling doc 已证伪);执行 pool size 必须 ≥ maxConcurrentTasks 否则自堵(`WorkerExecutionTimeoutProperties`)。

**长跑大文件** —— 开 `batch.worker.checkpoint.enabled=true` 断点续跑(需确认 load plugin 幂等:UPSERT / 唯一约束)。

---

> 一句话:单 worker 扛上万行是日常(扛过千万行),内存恒定;瓶颈在它身后的单机 PG 写入和控制面串行派发,而不是 worker。要更快先榨单机写法(COPY / 调 chunk / 换 GC),不要急着拆分区或加节点。

## 相关文档

- `docs/verifications/import-partition-replace-copy-10m-system-2026-06-07.md` —— 1000 万行实测
- `docs/verifications/streaming-large-file-import-export-2026-06-06.md` —— 流式 / RSS / 分区净亏 §5
- `docs/verifications/multitenant-peak-single-node-ceiling-2026-06-13.md` —— 控制面吞吐天花板
- `docs/verifications/control-plane-worker-throughput-2026-06-07.md` —— worker 非瓶颈实证
- `docs/analysis/throughput-bottleneck-*`(控制面瓶颈根因)/ ADR-005(分区责任链)/ ADR-038(checkpoint)
