# BFS 对标成熟调度方案后的边界路线图

> 日期:2026-06-29  
> 目的:把对标 Airflow / DolphinScheduler / Argo / Prefect / Dagster / Temporal 后识别出的能力,按 BFS 当前定位拆成「可以做」「降级表达」「明确不做」,并给出实施计划、验证方案和核心链路风险评估。  
> 定位基线:BFS 是**批量运行控制面 + 文件/任务交付闭环**,不是通用数据治理平台、K8s 调度器、实时流平台或通用长事务引擎。

## 1. 总结

| 类别 | 结论 | 处理方式 |
|---|---|---|
| 可以做 | 资源池公平调度、资产分区新鲜度、动态 fan-out、运维诊断 UX、受限插件适配、replay impact preview | 纳入 P0/P1 路线,按控制面能力落地 |
| 降级表达 | K8s 执行、Temporal-like saga、lineage/catalog、cost profile、calendar 能力 | 只做与批量交付闭环直接相关的最小闭环,不扩成平台 |
| 明确不做 | 自研 K8s 调度器、完整数据湖/数据治理、实时流处理、业务正确性裁判、通用 workflow/saga 引擎 | 写入边界守护,评审时直接拒绝 |

判断标准:

1. 是否在回答「何时跑、跑哪个、谁来跑、怎么切分、失败怎么办、结果是否完整、如何追溯」。
2. 是否只管理 BFS 的任务、文件、结果版本、审计和运维动作。
3. 是否避免裁定业务本身对错,避免接管基础设施调度,避免替代数据治理/数仓/流处理平台。

## 2. 可以做

### 2.1 资源池 / 公平调度产品化

范围:

- `resource_pool` / `tenant_quota` / `queue_depth` / `priority` / `max_running` / `backpressure`。
- 租户、队列、workerGroup 维度的公平性、排队、限流、饥饿保护。
- Console 展示每个池的排队、运行、拒绝、延迟和瓶颈原因。

不越界约束:

- 只做准入、排队和 worker 选择。
- 不自动拉起机器,不调 Kubernetes Pod,不做节点亲和/污点/容器编排。

实施计划:

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0-1 | 梳理现有 `ResourceQueue` / quota / worker registry,固化统一资源池模型和状态枚举 | 文档 + 单测覆盖 pool lookup / quota fallback / disabled pool |
| P0-2 | launch 前接入 admission decision: ACCEPT / DEFER / REJECT,拒绝必须带机器可读原因 | 1k storm 下无 `CREATED + NO_TASK`,拒绝有明确 failureClass |
| P0-3 | queue depth + wait age + tenant fairness 指标入 metric 和 Console query | 多租混压下可按 tenant/pool 看出排队原因 |
| P1 | priority aging / anti-starvation / pool SLA | 大租户压测时小租户 p95 wait 不失控 |

验证方案:

- 单测:quota 计算、优先级 aging、DEFER 去重、资源池禁用。
- IT:PG + Kafka 下 1k / 10k launch storm,断言终态和 queue depth 回落。
- sim:三租户混压,大租户不能饿死小租户。
- 稳定性:30-60 分钟 soak,观察 `non_terminal=0`、Kafka lag 归零、queue depth 不发散。

### 2.2 资产分区 / 新鲜度视图

范围:

- 批量产物级 metadata:`asset_code`、`bizDate`、`partition_key`、`result_version`、`freshness_status`。
- 上游 materialization event:哪个 job 在哪个批量日产出了哪份结果。
- trigger / workflow readiness 可基于 asset partition 判断「上游是否已准备好」。

不越界约束:

- 只维护 BFS 产物和外部文件到达的最小目录。
- 不做企业数据目录、字段级血缘、数据地图、主数据管理。

