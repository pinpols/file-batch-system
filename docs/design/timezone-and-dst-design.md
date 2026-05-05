# 批量平台时区与夏令时设计说明

## 1. 背景

批量平台由多个模块组成，包括：

- trigger
- orchestrator
- worker-import
- worker-export
- worker-dispatch
- console-api
- PostgreSQL
- Kafka
- MinIO

这些模块未来可能部署在不同物理机器、不同容器节点、不同云区，甚至不同国家或地区。

因此，平台不能依赖机器本地时区来判断：

- 当前业务日期
- 批量日归属
- 日切时间
- cutoff 归属
- trigger 触发时间
- 文件到达 SLA
- 批量日结算 SLA
- 补跑区间
- 前一批量日等待关系

否则在跨时区部署、容器迁移、云区切换、夏令时切换时，容易出现批量日错乱、重复触发、漏触发、SLA 判断错误等问题。

---

## 2. 核心结论

平台允许跨时区部署，但业务时间必须由平台统一控制。

统一原则如下：

```text
机器可以跨时区，业务不能跨口径。
```

最终规则：

```text
1. 所有真实事件时间统一使用 UTC 存储和比较；
2. 所有批量日归属由 business_calendar.timezone + cutoff_time 计算；
3. timezone 必须使用 IANA ZoneId，例如 Asia/Shanghai、Asia/Tokyo、America/New_York；
4. 禁止使用固定 offset，例如 +08:00、+09:00、-05:00 表达业务时区；
5. trigger 的 next_fire_time 使用 UTC Instant 存储；
6. cron 表达式按 schedule_timezone 解释；
7. orchestrator 是 bizDate 和 batch_day_instance 的最终裁决者；
8. worker 不允许自行根据机器时区计算 bizDate；
9. console 只负责展示时区转换，不参与业务裁决；
10. 夏令时 gap / overlap 必须有统一策略。
```

---

## 3. 需要区分的三类时间

### 3.1 系统事件时间

系统事件时间表示某个动作真实发生的时间点。

例如：

- created_at
- updated_at
- triggered_at
- started_at
- finished_at
- next_fire_time
- last_fire_time
- file_arrived_at
- settled_at
- archived_at

这类字段应该统一使用 UTC 语义。

数据库推荐：

```sql
created_at timestamptz not null
updated_at timestamptz not null
triggered_at timestamptz
started_at timestamptz
finished_at timestamptz
next_fire_time timestamptz
last_fire_time timestamptz
file_arrived_at timestamptz
settled_at timestamptz
```

Java 推荐：

```java
Instant
OffsetDateTime
```

不建议使用 `LocalDateTime` 表达真实发生时间，因为 `LocalDateTime` 不带时区，跨时区场景下容易产生歧义。

---

### 3.2 业务日期

业务日期不是 UTC 日期，也不是机器本地日期。

业务日期应该由业务日历决定：

```text
business_calendar.timezone + cutoff_time
```

例如：

```text
calendar_code = CN_MAIN
timezone = Asia/Shanghai
cutoff_time = 06:00
```

如果触发时间是：

```text
fire_time_utc = 2026-05-04T21:30:00Z
```

转换到上海时间：

```text
2026-05-05 05:30 Asia/Shanghai
```

因为还没有到 cutoff 06:00，所以：

```text
bizDate = 2026-05-04
```

业务日期字段推荐：

```sql
biz_date date not null
```

Java 推荐：

```java
LocalDate
```

注意：`biz_date` 是平台根据业务日历计算出来的结果，不能使用数据库当前日期或机器当前日期直接代替。

---

### 3.3 展示时间

控制台展示时间可以按：

- 用户时区
- 浏览器时区
- 平台默认展示时区
- 业务日历时区

进行格式化展示。

但展示时区不能反向影响调度、批量日归属、补跑、结算、依赖判断。

建议控制台同时展示关键时间的两种形式：

```text
UTC 时间 + 业务日历本地时间
```

例如：

```text
triggered_at_utc: 2026-05-04T21:30:00Z
calendar_local_time: 2026-05-05 05:30 Asia/Shanghai
biz_date: 2026-05-04
```

---

## 4. 跨时区部署风险

如果不同模块直接使用机器本地时间，会出现明显风险。

例如：

