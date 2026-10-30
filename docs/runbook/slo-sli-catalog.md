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
| 调度准点率 | 计划触发在允许延迟内进入 launch / WAITING / RUNNING | trigger fire 延迟、`trigger_request` 状态、请求到实例 p95、Quartz misfire、readiness defer | [`trigger-operations.md`](./trigger-operations.md)、[`dependency-aware-fire.md`](./dependency-aware-fire.md) |
| 批次日完成率 | 指定业务日内关键作业终态达到 SUCCESS 或可解释终态 | `batch_day_instance`、`job_instance` 终态、审批 / skip 记录 | [`batch-day-gate-howto.md`](./batch-day-gate-howto.md)、[`go-live-audit-checklist.md`](../standards/go-live-audit-checklist.md) |
| 日切恢复进度 | Orchestrator 停机后，启用日历的原始业务日 catch-up 是否收敛 | `batch_batch_day_open_catchup_remaining_days`、`batch_batch_day_open_catchup_calendars_behind`、扫描失败计数 | [`batch-day-gate-howto.md`](./batch-day-gate-howto.md) |
| 作业依赖等待 | 下游作业是否长期因上游结果缺失而无法启动 | `batch_job_dependency_waiting`、`batch_job_dependency_wait_oldest_age_seconds`、超时告警 | [`dependency-aware-fire.md`](./dependency-aware-fire.md) |
| 运行积压深度 | 运行态排队是否超过容量假设 | WAITING partition、oldest queued age、tenant / queue 并发 | [`heavy-workload-operations.md`](./heavy-workload-operations.md)、[`autoscaling-strategy.md`](./autoscaling-strategy.md) |
| Outbox 投递健康 | 控制面事件是否持续可投递 | `outbox_event` backlog、oldest unpublished age、retry / DLQ | [`outbox-architecture.md`](../architecture/outbox-architecture.md)、[`compensation-cleanup.md`](./compensation-cleanup.md) |
| Kafka 消费滞后 | Worker / 下游消费者是否跟上生产速率 | consumer group lag、topic backlog、KEDA scaler 信号 | [`autoscaling-strategy.md`](./autoscaling-strategy.md)、[`base-services-deployment.md`](./base-services-deployment.md) |
| 基础依赖可用性 | 应用依赖是否可连接，关键集群是否仍有可用成员 | exporter target `up`、PostgreSQL `pg_up`、Redis `redis_up`、Kafka `kafka_brokers`、MinIO 离线节点/磁盘数 | 本节依赖故障告警；实际目标清单和 Kafka 期望 Broker 数由部署环境提供 |
| 文件到达准点率 | 上游文件组是否在 SLA 内完整到达并通过完整性门禁 | file arrival group、`batch_file_arrival_group_waiting_count`、`batch_file_arrival_group_oldest_wait_seconds`、manifest / checksum、missing / stale 告警 | [`event-driven-arrival.md`](./event-driven-arrival.md)、[后端 Sim 可靠性场景组](../testing/backend-sim-reliability.md) |
| 重试恢复率 | 失败任务是否在重试预算内恢复,未耗尽进入 DLQ | `retry_schedule`、`dead_letter_task`、worker report 失败原因 | [`forensic-replay-howto.md`](./forensic-replay-howto.md)、[`compensation-cleanup.md`](./compensation-cleanup.md) |
| Worker 在线健康 | Worker 是否按能力和租约稳定参与执行 | `worker_registry` 心跳、lease renew、fast retry、offline count | [`rolling-upgrade-workers.md`](./rolling-upgrade-workers.md)、[后端 Sim 可靠性场景组](../testing/backend-sim-reliability.md) |
| 下游 readiness 成功率 | 依赖上游结果的触发是否正确等待并在窗口内放行 | readiness defer、timeout、asset partition EFFECTIVE | [`dependency-aware-fire.md`](./dependency-aware-fire.md)、[`../design/asset-partition-readiness.md`](../design/asset-partition-readiness.md) |
| 数据库迁移一致性 | 代码迁移文件与目标库 Flyway history 是否一致 | `flyway_schema_history`、schema governance report | [`db-migration-checklist.md`](./db-migration-checklist.md)、[`../design/database-schema-governance.md`](../design/database-schema-governance.md) |
| 归档调度健康 | 归档与文件清理调度是否持续正常返回 | `batch_archive_scheduler_last_success_timestamp_seconds{scheduler=...}`、数据库增长与磁盘水位 | [`backup-and-pitr.md`](./backup-and-pitr.md) |

