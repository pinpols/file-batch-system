# SLO / SLI 目录

本文定义批量调度平台的服务等级指标目录。它不是当前生产承诺,也不替代 staging 压测、灾备演练或客户 SLA；它用于把 Prometheus 规则、Grafana 面板、Runbook 和上线验收连接到同一套业务口径。

## 使用原则

- SLO 必须能回到业务结果,不能只看技术组件是否存活。
- SLI 必须说明数据来源、采样窗口、标签边界和不纳入场景。
- 告警阈值必须能反查到 SLO、容量假设或恢复动作。
- 本地、staging 和生产阈值可以不同；文档只定义口径,目标环境负责给出实际阈值。
- 历史压测和本地 smoke 不能替代当前环境 SLO 证据。

## 核心 SLI

| SLI | 业务含义 | 主要信号 | 典型 Runbook |
|---|---|---|---|
| 调度准点率 | 计划触发在允许延迟内进入 launch / WAITING / RUNNING | trigger fire 延迟、`trigger_request` 状态、Quartz misfire、readiness defer | [`trigger-operations.md`](./trigger-operations.md)、[`dependency-aware-fire.md`](./dependency-aware-fire.md) |
| 批次日完成率 | 指定业务日内关键作业终态达到 SUCCESS 或可解释终态 | `batch_day_instance`、`job_instance` 终态、审批 / skip 记录 | [`batch-day-gate-howto.md`](./batch-day-gate-howto.md)、[`go-live-realism-audit-2026-06-21.md`](./go-live-realism-audit-2026-06-21.md) |
| 运行积压深度 | 运行态排队是否超过容量假设 | WAITING partition、oldest queued age、tenant / queue 并发 | [`heavy-workload-operations.md`](./heavy-workload-operations.md)、[`autoscaling-strategy.md`](./autoscaling-strategy.md) |
| Outbox 投递健康 | 控制面事件是否持续可投递 | `outbox_event` backlog、oldest unpublished age、retry / DLQ | [`outbox-architecture.md`](../architecture/outbox-architecture.md)、[`compensation-cleanup.md`](./compensation-cleanup.md) |
| Kafka 消费滞后 | Worker / 下游消费者是否跟上生产速率 | consumer group lag、topic backlog、KEDA scaler 信号 | [`autoscaling-strategy.md`](./autoscaling-strategy.md)、[`base-services-deployment.md`](./base-services-deployment.md) |
| 文件到达准点率 | 上游文件组是否在 SLA 内完整到达并通过完整性门禁 | file arrival group、manifest / checksum、missing / stale 告警 | [`event-driven-arrival.md`](./event-driven-arrival.md)、[`worker-stage-coverage.md`](./worker-stage-coverage.md) |
| 重试恢复率 | 失败任务是否在重试预算内恢复,未耗尽进入 DLQ | `retry_schedule`、`dead_letter_task`、worker report 失败原因 | [`forensic-replay-howto.md`](./forensic-replay-howto.md)、[`compensation-cleanup.md`](./compensation-cleanup.md) |
| Worker 在线健康 | Worker 是否按能力和租约稳定参与执行 | `worker_registry` 心跳、lease renew、fast retry、offline count | [`rolling-upgrade-workers.md`](./rolling-upgrade-workers.md)、[`worker-stage-coverage.md`](./worker-stage-coverage.md) |
| 下游 readiness 成功率 | 依赖上游结果的触发是否正确等待并在窗口内放行 | readiness defer、timeout、asset partition EFFECTIVE | [`dependency-aware-fire.md`](./dependency-aware-fire.md)、[`../design/asset-partition-readiness.md`](../design/asset-partition-readiness.md) |
| 数据库迁移一致性 | 代码迁移文件与目标库 Flyway history 是否一致 | `flyway_schema_history`、schema governance report | [`db-migration-checklist.md`](./db-migration-checklist.md)、[`../design/database-schema-governance.md`](../design/database-schema-governance.md) |

## 告警映射

