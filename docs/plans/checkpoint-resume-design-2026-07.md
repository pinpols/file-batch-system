# Checkpoint / 断点续跑 设计与分期施工规划（2026-07）

> 后端 P2 最大单项(粗估见 [`backend-borrowings-and-improvements-2026-07.md`](./backend-borrowings-and-improvements-2026-07.md) §2.2,15–25 人天)。文档先行、过评审再动工。
> 原则:守 CLAUDE.md 架构硬约束(orchestrator 唯一状态主机 / worker 必 CLAIM / outbox 同事务 / UNIQUE 幂等契约 / 复合 PK 前瞻);借 Spring Batch 的 checkpoint 理念,不引依赖;把已有深度打磨到生产可用,优先于铺新面。

---

## 0. TL;DR(先看结论,再看论据)

**最重要的现状发现:断点续跑的核心链路已经落地,不是从零开始。**

ADR-038(2026-06-02 Accepted)已经交付**平台 worker 阶段内(intra-stage)续跑位点**,覆盖两条最痛的路径,并全部灰度开关保护、默认关闭:

| 能力 | 状态 | 证据 |
|---|---|---|
| Import **LOAD** 行号续跑 | ✅ 已合(P1/P2,2026-06-02) | `LoadStep.java`、`pipeline_progress`(V164)、`LoadStepCheckpointTest` |
| Export **GENERATE** cursor+字节位点续跑 | ✅ 已合(P3,2026-06-05) | `GenerateStep.java`、`GenerateCheckpoint`、`GenerateStepCheckpointTest` |
| 位点表 `batch.pipeline_progress` + archive 镜像 | ✅ 已合(V164) | `db/migration/V164__create_pipeline_progress.sql` |
| 灰度开关 `batch.worker.checkpoint.enabled`(默认 false) | ✅ 已合 | `WorkerCheckpointProperties`(prefix `batch.worker.checkpoint`) |
| 幂等前置校验(跨库无 1PC 安全网) | ✅ 已合(R3-3) | `LoadStep.requireIdempotentPluginIfCheckpointEnabled` `:240-258` |
| 运维 howto + 反例 | ✅ 已合 | `docs/runbook/platform-worker-checkpoint-howto.md` |

**因此本文档的价值不是"从零设计 checkpoint",而是三件事**:

1. **校准认知**:纠正 [`backend-borrowings-and-improvements-2026-07.md`](./backend-borrowings-and-improvements-2026-07.md) §1.2/§2.2 的"链路未打通"陈述——那是 ADR-038 落地前的判断,现已过时。LOAD/GENERATE 阶段内续跑**已打通**,只是**开关默认关、未默认启用**。
2. **补齐真缺口**:ADR-038 §决策四(阶段级续跑,P4)明确**未做**;PROCESS/DISPATCH 两条 pipeline 无阶段内续跑;bundle 上万 fan-out(ADR-046)的分区级续跑靠 `job_partition` 已有、但缺聚合观测。
3. **给出分期与取舍**:把"最大单项"拆成 P0(生产化已有,最高 ROI)→ P1(阶段级续跑 + PROCESS 幂等复用)→ P2(DISPATCH/COMPUTE 深水区,多数 YAGNI 后置),每期带人天/验收/回滚。

**一句话推荐方案**:**先把已落地的 LOAD/GENERATE 阶段内续跑补观测、sim/e2e 全链验证后默认启用(P0,系统未上线故无需影子期/租户渐进),再做轻量的"阶段级续跑 + PROCESS 阶段跳过"(P1),DISPATCH/COMPUTE 行级续跑按真实数据量证据 YAGNI 后置(P2),全程不新建位点表、不碰 orchestrator 状态机。**

---

## 1. 现状盘点(逐一亲读,给 `文件:行`)

### 1.1 位点表 `batch.pipeline_progress`(V164 / ADR-038)——已存在,是核心载体

DDL:`db/migration/V164__create_pipeline_progress.sql:25-42`。关键结构:

- 主键**单列** `id BIGSERIAL`(`V164:26`);逻辑幂等键是 `UNIQUE (tenant_id, pipeline_instance_id, stage)`(`V164:36-37`),即 **UPSERT 冲突目标**。
- 一行 = 一个 `(tenant_id, pipeline_instance_id, stage)` 的位点,即**每 pipeline 实例每 stage 一行**(粒度不是 chunk、不是 partition)。
- `position_marker VARCHAR(512)`:LOAD=已处理到的**物理行号**(staging 文件 append-only,行号稳定);GENERATE=`<byteOffset>@<typed-cursor>` 序列化(`V164:12-14`, `:50-51`)。
- `processed_count BIGINT`:已成功处理记录数,chunk/page 提交时累加。
- `completed BOOLEAN` + `completed_at`:该 stage 整体完成标记,用于幂等跳过。
- **CHECK 约束 `stage IN ('LOAD','GENERATE')`**(`V164:38-39`)——**这是关键设计边界**:表结构在 DDL 层就把续跑范围钉死在两个 stage。新增 PROCESS/DISPATCH 续跑必须 `ALTER` 这个 CHECK(是迁移、是语义变更,不是运维操作)。
- archive 镜像 `archive.pipeline_progress_archive`(`V164:69-87`),登记入 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES`(守 CLAUDE.md §archive 冷表对齐红线)。

**PK 前瞻注意**:本表用单列 `id` PK,不符合 CLAUDE.md「新表复合 PK 前瞻」的字面。但它是**只经父表 id(pipeline_instance_id)访问的 run 明细子表**,自身带 `tenant_id` + 独立 UNIQUE,属于 CLAUDE.md §多租隔离「② run 明细子表」豁免类别的近似。若本表未来要月分区,需评估复合化——**已列为风险 R-6**。

### 1.2 写入链路——谁写、粒度、同事务边界的真相

- 抽象:`ProcessingPositionStore`(`batch-worker/core/.../checkpoint/ProcessingPositionStore.java`),三方法 `load/advance/markCompleted`。
- 实现:`DefaultProcessingPositionStore`(同目录),经 `PipelineProgressMapper`。
- SQL:`batch-worker/core/src/main/resources/mapper/PipelineProgressMapper.xml`——`advance` 是 `INSERT ... ON CONFLICT (tenant_id,pipeline_instance_id,stage) DO UPDATE`(`:44-67`),`completed=true` 时不再回写位点(`:54-63`,防迟到 chunk 撤回已完成位点);`markCompleted`(`:70-85`)。
- LOAD 调用点:`LoadStep.java` —— 每 chunk `flushAndAdvance`(`:208-224`)先 `flushChunk`(plugin 写业务库)后 `advanceCheckpoint`(`:218` → `positionStore.advance` `:324-329`);阶段完成 `completeCheckpoint`(`:333-338`)。
- GENERATE 调用点:`GenerateStep.java` 开 `GenerateCheckpoint`(`:141-148`),每页 fsync + 记 `<byteOffset>@<cursor>`,完成 `checkpoint.complete(recordCount)`(`:169`)。

> **⚠️ 关键真相:实现不是真正的"chunk 业务写 + 位点同事务"。** ADR-038 §决策二(`ADR-038:86-102`)、V164 头注释(`:15-16`)、`ProcessingPositionStore` javadoc(`:9`)都写"同事务"。但 LOAD 的业务数据写**租户业务库**(`importBusinessDataSource`),位点写**平台库**(`batch.pipeline_progress`),**跨库无 1PC**。实际语义是运维 howto 明说的「业务先 commit → 位点后 advance」+ 插件幂等回退(`docs/runbook/platform-worker-checkpoint-howto.md:27-37`)。崩溃窗口(业务已 commit、位点未 advance)重派会**重做 ≤1 chunk**,靠 plugin 的 `INSERT ON CONFLICT` 保证不双写。这是**已知的、有意的设计取舍**,不是 bug——但**必须在本文档和 ADR-038 里把"同事务"措辞校正为"跨库补偿式最终一致 + 插件幂等"**(见 §7 R-1)。

### 1.3 读取链路——重派时确实读位点跳过已完成工作

- LOAD:`LoadStep.openCheckpoint`(`:302-317`)读 `positionStore.load`,`completed` → 整阶段幂等跳过(`:124-130` `markLoaded`);否则 `loadValidatedRecords` **skip 掉 `startLineNo` 行**再续跑(`:172-206`,skip 循环 `:185-188`),`loadedCount` 从 `processedCount` 起(`:182`,不用行号防空行虚增)。
- GENERATE:`GenerateStep` 读 `positionStore.load`(`:134-136`);`completed` 且确定化文件仍在 → 不重生成(`:137-139`);否则从存的 cursor 续页(`:141-148`)。
- **前置安全网**:开关开时,`requireIdempotentPluginIfCheckpointEnabled`(`LoadStep.java:240-258`)强制 plugin 自报 `IDEMPOTENT_BY_UNIQUE_CONSTRAINT` / `IDEMPOTENT_BY_PLUGIN_LOGIC`,否则 `WorkerConfigException` 拒跑(`IMPORT_LOAD_CONFIG_INVALID`);`PARTITION_REPLACE_COPY` 模板 + 续跑开关冲突也直接拒(`:270-294`,因为 replace 语义与行级续跑不兼容)。

### 1.4 测试覆盖(证明续跑真在工作)

- `LoadStepCheckpointTest`:关开关不碰位点;`completed` 跳过 loadChunk;开开关每 chunk `advance times(2)` 后 `markCompleted`;`resume_skipsAhead`(marker=2 → 跳 2 行、从第 3 行续、最终 marker `"4"`)。
- `LoadStepCheckpointPrecheckTest`:非幂等 plugin / PARTITION_REPLACE_COPY 拒跑,不进位点路径。
- `GenerateStepCheckpointTest`:用 `InMemoryPositionStore` 跨两次 `execute` 模拟崩溃重派;`completedAndFileExists_isIdempotentlySkipped`。
- `GenerateCursorCodecTest`:cursor 类型安全往返(Long/Timestamp/String… 支持;UUID 等降级为不可续跑全量跑,只影响能力不影响正确性)。

### 1.5 各 pipeline stage 的中断行为矩阵(现状)

| Pipeline | Stage | 崩溃/重派现状 | 是否有续跑 | 关键机制 |
|---|---|---|---|---|
| Import | LOAD | 行号续跑(开关开) | ✅ intra-stage | `pipeline_progress` + staging 文件 skip |
| Export | GENERATE | 字节偏移+cursor 续跑(开关开) | ✅ intra-stage | `pipeline_progress` + `FileChannel.truncate` |
| Process | COMPUTE→VALIDATE→COMMIT | **全量重算(但幂等不双写)** | ❌ 缺阶段级跳过 | 见下 §1.6(稳定 batch_key + 前置 DELETE + ON CONFLICT 已保幂等,仍重算) |
| Dispatch | PREPARE→DELIVER→ACK→COMPLETE | **全量重跑**(远端回执幂等靠渠道) | ❌ | 见下 §1.7 |
| Atomic | SQL/HTTP/SHELL/STORED_PROC | **全量重跑** | ❌(设计上不做) | 原子任务无行数语义 |
| 阶段之间(所有 pipeline) | runStageLoop | **从首个 stage 重来**,不从"上次成功 stage" | ❌ | ADR-038 §决策四(P4)未做 |

阶段循环骨架:`AbstractStageExecutor.runStageLoop`(`batch-worker/core/.../support/AbstractStageExecutor.java:40-104`)——顺序执行 step,失败即停(`:99-101`),重派每次从 `firstStep` 起(`:54`)。**没有读"已完成到哪个 stage"再跳过**的逻辑,这正是决策四缺口。

### 1.6 PROCESS 的 staging 角色——已幂等,但仍全量重算(缺阶段级跳过)

- 表:`batch.process_staging`(V75,WAP 模型),PK `(batch_key, row_seq)`(`V75__add_process_staging_table.sql:14-23`;后改按天 RANGE 分区 `scripts/db/business/migrate-process-staging-to-partitioned.sql`)。
- 流水线:PREPARE → COMPUTE 写 staging(`ComputeStep.java` 委托 `SqlTransformComputePlugin.compute`,是**不透明整体调用**,无 chunk 位点)→ VALIDATE 跑质量规则 → COMMIT `jsonb_populate_record(NULL::<target>, payload)` 原子发布到目标表(`CommitStep.java:28`)→ FEEDBACK 清 staging。
- **续跑相关的关键事实(经亲核代码,纠正 V75 迁移注释的过时描述)**:
  1. `batch_key` **已是稳定值** `"process-" + taskId`(`DefaultProcessStageExecutor.java:122-128`,跨 lease 回收/重派稳定;历史上曾用每次变的 traceId 致 COMMIT 发布两轮并集,已修)。**不是** V75 注释里写的"taskId + uuid 后缀"——迁移注释已过时。
  2. 重派幂等已由三层保证、**不会双写**:COMPUTE 前置 `DELETE` 清掉上次崩溃残留 staging(`SqlTransformComputePlugin.java:140-167`)→ 重 INSERT → COMMIT `ON CONFLICT DO NOTHING/UPDATE`(`:649-674`)。孤儿 staging(任务死在 FEEDBACK 前)由 `ProcessStagingOrphanCleaner` 定期清。
  3. **但仍是全量重算**:重派会重跑整个 COMPUTE(重新 transform + 重写 staging),只是安全不双写。COMMIT 是**原子发布**(staging→target 一次性),本身是"阶段边界一致点"。**所以 PROCESS 的真缺口是"阶段级跳过"**——COMPUTE 已成功时重派应跳到 COMMIT,而非重算——而**不是** batch_key/幂等改造(那已完成)。

### 1.7 DISPATCH 的中断行为——回执幂等在渠道侧,非平台位点

- steps:`PrepareDispatchStep → DeliverDispatchStep → AckDispatchStep → CompleteDispatchStep`(+ `RetryDispatchStep`/`CompensateDispatchStep`),`batch-worker/dispatch/.../stage/`。
- 分发关心的是**远端回执/校验和/渠道状态**,不是行数(`docs/design/pipeline-stage-progress-display.md:92` DISPATCH「默认否」行级进度)。崩溃重派靠**下游渠道幂等**(receipt/checksum)兜底,平台无字节/行位点。**行级续跑对 DISPATCH 价值低**(见 §4 YAGNI)。

### 1.8 大 fan-out 的"分区级续跑"——已被 job_partition 覆盖(ADR-014/046)

- `job_partition`(`V5__create_runtime_tables.sql:59-83`):fan-out 单元,有 `partition_status`、`retry_count`、`input_snapshot`、`output_summary`、`lease_expire_at`、`current_invocation_id`(V95 ADR-014)、幂等键 `uk_job_partition_idempotency_key UNIQUE(tenant_id, idempotency_key)`(`V5:79`,key=`jobInstanceId:partitionNo`;NULL-bypass 在 V124 收紧为 partial unique index `V124:24-26`)。
- **语义**:一个 K 分区的 `job_instance`,分区 5000 失败**只重跑分区 5000**,其余不动——这本身就是**分区粒度的断点续跑**,已生产可用。ADR-046 multi-row claim/report(`claimBatch`/`reportBatch`,`TaskControllerApplicationService.java:99-141`/`:188-206`)把 fan-out 的 claim/report 从 O(N) 压到 O(N/K),每分区仍是独立单元、无束状态机、无共享幂等。
- **checkpoint 与它的关系**:`pipeline_progress` 是**分区内(intra-partition)行级续跑**;`job_partition` 是**分区间(inter-partition)分区级续跑**。二者正交、叠加。上万 fan-out 的"续跑"绝大部分需求由 `job_partition` 已满足;`pipeline_progress` 只在**单个超大分区(百万行)内部**才有增量价值。

### 1.9 retry / replay / compensation 的边界(避免重复造轮子)

| 机制 | 粒度 | 是否新建实例 | 用途 | 证据 |
|---|---|---|---|---|
| **per-task retry**(run_attempt) | 单 task/partition | 否,原地重排 | 瞬时故障自愈 | `DefaultTaskOutcomeService.scheduleRetryIfNecessary :294-301`,partition→`RETRYING :428`,`JobPartitionMapper.markRetrying :242-256` 清 worker/lease |
| **checkpoint 续跑**(本设计) | 分区**内**行/页 | 否,同实例重派续跑 | 大数据量崩溃不重头 | `pipeline_progress` |
| **compensation**(前向再驱动) | JOB/STEP/PARTITION/FILE/BATCH/DLQ | 视类型 | 再跑/再投递,**非回滚业务数据** | `DefaultCompensationService :76-83`,`rerunJob`/`retryPartition`… |
| **batch_day_replay**(ADR-020) | 整 bizDate 的**整 job** | 是,新实例+新 result_version | 运维重放整天,带审批/进度/版本 | `V110`,`BatchDayReplayDispatcher.dispatchEntry :146-166` |

**边界结论**:retry 解决"重试单元",replay 解决"重放整天整 job",compensation 解决"前向再驱动/补偿链"。**checkpoint 解决且仅解决"同一次执行被重派后,分区内不从第 0 行重来"**——这是三者都不覆盖的空白,不与它们重叠。

### 1.10 硬约束现状核对(设计不得违反)

- **orchestrator 唯一状态主机**:位点落 worker 可写的平台内部表 `pipeline_progress`,worker **不写** `job_instance`/`pipeline_instance` 状态(ADR-038 §范围边界 `:36-42`)。终态 CAS 只在 orchestrator `DefaultTaskOutcomeService.finishTask`(`:306-317`,guard `task_status=RUNNING`)。✅ 现状合规,本设计延续。
- **worker 必 CLAIM**:claim 是乐观 version-CAS `UPDATE`(`JobTaskMapper.xml:265-277`),非 `FOR UPDATE SKIP LOCKED`;partition claim 带 lease CAS(`JobPartitionMapper.xml:213-227`)。✅
- **outbox 同事务**:`OutboxDomainEventPublisher.publish` 是 `@Transactional(MANDATORY)`(`:30-46`),永远不能脱离调用方事务;dispatch 的 createPartitions+createTasks+writeDispatchEvent 同事务(`DefaultPartitionDispatchService.java:89-95`)。**checkpoint 位点写不进 outbox 事务**——位点是 worker 内部记录,不触发 orchestrator 状态转移,**本就不该走 outbox**(见 §4 YAGNI「位点写 outbox」)。✅
- **UNIQUE 幂等契约**:`pipeline_progress` 的 `UNIQUE(tenant_id, pipeline_instance_id, stage)` 是位点 UPSERT 幂等键;任何扩 stage 值或改此列集都要 `grep -r 'on conflict'` 核对(CLAUDE.md §架构硬约束)。✅

---

## 2. 问题定义(什么场景需要 checkpoint,现状重跑代价)

用真实模块/表名。**只有以下场景**才是 checkpoint 的目标:

| # | 场景 | 现状(无/关开关)重跑代价 | 已覆盖? |
|---|---|---|---|
| S1 | **百万行 Import LOAD** 单分区跑到 80% 崩溃/超时/lease 回收 | 重派从第 0 行重读 staging + 重调 `loadChunk`,业务库再吃一次 INSERT/UPDATE 风暴(冲击业务 QPS/SLA),占 worker slot 分钟级 | ✅ LOAD 续跑已落地(开关) |
| S2 | **大结果集 Export GENERATE** 写文件途中崩溃 | 重派从 `cursor=null` 重头分页拉数据 + 重写文件,cursor 分页在百万行是分钟级 IO | ✅ GENERATE 续跑已落地(开关) |
| S3 | **PROCESS 长 COMPUTE**(百万行 transform 到 staging)在 COMMIT 前崩溃 | 重派重跑整个 COMPUTE(前置 DELETE 残留 + 重算 + 重写 staging);**幂等不双写但白算一遍**,分钟级 | ❌ 缺阶段级跳过(幂等已有) |
| S4 | **多 stage pipeline** 在 stage N 崩溃,stage 1..N-1 已成功 | 重派从 stage 1 重来,重复已成功阶段的副作用(受各 step 幂等性保护程度不一) | ❌ ADR-038 §决策四(P4)未做 |
| S5 | **上万 fan-out bundle**(ADR-046)中 3% 分区失败 | 只重跑失败分区(分区级已续跑),但**缺聚合观测**:运维看不清"哪些分区在续跑第几次、整体断点分布" | 🟡 分区级已有,观测缺 |
| S6 | **大 DISPATCH** 分发 10 万条回执途中崩溃 | 重派从头重投,靠渠道回执幂等去重(远端幂等则安全,否则重复投递) | ❌ 无位点(但价值存疑,见 §4) |

**关键判断**:S1/S2 已解决(缺的是**生产启用**);S3/S4/S5 是**真缺口**且改造轻;S6 价值最低。

---

## 3. 范围边界(YAGNI 红线)——❌ 不做清单

对齐 CLAUDE.md §ADR 与范围纪律 + ADR-038 §范围边界。**发现越界(即使代码正确)必须 reject。**

- ❌ **不新建第 2 张位点表**。`pipeline_progress` 是唯一位点载体;扩 stage 值走 `ALTER CHECK`,不建 `dispatch_progress`/`process_progress` 同义表(违反 CLAUDE.md「禁新增同义表」精神)。
- ❌ **不把位点写进 outbox / 不触发 orchestrator 状态转移**。位点是 worker 内部记录,不是业务事件;写 outbox 会放大控制面消息、且违反"位点是 worker 内部记录"(ADR-038 反例 `:99-106`,`docs/design/pipeline-stage-progress-display.md:137`)。
- ❌ **不追求跨库 1PC / 不引 XA/JTA**。ADR-035 §决策 P3 已否决;位点跨库最终一致 + 插件幂等是既定取舍,不"修"成强事务。
- ❌ **不做单条记录级回滚 / 不做 worker 内线程池并行**(ADR-038 §范围边界 `:42`)。续跑是"跳过已完成",不是"撤销部分写"。
- ❌ **不给 DISPATCH/ATOMIC 造行级位点**,除非出现真实数据量证据(对齐 ADR-038 改判逻辑:先有百万行 + 真崩溃证据再动)。DISPATCH 幂等承重在渠道回执,不在平台位点。
- ❌ **不做 checkpoint 的前端高频推送 / 全局百分比进度条**(`pipeline-stage-progress-display.md:130-136`)。观测走已有低频 SSE dirty event + 轮询快照。
- ❌ **不与 batch_day_replay 合并**。阶段级续跑(§决策四)与 replay 语义相邻但不同(replay=新实例+版本治理;续跑=同实例重派跳过)——ADR-038 howto `:145` 已标"需对齐后再做",本设计明确二者不合并、只做续跑侧。
- ❌ **不删 checkpoint 开关、不移除幂等前置校验**。系统未上线,P0 会把默认值翻 true(见 §5),但开关本身保留作回滚手段;`requireIdempotentPluginIfCheckpointEnabled` 拒非幂等 plugin 的安全网永久保留,不因默认启用而放松。

**用现有能力就够、不该上 checkpoint 的场景**:整天重放 → `batch_day_replay`;失败分片重试 → per-task retry / `retryPartition`;补偿链 → `DefaultCompensationService`;分区级断点 → `job_partition`(已天然续跑)。

---

## 4. 方案设计

### 4.1 位点粒度(已定,延续)

- **Import LOAD = 物理行号**(staging append-only,行号稳定可 skip)。
- **Export GENERATE = 字节偏移 @ 类型化 cursor**(`FileChannel.truncate` 截残尾 + cursor 续页)。
- **PROCESS COMPUTE(新)= 不做行级,做阶段级 + staging 幂等复用**(理由见 §1.6:COMMIT 已是原子发布,行级位点收益低于 deterministic staging 复用)。
- **DISPATCH = 不做**(YAGNI)。
- 粒度选择原则:**位点必须"可类型安全往返 + 可确定定位"**;做不到就降级为全量跑 + 一次 WARN(GENERATE 对 UUID cursor 已如此),**只影响续跑能力,不影响正确性**。

### 4.2 存储:扩展 `pipeline_progress`,不建新表

- 扩 CHECK 约束 `stage IN ('LOAD','GENERATE','COMPUTE_STAGE','PIPELINE_STAGE')`(具体值 P1 定),archive 镜像同迁移对齐(CLAUDE.md 红线),squawk `NOT VALID` + `VALIDATE` 同迁移双守护(见我方 memory「加 CHECK/FK 迁移的两道守护」)。
- **不改 UNIQUE 列集**(仍 `tenant_id, pipeline_instance_id, stage`),所以幂等契约不变、无需重扫全仓 on-conflict。扩 CHECK 值集是**加值不改键**,风险可控——但仍需 `grep -r 'on conflict' pipeline_progress` 确认无隐藏依赖。
- **复合 PK 前瞻**:若 P1/P2 预判 `pipeline_progress` 行数随租户×时间显著增长要月分区,应在扩 stage 的同批评估把 PK 改 `(tenant_id, id)` 或含分区键(CLAUDE.md「新表 PK 前瞻」)。当前作为 run 明细子表,暂不强制。

### 4.3 写入时机与事务边界(与"outbox 同事务"硬约束的关系)

- 位点 `advance` **在 worker 端 chunk/page 业务写的同一 `@Transactional` 边界内**(`ProcessingPositionStore` javadoc 已如此约定),但**LOAD 跨库故非严格 1PC**——校正措辞为「同平台事务内推进 + 跨业务库补偿式最终一致 + 插件幂等」。
- **与 outbox 无关**:位点不进 `outbox_event`,不触发 orchestrator 状态机(§1.10)。worker 仍走 CLAIM→EXECUTE→REPORT 主链;续跑只改变"EXECUTE 内部从哪行起",不改 REPORT 语义。
- **阶段级续跑(决策四)的事务边界**:`runStageLoop` 每 stage 成功后,worker 已经写 `pipeline_step_run`(`AbstractStageExecutor:86` `finishStepRunSuccess`)。方案是**复用已有的 `PIPELINE_LAST_SUCCESS_STAGE`**(`AbstractStageExecutor:66-69, 83-85`,已在写)作为阶段位点,重派时从 `last_success_stage` 的下一 stage 起、已完成 stage 幂等跳过——**几乎零新增存储**,读现有 `pipeline_instance`/`pipeline_step_run`。

### 4.4 恢复协议(CLAIM 后如何拿位点、幂等如何保证)

1. worker CLAIM 到 task(乐观 CAS,§1.10),task_payload 带 `pipelineInstanceId`。
2. 进 stage 前 `positionStore.load(tenantId, pipelineInstanceId, stage)`(读平台库,当前索引 `idx_pipeline_progress_tenant_instance` 支持)。
3. `completed` → 幂等跳过整 stage;否则拿 `positionMarker`+`processedCount` 续跑。
4. **幂等保证三层**:(a) `UNIQUE(tenant_id,pipeline_instance_id,stage)` UPSERT 防位点重复;(b) `completed=true` 后 advance 不回写(防迟到 chunk 撤回);(c) 业务写侧靠 plugin 幂等(`requireIdempotentPluginIfCheckpointEnabled` 前置校验拒非幂等 plugin)。
5. 与 run_attempt 交互:retry 由 orchestrator 把同 partition/task 重置 READY 再经 outbox `RunMode.RETRY` 重派(`DefaultRetryGovernanceService.requeuePartition:537`、`resetForDispatch READY :578`、`writeDispatchEvent :590`)。`pipeline_instance` 行按 `related_job_instance_id` UPSERT 复用(`PlatformFileRuntimeMapper.xml:135-155` `ON CONFLICT ... DO UPDATE`),故 `pipelineInstanceId` **跨重派稳定** → 位点自然被下一次 attempt 读到续跑。`run_attempt` 是 **job_instance 级** rerun 身份(`V62`/`V173`,幂等键 `(tenant_id,dedup_key,run_attempt)`),与 partition 级 `retry_count` 是两个计数器。与 compensation 交互:`retryPartition`/`rerunStep` 走 compensation 也是同实例重派 → 续跑生效;`rerunJob`/`batch_day_replay` 是**新实例**(新 pipelineInstanceId)→ 无旧位点 → 从 0 全量跑(**符合预期**:重放要的就是干净重跑)。

### 4.5 备选方案取舍(≥2)

**备选 A — 纯幂等重跑加速(不做真位点续跑)**
思路:不存位点,靠 plugin 幂等 + 目标表 UNIQUE,重跑时 `INSERT ON CONFLICT DO NOTHING` 快速跳过已存在行。
- 优点:零新增存储、零事务边界改动、无跨库一致性问题。
- 缺点:百万行仍要**重新读全量 staging + 重算 + 对每行打一次 DB**(ON CONFLICT 也是一次 DB round-trip + 索引探测),业务库仍吃一次风暴——ADR-038 §评估记录 `:16-19` 正是推翻此路(初评的"仅浪费 CPU"被证低估一个量级)。
- **裁定:否**(已被 ADR-038 改判否决,历史教训)。

**备选 B — 真位点续跑(pipeline_progress,当前已落地方案)**
- 优点:重派直接 skip 到断点,不重读/不重算已完成部分,业务库不再吃重复风暴;已实现、已测。
- 缺点:跨库无 1PC 的补偿窗口(重做 ≤1 chunk);要求 plugin 自报幂等;位点表运维(归档/清理)。
- **裁定:是**(本设计基线)。

**备选 C — 每分区拆更细(worker 内并行 + 更小分区)替代 checkpoint**
思路:把大分区在 orchestrator 层拆成更多小分区,单分区失败重跑代价天然小,不需要分区内位点。
- 优点:复用已有 `job_partition` 分区级续跑,无新机制;分区越小重跑越便宜。
- 缺点:分区数受 `maxPartitionCount`(现 256,批量 SQL 参数上限护栏)限制;拆过细放大 claim/report/调度开销(控制面单线程 launch 已是瓶颈,见 memory「吞吐瓶颈在控制面」);且"单文件百万行"物理上难再拆(一个 staging 文件一个分区)。
- **裁定:部分采纳**——对"多文件/可分片"场景优先用更细分区(备选 C),对"单超大文件/单大结果集"用位点续跑(备选 B)。二者互补,写进 §4.1 粒度决策。

---

## 5. 分期施工(P0 → P1 → P2)

> 前提:P1/P2/P3(LOAD/GENERATE 阶段内续跑)**代码已合**。故本分期与 ADR-038 原 P1-P5 编号不同——这里是"从已落地状态继续"的分期。人天为单人粗估,多 agent 并行可压缩 wall-clock。

### P0 — 生产化已有能力(最高 ROI,不写新续跑逻辑)

**目标**:把 LOAD/GENERATE 阶段内续跑从"已实现、开关默认关"推到"默认启用、可观测、可回滚"。

> **系统尚未上线**(2026-07 裁定,AM 迁移影子期同因取消)。故不需要 staging 影子期/按租户 profile 渐进灰度——验证完全走 sim/e2e 本地全链,验证通过后**直接把 `batch.worker.checkpoint.enabled` 默认值翻 true**,开关本身保留作回滚手段。

| 项 | 人天 | 说明 |
|---|---|---|
| 观测埋点 | 2–3 | `pipeline_progress` 命中续跑次数 counter、续跑跳过行数/字节 gauge、`completed=false` 存量 gauge(stuck 诊断);接入「控制面健康仪表盘」语义(borrowings §2.1) |
| 措辞校正(R-1) | 0.5 | 把 ADR-038/V164 注释/javadoc 的"同事务"校正为"跨库补偿式最终一致+插件幂等";howto 灰度章节同步简化 |
| 幂等前置校验加固 IT | 2 | 驱动真跨库场景(Testcontainers PG),验证崩溃窗口重做 chunk 不双写;非幂等 plugin 拒跑 |
| sim/e2e 全链验证 + 默认值翻 true | 0.5–1 | sim 断点重派用例(参考 `docs/test-data/sim-stage2e-import-checkpoint.sql`)+ e2e 全链绿后,默认值改 true 一行配置 |
| **P0 小计** | **5–6.5** | 约 1 周 |

**验收**:sim/e2e 百万行级 fixture 崩溃重派,`pipeline_progress` 命中续跑、业务库无重复写;观测指标可见续跑命中;默认 true 下全量 sim 套件连跑绿(单跑绿≠连跑绿)。
**回滚**:配置显式设 `batch.worker.checkpoint.enabled=false` + worker 重启,行为完全退回今天;已写位点行无害保留(howto §关闭/回滚)。V164 不可回滚但对结构无破坏。

**上线前 checklist(上线时再做,非 P0 阻塞)**:真实租户 plugin 幂等能力盘点(非 `GenericJdbcMappedImportLoadPlugin` 的自定义 plugin 逐个核 `idempotencyCapability`)、生产观测面板接告警、真实数据量下续跑命中率/跳过行数首周复盘。

### P1 — 阶段级续跑 + PROCESS 阶段跳过(真缺口,改造轻)

**目标**:补 S3/S4——多 stage 崩溃从未完成阶段起、PROCESS COMPUTE 不重算。

| 项 | 人天 | 说明 |
|---|---|---|
| 阶段级续跑(ADR-038 §决策四)——覆盖所有 pipeline | 4–6 | `runStageLoop` 重派时读"已完成到哪个 stage"(现有 `pipeline_step_run` 成功记录 + `AbstractStageExecutor` 已在写的 `PIPELINE_LAST_SUCCESS_STAGE` `:66-85`)从下一 stage 起,已完成 stage 幂等跳过;三条 pipeline(import/export/dispatch/process)共 `AbstractStageExecutor` 骨架一处改。**这是 P1 主体**,直接解 S4,并让 PROCESS 的 S3 收益(COMPUTE 成功 → 跳到 COMMIT,不重算)——**无需改 batch_key(已稳定幂等,§1.6)** |
| 阶段位点持久化(供跨重派读) | 2–3 | 阶段级"已完成 stage"需持久到 worker 可读处:优先复用 `pipeline_step_run` 成功记录判定(零新增);若不足,扩 `pipeline_progress` CHECK 加 `PIPELINE_STAGE` 值记阶段位点 |
| 扩 CHECK 迁移 + archive 对齐 + 守护(若走位点表方案) | 1–2 | `ALTER ... CHECK stage IN (...)`(加值不改 UNIQUE 键),`NotValidConstraintGuard` 同迁移 VALIDATE;`ArchiveSchemaDriftCheck` 核对;扩前 `grep on-conflict` |
| 测试(崩溃重派阶段跳过 / PROCESS COMPUTE 跳过不重算 / 各 step 跳过幂等) | 3–4 | Testcontainers IT + sim;重点验已完成 stage 的副作用不重复 |
| **P1 小计** | **10–15** | 约 2–3 周(若阶段位点全复用 `pipeline_step_run`,取下限) |

**验收**:多 stage pipeline 在 COMMIT 前崩溃,重派跳过已成功 COMPUTE(staging 复用,无重算),CommitStep 原子发布正确;PROCESS 孤儿 staging 被清。
**回滚**:阶段级续跑与位点续跑共用同一开关分支;关开关退回"从 INITIAL stage 全量重跑";deterministic batch_key 若出问题,回退带 uuid 后缀(孤儿 staging 由 staged_at 定期清兜底)。

### P2 — 深水区(多数 YAGNI 后置,按证据启动)

**目标**:仅在出现真实数据量/崩溃证据时启动。默认**不做**。

| 项 | 人天 | 说明 |
|---|---|---|
| bundle fan-out 续跑聚合观测(S5) | 3–5 | 上万 fan-out 的分区断点分布/续跑次数聚合视图(borrowings §1.3 Grid/Gantt 密度视图方向);**只观测,不加束状态机**(守 ADR-046 轻量版) |
| DISPATCH 回执级续跑(S6) | 8–12 | **默认不做**;若渠道无幂等 + 大批量重复投递成真痛点再评估;需渠道回执位点契约,复杂度高、价值存疑 |
| PROCESS COMPUTE 插件游标续跑(分区内行级) | 8–12 | **默认不做**;COMMIT 原子发布已让阶段级续跑够用,行级仅在单分区 COMPUTE 超大且频繁崩溃才有边际收益 |
| **P2 小计(仅观测项建议做)** | **3–5**(观测)+ 其余 YAGNI | |

**验收/回滚**:各子项独立,均开关/视图隔离,可单独回退。

### 工作量汇总

| 期 | 人天 | wall-clock |
|---|---|---|
| P0 生产化 | 5–6.5 | ~1 周 |
| P1 阶段级+PROCESS | 10–15 | ~2–3 周 |
| P2 观测(其余 YAGNI) | 3–5 | ~1 周 |
| **合计(建议做的部分)** | **18–26.5** | ~4–5 周 |

> 与 borrowings §2.2「15–25 人天」对比:量级吻合,但**构成不同**——原估把 LOAD/GENERATE 续跑算作待做,实际已落地;这里的 18–26.5 人天中,**真正的新续跑逻辑只有 P1 的 10–15 人天**,其余是生产化(P0)与观测(P2)。**净新增 P2 编排工作显著小于原估**,因为核心已建成。

---

## 6. 与硬约束的合规性自检(评审用)

| CLAUDE.md 硬约束 | 本设计如何守 |
|---|---|
| orchestrator 唯一状态主机 | 位点落 `pipeline_progress`(worker 可写平台内部表),worker 不写 job_instance/pipeline_instance 状态;终态仍 orchestrator CAS |
| worker 必 CLAIM | 续跑不改 CLAIM;只改 EXECUTE 内部起点 |
| outbox 同事务 | 位点不进 outbox、不触发状态转移;与 outbox 事务无交集 |
| UNIQUE 幂等契约 | 复用现有 `UNIQUE(tenant_id,pipeline_instance_id,stage)`;扩 CHECK 加值不改键;扩前 grep on-conflict |
| 复合 PK 前瞻 | `pipeline_progress` 单列 PK 为 run 明细子表;若要分区,同批评估复合化(R-6) |
| archive 冷表对齐 | 扩 stage/表结构同迁移补 archive 镜像 + `ArchiveSchemaDriftCheck` |
| 读写分离仅 console-api | 位点读写全在 worker→平台主库,不引 replica |
| Pipeline 内置不可扩 | 不新增 pipeline stage 种类,只给现有 stage 加续跑 |

---

## 7. 风险清单

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R-1 | **"同事务"措辞误导**:代码/ADR/DDL 写"同事务",实际 LOAD 跨库非 1PC | 评审/后人误以为强一致,放松 plugin 幂等要求 → 双写 | P0 校正 ADR-038/DDL/javadoc 措辞;保留 `requireIdempotentPlugin` 强制校验为唯一真守护 |
| R-2 | **位点污染**:业务 commit 成功、位点 advance 失败瞬间崩溃 | 重派重做 ≤1 chunk | 已有:plugin 幂等 + `completed` 后不回写;不做额外事 |
| R-3 | **部分写入可见性**:GENERATE 崩溃残文件尾部未确认页 | 续跑重复/丢行 | 已有:`FileChannel.truncate(byteOffset)` 先于重写,cursor 仅在有后继页时记(`docs/runbook/...:71-72`) |
| R-4 | **扩 CHECK 迁移半上线**:squawk NOT VALID 缺 VALIDATE 或 archive 漏镜像 | static-checks/archive-drift 红 | 同迁移 NOT VALID+VALIDATE 双守护;archive 镜像同 PR(memory 教训) |
| R-5 | **deterministic batch_key 与并发**:同实例并发重派两 worker 同 batch_key 写 staging | staging 重复行 | staging PK `(batch_key,row_seq)` + COMPUTE 幂等;lease/invocation 隔离(V95)保证同分区单 active worker |
| R-6 | **pipeline_progress 分区化**:单列 PK 表未来要月分区改造代价 | 存量改造 | 扩 stage 同批评估复合 PK;当前作 run 明细子表暂缓 |
| R-7 | **多行 claim 交互**:ADR-046 batchReport 每 item 独立事务,续跑位点跨 item 无共享 | 无(各分区位点独立,正是设计意图) | 确认续跑观测按分区聚合而非全束(§5 P2) |
| R-8 | **监控缺口**:`completed=false` 位点长期堆积=stuck 但无告警 | 运维看不见卡住的续跑 | P0 加 `completed=false` 存量 gauge + stale 告警;接执行时间线(borrowings §1.1) |
| R-9 | **开关误开在非幂等 plugin**:被前置校验拒跑,但运维体验差 | 任务 fail(非数据损坏) | howto 接入清单;错误码 `IMPORT_LOAD_CONFIG_INVALID` 指向文档 |

---

## 8. Concerns（给评审的坦白）

1. **这不是"P2 最大单项从零启动",而是"已建成 60% 的能力做生产化 + 补边角"。** borrowings §2.2 的 15–25 人天估计与"链路未打通"表述基于 ADR-038 落地前,应在评审时同步校正,避免重复投入。真正的净新增(P1)只 10–15 人天。
2. **跨库无 1PC 是天花板,不是缺陷**。LOAD 续跑的正确性 100% 押在 plugin 幂等上;任何"把它做成强一致"的提案都会撞 ADR-035 P3(否决 XA)——评审应确认接受这个边界,而非要求"修"。
3. **DISPATCH/COMPUTE 行级续跑我建议明确 YAGNI 后置**。没有百万级 DISPATCH 崩溃的真实证据前投 8–12 人天做回执位点,违反 ADR-038 自己的改判逻辑(先有数据证据再动)。评审若有该证据请提供,否则 P2 只做观测项。
4. **生产收益验证是上线后观测项,非阻塞**:系统尚未上线,续跑命中率/真实百万行业务库压力等生产收益指标无从验证也无需验证——sim/e2e 证机制正确即够 P0 验收;真实收益复盘列入 §5 P0「上线前 checklist」,上线后首周看观测面板即可。
5. **`pipeline_progress` 复合 PK 前瞻(R-6)**是本设计唯一与 CLAUDE.md 字面有张力处,需评审拍板:现在补复合化,还是作为 run 明细子表豁免、待分区时再改。

---

## 附:关键文件索引(施工指针)

- 位点表:`db/migration/V164__create_pipeline_progress.sql`
- 位点抽象/实现/SQL:`batch-worker/core/.../infrastructure/checkpoint/{ProcessingPositionStore,DefaultProcessingPositionStore}.java`、`batch-worker/core/src/main/resources/mapper/PipelineProgressMapper.xml`
- LOAD 续跑:`batch-worker/import/.../stage/LoadStep.java`(`openCheckpoint :302`、`flushAndAdvance :208`、前置校验 `:240`)
- GENERATE 续跑:`batch-worker/export/.../stage/GenerateStep.java`、`GenerateCheckpoint`、`GenerateCursorCodec`
- 阶段循环骨架(决策四改造点):`batch-worker/core/.../support/AbstractStageExecutor.java:40-104`
- PROCESS WAP:`db/migration/V75__add_process_staging_table.sql`、`batch-worker/process/.../stage/{PrepareStep,ComputeStep,CommitStep}.java`
- 分区级续跑:`db/migration/V5__create_runtime_tables.sql:59-83`(job_partition)、`TaskControllerApplicationService.java`(claim/claimBatch/reportBatch)
- 边界机制:`DefaultTaskOutcomeService.java`(retry/终态)、`DefaultCompensationService.java`、`BatchDayReplayDispatcher.java`、`db/migration/V110__batch_day_replay_session.sql`
- ADR/运维:`docs/architecture/adr/ADR-038-platform-worker-checkpoint-resume.md`、`docs/runbook/platform-worker-checkpoint-howto.md`、`docs/spike/adr-038-p3-export-file-recovery.md`

---

*依据:2026-07 对 pipeline_progress(V164/ADR-038)、worker 各 stage、staging、replay/retry/compensation、CLAIM/outbox/UNIQUE 契约的逐一亲读(见正文 file:line)。工作量为单人粗估。文档先行,过评审再动工。*
