# ADR-046 · 文件束聚合 —— 在单文件单元之上加一层批量编排(File Bundle Aggregation)

- **Status**: Proposed
- **Related**: ADR-040(清单驱动到达组,**分组种子**)、ADR-002(transactional outbox)、ADR-027(资源亲和范围红线,**边界对照**)、`docs/analysis`(吞吐瓶颈实测:瓶颈在控制面消费 + claim/report 争用,非 PG)、PR #465(launch 消费并发 +50%)
- **Plan**: 本 PR 仅设计文档。**架构级**——引入新的编排聚合单元 + 动 orchestrator 的 claim/report 粒度(orchestrator 是唯一状态主机)。评审拍板下面 §决策 的 4 个开放问题后再分阶段逐 PR 落地,**先定语义再写码**。

## 范围边界(实施 PR 必答)

判定提问:**「这是把 N 个『单文件交付单元』的**编排**批量化(每个文件仍是独立、可幂等、可隔离、可观测的单元),还是把多个文件**揉成一个不可分的处理单元**(共享一份幂等/一个失败域)?」**

- 前者(编排批量化、底层仍单文件)→ **属本 ADR**。
- 后者(多文件混成一个处理单元)→ ❌ **不属**,会破坏 ADR-001/主链的幂等与故障隔离承重墙。

**✅ 做**:

- ✅ 引入「文件束(Bundle)」作为**编排聚合层**:orchestrator 对一束文件做**一次** CLAIM / lease / REPORT / outbox 生命周期,把控制面往返从 O(N) 降到 O(N/K)。
- ✅ 束内每个文件仍是**独立 file-unit**:各自的幂等键、结果子记录、文件级 checkpoint、各自的 template/目标表/下游。
- ✅ 复用 ADR-040 到达组作为**分组种子**(到达组本就回答「哪些文件算一组」)。
- ✅ worker 束执行=内部循环 file-unit,**per-file try/catch**,部分失败不退整束。

**❌ 不做(明确边界)**:

- ❌ **不把多文件合并成一个共享幂等键的处理单元**:束是「编排容器」,不是「事务容器」。束内每文件保留独立幂等键(两层:bundle 键 + file 键),重跑跳过已完成文件。
- ❌ **不提供跨文件原子事务**(「N 个文件全成功才整体 commit」):那是下游 Workflow 聚合裁决的事(ADR 边界:平台「修业务数据」√ /「裁定业务对错」✗)。束的终态允许 `SUCCESS_WITH_FAILURES`。
- ❌ **不挑机器 / 不扩缩容**(ADR-027 红线):束是「编排粒度」优化,与节点资源编排、worker 选型无关。
- ❌ **不改单文件默认**:Bundle 是**可选路径**,默认仍是 1 task = 1 file。只有命中「小文件/多表洪峰」工作负载才启用。
- ❌ **不丢观测粒度**:不允许「一束一条记录、文件级看不见」——每 file-unit 必须有子记录(blast radius / 血缘 / 重跑定位)。

## 背景

### 现状:严格 1 task = 1 file = 1 partition

worker CLAIM 的粒度是 **partition**;IMPORT/EXPORT 是「一个文件 = 一个 `job_partition` = 一个 `job_task`」。文件内部有行级分片(ParseStep 按 `lineNo % N` 切片并行),但那是**单文件内并行**,orchestrator 不可见、不跨文件。`DefaultPartitionDispatchService` 对每个 partition `buildTask`,逐文件走完整 `CLAIM → EXECUTE → REPORT → outbox` 链。

这是**有意设计**,承重在三根墙上:

| 依据 | 单文件粒度保证 |
|---|---|
| **Claim 幂等** | `idempotency_key = jobInstanceId:partitionNo`,partition 独占;多文件共用则无文件级幂等 |
| **故障隔离** | 一个文件失败不牵连他文件;共用则一坏俱坏、整批阻塞 |
| **Checkpoint 续跑** | 每 stage 输出路径唯一(`PARSED_RECORDS_PATH`),崩了能续;混用定位不了 |
| **观测/审计** | `pipeline_step_run` 每文件每 stage in/out 一目了然 |

对**典型负载**(每个 bizDate 几个大文件),单文件是正确取舍。

### 触发本 ADR 的真实负载:低千~上万的 fan-out

两个真实场景,峰值单批 **低千~上万** 个单元:

1. **导入 fan-out**:大量小文件,**每个对应不同的表**(= 不同 jobCode/template,不是同一个 job 的分片)。
2. **导出/分发 fan-out**:一次性导出很多表 → 很多文件 → **分发到不同下游**。

代价:每个文件独立走一遍 `CLAIM / lease 续租 / DB 乐观锁 CAS / outbox / Kafka`,产生 **O(N)** 控制面 churn——**恰好撞上已实测的系统瓶颈**(瓶颈在控制面消费单线程 + claim/report 锁争用,PG 写有 10-15× 余量)。在 10⁴ 量级,光靠「把每个 task 做便宜些 / 提 launch 并发」可能不够。

### 为什么不能简单「多文件一个 task」

直接让一个 task 吃多文件、内部混着处理,会同时打破上面三根墙(幂等/隔离/checkpoint)。正确方向是**加一层包住单文件单元**,而不是替换它。

### 现有机制为何不直接覆盖

- **partition 模型**:`1 job_instance → N partition` 其实**launch 已经批量**(一次 launch 建 N 个 partition),但 ① claim/report 仍 per-partition(没批);② partition 假设是「同一个 job 的同构分片」,**装不下不同表/不同 template 的异构文件**。
- **到达组(ADR-040)**:回答「哪些文件算一组、何时齐」,但凑齐后**仍 per-file 触发**(每文件一个 job_instance),没有合并编排。
- **Workflow DAG**:多 job 依赖编排,由 orchestrator 串联,不合并控制面往返。

→ 缺的就是一层「**把 N 个异构单文件单元的编排合并成一次生命周期**」。

## 决策

### 1. 引入「文件束(Bundle)」编排聚合层

```
Bundle(父:1 次 CLAIM / lease / REPORT / outbox)        ← orchestrator 只见这一个生命周期
   ├── file-unit 1  (idempotency_key=bundle:fileNo, 结果子记录, file 级 checkpoint, 各自 template/表/下游)
   ├── file-unit 2
   └── file-unit K
```

- **orchestrator 侧**:claim/report/lease/outbox 按 **bundle** 一次 → task 数 **N → N/K**,直接削控制面 churn。
- **worker 侧**:领到 bundle → 内部循环 K 个 file-unit,逐个解析自己的 template、入自己的表 / 分发自己的下游;**每个 file-unit 留独立结果子记录**;per-file try/catch。
- **三根墙不破**:幂等=`bundle 键 + file 键` 两层(重跑跳过已完成 file-unit);隔离= worker per-file 隔离 + 部分失败不退整束;checkpoint= 束内文件级续跑(已完成 file-unit 不重做);观测=父束记录 + 子 file-unit 记录。

### 2. 落地形态:**扩展 `job_partition` 优先**,新建实体为备选

**方案 A(推荐)——把 partition 升级成「束内 file-unit」,新增「束 = 一组 partition」的组生命周期**

- `job_partition` 增 per-file 绑定列(`source_file_id` / `template_code` / `target_ref` 等),让同一束下的 partition 可**异构**(不同表/模板)。
- 新增「束」标识(`bundle_id` 或复用 `job_instance` 作束容器):一个 job_instance 容纳 K 个异构 partition,**组认领 / 组上报**——worker 一次 claim 整束,一次 report 回 per-partition 结果数组。
- 复用现有 partition 状态机(READY→RUNNING→SUCCESS/FAILED)做 file-unit 终态;束终态由子 partition 汇总(见 §3)。
- **优点**:复用 partition 幂等键、版本 CAS、监控(`job_partition` / `pipeline_step_run`),改动面集中在「launch 异构成组 + claim/report 批量化」。

**方案 B(备选)——全新 `file_bundle` + `file_bundle_item` 两表**

- 语义最干净,但要新造一套状态机 + 幂等 + 监控,与 partition 模型并行维护,**重复造轮子**。仅当方案 A 扩展 partition 被证明语义冲突时才走。

> 评审需拍板 A/B。倾向 A:partition 本就是「一个 job_instance 下被独立认领的执行单元」,把它从「同构分片」放宽到「异构 file-unit」+ 加组生命周期,是最小增量。

### 3. 部分失败语义(必须先定)

- 束终态:`SUCCESS`(全成)/ `SUCCESS_WITH_FAILURES`(部分成)/ `FAILED`(全败或前置失败)。
- 失败 file-unit:**记录** + 可**单独重试**(失败 file-unit 提升为独立 retry partition / task,或下一束 attempt 只跑剩余)。**不**因单文件失败整束回滚。
- 与现有 `PARTIAL_FAILED`(job_instance 级)语义对齐复用,不新造同义态。

