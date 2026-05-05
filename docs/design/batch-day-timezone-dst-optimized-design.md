# 批量日、时区与冬夏令时优化设计

## 1. 设计定位

本文基于以下三份设计材料和当前系统实现做收口：

- [`batch-day-design.md`](./batch-day-design.md)
- [`batch-day-capability-design.md`](./batch-day-capability-design.md)
- [`timezone-and-dst-design.md`](./timezone-and-dst-design.md)

当前系统已经不是从零建设批量日能力。已具备：

- `business_calendar.timezone`
- `business_calendar.cutoff_time`
- `business_calendar.late_arrival_tolerance_min`
- `business_calendar.sla_offset_min`
- `batch_day_instance`
- `batch_day_instance.timezone_snapshot`
- `trigger_runtime_state.next_fire_time` 使用 `timestamptz`
- Java 侧 `trigger_runtime_state.nextFireTime` 使用 `Instant`
- `BatchTimezoneProvider`
- `CalendarBizDateResolver`
- `BatchDayCutoffScheduler`

因此后续优化重点不是重复增加基础字段，而是补齐四类语义：

```text
1. 批量日何时打开，以及是否允许跨批量日重叠执行；
2. cron / cutoff / data_interval 在 DST gap / overlap 下如何裁决；
3. 调度 fire 的幂等键如何区分“日批只跑一次”和“真实时间点每次都跑”；
4. worker、console、API 如何不反向污染业务日期裁决。
```

## 2. 总体原则

统一规则：

```text
事件时间：Instant / timestamptz，统一按 UTC 语义存储和比较
业务日期：LocalDate，由 business_calendar.timezone + cutoff_time 裁决
调度时间：cron + schedule timezone -> Instant，落 trigger_runtime_state.next_fire_time
批量日：batch_day_instance 是运行态一等对象，保存 timezone_snapshot
worker：只消费 orchestrator 下发的 bizDate / batchDayId，不自行计算业务日期
console：只展示转换后的时间，不参与裁决
```

禁止规则：

```text
禁止用 ZoneId.systemDefault() 裁决业务日期
禁止用 LocalDate.now() / LocalDateTime.now() 裁决 bizDate
禁止用固定 offset 表达业务时区，例如 +08:00 / -05:00
禁止把展示时区当作调度或批量日时区
```

允许规则：

```text
平台默认时区可以是 Asia/Shanghai，用于缺省兜底
业务日历时区必须用 IANA ZoneId，例如 Asia/Shanghai、America/New_York
同一平台可以承载多个业务时区的日历
```

## 3. 当前模型的保留与修正

### 3.1 保留当前已落地模型

以下能力保持不变：

```text
business_calendar.timezone
business_calendar.cutoff_time
batch_day_instance.timezone_snapshot
job_instance.calendar_code 快照
trigger_runtime_state.next_fire_time
CalendarBizDateResolver
BatchTimezoneProvider
BatchDayCutoffScheduler
LaunchBatchDayService
```

当前 `bizDate` 计算口径继续保持：

```text
fire_time_utc
  -> 转 business_calendar.timezone
  -> 取 localDate + localTime
  -> localTime < cutoff_time ? localDate - 1 : localDate
  -> 再按 holiday_roll_rule 调整
```

### 3.2 修正“批量日只由触发顺带创建”的弱点

当前批量日主要在 job launch 时 upsert。这个模型可以运行，但批量日作为平台对象不够主动。

优化后采用双入口：

```text
日切预开入口：BatchDayOpenScheduler 按日历创建下一个 batch_day_instance
触发兜底入口：DefaultLaunchService / LaunchBatchDayService 继续 upsert，防止漏建
```

语义：

```text
日切预开负责让批量日在控制台和门闩逻辑中提前可见；
触发兜底负责兼容手工/API/事件触发和历史行为。
```

推荐新增调度器：

```text
BatchDayOpenScheduler
```

扫描启用的 `business_calendar`，按各自 `timezone + cutoff_time` 判断是否需要为当前业务日创建 `batch_day_instance`。

创建时必须保存：

```text
tenant_id
calendar_code
biz_date
open_at
cutoff_at
sla_deadline_at
timezone_snapshot
```

`cutoff_at` 和 `sla_deadline_at` 应在创建时按 `timezone_snapshot` 计算并落库，避免后续日历配置变化导致历史解释漂移。

## 4. 当前能力缺口复核

本节按 [`batch-day-capability-design.md`](./batch-day-capability-design.md) 的能力项，对照当前 DDL / 代码后重新标注。状态含义：

```text
已具备：当前主链路已有表、服务、API 或调度器支撑；
部分具备：有基础表或局部能力，但缺策略、状态、审计、API 或闭环；
未具备：目前主要停留在设计态，未形成可用运行能力。
```

