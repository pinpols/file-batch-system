# 依赖感知 fire(Dependency-Aware Fire)

> ADR-043 Phase B 实现。让声明了上游依赖的触发器在 fire 前先确认上游就绪,**不盲 fire 注定无输入 / 半输入的批**。默认对存量触发器零影响。

## 机制

`job_definition.depends_on_job_code`(可空)声明本触发器 fire 前需就绪的上游 job。scheduled fire 路径(`DefaultTriggerService.launchScheduled`)在 bizDate 解析后、persist 前插一道闸:

- `depends_on_job_code` 为空(绝大多数存量触发器)→ 直接放行,**行为完全不变**。
- 非空 → 经 orchestrator 只读 API `GET /internal/readiness/job` 查上游同 bizDate **最新一次 attempt** 是否 `SUCCESS`(`UpstreamReadinessChecker` → `ReadinessService`):
  - 就绪 → 正常 fire。
  - 未就绪 → `launchScheduled` 抛 `UpstreamNotReadyException`,wheel 调度器走 **readiness defer**(见下),**不丢批**。

> **就绪口径=最新 attempt**:用"该 bizDate 最新 run_attempt 是否 SUCCESS"而非"存在任意 SUCCESS"。先成功后 rerun 失败 / rerun 正在跑时,最新 attempt 非 SUCCESS → not ready,避免下游按已被推翻的过期结果启动(结算级)。

> trigger **不直连** orchestrator 状态表(读写分离 + 模块边界),就绪判定一律经 orchestrator 暴露的只读 internal API(携 `X-Internal-Secret`)。orchestrator 仍是唯一状态主机。

## readiness defer(未就绪不丢批,ADR-043 §6.4)

旧实现未就绪直接 skip + 推进 `next_fire_time` 到下一 cron 点——**日批场景**上游晚几分钟完成,下游当天就再也不会自动 fire(下一 cron 点已是次日)。现改为 **defer**(`HashedWheelTriggerScheduler`):

- **窗口内重检(同 bizDate 不丢批)**:未就绪且已等待 ≤ `readinessWindow` → 不前移真 cron,把 `next_fire_time` 设为"重检时钟"(`now + recheckInterval`),在 `trigger_runtime_state.readiness_deferred_since` 记**首次 defer 的原始触发时刻**,`last_fire_status='WAITING_READINESS'`。下个扫描窗重检;一旦上游就绪即 fire。bizDate **pin** 到 `readiness_deferred_since`,防重检跨午夜漂移到次日。
- **超窗 give-up**:等待 > `readinessWindow` 仍未就绪 → 推进到下一真 cron(基准=原始触发时刻),`last_fire_status='WAITING_READINESS_TIMEOUT'`,记 `ERROR` + metric。运维据此判断"上游严重延迟,本 bizDate 已放弃",可走 batch-day replay 手动补。
- defer 重检行的 `next_fire_time` 是重检时钟而非真 cron,**不参与 misfire 分流**(wheel 检测到 `readiness_deferred_since` 非空即跳过 misfire 判定)。
- metric:`batch.trigger.wheel.readiness.deferred`(按 group)、`batch.trigger.wheel.readiness.timeout`(按 group,**应配告警**)。

## fail-closed(结算优先)

就绪查询失败(orchestrator 不可达 / 超时 / 5xx)时,`UpstreamReadinessChecker` **不放行 fire**(返回未就绪)并记 `ERROR`。宁可不 fire 也不基于不确定状态盲 fire——结算链路要求。运维应对 ERROR 告警快速介入(多为 orchestrator 连通性 / secret 漂移)。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `job_definition.depends_on_job_code` | NULL | 上游 job code;非空才启用本触发器的依赖闸 |
| `batch.trigger.readiness-gate.enabled` | `true` | 全局 kill-switch;设 `false` 时一律放行(等价关闭依赖感知,应急用) |
| `batch.trigger.wheel.readiness-window-seconds` | `7200`(2h) | 等上游就绪的最长容忍窗口;超窗放弃本 bizDate(`WAITING_READINESS_TIMEOUT` + ERROR/metric) |
| `batch.trigger.wheel.readiness-recheck-interval-seconds` | `30` | 未就绪时的重检间隔;须 `<` `misfire-threshold-seconds`(默认 60)防重检被误判 misfire |

## 边界 / 后续(v1 范围)

- **v1 只支持单个上游 JOB + SAME_DAY 对齐**。多依赖、`PREV_DAY` 对齐、FILE_GROUP(文件组 TRIGGERED)就绪——均为 ADR-043 描述的后续增强项,本期未做。
- 超窗 give-up 目前是"推进到下一 cron + ERROR/metric 告警 + 人工 replay",未自动落 `trigger_misfire_pending` 走 misfire 策略(ADR §6.4 列的 misfire 路由)——留作后续增强;当前语义更简单且不会无限 catch-up 循环。
- 只 gate `launchScheduled`(正常调度 fire);catch-up / 手动 fire 不走此闸。
- 不裁定上游业务对错(ADR-021 红线),只问"上游这个 job 这个批次日跑成功了没"。
