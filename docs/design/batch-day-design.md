# 批量日设计方案（完整版）

## 核心原则

> **批量日（business_date）在触发时确定，一旦写入 job_instance 就永不改变。**
> 即使执行跨到第二天，biz_date 仍然是触发时的业务日期。

所有计算、文件命名、存储路径、对账，全部使用 `business_date`，不使用 `system_time`。

---

## 问题范围

本方案解决五件事：

| # | 问题 | 方案 |
|---|---|---|
| 1 | biz_date 计算不感知 cutoff，跨午夜批次日期错误 | CalendarBizDateResolver |
| 2 | 没有批量日生命周期状态，前端无法展示窗口状态 | batch_day_instance 表 |
| 3 | deadline_at 没有从日历配置自动计算 | SLA 配置 + 计算逻辑 |
| 4 | 没有 late arrival 处理机制，窗口关闭后数据到来无明确路由 | 容忍窗口 + 拒绝路由 |
| 5 | catch-up 与批量日状态割裂，补跑入口无日历驱动 | 批量日状态驱动 catch-up |

---

## 数据模型变更

### 变更 1：business_calendar 加三列

```sql
ALTER TABLE batch.business_calendar
    ADD COLUMN cutoff_time                TIME    NOT NULL DEFAULT '06:00:00',
    ADD COLUMN late_arrival_tolerance_min INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN sla_offset_min             INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN batch.business_calendar.cutoff_time IS
    '批量日切换时间。在该时间之前触发的批次，biz_date 属于前一个自然日。';
COMMENT ON COLUMN batch.business_calendar.late_arrival_tolerance_min IS
    'cutoff 之后，数据仍可被当天接收的容忍窗口（分钟）。超出后触发 catch-up。';
COMMENT ON COLUMN batch.business_calendar.sla_offset_min IS
    '批量日 SLA deadline = cutoff_time + sla_offset_min。0 表示无日历级 SLA。';
```

**字段语义：**

```
cutoff_time = 06:00
late_arrival_tolerance_min = 60

→ 06:00        批量日切换，不再接受新触发
→ 06:00~07:00  容忍窗口：晚到数据仍接收（LATE_ACCEPTED）
→ 07:00 之后   拒绝窗口：晚到数据路由到 catch-up（LATE_REJECTED）
```

### 变更 2：新增 batch_day_instance 表

批量日的运行态主体。每个 `(tenant_id, calendar_code, biz_date)` 对应一行。

```sql
CREATE TABLE IF NOT EXISTS batch.batch_day_instance (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    calendar_code   VARCHAR(128) NOT NULL,
    biz_date        DATE         NOT NULL,
    day_status      VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    open_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cutoff_at       TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    sla_deadline_at TIMESTAMPTZ,
    late_count      INTEGER      NOT NULL DEFAULT 0,
    catchup_count   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_day_instance UNIQUE (tenant_id, calendar_code, biz_date),
    CONSTRAINT ck_batch_day_status   CHECK (day_status IN ('OPEN','CUTOFF','IN_FLIGHT','SETTLED','FAILED'))
);
```

**day_status 状态机：**

```
OPEN ──── cutoff_time 到达 ──────────────────→ CUTOFF
            │
            ▼
         CUTOFF ── 存在 IN_FLIGHT 实例 ──→ IN_FLIGHT
            │
            ├── 所有实例终态 SUCCESS/PARTIAL ──→ SETTLED
            │
            └── 存在 FAILED 且无覆盖 ────────→ FAILED

IN_FLIGHT ── 所有实例终态 SUCCESS/PARTIAL ──→ SETTLED
IN_FLIGHT ── 存在 FAILED 且无覆盖 ──────────→ FAILED
```

`batch_day_instance` 由定时任务（`BatchDayCutoffScheduler`）维护状态推进，不依赖 job 执行链路，避免耦合。

### 变更 3：Flyway 版本号

当前最新版本需确认（执行 `select version from flyway_schema_history order by installed_rank desc limit 1` 查看）。
新迁移文件命名：`V14__add_batch_day_support.sql`（若当前最新是 V13）。

---

## biz_date 计算算法

### 触发时计算（DefaultLaunchAdapterService）

```java
// 原来（不感知 cutoff）
LocalDate bizDate = command.fireTime().atZone(zoneId).toLocalDate();

// 替换为
LocalDate bizDate = calendarBizDateResolver.resolve(
    command.fireTime(), calendar  // calendar 可为 null，null 时降级为原逻辑
);
```

### CalendarBizDateResolver 逻辑

