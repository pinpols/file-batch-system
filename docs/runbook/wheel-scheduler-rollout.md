# Wheel Scheduler 灰度上线 SOP

> 替代 Quartz 的时间轮 trigger scheduler 上线运维手册。
>
> **配套**:
>
> - [`docs/architecture/quartz-replacement-design.md`](../architecture/quartz-replacement-design.md) §11 灰度切换防护
> - [`docs/architecture/quartz-replacement-evaluation.md`](../architecture/quartz-replacement-evaluation.md) 战略决策
> - [`docs/runbook/quartz-capacity-baseline.md`](./quartz-capacity-baseline.md) 拐点判断

---

## 0. 前置条件(Pre-flight Checklist)

**任何一项 ❌ 都不许动手**。

| # | 检查 | 命令/方法 | 通过标准 |
|---|---|---|---|
| 1 | Quartz 容量 metric 显示接近拐点 | Grafana `quartz.fire.rate.qps` / `quartz.lock.wait.ms.p99` | 符合 `quartz-capacity-baseline.md` §4 黄/红色 |
| 2 | trigger 模块已加 ShedLockConfiguration | `find batch-trigger -name "*ShedLock*"` 非空 | follow-up 任务必须先完成 |
| 3 | staging 环境 wheel 模式跑过 ≥ 1 周无回归 | 见 §1 staging 验证 | 全部 metric 在阈值内 |
| 4 | 业务方明确接受 SLA(±2s 抖动,详见 design.md §5) | 沟通邮件存档 | 业务方书面确认 |
| 5 | Cron 兼容性扫描 0 阻塞 | `psql -d batch_platform -f scripts/db/quartz-replacement-preflight-scan.sql` | §3 0 行;§2 (L/W/#) 仅用 Quartz CronExpression(本方案沿用,不阻塞) |
| 6 | trigger_runtime_state / trigger_misfire_pending 表已创建 | `\d batch.trigger_runtime_state` | V67 + V69 已 migrate |

---

## 1. Staging 验证(切换前 1 周)

### 1.1 Staging 起 wheel 模式

```bash
# .env.staging 设置
BATCH_TRIGGER_SCHEDULER_IMPL=wheel

# 部署 trigger 镜像(其他模块不动)
docker compose --profile apps up -d --force-recreate trigger
```

启动日志应有:

```
HashedWheelTriggerScheduler started: leaderInstanceId=..., tick=100ms, buckets=512,
    window=300s, scanIntervalMillis=60000
wheel trigger reconciler initial run on application ready
```

quartz scheduler 应该 standby 不 fire:

```
Quartz Scheduler ... NOT STARTED
Currently in standby mode
```

### 1.2 数据迁移:Quartz → trigger_runtime_state

WheelTriggerReconciler 启动时会自动 INSERT runtime_state(用 cron.next(now) 算 next_fire_time)。
**但**这跟 Quartz 当前 NEXT_FIRE_TIME 可能差几秒~几分钟,造成首次 fire 时机偏移。

**建议**:用脚本把 Quartz 状态先 dump 到 trigger_runtime_state:

```sql
-- staging 切 wheel 之前跑(quartz 模式下)
INSERT INTO batch.trigger_runtime_state (
  job_definition_id, tenant_id, job_code, next_fire_time,
  misfire_count, version, created_at, updated_at
)
SELECT
  jd.id,
  jd.tenant_id,
  jd.job_code,
  to_timestamp(qt.NEXT_FIRE_TIME / 1000.0),
  0, 1, now(), now()
FROM batch.job_definition jd
JOIN quartz.QRTZ_TRIGGERS qt ON qt.JOB_NAME = jd.tenant_id || ':' || jd.job_code
                            AND qt.TRIGGER_GROUP = 'batch-trigger'
WHERE jd.enabled = true
  AND jd.schedule_type IN ('CRON', 'FIXED_RATE')
  AND qt.NEXT_FIRE_TIME > 0
ON CONFLICT (job_definition_id) DO UPDATE
SET next_fire_time = EXCLUDED.next_fire_time;
```

### 1.3 验证 staging 1 周

每天检查 4 类指标:

| 指标 | 期望 | 异常处理 |
|---|---|---|
| `batch.trigger.wheel.fire.success` rate | 跟 Quartz 时代相近(误差 < 5%) | 看 fire.failed metric 找原因 |
| `batch.trigger.wheel.fire.lag` P99 | < 500ms | 调大 `bucket-count` 或缩小 `sliding-window-seconds` |
| `batch.trigger.wheel.fire.duplicate.skipped` | 应该全程 0(R-1 三层防御都没漏) | 非 0 即为告警,看 leader 漂移日志 |
| `batch.trigger.wheel.misfire.handled` | 接近 0(staging 应该没 misfire) | 非 0 时按 catchUpPolicy 看处理是否对 |

每天人工对账:

```sql
-- staging fire 次数应跟业务预期一致
SELECT job_code, count(*) FROM batch.trigger_request
 WHERE created_at > now() - interval '1 day'
   AND trigger_type = 'SCHEDULED'
 GROUP BY job_code ORDER BY 2 DESC;
```

---

## 2. 生产灰度切换

### 2.1 灰度方案:从 1 个低优先级 trigger 实例开始

trigger 通常部署多副本(N >= 2)。灰度策略:

| 阶段 | 持续 | 范围 | 监控重点 |
|---|---|---|---|
| **W1** | 1 周 | 1 个 trigger 实例切 wheel | 与 quartz 实例并行;wheel 实例 fire.success 跟 quartz 实例相近 |
| **W2** | 1 周 | 50% 实例切 wheel | 互不干扰(ShedLock 选 leader) |
| **W3** | 持续 | 全量 wheel | quartz 实例下线,QRTZ_* schema 数据保留 30 天再清 |

### 2.2 单实例切换步骤

```bash
# 1) 选定一个 trigger 实例(如 trigger-2),重启它走 wheel
kubectl set env deployment/batch-trigger-2 BATCH_TRIGGER_SCHEDULER_IMPL=wheel
kubectl rollout restart deployment/batch-trigger-2

# 2) 观察 wheel 实例日志 + metric:
#    - 启动期 onLeaderAcquire 触发 fast-path
#    - 不应有 fire.failed 异常
#    - leader.acquire 计数 = 1(单 leader)
kubectl logs -f deployment/batch-trigger-2 | grep -i "wheel\|leader"

# 3) 看 trigger_request 有没有 wheel 写入的行
psql -c "SELECT count(*) FROM batch.trigger_request WHERE created_at > now() - interval '5 minutes';"
```

### 2.3 wheel ↔ quartz 多实例并存的安全性

ShedLock 保证 wheel 实例和 quartz 实例**不会同时 fire 同一 trigger**:

- wheel 实例:`@SchedulerLock("trigger_wheel_leader")` 抢锁
- quartz 实例:Quartz cluster mode 内部 QRTZ_LOCKS 抢锁

但这两个锁是**独立的**!所以**最坏情况**:wheel 抢到 trigger_wheel_leader → 跑 slidingWindow → claim trigger A → 准备 fire;同时 quartz 实例也 fire trigger A。

**R-1 三层防御兜住**:
- LaunchService 侧 dedupKey 一致(`tenantId:jobCode:fireTime.toString()`),后到的会看到 existing → 不再 forward
- job_instance.uk_job_instance_tenant_dedup 最终兜底

实际不会有业务问题,**只会在 trigger_request 表有 1 行重复(浪费空间但语义正确)**。

监控:

```promql
# 期望 ≈ 0;非 0 时说明 wheel/quartz 并行触发了同一 trigger
rate(batch_trigger_wheel_fire_duplicate_skipped[5m])
```

---

## 3. 回滚预案(wheel → quartz)

回滚比切换简单 — 因为 quartz schema 数据完整保留:

```bash
# 1) 把 wheel 实例切回 quartz
kubectl set env deployment/batch-trigger-2 BATCH_TRIGGER_SCHEDULER_IMPL=quartz
kubectl rollout restart deployment/batch-trigger-2
```

启动时 `TriggerReconciler`(quartz 模式启用)会自动跟 QRTZ_* 对账,把 30s 内 missing 的 trigger 重新注册。**不需要数据回迁**。

`trigger_runtime_state` 表保留(下次切回 wheel 时复用,免重新算 next_fire_time)。

---

## 4. 监控告警阈值

加 4 条 alertmanager rule:

```yaml
groups:
- name: wheel-scheduler
  rules:
  - alert: WheelFireDuplicateNonZero
    expr: rate(batch_trigger_wheel_fire_duplicate_skipped[5m]) > 0.001
    for: 10m
    annotations:
      summary: "wheel scheduler 出现重复 fire(R-1 防御被触发)"
      description: "触发条件:R-1 第二层 LaunchService 软幂等触发了至少一次。预期为 0,非 0 = wheel/quartz 并行运行 期间发生过 race。检查灰度阶段是否未结束,或确认是否有第三方 fire 入口。"

  - alert: WheelFireLagHigh
    expr: histogram_quantile(0.99, sum by (le)(rate(batch_trigger_wheel_fire_lag_seconds_bucket[5m]))) > 2.0
    for: 5m
    annotations:
      summary: "wheel fire lag P99 > 2s"
      description: "时间轮 fire 延迟 P99 > 2s,可能 wheel tick 跟不上 / leader GC pause 长 / DB 慢"

  - alert: WheelLeaderAcquireSpike
    expr: rate(batch_trigger_wheel_leader_acquire[5m]) > 0.01
    for: 5m
    annotations:
      summary: "wheel leader 切换频繁"
      description: "5 分钟内 leader 切换 > 3 次,可能 ShedLock TTL 异常 / 实例不稳定 / GC pause 严重"

  - alert: WheelMisfireSpike
    expr: rate(batch_trigger_wheel_misfire_handled[5m]) > 0.1
    for: 10m
    annotations:
      summary: "wheel misfire 数量异常"
      description: "10 分钟内每分钟 6+ 次 misfire,可能业务时钟漂移 / wheel 启动期 catch-up 没完成"
```

---

## 5. 已知限制(灰度期间不可避免)

### 5.1 wheel ↔ quartz 并存期 trigger_request 可能有重复行

详见 §2.3。**不影响业务正确性**,只占用 trigger_request 表少量空间。

### 5.2 切换 wheel 后首次 fire 时机可能偏移

如果跳过了 §1.2 数据迁移,WheelTriggerReconciler 用 `cron.next(now)` 算 next_fire_time,跟
Quartz NEXT_FIRE_TIME 可能差几秒~几分钟。**业务 SLA ±2s 之内可接受**;超出走 misfire AUTO 补一次。

### 5.3 catch-up throttle 在切换瞬间可能限速

切换瞬间如果有 N 个历史 misfire 同时被 wheel 接管,catch-up throttle(默认 10/s)会让 catch-up
按 0.1s/次 排队补完。N=100 → 10s 内补完。可调 `batch.trigger.wheel.catch-up-rate-per-second`。

---

## 6. 完成判定

灰度全量(W3)且持续运行 30 天后无回归,可以:

1. 删 Quartz 依赖 `spring-boot-starter-quartz`(下一个 release)
2. drop `batch_platform.quartz` schema 11 张 QRTZ_* 表
3. 删 `TriggerReconciler` 里 Quartz 适配代码
4. 删 `batch.trigger.scheduler-impl` 二选一开关(只剩 wheel)

按 `docs/architecture/quartz-replacement-design.md` §6 阶段 2 清理。

---

## 7. 修订历史

| 日期 | 改动 |
|---|---|
| 2026-04-26 | 创建,基于阶段 1 实施完成的代码现状 |
| 2026-04-26 | **切默认值**：`application.yml` 的 `BATCH_TRIGGER_SCHEDULER_IMPL` fallback 从 `quartz` 改为 `wheel`；`QuartzPauseWhenWheelEnabledCustomizer` 的 `@Value` fallback 同步；`TriggerReconciler` 去掉 `matchIfMissing=true`（原本是 quartz 默认时的兜底语义，现已不需要）。Quartz 仍保留作 opt-in 回退（`BATCH_TRIGGER_SCHEDULER_IMPL=quartz`）。完成判定 §6 的清理仍按"灰度全量 30 天无回归"的节奏走。 |
