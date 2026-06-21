# 批量系统统一时区检查与设计建议

## 1. 背景

当前系统已经具备批量日相关能力，例如：

- `business_calendar.cutoff_time`
- `late_arrival_tolerance_min`
- `batch_day_instance`
- `CalendarBizDateResolver`
- `trigger_runtime_state.next_fire_time`
- `DefaultLaunchService.launch()`

但“有批量日”和“全系统时间语义统一”不是一回事。

如果系统未来存在以下情况：

```text
orchestrator / trigger / worker / console 部署在不同机器
不同机器处于不同时区
容器时区和宿主机时区不一致
业务日历涉及中国、日本、欧美等不同时区
后续支持夏令时地区
```

那么必须明确统一时间模型，避免因为机器本地时区不同导致批量日、调度触发、SLA、文件到达判断出现偏差。

---

## 2. 总体结论

系统可以跨时区物理机器部署，但不能依赖机器本地时区。

正确口径应该是：

```text
事件时间：统一 UTC
业务日期：由 business_calendar.timezone + cutoff_time 计算
调度时间：由 cron_expression + schedule_timezone 计算，再转成 UTC 存储
orchestrator：负责 bizDate 和 batch_day_instance 的最终裁决
worker：只执行任务，不解释业务日期和时区
console：只负责展示时区转换
```

如果当前只是统一了这些配置：

```yaml
spring.jackson.time-zone: Asia/Shanghai
TZ: Asia/Shanghai
-Duser.timezone=Asia/Shanghai
```

这还不算真正的统一时区。

这只是运行环境层面的回退，不能作为业务时间判断依据。

---

## 3. 真正可靠的统一时区模型

### 3.1 真实事件时间统一 UTC

所有表示“真实发生时间点”的字段，都应使用 UTC 语义。

典型字段包括：

```text
created_at
updated_at
triggered_at
started_at
finished_at
settled_at
next_fire_time
last_fire_time
file_arrived_at
expected_fire_time
actual_fire_time
```

数据库推荐：

```sql
timestamptz
```

Java 推荐：

```java
Instant
OffsetDateTime
```

不建议用 `LocalDateTime` 表达真实发生时间，因为它没有时区信息。

---

### 3.2 业务日期由业务日历时区计算

`biz_date` 不是 UTC 日期，也不是机器本地日期。

它应该由业务日历决定：

```text
calendar_code = CN_MAIN
timezone = Asia/Shanghai
cutoff_time = 06:00
```

或者：

```text
calendar_code = JP_MAIN
timezone = Asia/Tokyo
cutoff_time = 06:00
```

计算逻辑：

```text
fire_time_utc
  ↓
转换到 business_calendar.timezone
  ↓
得到 local date + local time
  ↓
如果 local time < cutoff_time，则 bizDate = local date - 1
  ↓
否则 bizDate = local date
```

示例：

```text
calendar.timezone = Asia/Shanghai
cutoff_time = 06:00
fire_time_utc = 2026-05-04T21:30:00Z
```

转换到上海时间：

```text
2026-05-05 05:30 Asia/Shanghai
```

由于还没到 06:00，所以：

```text
bizDate = 2026-05-04
```

---

### 3.3 调度时间按 schedule_timezone 解释

cron 表达式不能按照机器默认时区解释。

推荐优先级：

```text
job_definition.schedule_timezone
  ↓ 没有则用
business_calendar.timezone
  ↓ 没有则用
platform.default_timezone
```

`next_fire_time` 应存 UTC 时间点。

例如：

```text
cron = 0 0 6 * * ?
schedule_timezone = Asia/Shanghai
next_fire_time_utc = 2026-05-04T22:00:00Z
```

trigger 只需要比较：

```text
now_utc >= next_fire_time_utc
```

这样不管 trigger 部署在哪个时区，都不会影响触发判断。

---

## 4. 当前系统最可能存在的风险点

### 4.1 只有 cutoff_time，但没有 timezone

如果 `business_calendar` 中只有：

```sql
cutoff_time time
```

没有：

```sql
timezone varchar(64)
```

那么 `06:00` 到底是哪一个时区的 06:00 就不明确。

建议增加：

```sql
timezone varchar(64) not null default 'Asia/Shanghai'
```

并要求使用 IANA ZoneId，例如：

```text
Asia/Shanghai
Asia/Tokyo
America/New_York
Europe/London
```

不要使用固定 offset，例如：

