# Trigger 运维操作手册

## 适用范围

本文覆盖 Trigger 的状态查询、注册、暂停、恢复、排空和 misfire 验证。生产操作统一经
Trigger 管理 API 完成，禁止直接改 Quartz 表或 `batch.job_definition`。

## 前置条件

调度定义的唯一权威字段属于 Workflow：单作业 Workflow 通过 `single_job_definition_id` 找到执行 Job，组合 Workflow
通过 JOB 节点编排执行。Trigger 根请求和管理 API 都只接受 Workflow 身份；管理操作位于
`/api/triggers/management/workflow/{register|unregister|pause|resume}`，使用 `tenantId` 与 `workflowCode` 定位。
Quartz identity 为 `tenantId:WF:workflowCode`。暂停/恢复只更新 `workflow_definition.schedule_enabled`，不会改写
`job_definition.enabled`；Job 停用仍独立阻止新的执行准入。已有 `MISFIRE_PENDING` 在调度暂停或 Workflow 完整停用后继续按原 fire 恢复（人工审批策略仍需审批），恢复任务不会重新启用常规日程。完整停用的例外必须由持久来源 `misfire_detected_at` 与 tenant/Workflow/fire/bizDate/request/version 关联证明，普通新请求及自报 `CATCH_UP` 不放行；租户暂停仍阻止恢复。
普通 Quartz 请求和首次 readiness 观察在事务内检查并共享锁定 Workflow 行；暂停更新须等已获准的请求/Outbox 事务提交。暂停确认后，不接纳新的普通 fire，也不新建其 readiness 等待；此前已观察的等待按原 fire 继续。该边界针对新接纳，已持久的队列消息和实例可以继续推进，不能用实例数不变代替暂停验证。
待审批请求使用首次保存的 `trigger_request.launch_payload`。审批不重算日期、区间及版本；快照缺失或不匹配时返回冲突，需排查原请求，禁止按当前日程补造旧载荷。全新数据库不创建或回填旧 `WORKFLOW` 包装 Job，也不提供
JobCode 管理别名。

Quartz 恢复信封的 `sourceFireTime` 保留原计划 fire；非 Quartz 补跑使用接纳事件时刻，不能拿业务数据区间起点代替。审批后的 Root 及其 JOB 子请求继承同一冻结区间；核验时必须同时检查 Root 和实际 JobInstance，不能只检查 Root 快照或成功状态。

已处于 SETTLED、FAILED、SKIPPED 或 MANUAL_RELEASED 的业务日拒绝普通 API 新请求，返回 HTTP 409 / `STATE_CONFLICT`，请求与 Outbox 事务回滚；调用方应先核对原请求，再经显式 CATCH_UP/RERUN 及其原有权限/审批流程处置，不可重开日或改写状态表来绕过。普通定时 fire 仍由 occurrence 记录 `CLOSED_DAY_REJECTED` 及原拒绝原因，不能将该路径和 API 冲突响应混为一种投递结果。

本地 Stage 22/24 的 misfire 必须由管理 API 暂停/恢复真实 Quartz，在原 fire 超过阈值后由 callback 产生待办和原启动快照，再审批该原请求并核对 Root/Job SUCCESS、原日期/区间/版本。手工插入无快照的 ACCEPTED 请求或改写 NEXT_FIRE_TIME 都不能替代这一证据。压力 API 使用与历史结算隔离的独立业务日；所有正常 API/Outbox 请求逐项核对同租户的 request→Root→JOB 节点→JobInstance 成功，失败终态和其它租户的同名 request 不计成功。

Stage 24 还会在真实 misfire 已持久化且 Workflow 完整停用后，通过所选运行环境的 `restart.sh` 重建 Trigger，再审批原请求。重启前后逐字段比较 pending/request/fire/bizDate、审批到期时间、首次载荷指纹、occurrence 状态及零 Outbox；只有状态和快照均保持不变、审批后 Root/Job 成功且 Workflow 仍停用，才算通过这个恢复窗口。容器运行时会实际重建 Trigger 容器，宿主机运行时则重启其 JVM。