```
resolve(fireInstant, calendar):
    if calendar == null:
        return fireInstant.atZone(systemDefault).toLocalDate()   // 降级

    localDT   = fireInstant.atZone(calendar.timezone)
    localTime = localDT.toLocalTime()
    localDate = localDT.toLocalDate()

    rawBizDate = localTime < calendar.cutoffTime
                 ? localDate.minusDays(1)   // 还在昨天的批量日内
                 : localDate

    return adjustForHoliday(rawBizDate, calendar)

adjustForHoliday(date, calendar):
    if not isHoliday(calendar.id, date):
        return date

    switch calendar.holidayRollRule:
        SKIP:          return null          // 调用方不创建触发
        NEXT_WORKDAY:  return nextWorkday(calendar.id, date)
        PREV_WORKDAY:  return prevWorkday(calendar.id, date)
```

### SKIP 规则处理

`DefaultTriggerService` SCHEDULED 路径：

```java
LocalDate bizDate = resolver.resolve(fireTime, calendar);
if (bizDate == null) {
    log.info("biz_date {} is holiday with SKIP rule, skipping trigger", rawDate);
    return LaunchResponse.skipped();
}
```

---

## 批量日生命周期管理

### BatchDayCutoffScheduler（新增，batch-trigger 模块）

每分钟轮询一次，检查是否有 `batch_day_instance` 需要推进状态：

```
1. 查询 day_status = OPEN 且 cutoff_at IS NULL 的记录
2. 对每条记录：判断 now(timezone) >= cutoff_time
3. 若是：UPDATE day_status = CUTOFF, cutoff_at = now()
         同时触发：late_arrival 容忍窗口开始计时
```

### BatchDaySettleScheduler（新增，batch-orchestrator 模块）

每分钟轮询，检查 CUTOFF / IN_FLIGHT 状态的批量日是否已可结算：

```
1. 查询 day_status IN (CUTOFF, IN_FLIGHT) 的记录
2. 对每条：查询关联 job_instance（biz_date + calendar_code 关联）
3. 若所有实例均终态：
   - 全部 SUCCESS/PARTIAL → SETTLED
   - 存在 FAILED 无 catch-up 覆盖 → FAILED
```

### batch_day_instance 创建时机

第一个归属该 `(tenant_id, calendar_code, biz_date)` 的 `job_instance` 创建时，
`DefaultLaunchService` 顺带 upsert `batch_day_instance`（OPEN 状态），同时写入 `sla_deadline_at`。

---

## SLA 集成

### sla_deadline_at 计算

```
sla_deadline_at = cutoff_time 的当日 Instant + sla_offset_min

例：
  cutoff_time = 06:00, sla_offset_min = 120
  biz_date 2026-03-28 的批量日：
    cutoff_at = 2026-03-29 06:00:00 Asia/Shanghai
    sla_deadline_at = 2026-03-29 08:00:00 Asia/Shanghai
```

`sla_offset_min = 0` 时不设 `sla_deadline_at`（无日历级 SLA）。

`job_definition` 的 `timeout_seconds` 继续作为单个 job 的 SLA，
`batch_day_instance.sla_deadline_at` 是整个批量日的 SLA，两者独立。

### 对账结论更新

`job_instance.deadline_at`：
- 若 `job_definition.timeout_seconds > 0`：`deadline_at = created_at + timeout_seconds`
- 若 `batch_day_instance.sla_deadline_at IS NOT NULL`：取两者较早值

---

## Late Arrival 处理

### 判断逻辑（EventTrigger 路径）

```
收到 event 类型触发请求，biz_date = D：

1. 查询 batch_day_instance(calendar_code, biz_date=D)
2. 若不存在 或 day_status = OPEN：正常处理（EARLY）
3. 若 day_status = CUTOFF 且 now < cutoff_at + tolerance：LATE_ACCEPTED（正常创建实例）
4. 若 day_status = CUTOFF 且 now >= cutoff_at + tolerance：LATE_REJECTED → 路由到 catch-up
5. 若 day_status IN (SETTLED, FAILED)：LATE_REJECTED → 路由到 catch-up
```

### trigger_request 记录 late arrival

在 `trigger_request` 的 `request_payload_hash` JSONB 字段（或新增）记录晚到标记。
短期方案：`params` 中写入 `lateArrival=true, arrivalStatus=LATE_ACCEPTED/LATE_REJECTED`。

`batch_day_instance.late_count` 在每次 `LATE_ACCEPTED` 时 +1，用于控制台展示。

---

## Catch-up 触发

### 现有机制不变

`trigger_type=CATCH_UP`、`CatchUpPolicyType`（NONE/AUTO/MANUAL_APPROVAL）、
`approvePendingCatchUp()` 流程——全部保留。

### 新增：批量日状态驱动的 catch-up

`BatchDaySettleScheduler` 推进到 `FAILED` 时，根据 `business_calendar.catch_up_policy`：