```text
+08:00
+09:00
-05:00
```

固定 offset 无法正确处理夏令时。

---

### 4.2 next_fire_time 如果是 LocalDateTime，会有隐患

如果当前代码或实体中存在：

```java
LocalDateTime nextFireTime;
LocalDateTime.now();
```

或者数据库字段是：

```sql
timestamp without time zone
```

那么在跨时区部署、容器时区变化、夏令时切换时容易出错。

推荐：

```java
Instant nextFireTime;
Instant now = clock.instant();
```

数据库：

```sql
next_fire_time timestamptz
last_fire_time timestamptz
```

---

### 4.3 trigger 和 orchestrator 不能各算各的 bizDate

trigger 可以负责：

```text
计算 next_fire_time
到点触发
生成 LaunchRequest
```

但 bizDate 最终应该由 orchestrator 裁决。

推荐流程：

```text
trigger 到点
  ↓
生成 LaunchRequest，携带 fire_time_utc / schedule_id / calendar_code
  ↓
orchestrator 调用 CalendarBizDateResolver
  ↓
确定 bizDate
  ↓
确定 batch_day_instance
  ↓
创建 job_instance / workflow_run / partition / task
```

不建议：

```text
trigger 算一次 bizDate
orchestrator 算一次 bizDate
worker 再算一次 bizDate
```

这会导致批量日归属不一致。

---

### 4.4 worker 不能自行计算业务日期

worker 可以记录执行开始和结束时间，但不能自己判断业务日。

错误示例：

```java
LocalDate bizDate = LocalDate.now();
LocalDateTime now = LocalDateTime.now();
ZoneId zone = ZoneId.systemDefault();
```

正确做法：

```text
bizDate 从 task context / job_instance 获取
batchDayId 从 orchestrator 下发
calendarCode 从 orchestrator 下发
startedAt / finishedAt 使用 Instant.now(clock)
```

worker 任务上下文建议至少包含：

```text
tenantId
calendarCode
bizDate
batchDayId
jobInstanceId
workflowRunId
partitionKey
scheduledFireTimeUtc
triggeredAtUtc
```

worker 的职责是执行任务，不参与时区和批量日判断。

---

## 5. 模块职责边界

### 5.1 trigger

trigger 负责：

```text
维护 trigger_runtime_state
计算 next_fire_time
到点触发
生成 LaunchRequest
```

要求：

```text
cron 必须按 schedule_timezone 解释
next_fire_time 必须按 UTC 存储
trigger 不依赖机器默认时区
trigger 不作为 bizDate 最终裁决者
```

---

### 5.2 orchestrator

orchestrator 是时间语义和批量日归属的核心裁决者。

负责：

```text
解析 bizDate
创建或获取 batch_day_instance
判断前一批量日是否已结清
处理补跑 / 跳批 / 人工释放
创建 job_instance
创建 workflow_run
创建 partition / task
```

关键类建议集中在：

```text
DefaultLaunchService
CalendarBizDateResolver
BatchDayService
BatchDayGateService
```

---

### 5.3 worker

worker 负责：

```text
消费任务
执行 import/export/dispatch
上报执行结果
记录 started_at / finished_at
```

不负责：

```text
计算 bizDate
判断 cutoff
判断日切
判断前一批量日是否可继续
处理夏令时 gap/overlap
```

---

### 5.4 console

console 负责：

```text
展示 UTC 时间对应的本地时间
展示 calendar.timezone
展示 bizDate
展示 batch_day_instance 状态
支持用户按展示时区查看
```

console 不应参与业务裁决。

---

## 6. 数据库字段建议

### 6.1 business_calendar

建议包含：

```sql
calendar_code varchar(64) not null,
timezone varchar(64) not null default 'Asia/Shanghai',
cutoff_time time not null default '06:00:00',
late_arrival_tolerance_min integer,
expected_settle_time time,
latest_settle_time time
```

说明：

```text
timezone 使用 IANA ZoneId
cutoff_time 是 timezone 下的本地业务时间
expected_settle_time / latest_settle_time 也是 timezone 下的本地业务时间
```

---

### 6.2 job_definition

建议支持：

```sql
schedule_timezone varchar(64)
```

解释优先级：

```text
job_definition.schedule_timezone
business_calendar.timezone
platform.default_timezone
```

如果作业不配置独立调度时区，则继承业务日历时区。

---

### 6.3 trigger_runtime_state

