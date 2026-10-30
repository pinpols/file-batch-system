# Trigger 运维操作手册

## 适用范围

本文覆盖 Trigger 的状态查询、注册、暂停、恢复、排空和 misfire 验证。生产操作统一经
Trigger 管理 API 完成，禁止直接改 Quartz 表或 `batch.job_definition`。

## 实现职责与事务

`TriggerService` 的统一入口保持不变，排障时按下列职责查找实际执行位置：

| 位置 | 职责与事务边界 |
|---|---|
| `DefaultTriggerService` | API手工启动校验及用例委派；`createPendingCatchUp` 的声明式事务仍从此入口开始 |
| `TriggerRequestPersistence` | 普通请求以原 `REQUIRES_NEW` 完成幂等/业务日围栏、request与Outbox；其构造/发布辅助方法不另开事务 |
| `TriggerExternalEventService` | 事件身份、冻结目标和全部目标写入位于同一接纳事务，不能逐目标调用新的独立事务 |
| `TriggerScheduledRecoveryService` | 原fire、readiness、已拒绝occurrence及冻结审批恢复；保留各流程原有显式事务，待审批创建继承门面事务 |
| `TriggerLaunchQueryService` | 启动/事件状态回查，保留原查询事务和租户绑定，不执行接纳或推进 |
| `TriggerAdmissionRules` | 现有租户、Workflow版本和稳定错误身份核验，不写状态、不新建事务 |

职责拆分没有改变锁顺序、幂等身份、首次启动快照或Outbox发布方式；不能绕过统一入口直接调用协作者来模拟恢复。

## 批次日自动补跑的原回执核验

同一补跑幂等键仅能复用相同的冻结业务载荷；重试时请求 ID 和 trace ID 可不同，其余身份和载荷不匹配时返回冲突。内部 `POST /internal/trigger/catch-up/verify` 使用内部密钥、原项目键和预期启动载荷，只读核验租户、Workflow、业务日、触发类型及持久化载荷；匹配后返回原 Trigger 请求标识，不返回原始载荷，也不接纳新请求。未找到或核验失败不能证明请求未提交，不能换键重发。请求标识证明接纳记录，不代表 Workflow 或 Job 已成功。

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

已处于 SETTLED、FAILED、SKIPPED 或 MANUAL_RELEASED 的业务日拒绝普通 API 新请求，返回 HTTP 409 / `STATE_CONFLICT`，请求与 Outbox 事务回滚；调用方应先核对原请求，再经显式 CATCH_UP/RERUN 及其原有权限/审批流程处置，不可重开日或改写状态表来绕过。普通定时 fire 仍由 occurrence 记录 `CLOSED_DAY_REJECTED` 及原拒绝原因，不能将该路径和 API 冲突响应混为一种投递结果。 关账拒绝不会覆盖已绑定补跑请求的 occurrence；如果旧回调与补跑接纳交错，回调回查并返回已接纳的请求。

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

## Readiness 持久等待恢复

Trigger 将首次等待的依赖集合、Workflow 调度版本、原计划 fire、业务日和截止时间移交至
Orchestrator 的 `readiness_wait`。Quartz 回调缺失时，Orchestrator 的等待扫描器仍按原业务日检查全部依赖，
并通过 Trigger 内部恢复端点进入正常启动链路；Trigger 继续校验原 occurrence 的版本、身份、终态及幂等性。
同一 fire 的等待起点和截止时间以 Trigger 首次持久化的窗口为准，后续重试和重复移交沿用该窗口；
调整等待窗口配置只影响尚未登记的新等待，不会延长或缩短已有等待。重新规划为新版本的 fire 使用新窗口。
依赖未就绪且超过截止时间时，Orchestrator 将等待终结为 `TIMED_OUT`；Trigger 的独立扫尾器将
occurrence 终结为 `READINESS_TIMEOUT`，日结不再被该计划 fire 阻塞。两端扫描均不依赖 Quartz 回调。
Trigger 扫尾更新时会重新核对截止时间仍不晚于本轮扫描时刻；扫描后窗口已延长的记录保留等待，由后续扫描处理。

