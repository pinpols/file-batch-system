# 批量日 (Business Day) & 作业实例化 vs 业界 — 缺陷盘点 2026-05-03

> **范围**：本仓 `batch_day_instance` / `business_calendar` / `LaunchService` / `SchedulePlanBuilder` 等批量日生命周期与作业实例化机制，与 Apache Airflow / Spring Batch / DolphinScheduler / 银行核心批 / AWS Batch 对比。
> **方法**：grep + 关键文件全文读 + 与 V32/V62/V68 schema 交叉对照，不凭空猜。

---

## 1. 现状速览表 (机制 × 实现)

| 机制 | 现状 | 关键文件 |
|---|---|---|
| **biz_date 计算** | Quartz fire→`CalendarBizDateResolver` 按 cutoffTime 滚算（< cutoff = 昨天）+ 节假日 SKIP/PREV/NEXT roll；API 触发由调用方传 `bizDate`，不再 resolve | `CalendarBizDateResolver.java:42` `DefaultLaunchAdapterService.java:49` |
| **batch_day 创建** | **lazy upsert**：第一条命中 `(tenant,calendar,bizDate)` 的 `job_instance` 写入数据库时由 `LaunchBatchDayService.upsertBatchDayInstance` 顺手插入；无定时器预创建 | `LaunchBatchDayService.java:101` `DefaultLaunchService.java:164` |
| **cutoff** | 独立扫描器：每 60s 扫 OPEN 候选，本地时间过 cutoff 时单行 CAS 翻 CUTOFF；ShedLock 防双 leader | `BatchDayCutoffScheduler.java:38` |
| **late_arrival** | EVENT 触发路径专属：`routeLateArrivalIfNeeded` 判 cutoff 后 + 容忍窗口内→LATE_ACCEPTED 继续走；窗口外→DB CAS 把 trigger_type EVENT→CATCH_UP 路由补跑 | `LaunchBatchDayService.java:410` |
| **settle** | 60s 扫 CUTOFF/IN_FLIGHT，按 active/failed/total 计数推进：active>0→IN_FLIGHT；failed>0→FAILED+driveCatchUp；其余→SETTLED | `BatchDaySettleScheduler.java:101` |
| **catch_up_policy** | NONE/AUTO/MANUAL_APPROVAL；AUTO 走 `LaunchService.launch`，MANUAL 落 `trigger_request.status=ACCEPTED` 等审批 | `BatchDaySettleScheduler.java:161` `DefaultTriggerService.java:107` |
| **timezone 隔离** | 三层回退：calendar.timezone → fallbackZoneId → `BatchTimezoneProvider.defaultZone`；创建批次日时 `timezone_snapshot` 抓快照 | `BatchTimezoneProvider` `BatchDayInstanceEntity.timezoneSnapshot` |
| **job_instance.biz_date** | 100% 来自 `LaunchRequest.bizDate`（trigger 算好），与 `batch_day_instance` 关联是 `(tenant,calendar,bizDate)` 隐式联接（无 FK） | `DefaultLaunchService.java:197` |
| **作业实例化** | `prepareJobInstance` (T1) 单事务 insert `job_instance` + upsert `batch_day_instance`；`PartitionDispatchService` (T2) 走 `SchedulePlanBuilder` 算 partition 并 insert `job_partition`/`job_task` + outbox | `DefaultLaunchService.java:152` `DefaultPartitionDispatchService.java:91` |
| **partition fanout** | `ShardStrategy ∈ {NONE/STATIC/DYNAMIC/AUTO}`；DYNAMIC 走 `@Order` resolver chain（Explicit→Size→Runtime→Worker），第一个正值胜出；硬上限 256 | `DefaultSchedulePlanBuilder.java:99` |
| **idempotency** | `(tenant_id, dedup_key, run_attempt)` 唯一键；RERUN 取 max+1；时间轮路径 `dedup_key=tenant:job:fireTimeMs`，Quartz/API 路径 `tenant:job:fireTime` 字符串 | V62 `DefaultLaunchService.java:309` `DefaultTriggerService.java:268` |
| **rerun** | TriggerType.RERUN 短路 dedup 短路；`run_attempt` 递增；并发竞争由唯一键回退（`hasSqlStateInChain "23505"`） | `DefaultLaunchService.java:104,309` |
| **dependency** | **未实现**：grep 全仓 0 命中 `JobDependency` / `prerequisite` / `depends_on`；workflow 内的 DAG 是节点级 edge，没有 job-之间-job 的依赖 | （缺失） |

