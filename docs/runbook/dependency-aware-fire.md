# 依赖感知 fire(Dependency-Aware Fire)

> ADR-043 Phase B 实现。让声明了上游依赖的触发器在 fire 前先确认上游就绪,**不盲 fire 注定无输入 / 半输入的批**。默认对存量触发器零影响。

## 机制

`job_definition.depends_on_job_code`(可空)声明本触发器 fire 前需就绪的上游 job。scheduled fire 路径(`DefaultTriggerService.launchScheduled`)在 bizDate 解析后、persist 前插一道闸:

- `depends_on_job_code` 为空(绝大多数存量触发器)→ 直接放行,**行为完全不变**。
- 非空 → 经 orchestrator 只读 API `GET /internal/readiness/job` 查上游同 bizDate 是否已有 **EFFECTIVE asset partition**(`UpstreamReadinessChecker` → `ReadinessService`):
  - 就绪 → 正常 fire。
  - 未就绪 → `launchScheduled` 抛 `UpstreamNotReadyException`,wheel 调度器走 **readiness defer**(见下),**不丢批**。

> **就绪口径=当前 EFFECTIVE 结果版本**:只认 `result_version.status=EFFECTIVE` 物化出的 asset partition。PENDING、DRY_RUN、失败产物、旧 SUPERSEDED 版本都不放行,避免下游按未生效或已被推翻的过期结果启动(结算级)。ready 响应会带 `businessKey/versionNo/jobInstanceId` 供日志和运维定位。

> trigger **不直连** orchestrator 状态表(读写分离 + 模块边界),就绪判定一律经 orchestrator 暴露的只读 internal API(携 `X-Internal-Secret`)。orchestrator 仍是唯一状态主机。

## readiness defer(未就绪不丢批,ADR-043 §6.4)

旧实现未就绪直接 skip + 推进 `next_fire_time` 到下一 cron 点——**日批场景**上游晚几分钟完成,下游当天就再也不会自动 fire(下一 cron 点已是次日)。现改为 **defer**(`HashedWheelTriggerScheduler`):

- **窗口内重检(同 bizDate 不丢批)**:未就绪且已等待 ≤ `readinessWindow` → 不前移真 cron,把 `next_fire_time` 设为"重检时钟"(`now + recheckInterval`),在 `trigger_runtime_state.readiness_deferred_since` 记**首次 defer 的原始触发时刻**,`last_fire_status='WAITING_READINESS'`。下个扫描窗重检;一旦上游就绪即 fire。bizDate **pin** 到 `readiness_deferred_since`,防重检跨午夜漂移到次日。
- **超窗 give-up**:等待 > `readinessWindow` 仍未就绪 → 推进到下一真 cron(基准=原始触发时刻),`last_fire_status='WAITING_READINESS_TIMEOUT'`,记 `ERROR` + metric。运维据此判断"上游严重延迟,本 bizDate 已放弃",可走 batch-day replay 手动补。
- defer 重检行的 `next_fire_time` 是重检时钟而非真 cron,**不参与 misfire 分流**(wheel 检测到 `readiness_deferred_since` 非空即跳过 misfire 判定)。
- metric:`batch.trigger.wheel.readiness.deferred`(按 group)、`batch.trigger.wheel.readiness.timeout`(按 group,**应配告警**)。

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
| `batch.trigger.wheel.readiness-window-seconds` | `7200`(2h) | 等上游就绪的最长容忍窗口;超窗放弃本 bizDate(`WAITING_READINESS_TIMEOUT` + ERROR/metric) |
| `batch.trigger.wheel.readiness-recheck-interval-seconds` | `30` | 未就绪时的重检间隔;须 `<` `misfire-threshold-seconds`(默认 60)防重检被误判 misfire |
| `batch.asset-freshness.enabled` | `true` | asset freshness SLA 扫描开关 |
| `batch.asset-freshness.scan-interval-millis` | `60000` | freshness policy 扫描间隔 |
| `batch.asset-freshness.batch-limit` | `500` | 单轮最多扫描的策略数 |

## 边界 / 后续(v1 范围)

- **v1 只支持单个上游 JOB + SAME_DAY 对齐**。多依赖、`PREV_DAY` 对齐、FILE_GROUP(文件组 TRIGGERED)就绪——均为 ADR-043 描述的后续增强项,本期未做。
- 超窗 give-up 目前是"推进到下一 cron + ERROR/metric 告警 + 人工 replay",未自动落 `trigger_misfire_pending` 走 misfire 策略(ADR §6.4 列的 misfire 路由)——留作后续增强;当前语义更简单且不会无限 catch-up 循环。
- 只 gate `launchScheduled`(正常调度 fire);catch-up / 手动 fire 不走此闸。
- 不裁定上游业务对错(ADR-021 红线),只问"上游这个 job 这个批次日跑成功了没"。