## 告警映射

| 告警类 | 映射 SLI | 处理原则 |
|---|---|---|
| `Outbox backlog` / oldest unpublished age | Outbox 投递健康 | 先判定数据库写入、publisher、Kafka 或下游 topic 是否异常；不要直接清表 |
| `Kafka lag` / KEDA lag scaler | Kafka 消费滞后、运行积压深度 | 先看消费者在线、rebalance、partition 数和 worker 饱和度，再扩容 |
| `BatchKafkaConsumerLagHigh` | Kafka 消费滞后 | 检查 Worker、Trigger、Console Job/Workflow 通知及 OpenLineage 消费组的 topic/partition 和命名空间；确认积压后再检查消费回执与重试 |
| `BatchPostgresUnavailable`、`BatchRedisUnavailable`、`BatchKafkaUnavailable`、`BatchMinioNodesOffline` | 基础依赖可用性 | 先区分 exporter target 不可抓取与服务自身健康失败；Kafka 当前只告警零 Broker，部分 Broker 故障须按环境期望数量判定 |
| `BatchJobMonitoringScanFailures`、`BatchJobMonitoringEventFailures`、`BatchWorkflowMonitoringFailures` | Job / Workflow SLA 监控覆盖 | 按告警标签查看失败阶段；扫描或整轮扫描恢复成功后自动解除，累计 counter 仅用于频次分析 |
| `file arrival SLA violation` | 文件到达准点率 | 先定位到达组、上游渠道和 manifest / checksum；缺文件不应手工伪造成功 |
| `ONLINE workers have stale heartbeats` | Worker 在线健康 | 检查 Worker 网络、心跳间隔、租约续期和发布中的滚动升级 |
| `worker lease fast-retry storm` | Worker 在线健康、重试恢复率 | 检查 lease 服务、DB 锁、worker GC / 网络抖动和 fast retry 原因 |
| `readiness timeout` / readiness retry high | 下游 readiness 成功率、调度准点率 | 确认上游真实终态和 asset partition,决定补跑、放弃或延长窗口 |
| `oldest queued partition waited` | 运行积压深度 | 检查准入限制、资源队列、Worker 容量、Kafka lag 和 PG 锁等待 |
| `PG lock / Hikari saturation` | 多个 SLI 的基础依赖 | 先保护写路径和主链路,再分析慢 SQL、连接池和迁移/清理任务 |
| `ArchiveSchedulerNeverSucceeded` / `ArchiveSchedulerStale` | 归档调度健康 | 先按 `scheduler` 标签检查任务日志、ShedLock、数据库连通性和归档开关；此指标表示调度调用正常返回，不证明逐条归档成功 |
| `BatchDayCatchupBacklog` / `BatchDayCatchupScanFailures` | 日切恢复进度 | 先检查 Orchestrator `batch_day_open` 调度与错误日志；失败时 gauge 保留上次完整快照。全局指标不含 tenant/calendar 归属，也不发现游标锚点前历史缺口 |
| `BatchJobDependencyWaitTooOld` | 作业依赖等待 | 指标仅为全局聚合且不含租户/作业标签；从租户作用域的 Console WAITING 列表定位，再检查对应上游业务日结果和超时告警，禁止直接修改等待表 |

Prometheus 规则入口：

- Docker: `deploy/docker/observability/prometheus-batch-rules.yml`
- Helm: `helm/batch-platform/files/prometheus-batch-rules.yml`

修改告警规则时,应同步更新本文或引用的 Runbook,避免告警阈值变成无人解释的技术数字。

## 批量调度告警覆盖矩阵