```bash
export BATCH_TRIGGER_URL="https://trigger.example.internal"
export BATCH_INTERNAL_SECRET="$SECRET_FROM_SECRET_MANAGER"
```

确认密钥不是默认占位符后，先执行 dry-run：

```bash
bash scripts/ops/manage-trigger.sh status
bash scripts/ops/manage-trigger.sh drain-status
```

## 状态变更

真实执行必须显式关闭 dry-run，并保留命令输出和操作审计：

```bash
BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false \
  bash scripts/ops/manage-trigger.sh pause-workflow TENANT_ID WORKFLOW_CODE

BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false \
  bash scripts/ops/manage-trigger.sh resume-workflow TENANT_ID WORKFLOW_CODE
```

优先使用单 Workflow 或单租户操作。`pause-all`、`resume-all` 和 drain 属于平台级操作，执行前要
确认维护窗口、当前运行实例和回滚负责人。

## 跨日历依赖等待

在 Console「批量日」页面先选择租户和日历，检查待处理等待的业务日、上游阻塞原因和首次等待时间。修复上游或依赖配置后，优先让周期 reconciler 自动恢复；确需人工决定时，管理员选择 `RELEASE` 或 `SKIP` 并填写原因。请求进入持久化队列，由 Orchestrator 下一轮调度扫描执行，页面显示的是请求状态，需刷新确认最终等待/批量日状态。`RELEASE` 会按原等待快照打开原业务日；`SKIP` 会留下 `SKIPPED` 终态。所有操作记录操作者、原因和批量日审计。

不得直接更新 `calendar_dependency_wait` 或 `batch_day_instance`。若请求后等待已被自动恢复，则以最终批量日状态和操作审计为准；记录请求时间、tenant、calendar、bizDate、reason 和 traceId 供排障。

## 请求到 Workflow 根运行的连续性告警

`TriggerStaleAcceptedWithoutInstance` 表示顶层 ACCEPTED/PROCESSING 请求超过 `batch.trigger.launch.metrics.stale-after-seconds`（默认 300 秒）仍没有同租户的 Workflow 根运行；仍处于 `PENDING` 的人工 misfire/catch-up 审批会排除在外，审批超时由待审批过期和恢复链处理。`TriggerRequestToInstanceLatencyHigh` 表示近 15 分钟至少 20 个已创建根运行样本的请求到根创建 p95 超过 30 秒并持续 10 分钟。根运行创建后即退出积压；纯控制节点的 Workflow 无需等待子 Job，顶层请求状态写回延迟也不影响样本。为保持观测接入稳定，既有指标名和告警名保留；两类指标为全局聚合，不含租户或请求 ID。

排查顺序：

1. 检查 Trigger outbox relay 的 backlog、最老未发布年龄和 GIVE_UP 行，确认事件已发布到 `batch.trigger.launch.v1`。
2. 检查 Kafka consumer group lag、consumer 健康和分区数，确认 Orchestrator 正在消费。
3. 按请求时间窗口核对顶层 `trigger_request`，以同租户 `workflow_run.trigger_request_id` 检查实际根运行；再检查 Orchestrator launch 错误。
4. 若根运行已存在但子 Job 未推进，转查 Workflow 节点和 Worker 链路；不要通过重复创建根运行修复子节点问题。
5. 修复根因后确认根运行已持久化，或请求进入可解释的等待/终态，并观察聚合指标在下一轮采样后归零/回落。

不要通过直接修改 `trigger_request` 或删除 outbox 行来消除告警。记录时间范围、requestId（仅放日志/审计，不作为指标标签）、traceId、outbox 状态和实例状态。

