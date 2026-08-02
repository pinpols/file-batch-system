# BFS 对标优秀系统工程化能力对照表（2026-08-02）

> 本文是 [`spring-boot-engineering-patterns-plan-2026-08-02.md`](./spring-boot-engineering-patterns-plan-2026-08-02.md) 的补充:不是简单列"还能学什么",而是对照 BFS 当前已有能力,判断哪些已经吸收、哪些值得继续补、哪些不建议搬。

## 1. 系统定位与借鉴边界

BFS 的定位必须先固定,否则"学习优秀系统"很容易滑成范围膨胀。

**BFS 是批量运行控制面 + 文件/任务交付闭环。** 它的核心价值是把批量日、任务实例、分片、文件完整性、worker 执行、租约、重试、补偿、审计和多租隔离做成一条可靠闭环。

**BFS 不是这些系统:**

| 不是 | 边界说明 |
|---|---|
| 不是通用工作流引擎 | workflow/DAG 只服务批处理和文件任务交付,不追求 Temporal/Airflow 的通用编排生态 |
| 不是数据治理平台 | lineage/catalog/data quality 只服务 readiness/freshness 和交付验收,不扩成数据资产平台 |
| 不是容器编排器 | 只选择 worker/任务,不调度机器/Pod,不替代 Kubernetes |
| 不是 ETL 引擎 | import/export/process worker 可执行数据处理,但平台核心是调度、状态、交付和恢复 |
| 不是消息中间件 | Kafka/outbox 是交付手段,不把 BFS 扩成通用事件平台 |
| 不是对象存储系统 | filesystem/S3/OSS/NAS 是适配层,不自研存储内核 |
| 不是监控平台 | 指标、告警、runbook 服务 BFS 可运营,不替代 Prometheus/Grafana/Sentry |
| 不是配置中心 | feature switch registry 管 BFS 自身配置,不扩成企业级配置平台 |

因此本文所有"可学习"都遵守三条红线:

1. **只借理念和工程化样板,不搬对方核心执行引擎。**
2. **只增强 BFS 主链的可靠性、可诊断性和可运维性,不扩张产品边界。**
3. **不破坏现有承重墙:MyBatis 显式 SQL、outbox 同事务、CAS 状态机、多租隔离、worker 协议。**

### 1.1 复核:成熟组件复用与 BFS 自研边界

本节是对“哪些能力应复用成熟方案、哪些能力仍需 BFS 自己实现”的逐项核查结果。它与
[`bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md`](./bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md)
共同作为后续评审的取舍依据。