建议：

```sql
next_fire_time timestamptz,
last_fire_time timestamptz,
schedule_timezone varchar(64),
scheduled_local_time time,
scheduled_local_date date
```

其中：

```text
next_fire_time / last_fire_time 是 UTC 事件时间点
schedule_timezone 是 cron 的解释时区
scheduled_local_time / scheduled_local_date 可用于审计和 DST 排查
```

---

### 6.4 batch_day_instance

建议保存时区快照：

```sql
calendar_code varchar(64) not null,
biz_date date not null,
timezone varchar(64) not null,
cutoff_time time not null,
opened_at timestamptz,
settled_at timestamptz,
status varchar(32)
```

为什么要保存时区快照？

因为日历配置未来可能修改。历史批量日应该保留当时使用的 timezone 和 cutoff_time，避免补跑或审计时口径漂移。

---

### 6.5 job_instance

建议包含：

```sql
calendar_code varchar(64),
biz_date date,
batch_day_id bigint,
scheduled_fire_time timestamptz,
triggered_at timestamptz,
started_at timestamptz,
finished_at timestamptz
```

说明：

```text
biz_date 是业务日历日期
scheduled_fire_time / triggered_at / started_at / finished_at 是 UTC 事件时间点
```

---

## 7. Java 代码建议

### 7.1 引入统一 Clock

建议提供统一时间组件：

```java
@Component
public class PlatformClock {

    private final Clock clock;

    public PlatformClock(Clock clock) {
        this.clock = clock;
    }

    public Instant nowInstant() {
        return Instant.now(clock);
    }

    public ZonedDateTime nowAt(ZoneId zoneId) {
        return nowInstant().atZone(zoneId);
    }
}
```

配置：

```java
@Bean
public Clock platformClock() {
    return Clock.systemUTC();
}
```

测试时可以替换：

```java
Clock.fixed(...)
```

这样可以稳定测试：

```text
cutoff 前后
跨日
跨时区
夏令时 gap
夏令时 overlap
补跑区间
```

---

### 7.2 CalendarBizDateResolver

推荐逻辑：

```java
public LocalDate resolveBizDate(Instant fireTimeUtc, BusinessCalendar calendar) {
    ZoneId zoneId = ZoneId.of(calendar.getTimezone());
    ZonedDateTime local = fireTimeUtc.atZone(zoneId);

    LocalDate localDate = local.toLocalDate();
    LocalTime localTime = local.toLocalTime();

    if (localTime.isBefore(calendar.getCutoffTime())) {
        return localDate.minusDays(1);
    }

    return localDate;
}
```

注意：

```text
传入 Instant
显式使用 calendar.timezone
不要使用 ZoneId.systemDefault()
不要使用 LocalDateTime.now()
```

---

### 7.3 DefaultLaunchService

推荐职责：

```text
接收 LaunchRequest
读取 job_definition / business_calendar
用 fire_time_utc 调用 CalendarBizDateResolver
确定 bizDate
获取或创建 batch_day_instance
检查前一批量日门闩
检查补跑 / 跳批 / 人工释放状态
创建 job_instance
```

如果 `LaunchRequest` 中携带了 `expectedBizDate`，也只能作为参考或审计字段，最终仍应由 orchestrator 校验。

---

## 8. 代码扫描清单

在项目中搜索以下用法：

```bash
grep -R "LocalDate.now" .
grep -R "LocalDateTime.now" .
grep -R "ZonedDateTime.now" .
grep -R "OffsetDateTime.now" .
grep -R "ZoneId.systemDefault" .
grep -R "new Date" .
grep -R "Calendar.getInstance" .
```

分类处理：

| 出现位置 | 判断 |
|---|---|
| `CalendarBizDateResolver` | 可以，但必须基于传入 Instant 和 calendar.timezone |
| `DefaultLaunchService` | 可以使用统一 Clock，不可直接用系统默认时区算 bizDate |
| `WheelTriggerReconciler` | 可以使用 Clock，next_fire_time 应为 UTC |
| `BatchDaySettleScheduler` | 可以使用 Clock，settle 判断需基于 calendar.timezone |
| `worker-*` | 不应使用当前时间判断 bizDate |
| `console-api` | 可以用于展示转换 |
| 测试代码 | 推荐使用 `Clock.fixed()` |

---

## 9. 冬夏令时检查

如果业务只在中国、日本、新加坡、香港，一般不涉及 DST。