本节是批量调度主链监控的权威核对入口，按**可用性、事件、批量作业**三个维度组织，避免把“服务存活”“发生异常”和“业务批次完成”混成一个结论。
当前静态核查基线为 2026-10-26；状态只反映仓库代码与配置，不代表目标环境已触发并送达通知。

### 1. 可用性监控

回答“控制面、执行器和关键依赖现在是否可服务”。

| 对象 | 当前规则 / 信号 | 覆盖结论 | 故障定位 |
|---|---|---|---|
| Console、Trigger、Orchestrator、五类 Worker | `BatchServiceDown`、`BatchCoreServiceTargetMissing` / Prometheus `up` | 已发现 target 抓取失败会告警；核心控制面 target 完全消失也会告警。Worker 缩容到零不按 target 缺失告警，依赖 worker registry 的在线/租约信号 | 查进程、探针、服务发现、ServiceMonitor 选择器和 worker registry |
| Prometheus 规则引擎、Alertmanager | `BatchPrometheusDown`、`BatchPrometheusConfigReloadFailed`、`BatchPrometheusRuleEvaluationFailures`、`BatchAlertmanagerDown` | Docker Prometheus 抓取自身；Helm 外部 Prometheus 须把自身和 Alertmanager metrics target 分别标为 `job=prometheus`、`job=alertmanager`。目标抓取失败或消失、配置重载失败及规则计算失败均有规则；两者整体停止仍须栈外监控发现 | 查 Prometheus Targets、稳定 `job` 标签、配置重载状态、规则评估日志及栈外探针 |
| Worker 在线状态、drain 和 lease | `BatchWorkerStaleOnlinePresent`、`BatchWorkerDrainOverduePresent`、`BatchDecommissionedWorkerActiveClaims`、`WorkerLeaseCircuitOpen`、`WorkerLeaseCircuitCurrentlyOpen` | 已覆盖心跳陈旧、drain 超期、下线仍持有任务和租约熔断 | 查 worker registry、心跳/续租、任务回收和滚动发布 |
| PostgreSQL / 连接池 | `PostgresReplicationStopped`、`PostgresReplicationLagHigh/Critical`、`HikariCpConnectionExhausted`、`HikariCpAcquireTimeout`、锁等待/长事务规则 | 覆盖主从保护、连接池耗尽和关键锁等待；需区分单库部署与启用副本的环境 | 查主库、复制状态、慢 SQL、连接池和长事务 |
| Kafka、Valkey/Redis、对象存储 | `BatchKafkaConsumerLagHigh`、Redis 容量/连接数规则、`BatchDependencyExporterDown`、Dispatch channel probe | Docker 已配置的 Kafka/Redis/PG/MinIO scrape target 不可达会告警；Kubernetes 仅对实际发现的 target 生效，缺失 target 由部署监控核验 | 联查依赖探针、Exporter `up`、客户端错误率和队列积压 |
| 告警和遥测基础设施 | Collector、Loki、Tempo 存活/丢弃/拒收规则；Prometheus/Alertmanager 自身状态、通知失败/配置重载；`BatchAlertmanagerNotifySkipped`、`BatchAlertmanagerDeliveryFailed` | 无渠道、Console 发送器失败及 Prometheus/Alertmanager 可观测到的失效均有规则；两者整体停止仍须栈外独立监控发现。目标环境 token、路由、真实接收渠道及最终送达仍需实测 | 按 `observability-stack.md` 做 firing 到最终通知的端到端演练，并用独立通道监控 Prometheus/Alertmanager |
| 用户访问路径/API 可用性 | `HttpServerErrorRateHigh`、HTTP 请求指标 | 仅覆盖服务级 5xx 比率（5 分钟至少 20 个请求，持续 5 分钟）；未发现黑盒探测、端到端 API 合成事务、通用 HTTP 延迟告警或已批准的可用性 SLO/error-budget burn 规则 | 需由平台明确目标路径、探测身份、采样频率、可用性/延迟目标和告警阈值；验证 DNS/Ingress/TLS 到 API 的真实路径，并与应用内 5xx 信号互补 |

### 2. 事件监控