| 能力 | 成熟方案/组件承担的部分 | BFS 保留的领域部分 | 当前取舍状态 |
|---|---|---|---|
| Quartz 与自有调度路径 | Quartz 负责 cron、calendar、misfire 和 JDBC 集群调度；不自研时间轮 | readiness、bizDate、业务窗口、dedup、准入和批量日语义 | **统一使用 Quartz**；Wheel ADR-033 已撤销，禁止维护第二套完整调度器 |
| DAG / Workflow | 借鉴 Airflow、Temporal、DolphinScheduler 的表达和运维视图 | 仅编排 BFS 的 job、file、partition、审批和补偿 | 保留领域 DAG；不引入通用 workflow/saga 引擎，不做图灵完备 workflow code |
| 配置中心 | 环境级配置由 env、Helm 和部署系统管理；缓存使用 Redis | `system_parameter`、领域配置、版本、审批、租户隔离和审计 | 保留轻量 DB + Redis；明确不引入 Nacos、Apollo、Spring Cloud Config |
| 限流 / 配额 | Bucket4j、Redis、Resilience4j 承担通用令牌桶、熔断和退避 | tenant/job/queue 配额、公平性、pending cap 和业务拒绝原因 | 通用算法复用成熟库；业务准入策略由 BFS 维护 |
| 通知 / 告警 | Prometheus + Alertmanager 负责技术告警、分组和路由；Webhook 作为交付适配 | 批次通知、审批通知、租户订阅、投递审计和业务渠道 | 两层并存；`alert_routing_config` 当前不是运行时 Alertmanager 路由，动态迁移暂缓 |
| 审计 | Loki/OpenSearch/SIEM 等负责技术日志和安全分析 | 重跑、审批、取消、配置变更、租户操作和结果取证 | 业务审计必须落库；不把 BFS 扩成通用合规审计平台 |
| 观测页面 | OTel、Prometheus、Grafana、Loki、Tempo、Alertmanager 负责基础观测 | job、batch day、partition、task、readiness、replay 的业务查询和操作视图 | 基础设施复用成熟栈；业务控制面视图保留自研 |
| Checkpoint / Restart | 借鉴 Spring Batch 的 chunk、skip、retry、ExecutionContext 思路 | 文件位点、pipeline stage、幂等版本、worker report 和租户业务事务边界 | 部分吸收；不引入 Spring Batch JobRepository 替换 BFS 协议 |
| Retry | Spring Retry、Resilience4j 负责技术异常的退避、重试和熔断 | task 状态 CAS、attempt、DLQ、replay、人工重试和终态保护 | 技术重试复用库；业务重试状态机保留自研 |
| Compensation | 借鉴 Temporal Saga 的声明式步骤和幂等思想 | 文件删除、下游冲销、补偿审批、补偿 checkpoint 和审计 | 只保留 BFS 范围内的补偿节点；不做通用 Saga 引擎或跨系统 1PC |
| AI | Spring AI、provider SDK、模型服务承担模型接入 | 只读查询、建议、成本计量、权限、审计和降级 | 默认关闭、只读、不写主链；不训练模型、不做自治运维 |
| 容量画像 | Prometheus/Grafana 和数据库/Kafka/MinIO exporter 提供指标 | BFS 热表、outbox、worker、批量窗口的容量趋势和验收报告 | 只做基础容量画像；不做 FinOps、云账单分摊或业务金额成本裁定 |

逐项核查结论:

1. **已明确取舍**:调度器、DAG、配置中心、限流、观测、Checkpoint、Retry、Compensation、AI、容量画像。
2. **需要特别防止误读**:通知能力是“Alertmanager 技术告警 + BFS 业务通知”两层模型；`alert_routing_config` 已有配置表但当前不生效，不能宣传为动态路由已完成。
3. **当前文档状态**:通知与审计的统一边界已收敛到 [`notification-and-audit-boundary.md`](../architecture/notification-and-audit-boundary.md)；治理表、日志采集和 Checkpoint 仍由各自的细节文档维护，实现状态以代码、测试和路线图的最新核查为准。
4. **历史文档优先级**:[`maturity-assessment.md`](../architecture/maturity-assessment.md) 明确是 2026-04-26 历史快照，其中的 Wheel 行动项不代表当前决策；当前调度结论以 ADR-033 的 `Superseded` 状态和本节为准。

## 2. 总体结论

BFS 已经不是早期脚手架系统,很多成熟系统的核心理念已经吸收过:

- 已吸收较深:Transactional Outbox、Kafka 异步分发、DLQ、Quartz misfire、worker lease、优雅停机、RLS、多租隔离、SDK conformance。
- 部分吸收:Spring Boot 自动装配、Actuator、Kubernetes controller 式 reconcile、Spring Batch checkpoint/restart、Temporal 式执行历史、Kafka Connect 式 worker lifecycle。
- 暂不建议吸收:全量替换为 Spring Batch/Temporal/Airflow/Argo,以及为了"先进"重构成 WebFlux/AOT/native image。

当前最值得继续学习的不是更多框架,而是四条工程化主线:

1. **Spring Boot 工程化闭环**:配置、自动装配、FailureAnalyzer、Actuator、生命周期、测试切片。
2. **Kubernetes Controller 收敛模型**:desired/observed state、reconcile、finalizer、限速重试。
3. **Spring Batch 断点恢复模型**:chunk、checkpoint、restart、skip/retry 语义。
4. **Kafka Connect Worker 契约**:task lifecycle、offset/disposition、rebalance、backpressure、DLQ。

## 3. 对标总表

