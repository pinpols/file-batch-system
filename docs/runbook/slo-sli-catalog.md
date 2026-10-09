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
| Console、Trigger、Orchestrator、五类 Worker | `BatchServiceDown` / Prometheus `up` | 覆盖已发现 target 的 scrape 失败；target 从服务发现中消失时未必产生 `up=0` | 查进程、探针、网络和 Prometheus target 列表 |
| Worker 在线状态、drain 和 lease | `BatchWorkerStaleOnlinePresent`、`BatchWorkerDrainOverduePresent`、`BatchDecommissionedWorkerActiveClaims`、`WorkerLeaseCircuitOpen`、`WorkerLeaseCircuitCurrentlyOpen` | 已覆盖心跳陈旧、drain 超期、下线仍持有任务和租约熔断 | 查 worker registry、心跳/续租、任务回收和滚动发布 |
| PostgreSQL / 连接池 | `PostgresReplicationStopped`、`PostgresReplicationLagHigh/Critical`、`HikariCpConnectionExhausted`、`HikariCpAcquireTimeout`、锁等待/长事务规则 | 覆盖主从保护、连接池耗尽和关键锁等待；需区分单库部署与启用副本的环境 | 查主库、复制状态、慢 SQL、连接池和长事务 |
| Kafka、Valkey/Redis、对象存储 | `BatchKafkaConsumerLagHigh`、Redis 容量/连接数规则、Dispatch channel probe | 覆盖消费滞后、部分 Redis 容量和派发通道探测；Kafka/exporter、Redis exporter、MinIO 的 target/服务不可达告警仍需核验 | 联查依赖探针、Exporter `up`、客户端错误率和队列积压 |
| 告警和遥测基础设施 | Collector、Loki、Tempo 存活/丢弃/拒收规则；Alertmanager → Console webhook 配置 | 规则与配置入口存在；目标环境的 token、路由、接收器和最终通知送达需实测 | 按 `observability-stack.md` 做 firing 到最终通知的端到端演练 |

### 2. 事件监控

回答“控制面是否发生了应被运维关注的异常事件”，不等同于作业最终结果。

| 事件 | 当前规则 / 指标 | 覆盖结论 | 说明 |
|---|---|---|---|
| HTTP Controller/API 异常 | `HttpServerErrorRateHigh` / `http_server_requests_seconds_count` | 部分覆盖：当前按所有服务汇总 5xx 比例，服务间流量可能互相稀释；低流量单次故障不一定触发 | 应基于 target 的稳定 `job` 标签按应用计算，避免用 URI 等高基数标签分组告警 |
| Trigger launch 失败 | `TriggerLaunchFailureSpike` / `batch_trigger_launch_failed_total` | 部分覆盖：仅非限流失败持续超过 1 次/秒才触发；低频技术失败需有单独规则 | launch 消息解析、HTTP/runtime、业务拒绝要按可操作性区分，不应混为同一严重级别 |
| 陈旧 CREATED 实例恢复失败 | `batch.trigger.launch.created_recovery_failed.total` | 有生产指标、未发现对应 Prometheus 告警规则 | 恢复失败会阻碍实例自愈，应对任意持续/重复失败告警 |
| Outbox、REPORT、DLQ、重试及通知投递 | Outbox backlog/GIVE_UP/circuit、Worker report dropped/GIVE_UP、DLQ、Webhook delivery 规则 | 已有多条事件告警 | 按 Runbook 先恢复投递，再处理重放；不能用清理积压替代根因修复 |
| ShedLock、CAS、超时执行器、工作流收尾 | `BatchShedLockProviderFailure`、`OrchestratorCasMiss`、`TimeoutEnforcerFailed`、`WorkflowStuckFinalized` | 已覆盖关键调度器和状态推进异常 | 关联实例/trace 日志排障；Prometheus 标签不得直接加入实例 ID |

### 3. 批量作业监控

回答“作业、批次日、分区是否按业务目标推进并得到可信终态”。