```text
orchestrator 部署在东京
trigger 部署在上海
worker-import 部署在新加坡
PostgreSQL 使用 UTC
```

同一个真实时间点可能对应不同本地日期：

```text
UTC:          2026-05-04 15:30
Asia/Shanghai: 2026-05-04 23:30
Asia/Tokyo:    2026-05-05 00:30
```

如果各模块分别使用 `LocalDate.now()` 或 `ZoneId.systemDefault()`，可能导致：

```text
trigger 认为是 2026-05-04
orchestrator 认为是 2026-05-05
worker 又按另一个日期写结果
```

后果包括：

- job_instance 归属错误批量日
- batch_day_instance 结算不准确
- 前一批量日等待失效
- 补跑区间错乱
- 文件归档路径错误
- 结果版本归属错误
- 下游依赖误释放或误阻塞

---

## 5. 模块职责边界

### 5.1 trigger

trigger 负责调度触发，不负责最终业务日期裁决。

trigger 应做：

```text
1. 按 cron_expression + schedule_timezone 计算 next_fire_time；
2. 将 next_fire_time 转为 UTC Instant 存储；
3. 到点后生成 LaunchRequest；
4. LaunchRequest 中携带 fire_time_utc、schedule_timezone、calendar_code 等上下文。
```

trigger 不应做：

```text
1. 不应依赖机器默认时区；
2. 不应直接用 LocalDate.now() 决定 bizDate；
3. 不应成为 bizDate 的唯一裁决者。
```

---

### 5.2 orchestrator

orchestrator 是业务日期和批量日归属的最终裁决者。

orchestrator 应负责：

```text
1. 根据 fire_time_utc + business_calendar.timezone + cutoff_time 计算 bizDate；
2. 创建或查找 batch_day_instance；
3. 判断前一批量日是否 SETTLED / SKIPPED；
4. 判断是否允许启动当前 job_instance；
5. 处理补跑、跳批、人工释放、SLA、依赖；
6. 将 bizDate、batchDayId、calendarCode 下发给 worker。
```

建议相关服务：

```text
CalendarBizDateResolver
BatchDayService
DefaultLaunchService
BatchDayGateService
```

---

### 5.3 worker

worker 只负责执行任务，不负责解释业务时间。

worker 接收到的任务上下文中应该已经包含：

```text
jobInstanceId
batchDayId
calendarCode
bizDate
partitionKey
scheduledFireTimeUtc
triggeredAtUtc
```

worker 不应该出现以下逻辑：

```java
LocalDate.now()
LocalDateTime.now()
ZonedDateTime.now()
ZoneId.systemDefault()
```

来判断业务日期或批量日。

worker 如果需要记录执行时间，应使用统一 Clock：

```java
Instant.now(clock)
```

---

### 5.4 console-api

console-api 主要负责展示和运维操作。

console-api 可以做：

```text
1. 将 UTC 时间转换为用户时区展示；
2. 将 UTC 时间转换为业务日历本地时间展示；
3. 展示 bizDate、calendarCode、timezone、cutoff_time；
4. 对人工补跑、跳批、重开、释放等操作进行审计。
```

console-api 不应该直接影响：

```text
1. bizDate 裁决；
2. next_fire_time 计算；
3. 批量日结算判断；
4. worker 执行上下文。
```

---

## 6. 数据模型建议

### 6.1 business_calendar

建议增加或确认以下字段：

```sql
alter table business_calendar
  add column timezone varchar(64) not null default 'Asia/Shanghai';
```

字段含义：

```text
timezone 表示该业务日历使用的 IANA ZoneId。
```

示例：

```text
Asia/Shanghai
Asia/Tokyo
America/New_York
Europe/London
Europe/Berlin
Australia/Sydney
```

不建议使用：

```text
+08:00
+09:00
-05:00
```

原因是固定 offset 无法正确表达夏令时规则。

---

### 6.2 job_definition

如果不同作业有独立调度时区，可以增加：

```sql
alter table job_definition
  add column schedule_timezone varchar(64);
```

优先级建议：

```text
job_definition.schedule_timezone
  ↓ 如果为空
business_calendar.timezone
  ↓ 如果为空
platform.default_timezone
```

通常情况下，作业调度时区可以继承业务日历时区。

---

### 6.3 trigger_runtime_state

建议 `next_fire_time` 使用 UTC 事件时间：