实施计划:

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0-1 | 设计 `data_asset` / `asset_partition` 最小模型,绑定 `result_version` | 同一 `(tenant, asset, bizDate, partition)` 只有一个 EFFECTIVE |
| P0-2 | import/export/process/report 时写 materialization event | 成功终态自动生成或刷新 partition 状态 |
| P0-3 | readiness API 支持按 asset partition 查询 | 上游未完成时 trigger defer,完成后可自动释放 |
| P1 | freshness policy: expectedBy / staleAfter / missing alert | 超 SLA 产生告警和可查原因 |

验证方案:

- 单测:partition upsert 幂等、EFFECTIVE 切换、防终态复活。
- IT:上游成功后下游 readiness 从 BLOCKED 变 READY。
- 故障注入:上游 FAILED / PARTIAL_FAILED / stale result 不得被静默消费。
- 回归:batch-day replay 后只引用最新 attempt 的 EFFECTIVE version。

### 2.3 动态 fan-out / 分片任务产品化

范围:

- 上游输出决定下游 N 个 partition/task。
- import/export/process 的分片展开、join、分片重试、失败恢复。
- Console 能看每个分片的状态、耗时、行数和失败原因。

不越界约束:

- 只做 BFS worker 的分片任务,不做通用 MapReduce / Spark 替代品。
- 不允许用户写任意分布式计算 DSL。

实施计划:

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0-1 | 固化 partition plan contract: partitionKey / shardIndex / range / expectedRows | plan 可重放,同一请求幂等 |
| P0-2 | worker claim/report 支持分片级 outputs 和 verifierFailures | 单分片失败不污染其他成功分片 |
| P0-3 | join aggregator 明确 SUCCESS / PARTIAL_FAILED / FAILED 规则 | 高并发分片下不会提前 promote 结果版本 |
| P1 | Console 分片明细与 retry failed shards | 运维可只重跑失败分片 |

验证方案:

- 单测:partition plan deterministic、join 状态机、防重复 report。
- IT:4/8/16/32 分片小规模,再跑 1000w import/export 基准。
- 故障注入:kill worker after chunk,复跑只补未完成或失败分片。
- 性能:真并行 wall time 接近最慢分片,不是所有分片耗时求和。

### 2.4 运维诊断 UX

范围:

- 队列卡住原因、DLQ 重放、outbox 积压、worker 健康、trace 关联、replay impact preview。
- 高危操作审批、审计、dry-run explain。

不越界约束:

- 只解释 BFS 状态和建议操作。
- 不替代企业日志平台、APM、SIEM、工单系统。

实施计划:

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0-1 | stuck diagnosis API: instance/task/worker/outbox/queue 五类原因 | 卡住实例不需要 SSH 查库 |
| P0-2 | replay impact preview:会重跑哪些 job、影响哪些 result_version | replay 前能看到影响范围 |
| P0-3 | DLQ/outbox runbook 操作 API 幂等化 | 重放不会造成重复成功上报 |
| P1 | Console 聚合页和告警 drill-down | 告警能一跳定位到可操作对象 |

验证方案:

- Controller 单测:权限、跨租户隔离、幂等 key。
- IT:构造 CREATED、RUNNING stale、DLQ、outbox pending,诊断原因必须准确。
- runbook rehearsal:按文档在本地 sim 故障后恢复,不手改 DB。

### 2.5 受限插件 / 适配器生态

范围:

- 围绕五类 worker 和 atomic executor 提供受控适配器:HTTP / SQL / SFTP / OSS / shell opt-in。
- SDK conformance:Java / Python / Go / JS / Rust 的最小契约对齐。

不越界约束:

- 不做无限制插件市场。
- 不允许插件绕过 claim/report/tenant/audit/timeout/idempotency。

实施计划:

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | conformance suite 覆盖 claim/report/heartbeat/cancel/idempotency | 五语言 SDK 同一套行为基线 |
| P1 | 官方 adapter whitelist + 安全策略 | 新 adapter 必须有 timeout、审计、权限、重试语义 |
| P2 | 示例模板和 starter | 租户能低成本接入,但不下放平台状态机 |

验证方案:

- SDK conformance:真实 HTTP transport 接入 fake platform。
- 安全测试:SSRF、命令注入、SQL DDL、路径逃逸。
- 兼容测试:旧 SDK 请求字段仍可兼容,新字段 non-null 受控。

## 3. 降级表达

### 3.1 K8s / 容器执行

允许表达:

- worker 镜像规范、健康检查、资源 request/limit 建议、Helm values 示例。
- worker capability / label 作为路由依据。

不允许表达:

- BFS 自己创建 Pod、扩容节点、实现亲和/污点/容忍调度。

验证方案:

- 只验证 worker 注册、下线、drain、重启后恢复。
- K8s 层验证交给部署 runbook / Helm chart,不进入 orchestrator 状态机。

### 3.2 Temporal-like saga

允许表达:

- 批量任务补偿、失败恢复、result_version promote/reject、outbox compensation。
- 单个 batch-day replay session 的审批、幂等和恢复。

不允许表达:

- 通用长事务引擎、任意业务 saga DSL、跨系统业务补偿裁判。

验证方案:

- 只测 BFS 管辖对象:job_instance、task、file_record、result_version、outbox。
- 失败恢复必须证明不丢、不重、不终态复活。

### 3.3 Lineage / Catalog

允许表达:

- 文件级、job 级、result_version 级 lineage。
- 输入文件到输出文件、job 到 asset partition 的可追溯链路。

不允许表达:

- 记录级血缘、字段级血缘、全企业数据目录、数据治理审批。

验证方案:

- forensic bundle 可重建某批次「输入→处理→输出→投递」证据链。
- 不要求逐行追踪,也不对业务含义作判断。

### 3.4 Cost profile

允许表达:

- 每个 tenant / job / worker 的耗时、行数、对象大小、DB IO 近似指标。
- 用于容量画像和调参建议。

不允许表达:

- 云账单治理、成本分摊财务系统、跨平台 FinOps。

验证方案:

- benchmark 报告记录 wall time、吞吐、CPU/IO、WAL 增量、对象大小。
- 不承诺云账单精确分摊。

### 3.5 Calendar

允许表达:

- 业务日历、节假日、半天工作日、日切、顺延、跨日依赖。

不允许表达:

- 全球日历 SaaS、外部公假日 API 自动同步、calendar fork/branch。

验证方案:

- DST、时区、节假日、misfire、catch-up 的本地/IT 覆盖。
- 外部节假日来源不进入自动信任链。

## 4. 明确不做

| 能力 | 不做原因 | 替代方式 |
|---|---|---|
| 自研 K8s 调度器 / 自动扩缩容 | 越过 worker 路由边界,运维风险高 | 交给 K8s/HPA/部署平台,BFS 只暴露 worker drain 和 health |
| 完整数据湖 / Iceberg / Delta / Trino | BFS 不是数据平台 | 输出文件和 result_version 可被外部湖仓消费 |
| 实时流处理平台 | 调度模型是批量日和批次闭环 | 准实时只做事件触发和到达通知 |
| 企业级数据治理 / 主数据 / 记录级血缘 | 业务数据治理属于数仓/治理系统 | BFS 只做文件/任务/结果级追溯 |
| 业务正确性裁判 | 平台无法判断业务金额该不该如此 | 只校验声明值 vs 实际值、输入 vs 输出连续性 |
| 通用 workflow/saga 引擎 | 会和 Temporal/业务系统重叠 | BFS workflow 只编排批量任务和文件交付 |
| 通用日志/APM/SIEM 平台 | 观测平台已有专业系统 | BFS 提供 traceId、状态、审计、关键指标 |

## 5. 综合实施节奏

### P0:上线前/近期必须收敛

| 优先级 | 事项 | 目标 |
|---|---|---|
| P0-1 | 资源池公平调度产品化 | 高压下不出现无语义拒绝、无 `CREATED + NO_TASK` |
| P0-2 | 动态 fan-out 分片闭环 | import/export/process 的分片执行和 join 语义稳定 |
| P0-3 | asset partition 最小模型 | 下游 readiness 不消费 stale / partial / old attempt |
| P0-4 | stuck diagnosis API | 运维能不手改 DB 定位和恢复 |