| 对标对象 | BFS 已学习/已有能力 | 成熟度 | 仍可学习的点 | 建议级别 |
|---|---|---:|---|---|
| Spring Boot | `AutoConfiguration.imports`、`@ConfigurationProperties`、`SmartLifecycle`、`HealthIndicator`、`ApplicationContextRunner` 已有使用 | 🟡 部分吸收 | FailureAnalyzer、配置 metadata、health group、ApplicationAvailability、条件装配报告、启动阶段耗时 | P0/P1 |
| Kubernetes Controller | trigger reconciler、stale recovery、lease 回收、状态收敛任务已有 | 🟡 部分吸收 | desired/observed state 标准化、reconcile workqueue 限速、finalizer 语义、reconcile result 分类 | P1 |
| Temporal | lease heartbeat、retry、compensation、workflow DAG、signal/approval 有类似能力 | 🟡 部分吸收 | 按实例聚合执行时间线、事件历史、可解释 replay 诊断 | P1/P2 |
| Spring Batch | worker stage、chunk、checkpoint 文档和部分实现已有 | 🟡 部分吸收 | checkpoint/restart 契约贯穿 orchestrator -> worker -> storage,失败后从游标续跑 | P1/P2 |
| Kafka Connect | Kafka consumer、DLQ、SDK transport、五语言 wire protocol/conformance 已有 | 🟡 部分吸收 | worker task lifecycle 标准化、offset/disposition 等价测试、rebalance/drain 语义 | P1 |
| Quartz | trigger 模块已使用 Quartz,有 misfire、calendar、指标、reconciler | 🟢 基本吸收 | misfire 与 readiness defer/pause-resume/批量日跨午夜的组合验证 | P1 |
| Debezium Outbox | outbox_event 同事务、event_key 幂等、发布状态机已有 | 🟢 基本吸收 | outbox lag/oldest age 告警、全局幂等检测指标、schema envelope 版本治理 | P1 |
| Prometheus/SRE | Micrometer、Prometheus、部分指标、runbook、压测报告已有 | 🟡 部分吸收 | SLO/error budget、告警分层、降级可见、DR drill 常态化 | P1 |
| PostgreSQL 工程实践 | Flyway、分区、ON CONFLICT、advisory lock、PITR 文档/演练方向已有 | 🟡 部分吸收 | prod-sized migration dry-run、锁等待预算、archive drift check、慢 SQL 基线 | P0/P1 |
| Airflow/DolphinScheduler | DAG、batch day、补数/backfill、console 视图已有 | 🟡 部分吸收 | 运维 UI 表达:Grid/Gantt/批量日视图/补数影响面 | P2 |
| Argo Workflows | workflow/DAG 概念有,但执行模型不同 | ⚪ 不建议主线吸收 | 只参考声明式 retry/GC 表达,不搬 Pod-per-step | P3 |
| Netflix Hystrix/Resilience4j | resilience4j 已接入,下游熔断/限流有基础 | 🟡 部分吸收 | 熔断事件到告警、fallback 审计、fail-open/fail-close 策略矩阵 | P1 |

图例:

- 🟢 基本吸收:理念和主要机制已经落地,后续只做观测和边界补强。
- 🟡 部分吸收:已有雏形,但缺统一契约、测试矩阵或生产可观测。
- ⚪ 不建议主线吸收:只借表达或局部思想,不引入其核心执行模型。

## 4. 分系统细化

### 4.1 Spring Boot:学工程化,不是学业务分层

| 维度 | BFS 当前状态 | 缺口 | 下一步 |
|---|---|---|---|
| 自动装配 | common/SDK 已有 `AutoConfiguration.imports` | 条件、顺序、用户覆盖点不够系统 | 按 storage/lock/observability/security/lifecycle 分组补条件测试 |
| 配置 | 大量 `@ConfigurationProperties` | yml、Helm、Compose、文档、CI 手工同步 | configuration metadata + feature switch registry |
| 启动失败 | 多处 fail-close | 诊断不够可执行 | FailureAnalyzer 输出 description/action/doc |
| 生命周期 | 已有 `SmartLifecycle` | phase 顺序缺全局台账 | `BatchLifecyclePhases` + 顺序测试 |
| Actuator | 有 health/metrics | 缺 batch domain 诊断端点 | storage/outbox/worker/switch diagnostics |
| 测试切片 | SDK 已有 `ApplicationContextRunner` | common/orchestrator auto-config 覆盖不足 | 推广 context runner 矩阵 |