| 告警类 | 映射 SLI | 处理原则 |
|---|---|---|
| `Outbox backlog` / oldest unpublished age | Outbox 投递健康 | 先判定数据库写入、publisher、Kafka 或下游 topic 是否异常；不要直接清表 |
| `Kafka lag` / KEDA lag scaler | Kafka 消费滞后、运行积压深度 | 先看消费者在线、rebalance、partition 数和 worker 饱和度，再扩容 |
| `file arrival SLA violation` | 文件到达准点率 | 先定位到达组、上游渠道和 manifest / checksum；缺文件不应手工伪造成功 |
| `ONLINE workers have stale heartbeats` | Worker 在线健康 | 检查 Worker 网络、心跳间隔、租约续期和发布中的滚动升级 |
| `worker lease fast-retry storm` | Worker 在线健康、重试恢复率 | 检查 lease 服务、DB 锁、worker GC / 网络抖动和 fast retry 原因 |
| `readiness timeout` / readiness retry high | 下游 readiness 成功率、调度准点率 | 确认上游真实终态和 asset partition,决定补跑、放弃或延长窗口 |
| `oldest queued partition waited` | 运行积压深度 | 检查准入限制、资源队列、Worker 容量、Kafka lag 和 PG 锁等待 |
| `PG lock / Hikari saturation` | 多个 SLI 的基础依赖 | 先保护写路径和主链路,再分析慢 SQL、连接池和迁移/清理任务 |

Prometheus 规则入口：

- Docker: `deploy/docker/observability/prometheus-batch-rules.yml`
- Helm: `helm/batch-platform/files/prometheus-batch-rules.yml`

修改告警规则时,应同步更新本文或引用的 Runbook,避免告警阈值变成无人解释的技术数字。

## 批量调度告警覆盖矩阵

本节是批量调度主链监控的权威核对入口，按**可用性、事件、批量作业**三个维度组织，避免把“服务存活”“发生异常”和“业务批次完成”混成一个结论。
当前静态核查基线为 2026-10-09；状态只反映仓库代码与配置，不代表目标环境已触发并送达通知。

### 1. 可用性监控

回答“控制面、执行器和关键依赖现在是否可服务”。

| 对象 | 当前规则 / 信号 | 覆盖结论 | 故障定位 |
|---|---|---|---|
| Console、Trigger、Orchestrator、五类 Worker | `BatchServiceDown`、`BatchCoreServiceTargetMissing` / Prometheus `up` | 已发现 target 抓取失败会告警；核心控制面 target 完全消失也会告警。Worker 缩容到零不按 target 缺失告警，依赖 worker registry 的在线/租约信号 | 查进程、探针、服务发现、ServiceMonitor 选择器和 worker registry |
| Worker 在线状态、drain 和 lease | `BatchWorkerStaleOnlinePresent`、`BatchWorkerDrainOverduePresent`、`BatchDecommissionedWorkerActiveClaims`、`WorkerLeaseCircuitOpen`、`WorkerLeaseCircuitCurrentlyOpen` | 已覆盖心跳陈旧、drain 超期、下线仍持有任务和租约熔断 | 查 worker registry、心跳/续租、任务回收和滚动发布 |
| PostgreSQL / 连接池 | `PostgresReplicationStopped`、`PostgresReplicationLagHigh/Critical`、`HikariCpConnectionExhausted`、`HikariCpAcquireTimeout`、锁等待/长事务规则 | 覆盖主从保护、连接池耗尽和关键锁等待；需区分单库部署与启用副本的环境 | 查主库、复制状态、慢 SQL、连接池和长事务 |
| Kafka、Valkey/Redis、对象存储 | `BatchKafkaConsumerLagHigh`、Redis 容量/连接数规则、`BatchDependencyExporterDown`、Dispatch channel probe | Docker 已配置的 Kafka/Redis/PG/MinIO scrape target 不可达会告警；Kubernetes 仅对实际发现的 target 生效，缺失 target 由部署监控核验 | 联查依赖探针、Exporter `up`、客户端错误率和队列积压 |
| 告警和遥测基础设施 | Collector、Loki、Tempo 存活/丢弃/拒收规则；Alertmanager scrape、通知失败/配置重载；`BatchAlertmanagerNotifySkipped`、`BatchAlertmanagerDeliveryFailed` | 无渠道、Console 发送器失败、Alertmanager 下线/通知失败/配置重载失败均有检测规则；Prometheus/Alertmanager 自身停止无法由同一套告警链路可靠自报，须由集群/外部监控监测。目标环境 token、路由、真实接收渠道及最终送达仍需实测 | 按 `observability-stack.md` 做 firing 到最终通知的端到端演练，并用独立通道监控 Prometheus/Alertmanager |

