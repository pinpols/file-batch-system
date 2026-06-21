# ADR-046 · 文件束聚合 —— 在单文件单元之上加一层批量编排(File Bundle Aggregation)

- **Status**: Proposed(**方向已定 2026-06-21**:见 §已决;4 个开放问题已拍板,待实施分阶段逐 PR)
- **Related**: ADR-040(清单驱动到达组,**分组种子之一**)、ADR-002(transactional outbox)、ADR-027(资源亲和范围红线,**边界对照**)、配置批量导入(`/config/tenant-package` Excel 配置包,**配置模型复用**)、`docs/analysis`(吞吐瓶颈实测:瓶颈在控制面消费 + claim/report 争用,非 PG)、PR #465(launch 消费并发 +50%)
- **Plan**: 本 PR 仅设计文档。**架构级**——引入新的编排聚合单元 + 动 orchestrator 的 claim/report 粒度(orchestrator 是唯一状态主机)。§已决 已拍板 4 个方向问题 + 配置模型,后续分阶段逐 PR 落地,**先定语义再写码**。

## 范围边界(实施 PR 必答)

判定提问:**「这是把 N 个『单文件交付单元』的**编排**批量化(每个文件仍是独立、可幂等、可隔离、可观测的单元),还是把多个文件**揉成一个不可分的处理单元**(共享一份幂等/一个失败域)?」**

- 前者(编排批量化、底层仍单文件)→ **属本 ADR**。
- 后者(多文件混成一个处理单元)→ ❌ **不属**,会破坏 ADR-001/主链的幂等与故障隔离承重墙。

**✅ 做**:

- ✅ 引入「文件束(Bundle)」作为**编排聚合层**:orchestrator 对一束文件做**一次** CLAIM / lease / REPORT / outbox 生命周期,把控制面往返从 O(N) 降到 O(N/K)。
- ✅ 束内每个文件仍是**独立 file-unit**:各自的幂等键、结果子记录、文件级 checkpoint、各自的 template/目标表/下游。
- ✅ **配置不 per-表**:一束作业 = **一个** job 定义(`BUNDLE_IMPORT` / `BUNDLE_DISPATCH`),不是 N 个 job;每次提交带 file→template manifest(数据,非配置)。模板 per-表-schema(批量导入、schema 同可复用)。详见 §配置模型。
- ✅ 分组种子=**用户单次提交集合**(request-scoped,主场景)**或凑齐的到达组**(ADR-040,opt-in);两者都是「已知集合 → 束」,到达组现有 per-file 默认不变(详见 §兼容性)。
- ✅ 省 churn 靠 **claim/report 多行批量**(一次领/报 K 个**独立** partition);worker 仍按现有 per-partition 方式处理,**不引入束状态机 / 束执行循环 / 束级 lease**——partition 三根墙原样。

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

### 1. 文件束 = launch + claim/report 批量化,partition 仍独立(**无新生命周期**)

> ⚠️ 关键(2026-06-21 评审收敛):省控制面 churn 靠「**批量认领 / 批量上报**」,**不靠**把束做成一个有内部循环的大 task。partition 还是各自独立的执行单元,束只是它们的**编排批量化**。

```
一个 job_instance(= 用户一次提交的束)
   ├── partition 1   (file→template→表;独立认领 / 执行 / 报告 / 重试 / lease / 幂等)
   ├── partition 2   ...
   └── partition K
worker 一次 claim K 个 partition、处理完一次 report K 个  →  控制面往返 O(N)→O(N/K)
```

- **orchestrator 侧**:launch 一次建束 + K 个 partition;claim/report 支持**多行**(一次领 / 报 K 个),outbox 批写。降的是**往返次数**,不是把 K 个文件合并成一个不可分单元。
- **worker 侧**:批量领到 K 个 partition 后,**每个 partition 照现有方式独立处理**(各自 template/表/下游、各自 checkpoint、per-partition try/catch),处理完批量报。**不引入「束 task 内部循环 / 束状态机 / 束执行生命周期」。**
- **三根墙原样不动**:partition 本身不变,所以幂等(`jobInstanceId:partitionNo`)、隔离(partition 独立失败/重试)、checkpoint(partition 级)全部沿用,**零新增语义**。

### 2. 落地形态:扩展 `job_partition`(异构 + 批量领报),**不新建实体、不加束状态机**