裁定:方向非常对,而且第一优先级最高。它不会替换 BFS 核心模型,但能让系统更像一个成熟平台。

### 4.2 Kubernetes Controller:学收敛模型

| Controller 理念 | BFS 已有类似点 | 缺口 |
|---|---|---|
| desired state | job/workflow/trigger 定义态 | 定义态与运行态差异没有统一表示 |
| observed state | job_instance、task、worker heartbeat、outbox 状态 | 观测状态分散,缺统一 reconcile 结果 |
| reconcile loop | trigger reconciler、stale created recovery、lease timeout recovery | 重试限速、错误分类、退避策略不统一 |
| finalizer | 文件清理、归档、补偿、终态保护有类似诉求 | 缺"删除/归档前必须完成副作用"的统一模型 |
| workqueue rate limit | 当前多为 scheduler/DB poll | 高压下 reconcile 风暴需要限速和合并 |

建议:

- 不引入 K8s client 或 CRD。
- 不把任务调度改成 Pod/Node 调度。
- 把 orchestrator 内部的 stale/replay/settle/recovery 统一成 reconcile 语义。
- 每类 reconciler 输出 `NOOP / UPDATED / RETRY_LATER / ESCALATED / FAILED`。
- 给 reconcile 加指标:扫描数、更新数、失败数、oldest stale age、retry delay。

### 4.3 Temporal:学执行历史和可解释性

| Temporal 能力 | BFS 当前对应 | 判断 |
|---|---|---|
| Task Queue | Kafka + CLAIM | 已有 |
| Heartbeat | worker heartbeat/lease renew | 已有 |
| RetryPolicy | retry governance | 已有 |
| Workflow | workflow DAG + pipeline | 已有 |
| Signal | approval/signal 类能力 | 部分已有 |
| Event History | job log/outbox/audit/trace 分散 | 值得补 |
| Deterministic Replay | BFS 不是同类执行模型 | 不建议搬 |

建议只做一件事:为每个 job_instance/workflow_run 聚合一条可查询执行时间线。

时间线至少包括:

- launch / validation / batch day gate
- outbox publish / Kafka offset / claim
- worker lease renew / checkpoint / progress
- report / retry / DLQ / compensation
- terminal transition / archive / cleanup

这样能提升上线排障能力,但不需要换成 Temporal。

### 4.4 Spring Batch:学 checkpoint/restart

| Spring Batch 理念 | BFS 当前状态 | 缺口 |
|---|---|---|
| chunk | import/export/process 已有 chunk/batch 思路 | 各 worker 参数和语义不完全统一 |
| ExecutionContext | heartbeat details/checkpoint 方向已有 | 缺平台级 checkpoint schema 和恢复契约 |
| restart | 幂等重跑已有 | 真正从断点续跑还不完整 |
| skip/retry | retry policy/DLQ 已有 | skip 与业务完整性/审计的契约需补 |

建议:

- 定义统一 `CheckpointPayload`:worker type、stage、offset/range、checksum、record count、version。
- orchestrator 在重新下发任务时带上 checkpoint。
- worker 必须声明是否支持 restart,不支持时明确全量重跑。
- checkpoint 写入频率要受控,避免 report/heartbeat 写放大。
- 不把 BFS 的 job_instance/task/worker 协议替换成 Spring Batch 的 JobRepository。

这是大文件 import/export/process 最有收益的方向。

### 4.5 Kafka Connect:学 worker lifecycle 和 offset 契约