---

## 2. vs 业界差距矩阵

| 维度 | 本仓 | Airflow | Spring Batch | DolphinScheduler | 银行核心批 | AWS Batch |
|---|---|---|---|---|---|---|
| **实例化粒度** | `job_instance` (1个 trigger 1个 instance) + `job_partition` 内分片 | `DagRun` + `TaskInstance` (per task per run) | `JobInstance` + `JobExecution` (重跑生新 execution，instance 不变) | `process_instance` + `task_instance` | `日终批次 = N 个 step` 串行/并行 | `Job` + `Job Attempt` (array job 有 child) |
| **数据区间表达** | 单点 `bizDate: LocalDate`，无 interval | `data_interval_start/end` (半开区间)，`logical_date`（旧 `execution_date`）已 deprecated | `JobParameters` 任意 KV（无强制语义）| `process_instance.schedule_time` 单点 + 全局日历 | T+0 / T+1 / T+N，明确日切窗口 | 不内建（job 自己管） |
| **重跑能力** | RERUN trigger_type + `run_attempt` 序号 + `parent_instance_id` 链 | `clear` + `rerun`，重跑覆盖原 TaskInstance（不留 attempt 链）| `restart()` 接续上次 chunk；只对 FAILED/STOPPED 生效 | console "重跑" / "补数"，区分依赖重跑 | 灰皮书：日终重启从断点续跑 + 全量重跑 | `RetryStrategy` 自动重试，手工重跑生新 job |
| **日历隔离** | 多租户 `business_calendar` + 多租户 `batch_day_instance`；calendar 单挂 job_definition | `Calendar` 全局共享；DAG 绑 `schedule` 自带 timezone | 无内建日历，业务自管 | 多租户日历 + 节假日表 | 银行级日历核心组件，跨系统共享 | EventBridge cron 自带 TZ，无业务日历 |
| **跨日 / cutoff** | `cutoff_time` + `late_arrival_tolerance_min` + `OPEN/CUTOFF/IN_FLIGHT/SETTLED/FAILED` 五态 + `timezone_snapshot` | 无 cutoff 概念，靠 schedule 时间区分 run | 无 | `complement` 支持补数，但无 cutoff 状态机 | **核心强项**：日切 + 跑批窗口 + 日终结账三段式 | 无 |
| **跨 job 依赖** | **缺失** | `ExternalTaskSensor` / `Dataset` (新)| `Step.next()` 链或 `Flow` | console 拖拽依赖 + `task_dependency` 表 | 任务编排：A 完→B 启 | `dependsOn` (job 间) |
| **late arrival 处理** | 容忍窗口内 LATE_ACCEPTED；窗口外自动 EVENT→CATCH_UP CAS 翻型 | 无（事件触发 = 新 DagRun） | 无 | 无 | **核心强项**：晚到批次有专门通道 | 无 |

---

## 3. 细节问题 punch list

### 🔴 P0 — 核心可用性 / 数据正确性

#### 3.1 `calendar_holiday` 表无 `tenant_id`，违反多租隔离硬约束
- **位置**：`db/migration/V3__create_config_tables.sql:88-99`
- **现状**：`UNIQUE (calendar_id, biz_date)`，靠 `business_calendar.tenant_id` 间接保证；CLAUDE.md "多租隔离" 章明确禁止此模式
- **业界**：DolphinScheduler `t_ds_command` / Airflow 多租改造方案均带 `tenant_id` 直列
- **影响**：跨表 JOIN 才能租户过滤，PG planner 走不到 (tenant_id, calendar_code) 复合索引；未来分库 / partition 时要先补列；calendar 误删会让 holiday 行变孤儿
- **修法**：补 `tenant_id` 列 + 唯一键改 `(tenant_id, calendar_id, biz_date)`，参考 V84/V85 workflow_node/edge 的 tenant_id 补列 PR

