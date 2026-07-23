# 依赖感知 fire(Dependency-Aware Fire)

> ADR-043 Phase B 实现。让声明了上游依赖的触发器在 fire 前先确认上游就绪,**不盲 fire 注定无输入 / 半输入的批**。默认对存量触发器零影响。

## 机制

`job_definition.depends_on_job_code`(可空)声明本触发器 fire 前需就绪的上游 job。scheduled fire 路径(`DefaultTriggerService.launchScheduled`)在 bizDate 解析后、persist 前插一道闸:

- `depends_on_job_code` 为空(绝大多数存量触发器)→ 直接放行,**行为完全不变**。
- 非空 → 经 orchestrator 只读 API `GET /internal/readiness/job` 查上游同 bizDate 是否已有 **EFFECTIVE asset partition**(`UpstreamReadinessChecker` → `ReadinessService`):
  - 就绪 → 正常 fire。
  - 未就绪 → `launchScheduled` 抛 `UpstreamNotReadyException`，Quartz 创建一次性 retry trigger 执行 **readiness defer**，**不丢批**。

> **就绪口径=当前 EFFECTIVE 结果版本**:只认 `result_version.status=EFFECTIVE` 物化出的 asset partition。PENDING、DRY_RUN、失败产物、旧 SUPERSEDED 版本都不放行,避免下游按未生效或已被推翻的过期结果启动(结算级)。ready 响应会带 `businessKey/versionNo/jobInstanceId` 供日志和运维定位。
>
> 2026-07-01 起,orchestrator 读取物化 `asset_partition` 时还会校验它指向的是同 `businessKey` 的最新 EFFECTIVE 版本,写入物化行时也按 `version_no` 单调更新。这个守卫用于防止重跑产生更高版本后,旧 EFFECTIVE 物化行继续让下游误判 ready。

> trigger **不直连** orchestrator 状态表(读写分离 + 模块边界),就绪判定一律经 orchestrator 暴露的只读 internal API(携 `X-Internal-Secret`)。orchestrator 仍是唯一状态主机。

## readiness defer(未就绪不丢批,ADR-043 §6.4)

旧实现未就绪直接 skip——**日批场景**上游晚几分钟完成，下游当天就再也不会自动 fire。现由 `QuartzLaunchJob` 执行 defer：

- **窗口内重检(同 bizDate 不丢批)**：Quartz 为同一 JobDetail 创建 one-shot trigger，在 `now + recheckInterval` 再次执行。retry JobDataMap 固定原始 fire 时间、首次 defer 时间和原始 TriggerType，因此跨午夜不会漂移 bizDate，也不会因等待超过 misfire 阈值改变触发类型。
- **超窗 give-up**：等待达到 `readinessWindow` 后停止创建 retry trigger，记录 ERROR，并增加 `batch.trigger.quartz.readiness.timeout`。运维据此判断“上游严重延迟，本 bizDate 已放弃”，可走 batch-day replay 手动补。
- 原 Cron/FIXED_RATE trigger 保持不变；retry trigger 是独立的一次性触发，不修改正常调度表达式。
- 每次创建 retry trigger 增加 `batch.trigger.quartz.readiness.deferred`，对应 Prometheus 提前预警。

## fail-closed(结算优先)

就绪查询失败(orchestrator 不可达 / 超时 / 5xx)时,`UpstreamReadinessChecker` **不放行 fire**(返回未就绪)并记 `ERROR`。宁可不 fire 也不基于不确定状态盲 fire——结算链路要求。运维应对 ERROR 告警快速介入(多为 orchestrator 连通性 / secret 漂移)。

## freshness policy 告警

readiness defer 解决的是"依赖上游未就绪时不要盲 fire"。资产新鲜度策略解决的是"上游到了业务 SLA 还没产出时要显性告警"。

orchestrator 可通过 `batch.asset_freshness_policy` 配置 JOB asset 的 `expected_by_local_time / timezone / stale_after_seconds / lookback_days / severity`。定时扫描器只读当前 `EFFECTIVE asset_partition`:

- expectedBy 已过但宽限期未过,发 `ASSET_FRESHNESS_MISSING`。
- expectedBy + staleAfter 已过仍无 EFFECTIVE,发 `ASSET_FRESHNESS_STALE`。
- 已有 EFFECTIVE,不发告警。

这个告警不改变 trigger readiness 语义,也不把旧结果标为可消费。下游能否 fire 仍只由 `EFFECTIVE asset_partition` 决定。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `job_definition.depends_on_job_code` | NULL | 上游 job code;非空才启用本触发器的依赖闸 |
| `batch.trigger.readiness-gate.enabled` | `true` | 全局 emergency switch;设 `false` 时一律放行(等价关闭依赖感知,应急用) |
| `batch.trigger.runtime.readiness-window-seconds` | `7200`(2h) | 等上游就绪的最长容忍窗口；超窗后停止 retry 并记录 ERROR |
| `batch.trigger.runtime.readiness-recheck-interval-seconds` | `30` | Quartz one-shot retry 的重检间隔 |
| `batch.asset-freshness.enabled` | `true` | asset freshness SLA 扫描开关 |
| `batch.asset-freshness.scan-interval-millis` | `60000` | freshness policy 扫描间隔 |
| `batch.asset-freshness.batch-limit` | `500` | 单轮最多扫描的策略数 |

## 边界 / 后续(v1 范围)

- **v1 只支持单个上游 JOB + SAME_DAY 对齐**。多依赖、`PREV_DAY` 对齐、FILE_GROUP(文件组 TRIGGERED)就绪——均为 ADR-043 描述的后续增强项,本期未做。
- 超窗 give-up 目前是“停止 retry + ERROR 日志 + 人工 replay”，未自动落 `trigger_misfire_pending` 走 misfire 策略。
- 只 gate `launchScheduled`(正常调度 fire);catch-up / 手动 fire 不走此闸。
- 不裁定上游业务对错(ADR-021 红线),只问"上游这个 job 这个批次日跑成功了没"。
- 不做通用 deadline/window-aware 优化调度器。后续最多补 late/at-risk/missed-window 标记和告警,不得把 trigger 扩成 Airflow/Temporal 类通用编排器。