### P1:扩大流量前增强

| 优先级 | 事项 | 目标 |
|---|---|---|
| P1-1 | replay impact preview | 补跑前知道影响范围,降低误操作 |
| P1-2 | freshness policy + alert | 上游晚到、缺失、过期有明确告警 |
| P1-3 | SDK / adapter conformance | 自托管 worker 不发生生产行为漂移 |
| P1-4 | lineage 最小链路 | forensic 能重建输入到投递证据链 |

### P2:容量画像和长期产品化

| 优先级 | 事项 | 目标 |
|---|---|---|
| P2-1 | cost profile | 给出 tenant/job/worker 维度容量画像 |
| P2-2 | K8s 部署规范 | 部署层可水平扩 worker,但 BFS 不接管扩容 |
| P2-3 | 更多 adapter 模板 | 降低接入成本,不放宽安全边界 |

## 6. 验证分层

| 层级 | 验证内容 | 必须覆盖 |
|---|---|---|
| 单测 | 状态机、quota、partition plan、result_version gate、幂等 key | 边界条件和非法状态 |
| Mapper/Repository IT | PG 约束、唯一键、锁、SKIP LOCKED、租户隔离 | 并发写、防终态复活 |
| 服务 IT | launch T1/T2、admission、claim/report、replay、readiness | 真 PG + 真 Kafka |
| worker 场景 | import/export/process/dispatch/atomic 的正常、失败、取消、重试、checkpoint | 五类 worker 业务分支 |
| sim | 真实 API 触发,不走前台模拟,验证上下游闭环 | 批量日、misfire、storm、多租户 |
| chaos | Kafka/PG/对象存储/下游 HTTP/SFTP 故障注入 | 不丢、不重、不卡 RUNNING |
| soak | 30-120 分钟稳定性 | lag 归零、queue 不发散、无资源泄漏 |
| benchmark | 1000w 级 import/export/process 和 task storm | 吞吐、wall time、WAL、CPU/IO、失败率 |

统一验收口径:

- `non_terminal=0` 或有明确可解释的 waiting/deferred 状态。
- Kafka lag 最终归零。
- 不出现 `CREATED + NO_TASK`。
- 失败必须终态为 `FAILED / REJECTED / PARTIAL_FAILED`,不能静默卡住。
- result_version 只能由成功且完整的 attempt promote。
- 跨租户查询返回 0 行,队列公平性可解释。

## 7. 当前核心逻辑链路风险评估

核心链路:

```text
trigger
  -> trigger_outbox_event
  -> orchestrator launch T1/T2
  -> job_instance / job_partition / job_task
  -> outbox_event
  -> Kafka
  -> worker claim
  -> lease renew / heartbeat
  -> worker execute
  -> report
  -> result_version / file_record / dispatch receipt
  -> audit / metric / alert
```

### 7.1 控制面 DB-centric 瓶颈

风险:

- 当前状态主机集中在 PG。高压下瓶颈更可能在 launch/claim/report 串行段,不是 worker 执行体。
- 单机容量报告已显示控制面吞吐有天花板,盲目上 Citus 或加 worker 不一定有效。

影响:

- task storm、月底补数、多租户混压时排队延迟上升。
- 如果没有 admission 语义,正常请求可能被硬拒或留下不清晰终态。

缓解:

- P0 做资源池 admission + queue depth 指标。
- 压测必须记录 launch p95/p99、DB wait、锁等待、Kafka lag、worker idle。

### 7.2 launch T1/T2 与终态语义

风险:

- T1 创建实例,T2 分发失败时如果终态不严谨,会出现 `CREATED + NO_TASK` 或实例不可恢复。

影响:

- 前台看到实例存在但 worker 永远不执行。
- replay/补偿无法判断该不该重推。

缓解:

- launch 后必须有 dispatch success / deferred / rejected / failed 的可解释状态。
- 1k/10k storm 回归把 `CREATED + NO_TASK=0` 作为硬门槛。

### 7.3 Kafka / Outbox 积压

风险:

- Kafka 短故障时 outbox 堆积,恢复后可能形成洪峰。
- outbox GIVE_UP 如果只靠人工查表,运维响应慢。

影响:

- worker 空闲但任务未到达。
- 延迟恢复后打爆 worker 或下游。

缓解:

- outbox pending/stale/GIVE_UP 指标 + circuit breaker + replay runbook。
- chaos 测 Kafka down/up,验证 backlog 清空速度和幂等。

### 7.4 worker lease / 长任务心跳

风险:

- 长 shell/sql/process 任务如果只有 worker 级心跳,极端情况下会误判 worker 死亡或租约过期。

影响:

- 重投导致重复执行,或任务卡 RUNNING。

缓解:

- 长任务接 task-level progress heartbeat。
- kill worker、GC pause、PG transient disconnect 必须覆盖恢复验证。

### 7.5 分片 join 与结果版本 promote

风险:

- 动态 fan-out 后,单分片失败、重复 report、慢分片 late arrival 都可能误触发整体 SUCCESS。

影响:

- 下游消费不完整结果,这是结算级风险。

缓解:

- join aggregator 只能在全部 required 分片成功后 promote。
- PARTIAL_FAILED 不允许自动 EFFECTIVE。
- 分片 report 幂等 + 防终态复活单测必须作为守护。

### 7.6 readiness / freshness / stale result

风险:

- 下游按 job success 判断,但没有绑定 asset partition 和 latest attempt,会误读旧产物。

影响:

- 重跑后旧结果被下游误用。
- 上游晚到时下游提前跑。

缓解:

- P0 asset partition + freshness gate。
- readiness 查询必须返回 result_version、attempt、freshness reason。

### 7.7 多租户邻居效应

风险:

- 表级 tenant 隔离能防串租,但不能天然防资源抢占。

影响:

- 大租户 storm 影响小租户 SLA。

缓解:

- tenant quota + fair scheduling + per-tenant metrics。
- 多租混压必须验证小租户 p95 wait 和 success rate。

### 7.8 运维可恢复性

风险:

- 如果卡住原因只能靠 SSH/SQL 查,Day-2 运维不可控。

影响:

- 故障恢复依赖开发,恢复动作可能重复出账或误 promote。

缓解:

- stuck diagnosis、replay preview、DLQ/outbox 幂等重放进入 P0/P1。
- runbook rehearsal 要证明不手改 DB 也能恢复。

## 8. 评审红线

任何新增需求或 PR,如果命中以下问题必须先补 ADR 或降级:

1. 是否要求 BFS 创建/扩容/调度容器或机器。
2. 是否要求 BFS 裁定业务数据是否正确。
3. 是否引入记录级/字段级企业血缘。
4. 是否把 dry-run 变成「实跑后回滚」。
5. 是否允许 worker 或 SDK 直写平台状态主表。
6. 是否让插件绕过租户、幂等、超时、审计和 report 契约。
7. 是否把 Console 变成通用日志、APM、AI、数据治理入口。

如果答案为是,默认不做;确需做也必须先证明它仍属于批量运行控制面或文件/任务交付闭环。

## 9. 执行记录

### 2026-06-29 P0-1 第一刀:资源准入语义显式化

已做:

- `ResourceSchedulingDecision` 增加显式 `admissionAction=ACCEPT/DEFER/REJECT`。
- `DefaultResourceScheduler` 将原有 `dispatchable/failFast` 判定映射为三态准入语义:
  - `ACCEPT`:可立即派发,写 outbox。
  - `DEFER`:资源/窗口/worker 暂不可用,创建 WAITING 分区和 CREATED task,等待后续 tick。
  - `REJECT`:策略要求 fail-fast,launch 反向收敛为 `FAILED/REJECTED`。