| 能力 | 当前状态 | 当前依据 | 主要缺口 |
|---|---|---|---|
| 调度初始化为 `trigger_runtime_state.next_fire_time` | 已具备 | `trigger_runtime_state.next_fire_time`、wheel reconciler | 缺本地计划时间审计字段 |
| 触发后创建 `job_instance / workflow_run / partition / task` | 已具备 | `DefaultLaunchService` 主链路 | 无 |
| `bizDate` 按 `timezone + cutoff_time` 计算 | 已具备 | `CalendarBizDateResolver`、`business_calendar.timezone/cutoff_time` | DST 策略未显式化 |
| `batch_day_instance` 建模 | 已具备 | V32 / V62，含 `timezone_snapshot`、`version` | 状态集偏少 |
| launch 时 upsert 批量日 | 已具备 | `LaunchBatchDayService` | 不是日切主动创建 |
| 批量日主动打开 | 未具备 | 未见 `BatchDayOpenScheduler` | 日切后无法先看到“当天批量日已打开” |
| 批量日 cutoff | 已具备 | `BatchDayCutoffScheduler` | DST gap/overlap 裁决未封装 |
| 批量日 settle | 已具备 | `BatchDaySettleScheduler` | 无 `SETTLING`，失败后治理策略不完整 |
| late arrival | 部分具备 | `late_arrival_tolerance_min`、`late_count`、`routeLateArrivalIfNeeded` | 缺按策略转跳批/等待/人工处理的闭环 |
| catch-up 补跑 | 部分具备 | `CATCH_UP`、审批、批量日补跑 API | 粒度/结果覆盖/配置版本策略不足 |
| 前一批量日门闩 | 未具备 | 未见 `day_rollover_policy` / `BatchDayGateService` | 核心日批无法平台级等待前一天 |
| calendar 级跨日策略 | 未具备 | 无 `day_rollover_policy` | 缺 `ALLOW_OVERLAP / WAIT_PREVIOUS_DAY / REJECT_IF_PREVIOUS_OPEN` |
| job 级跨日覆盖 | 未具备 | 无 `previous_day_dependency_scope` | 缺 `SAME_JOB / SAME_CALENDAR` 等 |
| 批量日跳批 | 未具备 | `batch_day_instance` 状态无 `SKIPPED` | 缺跳批 API、审计、依赖影响策略 |
| 批量日冻结 / 关闭 | 未具备 | 状态无 `FROZEN/CLOSED` | 缺阻止新实例启动的治理动作 |
| 人工释放等待 | 未具备 | 无 `MANUAL_RELEASED` 或 gate release 表 | WAIT 场景无法人工放行 |
| 批量日重开 | 部分具备 | `LaunchBatchDayService.withReopened` 仅内部 reopen FAILED | 缺显式 API、审批、审计和状态 |
| DAG / workflow 依赖 | 部分具备 | workflow DAG、node skip 已有 | 缺跨批量日、跨日历依赖 |
| 文件批量治理 | 部分具备 | 文件导入/导出/分发、治理表和 Console API 已有 | 文件到达等待、晚到策略、文件组等待未完全批量日化 |
| 幂等与重入 | 部分具备 | `idempotency_record`、`run_attempt`、`RERUN`、分区幂等键 | 缺策略化 `REJECT_DUPLICATE / CREATE_NEW_VERSION / ALLOW_PARALLEL` |
| 结果版本 | 未具备 | 未见结果版本主模型 | 补跑后“哪版有效”无平台统一裁决 |
| 配置版本治理 | 部分具备 | 有配置表、审计、部分审批 | 运行实例未系统绑定 config version |
| SLA 与告警 | 部分具备 | `sla_deadline_at`、timeout、告警基础 | 缺批量日未结算 SLA 告警闭环 |
| 资源限流 | 部分具备 | queue/quota/worker group 已有 | 缺按业务域、核心链路、跨日门闩联动限流 |
| 死信与补偿 | 部分具备 | dead letter、compensation、replay 基础 | 缺按批量日维度重放治理 |
| 观测与审计 | 部分具备 | 批量日查询、窗口查询、audit log | 缺阻塞原因、人工释放、跳批、冻结等视图 |
| 权限与审批 | 部分具备 | console 鉴权、审批命令基础 | 缺批量日高风险权限点和审批流 |

结论：

```text
当前是“触发驱动 + 批量日投影”的运行平台；
要达到文档目标，需要补齐“批量日驱动 + 策略门闩 + 人工治理 + 版本治理”。
```

### 4.1 2026-05-05 实施回填

本轮 7 步已落到分支 `codex/batch-day-lifecycle-closure`，并按阶段提交。当前状态从“设计缺口”更新为：