回答“控制面是否发生了应被运维关注的异常事件”，不等同于作业最终结果。

| 事件 | 当前规则 / 指标 | 覆盖结论 | 说明 |
|---|---|---|---|
| HTTP Controller/API 异常 | `HttpServerErrorRateHigh` / `http_server_requests_seconds_count` | 已覆盖：按稳定 target `job` 分服务计算 5xx 比率，并设请求数下限；避免服务间流量稀释 | 低于请求数下限的单次错误由日志/追踪定位，不按 URI 等高基数标签分组告警 |
| Import 对象到达事件 | `batch.import.event_arrival.notifications`、`batch.import.event_arrival.scans` | 部分覆盖：只有通知数和即时扫描调用数，没有扫描成功/失败、最后成功时间、对象登记结果或到达至 `file_record` 的时延指标；当前没有对应 Prometheus 告警。扫描总开关关闭时返回 `scanner-disabled` 且不计扫描；在途扫描会合并通知，对象若在该轮列表快照之后落地仍需等待轮询兜底，且未度量合并数和事件到登记时延 | 补低基数扫描结果、新鲜度、合并数量和事件到登记延迟信号及告警。轮询兜底只能限制漏发现时间，不能证明事件加速生效 |
| Trigger launch 技术失败 | `TriggerLaunchFailureDetected`、`TriggerLaunchFailureSpike` / `batch_trigger_launch_failed_total` | 已覆盖：低频技术失败告警 + 持续高频严重告警；排除预期限流和业务拒绝 | 消息解析、HTTP 和 runtime 失败触发；业务拒绝保留为业务结果，不升级成平台故障告警 |
| ACCEPTED 请求未创建实例 | `TriggerStaleAcceptedWithoutInstance` / `batch_trigger_launch_stale_accepted_count`、`batch_trigger_launch_stale_accepted_oldest_age_seconds` | 已覆盖：超过配置阈值仍没有非 CREATED 实例即告警；待人工审批的 misfire/catch-up 不计入，避免把审批等待误报为投递故障 | 排查 outbox 发布、Kafka consumer、Orchestrator launch 错误和陈旧 CREATED 恢复；审批等待由独立待审批/过期链路处理；指标为全局聚合，不暴露租户标签 |
| 请求到实例延迟 | `TriggerRequestToInstanceLatencyHigh` / `batch_trigger_launch_request_to_instance_p95_seconds` | 已覆盖：近 15 分钟至少 20 个样本且 p95 超过 30 秒持续 10 分钟告警 | 这是数据库请求创建到 job_instance 创建的端到端控制面时延；WAITING/未启动请求不进入延迟样本 |
| 陈旧 CREATED 实例恢复失败 | `StaleCreatedLaunchRecoveryFailed` / `batch_trigger_launch_created_recovery_failed_total` | 已覆盖：窗口内任一恢复失败即告警 | 恢复失败会阻碍实例自愈；按告警描述检查 DB、trigger_request 和恢复调度器 |
| 告警渠道实际投递失败 | `BatchAlertmanagerDeliveryFailed` / `am_notify_failed_total` | 已覆盖：匹配到渠道但发送器返回失败时按 receiver 计数；与无渠道跳过分开处理 | 检查 `notification_delivery_log`、渠道状态和独立升级渠道；不能只看 Alertmanager 已把 webhook 发到 Console |
| Job/Workflow 监控调度停摆 | `BatchJobMonitoringStale`、`BatchWorkflowMonitoringStale` / 最近成功时间、启用状态和扫描间隔 | 已覆盖：启用后从未成功或超过配置扫描间隔五倍且至少五分钟未成功会告警；显式禁用时不误报 | 检查启用开关、ShedLock、调度线程、租户目录和数据库 |
| Outbox、REPORT、DLQ、重试及通知投递 | Outbox backlog/GIVE_UP/circuit、Worker report dropped/GIVE_UP、DLQ、Webhook delivery 规则 | 已有多条事件告警 | 按 Runbook 先恢复投递，再处理重放；不能用清理积压替代根因修复 |
| ShedLock、CAS、超时执行器、工作流收尾 | `BatchShedLockProviderFailure`、`OrchestratorCasMiss`、`TimeoutEnforcerFailed`、`WorkflowStuckFinalized` | 已覆盖关键调度器和状态推进异常 | 关联实例/trace 日志排障；Prometheus 标签不得直接加入实例 ID |