| Kafka Connect 能力 | BFS 当前状态 | 缺口 |
|---|---|---|
| Connector/Task 分层 | 五类 worker + SDK 有类似分层 | task lifecycle 状态表达可继续统一 |
| Offset commit | BFS 是 task outcome/report 语义 | 五语言 SDK disposition 一致性仍要继续压实 |
| Rebalance | BFS 不完全依赖 consumer group rebalancing | worker drain/claim/report 边界需更强契约 |
| DLQ | 已有 | DLQ replay 与幂等/终态保护要持续压测 |
| Backpressure | semaphore/limit/lease 有基础 | pre-claim 限流和报告风暴治理继续补 |

建议:

- SDK conformance 从 fixture 升级到真实 transport 场景。
- Java/Python/Go/Node/.NET 的 cancel、heartbeat、report、DLQ、未知版本行为对齐。
- 明确"什么时候提交 offset/什么时候不提交"的跨语言不可变契约。
- 不把 BFS worker 改造成 Kafka Connect connector 插件,BFS 仍保留自己的任务协议和租约模型。

### 4.6 Quartz:已学习较多,继续补组合场景

BFS trigger 模块已经使用 Quartz,并有 misfire listener、misfire pending、trigger outbox、reconciler 和指标。这里不是"还要学 Quartz",而是要把组合场景验透。

重点组合:

- 高频 cron + misfire replay。
- calendar/maintenance window + readiness defer。
- pause/resume + 终态防复活。
- bizDate 跨午夜 pin。
- trigger outbox 积压 + stale publishing 回收。

建议级别 P1,因为它直接影响批量日和上线后补跑正确性。

### 4.7 Debezium Outbox:模式基本对,补 envelope/指标

BFS 已经把状态写入和 outbox_event 同事务作为红线,这是正确方向。后续可学 Debezium Outbox 的不是 CDC 本身,而是事件 envelope 和治理方式。

建议:

- envelope version 显式化。
- event_type / aggregate_type / aggregate_id / event_key 规范化检测。
- outbox lag、oldest age、publish failure reason 做成告警。
- 对"全局幂等键有意取舍"补检测指标,避免文档有承诺但线上不可见。
- 不引入 Debezium CDC 作为主链强依赖,除非未来有明确跨库同步需求和运维能力。

### 4.8 Prometheus/SRE:从有指标到可运营

| SRE 能力 | BFS 当前状态 | 缺口 |
|---|---|---|
| 指标 | Micrometer/Prometheus 已有 | 指标分散,缺 SLO 语言 |
| 告警 | 部分已有 | 降级/fail-open/fail-close 未完整告警 |
| Runbook | 已有不少文档 | 需绑定具体告警和演练脚本 |
| DR drill | 有方向和部分脚本 | 需周期化、可重复、记录 RTO/RPO |

建议:

- 为核心链路定义 SLO:launch latency、claim latency、report latency、outbox lag、worker heartbeat freshness。
- 每个 P0 告警必须有 runbook 链接。
- 降级不是只写日志,要有 gauge/counter 和告警。
- 不把 BFS 做成监控平台;指标和告警只围绕自身 SLO。

### 4.9 PostgreSQL 工程实践:继续深挖

BFS 的命门之一是 PG:状态机、outbox、幂等键、分区、归档都在 DB 上。这里值得继续学成熟 PG 生产实践。

建议:

- prod-sized Flyway dry-run:耗时、锁、回滚策略。
- 热表索引膨胀和 autovacuum 观察。
- `ON CONFLICT` 幂等键全量对账。
- 分区表 archive 镜像 drift check。
- PITR 演练记录 RPO/RTO。
- 高频 claim/report 下的锁等待和连接池饱和基线。

### 4.10 Airflow / DolphinScheduler:只学运维视图

BFS 已有 workflow DAG、批量日、补数/重放、console。Airflow/DolphinScheduler 对 BFS 最大价值在 UI/运维表达:

- DAG grid:一屏看清每个 batch day、每个 step、每个 shard 状态。
- Gantt/timeline:看 task 等待、执行、重试、补偿耗时。
- Backfill impact view:补数前展示影响任务、预计产物、冲突窗口。
- Retry/DLQ/replay workbench:把失败恢复变成一套操作台。