| 能力 | 实施状态 | 已落代码 / DDL | 仍待补齐 |
|---|---|---|---|
| 批量日主动打开 | 已完成后端能力 | `BatchDayOpenScheduler`；创建时落 `cutoff_at / sla_deadline_at / timezone_snapshot / dst_policy_snapshot` | Console 视图仍需接入“已打开但未触发作业”的查询展示 |
| 前一批量日门闩 | 已完成后端能力 | V100 `day_rollover_policy`、`previous_day_dependency_scope`、`batch_day_waiting_launch`；`BatchDayGateService` 支持 `ALLOW / WAIT / REJECT` | 细粒度 `SAME_JOB / SAME_JOB_GROUP` 仍未完全展开，目前非 `INHERIT` 按等待前一批量日处理 |
| 批量日人工治理 | 部分完成 | V101 扩展 `SKIPPED / MANUAL_RELEASED`、`frozen`、操作快照字段；`BatchDayOperationService` 支持 `FREEZE / RELEASE / SKIP / REOPEN / CLOSE`；审计写 `job_execution_log` | 未新增独立 `batch_day_operation_audit` 表；未暴露 Console REST API；权限点、高风险审批、操作历史 UI 未接入 |
| DST 策略显式化 | 部分完成 | V102 `dst_gap_policy / dst_overlap_policy / dst_policy_snapshot`；`BatchDayTimePolicyResolver` 统一处理 cutoff gap / overlap | trigger cron 本地计划审计字段、Console DST 调整展示未做；`RUN_TWICE` 对 cutoff 降级为一次语义 |
| worker bizDate 兜底 | 已完成核心收口 | export `PrepareStep` 缺 `bizDate` 失败；import scanner 要求显式 `defaultBizDate`；生产 worker 路径不再用 `LocalDate.now()` 推断业务日 | import scanner 的显式日期仍是配置项，后续建议改为从文件命名规则或上游到达事件解析 |
| 补跑版本治理 | 部分完成 | V103 `job_definition_version`、`rerun_policy_snapshot`；`run_attempt` 已保证 RERUN 新建实例不覆盖历史；`params_snapshot` 增加 job definition version | 补跑请求层 `resultPolicy / configVersionPolicy` 尚未成为显式 API 参数；结果产物版本/生效版本和核心账务审批未统一平台化 |

## 5. 补齐能力的实施设计

### 5.1 数据模型增量

#### business_calendar

新增 calendar 级策略字段：

```sql
ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS day_rollover_policy VARCHAR(32) NOT NULL DEFAULT 'ALLOW_OVERLAP',
    ADD COLUMN IF NOT EXISTS dst_gap_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_AT_NEXT_VALID_TIME',
    ADD COLUMN IF NOT EXISTS dst_overlap_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_ONCE_EARLIER_OFFSET';
```

#### job_definition

新增 job 级前日依赖覆盖和补跑结果策略：

```sql
ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS previous_day_dependency_scope VARCHAR(32) NOT NULL DEFAULT 'INHERIT',
    ADD COLUMN IF NOT EXISTS rerun_result_policy VARCHAR(32) NOT NULL DEFAULT 'CREATE_NEW_VERSION';
```

#### batch_day_instance

扩展状态集和治理字段：

```sql
ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS blocked_reason VARCHAR(128),
    ADD COLUMN IF NOT EXISTS manual_release_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS manual_release_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS skip_reason VARCHAR(128),
    ADD COLUMN IF NOT EXISTS skip_comment VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS frozen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS frozen_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;
```

状态建议扩展为：

```text
OPEN
CUTOFF
IN_FLIGHT
BLOCKED
FROZEN
SETTLING
SETTLED
FAILED
SKIPPED
MANUAL_RELEASED
CLOSED
```

兼容要求：

```text
1. 现有 OPEN / CUTOFF / IN_FLIGHT / SETTLED / FAILED 语义保持不变；
2. BLOCKED 表示因前一批量日、文件、人工确认等前置条件阻塞；
3. FROZEN 表示人工冻结，不接受新的普通触发；
4. SKIPPED 是有记录地跳批，可作为后续批量日门闩的允许终态；
5. MANUAL_RELEASED 表示人工放行，允许后续批量日继续；
6. CLOSED 表示批量日关闭，不再接收普通新触发。
```

#### batch_day_operation_audit

当前已有 `job_execution_log` 可记录部分状态变化，但批量日高风险治理建议独立表，便于 Console 查询和审批追溯：

