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
| `WAIT_PREVIOUS_DAY` | 前日没 SETTLED → Day-2 进 `batch_day_waiting_launch` 等待,前日收敛后**自动释放**(由 `BatchDayWaitingReleaseScheduler` 定时扫,默认 60s 一次) |
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

## 7. 日切停机恢复进度

`BatchDayOpenScheduler` 每轮最多扫描 `BATCH_BATCH_DAY_CATCHUP_DAYS_PER_SCAN` 个原始日期，后续周期会从持久化游标继续。通过 Prometheus 查看：

- `batch_batch_day_open_catchup_remaining_days`：最近一次完整成功扫描后，所有启用日历中最大的剩余日期数。
- `batch_batch_day_open_catchup_calendars_behind`：该扫描后仍有原始日期待检查的日历数。
- `batch_batch_day_open_catchup_scan_failures_total`：扫描失败累计次数；失败时积压 gauge 保留上一次完整快照。

## 8. CUTOFF 空日自动结算

`BatchDaySettleScheduler` 只对已到 `CUTOFF` 且没有任何已创建实例的批量日尝试自动结算。它在同一短事务里检查预期 occurrence、定时定义覆盖、尚未派发的 Trigger 请求、跨日历/作业依赖等待；检查通过后才 CAS 到 `SETTLED` 并写 `AUTO_EMPTY_NO_EXPECTED_WORK` 审计原因。

`FILE_GROUP` 作业用 `/internal/file-group-expectations` 配置每日预期；外部事件作业用 `/internal/event-expectations` 配置 `eventCode`、预期本地时间和可选同日截止时间。业务日首次开启时生成逐日 `EXPECTED` 项。事件消费方发起 EVENT 触发时必须在 Trigger 请求中传入同一 `externalEventCode`；系统会将其持久化到对应 `trigger_request`。请求达到 `LAUNCHED` 后，消费方调用 `/internal/event-expectations/satisfied` 并携带原 `triggerRequestId` 和 `eventCode`；服务校验请求与预期事件编码一致，避免同一作业的另一个事件误满足该项，重复调用幂等。未到事件仍阻止自动结算，即使超过 due 时间也不会自动忽略。启用的 EVENT/MIXED 作业若没有任何逐日预期规则，日历继续失败关闭；不要仅凭 `job_instance` 为零推断当天无工作。

当积压持续大于 0 时，先核对 Orchestrator `batch_day_open` 调度器和数据库错误日志；扫描失败计数增加时，修复失败原因后等待下一轮周期扫描。不要直接更新 `batch_day_open_cursor`。这组全局指标不列出具体租户/日历，也不覆盖 cursor 锚点之前的历史缺口；定位单个日历的锚点前缺口时，使用下方 Console 历史缺口候选查询，不据此手工改游标。

Console「批量日」页面的「日切补齐预览」可按当前租户和日历显示下轮将评估的原始日期范围、游标来源及剩余日期数。预览只读，不会创建批量日或推进游标；`LATEST_BATCH_DAY_BASELINE` 表示系统首次初始化游标时以最近一条已落库批量日为锚点，锚点之前的历史缺口不在本预览范围，也不会由后台自动回溯。节假日滚动、依赖阻塞和灾难日规则会在真正扫描时按当前策略判断；预览中的原始日期不代表一定新建同日批量日。

管理员可在同一预览面板暂停/恢复一个日历的自动历史 catch-up，必须填写原因。暂停仅停止该日历游标推进，当前业务日正常开启、日历依赖等待恢复和积压指标仍继续；恢复后从原游标续扫。操作及操作者、原因会追加审计事件。若暂停期间继续积累 backlog，先确认依赖/日历配置和运行容量，再恢复；不要手工改游标或将暂停当作历史补跑审批。

### 部署前历史缺口盘点

上线或切换调度策略前，在 Console「批量日」页面选择租户和日历，使用「历史缺口候选」填写明确的起止日期（最多 90 天），记录缺失日期，并由业务负责人核对当时适用的节假日、滚动规则、灾难日覆盖和依赖结果。该查询仅按“日期范围内没有批量日记录”列候选，不推断历史上该日是否应开批，也不触发作业；结果是人工核对清单，不是自动回填指令。