### 2. 事件监控

回答“控制面是否发生了应被运维关注的异常事件”，不等同于作业最终结果。

| 事件 | 当前规则 / 指标 | 覆盖结论 | 说明 |
|---|---|---|---|
| HTTP Controller/API 异常 | `HttpServerErrorRateHigh` / `http_server_requests_seconds_count` | 已覆盖：按稳定 target `job` 分服务计算 5xx 比率，并设请求数下限；避免服务间流量稀释 | 低于请求数下限的单次错误由日志/追踪定位，不按 URI 等高基数标签分组告警 |
| Trigger launch 技术失败 | `TriggerLaunchFailureDetected`、`TriggerLaunchFailureSpike` / `batch_trigger_launch_failed_total` | 已覆盖：低频技术失败告警 + 持续高频严重告警；排除预期限流和业务拒绝 | 消息解析、HTTP 和 runtime 失败触发；业务拒绝保留为业务结果，不升级成平台故障告警 |
| 陈旧 CREATED 实例恢复失败 | `StaleCreatedLaunchRecoveryFailed` / `batch_trigger_launch_created_recovery_failed_total` | 已覆盖：窗口内任一恢复失败即告警 | 恢复失败会阻碍实例自愈；按告警描述检查 DB、trigger_request 和恢复调度器 |
| 告警渠道实际投递失败 | `BatchAlertmanagerDeliveryFailed` / `am_notify_failed_total` | 已覆盖：匹配到渠道但发送器返回失败时按 receiver 计数；与无渠道跳过分开处理 | 检查 `notification_delivery_log`、渠道状态和独立升级渠道；不能只看 Alertmanager 已把 webhook 发到 Console |
| Outbox、REPORT、DLQ、重试及通知投递 | Outbox backlog/GIVE_UP/circuit、Worker report dropped/GIVE_UP、DLQ、Webhook delivery 规则 | 已有多条事件告警 | 按 Runbook 先恢复投递，再处理重放；不能用清理积压替代根因修复 |
| ShedLock、CAS、超时执行器、工作流收尾 | `BatchShedLockProviderFailure`、`OrchestratorCasMiss`、`TimeoutEnforcerFailed`、`WorkflowStuckFinalized` | 已覆盖关键调度器和状态推进异常 | 关联实例/trace 日志排障；Prometheus 标签不得直接加入实例 ID |

### 3. 批量作业监控

回答“作业、批次日、分区是否按业务目标推进并得到可信终态”。

| 对象 | 当前规则 / 指标 | 覆盖结论 | 说明 |
|---|---|---|---|
| 作业终态失败和错误分类 | `JobFailureRateHigh`、`JobErrorCodeRateHigh`、`BatchJobDefinitionFailingRepeatedly` | 已覆盖作业级失败率、错误码和重复失败；仅在作业进入相应终态后体现 | 这是作业聚合信号，不代表每个分片失败都能即时发现 |
| 分片/分区失败 | `JOB_FINAL_PARTITION_FAILURE` / `BatchFinalPartitionFailures` | 已覆盖近期非 dry-run `FAILED`/`PARTIAL_FAILED` 且最终失败分区数大于零的实例；有界查询、幂等 claim、Prometheus 告警；不把 RETRYING、取消或 dry-run 当成最终分区失败 | 查看实例与失败分区详情；扫描回看窗口默认 1 小时，批大小默认 50，可通过 orchestrator 配置调整 |
| 任务超时、长尾和 Pipeline 阶段耗时 | `WorkerTaskTimeoutHigh`、`BatchPipelineStepExecutionLatencyHigh` / 执行时延指标 | 已覆盖任务超时率和阶段 P95 长尾 | 按 worker 类型和阶段定位容量/慢步骤，不把长尾直接等同作业 SLA 违约 |
| 作业耗时过久、启动过晚、完成过晚 | `JobMonitoringScheduler`、`JobInstanceTimeoutEnforcer`、`BatchJobRunningTooLong`、`BatchJobNotStartedByDeadline`、`BatchJobNotCompletedByDeadline` | 三类软时限 + 近期最终失败分区告警共四类均写入 `alert_event`，有独立 Prometheus 告警；耗时阈值适用于所有已启动作业；启动/完成时限适用于无依赖 Cron 和声明上游依赖的作业；真实 PostgreSQL mapper IT 验证候选边界，旁路有独立线程、批量上限和失败隔离。硬超时仍由执行器负责，不改写实例状态 | 共享 PG 仍有 CPU/IO 竞争；应在 Docker/Helm 环境测查询计划和最终通知送达 |
| Pipeline 处理 SLA / 文件到达 | `PipelineProcessingSlaViolation`、`FileArrivalSlaViolation` | 已覆盖已配置并启用的 SLA | 核查处理窗口、上游到达组和 SLA 配置 |
| 调度等待、Worker 选择、容量和反压 | `BatchSchedulerQueueOldestWaitHigh`、`WorkerSelectionNoMatch`、Worker capacity/backpressure、Kafka lag | 已覆盖运行积压与派发能力不足 | 这是“无法及时启动/消费”的信号，不替代分区结果失败 |
| Readiness、批次日与资产新鲜度 | readiness timeout/defer、asset freshness、批次日门禁相关规则 | 覆盖依赖等待、窗口超时及资产新鲜度事件；批次日最终完成率仍需结合业务日视图验收 | 对账实例清单、补跑/审批记录和最终终态 |