### 4. 分组策略(必须先定)

成束的输入边界,候选(可组合):

- **到达组种子**(ADR-040):一个 `(tenant, fileGroupCode, bizDate)` 到达组 = 一束(天然语义)。
- **(bizDate, jobType/category)** 聚类:同类小文件成束。
- **大小上限**:`≤K 个文件` 且 `≤X MB / ≤Y 行`——防一束跑太久撑爆 lease;超限自动切多束。
- **同表 only vs 允许混表**:允许混表(每 file-unit 自带 template),但**混表时部分失败的运维定位更难**,需子记录足够清晰。

### 5. 幂等 + lease/checkpoint

- **两层幂等键**:`bundle_key`(束级,防重复 launch 整束)+ `file_key`(file-unit 级,束内重跑跳过已完成)。
- **lease**:按束认领,但**心跳要细到 file-unit 进度**(大束跑得久,lease 续租 + checkpoint 要随 file-unit 推进刷新,否则误判 worker 死)。复用 `ActiveTaskLeaseRegistry` 心跳,带束内进度。

## 改动点(落地后,非本 PR)

- **batch-orchestrator**:
  - launch/plan:`DefaultSchedulePlanBuilder` / `DefaultPartitionDispatchService` 支持「成束 + 异构 partition」;一次插入束 + K 个 partition。
  - claim/report:`TaskController` / `DefaultPartitionLifecycleService` 支持**组认领 / 组上报**(report 携带 per-file 结果数组,事务内批量推进 K 个 partition + 写一条束级 outbox)。
- **batch-worker-core / import / export**:束执行循环(per-file try/catch + 子记录 + 文件级 checkpoint 续跑);export/dispatch 侧束生成 M 文件 + 保留**每文件 dispatch 回执**。
- **DB**:方案 A → `job_partition` 加 per-file 绑定列(+ 同步 archive 冷表镜像,`ArchiveSchemaDriftCheck` 启动 fail-fast);束容器标识。**改 UNIQUE/PK 前必 `grep 'on conflict'` 核幂等契约**(主链承重墙)。
- **console-api / FE**:监控页支持「束 → file-unit」下钻;不丢现有粒度。

## 实施分阶段

1. **Phase 0 — 设计定稿**:本 ADR 评审拍板 §决策 4 个开放问题(A/B 形态、部分失败语义、分组策略、幂等/lease)。
2. **Phase 1 — 控制面只读批量化(低风险先行)**:不改 worker 模型,先把 launch/claim/report/outbox 的**操作**批量化(bulk insert partition、多行 claim、outbox 批写),验证在低千量级是否已够。这是「杠杆 1」,可能先把压力压下去、推迟 Phase 2。
3. **Phase 2 — Bundle 聚合层(命中上万才上)**:实现束生命周期 + 异构 partition + worker 束执行 + 监控下钻。仅当 Phase 1 实测仍撞控制面墙时推进。
4. **Phase 3 — export/dispatch 侧 fan-out 束**:导出多表 → 一束生成 M 文件 + per-file 扇出回执。

## Consequences

- **正面**:控制面 churn O(N)→O(N/K),直击已知瓶颈;单文件三根墙保留;到达组/Workflow 既有能力不动。
- **负面/成本**:partition 模型从「同构分片」放宽到「异构 file-unit」增加状态/监控复杂度;部分失败语义 + 两层幂等是新增认知负担;束大小/lease 需调参。
- **风险红线**:① 不得让束变成共享幂等的「大事务」(破隔离);② 不得丢 file-unit 级观测;③ 改 partition UNIQUE/PK 必核全仓 `ON CONFLICT` 幂等契约。

## 开放问题(评审必答)

1. **形态 A(扩展 partition)还是 B(新建 file_bundle 表)?** —— 倾向 A。
2. **部分失败**:失败 file-unit 是「独立 retry」还是「下束 attempt 跑剩余」?束 `SUCCESS_WITH_FAILURES` 是否需人工确认?
3. **分组策略**:以到达组为唯一种子,还是再加 (bizDate,jobType) + 大小上限?允许混表吗?
4. **是否先只做 Phase 1(操作批量化)**、把 Phase 2 留作「实测撞墙才上」的条件触发?(避免过度工程)