#### 3.2 batch_day upsert 用 REQUIRES_NEW 独立事务，late_count/catchup_count 可能"虚增"
- **位置**：`LaunchBatchDayService.java:101` (`@Transactional(propagation = REQUIRES_NEW)`)
- **现状**：注释明确承认 "外层 T1 若因 job_instance 唯一键回滚，late_count / catchup_count 的+1 会留下（记录的是『尝试』而非『成功』）"
- **业界**：Airflow `dagrun.start_date` 与 task instance 同事务；DolphinScheduler `process_instance` 与 `command` 同库同事务
- **影响**：lateCount / catchupCount 不再是"业务真值"而是"尝试次数"，控制台展示给运维会造成困惑（"这天有 5 次晚到？"实际可能 4 次回滚）；SLA 看板若用此字段统计会偏高
- **修法**：要么把 `batch_day_instance` 的更新放回主事务（接受 CAS 冲突阻塞 launch，但需要 launch 整体重试），要么文档明确"counter 是尽力而为，不要拿来对账"

#### 3.3 `batch_day_instance` 与 `job_instance` 没有外键 / 强关联
- **位置**：`V32__add_batch_day_support.sql:46-67` `JobInstanceMapper.selectBatchDayMetrics`
- **现状**：`batch_day_instance` 用 `(tenant_id, calendar_code, biz_date)` 隐式关联 job_instance；job_instance 不存 `calendar_code`，要靠 `job_definition.calendar_code` JOIN 反查
- **业界**：DolphinScheduler `process_instance.schedule_time` 直接索引；银行核心批每条 `日终 step` 都带 `business_date` + `batch_id` 双索引
- **影响**：(1) `BatchDaySettleScheduler` 每 60s 跑 metric 聚合都要 3 表 JOIN；(2) `job_definition.calendar_code` 改值后历史 instance "漂离"原 batch_day（settle 找不到、catchup 漏算）；(3) 删除 calendar 不会级联清理 batch_day_instance
- **修法**：`job_instance` 加 `calendar_code` 直列（运行态不可变快照），settle metric 直读，避免 calendar 配置变更导致的历史漂移

#### 3.4 `late_arrival` 路由仅覆盖 EVENT，SCHEDULED/API/MANUAL 路径无窗口检查
- **位置**：`LaunchBatchDayService.java:410-413`（`triggerType() != TriggerType.EVENT` 直接 return request）
- **现状**：注释只说"EVENT 触发路径专属"，实际 SCHEDULED 严重 misfire 后到达、API 用旧 bizDate 重灌、MANUAL 操作员补单 —— 这些场景都可能出现 cutoff 后到达，但全部被无脑放行
- **业界**：银行批 cutoff 后任何渠道的同 bizDate 写入都进 "异常账" 通道；Airflow `data_interval_end` 是硬边界
- **影响**：cutoff 后 SCHEDULED misfire（drift 几小时）会直接落新 instance，late_count 不增加；day_status 已 SETTLED 还能加 instance，settle 永远漂移
- **修法**：`routeLateArrivalIfNeeded` 改为对所有 triggerType 都跑 cutoff/容忍窗口判断；MANUAL/API 单独决策（人工操作通常允许穿越，但要落审计）

#### 3.5 cutoff 后 batch_day → IN_FLIGHT/SETTLED 的批次能"无声 reopen"
- **位置**：`LaunchBatchDayService.java:201-284`（`updateExistingBatchDay` 内 `shouldReopen` + `withReopened`）
- **现状**：`shouldReopenBatchDay` 对 SETTLED/FAILED 都返回 true；只要后续触发命中（哪怕是 LATE_ACCEPTED），状态会被默默拉回 IN_FLIGHT，`settled_at` 重置为 null
- **业界**：Airflow `DagRun` SUCCESS 后不可逆；要重跑必须显式 clear；银行 settled 日只能"差错冲账"另开新批次
- **影响**：财务 / 监管类报告可能看到 "已结算的日期重新变 IN_FLIGHT"，对账丢锚点；audit log 虽然有记录，但 `day_status` 当前值已被覆盖
- **修法**：SETTLED 后禁 reopen，强制走 catch-up 新批次（带 `parent_batch_day_id` 链）；或者保留 reopen 但加单独的 `reopen_count` + 触发独立审批流