### 实施状态与环境验收

代码、配置、规则和本地自动化验证已闭环；由于没有获准的真实收件渠道，不能把目标环境端到端送达标记为完成。前四项为已落地能力，最后一项仍是部署环境验收：

1. ✅ 最终分区失败使用 job_instance 的终态失败计数，通过 1 小时回看、每轮限批、部分索引和独立幂等 claim 发告警；IT 覆盖部分失败、零失败数、历史行和 dry-run。
2. ✅ 已为低频 Trigger launch 技术失败和陈旧 `CREATED` 实例恢复失败补独立告警，并将高频 failure-spike 限定为技术失败。
3. ✅ HTTP 5xx 比率按稳定 `job` 标签计算并设最低请求数；Docker 配置的依赖 Exporter 不可达规则已补。Kubernetes 动态 target 缺失仍由平台部署监控确认，不能用空 `up` 序列推断服务健康。
4. ✅ 已将作业监控按作业配置拆为硬执行超时与三种软时限告警，并增加近期终态失败分区告警；四类告警事件写入现有 `alert_event`，各有独立 Prometheus 告警，扫描/事件持久化故障另行监控。软 SLA 不改变执行状态。实现和配置语义见 [`sla-and-quality.md`](../design/sla-and-quality.md)。
5. 在 Docker 与 Helm 目标环境分别做可控 firing 和端到端送达演练，验证 Prometheus、Alertmanager、Console 鉴权、通知渠道及 delivery log。规则 YAML、CI 静态检查或 Alertmanager 页面可访问都不等于送达验收；当前本地无获准的真实收件端，禁止将其记为已送达。

以上三类监控必须分别验收：可用性故障注入、事件规则触发/恢复、代表性批次从创建到分区终态及 SLA 违约的业务链路。四类作业监控已完成代码、配置链路和真实 PostgreSQL 候选查询验证；promtool fixture 覆盖四类作业告警、服务 down/核心 target 缺失、Trigger 技术失败与陈旧 CREATED 恢复失败、Alertmanager 通知失败/配置错误，并验证预期限流/业务拒绝不误告警。本轮未穷举所有既有规则，也不替代 Docker/Helm 的 Alertmanager 最终送达演练。不得用其中一类的绿灯替代另外两类。

### 规则变更验收

### 告警持续、防风暴与解除语义

