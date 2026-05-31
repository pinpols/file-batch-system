# 日切门闩配置 Howto — 让 job 获得"批量行为"

> **目标读者**:业务接入方 / 运维 — 想让某 job 实现"Day-1 没结清 Day-2 等待/拒绝"的批量语义。
>
> **背景**:平台默认行为是"不卡"(`day_rollover_policy=ALLOW_OVERLAP`),需主动配置才会获得批量行为。日切门闩在 orchestrator launch 阶段生效,与 worker 部署模式(平台 / 自托管 SDK)无关。

## 1. 何时需要批量行为

| 场景 | 是否需要日切门闩 | 推荐配置 |
|---|---|---|
| 日终对账(Day-2 输入依赖 Day-1 入库结果) | **需要** | `WAIT_PREVIOUS_DAY` + `SAME_JOB_GROUP` |
| 日切入库(strict 顺序) | **需要** | `REJECT_IF_PREVIOUS_OPEN` + `SAME_JOB` |
| 监控 / 探活 / 实时数据 | 不需要 | 默认 `ALLOW_OVERLAP` 或 `scope=NONE` |
| 文件交付(Pipeline) | 不需要 | 默认 `ALLOW_OVERLAP`(单次任务,无日序依赖) |

## 2. 三件套配置

### 2.1 calendar 级:`business_calendar.day_rollover_policy`

| 值 | 行为 |
|---|---|
| `ALLOW_OVERLAP`(默认) | 前日没结清也放行 — 无批量行为 |
| `WAIT_PREVIOUS_DAY` | 前日没 SETTLED → Day-2 进 `batch_day_waiting_launch` 等待,前日收口后**自动释放**(由 `BatchDayWaitingReleaseScheduler` 定时扫,默认 60s 一次) |
| `REJECT_IF_PREVIOUS_OPEN` | 前日没 SETTLED → Day-2 直接 REJECTED,不排队 |

### 2.2 job 级:`job_definition.previous_day_dependency_scope`

| 值 | 含义 |
|---|---|
| `INHERIT`(默认) | 跟 calendar 的 policy,粒度是"整个 batch_day"(同 calendar 任一 job 没完都卡) |
| `NONE` | 该 job 不参与日切门闩(逃生口,适合监控类) |
| `SAME_JOB` | 只看自己上一日 `job_instance` 是否终态 |
| `SAME_JOB_GROUP` | 看同 `job_group_code` 下所有 job 上一日终态(未配 group 自动降级 `SAME_JOB`) |

### 2.3 启动参数:`LaunchRequest.bizDate` + `job_definition.calendar_code`

两者**任一为空**,门闩跳过(代码 `BatchDayGateService.evaluateAndApply` 入口判定)。新建 job 必须配 calendar,trigger 必须带 bizDate,否则配了 policy 也不生效。

## 3. 配置示意(对账日切场景)

```sql
-- 1. calendar 配等待策略
UPDATE business_calendar
SET day_rollover_policy = 'WAIT_PREVIOUS_DAY'
WHERE tenant_id = 'tenantA' AND code = 'RECON_DAILY';

-- 2. job 绑定 calendar + 同组联动
UPDATE job_definition
SET calendar_code = 'RECON_DAILY',
    previous_day_dependency_scope = 'SAME_JOB_GROUP',
    job_group_code = 'RECON'
WHERE tenant_id = 'tenantA'
  AND job_code IN ('RECON_LOAD', 'RECON_MATCH', 'RECON_REPORT');
```

## 4. 释放时序(自动 vs 手动)

### 4.1 自动释放路径

```
Day-1 跑完最后一个 job
   │
   ▼
BatchDaySettleScheduler(默认 60s 一次)
   │ tx1: IN_FLIGHT → SETTLING
   │ tx2: SETTLING → SETTLED + 写 batch_day_operation_audit
   ▼
BatchDayWaitingReleaseScheduler(默认 60s 一次)
   │ 扫 batch_day_waiting_launch where wait_status='WAITING'
   │ 按 (tenantId, calendarCode, bizDate-1) 去重
   │ 探测前一日 day_status ∈ {SETTLED, SKIPPED, MANUAL_RELEASED}
   │ 命中 → 委托 BatchDayOperationService.releaseWaitingLaunchesForBatchDay
   ▼
重投 LaunchService.launch + markReleased(operator='AUTO_RELEASE')
```

**释放延迟** ≈ settle 间隔 + waiting-release 间隔(默认上限约 2 分钟)。可调:

```yaml
batch:
  batch-day:
    settle-scan-interval-millis: 60000          # BatchDaySettleScheduler
    waiting-release-scan-interval-millis: 60000  # BatchDayWaitingReleaseScheduler
```

### 4.2 手动释放路径(运维补救)