| 对象 | 当前规则 / 指标 | 覆盖结论 | 说明 |
|---|---|---|---|
| 作业终态失败和错误分类 | `JobFailureRateHigh`、`JobErrorCodeRateHigh`、`BatchJobDefinitionFailingRepeatedly` | 已覆盖作业级失败率、错误码和重复失败；仅在作业进入相应终态后体现 | 这是作业聚合信号，不代表每个分片失败都能即时发现 |
| 分片/分区失败 | 当前只有作业终态失败聚合；队列指标不包含 FAILED 数 | **缺独立分区失败信号及告警** | 需补充低基数、无高频全表扫描的最终失败计数；定义重试中、最终失败、取消和 dry-run 的口径 |
| 任务超时、长尾和 Pipeline 阶段耗时 | `WorkerTaskTimeoutHigh`、`BatchPipelineStepExecutionLatencyHigh` / 执行时延指标 | 已覆盖任务超时率和阶段 P95 长尾 | 按 worker 类型和阶段定位容量/慢步骤，不把长尾直接等同作业 SLA 违约 |
| 作业运行超时、耗时过长、到期未启动、结束晚 | `JobSlaScheduler`、`JobInstanceTimeoutEnforcer`、SLA 违约 gauge、Console 完成时限统计 | **部分覆盖，必须按四种语义配置和验收**：硬超时与运行中软 SLA 已有实现；WAITING/READY 逾期由 SLA 扫描覆盖，CREATED 卡住由恢复链路覆盖但恢复失败告警缺失；结束晚只有统计口径，未发现终态后的专门告警 | 见 [`sla-and-quality.md`](../design/sla-and-quality.md)：硬超时不能代替预期耗时，未启动逾期不能代替执行中耗时，完成时间超过 deadline 需单独统计/告警 |
| Pipeline 处理 SLA / 文件到达 | `PipelineProcessingSlaViolation`、`FileArrivalSlaViolation` | 已覆盖已配置并启用的 SLA | 核查处理窗口、上游到达组和 SLA 配置 |
| 调度等待、Worker 选择、容量和反压 | `BatchSchedulerQueueOldestWaitHigh`、`WorkerSelectionNoMatch`、Worker capacity/backpressure、Kafka lag | 已覆盖运行积压与派发能力不足 | 这是“无法及时启动/消费”的信号，不替代分区结果失败 |
| Readiness、批次日与资产新鲜度 | readiness timeout/defer、asset freshness、批次日门禁相关规则 | 覆盖依赖等待、窗口超时及资产新鲜度事件；批次日最终完成率仍需结合业务日视图验收 | 对账实例清单、补跑/审批记录和最终终态 |

### 必须闭环的缺口

仓库目前不能标记为“必要告警全部完成”。实施顺序：

1. 为最终失败分区增加低成本、可解释的指标和规则；覆盖正常 REPORT、重试耗尽、部分失败及 dry-run 边界。
2. 为低频 Trigger launch 技术失败和陈旧 `CREATED` 实例恢复失败补独立告警，不以高阈值 failure-spike 替代。
3. 将 HTTP 5xx 比率按 Prometheus target 的稳定应用标签计算；并补关键基础依赖/Exporter 不可达信号，覆盖 Kafka、Valkey/Redis、MinIO 和指标采集目标。
4. 将作业监控按作业配置拆分为硬执行超时、执行中预计耗时超限、deadline 到期未启动、终态晚完成；软 SLA 配置不得与硬 timeout 混用，并为晚完成补终态事件/告警。
5. 在 Docker 与 Helm 目标环境分别做可控 firing 和端到端送达演练，验证 Prometheus、Alertmanager、Console 鉴权、通知渠道及 delivery log。规则 YAML、CI 静态检查或 Alertmanager 页面可访问都不等于送达验收。

以上三类监控必须分别验收：可用性故障注入、事件规则触发/恢复、代表性批次从创建到分区终态及 SLA 违约的业务链路。不得用其中一类的绿灯替代另外两类。

### 规则变更验收

- 指标名必须能追溯到生产代码的 meter 注册点；新增 gauge/counter 时验证状态语义、重试/终态口径、dry-run 过滤和租户标签基数。
- 规则必须同时落在 Docker canonical 文件与 Helm 副本，并运行 `bash scripts/ci/check-helm-prometheusrule-sync.sh`。
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
