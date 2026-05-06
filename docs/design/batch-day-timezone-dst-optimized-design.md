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

本节按 [`batch-day-capability-design.md`](./batch-day-capability-design.md) 的能力项，对照当前 DDL / 代码后重新标注（已合并 2026-05-05 落地）。状态含义：

```text
已具备：当前主链路已有表、服务、API 或调度器支撑；
部分具备：有基础表或局部能力，但缺策略、状态、审计、API 或闭环；
未具备：目前主要停留在设计态，未形成可用运行能力。
```

「主要缺口」列已对齐当前后端实现：
- 后端尾巴显式标注，关联到 §14.3 优先级；
- 非后端（Console 视图 / REST API / 审批 UI）显式标 "(非后端)"，跟踪入口在 §4.1 / §14.3。

| 能力 | 当前状态 | 当前依据 | 主要缺口 |
|---|---|---|---|
| 调度初始化为 `trigger_runtime_state.next_fire_time` | 已具备 | `trigger_runtime_state.next_fire_time`、wheel reconciler；V104 `schedule_timezone / scheduled_local_date / scheduled_local_time / fire_sequence` cron 路径回写 | 无 |
| 触发后创建 `job_instance / workflow_run / partition / task` | 已具备 | `DefaultLaunchService` 主链路 | 无 |
| `bizDate` 按 `timezone + cutoff_time` 计算 | 已具备 | `CalendarBizDateResolver`、`business_calendar.timezone/cutoff_time`；DST 策略由 V102 + `BatchDayTimePolicyResolver` 显式化 | 无 |
| `batch_day_instance` 建模 | 已具备 | V32 / V62 / V101 / V102 / V107，含 `timezone_snapshot`、`dst_policy_snapshot`、治理字段、`SETTLING` 中间态、`version` | 无 |
| launch 时 upsert 批量日 | 已具备 | `LaunchBatchDayService` | 无（已与日切主动打开形成双入口） |
| 批量日主动打开 | 已具备 | `BatchDayOpenScheduler` 主动 upsert，落 `cutoff_at / sla_deadline_at / timezone_snapshot / dst_policy_snapshot` | Console "已打开未触发作业"视图未接入（非后端） |
| 批量日 cutoff | 已具备 | `BatchDayCutoffScheduler` + `BatchDayTimePolicyResolver` 处理 gap / overlap，跳过 frozen | 无 |
| 批量日 settle | 已具备 | `BatchDaySettleScheduler` 两阶段（claimSettling → finalizeSettling），V107 `SETTLING` 中间态；崩溃中段下次 finalize 幂等恢复 | 无 |
| late arrival | 已具备 | `late_arrival_tolerance_min`、`late_count`、`routeLateArrivalIfNeeded`；接 `BATCH_DAY_LATE_ACCEPTED / LATE_REJECTED` alert event | 跳批/等待/人工策略可继续细化（设计层，无紧急后端债） |
| catch-up 补跑 | 已具备 | `CATCH_UP`、审批、批量日补跑 API；V103 `rerun_policy_snapshot`；`RerunRequest.resultPolicy / configVersionPolicy / configVersion` 显式入参 + 跨字段校验 | 无 |
| 前一批量日门闩 | 已具备 | V100 + `BatchDayGateService`（FROZEN→REJECT、bypass for CATCH_UP/RERUN）+ `batch_day_waiting_launch` + `BatchDayWaitingReleaseScheduler` + `BATCH_DAY_GATE_WAITING/REJECTED` alert event | 无 |
| calendar 级跨日策略 | 已具备 | V100 `day_rollover_policy`（`ALLOW_OVERLAP / WAIT_PREVIOUS_DAY / REJECT_IF_PREVIOUS_OPEN`） | 无 |
| job 级跨日覆盖 | 已具备 | V100 `previous_day_dependency_scope`（`INHERIT / NONE / SAME_JOB / SAME_JOB_GROUP / SAME_CALENDAR / CUSTOM_CHAIN`）+ V106 `job_definition.job_group_code`；gate 内 `JobInstanceMapper.countNonTerminalByJobCode/JobGroupAndBizDate` 真正区分；group 未配则降级 SAME_JOB | 无 |
| 批量日跳批 | 已具备 | V101 `SKIPPED` + `skip_reason / skip_comment`；`BatchDayOperationService.skipBatchDay`；gate 视为可放行；V105 `batch_day_operation_audit` 独立表 | 无 |
| 批量日冻结 / 关闭 | 已具备 | V101 `frozen_at / frozen_by / closed_at` 字段；`freeze / unfreeze / close` 操作；cutoff/settle 跳过 frozen；launch gate `FROZEN→REJECT`（CATCH_UP / RERUN bypass） | 无 |
| 人工释放等待 | 已具备 | V101 `MANUAL_RELEASED` + `BatchDayOperationService.releaseBatchDay` + 等待表重放 | 无 |
| 批量日重开 | 已具备 | `BatchDayOperationService.reopenBatchDay` 服务层完成，写 `job_execution_log` | Console REST API、审批、操作历史 UI（非后端） |
| DAG / workflow 依赖 | 部分具备 | workflow DAG、node skip 已有 | 缺跨批量日、跨日历依赖 |
| 文件批量治理 | 部分具备 | 文件导入/导出/分发、治理表和 Console API 已有；import scanner 接 `bizDatePattern` 从 object name 解析 bizDate | 文件到达等待、晚到策略、文件组等待未完全批量日化 |
| 幂等与重入 | 已具备 | `idempotency_record`、`run_attempt`、`RERUN`、分区幂等键；V103 `params_snapshot` 含 job definition version；rerun 显式 `resultPolicy / configVersionPolicy` 通过 LaunchRequest.params 透传到 `rerun_policy_snapshot` | 无 |
| 结果版本 | 未具备 | — | 补跑后"哪版生效"仍无平台统一裁决（后端，设计已写入 §5.5） |
| 配置版本治理 | 已具备 | V103 `job_definition_version` + `job_instance.rerun_policy_snapshot`；`params_snapshot` 含 version | 无 |
| SLA 与告警 | 已具备 | `sla_deadline_at`、timeout、告警基础；`JobSlaScheduler` 增加 escalation 重试 + `BATCH_DAY_LATE_*` / `BATCH_DAY_GATE_*` alert event；可关闭（escalation-delay-seconds=0） | 无 |
| 资源限流 | 部分具备 | queue/quota/worker group 已有 | 缺按业务域、核心链路、跨日门闩联动限流（设计层，未列入本轮） |
| 死信与补偿 | 部分具备 | dead letter、compensation、replay 基础 | 缺按批量日维度重放治理（设计层，未列入本轮） |
| 观测与审计 | 已具备 | 批量日查询、窗口查询、audit log；V105 `batch_day_operation_audit` 独立表沉淀治理动作；alert event 流覆盖 gate / late-arrival 决策 | Console 操作历史 UI 接入（非后端） |
| 权限与审批 | 部分具备 | console 鉴权、审批命令基础 | `batch_day.skip / freeze / release / reopen / close` 权限点和审批流（API + 后端校验） |