`batch.trigger.outbox.publish.latency.negative_duration` 记录因单调时长差为负而跳过的无效计时样本；此样本不进入 ok/fail 延迟分布。单独出现该计数不等于 outbox 投递失败，仍须按请求状态、outbox 终态和 Kafka 消费结果追查；若持续增长，应检查 JVM 运行时与计时采样路径。

## Workflow 执行槽位等待

同一 Workflow 定义、业务日和 dry-run 模式已有 `CREATED/RUNNING` 根运行时，独立请求进入 Orchestrator 的
`workflow_launch_wait` 持久等待。Kafka 消息只有在等待记录和请求 `PROCESSING` 同事务提交后才确认；
不会因某个 Workflow 占位而暂停整个 Kafka 分区。恢复器按租户与可用槽位扫描，每个槽位优先处理最早请求，
使用原 LaunchRequest 载荷、业务日和幂等身份恢复；重启后无需重新调用原触发 API。

人工 misfire 审批写入 Outbox 后保持 `PROCESSING`；只有实际存在根运行后才显示 `LAUNCHED`。
被拒绝的请求不能通过等待恢复复活；载荷身份或日期与原请求不符时拒绝恢复。租户挂起期间不扫描该租户。

等待写入必须与原请求身份、可受理状态及对应状态转换在同一事务提交；原请求终结、目标/业务日/dry-run不一致或必需写入0行时拒绝并回滚，不能只看等待INSERT成功。容量恢复先核对持久槽位与载荷，再由启动事务核对原请求；LAUNCHED不被等待拒绝路径改回拒绝。普通启动从未等待或重复收敛时的可选CAS0行属于幂等无操作，与准入必需写入0行区分。

排障时通过受支持的查询入口对照tenant、requestId、Workflow/Job、bizDate、dry-run、原请求状态和等待状态，保留首次载荷及错误记录。Job依赖等待恢复先核对原请求与持久载荷，即使上游未就绪也拒绝坏身份；未创建实例的坏等待与原请求同事务收敛为CANCELLED/REJECTED。若已有该原请求亲自创建的实例，则保留等待并显示`LAUNCH_ALREADY_PREPARED`，应追踪既有CREATED恢复与派发结果，不能人工删除或重新触发。合法等待的损坏依赖快照仍保持WAITING并报告`DEPENDENCY_SNAPSHOT_INVALID`。V294已为两类等待绑定同租户原请求NO ACTION外键；父请求/终态等待按[引用整改专题§2.11](../plans/database-reference-integrity-remediation-2026-10-18.md)先子后父清理，活跃、近期或仍有关联运行/审计的记录保留。FK不校验载荷业务日或状态，应用身份守护仍必须执行；不要用删除原请求或手改载荷解决不一致。

以下均为 `STATIC / RESTART_REQUIRED` 配置，默认适用于本地、Compose 与 Helm 同版本应用；
可通过 Spring 配置或部署的额外环境变量覆盖，修改后滚动重启 Orchestrator，不在 Console 动态修改：

| 配置 | 默认 | 作用 |
|---|---|---|
| `batch.workflow.launch-wait.poll-interval-millis` | 5000 | 扫描周期 |
| `batch.workflow.launch-wait.batch-size` | 100 | 每租户每轮最多领取数 |
| `batch.workflow.launch-wait.retry-delay-seconds` | 5 | 领取后失败或中断的最早再次尝试间隔 |

持续等待时先查看对应活跃 WorkflowRun、Worker/分区和资源准入原因；不要删除等待行或重新生成 requestId。
该机制不提高一个槽位的业务吞吐上限，长期积压的容量和告警仍须按业务量与追平时限验收。

Job依赖等待若已CANCELLED、原请求已REJECTED而根仍活跃，恢复器会按原请求固定的节点尝试继续传播失败；失败分支、跳过、根终态和终态Outbox共用原有DAG事务。一次节点写入失败会留下可重扫状态，下一轮继续，不需要重建requestId。暂停期间暂缓，恢复后再扫；旧拒绝不会覆盖新尝试或已绑定实例。原请求和等待清理必须保留尚未完成的传播证据，本地保留期入口已按[专题§2.11](../plans/database-reference-integrity-remediation-2026-10-18.md)排除尚保留的冷热Workflow关联，完整弱引用并发、热冷恢复与目标部署仍开放；不要手改节点终态或删账本来消除告警。