但平台设计仍建议支持 DST，因为未来接入欧美业务时会受影响。

要求：

```text
timezone 必须是 IANA ZoneId
不能使用固定 UTC offset
cron next_fire_time 计算必须 DST-aware
cutoff_time 应按 calendar.timezone 解释
DST gap/overlap 由统一策略处理
worker 不参与 DST 判断
```

典型问题：

```text
夏令时开始：某个本地时间不存在，例如 02:30 不存在
冬令时结束：某个本地时间重复，例如 01:30 出现两次
```

推荐默认策略：

```text
DST gap：RUN_AT_NEXT_VALID_TIME
DST overlap：RUN_ONCE / USE_EARLIER_OFFSET
```

对于日批类作业，通常建议：

```text
同一个 job_code + schedule_id + bizDate 只触发一次
```

避免冬令时重复小时导致重复跑批。

---

## 10. 最终统一规则

建议把系统时间口径定为：

```text
1. 所有 created_at / updated_at / started_at / finished_at / triggered_at / settled_at 使用 UTC 事件时间；
2. 数据库事件时间字段使用 timestamptz；
3. Java 真实事件时间使用 Instant；
4. biz_date 使用 LocalDate，但只能由 CalendarBizDateResolver 计算；
5. cutoff_time 是 business_calendar.timezone 下的本地时间；
6. cron 按 schedule_timezone 解释；
7. next_fire_time 存 UTC Instant；
8. trigger 只比较 now_utc 和 next_fire_time_utc；
9. orchestrator 是 bizDate 和 batch_day_instance 的唯一裁决者；
10. worker 不允许自行计算 bizDate；
11. console 只做展示时区转换；
12. 禁止业务逻辑依赖 ZoneId.systemDefault()；
13. 生产容器建议统一 TZ=UTC，JVM 可设置 -Duser.timezone=UTC 作为回退；
14. 业务时区必须来自配置，不来自机器环境。
```

---

## 11. P0 落地清单

建议优先完成：

```text
1. business_calendar 增加 timezone 字段；
2. batch_day_instance 保存 timezone / cutoff_time 快照；
3. trigger_runtime_state.next_fire_time 改为 UTC 事件时间语义；
4. Java 侧真实事件时间统一使用 Instant；
5. CalendarBizDateResolver 显式使用 calendar.timezone；
6. DefaultLaunchService 统一裁决 bizDate；
7. worker 禁止使用本机时间计算业务日；
8. 引入 PlatformClock；
9. 增加 cutoff 前后单元测试；
10. 增加跨时区单元测试；
11. 增加 DST gap / overlap 测试用例；
12. console 展示同时显示业务时区和 UTC 时间。
```

---

## 12. 推荐测试用例

### 12.1 cutoff 前归属前一业务日

```text
timezone = Asia/Shanghai
cutoff_time = 06:00
fire_time_utc = 2026-05-04T21:30:00Z
local_time = 2026-05-05 05:30
expected_biz_date = 2026-05-04
```

---

### 12.2 cutoff 后归属当天业务日

```text
timezone = Asia/Shanghai
cutoff_time = 06:00
fire_time_utc = 2026-05-04T22:30:00Z
local_time = 2026-05-05 06:30
expected_biz_date = 2026-05-05
```

---

### 12.3 东京机器部署但业务日历是上海

```text
machine_timezone = Asia/Tokyo
calendar.timezone = Asia/Shanghai
cutoff_time = 06:00
```

预期：

```text
bizDate 只按 Asia/Shanghai 计算
不能受机器 Asia/Tokyo 影响
```

---

### 12.4 DST gap

```text
timezone = America/New_York
cron = 每天 02:30
夏令时开始日 02:30 不存在
```

预期：

```text
按 dst_gap_policy 处理
默认 RUN_AT_NEXT_VALID_TIME
```

---

### 12.5 DST overlap

```text
timezone = America/New_York
cron = 每天 01:30
冬令时结束日 01:30 出现两次
```

预期：

```text
日批类作业同一个 bizDate 只跑一次
或者按显式策略允许跑两次
```

---

## 13. 一句话总结

机器可以跨时区部署，但业务时间不能跨口径。

你的系统不要只统一机器时区，而要统一时间语义：

```text
UTC 存事件时间；
business_calendar.timezone 算业务日；
schedule_timezone 解释 cron；
orchestrator 统一裁决；
worker 只执行不算日期。
```