```
NONE:             记录 FAILED，不触发补跑
AUTO:             自动创建 catch-up trigger_request（CATCH_UP 类型），
                  状态直接 LAUNCHED
MANUAL_APPROVAL:  创建 catch-up trigger_request，状态 ACCEPTED，
                  等待控制台人工审批（现有流程）
```

`batch_day_instance.catchup_count` +1。

---

## 前端需要的新 API

### 批量日视图

```
GET /console/query/batch-days
    ?tenantId=&calendarCode=&from=2026-03-01&to=2026-03-31
    &pageNo=1&pageSize=20

Response: PageResponse<BatchDayView>

BatchDayView:
  bizDate           DATE
  dayStatus         String           // OPEN/CUTOFF/IN_FLIGHT/SETTLED/FAILED
  openAt            OffsetDateTime
  cutoffAt          OffsetDateTime
  settledAt         OffsetDateTime
  slaDeadlineAt     OffsetDateTime
  slaStatus         String           // ON_TIME/SLA_BREACH/SLA_TIMEOUT/NO_SLA
  totalJobCount     int
  successJobCount   int
  failedJobCount    int
  inFlightJobCount  int
  lateCount         int
  catchupCount      int
  catchupSummary    List<CatchUpSummary>
```

### 窗口状态视图

```
GET /console/query/batch-days/{bizDate}/window
    ?tenantId=&calendarCode=

Response: BatchDayWindowView
  bizDate, dayStatus, cutoffAt, slaDeadlineAt
  currentSystemTime, timeUntilCutoff (seconds, 负数表示已过 cutoff)
  lateArrivalWindowClosesAt
  jobs: List<JobDaySummary>   // 该批量日所有 job 的状态汇总
```

### 补跑入口

```
POST /console/command/batch-days/{bizDate}/catchup
    Body: { tenantId, calendarCode, jobCodes?: [] }

→ 对指定 bizDate 的 FAILED job（或指定 jobCodes）触发 catch-up
→ 受 catch_up_policy 约束（AUTO 直接跑，MANUAL_APPROVAL 走审批）
```

---

## 实施顺序

```
Phase 1：数据模型（不影响现有行为）
  └─ Flyway V14：business_calendar 加三列，新建 batch_day_instance 表
  └─ 更新 BusinessCalendarRecord 实体

Phase 2：biz_date 算法修复（影响 SCHEDULED 触发路径）
  └─ CalendarBizDateResolver（纯函数，无 IO）
  └─ DefaultLaunchAdapterService 替换计算逻辑
  └─ DefaultTriggerService 加载 calendar
  └─ 单测：5 个场景验收

Phase 3：批量日生命周期（新能力，不改现有链路）
  └─ BatchDayCutoffScheduler（batch-trigger）
  └─ BatchDaySettleScheduler（batch-orchestrator）
  └─ DefaultLaunchService upsert batch_day_instance

Phase 4：SLA 集成
  └─ deadline_at 计算逻辑更新
  └─ sla_deadline_at 写入 batch_day_instance

Phase 5：Late arrival + catch-up 驱动
  └─ EventTrigger 路径加 late arrival 判断
  └─ BatchDaySettleScheduler → FAILED 时驱动 catch-up

Phase 6：前端 API
  └─ BatchDayView DTO + 查询接口
  └─ 窗口状态接口
  └─ 补跑入口接口
  └─ OpenAPI 回写
```

---

## 验收矩阵

### biz_date 计算

| 场景 | 参数 | 预期 biz_date |
|---|---|---|
| cutoff=06:00，02:00 触发，工作日 | T-1 是工作日 | T-1 |
| cutoff=06:00，08:00 触发，工作日 | T 是工作日 | T |
| 02:00 触发，T-1 是节假日，SKIP | - | 不产生实例 |
| 02:00 触发，T-1 是节假日，PREV_WORKDAY | - | T-1 前最近工作日 |
| calendar_code 为空 | - | fireTime.toLocalDate()（降级） |

### Late arrival

| 场景 | 预期行为 |
|---|---|
| 事件在 cutoff 前到达 | EARLY，正常创建实例 |
| 事件在 cutoff 后、容忍窗口内到达 | LATE_ACCEPTED，正常创建实例，late_count+1 |
| 事件在容忍窗口关闭后到达 | LATE_REJECTED，路由 catch-up |
| batch_day SETTLED 后事件到达 | LATE_REJECTED，路由 catch-up |

### 批量日状态

| 场景 | 预期 day_status |
|---|---|
| 第一个 job 创建，cutoff 未到 | OPEN |
| cutoff_time 到达 | CUTOFF |
| 所有 job SUCCESS | SETTLED |
| 存在 FAILED，无 catch-up | FAILED |
| FAILED → catch-up 成功 | SETTLED |