```sql
CREATE TABLE IF NOT EXISTS batch.batch_day_operation_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    biz_date DATE NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    operator_id VARCHAR(128) NOT NULL,
    operator_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128),
    comment VARCHAR(1024),
    approval_id BIGINT,
    request_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

典型 `operation_type`：

```text
BATCH_DAY_OPEN
BATCH_DAY_CUTOFF
BATCH_DAY_BLOCK
BATCH_DAY_RELEASE
BATCH_DAY_SKIP
BATCH_DAY_FREEZE
BATCH_DAY_UNFREEZE
BATCH_DAY_REOPEN
BATCH_DAY_CLOSE
BATCH_DAY_CATCH_UP
```

#### batch_day_waiting_launch

`WAIT_PREVIOUS_DAY` 不建议直接丢弃触发，也不建议创建已运行的 `job_instance`。推荐增加等待表：

```sql
CREATE TABLE IF NOT EXISTS batch.batch_day_waiting_launch (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    job_code VARCHAR(128) NOT NULL,
    biz_date DATE NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    trigger_type VARCHAR(32) NOT NULL,
    wait_reason VARCHAR(128) NOT NULL,
    launch_payload JSONB NOT NULL,
    wait_status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    released_at TIMESTAMPTZ,
    released_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

用途：

```text
前一批量日未结清时保留触发意图；
前一批量日 SETTLED / SKIPPED / MANUAL_RELEASED 后自动释放；
运维可人工释放或取消；
不污染 job_instance 运行态。
```

### 5.2 服务设计

#### BatchDayOpenScheduler

职责：

```text
1. 扫描 enabled business_calendar；
2. 按 calendar.timezone + cutoff_time 判断当前应打开的 bizDate；
3. 主动 upsert batch_day_instance；
4. 计算并保存 cutoff_at / sla_deadline_at / timezone_snapshot；
5. 写 batch_day_operation_audit。
```

幂等：

```text
以 (tenant_id, calendar_code, biz_date) 唯一键保证重复扫描只产生一行；
与 LaunchBatchDayService 并发时允许 insert 冲突后转读现有行。
```

#### BatchDayGateService

职责：

```text
1. 在 DefaultLaunchService 创建 job_instance 前执行；
2. 根据 job_definition.previous_day_dependency_scope 和 calendar.day_rollover_policy 判断是否允许启动；
3. 返回 ALLOW / WAIT / REJECT；
4. WAIT 写 batch_day_waiting_launch；
5. REJECT 写 audit 并返回明确错误；
6. ALLOW 继续原 launch 链路。
```

前一批量日允许状态：

```text
SETTLED
SKIPPED
MANUAL_RELEASED
```

前一批量日不允许状态：

```text
OPEN
CUTOFF
IN_FLIGHT
BLOCKED
FROZEN
FAILED
```

scope 解释：

| scope | 检查范围 |
|---|---|
| `NONE` | 不检查前一批量日 |
| `SAME_JOB` | 检查同一 `job_code` 前一 `bizDate` 的实例是否终态成功或被允许跳过 |
| `SAME_JOB_GROUP` | 检查同组作业前一 `bizDate` |
| `SAME_CALENDAR` | 检查前一 `batch_day_instance` 是否允许放行 |
| `CUSTOM_CHAIN` | 交给显式 DAG / dependency 配置裁决 |

#### BatchDayWaitingReleaseScheduler

职责：

```text
1. 扫描 WAITING 的 batch_day_waiting_launch；
2. 重新调用 BatchDayGateService；
3. 前置条件满足后将 wait_status 更新为 RELEASED；
4. 重新投递 LaunchRequest；
5. 写 audit。
```

要求：

```text
释放必须保留原 request_id / trace_id / idempotency 语义；
重复释放由 idempotency_record 或 waiting 表状态 CAS 兜底。
```

#### BatchDayOperationService

提供批量日人工治理动作：

```text
skipBatchDay
freezeBatchDay
unfreezeBatchDay
releaseBatchDay
reopenBatchDay
closeBatchDay
catchUpBatchDay
```

高风险动作默认要求：

```text
幂等键
操作原因
审计记录
权限点
可选审批
```

### 5.3 API 设计

Console 建议新增或扩展：

```text
POST /api/console/jobs/batch-days/{bizDate}/skip
POST /api/console/jobs/batch-days/{bizDate}/freeze
POST /api/console/jobs/batch-days/{bizDate}/unfreeze
POST /api/console/jobs/batch-days/{bizDate}/release
POST /api/console/jobs/batch-days/{bizDate}/reopen
POST /api/console/jobs/batch-days/{bizDate}/close
GET  /api/console/queries/batch-days/{bizDate}/operations
GET  /api/console/queries/batch-days/waiting-launches
```

请求体统一包含：

```text
tenantId
calendarCode
reasonCode
comment
approvalId?
jobCodes?
effectivePolicy?
```

新增响应字段：

```text
previousDayGateStatus
blockedReason
manualReleaseAt
manualReleaseBy
skipReason
frozenAt
closedAt
operationSummary
waitingLaunchCount
```

修改 `/api/console/**` 后必须同步前端：

```text
../batch-console/src/types/api.generated.ts
../batch-console/src/api
../batch-console/src/views/scheduler 或 monitor 对应页面
```

### 5.4 权限与审批点

建议新增权限点：

```text
batch_day.view
batch_day.skip
batch_day.freeze
batch_day.release
batch_day.reopen
batch_day.close
batch_day.catch_up
batch_day.waiting_launch.manage
```

默认需要审批：

```text
batch_day.skip
batch_day.release
batch_day.reopen
batch_day.close
核心 calendar 的 batch_day.catch_up
```

### 5.5 结果版本与配置版本

补跑能力要补齐两个裁决：

```text
补跑用哪个配置版本？
补跑结果哪一版生效？
```

短期落地建议：

```text
1. job_instance 已有 run_attempt，继续作为运行尝试序号；
2. 新增 result_version / effective_result 标记可放在业务结果表或结果索引表，不强塞 job_instance；
3. 补跑请求 params 明确 configVersionPolicy：
   USE_ORIGINAL_CONFIG / USE_LATEST_CONFIG / USE_SPECIFIED_VERSION；
4. 补跑请求 params 明确 resultPolicy：
   CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE；
5. Console 展示 originalInstanceId、rerunInstanceId、runAttempt、resultPolicy。
```

不建议直接覆盖历史输出文件或业务结果。默认策略：

```text
CREATE_NEW_VERSION
```

## 6. 补齐后的目标状态机

### 6.1 批量日主状态机

```text
OPEN
  -> CUTOFF
  -> IN_FLIGHT
  -> SETTLING
  -> SETTLED

OPEN / CUTOFF / IN_FLIGHT
  -> FROZEN
  -> OPEN / CUTOFF / IN_FLIGHT

OPEN / CUTOFF / IN_FLIGHT / FAILED
  -> SKIPPED

CUTOFF / IN_FLIGHT / SETTLING
  -> FAILED
  -> IN_FLIGHT   (显式 catch-up 或 reopen)
  -> SETTLED

OPEN / CUTOFF
  -> BLOCKED
  -> MANUAL_RELEASED
  -> IN_FLIGHT

SETTLED
  -> CLOSED
```

### 6.2 后续批量日放行规则

允许自动放行：

```text
SETTLED
SKIPPED
MANUAL_RELEASED
```

禁止自动放行：

```text
OPEN
CUTOFF
IN_FLIGHT
SETTLING
FAILED
BLOCKED
FROZEN
CLOSED
```

`CLOSED` 是否放行不写死。推荐：

```text
如果 closeReason=TREAT_AS_SETTLED，可放行；
否则需要 MANUAL_RELEASED。
```

## 7. 批量日前日门闩

### 7.1 不做全局硬阻塞

系统不应全局强制“今天必须等昨天全部 SETTLED 才能启动”。文件导入、导出、分发、技术任务可能天然按 `bizDate` 隔离，可以跨批量日并行。

但账务、清算、余额、库存、日终总控等核心日批必须支持前一批量日门闩。

### 7.2 推荐新增 calendar 默认策略

建议后续迁移在 `business_calendar` 增加：

```sql
ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS day_rollover_policy VARCHAR(32) NOT NULL DEFAULT 'ALLOW_OVERLAP';
```

可选值：

| 值 | 含义 |
|---|---|
| `ALLOW_OVERLAP` | 允许不同 bizDate 的批量日重叠运行 |
| `WAIT_PREVIOUS_DAY` | 前一批量日未结清时，当前批量日触发进入等待 |
| `REJECT_IF_PREVIOUS_OPEN` | 前一批量日未结清时，拒绝本次启动并写审计 |

允许放行的前一批量日状态：

```text
SETTLED
SKIPPED
MANUAL_RELEASED
```

不允许自动放行：

```text
OPEN
CUTOFF
IN_FLIGHT
FAILED
BLOCKED
FROZEN
```

### 7.3 推荐新增 job 覆盖策略

建议后续在 `job_definition` 增加：

```sql
ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS previous_day_dependency_scope VARCHAR(32) NOT NULL DEFAULT 'INHERIT';
```

可选值：

| 值 | 含义 |
|---|---|
| `INHERIT` | 继承 calendar 的 `day_rollover_policy` |
| `NONE` | 不等待前一天 |
| `SAME_JOB` | 只等待同一 job 的前一业务日完成 |
| `SAME_JOB_GROUP` | 等同组作业前一业务日完成 |
| `SAME_CALENDAR` | 等整个 calendar 前一业务日结清 |

落地位置：

```text
DefaultLaunchService / LaunchBatchDayService
  -> BatchDayGateService
  -> 允许启动 / 进入等待 / 拒绝并审计
```

不建议让 trigger 自己做门闩裁决。trigger 可以到点发起，最终是否创建 `job_instance` 由 orchestrator 判断。

## 8. DST 策略

### 8.1 策略默认值

中国大陆、日本、新加坡、香港通常不涉及 DST，但平台接入欧美、澳洲业务时必须可解释。

推荐默认策略：

```text
DST gap：RUN_AT_NEXT_VALID_TIME
DST overlap：RUN_ONCE_EARLIER_OFFSET
```

原因：

```text
gap 时日批更怕漏跑，顺延到下一个合法本地时间；
overlap 时日批语义通常是同一个本地计划只跑一次，默认取较早 offset。
```

### 8.2 推荐新增配置字段

短期可以先代码常量固化。若要支持租户或日历差异化，建议在 `business_calendar` 增加：

```sql
ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS dst_gap_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_AT_NEXT_VALID_TIME',
    ADD COLUMN IF NOT EXISTS dst_overlap_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_ONCE_EARLIER_OFFSET';
```

可选值：

| 字段 | 值 |
|---|---|
| `dst_gap_policy` | `RUN_AT_NEXT_VALID_TIME` / `SKIP` / `FAIL_FAST` |
| `dst_overlap_policy` | `RUN_ONCE_EARLIER_OFFSET` / `RUN_ONCE_LATER_OFFSET` / `RUN_TWICE` |

### 8.3 cutoff 计算

`cutoff_time` 是日历本地时间。计算 `cutoff_at` 时必须使用：

```text
biz_date + 1 day
cutoff_time
timezone_snapshot
```

得到 `ZonedDateTime` 后转 `Instant` 落库。

如果 `cutoff_time` 落入 DST gap：

```text
RUN_AT_NEXT_VALID_TIME：cutoff_at 顺延到下一个合法时间
SKIP：该批量日进入 SKIPPED 或 WAIT_MANUAL_DECISION
FAIL_FAST：记录配置错误并阻断该日历日切
```

如果 `cutoff_time` 落入 DST overlap：

```text
RUN_ONCE_EARLIER_OFFSET：取较早 offset 对应 Instant
RUN_ONCE_LATER_OFFSET：取较晚 offset 对应 Instant
RUN_TWICE：不建议用于 cutoff；若配置则按 RUN_ONCE_EARLIER_OFFSET 降级并报警
```

cutoff 是批量日边界，不是作业触发点，因此不建议支持 `RUN_TWICE`。

### 8.4 cron 计算

cron 解释顺序：

```text
job_definition.timezone
  -> business_calendar.timezone
  -> batch.timezone.default-zone
```

当前系统已有 `job_definition.timezone`，可以继续作为 schedule timezone 使用；文档中提到的 `schedule_timezone` 不必马上新增字段，除非未来要明确区分“作业展示/业务时区”和“调度时区”。

cron 下次触发计算必须通过 DST-aware 的库或适配器完成，禁止手写：

```text
lastFire.plusDays(1)
```

## 9. fire identity 与幂等键

DST overlap 会让同一个本地时间对应两个不同 UTC Instant。系统必须明确“要不要跑两次”。

建议引入调度 fire 分类：

```text
DAILY_BUSINESS：日批语义，同一 bizDate / job 通常只跑一次
TECHNICAL_INTERVAL：技术轮询语义，每个真实 UTC fire point 都可跑
```

### 9.1 日批类唯一键

日批类推荐幂等键：

```text
tenant_id
job_code
calendar_code
biz_date
trigger_type
```

如需区分同日多计划，再加入：

```text
schedule_slot_code
scheduled_local_time
```

不要只用 `scheduled_fire_time_utc`，否则 DST overlap 可能生成两次同一业务日实例。

### 9.2 高频技术类唯一键

技术轮询类推荐幂等键：

```text
tenant_id
job_code
scheduled_fire_time_utc
```

这种模式允许 overlap 的两个 UTC 触发点都创建实例。

### 9.3 建议落库审计字段

`trigger_runtime_state` 当前已有 `next_fire_time`，但缺少本地计划审计信息。建议后续增加：

```sql
ALTER TABLE batch.trigger_runtime_state
    ADD COLUMN IF NOT EXISTS schedule_timezone VARCHAR(64),
    ADD COLUMN IF NOT EXISTS scheduled_local_date DATE,
    ADD COLUMN IF NOT EXISTS scheduled_local_time TIME,
    ADD COLUMN IF NOT EXISTS fire_sequence INTEGER NOT NULL DEFAULT 1;
```

用途：

```text
排查 DST gap / overlap
解释为何 UTC 时间与本地计划不同
构造稳定 fire identity
```

## 10. worker 与 data interval

worker 不裁决 `bizDate`，但可能根据 `bizDate` 兜底生成数据窗口。这个兜底必须收敛。

推荐规则：

```text
1. orchestrator 下发 bizDate、calendarCode、batchDayId、dataIntervalStart、dataIntervalEnd；
2. worker 优先使用 dataIntervalStart / dataIntervalEnd；
3. 如果为空，worker 只能按 bizDate 做业务参数兜底，不能用 LocalDate.now()；
4. worker 记录 startedAt / finishedAt 使用 Instant.now(clock) 或统一 clock；
5. 文件命名、对象存储路径、SQL 参数都使用下发 bizDate。
```

当前需重点整改的风险点：

```text
worker-export PrepareStep 中 payload/context 都没有 bizDate 时回退 LocalDate.now()
```

优化方向：

```text
缺少 bizDate 时失败并提示上游上下文不完整；
或仅在本地 demo profile 下允许 LocalDate.now() 兜底。
```

## 11. console 展示

Console 不参与业务裁决，但应帮助排障。

批量日页面建议展示：

```text
bizDate
calendarCode
timezoneSnapshot
cutoffAtUtc
cutoffAtLocal
slaDeadlineAtUtc
slaDeadlineAtLocal
dayStatus
lateCount
catchupCount
previousDayGateStatus
dstPolicy
```

调度页面建议展示：

```text
nextFireTimeUtc
scheduledLocalDate
scheduledLocalTime
scheduleTimezone
dstAdjustment
lastFireStatus
misfireCount
```

前端类型在修改 `/api/console/**` 后需要重新生成：

```text
../batch-console/src/types/api.generated.ts
```

## 12. 推荐落地顺序

### Phase 1：文档与守护测试

状态：已完成。提交 `955c993a`。

```text
1. 将本文作为批量日/时区/DST 的主设计入口；
2. 给 CalendarBizDateResolver 增加跨时区、DST gap、DST overlap 单测；
3. 给 BatchDayCutoffScheduler 增加 America/New_York gap/overlap 用例；
4. 扫描 worker 中用于业务日期的 LocalDate.now() / LocalDateTime.now()。
```

### Phase 2：批量日主动打开

状态：后端能力已完成。提交 `4a4167b2`。Console 展示仍待接入。

```text
1. 新增 BatchDayOpenScheduler；
2. 创建时落 cutoff_at / sla_deadline_at / timezone_snapshot；
3. 保留 launch 时 upsert 作为兜底；
4. Console 批量日视图展示“已打开但未触发作业”的批量日。
```

### Phase 3：前一批量日门闩

状态：后端能力已完成。提交 `98d57be2`。

```text
1. 增加 day_rollover_policy；
2. 增加 previous_day_dependency_scope；
3. 实现 BatchDayGateService；
4. 支持 WAIT / REJECT / ALLOW 三种结果；
5. 增加人工释放审计。
```

### Phase 4：批量日人工治理

状态：服务层与状态字段已完成，API / 权限 / 审批 / Console 未接入。提交 `072d2802`。

```text
1. 增加 batch_day_operation_audit；
2. 扩展 batch_day_instance 状态和治理字段；
3. 增加 skip / freeze / release / reopen / close API；
4. 增加权限点和高风险审批；
5. Console 展示操作历史与阻塞原因。
```

### Phase 5：DST 策略显式化

状态：cutoff 侧 DST 策略已显式化，trigger cron 审计字段和 Console 展示未接入。提交 `f34b2828`。

```text
1. 固化默认 DST 策略；
2. 必要时增加 dst_gap_policy / dst_overlap_policy；
3. trigger_runtime_state 增加 schedule timezone 和 scheduled local 字段；
4. Console 展示 DST 调整结果。
```

### Phase 6：worker 兜底收口

状态：已完成核心生产路径收口。提交 `94f969a5`。

```text
1. 禁止生产路径 worker 用本机日期补 bizDate；
2. 缺少 bizDate 直接失败或进入配置错误；
3. data interval 从 orchestrator 下发成为主口径。
```

### Phase 7：补跑版本治理

状态：实例级追溯已完成，补跑 API 策略参数与结果生效版本治理未接入。提交 `cc69a768`。

```text
1. 补跑请求显式 resultPolicy / configVersionPolicy；
2. Console 展示 originalInstanceId / rerunInstanceId / runAttempt；
3. 结果输出默认创建新版本；
4. 核心账务类补跑默认需要人工确认生效版本。
```

## 13. 验收矩阵

本轮按代码和针对性测试做验收，状态含义：

```text
通过：当前后端代码和测试已覆盖主要预期；
部分通过：核心服务/状态已完成，但 API、Console、审批或更细策略未闭环；
未通过：仍停留在设计目标，当前实现不能满足。
```

| 场景 | 输入 | 预期 | 当前状态 | 依据 / 说明 |
|---|---|---|---|---|
| 上海 cutoff 前 | `2026-05-05 05:30 Asia/Shanghai`, cutoff `06:00` | `bizDate=2026-05-04` | 通过 | `BatchDayOpenSchedulerTest#shouldOpenPreviousBizDateBeforeCutoff` |
| 上海 cutoff 后 | `2026-05-05 06:30 Asia/Shanghai`, cutoff `06:00` | `bizDate=2026-05-05` | 通过 | `BatchDayOpenSchedulerTest#shouldOpenCurrentBizDateAfterCutoff` |
| 纽约 DST gap cron | `America/New_York`, cron `02:30`，夏令时开始日 | 默认顺延到下一个合法本地时间 | 部分通过 | cutoff 侧由 `BatchDayTimePolicyResolverTest#shouldMoveGapCutoffToNextValidInstantByDefault` 覆盖；trigger cron fire 审计字段未落 |
| 纽约 DST overlap cron | `America/New_York`, cron `01:30`，冬令时切回日 | 日批默认只生成一次 | 部分通过 | cutoff overlap 侧由 `BatchDayTimePolicyResolverTest#shouldUseLaterOffsetWhenOverlapPolicyRequiresIt` 覆盖；cron 日批去重仍依赖现有 trigger / dedup 机制，未新增本地计划审计 |
| cutoff 落入 DST gap | `cutoff_time=02:30` | 默认顺延，写入实际 `cutoff_at` | 通过 | V102 + `BatchDayTimePolicyResolver`；gap 默认写入 transition instant |
| 前日未结清，ALLOW | 前一日 `IN_FLIGHT` | 当前作业允许启动 | 通过 | `BatchDayGateServiceTest#shouldAllowWhenCalendarAllowsOverlap` |
| 前日未结清，WAIT | 前一日 `IN_FLIGHT` | 当前启动进入等待，不创建运行中任务 | 通过 | `BatchDayGateServiceTest#shouldWaitWhenPreviousDayIsNotClosed`；写入 `batch_day_waiting_launch`，`trigger_request=WAITING` |
| 前日未结清，REJECT | 前一日 `IN_FLIGHT` | 拒绝并写审计 | 通过 | `BatchDayGateServiceTest#shouldRejectWhenCalendarRejectsOpenPreviousDay` |
| 批量日跳过 | `skipBatchDay`，原因 `HOLIDAY` | `day_status=SKIPPED`，后续门闩允许放行 | 通过 | `BatchDayOperationServiceTest#shouldSkipNonTerminalBatchDay`；gate 已把 `SKIPPED` 视为可放行 |
| 批量日冻结 | `freezeBatchDay` | 普通触发被拒绝或进入等待，catch-up/rerun 需显式允许 | 部分通过 | `BatchDayOperationServiceTest#shouldFreezeOpenBatchDay`；cutoff / settle 调度会跳过 frozen；launch gate 尚未按 frozen 拒绝普通触发，catch-up/rerun 显式允许策略未做 |
| 人工释放 | 前一日 `FAILED`，人工 release | 前一日 `MANUAL_RELEASED`，等待触发可释放 | 通过 | `BatchDayOperationServiceTest#shouldReleaseWaitingLaunchesForNextBizDate`；release 后触发等待队列重放 |
| 批量日重开 | `FAILED` 批量日显式 reopen | 状态回到 `IN_FLIGHT`，写审计和审批 | 部分通过 | 服务层可 `REOPEN -> IN_FLIGHT` 并写审计；审批和 Console/API 未接入 |
| 补跑结果版本 | 同一 job/bizDate 补跑 | 生成新 runAttempt，不覆盖历史结果 | 通过 | V62 已有 `(tenant_id, dedup_key, run_attempt)` 唯一约束；`DefaultLaunchService` 的 RERUN `max+1` 语义；V103 增加 `rerun_policy_snapshot` |
| worker 缺 bizDate | payload/context 都无 `bizDate` | 生产路径失败，不用本机日期兜底 | 通过 | `PrepareStepTest#execute_failsWhenBizDateMissingFromPayloadAndContext`；worker 路径 grep 已无 `LocalDate.now()` 推断 `bizDate` |

本轮验收结论：

```text
14 项验收中：
- 10 项通过；
- 4 项部分通过；
- 0 项完全未通过。

部分通过项集中在 Console/API/审批/trigger 审计字段，不影响后端核心批量日生命周期闭环，但仍不是完整产品化闭环。
```

## 14. 最终收口

最终平台口径：

```text
trigger 负责按时区计算真实 fire Instant；
orchestrator 负责裁决 bizDate、batchDayId、前日门闩、补跑和释放；
batch_day_instance 保存历史解释所需快照；
worker 只执行，不解释业务日期；
console 只展示和发起人工操作，不参与时间裁决。
```