上线后由持久化游标负责在线 catch-up；游标锚点之前的缺口仍需单独治理。目前没有为缺失 `batch_day_instance` 建立历史业务日的生产回填流程。现有 `BatchDayReplay` 审批链面向已有实例重跑；`SCHEDULE_PLAN` 仅支持 dry-run，不能据当前作业定义发起历史生产作业。确认需要补建时，记录候选日期并按组织既有变更审批流程评估；在专用回填能力交付前，不得宣称已批准/已回填，不得用当前日历规则自动推演历史，也不得直接插入 `batch_day_instance` 或移动游标。

## 8. 常见问题排查

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

查 `BatchDayOperationServiceTest#shouldReleaseWaitingLaunchesForNextBizDate` 验证逻辑;若线上确实卡住,可能命中已知边界:同 requestId 二次 launch 被 gate 再次卡为 WAIT 时,`on conflict do nothing` 会让原行保留 — 此时 `wait_status` 仍是 WAITING,等下一轮 settle 完成会被 auto-release 重试。若反复多次仍未释放,人工 `RELEASE` 操作回退。

## 8. 文件组预期到达登记

先配置周期文件组规则；每个批量日首次打开成功时，系统按该日历工作日自动创建当天 EXPECTED 项，重复开日不会重复建账。规则的 expected/due 使用日历时区的本地时间，due 必须晚于 expected 且落在同一个业务日。

```http
PUT /internal/file-group-expectations
X-Internal-Secret: <service secret>
Content-Type: application/json
```

```json
{
  "tenantId": "tenantA",
  "calendarCode": "IMPORT_DAILY",
  "jobCode": "IMPORT_ORDERS",
  "fileGroupCode": "ORDERS_DAILY",
  "expectedLocalTime": "09:00:00",
  "dueLocalTime": "11:00:00",
  "enabled": true
}
```

规则日历必须匹配作业定义的 `calendar_code`。文件记录里的 `fileGroupCode` 和 `bundleJobCode` 要与规则一致，bundle 到达后才会推进同一预期项。

如上游业务清单要覆盖单个日期或补偿已打开日期，可调用逐日登记接口；否则系统无法从尚不存在的 `file_record` 推断缺失文件。接口幂等身份为 tenant + calendar + job + fileGroupCode + bizDate。

```http
POST /internal/expected-work-occurrences/file-groups
X-Internal-Secret: <service secret>
Content-Type: application/json
```

```json
{
  "tenantId": "tenantA",
  "calendarCode": "IMPORT_DAILY",
  "jobCode": "IMPORT_ORDERS",
  "fileGroupCode": "ORDERS_20261014",
  "bizDate": "2026-10-14",
  "expectedAt": "2026-10-14T01:00:00Z",
  "dueAt": "2026-10-14T03:00:00Z"
}
```

登记成功后，`EXPECTED` 会阻止该业务日空结算；bundle 到达时复用相同身份推进为 `ARRIVED`，Trigger 请求已接受或等待依赖后转为 `SATISFIED`。相同请求重放会返回 `inserted=false`。`dueLocalTime` 目前只记录期限，尚未自动生成超时终态；文件未到时仍保持阻塞，直至运维处置。

## 9. 相关代码 / 文档

- `batch-orchestrator/.../BatchDayGateService.java` — 门闩判定
- `batch-orchestrator/.../BatchDayWaitingReleaseScheduler.java` — 自动释放
- `batch-orchestrator/.../BatchDayOperationService.java` — 手动操作 + 共享释放逻辑
- 设计文档:[`docs/design/batch-day-capability-design.md`](../design/batch-day-capability-design.md) — 能力总览
- 时区/DST 配合:[`docs/design/batch-day-timezone-dst-optimized-design.md`](../design/batch-day-timezone-dst-optimized-design.md)
- 部署模式:[`docs/design/worker-deployment-models.md`](../design/worker-deployment-models.md) §4.1.E