- `job_partition` 增 per-file 绑定列(`source_file_id` / `template_code` / `target_ref`),让同一 `job_instance` 下的 partition 可**异构**(不同表/模板)。
- 新增束作业类型 `BUNDLE_IMPORT` / `BUNDLE_DISPATCH`,launch 按提交 manifest 把束**展成 N 个异构 partition**;存量普通 job 一字不动。
- claim/report 加「**多行**」变体(一次领 / 报 K 个 partition),复用现有 partition 状态机(READY→RUNNING→SUCCESS/FAILED)、版本 CAS、幂等键、监控(`job_partition` / `pipeline_step_run`)。
- **明确不做**(这些正是「改动太大」的来源,砍掉):不新建 `file_bundle` 表、不加束级状态机、不加 worker 束执行生命周期。束的「容器」就是 `job_instance`,束的「终态」就是现有 partition 汇总态(`PARTIAL_FAILED` 等),**不造同义物**。

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

### 5. 幂等 + lease(**沿用现有,不新增层**)

- **幂等**:就是现有 partition 幂等键 `jobInstanceId:partitionNo`——`job_instance` 是束、partition 是文件,**天然两层、无需新键**。重复 launch 整束由 job_instance dedup 兜;束内重跑跳过已 SUCCESS 的 partition(现状即如此)。
- **lease**:**仍 per-partition**(现状不变)。worker 批量领 K 个 partition,但每个 partition 各自续租/心跳——**没有「大束 task 跑很久撑爆一个 lease」的问题**(那是被砍掉的重方案才有的)。

## 配置模型(为什么不 per-表配置)

运行时聚合解决「N 个 task 压垮 orch」,但用户的真实痛点还有一层:**要不要手配 N 个 job 定义?** 答案:**不**。

- **job 定义:N → 1**。不为每张表建一个 job,而是**一个束作业定义**(`BUNDLE_IMPORT` / `BUNDLE_DISPATCH`)。它不绑死单张表,而是「按提交清单,把每个文件导到它对应的表/模板」。
- **每次提交带一个 manifest**(`file → templateCode → 目标表` 的映射,导出侧是 `表 → 文件 → 下游渠道`)——这是**数据,不是配置**:用户提交文件集合时一起给,或按文件名约定/ADR-040 批次清单推导。**不预先建 job**。
- **模板(template)仍 per-表-schema**:不同表的字段映射/格式/校验本质不同,绕不开(header 自动映射生产级不可靠)。但 ① 复用既有**配置批量导入**(`/config/tenant-package` Excel 配置包)一次导 M 个模板;② **schema 相同的表共用一个模板**(模板参数化目标表名),故 M 往往 < N。
- **导出/分发侧同构**:一个 `BUNDLE_DISPATCH` 作业 + per-下游 channel(可批量导)+ 提交时 `表→文件→下游` manifest。

**最终配置量** = `1 个束作业 + M 个模板(M≤N,批量导/可复用) + 每次提交一个 manifest(数据)`,**不是 N 个 job + N 处手配**。

> 边界:束作业**只编排**(挑文件→挑模板→派发),不内联业务映射逻辑;映射仍由 template/channel 承载(职责不挪、不膨胀)。

## 适用范围(跨 worker 类型 = 一套束骨架 + per-type 绑定 profile)

束不是「给每个 worker 类型各造一套」。拆两层:

**① 通用层(做一次,不挑 worker)**:

- **运行时 churn 优化**(多行 claim/report)在 orchestrator 层,所有类型的 partition/task 都经同一个 `TaskController` 认领 → **import/export/process/dispatch/atomic 全自动受益**,无 per-type 工作。
- **束骨架**(`job_instance` + K 个异构 partition + 提交 manifest)也通用:manifest 里每个 partition 声明自己的 `taskType/handler + 绑定`。

**② per-type 绑定 profile(只是 partition 绑定字段不同,非四套机制)**:

| 类型 | 是否需要束入口 | partition 绑定 profile |
|---|---|---|
| **IMPORT** | ✅ N 文件→N 表 | `source_file_id + template_code + 目标表` |
| **EXPORT** | ✅ M 表→M 文件 | `源表/查询 + template_code` |
| **DISPATCH** | ✅ M 文件→M 下游 | `file + channel_code/下游` |
| **PROCESS** | ⚠️ 看情况 | 若是 pipeline 中间 stage(import 后自动接)→**跟着这一束的 pipeline 走、不要独立入口**;仅当有「独立批量提交去 process」才加 profile |
| **ATOMIC** | ⚠️ 性质不同 | atomic 非文件(shell/sql/proc/http,ADR-029)。通用 churn 优化照样受益;**束**仅当用户成批提交原子任务(如「跑 1000 条 SQL」)才有意义,有则加 `原子任务 spec` profile,无则通用层已够 |