- 普通 job dispatch 与 workflow node dispatch 共用同一 `REJECT` 识别,避免 DAG 节点绕过资源拒绝语义。
- dispatch reject 审计摘要增加机器可读 `reasonCode`,保留人可读 `reason`,便于 Console/诊断 API 后续按原因聚合。
- 修复资源池解析排序:未显式指定 `queueCode` 时,workerType 专用队列优先于 `MIXED` 回退队列,避免专用导入/导出池被高权重混合池抢走。

本地验证:

- `mvn -q -pl batch-orchestrator -DskipTests spotless:apply`
- `mvn -q -pl batch-orchestrator -am -Dtest=DefaultResourceSchedulerTest,DefaultResourceQueueManagerTest,DefaultLaunchServiceTest,DefaultWorkflowNodeDispatchServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

还未做:

- queue depth / wait age / tenant fairness 指标和 Console query。
- 1k / 10k launch storm 服务 IT 与多租户 sim 复验。
- stuck diagnosis API 对 `DEFER/REJECT` reasonCode 的聚合展示。

### 2026-06-30 P0-1 第二刀:队列积压观测闭环

已做:

- `SchedulerSnapshotResponse.QueueSnapshot` / `ConsoleSchedulerSnapshotResponse.QueueSnapshot` 增加队列积压字段:
  - `created/waiting/ready/running/retrying/queued/activePartitions`
  - `oldestWaitingSeconds`
  - `tenantWaitingSharePermille`
  - `partitionSaturationPermille`
  - `bottleneckReason`
- 队列积压统计口径与 WAITING 重派保持一致:优先读 `job_partition.input_snapshot.queueCode`,缺失再回退 `job_instance.queue_code`,避免 workflow 子任务被错误归到 workflow 自身队列。
- `bottleneckReason` 首版枚举:
  - `NONE`
  - `QUEUE_JOB_LIMIT`
  - `QUEUE_PARTITION_LIMIT`
  - `NO_ONLINE_WORKER`
  - `WAITING_DISPATCH_BACKLOG`
- 新增全局低基数 Micrometer gauge:
  - `batch.orchestrator.scheduler.queue.created.partitions`
  - `batch.orchestrator.scheduler.queue.waiting.partitions`
  - `batch.orchestrator.scheduler.queue.ready.partitions`
  - `batch.orchestrator.scheduler.queue.running.partitions`
  - `batch.orchestrator.scheduler.queue.retrying.partitions`
  - `batch.orchestrator.scheduler.queue.queued.partitions`
  - `batch.orchestrator.scheduler.queue.oldest_wait.seconds`
- 租户/队列明细不打 Prometheus tag,只通过 Console snapshot 查询,避免 tenant/queue 维度膨胀。
- Console OpenAPI 已补充新增响应字段。

本地验证:

- `TenantSchedulerSnapshotServiceTest` 覆盖队列积压、等待年龄、饱和度、无在线 worker 瓶颈原因。
- `SchedulerQueueBacklogMetricsSchedulerTest` 覆盖 gauge 采样更新。

还未做:

- 1k / 10k launch storm 服务 IT 与多租户 sim 复验。
- stuck diagnosis API 对 `DEFER/REJECT` reasonCode 与队列 bottleneck 的聚合展示。
- P1 priority aging / anti-starvation / pool SLA。

### 2026-06-30 P0-2 第一刀:partition plan contract 固化

已做:

- `SchedulePlan` 增加 `totalExpectedRows`。
- `SchedulePlan.PartitionPlan` 增加可持久化的分区计划契约字段:
  - `shardIndex`
  - `shardTotal`
  - `rangeStartInclusive`
  - `rangeEndExclusive`
  - `expectedRows`
- `DefaultSchedulePlanBuilder` 从运行参数读取 `expectedRows / totalExpectedRows / totalRowsHint / recordCount / estimatedItemCount`,并按 `shardIndex/shardTotal` 均分为半开范围。
- `WorkflowFanOutSupport.expandPartitions` 展开后复用同一个 `normalizePartitionContract()` 口径,动态 fan-out 分区也带 `shardIndex/shardTotal`。
- `DefaultPartitionLifecycleService` 将 `partitionPlanVersion=1` 与上述字段写入 `job_partition.input_snapshot`,让 worker、运维诊断和重放都能读到同一份计划。

本地验证:

- `DefaultSchedulePlanBuilderTest` 覆盖 10 行 / 3 分片的范围均分与 expectedRows。
- `WorkflowFanOutSupportTest` 覆盖 fan-out 展开后的 shardIndex/shardTotal。
- `DefaultPartitionLifecycleServiceTest` 覆盖 input_snapshot 持久化字段。

还未做:

- worker claim/report 协议显式暴露分片级 outputs 与 verifierFailures。
- join aggregator 明确 SUCCESS / PARTIAL_FAILED / FAILED 规则,并防止提前 promote result_version。
- 4/8/16/32 分片小规模 IT 与 1000w import/export 基准复验。

### 2026-06-30 P0-2 第二刀:分片 outputs 聚合与 result_version 防覆盖

已做:

- worker report 成功路径已把 `outputs` 与 `verifierFailures` 写入 `job_partition.output_summary`,不再只保留 task 的文本摘要。
- `workflow_node_run.output` 不再直接使用最后一个 report 的 `outputs`;节点终态时按当前 workflow node 的 SUCCESS 分片聚合。
- `result_version.payload_json` 不再直接使用最后一个 report 的 `outputs`;实例终态时按实例 SUCCESS 分片聚合。
- 聚合兼容旧消费者:
  - 单个成功分片保持原始 `outputs` 形状。
  - 多个成功分片包装为 `partitionedOutputs`,每项带 `partitionId/partitionNo/partitionKey/outputs`。
- 失败分片不进入成功产物集合,但多分片包装保留 `failedPartitionCount`,终态仍由 `SUCCESS / PARTIAL_FAILED / FAILED` 状态机表达。

本地验证:

- `TaskOutcomeSummaryBuilderTest` 覆盖:
  - `output_summary` 持久化 `outputs/verifierFailures`
  - 单分片输出形状兼容
  - 多分片 `partitionedOutputs` 包装
  - workflow node 分片过滤
- `DefaultTaskOutcomeServiceTest` 回归 workflow node run 并发幂等。

还未做:

- 分片失败后的 `retry failed shards` Console/API 运维入口。
- 4/8/16/32 分片服务 IT 与 1000w import/export 基准复验。

### 2026-06-30 P0-2 第三刀:claim typed 分区计划透传

已做:

- `EffectiveTaskConfig` 增加 typed 分区计划字段:
  - `partitionPlanVersion`
  - `shardIndex`
  - `shardTotal`
  - `rangeStartInclusive`
  - `rangeEndExclusive`
  - `expectedRows`
- orchestrator claim 时从 `job_partition.input_snapshot` 读取上述字段；历史空快照或坏 JSON 降级为 null，不阻断 claim。
- worker-core `PulledTask` 增加对应字段，并在 `DefaultTaskExecutionWrapper` 写入 execution context，key 与 snapshot 字段同名。
- 旧 worker/plugin 仍可继续用 `partitionNo/partitionCount/partitionKey`；新插件可优先用 typed range/shard 字段。

本地验证:

- `DefaultTaskAssignmentServiceTest` 覆盖 input_snapshot → `EffectiveTaskConfig` typed 字段。
- `TaskControllerTest` 覆盖 claim response JSON 字段。
- `TaskDispatchExecutorTest` 覆盖 claim typed 字段进入 `PulledTask`。
- `DefaultTaskExecutionWrapperTest` 覆盖 typed 字段进入 execution context。

还未做:

- 分片失败后的 `retry failed shards` Console/API 运维入口。
- 4/8/16/32 分片服务 IT 与 1000w import/export 基准复验。
