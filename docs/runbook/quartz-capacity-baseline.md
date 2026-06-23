# Quartz 容量基线压测 Runbook

> **目标**：测出本项目部署形态下 Quartz 共库的真实拐点，作为 [`quartz-replacement-evaluation.md`](../architecture/quartz-replacement-evaluation.md) 阶段 1 启动决策的实测依据。
>
> **何时跑**：阶段 0 准备时跑一次拿基线；之后每半年或扩容后跑一次刷新；业务量级增长接近上一次基线 70% 时立即重跑。

---

## 1. 已暴露的 4 个 micrometer 指标

batch-trigger 在 2026-04-25 加入了观测埋点（`io.github.pinpols.batch.trigger.observability`）：

| 指标 | 类型 | 说明 | 拐点信号 |
|---|---|---|---|
| `batch.trigger.quartz.fire.total` | Counter（按 group tag） | 每次 `jobWasExecuted` +1；用 `rate(...[5m])` 算 QPS | > 50 QPS 持续 5 min = 红色 |
| `batch.trigger.quartz.execution.duration` | Timer（按 group tag） | jobToBeExecuted → jobWasExecuted 总耗时；含 acquire 锁等待 | P99 > 500 ms 持续 10 min = 红色 |
| `batch.trigger.quartz.misfire.total` | Counter | `triggerMisfired` 回调 +1 | rate > 0 持续 5 min = 黄色，> 100/分钟 = 红色 |
| `batch.trigger.quartz.triggers.active` | Gauge（按 group=all） | `Scheduler.getJobKeys().size()` | 信息性指标，配合 fire QPS 算"每 trigger 平均 fire 率" |

> WAL 字节占比指标改由 pg_exporter + Grafana 直查 `pg_stat_database`，不在 Java 侧维护。`observability-stack.md` 已含 PG 指标采集；只需在 Grafana 加一个 panel：`rate(pg_stat_database_xact_commit{datname='batch_platform'}[5m])` 拆按 schema 即可。

---

## 2. 压测方法（人工，按需）

不预制自动 inject 工具，因为真要测时数据来源差异大（staging 真实 cron / 临时 mock / 部分租户压测）。按下面三种姿势挑一种：

### 2.1 姿势 A：用真实 staging 流量自然观察（推荐）

最低成本：把 staging 跑 7 天，让 Grafana 自然采集 4 个指标。**绝对值不重要，重要的是看曲线变化趋势**——`fire.total` 增长、`execution.duration.p99` 是否同步抬升、`misfire.total` 是否非零。

适合：当前业务量级 < 100 万 fire/天，没必要造流量。

### 2.2 姿势 B：批量灌 enabled job_definition

往 `batch.job_definition` 表批量插 N 行 cron 表达式不同的 job，等 `TriggerReconciler` 自动同步到 Quartz。例：

```sql
-- 灌 1000 个 5 秒级 trigger（注意 schedule_expr 是 6 字段 Quartz cron）
DO $$
DECLARE
  i INT;
BEGIN
  FOR i IN 1..1000 LOOP
    INSERT INTO batch.job_definition (
      tenant_id, job_code, job_name, job_type, worker_group,
      schedule_type, schedule_expr, timezone,
      enabled, created_by, updated_by, created_at, updated_at
    ) VALUES (
      'loadtest-tenant',
      'loadtest_quartz_' || i,
      'Quartz capacity test job ' || i,
      'GENERAL',
      'GENERAL',
      'CRON',
      -- 把 1000 个 trigger 散开到 60 秒不同的秒/分槽位，避免同时点风暴
      (i % 60) || ' */1 * * * ?',
      'Asia/Shanghai',
      true, 'loadtest', 'loadtest', now(), now()
    );
  END LOOP;
END $$;

-- 触发 reconciler 立即同步（或等下次自动 tick）
-- 通过 trigger 模块 ops 端点（如有）或重启 trigger 进程
```

**清理**（测试完必做）：

```sql
DELETE FROM batch.job_definition WHERE tenant_id='loadtest-tenant' AND job_code LIKE 'loadtest_quartz_%';
-- 等 reconciler 同步删除 Quartz 端，或手工：
-- DELETE FROM quartz.QRTZ_TRIGGERS WHERE TRIGGER_NAME LIKE 'loadtest_quartz_%';
```

适合：想测 1k / 10k / 100k 三档活跃 trigger 的拐点。

### 2.3 姿势 C：直接调 Quartz API 注册 dummy job（只测调度，不进 LaunchService）

跳过 job_definition 表 + reconciler 链路，**直接通过 Quartz API 给 scheduler 注册 N 个 dummy trigger**，job 实现是空 callback——只测 Quartz 引擎本身的吞吐 / 锁竞争，不污染业务链路。

需要 trigger 模块加一个 **dev profile only** 的 ops 端点（当前未实现，按需加）：

```java
@RestController
@RequestMapping("/api/trigger/loadtest")
@Profile({"dev", "loadtest"})  // 生产 profile 不暴露
class QuartzLoadTestController {
  @PostMapping("/inject")
  ResponseEntity<?> inject(@RequestParam int count, @RequestParam(defaultValue = "0/5 * * * * ?") String cron) {
    for (int i = 0; i < count; i++) {
      JobDetail job = JobBuilder.newJob(NoOpJob.class)
          .withIdentity("loadtest-" + i, "loadtest")
          .build();
      Trigger trigger = TriggerBuilder.newTrigger()
          .withIdentity("loadtest-" + i, "loadtest")
          .withSchedule(CronScheduleBuilder.cronSchedule(cron))
          .build();
      scheduler.scheduleJob(job, trigger);
    }
    return ResponseEntity.ok(Map.of("injected", count));
  }

  @PostMapping("/cleanup")
  ResponseEntity<?> cleanup() throws SchedulerException {
    scheduler.deleteJobs(scheduler.getJobKeys(GroupMatcher.groupEquals("loadtest")).stream().toList());
    return ResponseEntity.ok().build();
  }
}
```