### 3. 批量作业监控

回答“作业、批次日、分区是否按业务目标推进并得到可信终态”。

| 对象 | 当前规则 / 指标 | 覆盖结论 | 说明 |
|---|---|---|---|
| 作业终态失败和错误分类 | `JobFailureRateHigh`、`JobErrorCodeRateHigh`、`BatchJobDefinitionFailingRepeatedly` | 已覆盖作业级失败率、错误码和重复失败；仅在作业进入相应终态后体现 | 这是作业聚合信号，不代表每个分片失败都能即时发现 |
| 分片/分区失败 | `JOB_FINAL_PARTITION_FAILURE` / `BatchFinalPartitionFailures` | 已覆盖近期非 dry-run `FAILED`/`PARTIAL_FAILED` 且最终失败分区数大于零的实例；有界查询、幂等 claim、Prometheus 告警；不把 RETRYING、取消或 dry-run 当成最终分区失败 | 查看实例与失败分区详情；扫描回看窗口默认 1 小时，批大小默认 50，可通过 orchestrator 配置调整 |
| 任务超时、长尾和 Pipeline 阶段耗时 | `WorkerTaskTimeoutHigh`、`BatchPipelineStepExecutionLatencyHigh` / 执行时延指标 | 已覆盖任务超时率和阶段 P95 长尾 | 按 worker 类型和阶段定位容量/慢步骤，不把长尾直接等同作业 SLA 违约 |
| 作业耗时过久、启动过晚、完成过晚 | `JobMonitoringScheduler`、`JobInstanceTimeoutEnforcer`、`BatchJobRunningTooLong`、`BatchJobNotStartedByDeadline`、`BatchJobNotCompletedByDeadline` | 三类软时限 + 近期最终失败分区告警共四类均写入 `alert_event`，有独立 Prometheus 告警；耗时阈值适用于所有已启动作业；启动/完成时限适用于无依赖 Cron 和声明上游依赖的作业；真实 PostgreSQL mapper IT 验证候选边界，旁路有独立线程、批量上限和失败隔离。软监控不改变实例状态；硬超时执行器会按条件将实例收敛为失败并推进关联 Workflow | 共享 PG 仍有 CPU/IO 竞争；应在 Docker/Helm 环境测查询计划和最终通知送达 |
| Pipeline 处理 SLA / 文件到达 | `PipelineProcessingSlaViolation`、`FileArrivalSlaViolation` | 已覆盖已配置并启用的 SLA | 核查处理窗口、上游到达组和 SLA 配置 |
| 调度等待、Worker 选择、容量和反压 | `BatchSchedulerQueueOldestWaitHigh`、`WorkerSelectionNoMatch`、Worker capacity/backpressure、Kafka lag | 已覆盖运行积压与派发能力不足 | 这是“无法及时启动/消费”的信号，不替代分区结果失败 |
| Readiness、批次日与资产新鲜度 | readiness timeout/defer、asset freshness、批次日门禁相关规则 | 覆盖依赖等待、窗口超时及资产新鲜度事件；批次日最终完成率仍需结合业务日视图验收 | 对账实例清单、补跑/审批记录和最终终态 |

### 2026-10-25 事件与可用性覆盖复核

本次核对当前 `fix/wdi-current` 的指标生产端、规则、消费组和解除发送链路，并补齐可由仓库规则与应用指标闭环的缺口。规则文件及 Helm 副本已更新；promtool 夹具尚未运行，目标环境和真实收件端也未验收。

