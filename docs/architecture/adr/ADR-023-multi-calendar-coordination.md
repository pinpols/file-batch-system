# ADR-023 · 多日历联动 + 半天工作日

- **Status**: Accepted（**第 1 阶段必做 / P0-P1**，gated — 见"实施触发条件"；满足跨境 / 半天 cutoff / 灾难日审计 任一即开工）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-018（跨日 DAG，calendar 跨时区独立）/ §14.3.2 / [ADR 012/021-027 优先级 + 范围边界](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)

## 范围边界（Scope Discipline）

> **批量调度系统的核心能力，不越界。**只做"让 Job 引用业务日历 + 跨日历联动"，不做"全球日历 SaaS / 外部公假日同步"。

| ✅ 做 | ❌ 不做 |
|---|---|
| `calendar_dependency`：A SETTLED 才起 B（中港美串联） | 引入 ICU / iCal RFC 5545 RRULE（过度工程） |
| `cutoff_schedule` JSONB：圣诞夜 / 春节前夕半天 cutoff | calendar 自动同步外部公假日 API（数据来源不可控、合规风险） |
| `calendar_group` + holiday `scope=GROUP` 共享假日 | 全球日历 SaaS / "calendar fork / branch" |
| `disaster_day_override`：突发停业不改 holiday 表保审计清晰 | calendar_group 嵌套（flat group 即可） |
| 5 个调度核心能力：是否工作日 / 半天工作日 / 下一工作日 / 是否触发 / 顺延跳过 | disaster override 未来生效（只解当下灾难，未来停业走正常 holiday） |

## 背景

当前 `business_calendar` 是独立配置：

- 单 `timezone` + 单 `cutoff_time: LocalTime`（一天一个 cutoff）；
- `calendar_holiday` 单租户独立配置；
- calendar 之间无依赖关系。

跨境 / 多市场场景挤压出真痛点：

- **跨时区联动**：中国 + 香港 + 美国同租户，"中国 calendar SETTLED 后才起香港 calendar"现在做不到；
- **半天工作日**：圣诞夜下午关市；中国春节前一天下午半天班；当前 cutoff 单值表达不出来；
- **节假日动态**：春节调休年年改，目前修 holiday 直接覆盖，没 audit 历史（接 ADR-022 forensic 解一半）；
- **灾难日热切换**：突发停业，需要把当天标 SKIP 但又想保留原配置 — 当前只能改 holiday，影响重放；
- **Calendar group**：多 calendar 共享某些假日（亚洲市场都关春节），现在要每个 calendar 各自 INSERT。

业界 BMC Control-M / CA Autosys 的 "calendar group" + "compound calendar" 是标配。

## 决策

引入三个新概念，与现有 `business_calendar` 共存（不破坏）：

1. **Calendar dependency**：A SETTLED 才起 B；
2. **Cutoff schedule**：cutoff_time 由单值改为 `JSONB schedule` 支持半天 / 多 cutoff；
3. **Calendar group 共享假日**：`calendar_group` + `calendar_holiday` 加 `group_code` 列。

### Calendar Dependency

```sql
batch.calendar_dependency
  id              BIGSERIAL PK
  tenant_id       VARCHAR(64)
  upstream_code   VARCHAR(128)   -- 必须先 SETTLED
  downstream_code VARCHAR(128)   -- 才能 OPEN
  rule            VARCHAR(32)    -- WAIT_SETTLED / WAIT_CUTOFF / SAME_DAY_PARALLEL
  enabled         BOOLEAN
  UNIQUE (tenant_id, upstream_code, downstream_code)
```

`BatchDayOpenScheduler` 创建 downstream 批量日前查 dependency，未满足 → 推迟（不算失败，写 `BLOCKED_BY_UPSTREAM_CALENDAR` 原因）。

### Cutoff Schedule（半天 / 多 cutoff）

`business_calendar.cutoff_time` 保留兼容（NULL 时用 schedule）。新增：

```sql
ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS cutoff_schedule JSONB;
```

`cutoff_schedule` 形态：

```json
{
  "default": "06:00",
  "overrides": [
    {"date": "2026-12-24", "cutoff": "13:00", "reason": "圣诞夜半天班"},
    {"date": "2026-02-09", "cutoff": "12:30", "reason": "春节前夕"},
    {"weekdayPattern": "FRIDAY", "cutoff": "05:30", "from": "2026-06-01", "to": "2026-08-31", "reason": "夏季周五早 cutoff"}
  ]
}
```

`BatchDayTimePolicyResolver.resolveCutoffAt` 接 `cutoff_schedule` 优先于 `cutoff_time`。

### Calendar Group + 共享假日

```sql
batch.calendar_group
  id              BIGSERIAL PK
  tenant_id       VARCHAR(64)
  group_code      VARCHAR(128)
  description     VARCHAR(512)
  UNIQUE (tenant_id, group_code)

ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS group_code VARCHAR(128);  -- 加入哪个 group

ALTER TABLE batch.calendar_holiday
    ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'CALENDAR',
    ADD COLUMN IF NOT EXISTS group_code VARCHAR(128);
    -- scope = CALENDAR (单 calendar) / GROUP (整组共享)
```