恢复客户端的 `batch.trigger.internal.base-url` 沿用现有 `BATCH_TRIGGER_BASE_URL`；本地未指定完整地址时
使用 `BATCH_TRIGGER_PORT`，默认 18081。Compose 使用 `http://trigger:18081`，Helm 使用既有 Trigger Service
地址。该地址为静态配置，修改后需要重启 Orchestrator。两端还需注入一致的 `BATCH_INTERNAL_SECRET`；
恢复客户端不会在密钥为空时发送请求，即使测试环境开启安全 bypass 也一样。

恢复调用复用 Orchestrator 受控 HTTP 通道。`batch.trigger.internal.connect-timeout` 默认 `2s`,
`batch.trigger.internal.request-timeout` 默认 `5s`, 属于静态启动配置; 不回包在单次请求预算内失败,
原等待保持 PENDING。可通过应用配置覆盖, 不在业务方法内新建无超时客户端。

等待扫描仍保持单轮 100 条上限, 按 `updated_at` 探测水位轮转, 同水位按 deadline/id 排序。
每项探测前仅更新仍为 PENDING 的水位, 不改等待状态, 使长期未就绪项不会占满每轮入口。
V359 增加对应部分索引; 截止时间和原 fire 身份语义保持不变。

排查时同时核对 `readiness_wait` 和同租户、Workflow、原 fire 的 `scheduled_occurrence`。
移交失败会记录 `batch.trigger.readiness.handover.failed.total`；HTTP 恢复失败应保留等待，下一轮重试。
不能把接口收到请求或单端等待终结当作完整恢复或日结解除阻塞的证据。

Console 的“调度快照 → 持久化等待”页签可通过 `GET /api/console/ops/persistent-waits` 按租户、类型、状态和业务日查询 readiness 与容量准入等待。该接口是只读安全投影，不返回 `workflow_launch_wait.launch_payload`；它用于定位记录和关联请求，不替代核对 Trigger occurrence、WorkflowRun 或最终启动结果。

## Workflow 执行槽位等待

Trigger Kafka 启动消息有限重试耗尽后，Orchestrator 将原始信封加密保存到 `trigger_launch_recovery`，待恢复器处理。同租户、同请求 ID 的重复信封必须与已保存信封一致：`PENDING` 保留原等待与退避；`DEAD` 在相同信封再次投递时重置重试次数并回到 `PENDING`；`DONE` 和 `REJECTED` 保持终态。不同信封、无法解密的原记录或无法确认持久状态时，登记不会报告成功，消费错误处理器不得按已恢复跳过消息。原信封对比包括请求、来源时刻及追踪上下文，排查冲突时应检查请求生产方的幂等身份，不要把密文或原始载荷写入日志。

重新入队时 `recovery_generation` 递增，旧一轮迟到的失败或拒绝回执不能消耗新一轮次数或改写状态；同一冻结请求的成功回执仍可完成锚点。恢复失败次数和退避按数据库当前值原子更新，达到上限后停止继续排队；已经并发开始的调用仍可能完成。

如果重复启动在读取旧状态后遇到定义、版本或父实例校验失败，但同一原请求已经建档，启动入口返回可重试的 503；Trigger 消费端交给监听器重投，恢复锚点按退避重试。排查时应沿用原请求身份，不要换新请求键重发；状态和重试结果以持久化记录为准。

Workflow 首次建档时会加密保存 START 载荷；该运行的入口节点派发和重复启动均使用这份冻结载荷，不能通过同一请求键的后续参数改变它。冻结载荷缺失或无法解密时不会继续派发，需检查运行记录及密钥可用性。

同一 Workflow 定义、业务日和 dry-run 模式已有 `CREATED/RUNNING` 根运行时，独立请求进入 Orchestrator 的
`workflow_launch_wait` 持久等待。Kafka 消息只有在等待记录和请求 `PROCESSING` 同事务提交后才确认；
不会因某个 Workflow 占位而暂停整个 Kafka 分区。恢复器按租户与可用槽位扫描，每个槽位优先处理最早请求，
使用原 LaunchRequest 载荷、业务日和幂等身份恢复；重启后无需重新调用原触发 API。