| 范围 | 已确认缺口 | 补齐与验收要求 |
|---|---|---|
| 依赖服务实际健康 | 除 exporter 抓取失败外，新增 `BatchPostgresUnavailable`（`pg_up`）、`BatchRedisUnavailable`（`redis_up`）、`BatchKafkaUnavailable`（`kafka_brokers < 1`）及 MinIO 离线节点/磁盘告警；Kafka 规则只识别无可用 Broker，不判断部分 Broker 故障 | 规则按 Compose 固定依赖指标实现；目标环境还须确认 exporter 已部署、指标权限和标签正确，并为 Kafka 部分 Broker 故障提供拓扑基准 |
| Kubernetes 依赖目标缺失 | 当前 ServiceMonitor 模板覆盖应用和可选 Collector；依赖 exporter 由外部部署负责, `up == 0` 不覆盖整个目标消失 | 明确部署模式的预期依赖目标清单及外部监控责任, 针对预期目标缺失验证告警；不为未启用的组件制造告警 |
| 消费积压 | `BatchKafkaConsumerLagHigh` 已覆盖五类 Worker、Trigger launch、Console Job/Workflow 终态通知及可选 OpenLineage 消费组，并允许 `BatchKafkaTopicNamespace` 添加的前缀 | fixture 覆盖 namespaced Workflow 通知和无前缀 Job 通知；promtool 尚未运行，仍需部署环境核对实际 topic、消费组和 exporter 标签 |
| 监控器自身失效 | Workflow 扫描器新增按 `directory`、`tenant_scan`、`emit` 分 stage 的最后失败/整轮成功时间戳；Job 查询及告警持久化也新增按违规类型和阶段区分的时间戳 | Prometheus 规则比较最近失败与最近完整成功；成功完成后自动解除，累计 counter 保留用于频次分析。Job/Workflow scheduler 定向单测 11 项通过；promtool fixture 已补但本机缺少 promtool，尚未执行 |
| 监控器静默停摆 | 失败时间戳只能发现已发生的失败，不能发现调度器从未成功运行或调度任务停止 | 两类调度器启动时暴露启用状态和扫描周期；新增未成功/超期规则，按扫描周期五倍且至少五分钟判定，禁用状态不触发。新增规则 fixture 尚待 promtool 执行 |
| 低频首次失败 | 原先仅用 `increase(counter[5m])` 会在首次抓取前发生孤立失败时漏报 | 新增并预注册时间戳 gauge，失败时间保留到相应查询或完整扫描成功；规则不依赖 counter 的首个样本，fixture 覆盖首次观测与恢复。进程重启后的恢复边界仍需目标环境演练 |
| 告警解除可靠性 | V357 保存关闭意图；轮询前以来源事实和行快照 CAS 判定 Job/Workflow 软时限告警是否可关闭；Workflow 子作业在实例创建前后按触发请求身份持续跟踪 | 先前 26 项自动解除用例通过；本轮候选、发射、调度及恢复联合 42 项通过（29 项真实 PostgreSQL），覆盖未建实例机会、自然开始/终态、明确拒绝和回执保护。Console 至本地 HTTP 收件端已有真实 PostgreSQL 定向用例覆盖 503 重试、稳定幂等键及发生/恢复回执；真实 Alertmanager/Orchestrator 联合投递仍待补，不以自然超时替代恢复证据 |

本轮未启动监控栈、注入依赖故障或向真实收件渠道发送消息。已在规则和代码中补齐 Prometheus 自监控、目标缺失、规则引擎故障、Job/Workflow 监控器停摆、依赖后端健康、核心事件消费组 lag 和低频首次失败保持信号；Workflow 子作业未创建 `job_instance` 前的开始/完成时限机会已按触发请求补入扫描与恢复闭环。静态审查另确认 Import 到达事件缺少结果/新鲜度指标及告警，服务级 5xx 规则也不构成用户访问可用性 SLO。剩余缺口包括 Kubernetes 依赖 target 清单和缺失告警责任、Kafka 部分 Broker 阈值、Alertmanager/Prometheus 栈外独立监控、用户路径黑盒与量化 SLO、Import 事件监控补齐及真实 firing/resolved 最终收件联验。动态路由页面仍是预留能力，不计作运行时覆盖。作业及 Workflow 时限告警的环境验收继续跟踪于[专项计划](../plans/job-workflow-sla-completion-plan-2026-10-25.md)。