结论（2026-05-06 更新）：

```text
"批量日驱动"主链后端能力已全闭合：主动打开、前日门闩(含 FROZEN→REJECT、SAME_JOB / SAME_JOB_GROUP 真正区分)、
人工治理(独立审计表)、DST 策略(含 RUN_TWICE 降级 warn)、worker bizDate 收口、补跑显式策略、SETTLING 中间态、
trigger 本地计划审计、SLA escalation + alert event 流、import scanner bizDatePattern。

仍未做(全部为非后端 / 设计层):
- 跨业务域 + 核心链路联动限流(设计层)
- 跨批量日 DAG 依赖 / 批量日维度重放治理(设计层)
- 结果版本"生效"裁决主模型(设计层 §5.5, 业务结果表归属)
- Console 治理 REST API + 权限点 + 审批 UI + 操作历史视图(非后端)
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

### 14.1 平台分工口径

最终平台口径：

```text
trigger 负责按时区计算真实 fire Instant；
orchestrator 负责裁决 bizDate、batchDayId、前日门闩、补跑和释放；
batch_day_instance 保存历史解释所需快照；
worker 只执行，不解释业务日期；
console 只展示和发起人工操作，不参与时间裁决。
```

任何后续修改都必须保留这一分层。trigger 不得反向裁决 `bizDate`；worker 不得用本机时钟推断业务日；console 不得在前端做"跨日合法性"判断。

### 14.2 本轮已落交付物

DDL 迁移：

| 文件 | 内容 |
|---|---|
| `V100__batch_day_gate_policy.sql` | `business_calendar.day_rollover_policy` / `job_definition.previous_day_dependency_scope` / `batch_day_waiting_launch` |
| `V101__batch_day_operation_governance.sql` | `batch_day_instance` 治理字段（`blocked_reason / manual_release_* / skip_* / frozen_* / closed_*`）、状态扩展 `SKIPPED / MANUAL_RELEASED` |
| `V102__batch_day_dst_policy.sql` | `business_calendar.dst_gap_policy / dst_overlap_policy`、`batch_day_instance.dst_policy_snapshot` |
| `V103__job_instance_rerun_config_snapshot.sql` | `job_definition_version`、`job_instance.rerun_policy_snapshot`、`params_snapshot` 增加 job definition version |
| `V104__trigger_runtime_state_local_audit.sql` | `trigger_runtime_state` 增 `schedule_timezone / scheduled_local_date / scheduled_local_time / fire_sequence` |
| `V105__batch_day_operation_audit.sql` | 新建独立 `batch_day_operation_audit` 表 + 索引 |
| `V106__job_definition_group_code.sql` | `job_definition.job_group_code` + partial index, 给 `SAME_JOB_GROUP` 用 |
| `V107__batch_day_settling_status.sql` | `ck_batch_day_instance_status` 加入 `SETTLING` 中间态 |

服务与调度器：

| 类 | 职责 |
|---|---|
| `BatchDayOpenScheduler` | 日切前主动打开批量日，落 `cutoff_at / sla_deadline_at / timezone_snapshot / dst_policy_snapshot` |
| `BatchDayGateService` | launch 前裁决 `ALLOW / WAIT / REJECT`，写 `batch_day_waiting_launch` + 独立审计 + alert event；`FROZEN→REJECT`（CATCH_UP / RERUN bypass）；`SAME_JOB / SAME_JOB_GROUP` 细粒度查 `job_instance` 上一日终态 |
| `BatchDayOperationService` | `FREEZE / RELEASE / SKIP / REOPEN / CLOSE` 等人工治理动作；同事务双写 `job_execution_log` + V105 `batch_day_operation_audit` |
| `BatchDayOperationAuditMapper` | V105 独立审计表读写 |
| `BatchDayTimePolicyResolver` | 统一处理 cutoff 在 DST gap / overlap 下的裁决；`RUN_TWICE` 显式降级 `RUN_ONCE_EARLIER_OFFSET` + warn + snapshot 对齐 |
| `BatchDayCutoffScheduler` | 已存在；本轮接入 `BatchDayTimePolicyResolver`，并跳过 frozen 批量日 |
| `BatchDaySettleScheduler` | 两阶段 settle：`claimSettling` (tx1) → `finalizeSettling` (tx2)，崩溃中段 SETTLING 行下次扫描幂等恢复 |
| `BatchDayWaitingLaunchMapper` | 等待队列读写，前一日放行后由 gate 触发重放 |
| `JobSlaScheduler` | SLA escalation 重试 + `BATCH_DAY_LATE_*` / `BATCH_DAY_GATE_*` alert event；`escalation-delay-seconds=0` 关闭 |
| `WheelTriggerReconciler` / `HashedWheelTriggerScheduler` | cron 计算路径回写 V104 本地计划字段；DST overlap 二次触发 fire_sequence 累加 |
| `RerunRequest` (console-api) → `CompensationSubmitCommand` → `DefaultCompensationService.applyRerunPolicyParams` → `DefaultLaunchService.buildRerunPolicySnapshot` | `resultPolicy / configVersionPolicy / configVersion` 显式入参 + 跨字段校验 + 透传到 `rerun_policy_snapshot` |

worker 路径整改：

```text
worker-export PrepareStep：缺 bizDate 时 fail-fast，禁止 LocalDate.now() 兜底
worker-import scanner：要求显式 defaultBizDate
全 worker 路径：业务日 grep 已无 LocalDate.now() 推断
```

提交对照（按 §12 阶段）：

```text
Phase 1 文档 / 守护测试            955c993a
Phase 2 批量日主动打开             4a4167b2
Phase 3 前一批量日门闩             98d57be2
Phase 4 批量日人工治理             072d2802
Phase 5 DST 策略显式化             f34b2828
Phase 6 worker 兜底收口            94f969a5
Phase 7 补跑版本治理               cc69a768
本轮文档 codify                   2965ddb1 / 8fa25050 / 3820c7e3