人工 misfire 审批写入 Outbox 后保持 `PROCESSING`；只有实际存在根运行后才显示 `LAUNCHED`。
被拒绝的请求不能通过等待恢复复活；载荷身份或日期与原请求不符时拒绝恢复。租户挂起期间不扫描该租户。

等待写入必须与原请求身份、可受理状态及对应状态转换在同一事务提交；原请求终结、目标/业务日/dry-run不一致或必需写入0行时拒绝并回滚，不能只看等待INSERT成功。容量恢复先核对持久槽位与载荷，再由启动事务核对原请求；LAUNCHED不被等待拒绝路径改回拒绝。普通启动从未等待或重复收敛时的可选CAS0行属于幂等无操作，与准入必需写入0行区分。

排障时通过受支持的查询入口对照tenant、requestId、Workflow/Job、bizDate、dry-run、原请求状态和等待状态，保留首次载荷及错误记录。Job依赖等待恢复先核对原请求与持久载荷，即使上游未就绪也拒绝坏身份；未创建实例的坏等待与原请求同事务收敛为CANCELLED/REJECTED。若已有该原请求亲自创建的实例，则保留等待并显示`LAUNCH_ALREADY_PREPARED`，应追踪既有CREATED恢复与派发结果，不能人工删除或重新触发。合法等待的损坏依赖快照仍保持WAITING并报告`DEPENDENCY_SNAPSHOT_INVALID`。V294已为两类等待绑定同租户原请求NO ACTION外键；父请求/终态等待按[引用整改专题§2.11](../plans/database-reference-integrity-remediation-2026-10-18.md)先子后父清理，活跃、近期或仍有关联运行/审计的记录保留。FK不校验载荷业务日或状态，应用身份守护仍必须执行；不要用删除原请求或手改载荷解决不一致。

容量等待也可从 Console 的“调度快照 → 持久化等待”只读查看。页面按 `tenantId` 和后端租户守卫过滤，不展示首次保存的启动载荷；遇到 `WAITING`、`RESUMED` 或 `REJECTED` 时，仍须按 requestId/Trace ID 对照请求状态与 WorkflowRun，不能将查询结果当作恢复或派发成功。

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

## 业务日与夏令时日切

有日历时，触发 Instant 先按日历时区定位当日，再和当日日切的实际时间点比较：日切前归前一天，日切起归当天。秋季回拨造成同一日切出现两次时，采用第一次，避免业务日期在重复小时倒退；春季跳时造成日切不存在时，采用跳时后的首个有效时刻。无日历时仍按生效时区取自然日期，不套用默认日切。

例如纽约 2026-11-01、日切 01:30：05:40Z 与回拨后的 06:10Z 都属于 11 月 1 日，不因第二次本地 01:10 退回前一天。之后仍按日历中明确维护的节假日、补工作日与滚动规则处理；周末不自动等同节假日。排查应保留原 fireTime、日历时区/日切及冻结 bizDate，不能仅比较格式化后的本地时分。


### 批量日状态推进的服务所有权

Trigger 不承担 `batch_day_instance` 的 OPEN→CUTOFF 写入。由 Orchestrator 按持久化截止时间或日历时间策略推进，冻结记录跳过，版本 CAS 与审计在同一短事务中完成。排查应检查 Orchestrator 的扫描与审计，不在 Trigger 补建第二条日切写入链路。更新源码只改变后续构建；现有环境需按发布流程更新对应服务，本地回归不代表已部署。


### 准入排队期间开始排水

手工启动、外部事件及指定 occurrence 补跑会先进入本地有界准入队列。若等待期间开始排水，获得许可后在业务调用前再次检查并返回 `STATE_CONFLICT`（HTTP 409），不新增业务写入。已进入业务执行的请求继续由事务、Outbox 与原幂等键收敛；排水开关不撤销已经受理的动作，调用方应按原请求查询状态。


### 外部事件冻结目标的重投

事件级接纳以数字 ID 数组冻结首次 Workflow 目标。按单个 Workflow 重投同一事件时，目标在冻结集合内则复用原请求及 Outbox；集合外目标仍拒绝，不能因后续定义变化扩展既有投递。排查应按 tenant/source/event ID 核对原目标与请求，不将合法重投的冲突误认为需要生成新业务事件。