### 实施状态与环境验收

以下记录区分仓库实现和环境验收；新增规则及时间戳指标尚未通过 promtool 或目标环境验证，真实收件也未验收：

1. ✅ 最终分区失败使用 job_instance 的终态失败计数，通过 1 小时回看、每轮限批、部分索引和独立幂等 claim 发告警；IT 覆盖部分失败、零失败数、历史行和 dry-run。
2. ✅ 已为低频 Trigger launch 技术失败和陈旧 `CREATED` 实例恢复失败补独立告警，并将高频 failure-spike 限定为技术失败。
3. ✅ HTTP 5xx 比率按稳定 `job` 标签计算并设最低请求数；Docker 规则现区分 exporter 不可达与 PostgreSQL、Redis、Kafka 全部 Broker 不可用及 MinIO 节点/磁盘离线。Kubernetes 动态 target 缺失仍由平台部署监控确认，不能用空 `up` 序列推断服务健康。
4. ✅ 已将作业监控按作业配置拆为硬执行超时与三种软时限告警，并增加近期终态失败分区告警；四类告警事件写入现有 `alert_event`，各有独立 Prometheus 告警。Job/Workflow 监控器新增失败保持与成功恢复时间戳。软 SLA 不改变执行状态。实现和配置语义见 [`sla-and-quality.md`](../design/sla-and-quality.md)。
5. 在 Docker 与 Helm 目标环境分别做可控 firing 和端到端送达演练，验证 Prometheus、Alertmanager、Console 鉴权、通知渠道及 delivery log。规则 YAML、CI 静态检查或 Alertmanager 页面可访问都不等于送达验收；当前本地无获准的真实收件端，禁止将其记为已送达。

以上三类监控必须分别验收：可用性故障注入、事件规则触发/恢复、代表性批次从创建到分区终态及 SLA 违约的业务链路。现有 fixture 覆盖四类作业告警、服务 down/核心 target 缺失、Prometheus/Alertmanager 自检与目标缺失、监控扫描器停摆、Trigger 技术失败、Alertmanager 投递失败，以及依赖健康、消费组 lag 和监控器恢复；本机缺少 promtool，新增 fixture 尚未执行。本轮未穷举所有既有规则，也不替代 Docker/Helm 故障注入与 Alertmanager 最终送达演练。不得用其中一类的绿灯替代另外两类。

### 2026-10-26 监控补充复核

在 `fix/wdi-current`、基线 `f3d5e400d` 及其既有未提交改动上补齐：Docker Prometheus 自抓取及其配置重载/规则计算错误告警；Alertmanager target 完全缺失告警；Job/Workflow 监控器启用状态、扫描间隔和“从未成功或超期未成功”告警。停用的监控器由启用指标明确排除，不将预期停用判为故障。

验证：`check-observability-contract.py`、Helm 规则同步、Helm lint/template 渲染、文档结构/日期/链接/SLO/Changelog 守卫、四份 YAML 解析和 `git diff --check` 通过；隔离源码副本中 Job/Workflow scheduler 定向 11 项测试通过，0 失败、0 错误、0 跳过。当前没有 `promtool`，Docker API socket 访问被拒，Prometheus 规则 fixture 未运行；未启动观测栈、注入故障或向真实渠道发送通知。