不建议:

- 不搬 Airflow scheduler。
- 不引入 Python DAG 作为核心 DSL。
- 不让 DolphinScheduler/Argo 接管执行。

## 5. 当前 BFS 已经学过的能力清单

| 能力 | 来源理念 | BFS 当前表现 | 后续动作 |
|---|---|---|---|
| Transactional Outbox | Debezium/微服务事件一致性 | `outbox_event`、`trigger_outbox_event`、同事务写入红线 | 补 envelope/version/lag 告警 |
| DLQ | Kafka Connect/Kafka consumer | `dead_letter_task`、DLQ replay | 继续压测 replay 幂等 |
| Misfire | Quartz/Airflow 调度语义 | trigger misfire listener/pending/metrics | 补高频 cron 组合验证 |
| Worker lease | Temporal/Kafka Connect | heartbeat、lease renew、超时回收 | 补真 transport 极限测试 |
| DAG/workflow | Airflow/Temporal/DolphinScheduler | workflow_run/node_run、gateway、补偿 | 补运维时间线视图 |
| Checkpoint 雏形 | Spring Batch | checkpoint 文档/部分阶段能力 | 打通 restart 契约 |
| 多租隔离 | SaaS/RLS 成熟实践 | tenant_id、RLS、mapper guard | 继续真实跨租数据验证 |
| 优雅停机 | Spring Boot/K8s | drain、SmartLifecycle、shutdown 配置 | phase 台账化 |
| 配置开关治理 | Spring Boot/平台工程 | feature switch 文档方向 | registry 单一源 |
| SDK conformance | Kafka Connect/云 SDK | wire protocol、五语言 SDK | 补真实 transport 对齐 |

## 6. 不要重复建设的方向

| 方向 | 为什么不做 |
|---|---|
| 用 Temporal 替换 orchestrator | BFS 已有领域状态机、文件链路、多租和五类 worker;迁移成本远大于收益 |
| 用 Spring Batch 替换 worker | 可借 checkpoint/restart,但不应替换平台协议 |
| 用 Airflow/DolphinScheduler 接管调度 | BFS 已有 trigger + workflow + batch day;外部调度器会造成双主 |
| 用 Argo Workflows 跑每个 step | 与常驻 worker/claim 模型冲突,也触碰容器编排边界 |
| 为了先进全面 WebFlux 化 | 当前瓶颈不是 servlet 线程,而是 DB/Kafka/状态推进/背压 |
| 现在追 native image | 反射、MyBatis、多模块和 SDK 成本高,收益低于诊断和配置治理 |

## 7. 建议执行顺序

### 7.1 第一阶段:补 Spring Boot 工程化闭环（P0/P1）

1. FailureAnalyzer:生产密钥、存储、Kafka、RLS、Redis/ShedLock。
2. SmartLifecycle phase 台账:调度、relay、lease、client、DB/Kafka/Redis 停机顺序可测试。
3. Configuration metadata + feature switch registry:配置单一登记源。
4. ApplicationContextRunner:自动装配条件矩阵。

### 7.2 第二阶段:补控制面收敛能力（P1）

1. Reconciler 统一结果分类。
2. Stale/replay/settle/recovery 指标统一。
3. Reconcile 限速和退避。
4. 关键异常路径 runbook 化。

### 7.3 第三阶段:补 worker 生产契约（P1/P2）

1. Checkpoint/restart 统一契约。
2. 五语言 SDK 真实 transport conformance。
3. Cancel/drain/report/DLQ 行为对齐。
4. 大文件 import/export/process 断点恢复验证。

### 7.4 第四阶段:补运维视图（P2）

1. job_instance/workflow_run 执行时间线。
2. batch day grid/Gantt。
3. Backfill impact view。
4. DLQ/retry/replay workbench。

## 8. 一句话裁定

BFS 已经吸收了不少成熟系统的核心思想,不是从零补课。现在最该做的是把这些思想工程化闭环补齐:启动能解释、配置不漂移、生命周期可测试、状态能收敛、失败能恢复、运维能看懂。