## Misfire 验证纪律

验证必须区分真实 Quartz callback 和业务 fallback：

对于 `AUTO` 日历，Quartz `DO_NOTHING` callback 只报告的单个 fire 不是停机期间全部错过的 fire。Reconciler 会按当前
Workflow schedule version 查询已物化、早于 `batch.trigger.runtime.misfire-catch-up-threshold-seconds` 安全窗口且仍为
`PLANNED` 的 occurrence，按原 scheduled fire time 每轮最多恢复 100 条；每条独立进入既有 `MISFIRE_PENDING` 和 request/Outbox
链路。已持久的 `bizDate` 与 fire identity 不重算，CAS 与 request dedup 收敛 callback、reconcile 和重启竞争。后续 reconcile
继续处理剩余页。每轮 100 条是有界恢复保护，不是积压追平 SLA；目标容量和长时追平仍按整合验收 EX-03 单独签收。

1. 在 staging 或隔离环境创建带明确 misfire 策略的临时定时 Workflow（含至少一个 JOB 节点），并记录
   workflowCode、tenant、schedule version 和 fire time。
2. 让 Quartz 在维护窗口内真实发生 misfire，观察 `triggerMisfired` 日志、`batch.trigger_misfire_pending`
   和 `batch.trigger.quartz.misfire.total` 指标。
3. 对 `MANUAL_APPROVAL` 只通过审批/replay API 继续，不直接更新 pending 行。
   统一审批完成后，Trigger 对账器会在 30 秒内（启动就绪时也会立即运行）同步收敛 misfire pending 与 occurrence；拒绝或到期时只将仍为 `ACCEPTED` 的关联请求置为 `REJECTED`。已进入处理或已启动的请求不会被 reaper 关闭。对账连续失败时检查 Trigger 日志中的 `misfire-pending-reconcile-failed`；恢复数据库连接后下一轮会重试，不需要手工改运行态表。
4. 验证 requestId/dedup、outbox、WorkflowRun 与节点关联的 JobInstance 最终状态以及无重复业务执行。
5. `scripts/sim/24-trigger-stage6d.sh` 在隔离 SIM 环境通过 `pause-all` 暂停 Quartz，让已登记的
   真实 fire 超过 Quartz misfire 阈值后通过 `resume-all` 恢复。必须出现真实 callback、匹配的 occurrence 与审批请求；
   未出现则验收失败，不再自动注入 pending 兜底。该场景会短暂停止所有 Quartz 调度，仅用于隔离验证。

## 回滚与故障处理

- 暂停错误：使用同租户 `resume-tenant` 或同 Workflow `resume-workflow`，然后查询 scheduler status。
- 排空超时：先运行 `scripts/ops/inspect-workers.sh`，再按 worker runbook 处理；不要通过 Trigger
  脚本强行清理 Quartz 锁。
- outbox 或 replay 异常：使用对应 `scripts/ops/heal-*.sh`，保留 dry-run 输出和幂等键。
- API 返回失败：保留响应、traceId 和 requestId，先检查 Trigger 日志及数据库状态，再决定重试。

## 验收记录

每次专项至少记录：

| 字段 | 说明 |
|---|---|
| 环境 | staging / 隔离环境标识 |
| misfire_source | `quartz` 或 `seeded-fallback` |
| trigger/job | 临时对象标识 |
| pending / replay | pending 数量、审批和补跑结果 |
| dedup | requestId、幂等键及实例数量 |
| 最终状态 | job instance、task、outbox 是否终态 |
| 证据 | 日志、指标和查询结果路径 |