**适合**：精确测 Quartz 引擎本身的拐点，不被业务执行干扰。如果真要走阶段 1，先加这个端点投入压测；目前阶段 0 不需要。

---

## 3. 观察 Grafana

在 `docker/observability/grafana-dashboard-batch-coverage.json` 加 4 个 panel（dashboard JSON 里追加 panel block）：

| Panel 标题 | PromQL | 单位 | 阈值 |
|---|---|---|---|
| Quartz Fire QPS | `sum(rate(batch_trigger_quartz_fire_total[5m]))` | ops/s | 黄 10 / 红 50 |
| Quartz Execution P99 | `histogram_quantile(0.99, sum by (le)(rate(batch_trigger_quartz_execution_duration_seconds_bucket[5m])))` | s | 黄 0.1 / 红 0.5 |
| Quartz Misfire Rate | `sum(rate(batch_trigger_quartz_misfire_total[5m])) * 60` | misfire/min | 黄 1 / 红 100 |
| Active Triggers | `batch_trigger_quartz_triggers_active` | count | 信息性 |

**注意 micrometer 命名映射到 Prometheus 时点变下划线**：`batch.trigger.quartz.fire.total` → `batch_trigger_quartz_fire_total`。

PG 侧补一个 panel（用 pg_exporter）：

| Panel | PromQL |
|---|---|
| Quartz schema commit rate | `rate(pg_stat_user_tables_n_tup_upd{schemaname='quartz'}[5m])` |

---

## 4. 拐点判断（执行结果如何读）

### 4.1 绿色（继续观察，不用动）

```
Fire QPS:           < 10
Execution P99:      < 50 ms
Misfire/min:        0
Active triggers:    < 1000
```

→ 现状（共库共 schema）远低于拐点。**不要做任何 Quartz 替换**。继续把 micrometer 接入 Grafana,等下次刷新。

### 4.2 黄色（启动阶段 0 准备，3-6 个月内可能要做）

```
Fire QPS:           10-50
Execution P99:      50-300 ms
Misfire/min:        > 0 偶发
Active triggers:    1000-10000
```

→ 接近舒适区上限。动作：
1. 跑姿势 B 模拟 2 倍当前量,看 P99 / misfire 怎么变
2. 把 `quartz-replacement-evaluation.md` 阶段 1 的 1 人月工程列入排期
3. Grafana 告警阈值收紧（红色阈值降一档）

### 4.3 红色（立即启动阶段 1）

```
Fire QPS:           > 50
Execution P99:      > 500 ms
Misfire/min:        > 100
Active triggers:    > 10000
QRTZ_LOCKS 抢锁等待已成主因
```

→ Quartz 共库已是瓶颈。**立即按 quartz-replacement-evaluation.md §6 阶段 1 启动时间轮替换**，1 人月工期。

> 同时排查是否有"误注册"trigger（loadtest 残留 / 业务方误把高频任务直接放 cron 表达式）：先清孤儿能给阶段 1 争取时间。

---

## 5. 压测报告归档

每次跑完留一份 markdown 到 `docs/analysis/quartz-capacity-baseline-YYYY-MM-DD.md`，模板：

```markdown
# Quartz 容量基线 — 2026-XX-XX

## 测试形态
- 部署：staging / docker-compose / 裸 jar
- 数据来源：姿势 A 真实流量 / 姿势 B 灌 N=xxx / 姿势 C 直接注入
- 压测时长：X 小时
- PG 硬件：xx core / xx GB / NVMe

## 关键指标快照
| 指标 | 值 | 拐点判定 |
|---|---|---|
| Fire QPS（峰值） | ... | 🟢/🟡/🔴 |
| Execution P99 | ... | 🟢/🟡/🔴 |
| Misfire/min | ... | 🟢/🟡/🔴 |
| Active triggers | ... | 信息性 |

## QRTZ_LOCKS 等待时间（PG 侧补充）
- pg_stat_activity 中 `QRTZ_LOCKS` 上的等待事件 TOP 5
- 持锁时长 P99

## 结论
- 当前位于绿/黄/红
- 距离下一档拐点估计还有 X 倍业务量增长空间
- 建议下一步动作

## 附录
- Grafana dashboard 截图
- PromQL 原始查询
```

---

## 6. 相关代码

- 4 个指标定义：`batch-trigger/src/main/java/io/github/pinpols/batch/trigger/observability/QuartzMetrics.java`
- JobListener / TriggerListener 实现：`batch-trigger/.../observability/QuartzMetricsListener.java`
- 启动期注册 + gauge：`batch-trigger/.../observability/QuartzMetricsConfiguration.java`
- 关闭开关（默认开）：`batch.trigger.quartz-metrics.enabled=false`

## 7. 相关文档

- [`docs/architecture/quartz-replacement-evaluation.md`](../architecture/quartz-replacement-evaluation.md) §7 拐点预警
- [`docs/runbook/feature-switches.md`](./feature-switches.md) §3.4（Phase 2 quartz-datasource 开关移除说明）
- [`docs/runbook/observability-stack.md`](./observability-stack.md) PG 监控