```sql
next_fire_time timestamptz
last_fire_time timestamptz
```

并明确：

```text
next_fire_time 表示下一次真实触发时间点，统一使用 UTC 语义比较。
```

trigger 到点判断只需要：

```text
now_utc >= next_fire_time_utc
```

不需要依赖机器本地时间。

---

### 6.4 batch_day_instance

建议包含：

```sql
calendar_code varchar(64) not null
biz_date date not null
status varchar(32) not null
timezone varchar(64) not null
opened_at timestamptz
settled_at timestamptz
closed_at timestamptz
```

其中：

```text
biz_date 是业务日期；
timezone 是当时批量日使用的业务日历时区快照；
opened_at / settled_at / closed_at 是 UTC 事件时间。
```

保留 timezone 快照的好处：

```text
即使后续 business_calendar.timezone 被修改，历史批量日仍然能解释当时的归属规则。
```

---

## 7. cutoff 计算规则

cutoff 必须绑定业务日历时区。

错误方式：

```java
LocalDate today = LocalDate.now();
LocalTime now = LocalTime.now();
```

正确语义：

```text
fire_time_utc
  ↓
转换到 business_calendar.timezone
  ↓
得到 localDate + localTime
  ↓
如果 localTime < cutoff_time，则 bizDate = localDate - 1
否则 bizDate = localDate
```

示例代码：

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

---

## 8. cron 与 next_fire_time 规则

cron 表达式不能脱离时区解释。

完整调度语义应该是：

```text
cron_expression + schedule_timezone → scheduled local time → Instant → next_fire_time_utc
```

例如：

```text
cron_expression = 0 0 6 * * ?
schedule_timezone = Asia/Shanghai
```

表示：

```text
每天上海时间 06:00 触发
```

存储时应该保存为 UTC：

```text
next_fire_time_utc = 2026-05-04T22:00:00Z
```

trigger 比较时只比较 UTC：

```text
now_utc >= next_fire_time_utc
```

不要自己用 `plusDays(1)` 计算下一次触发，尤其是在有夏令时的地区。

---

## 9. 夏令时设计

### 9.1 为什么需要考虑夏令时

中国大陆、日本、新加坡、香港等地区通常不涉及夏令时。

但如果平台接入美国、欧洲、澳洲、新西兰等业务日历，就必须考虑 DST。

夏令时有两个典型问题：

```text
1. DST gap：某个本地时间不存在；
2. DST overlap：某个本地时间出现两次。
```

---

### 9.2 DST gap：本地时间不存在

夏令时开始时，某些地区会从：

```text
01:59:59
直接跳到
03:00:00
```

那么当天：

```text
02:30
```

这个本地时间不存在。

如果作业配置为：

```text
每天 02:30 执行
```

平台必须决定当天如何处理。

建议策略：

```text
RUN_AT_NEXT_VALID_TIME：顺延到下一个合法时间，例如 03:00
SKIP：当天跳过
FAIL_FAST：标记调度异常，等待人工处理
```

默认建议：

```text
RUN_AT_NEXT_VALID_TIME
```

原因：批量系统通常更关注当天任务不能漏跑。

---

### 9.3 DST overlap：本地时间出现两次

冬令时切回时，某些地区会出现重复小时。

例如：

```text
01:00 - 02:00 出现两次
```

如果作业配置为：

```text
每天 01:30 执行
```

当天可能出现两个不同 UTC 时间点，对应同一个本地 01:30。

平台必须决定：

```text
只跑第一次
只跑第二次
两次都跑
```

建议策略：

```text
RUN_ONCE_EARLIER_OFFSET：只跑第一次
RUN_ONCE_LATER_OFFSET：只跑第二次
RUN_TWICE：两个 UTC 触发点都跑
```

对日批类作业，默认建议：

```text
RUN_ONCE_EARLIER_OFFSET
```

原因：日批作业通常语义是“某个业务日执行一次”，不是“每个 UTC 触发点都执行一次”。

对高频技术类任务，可以允许：

```text
RUN_TWICE
```

---

## 10. DST 策略配置建议

可以先在代码中固化默认策略，也可以在 business_calendar 或 job_definition 中配置。

推荐配置项：