holiday 解析层（`CalendarBizDateResolver`）逻辑：先查 `scope=CALENDAR` 命中，再 fall through 到 `scope=GROUP AND group_code = <calendar.group_code>`。

### 灾难日热切换

新增 `disaster_day_override` 表：

```sql
batch.disaster_day_override
  id, tenant_id, calendar_code, biz_date,
  action VARCHAR(32),            -- SKIP / DEFER_TO_NEXT_BIZDAY
  reason VARCHAR(512),
  approved_by, approved_at,
  effective_at, ttl_until
```

激活时 `BatchDayOpenScheduler` 创建批量日前先查这张表；命中即按 action 处理（直接 SKIPPED 或推迟到次日）；不改 holiday 表，事后审计清晰。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 3 张新表 + 3 列 ALTER；archive 镜像同步 |
| 模块 | `BatchDayOpenScheduler` 加 dependency 检查；`BatchDayTimePolicyResolver` 接 schedule；`CalendarBizDateResolver` 加 group fallback；console-api 加 group / dependency / disaster CRUD |
| 兼容 | 老 calendar 不加 group_code → 行为不变；cutoff_schedule NULL → 用单值；dependency 表空 → 跨 calendar 串联无影响 |

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | calendar_group + holiday.scope/group_code + 解析层 fallback | 2 天 |
| 2 | cutoff_schedule JSONB + resolver 接入 | 2 天 |
| 3 | calendar_dependency + BatchDayOpenScheduler 检查 | 3 天 |
| 4 | disaster_day_override + open path 优先级 | 2 天 |
| 5 | Console CRUD + 校验 | 2 天 |
| 6 | E2E（中港美三 calendar 联动） | 2 天 |

总 ~13 人天。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 多个 cutoff 用单 calendar 多日期独立行 | 表行膨胀，cutoff 是结构化语义不该靠扁平化拆分 |
| holiday 表共享靠"按 group 复制 N 行"维护脚本 | 事实失同步；calendar 增减 group 时要全量刷新；脆弱 |
| disaster 修 holiday 表 | 事后回放 holiday 表混淆，不知是真节假还是灾难 SKIP |

## 不变量

1. dependency 不允许成环（启用时 graph validator 检查）；
2. cutoff_schedule 任意 override 都必须落在 calendar.timezone 下的 LocalTime（解析期校验）；
3. holiday `scope=GROUP` 行只在 calendar 加入 group 时生效；calendar 退出 group → 自动失效（不改 history）；
4. disaster_day_override TTL 过期前不可重复对同 (calendar, biz_date) 创建（DB 部分唯一）；
5. dependency 检查失败 → 推迟创建批量日，不算"失败"（不污染 SLA）。

## 验收

- 单测：cutoff_schedule 5 种 override 形态 / dependency graph cycle / group fallback
- IT：3 calendar dependency 串联（CN → HK → US）
- E2E：圣诞夜 13:00 cutoff 实跑；春节前调休批量自动按 group 假日 SKIP
- 守护：`CalendarDependencyCycleTest` + 跨时区 schedule 一致性测

## 实施触发条件

满足任一：
1. **跨境业务**：单租户跨 ≥ 2 时区（中国 + 香港 / 中国 + 美国 / 多个区域市场）；
2. **半天 cutoff 诉求**：业务方主动提"圣诞夜 / 春节前夕需要不同 cutoff"；
3. **灾难日不可变**：监管要求灾难 SKIP 必须留独立审计（不允许改 holiday 表）；
4. **calendar 数 ≥ 5**：calendar 数量上规模后维护重复 holiday 痛苦。

单时区 + 单 calendar + 没碰到调休痛点 → 不开工。

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | dependency 异步串联 vs 同步阻塞 | 异步：upstream SETTLED 触发 event → reconciler 唤醒 downstream；启动期不阻塞调度 |
| 2 | cutoff_schedule weekdayPattern 是否支持多周期 | v1 仅支持 `from-to + weekday + 单 cutoff`；复杂周期（双周轮换等）走自定义 resolver SPI |
| 3 | calendar_group 嵌套 | 不支持。flat group 即可，避免复杂度 |
| 4 | disaster override 是否需要审批 | **必经审批**（reuse `approval_request`，approval_type=DISASTER_DAY_OVERRIDE）；高风险动作不能裸跑 |

### 不会做

- ❌ 不引入 ICU / iCal RFC 5545 RRULE（过度工程）
- ❌ 不让 calendar 自动同步外部公假日 API（数据来源不可控、合规风险）
- ❌ v1 不做"calendar fork / branch"（calendar A 派生 calendar A'）
- ❌ disaster override 不做 effective_at 未来生效（只解当下灾难，未来停业走正常 holiday 维护）