> 结论:`BUNDLE_IMPORT`/`EXPORT`/`DISPATCH`/`ATOMIC` 与其说是「四套机制」,不如说是**同一束骨架的四种 partition 绑定 profile**。实施顺序:通用骨架 + 多行 claim/report 先做(覆盖全类型 churn + 配置收口);绑定 profile 按真实 fan-out 逐个加(import/export/dispatch 已确认有,先做三个;process/atomic 按是否有批量提交再补)。

## 兼容性 + 与到达组(ADR-040)的关系

### 对现有的破坏:纯加法,默认不破

| 改动 | 为什么不破存量 |
|---|---|
| 新增 `BUNDLE_*` 束作业类型 | 存量普通 job 类型一字不动,走老路 |
| `job_partition` 加 3 列(per-file 绑定) | **可空 + 有默认**;存量 partition 这几列为 NULL,行为不变 |
| 多行 claim/report | **新增变体**,不替换;存量单 partition claim/report 路径原样在 |
| partition 状态机 / 幂等 / lease / worker 执行 | **一字不改**(轻量版核心) |

**实施期唯一核查项**(非设计缺陷,但 Phase 1 必扫):有没有共享代码**假设「一个 `job_instance` = 单 jobCode 同构」**(如 job_instance→job_definition 按 jobCode join 的查询/监控/报表)。bundle 实例的 partition 是异构的——① bundle 是新 job 类型,存量实例不碰此假设;② 须确认「同构假设只在 partition 级、不在 job_instance 级」,或共享代码能识别 bundle 实例后再放宽。

### 与到达组:正交 + 互补,不破

到达组与束管的是不同的事,**可组合**:

| | 管什么 | 回答 |
|---|---|---|
| **到达组(ADR-040)** | 输入门控:哪些文件算一组、何时齐 | **「何时触发」** |
| **文件束(本 ADR)** | 编排批量:一组已知单元怎么便宜地 launch/claim/report | **「怎么便宜地扇出」** |

- **默认行为不变**:到达组凑齐后 per-file 触发 N 个 job_instance 这条路**原样保留**,不开束就和现在一模一样。
- **到达组是束的第二个合法种子(opt-in)**:一个**凑齐的到达组 = 一个已知集合**,可**可选地**触发**一个束**(而非 N 个 job_instance)。机制同一套(已知集合 → 一个束),到达组只是除「用户提交集合」之外的另一个触发入口。无论走哪个种子,**都不动到达组现有 per-file 默认**。

> 修正:本 ADR 早先把到达组写成「不做种子/正交另一条线」**说保守了**。准确表述:**束有两个合法种子——① 用户单次提交集合(主场景),② 凑齐的到达组(opt-in)**,两者都是「已知集合 → 束」,非竞争。

## 改动点(落地后,非本 PR)

- **batch-orchestrator**:
  - launch/plan:`DefaultSchedulePlanBuilder` / `DefaultPartitionDispatchService` 支持束作业按 manifest **展成 N 个异构 partition**;一次插入。
  - claim/report:`TaskController` / `DefaultPartitionLifecycleService` 加**多行 claim / 多行 report**(一次事务推进 K 个独立 partition + outbox 批写)。**注:是批量操作,不是束级状态机。**
- **batch-worker-core / import / export**:worker 批量领 K 个 partition 后**按现有 per-partition 方式逐个独立处理**(不新增束执行循环 / 不改 partition 执行语义);export/dispatch 侧每 partition 生成自己的文件 + 保留**每文件 dispatch 回执**。
- **DB**:`job_partition` 加 per-file 绑定列(`source_file_id`/`template_code`/`target_ref`,+ 同步 archive 冷表镜像,`ArchiveSchemaDriftCheck` 启动 fail-fast)。**纯加列,不改 partition 的 UNIQUE/PK**(故不动 `ON CONFLICT` 幂等契约;若确需改则必 `grep 'on conflict'` 全量核)。
- **console-api / FE**:监控页支持「束(job_instance)→ partition」下钻——**复用现有 job_instance→partition 下钻**,不新增层。

## 实施分阶段