- **Prometheus 条件告警**：规则的 `for` 表示条件必须连续成立多久才进入 firing；条件恢复为 false 后进入 resolved。通知端的 `group_wait=30s`、`group_interval=5m`、`repeat_interval=2h` 分别控制首次分组等待、同组更新和持续告警的重复通知，不延迟数据库事件状态变化。所有 webhook receiver 开启 `send_resolved`。
- **计数器窗口告警**：`increase(counter[窗口]) > 0` 表示窗口内发生过事件，不等同于根因仍未恢复。无新事件后，告警会在窗口样本退出后解除；样本窗口加 scrape/evaluation 间隔构成解除延迟。不要把这类 resolved 当作某个具体作业/分区已修复。
- **`alert_event` 业务事件**：相同 tenant/fingerprint 的重复事件合并到一行并累计 `occurrence_count`；OPEN 事件每 60 秒重新发布给 Alertmanager，以维持 firing。事件查询、去重和解除候选均带 tenant；直发 AM 的事件标签也带 tenant。Console 的关闭动作发 `endsAt=now`；同一 tenant、service、类型、级别的 AM label 组仍有其他 OPEN 事件时，不得解除整组告警。确认/静默/关闭是不同操作，历史事件是否自动关闭必须由事件来源给出明确恢复条件，不能由通用计数器窗口推断。
- **平台级指标告警**：不带 tenant 标签，按平台整体汇总并发送到平台通知渠道；它们用于服务、依赖、投递和聚合失败率监控，不提供租户级归属。若要增加租户级业务告警，必须先评估标签基数、租户授权和通知路由，不能直接把 tenant/job/instance 全量加入时序标签。
- **通知防风暴与租户边界**：Alertmanager 按 `alertname/tenant/team/alert_group/severity` 聚合，critical 仅抑制同租户同组 warning，持续 firing 最快每 2 小时重复通知；数据库 fingerprint 去重限制同一业务故障的事件行增长。自定义子路由也必须保留 `tenant` 分组。上述机制不会丢弃高基数业务明细，明细保留在 `alert_event`，不得把 instance/task/resource id 加入 Prometheus labels 规避分组。
- **恢复验收**：每类状态型告警至少验证“触发后持续 firing、恢复后发 resolved、同组仍有另一活动事件时不发 resolved、最后一个活动事件关闭后发 resolved”；计数器窗口告警另验窗口自然过期。通知渠道实际收到 firing/resolved 仍须在目标环境演练，规则单测不证明端到端送达。

- 指标名必须能追溯到生产代码的 meter 注册点；新增 gauge/counter 时验证状态语义、重试/终态口径、dry-run 过滤和租户标签基数。
- 规则必须同时落在 Docker canonical 文件与 Helm 副本，并运行 `bash scripts/ci/check-helm-prometheusrule-sync.sh`。
- 核心服务 target 缺失告警依赖稳定 `job=batch-<component>` 标签；Docker file-SD 与 Helm ServiceMonitor 必须使用同一命名。可弹性缩至零的 Worker 不使用静态 target 缺失告警，应由 Worker registry/容量策略提供业务可用性信号。
- 有 `promtool` 时运行 `promtool check rules deploy/docker/observability/prometheus-batch-rules.yml`；无工具时不能报告规则语法已验证。
- 至少构造一个触发样例和一个不触发样例；重要告警另需在目标环境验证从 Prometheus 到最终接收端的完整链路。
- 每个告警应给出 severity、稳定的 alert group、可定位的摘要/描述和 Runbook；不要在标签中放 instanceId、taskId 等高基数字段。

## 验收证据

一次上线或容量评估至少保留：

- 环境和版本：commit、镜像 digest、配置 profile、数据规模。
- 时间窗口：采样开始 / 结束时间、是否覆盖业务高峰。
- 业务结果：关键批次日、关键作业、文件到达组和重试 / DLQ 结论。
- 技术信号：Outbox、Kafka lag、Worker 心跳、DB 连接池、PG 锁、readiness timeout。
- 未覆盖项：未跑的 Worker 类型、未演练的故障、未接入的告警通道。

## 不纳入 SLO 的内容

- 单次本地 smoke 的耗时。
- 已跳过、已取消或 pending 的 CI job。
- 无真实数据规模的 `idx_scan=0`、低行数或空库现象。
- 前端 mock 接口通过的 Playwright 用例。
- 没有目标环境接收端的告警配置。

## 相关入口

- 观测栈: [`observability-stack.md`](./observability-stack.md)
- 告警升级: [`alert-escalation.md`](./alert-escalation.md)
- 上线就绪: [`go-live-readiness.md`](./go-live-readiness.md)
- staging 执行: [`go-live-staging-execution.md`](./go-live-staging-execution.md)
- 工程成熟度路线: [`../architecture/engineering-maturity-roadmap.md`](../architecture/engineering-maturity-roadmap.md)