— 2026-05-06 §14.3 后端残留收口 —
SETTLING 中间态 + import scanner bizDatePattern    28b77c59
SLA escalation + gate/late-arrival alert event   a98ba722
trigger 本地计划审计 / 独立审计表 / SAME_JOB_GROUP / rerun policy 显式入参 / RUN_TWICE warn   d9f2ac7b
```

### 14.3 残留缺口与下一步

2026-05-06 完成扫尾后, 状态分两类:

#### 14.3.1 已完成（commit 标注）

| 优先级 | 缺口 | 落地 |
|---|---|---|
| P0 | trigger cron 本地计划审计字段 | V104 + entity / mapper / cron 路径回写 + fire_sequence DST overlap 累加 (d9f2ac7b) |
| P0 | launch gate 对 `FROZEN` 批量日的拒绝路径 | `BatchDayGateService` `FROZEN→REJECT`，CATCH_UP / RERUN bypass + alert event (a98ba722, 测试 d9f2ac7b) |
| P1 | `batch_day_operation_audit` 独立表 | V105 + entity / mapper / xml；`BatchDayOperationService` 同事务双写 (d9f2ac7b) |
| P1 | `previous_day_dependency_scope` 细粒度 `SAME_JOB / SAME_JOB_GROUP` | V106 `job_definition.job_group_code` + `JobInstanceMapper.countNonTerminalByJobCode/JobGroupAndBizDate`；group 未配自动降级 SAME_JOB (a98ba722 + d9f2ac7b) |
| P1 | 补跑请求 `resultPolicy / configVersionPolicy` 显式 API | `RerunRequest` 三字段 + @Pattern / @Positive；跨字段校验 USE_SPECIFIED_VERSION 必带 configVersion；透传到 `rerun_policy_snapshot` (d9f2ac7b) |
| P2 | `RUN_TWICE` 用于 cutoff | `BatchDayTimePolicyResolver.effectiveCutoffOverlapPolicy` 显式降级 + warn + snapshot 对齐 (d9f2ac7b) |
| P2 | import scanner `defaultBizDate` 来源化 | `ImportScannerProperties.bizDatePattern` 从 object 名 named-group 解析 yyyyMMdd (28b77c59) |
| 附加 | `SETTLING` 中间态 | V107 + `BatchDaySettleScheduler` 两阶段；崩溃中段下次幂等恢复 (28b77c59) |
| 附加 | SLA escalation + alert event 流 | `JobSlaScheduler` 重试 + `BATCH_DAY_LATE_*` / `BATCH_DAY_GATE_*` alert event；可关闭 (a98ba722) |
| P1 | 批量日治理 Console REST API | 单 endpoint dispatcher `POST /api/console/batch-days/operate`（5 个 action 走 body 路由）；orchestrator `POST /internal/batch-days/operate` + `ConsoleOrchestratorProxyService.batchDayOperate` 转发；ROLE_ADMIN + `@Idempotent` + `@Pattern` 白名单；OpenAPI / protocol changelog 同步 (455354f8) |

#### 14.3.2 仍未做（非后端 / 设计层）

| 优先级 | 缺口 | 性质 | 推进入口 |
|---|---|---|---|
| P2 | Console 批量日视图：已打开未触发、阻塞原因、DST 调整、操作历史 | 非后端 | 数据源齐备（`batch_day_instance` 全字段 + V105 独立审计表 + V104 trigger 本地计划） |
| 设计 | 跨业务域 / 核心链路联动限流 | 设计层（Accepted，实施 gated） | [ADR-019](../architecture/adr/ADR-019-cross-domain-rate-limit.md) — `business_domain` 主模型 + 域级 quota + 父子借调 + 三态开关；实施触发条件已明确，未触发期间不开工 |
| 设计 | 跨批量日 DAG 依赖 | 设计层（Accepted，已开工） | [ADR-018](../architecture/adr/ADR-018-cross-batch-day-dag-dependency.md) — pipe 模型；`workflow_node.cross_day_dependencies` JSONB + `WAITING_DEPENDENCY` 节点状态 + `BizDateArithmetic` + `CrossDayDependencyResolver`。Stage 2-4 已落 V109，Stage 5 reconciler / Stage 6 E2E / Stage 7 超时治理排期中 |
| 设计 | 批量日维度重放治理 | 设计层（Accepted，已开工） | [ADR-020](../architecture/adr/ADR-020-batch-day-replay.md) — `batch_day_replay_session` 聚合 + 4 种 scope (ALL/ALL_FAILED/SUBSET_JOB_CODES/OUTPUTS_ONLY) + 接审批 + 同 (tenant,calendar,bizDate) 唯一 active session 不变量。Stage 2 schema 已落 V110，dispatcher / approval / OUTPUTS_ONLY promote 按 Stage 3-8 推进 |
| 设计 | 结果版本 "生效" 裁决 | 设计层（Accepted，主链路已落） | [ADR-017](../architecture/adr/ADR-017-result-version-model.md) — `result_version` 主模型 + EFFECTIVE 单版索引 + payload INLINE/EXTERNAL/FILE_RECORD + retention scheduler。Stage 1-5 已落 V108，Stage 6 console UI 待接入 |
| 设计 | late arrival 跳批/等待/人工策略闭环细化 | **决策：v1 不做**（类 ADR-019 gating 模式） | 当前 `routeLateArrivalIfNeeded` 已 binary 完整闭环：容差内 LATE_ACCEPTED + WARN alert；容差外自动翻 CATCH_UP + ERROR alert + audit log + DB CAS。SKIP_SILENT / WAIT_MANUAL_RELEASE / job 级 tolerance override 等"细化策略"会引入 4 枚举值 + 1 张表 + 3 API，但目前 backlog 无具体客户诉求。**触发条件**（满足任一才动）：(1) ≥2 个生产工单为"超容差但希望人工决定"；(2) 出现"已知会迟到、不希望刷屏 ERROR alert"的特殊业务（SKIP_SILENT 才有意义）；(3) 合规要求 late arrival 必须独立审计表（`batch_day_late_arrival` 才必要）。未触发期间不开 ADR、不排期 |

### 14.4 守护与回归

下次改动批量日 / 时区 / DST / trigger 路径必须跑：

```text
BatchDayOpenSchedulerTest
BatchDayGateServiceTest                          (FROZEN reject + SAME_JOB/SAME_JOB_GROUP)
BatchDayOperationServiceTest                     (审计双写)
BatchDayTimePolicyResolverTest                   (含 RUN_TWICE 降级)
BatchDayCutoffSchedulerTest
BatchDaySettleSchedulerTest                      (SETTLING 中段恢复)
PrepareStepTest                                  (worker-export bizDate 兜底)
CalendarBizDateResolverTest                      (cutoff 跨时区)
DefaultLaunchServiceTest                         (rerun_policy_snapshot)
DefaultCompensationServiceTest                   (rerun policy 透传)
JobSlaSchedulerTest                              (escalation 重试 + alert event)
TriggerRuntimeStateMapperIntegrationTest         (V104 本地计划字段)
```

全 reactor 验收口径：`mvn -pl batch-orchestrator,batch-trigger,batch-console-api,batch-common -am -DskipITs test` 全 PASS（414/414，2026-05-06）。

回归监控：

```text
trigger 触发到 launch 的耗时（gate 引入了等待表写路径）
batch_day_waiting_launch 积压量（释放调度器是否落后）
batch_day_instance.day_status 分布（FROZEN / SKIPPED / MANUAL_RELEASED 的占比异常波动）
DST 切换日的 cutoff_at / next_fire_time 漂移（gap / overlap 处理是否符合策略）
```

### 14.5 文档维护

- 本文是批量日 / 时区 / DST 的**主设计入口**，[`batch-day-design.md`](./batch-day-design.md) / [`batch-day-capability-design.md`](./batch-day-capability-design.md) / [`timezone-and-dst-design.md`](./timezone-and-dst-design.md) 是其下游材料
- 后续相关 schema 变化（含归档表对齐）必须同步更新本文 §5 / §13 / §14.2 / §14.3
- 新增 `batch_day.*` 状态、policy 取值、API 必须同步 `docs/dict/glossary.md`、`docs/api/console-api.openapi.yaml`、`docs/api/console-api-protocol.md`
- CLAUDE.md 时区策略章节、`docs/coding-conventions.md §20`、`docs/runbook/feature-switches.md` 需保持与本文 §2 / §8 一致