> **轻量化说明(2026-06-21)**:原 Phase 2 含「束生命周期 + worker 束执行」被评审判为**改动太大且不必要**——省 churn 靠批量领报即可,partition 仍独立。下列已收敛成**纯加法**,无重构。

1. **Phase 0 — 设计定稿**:✅ 已拍板(见 §已决)+ §配置模型 + §适用范围。
2. **Phase 1 — 通用束骨架 + 首个绑定 profile(IMPORT)+ launch/outbox 批量化(低风险先行)**:做**通用束骨架**(`job_instance` + K 异构 partition + manifest,**全类型复用**)+ IMPORT 绑定 profile;launch 一次展成 N 个异构 partition + outbox 批写。**运行时仍 N 个独立 partition**,claim/report 暂不动。验证低千量级是否已够(很可能够)。
3. **Phase 2 — 多行 claim / 多行 report(通用、命中上万再上)**:把 claim/report 改成一次领/报 K 个独立 partition,O(N)→O(N/K)。**通用、不挑 worker 类型**;仅此一项优化,partition 语义/状态机/worker 执行全不变;无束状态机、无 worker 束循环。
4. **Phase 3 — 补绑定 profile(EXPORT / DISPATCH,按需 PROCESS / ATOMIC)**:在通用骨架上加各类型的 partition 绑定 profile(见 §适用范围),**不是各造一套束机制**。export/dispatch 先做;process(独立批量提交才需)、atomic(批量原子任务才需)按真实场景补。

## Consequences

- **正面**:配置 N→1(束作业);控制面 churn O(N)→O(N/K)(批量领报);**全程纯加法**——partition 语义/状态机/幂等/lease/worker 执行**一律不动**,三根墙原样;到达组/Workflow 既有能力不动。
- **负面/成本**:partition 放宽到「可异构」(加 3 列 + 新作业类型),launch 展开 + claim/report 多行变体需测;束大小上限需调参。**比原重方案小一个量级**(无新表、无束状态机、无 worker 束生命周期)。
- **风险红线**:① 束只是「批量领报 + 异构 partition」,**不得**滑回「束=共享幂等大事务/内部循环 task」(破隔离、撑爆 lease);② 不丢 partition 级观测;③ 加列不碰 partition UNIQUE/PK(若碰必核全仓 `ON CONFLICT`)。

## 已决(2026-06-21 评审拍板)

场景锚定:**用户单次动作 = 导入多文件→多表 / 导出多表→多下游;集合在提交时已知**(非零散到达)。基于此:

1. **形态 = A(扩展 `job_partition`),束 = 「一个 job_instance + K 个异构、且各自独立的 partition」。** 集合提交即已知 → 一次 launch 建束 + K 个 partition,不等不回填。放宽「job_instance = 单 jobCode 同构分片」假设,新增束作业类型 `BUNDLE_IMPORT` / `BUNDLE_DISPATCH`,partition 携带 per-file `source_file_id + template_code + target_ref`;存量普通 job 不动。省 churn 靠 **claim/report 多行批量**(一次领/报 K 个独立 partition),复用 partition 状态机/幂等/监控。**不走 B(新建 file_bundle 表)、不加束状态机、不加 worker 束执行生命周期**——那些是评审判定的「改动太大」,砍掉(见 §1/§实施分阶段轻量化说明)。
2. **部分失败 = `SUCCESS_WITH_FAILURES` + 失败 partition 独立重试,不退整束。** 复用既有 `PARTIAL_FAILED` + 「retry partition」能力(坏文件=坏 partition,单独重跑、不碰已成功);**不**整束重跑,**不**需人工确认(与现有 partial-failure 一致)。
3. **分组种子 = 两个,都合法:① 用户单次提交集合(request-scoped,主场景)② 凑齐的到达组(opt-in)。** 允许混表(本就是设计),加大小上限 `≤K 个文件 且 ≤X MB/行` 自动切多束防撑爆 lease。到达组现有 per-file 默认不变(详见 §兼容性)。
4. **Phase 1 先行,Phase 2(多行 claim/report)按实测上。** 低千:Phase 1 配置收口 + launch/outbox 批量化,大概率够;上万:claim/report 往返仍顶,上 Phase 2 的**多行 claim/report**(只此一项优化,partition 全不变)。两个 Phase 都是纯加法,不再有原来那个重量级 Phase 2。

> 配置层结论见 §配置模型:1 束作业 + M 模板(批量导/可复用)+ manifest,不 per-表配 job。