### 🟡 P1 — 功能表达力 / 运维体验

#### 3.6 没有 job-到-job 依赖（仅 workflow 内 DAG）
- **位置**：grep 全仓 0 命中（无 `JobDependencyResolver`、`prerequisite`、`depends_on` 业务列）
- **业界**：Airflow `ExternalTaskSensor` / `Dataset`；DolphinScheduler 工作流任务依赖另一工作流；银行核心批 1000+ step 全靠依赖图
- **影响**：跨 job 编排只能写入同一个 `workflow_definition`；多业务域分别 own 自己的 job 时无法表达"风控批必须等账务批 SETTLED"
- **修法**：新表 `job_dependency (tenant_id, downstream_job_code, upstream_job_code, condition_type)` + `LaunchValidationService` 启动前查 `batch_day_instance(upstream).day_status` 决定是否放行 / WAITING

#### 3.7 `bizDate` 仅 `LocalDate` 单点，缺数据区间语义（`data_interval_start/end`）
- **位置**：`LaunchRequest.bizDate: LocalDate`、`job_instance.biz_date: DATE`
- **业界**：Airflow `data_interval_start/end` 是核心模型（半开区间，恰好对齐窗口）；银行批用 `(tenant, business_date, accounting_period)` 三元组
- **影响**：(1) 小时级 / 周级 / 月末批必须自己在 params 里造区间字段，没有统一约定；(2) IMPORT/EXPORT worker 拿不到"这次跑的数据是 [2026-04-01 00:00, 2026-04-02 00:00)" 的强类型，全靠 `bizDate.atStartOfDay`+约定
- **修法**：`job_instance` 加 `data_interval_start/end TIMESTAMPTZ`（计算自 `bizDate` + `schedule_type` + `cutoff_time`），单点场景退化为 `[bizDate 00:00, bizDate+1 00:00)`

#### 3.8 partition fanout 算完就定，运行时无法 dynamic re-shard
- **位置**：`DefaultSchedulePlanBuilder.java:43-85`、`DefaultPartitionDispatchService.java:136-164`
- **现状**：T2 dispatch 时一次性算 partition_count，写完 `job_partition` 就锁死；某 partition 数据爆失败只能等 lease 超时
- **业界**：Spark dynamic allocation；Argo `withParam` 运行时展开；Spring Batch `Partitioner` 也是启动时定，但 `RemotePartitionHandler` 支持 chunk-level 再分
- **影响**：大 IMPORT 数据倾斜时单 partition 拖死整个 instance；缺少"运行时再 fork 子 partition"的能力
- **修法**：先在 worker `report` 时支持上报 `additionalPartitionsRequested`，orchestrator 拿到后在同 instance 下补 partition + outbox（独立 ADR）

#### 3.9 `business_calendar.cutoff_time` 不加区分，job 维度无 override
- **位置**：`business_calendar` 表只有日历级 cutoff；`job_definition` 无 `cutoff_override_time`
- **业界**：银行批不同 step（账务 / 风控 / 报表）有各自 cutoff；DolphinScheduler 任务节点可独立配 timezone
- **影响**：同租户多业务（账务 06:00 cutoff，报表 09:00 cutoff）必须建多套 calendar_code，配置膨胀；calendar 改 cutoff 影响所有挂载的 job
- **修法**：`job_definition` 加 `cutoff_offset_min`（基于 calendar.cutoff_time 偏移），保持 calendar 单点真相