```sql
alter table business_calendar
  add column dst_gap_policy varchar(32) not null default 'RUN_AT_NEXT_VALID_TIME',
  add column dst_overlap_policy varchar(32) not null default 'RUN_ONCE_EARLIER_OFFSET';
```

可选值：

```text
dst_gap_policy:
  RUN_AT_NEXT_VALID_TIME
  SKIP
  FAIL_FAST

dst_overlap_policy:
  RUN_ONCE_EARLIER_OFFSET
  RUN_ONCE_LATER_OFFSET
  RUN_TWICE
```

如果暂时不想扩表，也应在设计文档和代码常量中明确默认策略。

---

## 11. fire_key 与幂等控制

夏令时 overlap 会导致同一个本地时间出现两次。

为了避免重复触发或误判重复，平台需要稳定的调度触发键。

建议记录：

```text
schedule_fire_id
scheduled_fire_time_utc
scheduled_local_date
scheduled_local_time
schedule_timezone
biz_date
fire_sequence
```

不同业务语义下，唯一键不同。

### 11.1 日批类作业

如果语义是：

```text
同一个业务日只跑一次
```

唯一键建议：

```text
tenant_id
calendar_code
job_code
schedule_id
biz_date
```

或：

```text
tenant_id
job_code
schedule_id
scheduled_local_date
scheduled_local_time
schedule_timezone
```

这样 DST overlap 时不会因为两个 UTC instant 而重复生成两个日批实例。

---

### 11.2 高频技术类任务

如果语义是：

```text
每个真实 UTC 触发点都要执行
```

唯一键可以使用：

```text
tenant_id
job_code
schedule_id
scheduled_fire_time_utc
```

这种模式允许 DST overlap 时触发两次。

---

## 12. 补跑与夏令时

补跑不应该按 UTC 日期直接展开。

正确方式：

```text
按 business_calendar.timezone 下的 bizDate 区间展开。
```

例如：

```text
补跑 2026-11-01 到 2026-11-03
calendar.timezone = America/New_York
```

平台应该按业务日期展开：

```text
2026-11-01
2026-11-02
2026-11-03
```

然后每个 bizDate 再通过日历规则、cutoff、调度配置生成对应执行上下文。

不能简单按：

```text
UTC 00:00 到 UTC 23:59
```

划分业务日。

---

## 13. SLA 与文件时间规则

文件到达时间、批量日结算时间、作业最晚完成时间通常是业务本地时间。

例如：

```text
expected_arrival_time = 08:30
latest_arrival_time = 09:00
expected_settle_time = 07:00
latest_settle_time = 08:00
```

这些字段必须解释为：

```text
calendar.timezone 下的本地时间
```

判断时：

```text
bizDate + local time + calendar.timezone → Instant
```

然后和当前 UTC Instant 比较。

示例：

```text
bizDate = 2026-05-05
latest_arrival_time = 09:00
timezone = Asia/Shanghai
```

转换为：

```text
2026-05-05 09:00 Asia/Shanghai → UTC Instant
```

再判断文件是否晚到。

---

## 14. JVM 与容器时区建议

生产环境建议容器统一设置为 UTC：

```yaml
environment:
  TZ: UTC
```

JVM 参数也可以统一：

```bash
-Duser.timezone=UTC
```

但要注意：

```text
JVM / 容器时区只是兜底，不是业务时区来源。
```

业务时间必须来自：

```text
business_calendar.timezone
job_definition.schedule_timezone
```

禁止业务逻辑依赖：

```java
ZoneId.systemDefault()
```

---

## 15. 统一 Clock 设计

建议平台提供统一时间服务，便于测试和控制。

示例：

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

测试时可以替换为：

```java
Clock.fixed(...)
```

这样可以稳定测试：

- cutoff 前后归属
- 日切创建
- 前一批量日等待
- 补跑区间展开
- DST gap
- DST overlap

---

## 16. 代码扫描清单

需要重点检查以下用法：

```java
LocalDate.now()
LocalDateTime.now()
ZonedDateTime.now()
OffsetDateTime.now()
ZoneId.systemDefault()
new Date()
Calendar.getInstance()
```

处理原则：

```text
1. 如果是记录真实事件时间，改为 Instant.now(clock)；
2. 如果是计算业务日期，改为 CalendarBizDateResolver；
3. 如果是计算调度时间，使用 cron + schedule_timezone；
4. 如果是展示时间，放到 console/API 层转换；
5. worker 内禁止自行计算 bizDate。
```