console-api → `BatchDayOperationService.operate(action=RELEASE)`,把 `day_status` 推到 `MANUAL_RELEASED` 并同步释放后一日 waiting。同条 release 路径,但 operator 标记为人工 ID。

### 4.3 释放观测口径

```sql
-- 1. 当前所有 WAITING 触发
SELECT tenant_id, calendar_code, job_code, biz_date, request_id, wait_reason, created_at
  FROM batch.batch_day_waiting_launch
 WHERE wait_status = 'WAITING'
 ORDER BY created_at;

-- 2. 自动释放历史(operator='AUTO_RELEASE'识别)
SELECT tenant_id, request_id, released_at, released_by
  FROM batch.batch_day_waiting_launch
 WHERE wait_status = 'RELEASED'
   AND released_by = 'AUTO_RELEASE'
 ORDER BY released_at DESC LIMIT 50;
```

## 5. SDK 自托管场景

**门闩与部署模式无关**。无论 job 走平台 worker 还是租户自托管 SDK,launch 阶段都走 `BatchDayGateService`。SDK 端**不需要也不应该**自己实现日切判定 — 该 job 配了门闩就在 launch 时卡住,Kafka 派单消息根本不会发出,SDK 端不感知。

详见 [`docs/design/worker-deployment-models.md`](../design/worker-deployment-models.md) §4.1.E。

## 6. 补救路径

| 场景 | 操作 |
|---|---|
| 前日 stuck 在 IN_FLIGHT,要紧急放过 Day-2 | `BatchDayOperation.RELEASE`(把前日推到 `MANUAL_RELEASED`)+ auto-release scheduler 接力 |
| 节假日要跳过 batch | `BatchDayOperation.SKIP`(`day_status=SKIPPED`)— gate 视为可放行 |
| 整日冻结(运维窗口期) | `BatchDayOperation.FREEZE`(`frozen=true`)— 默认拒绝所有触发,`triggerType` ∈ {`CATCH_UP`, `RERUN`} 可绕过 |
| 故障期间错过的批量 | `triggerType=CATCH_UP` 补跑,绕过 frozen + 前日门闩 |
| 同一日重跑 | `triggerType=RERUN` 绕过 frozen,但仍受前日门闩约束 |

## 7. 常见问题排查

### Q1. 配了 `WAIT_PREVIOUS_DAY`,Day-2 还是直接跑了

依次检查:
1. `job_definition.calendar_code` 是否为空(空 → gate 跳过)
2. `LaunchRequest.bizDate` 是否为空(空 → gate 跳过)
3. `job_definition.previous_day_dependency_scope` 是否 `NONE`
4. `triggerType` 是否 `CATCH_UP`/`RERUN`(显式 bypass)
5. 前一日的 `batch_day_instance` 是否已是 `{SETTLED, SKIPPED, MANUAL_RELEASED}`(则不算"未结清")

### Q2. Day-1 已 SETTLED,Day-2 waiting 还没释放

依次检查:
1. `BatchDayWaitingReleaseScheduler` 是否在运行(看应用日志,默认 60s 一次)
2. ShedLock `batch_day_waiting_release` 是否被其他实例占着(看 `shedlock` 表)
3. `OrchestratorGracefulShutdown.isDraining()` 是否为 true(停机期跳过)
4. `batch_day_waiting_launch.biz_date` 是否真的是 Day-1 的下一日(date 计算 off-by-one)
5. `SAME_JOB`/`SAME_JOB_GROUP` 是否还有同 job/组的非终态实例(release 触发 launch,gate 二次评估仍 WAIT 会重新写 waiting)

### Q3. 等待行 `wait_status` 永远在 WAITING

查 `BatchDayOperationServiceTest#shouldReleaseWaitingLaunchesOnRelease` 验证逻辑;若线上确实卡住,可能命中已知边界:同 requestId 二次 launch 被 gate 再次卡为 WAIT 时,`on conflict do nothing` 会让原行保留 — 此时 `wait_status` 仍是 WAITING,等下一轮 settle 完成会被 auto-release 重试。若反复多次仍未释放,人工 `RELEASE` 操作兜底。

## 8. 相关代码 / 文档

- `batch-orchestrator/.../BatchDayGateService.java` — 门闩判定
- `batch-orchestrator/.../BatchDayWaitingReleaseScheduler.java` — 自动释放
- `batch-orchestrator/.../BatchDayOperationService.java` — 手动操作 + 共享释放逻辑
- 设计文档:[`docs/design/batch-day-capability-design.md`](../design/batch-day-capability-design.md) — 能力总览
- 时区/DST 配合:[`docs/design/batch-day-timezone-dst-optimized-design.md`](../design/batch-day-timezone-dst-optimized-design.md)
- 部署模式:[`docs/design/worker-deployment-models.md`](../design/worker-deployment-models.md) §4.1.E