#### 3.10 catch-up 仅按 `(jobCode, bizDate)` 1:1 单 job 重跑，无依赖串联
- **位置**：`BatchDaySettleScheduler.java:161-218` `driveCatchUp`
- **现状**：`selectBatchDayCatchUpCandidates` 拿 FAILED job 列表，逐条 `LaunchService.launch` 独立重跑
- **业界**：Airflow `clear` 默认带 downstream 重算；银行批补跑会拉整条依赖链
- **影响**：A→B→C 三连，A FAILED 触发补跑成功后，B/C（已 SUCCESS 但用了 A 的旧产出）不会重跑，数据脏
- **修法**：依赖于 §3.6 落地后扩展，sweep 时按依赖图拉子图

### 🟢 P2 — 取舍 / 演进观察

#### 3.11 batch_day 是 lazy upsert 而非定时预创建
- **位置**：`LaunchBatchDayService.java:118` (`if (existing == null) insertNewBatchDay`)
- **取舍**：lazy 模式简单、无空跑；预创建模式（DolphinScheduler 风格）允许"今天还没触发就能看见 OPEN 的 batch_day"，console 视图更直观
- **影响**：当天没任何 job_instance 就完全没有 batch_day_instance 行；运维想确认"明天的 batch_day 已就位"无入口；统计"全年完成多少批量日"要靠存在性反推
- **建议**：保留 lazy（避免无谓行），但加可选的 `batch_day_warmup` 任务（按 calendar 提前 1 天 INSERT OPEN 行），通过 properties 开关

#### 3.12 `bizDate` 由 trigger 单方面计算，orchestrator 不复算
- **位置**：`DefaultLaunchAdapterService.java:56` 算完 → orchestrator `LaunchValidationService` 直接信 `request.bizDate`
- **取舍**：避免双侧计算分歧，但 trigger 配置漂移（如 calendar timezone 改了）后旧 outbox event 重投会用旧值
- **业界**：Airflow scheduler 单方面算 logical_date 后写 DB，是 immutable 事实；与本仓一致
- **建议**：当前是合理的"single source of truth"取舍，文档化即可；`trigger_outbox_event.payload` 已经 freeze 了 `bizDate`，相当于事件溯源

---

## 4. 修复优先级建议

| 优先级 | 项 | 一句话理由 | 估时 |
|---|---|---|---|
| **P0-1** | §3.1 `calendar_holiday.tenant_id` | 已违反 CLAUDE.md 硬约束，schema migration 5 行 | 0.5d |
| **P0-2** | §3.4 late_arrival 覆盖所有 triggerType | EVENT-only 假设错误，cutoff 后 SCHEDULED misfire 是真实场景 | 1d |
| **P0-3** | §3.5 SETTLED 不可 reopen | 财务对账正确性硬要求；改 `shouldReopenBatchDay` + 补 reopen audit | 1d |
| **P0-4** | §3.3 `job_instance.calendar_code` 快照列 | 配置变更不污染历史 + settle 性能改善（少一次 JOIN） | 1d + 数据回填 |
| **P1-1** | §3.2 batch_day counter 语义文档化 | 短期文档回退；长期改主事务需配合 §3.4 | 0.5d 文档 |
| **P1-2** | §3.7 `data_interval_start/end` 引入 | 小时/周级批次落地的前置条件；单点场景兼容退化 | 2d |
| **P1-3** | §3.9 job-level cutoff override | 缓解不加区分 calendar 配置膨胀，向 §3.7 演进 | 1d |
| **P1-4** | §3.6 + §3.10 job-to-job 依赖 | 跨业务域编排刚需；独立 ADR + 新表，2026-Q3 立项 | 5-10d (ADR 级) |
| **P2** | §3.8 dynamic re-shard / §3.11 warmup | 性能 / 体验优化，不阻塞主链路；按需立项 | - |

**短期（本 sprint 可消化）**：P0-1/2/3/4 + P1-1/2/3 ≈ 7 人天，全部为 schema + 单点服务改动，无跨模块大刀。

**中期（独立 ADR）**：P1-4 与现有 workflow DAG 模型有边界争议，需先和 product 确认"跨 job 依赖" vs "拼大 workflow" 的产品语义再开工。