---

## 17. 测试用例建议

### 17.1 基础跨时区测试

测试场景：

```text
calendar.timezone = Asia/Shanghai
cutoff_time = 06:00
```

用例：

```text
2026-05-04T21:30:00Z → 上海 2026-05-05 05:30 → bizDate = 2026-05-04
2026-05-04T22:30:00Z → 上海 2026-05-05 06:30 → bizDate = 2026-05-05
```

---

### 17.2 不同时区同一 Instant 测试

同一个 UTC 时间：

```text
2026-05-04T15:30:00Z
```

分别转换：

```text
Asia/Shanghai → 2026-05-04 23:30
Asia/Tokyo    → 2026-05-05 00:30
UTC           → 2026-05-04 15:30
```

确认 bizDate 只由 calendar.timezone 决定，而不是机器部署时区。

---

### 17.3 DST gap 测试

示例地区：

```text
America/New_York
```

配置：

```text
cron = 每天 02:30
```

夏令时开始日，02:30 不存在。

测试平台是否按策略处理：

```text
RUN_AT_NEXT_VALID_TIME
SKIP
FAIL_FAST
```

---

### 17.4 DST overlap 测试

示例地区：

```text
America/New_York
```

配置：

```text
cron = 每天 01:30
```

冬令时结束日，01:30 出现两次。

测试平台是否按策略处理：

```text
RUN_ONCE_EARLIER_OFFSET
RUN_ONCE_LATER_OFFSET
RUN_TWICE
```

---

### 17.5 worker 边界测试

验证 worker 任务上下文中必须包含：

```text
bizDate
batchDayId
calendarCode
scheduledFireTimeUtc
```

并通过代码扫描或单元测试确保 worker 不调用：

```java
LocalDate.now()
ZoneId.systemDefault()
```

来判断业务日期。

---

## 18. 推荐落地优先级

### P0：必须先做

```text
1. business_calendar 增加 timezone，使用 IANA ZoneId；
2. 所有真实事件时间统一 UTC；
3. next_fire_time 使用 UTC 存储；
4. cron 按 schedule_timezone 或 calendar.timezone 解释；
5. orchestrator 统一裁决 bizDate；
6. worker 禁止自行计算 bizDate；
7. 引入 PlatformClock；
8. 增加 cutoff 跨时区单元测试。
```

---

### P1：强烈建议

```text
1. 明确 DST gap 默认策略；
2. 明确 DST overlap 默认策略；
3. 增加 schedule_fire_key；
4. 增加美国/欧洲 DST 切换日测试；
5. batch_day_instance 保存 timezone 快照；
6. 控制台展示 UTC + 业务本地时间。
```

---

### P2：平台成熟后增强

```text
1. 支持按 calendar/job 配置 DST 策略；
2. 支持高频任务与日批任务不同 fire_key 策略；
3. 支持多区域业务日历；
4. 支持历史日历版本；
5. 支持时区变更影响分析；
6. 支持补跑时选择历史日历版本。
```

---

## 19. 推荐最终口径

平台应形成以下统一口径：

```text
1. 物理机器、容器、JVM 可以运行在任意时区；
2. 平台不信任机器本地时区；
3. 所有事件时间统一用 UTC；
4. 所有业务日期统一由 business_calendar.timezone + cutoff_time 计算；
5. 所有 cron 调度统一由 schedule_timezone 解释；
6. timezone 必须使用 IANA ZoneId，不能使用固定 offset；
7. DST gap 和 DST overlap 必须有默认处理策略；
8. orchestrator 是 bizDate 和 batch_day_instance 的唯一裁决者；
9. worker 只执行任务，不解释业务时间；
10. console 只展示时间，不参与业务裁决。
```

---

## 20. 一句话总结

```text
跨时区部署不是问题，问题是业务时间必须统一口径。
```

对于当前批量平台，推荐最终模型是：

```text
数据库事件时间：UTC
业务日历时区：business_calendar.timezone
调度解释时区：job_definition.schedule_timezone，默认继承 calendar.timezone
批量日归属：calendar.timezone + cutoff_time
夏令时处理：calendar/schedule 服务统一裁决
worker：只接收 bizDate 和 batchDayId，不参与时间解释
```