仍需环境补齐：Alertmanager/Prometheus 栈外独立监控、Kubernetes 依赖 target 预期清单和缺失告警、Kafka 部分 Broker 阈值、Docker/Helm 运行时 firing/resolved 及最终收件联验。未建实例 Workflow 子作业机会已补源码和真实 PostgreSQL 定向验证，覆盖明细及边界见[专项计划第十批](../plans/job-workflow-sla-completion-plan-2026-10-25.md#第十批workflow-子作业未建实例机会监控)。

### 2026-10-26 事件与用户路径可用性静态复核

本次只静态核对 Import 到达事件生产端、HTTP/服务告警规则及现有 SLO 目录，未运行测试、promtool 或目标环境探测。确认 Import 端点目前只有通知/扫描调用计数，缺少扫描结果、最后成功时间与到达登记时延告警；当事件到达开关开启但扫描器总开关关闭时，调用会被扫描器提前忽略，端点仍返回 `triggered=true` 并计一次扫描。在途扫描会合并通知；若对应对象晚于该轮列表快照落地，本轮可能看不到，只能等待后续轮询，当前未统计合并数量或事件到登记延迟。服务可用性已有 Prometheus target/down 与聚合 5xx 规则，但未发现用户路径黑盒、通用 HTTP 延迟告警或量化可用性 SLO/error-budget burn 规则。整改和验收责任列于 [TODO Master OBS-ENV-05/06](../plans/todo-master.md)。

### 规则变更验收

### 告警持续、防风暴与解除语义

- **Prometheus 条件告警**：规则的 `for` 表示条件必须连续成立多久才进入 firing；条件恢复为 false 后进入 resolved。通知端的 `group_wait=30s`、`group_interval=5m`、`repeat_interval=2h` 分别控制首次分组等待、同组更新和持续告警的重复通知，不延迟数据库事件状态变化。所有 webhook receiver 开启 `send_resolved`。
- **计数器窗口告警**：`increase(counter[窗口]) > 0` 表示窗口内发生过事件，不等同于根因仍未恢复。无新事件后，告警会在窗口样本退出后解除；样本窗口加 scrape/evaluation 间隔构成解除延迟。不要把这类 resolved 当作某个具体作业/分区已修复。
- **`alert_event` 业务事件**：相同 tenant/fingerprint 的重复事件合并到一行并累计 `occurrence_count`；OPEN/ACKED/SUPPRESSED 按租户、尝试水位公平轮询以维持 firing，确认和静默本身不代表恢复。V357 对新关闭意图保存 `closed_at` 与原因，CLOSED 在最终渠道恢复回执不足时周期补发；尝试不视为送达，旧恢复回执及晚到的发生回执均需重验。同一通知组各 fingerprint 独立解除，当前实体关闭不解除或阻止其他实体。历史 CLOSED 不回填通知意图以避免部署后重放历史；自动关闭仍必须由来源定义真实恢复条件，不能从计数器窗口推断。
- **平台级指标告警**：不带 tenant 标签，按平台整体汇总并发送到平台通知渠道；它们用于服务、依赖、投递和聚合失败率监控，不提供租户级归属。若要增加租户级业务告警，必须先评估标签基数、租户授权和通知路由，不能直接把 tenant/job/instance 全量加入时序标签。
- **通知防风暴与租户边界**：Alertmanager 按 `alertname/tenant/team/alert_group/severity` 聚合，critical 仅抑制同租户同组 warning，持续 firing 最快每 2 小时重复通知；数据库 fingerprint 去重限制同一业务故障的事件行增长。自定义子路由也必须保留 `tenant` 分组。上述机制不会丢弃高基数业务明细，明细保留在 `alert_event`，不得把 instance/task/resource id 加入 Prometheus labels 规避分组。
- **恢复验收**：每类状态型告警至少验证“触发后持续 firing、恢复后按当前实体发 resolved、同组其他活动实体保持 firing、逐条关闭后均有各自恢复回执”；计数器窗口告警另验窗口自然过期。通知渠道实际收到 firing/resolved 仍须在目标环境演练，规则单测不证明端到端送达。

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

## 维护窗口的通知边界

- `BatchConsoleMaintenanceSuppressionUnavailable`：公告启用但范围/地址/共享状态或维护信号同步未确认，持续一分钟告警；不能把此状态标记为已经静默。
- `BatchConsoleMaintenanceWindowOverdue`：维护开关仍开、有限通知窗口已到期，持续一分钟告警；通知恢复不自动解除业务冻结。
- 计划维护仅抑制同部署/服务的两类可用性通知，维护控制信号不进入用户接收渠道。统一 HTTP 指标标签区分预期维护拒绝和真实错误，规则的分子、分母、请求量下限同时排除预期维护请求。SLA、数据完整性和监控自身故障保留原判断，见[维护 SOP](maintenance-mode.md#10-维护期通知抑制2026-10-26)。
