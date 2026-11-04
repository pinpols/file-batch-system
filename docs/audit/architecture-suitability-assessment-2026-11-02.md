# 批量调度系统架构适配性评估

日期：2026-11-02。范围：后端单仓的 Workflow 运行模型、事件可靠性、Worker 隔离、多租户、扩缩容和可观测能力。基线：后端仓库 `codex/closure-review`，HEAD `fb7ec82a05bb3c3d722afe243e2db22a656e1730`；工作区有既存未提交修改，本评估以当时工作树的静态源码与文档为证据。后文“架构概览核对”“架构图覆盖与改进评估”“架构图优化记录”各节另有基线，以节内声明为准。

## 结论与适用边界

面向多租户、文件交付与复杂工作流的批量调度，统一运行模型、数据库事实源、事务 Outbox、任务认领和专用 Worker 的组合有明确价值，架构方向合理，工程设计具备较强的完整性。它也要求较多组件、状态与恢复手段；没有目标业务规模、成本和运行证据，不能判断其为所有场景的最简方案，也不能由设计完整推断生产成熟或技术领先。

控制面定位是批量调度与交付，不扩展成通用计算平台；多模块和独立部署单元也不等同于已实现数据库和故障域完全隔离。

## 覆盖与证据

| 范围 | 静态结论 | 未覆盖 |
|---|---|---|
| 顶层模型 | 单作业与 DAG 统一为 WorkflowRun；JOB 按条件创建 JobInstance，控制节点不创建空 Worker 任务。见 [`core-model.md`](../architecture/core-model.md#31-实例层级)、`DefaultWorkflowNodeDispatchService`、`DefaultLaunchService`。 | 未运行工作流、重放、容量或性能测试；不支持的嵌套 Workflow 不视为实现。 |
| 事件与恢复 | Trigger 请求和 Outbox 同事务，relay 发送并退避；Worker 使用 Kafka 接收派发并经 HTTP claim/renew/report。见 `TriggerOutboxDomainEventPublisher`、`TriggerOutboxRelay`、`TriggerLaunchConsumer` 及[模块通信](../architecture/runtime-module-communication.md)。 | 未演练 DB/Kafka 故障、消息过期、重放或外部副作用。存在 GIVE_UP 和消费端不可恢复拒绝路径，Outbox 不等于端到端 exactly-once 或无条件消息不丢。 |
| Worker | 五类 Worker 有不同资源与信任边界；ATOMIC 特权执行器的独立 classpath 与身份隔离有安全理由，见 [ADR-029](../architecture/adr/ADR-029-dedicated-spi-worker.md)。 | 未测各 Worker 利用率与合并部署的成本收益；不能仅按空闲率合并 PROCESS/ATOMIC。 |
| 租户 | 片内 RLS 与业务库路由是不同层；默认单片，租户 placement 和分片拓扑生命周期不同，见[路由手册](../runbook/biz-tenant-routing.md)与 `RlsTenantSessionSupport`。 | 未在目标环境验证数据库角色、跨物理库隔离或租户迁移。不能凭“非敏感”放宽租户边界。 |
| 扩缩容与容量 | Helm 存在 HPA/KEDA 模板，默认配置需按环境启用；[重任务保障](../architecture/heavy-workload-guarantees.md#7-范围边界)列出 WAITING 硬上限及任务派发显式 lag gate 的限制。 | 不代表目标集群已启用、告警送达或任意峰值下积压有界；未做真实负载、缩容 drain 或目标容量签收。 |
| 观测与文档 | 配置包含 Prometheus、traces/logs 导出及运维文档；默认 OTLP 导出关闭，见 `batch-common/src/main/resources/batch-defaults.yml`。 | 未检查生产配置、采集、告警到人的全链路；文档存在不一致风险。 |

## 用户提供建议的评估

1. **合并服务**：先按作业类型采集并发、空闲时间、资源配置和故障影响；优先复用公共运行库、共享基础设施或调整副本，不为减少 Deployment 数量削弱 ATOMIC 的进程、身份与网络边界。
2. **同步替代 Kafka**：当前系统已有同步 HTTP，用于 claim/续租/上报；Trigger 同步桥已撤除。若要改变某条事件链，须明确持久化接纳、重试、幂等、顺序、背压和发送/接收故障恢复，不能将同步调用视为 Outbox 的等价替代。
3. **多租户性能**：保留授权和租户隔离，先按目标数据量做查询计划、索引、连接池和吞吐测试。动态修改 placement 不是数据迁移；跨片迁移需单独设计切换、校验与回滚。
4. **自动化运维**：基于已有 HPA/KEDA 能力验证与启用条件，而不是假设从零建设或已经生效；同时核对延迟、积压、隔离、下游容量、缩容中的租约及回报。
5. **证据和文档**：[`DefaultWorkflowNodeDispatchService`](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) 类注释仍描述旧 TASK 派发，新根路径已明确拒绝，属于可定位的说明漂移，未证明运行缺陷。应结合后续文档维护修正，不能据此声称整个文档体系已完成防漂移。

## 后续验证建议

按业务 SLO 先定义批次完成期限、吞吐、数据量、峰值和可恢复时间，再在隔离目标拓扑验证数据库/Kafka/Worker 故障、重复投递和外部副作用、跨租户隔离、容量积压及回放、扩缩容和告警送达。当前记录只评价设计和有限的源码事实；未执行编译、测试、压测、故障注入、staging 或生产验证，不构成上线或容量签收。

## 可考虑的架构优化顺序

以下为 2026-11-02 根据用户追问提出的候选，不是已批准的方案或已确认的线上缺陷。保持 WorkflowRun 根模型、数据库状态事实源、现有执行链和 ATOMIC 安全隔离；具体语义与落地范围需结合实际业务裁定。

1. **先把执行结果不确定的恢复做成明确合同。** 延续 [ADR-049](../architecture/adr/ADR-049-replay-operation-scopes.md) 的稳定业务幂等身份与未知结果恢复，不新建一套通用重试框架。逐类核对 HTTP、文件交付、SQL 等副作用如何查询、确认和人工处理；检查实际部署的 Worker REPORT Outbox 开关、持久存储、耗尽告警与处理手册。其与业务提交并非分布式原子，开关本身不解决全部窗口。验收应覆盖业务已提交但响应/REPORT 丢失、Worker 崩溃、lease 过期及人工重放，并核验下游实际副作用。
2. **明确积压容量和接纳语义。** 现有活跃并发、租户/队列 QPS 和 Worker 准入应保留；针对 WAITING 无统一硬上限、派发无显式 Kafka lag gate 的已知边界，先定义已接纳义务、暂缓、拒绝、租户公平和恢复追平语义，建立可监测容量水位，再按目标负载决定是否增加硬准入控制。不得仅按 TTL 清理已接纳业务；验证需证明多租户超载时不丢批次且恢复后能够追平。
3. **按状态所有权收敛控制面，而非先物理拆分。** Orchestrator 继续作为运行态主机，在现有模块内梳理 Workflow 编排、Job 准入、Task claim/report/lease 及投递恢复的入口、状态写入与事务边界；只对实际越界或重复职责做局部收敛。物理拆分须先有瓶颈、故障隔离或组织协作证据，并比较跨服务一致性和恢复成本；本轮未证明其是性能瓶颈。
4. **将保留期与恢复窗口联动。** 依据当前 [`outbox-architecture.md`](../architecture/outbox-architecture.md) 和容量手册，核对 Kafka retention、Outbox、去重账本、Worker 报告持久化、归档与人工重放窗口，明确顺序及依赖，不为缩表先删仍可能重投事件的身份。验收应包含长时间积压、归档后重放和备份恢复对账；具体期限由业务与目标环境决定。
5. **以现有部署与观测能力降低运维复杂度。** 明确小规模和生产拓扑各自支持的可靠性与依赖，不另建旁路调度链；基于现有 Helm/HPA/KEDA 和 SLI，按批次准点率、最久等待、Outbox age、Kafka lag、Worker 饱和及下游容量确定配置，并验证缩容 drain、租约和告警实际送达。仓库模板与目录不等于目标环境启用或 SLO 达标。

本批仅补充架构建议和验证条件，没有修改运行实现、默认值或业务契约；未执行故障注入、容量测试、部署或生产验证。

## 改动规模初评

2026-11-02 按用户追问重新静态核对当前工作树。结论是**不必重建架构**：现有 R25、本地 REPORT 丢失场景测试、准入/等待重评及 HPA/KEDA 为分阶段工作提供基础，但历史测试证据须按源码和适用范围判断，不能当作本轮或目标环境已通过。

| 方向 | 限定现有行为的最小范围 | 会使规模显著扩大的选择 |
|---|---|---|
| 结果未知与恢复 | 小到中：核对已用场景的合同/部署配置，复用既有测试，针对性补缺陷与证据 | 大：跨多语言 SDK 变更协议，或为多个外部系统新增自动查询、去重与恢复能力；依赖下游配合 |
| 积压与容量 | 小到中：核对现有指标、限流与超载行为，做定向容量验证 | 中到大：实现全局/租户/队列硬上限或 lag gate，必须先确定受理、拒绝、公平和已接纳义务保全，覆盖并发、持久化、配置及真实数据库回归 |
| Orchestrator 边界 | 小：梳理状态写入者与事务，按发现修正说明；具体已证实越界的调用链通常中等 | 大：拆进程/数据库会引入跨服务事务、迁移、幂等及恢复，不建议作为默认范围 |
| 留存与恢复窗口 | 小到中：对齐现有 Outbox、去重、Kafka、归档期限与手册 | 大：自动删除或迁移去重账本涉及租户、并发、重放窗口、回滚和真实数据库验证 |
| 运维与扩缩 | 小到中：核对现有模板、开关、告警和隔离环境演练 | 中到大：Orchestrator 动态分片、真实容量和扩缩收敛及生产签收依赖目标环境 |

低置信规划口径：一名熟悉该仓库的工程师、业务语义已明确、隔离依赖可用、复用已有测试、没有发现重大缺陷的前提下，第一批只核对配置和合同并补少量有代表性的真实依赖验证，可先按约 **5–10 人日**做排期讨论；完整 WAITING 硬限额及数据库并发回归单项可粗估 **8–20 人日**。这是范围假设而非承诺；各项测试与环境可复用，不能简单相加。外部系统配合、staging/生产窗口、排队及超载策略裁定、真实容量验证均未计入。先列具体用例与调用链后再细化，发现兼容性或数据迁移问题时需重新估算。

本节是评估，不授权实施；本轮未执行测试、发布、故障注入或压测。

## 用户提供的架构概览核对

2026-11-02 对照用户提供的模块图与概览，静态核查基线为 `codex/closure-review`、HEAD `d60868a633af68d08da3e9282ece3f3d3e4f9903`。统一 Workflow 运行模型、IMPORT/EXPORT 固定阶段和 Outbox/Kafka 派发主链与现行资料基本一致；以下应区分图示错误与部署条件，不推断当前环境状态：

- 图中 `batch-common` 指向 `console-api` 的“依赖”方向反了，实际 Console API 依赖 common；现行 [`project-structure.md`](../architecture/project-structure.md) 同图存在该说明漂移。仅登记审查结论，未修改原图。
- 业务执行由 Worker 完成；Orchestrator 负责调度状态、CLAIM/REPORT/租约及编排。Worker 另有注册、心跳、续租以及受限平台文件元数据访问；Trigger 除 launch 的异步链外还调用 readiness 内部 HTTP。见[运行时模块通信](../architecture/runtime-module-communication.md)。
- RLS 租户上下文在进程与 PostgreSQL 事务变量 `app.tenant_id` 中，非 Redis。Console 读副本路由为可选能力，只读事务、强制主库和故障回退遵循各自规则；业务分片默认关闭、单片运行。见 `RlsTenantSessionSupport`、[读副本手册](../runbook/read-replica.md)与[业务分片手册](../runbook/biz-tenant-routing.md)。
- 指标由 Prometheus 抓应用 `/actuator/prometheus`；OTel Collector 接日志与链路并转发 Loki/Tempo，Grafana 查询数据源。OTLP 默认是否启用取决于配置，见[观测拓扑](../runbook/observability-stack.md)与 `batch-defaults.yml`。
- Console SSE 使用 Redis Pub/Sub 与有界回放缓冲，游标失效需重新获取状态；AI 附件必须使用与批量业务不同的 bucket，独立桶不等同独立故障域。见 `ConsoleRealtimeProperties` 和[存储域边界](../runbook/object-storage-s3-backends.md)。
- PROCESS 除计算外还负责 staging、校验和业务数据提交；不能概括为仅计算。当前核查未运行服务、测试或验证部署配置。

## 架构图覆盖与改进评估

2026-11-02 静态核查 `codex/fix-atomic-redelivery-e2e`，HEAD `e25e2eebdbda553afb93d40bef8935f277624829`；工作树有既存修改。限定统计 `docs/architecture` 中以 Mermaid 围栏标记的内容，覆盖 21 个文件、55 个图块；只统计存在性，不代表逐图验真或渲染通过。

用户所列模块图和实例模型图均已有权威入口：[`project-structure.md`](../architecture/project-structure.md#模块结构图) 和 [`core-model.md`](../architecture/core-model.md#31-实例层级)。已有主链、Trigger 时序、观测、DAG、死信、扩缩容、四类文件 Pipeline、分区状态、Outbox、Workflow 端到端时序及实体关系图；平台/自托管 Worker、业务分片和观测拓扑也有图。因此不建议重复新建同义总图。

待处理的说明和图示问题：

1. 模块总图中的 common 到 Console 依赖方向反了；总图应标明仅为简化视角并链接模块通信，避免把省略的心跳、续租、readiness 和受限平台文件元数据访问理解为不存在。目录文本对 Orchestrator 的 EXECUTE 以及 PROCESS 的“纯业务计算”表述也需与职责表对齐。
2. [`system-flow-overview.md`](../architecture/system-flow-overview.md) 对 Trigger 重启后消息不丢及 Outbox 最差只是延迟的文字过于绝对。Relay 有 GIVE_UP 等边界，需说明已落库、消息保留、消费拒绝、告警及恢复条件；本轮不推断现场丢消息。观测图本身已画出 Prometheus 直接抓取，图前的 Collector 上报说明应补开关和环境条件。
3. 当前架构、可选能力和部署路线应在图标题或图例中区分；例如 [`compose-cd-roadmap.md`](../runbook/compose-cd-roadmap.md) 的最终发布拓扑是路线图，不能当作当前实际生产部署。系统当前部署需按目标环境单独核实。

按实际读者需要考虑补充：

- 优先在现有运行/恢复权威文档补一张**业务提交后 REPORT 丢失或租约过期**的故障时序，标明平台与业务事务、旧 invocation、已知/UNKNOWN 结果、人工确认和外部幂等；已有 Outbox 状态图与 UNKNOWN 运维决策树，不把这项称作“完全没有恢复图”。
- 若需要解释权限和隔离，可将现有[安全模型](../architecture/security-model.md)中的认证、租户上下文、RLS、对象存储与 Worker 信任边界串成一图；在限定核查范围内未发现完整单图，不推断系统缺功能。
- 仅按读者和运维需要，分别补充 WorkflowRun、NodeRun、Partition、Task 的状态图；状态枚举及迁移文字已有，画图必须核对实际转换路径，不混成一张通用状态图。

本节是文档评估，未修改原架构图、产品代码或配置，未渲染全部图，也未验证运行、目标环境或生产部署。


## 架构图优化记录

2026-11-02 按以上评估实施文档优化，基线为 `codex/fix-atomic-redelivery-e2e`、HEAD `e25e2eebdbda553afb93d40bef8935f277624829`。本次保留原有工作树修改，仅调整文档：

- 在 [模块结构](../architecture/project-structure.md)将运行主链与数据访问分图，修正依赖与模块职责；在[安全模型](../architecture/security-model.md)分别展示访问边界与执行边界；在[核心模型](../architecture/core-model.md)分别补充四层典型状态路径。采用与现有架构流程图一致的直线／折线，减少交叉和拥挤。
- 在[排障决策树](../runbook/troubleshooting-decision-tree.md)补充结果未知和 REPORT 丢失时序；校准[系统流程](../architecture/system-flow-overview.md)的 Outbox、观测和扩缩容条件；在 [Compose 部署基线](../runbook/docker-deployment.md)补充本地配置视图，并标明[发布路线](../runbook/compose-cd-roadmap.md)是目标图。
- 源码复核发现受限的 `JobTask FAILED → READY` 重试重置实现，与“终态不直接回非终态”的模型原则存在需单独裁定的口径差异；图中不将其作为通用迁移，也不在本文认定为运行缺陷或擅自更改实现。

文档图示经 Mermaid 语法检查，并对模块、安全、部署、恢复及状态示例进行本地浏览器渲染检查；该证据不能替代运行时、租户隔离、故障注入或生产部署验证。本次未修改产品代码、配置，也未执行应用测试。

## 后续承接（2026-11-04 补记）

优化候选 3 由 [2026-11-03 架构设计审查](architecture-design-review-2026-11-03.md) 承接并实施 ARCH-02/03、CONV-01..04 及 ARCH-01 的 Worker 范围；其余候选和 `FAILED → READY` 口径裁定已登记到 [`todo-master`](../plans/todo-master.md)（ASSESS-20261102-*）。本文结论保持当时判断，当前状态以 `todo-master` 和 [2026-11-04 复核](architecture-suitability-assessment-review-2026-11-04.md) 为准。
