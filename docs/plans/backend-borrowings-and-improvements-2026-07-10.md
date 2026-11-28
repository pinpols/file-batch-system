# 后端借鉴与改进规划（2026-07）

> 文件日期沿革：原文件为 `backend-borrowings-and-improvements-2026-07.md`；完整日期依据Git 首次提交日期；H2/月份为原规划周期，保留在主题或正文。本次仅统一命名，不表示重新评估或验收。

> 本文回答两个问题:**该向哪些成熟系统借鉴什么理念**,以及**fbs 后端自身的演进方向**。
> 原则贯穿全文:借鉴理念,不搬运框架;守 [`bfs-open-source-scheduler-boundary-roadmap`](bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md) 的范围边界;把已有深度打磨到无懈可击,优先于往功能广度铺开。

## 0. 定位前提

fbs 是**批量运行控制面 + 文件/任务交付闭环**,不是通用工作流引擎、不是数据治理平台、不是容器编排器。经两轮深度审计,它已经具备:

- 编排状态机(CAS 纪律 + version 乐观锁)、outbox→Kafka→CLAIM→EXECUTE→REPORT 主链;
- worker 心跳 + 租约续期 + 超时兜底;
- Workflow DAG(GATEWAY + 补偿 + 审批 + 信号)、Pipeline 固定 stages;
- 可配置重试(RetryPolicyType NONE/FIXED/EXPONENTIAL + jitter);
- 多租户隔离(RLS + 租户路由)、五语言 SDK 的 wire 契约;
- 月分区、advisory-lock 串行化、批量 SQL、resilience4j 熔断、bucket4j 限流;
- console AI 助手(已接 Spring AI + RAG + 只读工具,默认关闭)——AI 方向见 [`ai-integration-plan-2026-07`](ai-integration-plan-2026-07-10.md),本文只谈后端架构。

**所以「借鉴」的重点不是补功能,而是补理念与打磨。** 下面每一条都先标注 fbs 现状,再说借什么。

## 0.1 现状校准（2026-08-02）

本文最初形成于 2026-07，以下结论以当前 `main`（合并 PR #866 后）代码为准。不能继续把已经落地的能力写成“待建设”，也不能把有测试资产写成“生产演练已完成”。

| 方向 | 当前真实状态 | 仍需补齐 |
|---|---|---|
| Spring Boot 工程化 | ✅ 已补启动失败诊断、配置边界校验、自动装配条件测试、生命周期 phase、readiness、`batchruntime` 脱敏诊断和 feature-switch CI 校验 | 目标环境 Helm/滚动发布/强杀恢复证据 |
| Checkpoint / Resume | ✅ 平台位点存储已接入 Import `LOAD` 与 Export `GENERATE`，有 chunk/page 推进、完成标记、崩溃续跑和补偿清位点测试 | 不是所有 worker 都适合复用；需继续验证 process/dispatch/atomic 的语义边界与生产同构演练 |
| Pipeline 进度 | ✅ `pipeline_progress`、worker progress sink、orchestrator cache、Console 查询和 SSE dirty event 已形成链路 | 指标告警与前端展示体验仍需按上线场景验收 |
| 执行时间线 | ✅ `trace-snapshot.timeline` 已把 execution log、outbox、审计、实例和 pipeline 统一成只读时间序列 | 只做诊断聚合，不引入 Temporal Event History 实现 |
| 容量与背压 | ⚠️ 已有 admission、租约、限流和压测证据，控制面 launch/report/claim 仍是主要杠杆 | 继续用高压数据决定是否优化单线程消费、Redis 慢故障短路和告警阈值 |
| DTO / 契约收口 | ✅ SDK wire 契约和大量 Console response DTO 已治理 | 余下无类型 Map 只能按端点分批收口，并同步 OpenAPI/前端 |

本节的“已具备”表示代码与测试已存在；“仍需补齐”表示上线证据或明确范围内的后续工程，不等同于当前存在已确认的 P0 缺陷。

---

## 1. 借鉴对象与「借什么理念」

### 1.1 Temporal —— 借「可靠执行」的理念,不搬引擎

| 能力 | fbs 现状 | 借鉴判断 |
|---|---|---|
| Task Queue / Worker 集群 | ✅ Kafka + CLAIM | 已等价,不动 |
| 心跳 Heartbeat | ✅ 已有(本轮刚优化成 RETURNING) | 已有 |
| 可配置重试 RetryPolicy | ✅ RetryGovernanceService 已可配 | 已有,非「待升级」 |
| DAG / Workflow | ✅ workflow_* 表 + GATEWAY + 补偿 + 审批 | 已有较完整 |
| Saga 补偿 | ✅ COMPENSATING 状态 + 补偿链 | 已有 |
| Signal / 人工干预 | ⚠️ 部分(workflow 信号 + 审批) | 有雏形 |
| **Event History(确定性重放)** | ❌ 有执行日志/审计,但非可重放的事件历史 | **借理念**,见下 |
| Continue-As-New / Workflow Query | ❌ | 对批处理 YAGNI,不做 |

**结论:整体搬 Temporal 是烧钱买已拥有的东西**——早期换省钱,系统成熟后换等于把主链变成薄胶水,还要重建多租户/文件领域/五语言 SDK 的所有耦合。真正值得吸收的只有一点:

- **Event History 的理念(不是它的实现)**:Temporal 的价值在「执行过程是一等公民、可回放、可审计」。fbs 已有 job_execution_log + outbox + OTel trace,但它们分散、不构成「一条任务实例的完整可回放时间线」。2026-08-02 已把关键领域聚合为 `GET /api/console/queries/trace-snapshot` 的 `timeline` 字段；这只提供运维诊断视图，不伪装成确定性重放引擎。

### 1.2 Spring Batch —— 借「Chunk / Checkpoint / Restart」

| 理念 | fbs 现状 | 借鉴判断 |
|---|---|---|
| Chunk 处理 | ✅ worker pipeline 分 stage 处理 | 已有 |
| **Checkpoint / 断点续跑** | ✅ ADR-038 平台位点已接入 Import LOAD / Export GENERATE,按 chunk/page 持久化并支持崩溃续跑 | **继续借理念**,但不强行覆盖不满足幂等条件的 worker |
| Skip / Retry 策略 | ✅ 失败分类 + 重试治理 | 已有 |

**这是最值得实打实借鉴的一条。** Import LOAD 与 Export GENERATE 已完成“持久位点 → 重派下发 → worker 续跑”的链路，带幂等能力前置校验、崩溃恢复 IT 和 Prometheus 证据口径。PROCESS 只对有明确 stage 游标的实现开放，DISPATCH/ATOMIC 默认整任务重派，避免把远端副作用或原子操作错误地套成通用 checkpoint。Spring Batch 的 `ItemStream`/`ExecutionContext` 仍作为契约设计参考。

### 1.3 Apache DolphinScheduler / Airflow —— 借「可视化 + 依赖」,大多已有

- **DAG 依赖 / 可视化编排**:fbs 已有 workflow DAG + 前端编排器。DolphinScheduler/Airflow 的 DAG 模型 fbs 已用自己的方式实现,**不需要再借**。
- **可视化运维大盘**:Airflow 的 Grid/Gantt 视图理念可参考——把 job_instance 的分区 fan-out、stage 进度、重试次数做成一眼可读的密度视图。这和 1.1 的执行时间线是同一方向的前端表达。
- **不借**:它们的通用调度器、任务依赖 DSL、插件生态——fbs 有自己的 trigger/DAG 模型,引入会造成双轨。

### 1.4 Argo Workflows —— 基本不借

云原生容器编排、每 step 起 Pod——与 fbs 的 worker 常驻 + CLAIM 模型正交,且触碰 ADR-027 明确 reject 的「自研 K8s 调度」边界。**唯一可看**:Argo 的 `retryStrategy`/`podGC` 的声明式表达法,作为 RetryPolicy 配置 DSL 的表达参考,仅此而已。

---

## 2. fbs 后端自身的改进方向

以下不是「补外部框架的功能」,是「把已有能力做到生产级扎实」——按收益/投入排序。多数已在两轮审计中定位,此处系统化。

### 2.1 可观测性:从「有指标」到「降级可见」(P1,已落地并持续收口)

审计核心结论曾指出:**保护基础设施的行为逻辑测得扎实,但「降级发生了/保护旁路了」这一层系统性缺失**。目前已补 outbox 熔断 OPEN gauge、限流拒绝/fail-open counter、apikey 缓存命中率、advisory lock 争用 Timer，以及启动自检的 ApplicationStartup/Micrometer 观测、trigger/worker readiness 和脱敏运行态诊断端点。**继续方向**:

- 把散落的指标收敛成「控制面健康仪表盘」的语义(而非一堆孤立 metric);本轮补齐 worker 续租熔断当前状态、消费背压 pause/resume 事件,并保留低基数标签约束;
- 关键降级(outbox 集群熔断、Redis fail-open、worker lease 熔断、消费许可耗尽)已接告警规则,而非只在日志;Compose 与 Helm 规则同步;
- 执行时间线(见 1.1)作为诊断的一等入口。

本轮代码落点:

- `WorkerTaskLeaseRenewer` 暴露 `batch.worker.lease.circuit.open` 当前状态 gauge,与历史发生次数 counter 分离,避免把“曾经打开”误判成“当前仍不可达”;
- `AbstractTaskConsumer` 暴露 `batch.worker.consumer.pause.total` / `resume.total`,配合 `batch.worker.semaphore.available` 识别真实背压;
- `BatchOutboxCircuitBreaker` 已有 `batch.outbox.circuit.open` / `failopen.total`,本轮在 Compose/Helm 规则中补齐当前 OPEN 与 Redis fail-open 告警;
- 对 Redis、Kafka rebalance、PG failover 运行手册的告警名称与实际指标做了校准,没有为缺少可靠指标的判断硬造规则。

### 2.2 Checkpoint / 断点续跑(主路径已落地，按 worker 语义设边界)

见 1.2。Import LOAD 与 Export GENERATE 已完成平台位点接入，并有数据库位点、chunk/page 推进、崩溃续跑和失败补偿测试。这是「上万 fan-out」方向的真实前置。

边界必须保持清楚：checkpoint 不是所有 worker 的通用开关。只有处理游标可稳定编码、业务写入可幂等重放、位点与业务写入的一致性已验证时才允许打开；process/dispatch/atomic 需要分别证明语义，不能因已有公共接口就默认复用。多分区任务沿用 `job_partition` 级重派，不共享单分区位点键。

### 2.3 状态机心脏的可维护性(P2,增量)

`DefaultTaskOutcomeService`(1046 行、38 方法、四类职责)是并发/状态机 bug 高发区(两轮审计的 report O(N²)、advisory lock、B2 批停摆都在它附近)。**不必专项拆**,但每次动这块时顺手拆出 DAG 推进 / 分区推进 / 计数聚合,让单次改动 blast radius 变小、可单测。

### 2.4 容量与背压的确定性(P1→P2)

- bucket4j timeout 已从 2s 降 500ms 缓解线程饥饿；console Redis 限流已补连续失败短路窗口、冷却期单探测恢复和低基数指标，故障时仍 fail-open 但不重复阻塞请求线程;
- 批量 SQL 已加 chunk 护栏(防 PG 65535 参数上限),为放开 maxPartitionCount=256 上限铺路;
- launch 消费单线程仍是已知控制面瓶颈——若要提吞吐,这里是杠杆(非 Citus)。

### 2.5 契约与类型安全的收口(主路径已完成，动态边界保留)

- trace-snapshot 的 `timeline` 已同步后端 OpenAPI、前端 `api.generated.ts` 和 `observabilityQueries.ts`，前端不再为该响应维护假性的 `Record<string, unknown>` 结构;
- console 剩余 `Map` 仅保留在动态配置、透传 orchestrator、Excel/JSON payload 等字段边界；稳定领域响应已使用 record/DTO。后续新增稳定字段必须先建 schema，不再扩大 Map 面;
- SDK wire 契约已五语言对齐,保持防漂移契约测试。

---

## 3. 有意不做的边界(范围纪律)

再次明确,防止「借鉴」滑向「扩张」:

- ❌ 整体搬运 Temporal / 任何通用 workflow 引擎;
- ❌ 通用 lineage / catalog / 数据治理平台(血缘只服务 readiness/freshness);
- ❌ 自研 K8s 调度 / 容器编排(ADR-027:挑 worker √,挑机器 ✕);
- ❌ 复杂成本核算、business-domain quota 预置扩张;
- ❌ 为「代表作」冲代码行数——15–20 万行、每层正确、经得起威胁模型审计,胜过百万行功能堆砌。

---

## 4. 一句话方向

**借外部系统的理念(可靠执行、断点续跑、执行可观测),打磨 fbs 自身的深度(状态机纪律、多租户、幂等、容量),守住范围边界。** 系统的护城河在深处,不在功能清单的长度。

---

## 附:工作量估算（单人粗估,人天;标注不确定项）

### P1 收口（2026-08-02 复核状态）

| 项 | 人天 | 说明 |
|---|---|---|
| IM/SMS sender SSRF 真根治 | ✅ | sender 已在建连前走 `DnsResolveGuard`；SDK 固定 endpoint 不接受用户 URL |
| resolveTenant web 路径 fail-close | ✅ | `ConsoleTenantGuard`/`requireTenant` 已覆盖 web 路径并有回归测试 |
| Redis 慢故障短路熔断 | ✅ | 2026-08-02 补短路、单探测恢复、指标和单测 |
| arch guard 租户谓词检测 | ✅ | Mapper XML 租户守卫与白名单测试已存在；运行时 guard 仍是最后防线 |
| advisory lock 死锁真 IT | ✅ | 并发与 outcome/reclaim 死锁 IT 已存在 |
| SQL 校验器配置源统一 | ✅ | `SelectSqlAstValidator` 是传感器和数据质量执行器共用入口 |
| DispatchChannel 熔断指标 | ✅ | 状态 gauge、事件计数和测试已落地 |
| **P1 小计** | **已收口** | 只剩 staging/生产证据复验，不再重复立项 |

### P2 架构演进

| 项 | 人天 | 说明 |
|---|---|---|
| **Checkpoint / 断点续跑** | ✅ 主链 | Import/Export 已落地；process/dispatch/atomic 按语义保留边界，生产同构演练走 howto 证据 |
| console Map 响应体收敛 | ✅ 主路径 | 稳定领域响应已 DTO 化；动态配置和透传 payload 保留 Map 边界 |
| alert_routing_config 接通 | ✅ | CRUD、租户配置包、Console 查询和通知路由链路已接通 |
| god class 拆分 | 持续治理 | 不专项大拆，下次状态机变更按职责抽取并配并发测试 |
| **AM 完整迁移** | **15–20** | 独立专项;**需真实环境影子期,不能无人值守** |

**判断**:文档2中可由本仓代码直接完成的 P1/P2 主路径已收口；剩余是生产证据、持续拆分和 AM 独立迁移，不再为了“看起来全部完成”跨越系统边界。

## 5. 2026-08-02 下一步执行顺序

文档 1 的 Spring Boot 工程化改造已合并；本文后续不再重复安排相同的启动诊断、phase、readiness 和 registry 工作。剩余工作按上线收益排序：

1. **已完成：降级可见性闭环代码**。outbox/Redis/限流/lease/背压指标、告警规则、运行手册和控制面时间线已具备；上线前仍需按环境阈值复核。
2. **已完成：checkpoint 主链和适配性裁定**。生产同构 staging 的崩溃、重派、业务幂等和位点证据仍是发布验收，不再重复改公共契约。
3. **已完成：控制面背压观测基础**。launch lag、claim/report 锁等待、outbox 积压、Redis 慢请求和连接池占用作为后续容量决策证据，不凭感觉扩大线程池。
4. **已完成：执行时间线读模型**。复用 execution log/outbox/audit/trace，保留租户隔离和只读聚合边界，不引入通用 workflow engine。
5. **已完成：稳定 Console response DTO 主路径**。新增稳定字段同步 OpenAPI、生成 TS 类型和前端调用方；动态 Map 保留在明确边界。
6. **持续：真实故障演练**。PG failover、Kafka 短不可用、Redis/ShedLock 故障、全 worker 组崩溃、DLQ replay、PITR 和滚动发布回滚属于环境证据，不由本 PR 虚报“代码已验证”。

## 6. 通用组件复用候选（2026-11-22）

基线为 `origin/main` 的 `37a817471`（#1455）。本轮静态核对 Trigger、Orchestrator、Console API、Worker 和 Java SDK 的依赖及相关生产调用。以下是可行性评估，实施、性能收益和目标环境效果均待验证。

| 顺序 | 现有实现与候选 | 替换前的验收条件 |
|---|---|---|
| 1 · 复用已有 Caffeine | [CronExpressionAdapter](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/support/CronExpressionAdapter.java) 用无容量上限的 Map 缓存 Quartz 表达式和时区组合，`evict` 在本基线未找到生产调用。[Trigger POM](../../batch-trigger/pom.xml) 已依赖 Caffeine，可由库负责容量淘汰。[Caffeine 缓存淘汰](https://github.com/ben-manes/caffeine/wiki/Eviction) | 缓存满后旧表达式可重算，同一表达式跨时区并发结果独立。校验夏令时、下一次触发和业务日推导与基线一致。 |
| 2 · 复用已有 Spring Framework | [HttpTaskExecutor](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java) 手写尝试次数和指数退避，[HttpTaskExecutionClient](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java) 另有 claim、renew 和 report 重试。先评估仅将 Atomic HTTP 执行循环交给 Spring 核心 `RetryTemplate`，由应用确定方法和响应的重试资格。[Spring 重试参考](https://docs.spring.io/spring-framework/reference/core/resilience.html) | 校验首次调用计数、已被显式接受的 5xx、可重试 I/O、非幂等请求只尝试一次及其结果不明分类、线程中断和指标。后续独立评估内部传输路径的各自规则。 |
| 3 · 对照验证后决定是否引入 Commons CSV | [导入](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/DelimitedFormatParser.java) 已由 uniVocity 解析。[导出](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java) 手写引号、空值和转义处理。优先对照标准 CSV 模板的行编码，现有自定义分隔符及转义策略按兼容证据决定适用范围。[Apache Commons CSV 格式选项](https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.Builder.html) | 对照历史模板逐字节比对，覆盖空值、空白、分隔符、引号、CR/LF、反斜线、编码、表头与尾记录。校验恢复追加后文件及控制总数一致。 |
| 4 · 先收敛图算法，再衡量 JGraphT | [在线定义校验](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidator.java)、[Excel 导入](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/WorkflowGraphTopologyValidator.java)、[定义诊断](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/WorkflowDefinitionDagInspector.java)及[编排校验](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidator.java)各有环检测或遍历。先梳理共同输入和输出，可共享纯图算法。只有图建模和错误转换后仍能减少维护量时，再评估 JGraphT。[环检测](https://jgrapht.org/javadoc/org.jgrapht.core/org/jgrapht/alg/cycle/CycleDetector.html) · [拓扑排序](https://jgrapht.org/javadoc/org.jgrapht.core/org/jgrapht/traverse/TopologicalOrderIterator.html) | 对照自环、断图、重复边、环内节点定位和错误顺序。条件分支、Join、补偿规则、Excel 行级报错和编排执行仍由原入口负责。 |

- **已有组件。** Quartz 计算 Cron，ShedLock 负责周期任务的执行锁，Bucket4j 负责令牌桶，Caffeine 负责已有的本地缓存，Resilience4j 负责熔断，POI 负责 Excel，JSch 负责 SFTP，JSqlParser 负责 SQL 语法树解析。自研的 PostgreSQL 与 SDK MySQL 语句切分涉及方言、执行文本及失败定位，现有 AST 校验用法不足以证明可替换。
- **告警。** Alertmanager 分组、去重、路由和静默已有发射、Webhook 与静默桥接代码。按[现有迁移方案](alertmanager-migration-plan-2026-07-12.md) §7.1 验证通知送达、投递记录和回滚，再决定旧升级通知链路的退役范围。业务告警状态、租户和审计继续在平台内管理。[Alertmanager 能力](https://prometheus.io/docs/alerting/latest/alertmanager/)
- **批处理主链。** 继续由平台维护业务日、租户范围、状态机与 CAS、租约、持久化重试、断点和补偿重放。整体引入工作流或批处理引擎会牵动持久化、API/SDK 和文件交付语义。当前候选以复用进程内基础组件为主。


<a id="backend-oss-reuse-plan"></a>

## 7. 开源组件复用实施方案（2026-11-22）

目标是减少自维护的基础机制和重复算法。验收以同一输入下的调度结果、副作用次数、交付文件和校验结果为准。基线沿用 §6。方案已登记，代码和业务验收待执行。

| 批次 | 工作项 | 交付条件 | 状态 |
|---|---|---|---|
| 1A | Trigger Cron 缓存复用 Caffeine | 缓存容量有界，淘汰前后的调度结果一致 | 已实施（2026-11-22） |
| 1B | Atomic HTTP 验证并复用 Spring 的程序化重试 | HTTP 实际请求次数、终态分类和运行指标与基线一致，重试代码有实质减少 | 关闭：不采纳（2026-11-22） |
| 2A | 对照 Commons CSV 与现有导出 | 标准模板逐字节兼容，且能减少编码逻辑。否则保留现有编码并记录差异 | 关闭：不做（2026-11-22） |
| 2B | Workflow 纯图算法复用 | 四个入口的输入、结果、错误定位和顺序经对照。先复用现有图工具，按收益决定是否采用 JGraphT | 暂缓（2026-11-22） |
| 独立专项 | Alertmanager 通知迁移验收 | 按既有方案验证从告警落库到实际收件、静默和回滚 | 本地联验完成，目标环境送达待验收 |

批次 1A、1B 可独立审查与回退。批次 2A 与 2B 各自做对照，分别决定是否进入实施。Alertmanager 验收按接收渠道和部署条件安排。

### 7.1 Trigger Cron 缓存

1. 在 `CronExpressionAdapter` 将本地 Map 换为 Caffeine 的容量淘汰缓存。容量先按活跃表达式与时区组合数量估算，沿用模块中有界缓存的写法，本批用内部常量即可。缓存仅保存解析结果，记录被淘汰后按同一规则重算。
2. 保留同一表达式跨时区隔离及构建时设置时区的做法。用生产调用点核对原主动失效方法的用途。当前仅见测试调用，若移除该方法，用容量与重新计算的行为用例替代对实现细节的断言。
3. 先用小容量测试覆盖超额淘汰和查询重算，再运行 `CronExpressionAdapterTest` 与 `DefaultLaunchAdapterServiceTest`，覆盖 Quartz 时间序列、夏令时切换、跨时区并发、非法表达式和业务日边界。记录容量依据与回归结果。若触发时间或业务日结果变化，撤回本批独立变更。

> **2026-11-22 实施**：缓存换为 Caffeine 容量淘汰，`CACHED_EXPRESSION_LIMIT = 1024`，包级构造器允许测试注入更小容量；删除生产无调用点的 `evict`。新增淘汰后重算、容量上限、跨时区独立三个用例。命令与结果见[联验记录](../verifications/backend-oss-reuse-acceptance-2026-11-22.md)。

### 7.2 Atomic HTTP 重试

1. 用现有 `HttpTaskExecutorTest` 锁定实际下游请求次数、成功返回、异常后的任务状态及输出字段。补齐用例时复用本地 HTTP fixture，覆盖 `maxRetries=0`、重试打满、I/O 恢复、退避被中断、显式接受的 5xx、预期状态不匹配和副作用已发生但响应丢失。
2. 试用 Spring Framework 7.0.10 的 `org.springframework.core.retry.RetryTemplate`。把现有配置映射为首次调用后最多重试 `maxRetries` 次，退避沿用现有起点与倍数。只有明确可重试的分支进入重试，终止时还原最后一次响应或原异常的业务分类。保留 OkHttp 单次传输、结果输出、耗时、重试日志和实际请求次数的现有语义。[7.0.10 重试配置](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/core/retry/RetryPolicy.Builder.html)
3. 对照运行 `HttpTaskExecutorTest`、`HttpTaskExecutorIpBlockTest` 和 `HttpTaskExecutorDryRunTest`。非幂等请求实测只发送一次，符合现行规则的失败继续落为 `EXTERNAL_OUTCOME_UNKNOWN`，中断后停止发送。若异常转换、响应恢复和监听器比现有实现更复杂，保留当前实现与新增行为证据。内部 claim、renew、report 各自带传输和回退语义，后续遇到明确维护痛点再单独评估。

> **2026-11-22 关闭：不采纳。** 本机 `spring-core 7.0.10` 的 `RetryPolicy` 只有 `shouldRetry(Throwable)` 和 `predicate(Predicate<Throwable>)`，无法按 `TaskResult.statusCode` 判定重试；`RetryTemplate` 在失败时抛 `RetryException`，中断时恢复 interrupt 后同样抛出。改用它需自造可重试信号异常、在终态还原最后一次响应，并外置记录 `attempts`/`durationMillis`，净减约 20 行而失败模式增加。现有 `HttpTaskExecutorTest` 47 个用例（含 10 个重试语义）保留。

### 7.3 CSV 导出兼容性对照

1. 用现有格式与脱敏历史模板生成基准文件。首轮覆盖逗号或制表符、常规双引号和换行，以及当前模板可配置的空字段、纯空白、反斜线、表头和尾记录。记录标准模板与扩展格式的各自配置组合。
2. 对照「保留现有编码」与「仅用 Commons CSV 编码标准记录」两种实现。复用现有文件生命周期与续跑逻辑，保持字节偏移、字符集和 checkpoint 的写入顺序。验证一次全写与从已有位点恢复追加得到同一文件。
3. 以文件字节、业务记录数和控制总数作为退出条件，复用 `GenerateStepCheckpointTest` 的续跑夹具。只有稳定存在兼容范围且能减少维护量时才引入依赖，并按[开源依赖治理](../standards/open-source-governance.md#依赖更新批次)更新许可证与 SBOM 证据。差异模板继续走原编码。若兼容适配器使实现更复杂，保留当前编码。

> **2026-11-22 关闭：不做。** 不引入 Commons CSV，字节级夹具不做。`EscapePolicy.BACKSLASH` 把 `\r`/`\n` 写成字面量、`NONE` 完全不转义，Commons CSV 无等价配置，替换只能覆盖标准组合并形成双路径；仓库也没有导出历史模板可作逐字节基准。现有 `GenerateStepTest` 的 `contains` 断言保留。核对时发现 `csv()` 的 `startsWith(" ")`/`endsWith(" ")` 在 `textValue()` 先 trim 后不可达，属既有死条件，本批未改动。

### 7.4 Workflow 图算法复用

1. 盘点 §6 四个入口的图结构及错误合同。[WorkflowGraphs](../../batch-common/src/main/java/io/github/pinpols/batch/common/validation/WorkflowGraphs.java) 已供 Excel 导入和 Orchestrator 计算可达性。优先复用该工具，按重复程度评估共享环检测。业务入口负责组装图、将结果映射为现有错误和执行合法性规则。
2. 用同一批图对照各入口原有输出，覆盖自环、多个环、重复边、不可达节点、并列节点顺序、错误节点或行号。复用 `WorkflowGraphsTest`、`WorkflowDagValidatorTest`、`ConfigPackageExcelValidatorWorkflowTest` 和 `WorkflowGraphValidatorTest`。
3. 只有现有工具难以自然覆盖多个已存在的算法需求时，才用同一批用例比较 JGraphT。选型同时比较图转换、错误转换、依赖和重复遍历的维护量。若库的错误定位或遍历顺序与现有合同有差异，保留原行为，优先落地能独立验证的共享部分。

> **2026-11-22 暂缓。** 触发条件：出现第五个图输入入口、某次环检测缺陷需要同时改四处、或要求统一错误码。届时只抽返回环集合/环边的纯算法，各入口继续负责错误映射；JGraphT 仍不引入（`CycleDetector` 只返回布尔，定位能力弱于现状）。

### 7.5 Alertmanager 迁移与收口

实施和状态继续由[Alertmanager 迁移方案](alertmanager-migration-plan-2026-07-12.md)维护。联验记录覆盖业务告警落库、路由和分组去重、指定收件端实际收到、投递日志、租户隔离、静默及开关回滚。旧升级通知链路的退役以这些证据为依据。

### 7.6 改造量评估（2026-11-22）

基于同一基线 `37a817471` 的只读核对，用于排期与决定“实施/保留现状”。人天为单人粗估，含代码、单测、本地模块回归和文档回填，不含评审排队、CI 等待和目标环境窗口；不改变 §7 各行状态，也不构成本身实施授权。

| 批次 | 代码落点与量级 | 测试落点 | 依赖与治理 | 人天 | 预判 |
|---|---|---|---|---|---|
| 1A Cron 缓存 | [CronExpressionAdapter](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/support/CronExpressionAdapter.java) 77 行：第 33 行改为 Caffeine 有界缓存，74–76 行 `evict` 生产无调用点可删；为淘汰用例加包级容量构造。生产调用仅 `DefaultLaunchAdapterService:150` | `CronExpressionAdapterTest` 9 个用例已覆盖 DST、跨年、与 Quartz 序列一致；新增 3–4 个淘汰后重算/跨时区并发用例；回归 `DefaultLaunchAdapterServiceTest` | 无新增依赖（`batch-trigger/pom.xml` 已有 Caffeine） | 0.5–1 | 实施。唯一确定减少自维护机制且 blast radius 最小的批次 |
| 1B Atomic HTTP 重试 | [HttpTaskExecutor.runWithRetry](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java) 520–598 行约 80 行，改为 `RetryTemplate` + 结果持有/信号异常，净减约 20 行 | `HttpTaskExecutorTest` 47 个用例中 10 个已覆盖 5xx、重试打满、非幂等、中断和 `attempts`；需新增 4–6 个信号异常往返、`RetryException` 解包和响应还原用例 | 无新增依赖（Spring Boot 4.1.2 已带 spring-core 7.0.10） | 1–1.5（验证定论）／2.5–3.5（若替换） | 大概率保留现状。见下方约束 2 |
| 2A CSV 对照 | [csv()/escapeDelimited](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java) 473–513 行约 40 行；Commons CSV 只能自然覆盖 `REQUIRED/ALL + DOUBLE_QUOTE` | 当前无格式级单测，需新建 golden 夹具（3 引号策略 × 3 转义策略 × 空值/空白/分隔符/引号/CRLF/表头/trailer）；续跑对照复用 `GenerateStepCheckpointTest` 615 行夹具 | 新依赖需同 PR 重建 `docs/compliance/sbom.json` 并更新许可证台账 | 2–3（只对照）／+1.5–2（若引入并保留双路径） | 对照后保留现有编码，把夹具留下；标准组合之外无法合并 |
| 2B DAG 图算法 | 四个入口环检测约 120 行重复（`WorkflowDagValidator` 254–283、`WorkflowGraphTopologyValidator` 251–275、`WorkflowDefinitionDagInspector` 197–230、`WorkflowGraphValidator` 492–530）；`WorkflowGraphs` 59 行扩环原语后约减 60 行 | 4 个测试类共约 1 386 行；新增原语用例并逐入口对照错误码/定位 | JGraphT 不引入：`CycleDetector` 只返回布尔，拓扑排序成环时抛异常，定位弱于现状 | 2–3 | 只抽共享环原语，保留各入口错误合同 |
| AM 独立专项 | 代码已在库，本批不改代码：[AlertmanagerNotifyProperties](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/AlertmanagerNotifyProperties.java) `enabled` 默认 true、token 未配即 401、`tenantId=system` | 迁移方案 §7.1 十步 smoke、投递日志、静默桥接、开关回滚 | 目标环境与真实渠道 | 2–4（主要是环境协调） | 按专项验收，再决定旧升级链路退役 |

合计：验证型工作约 7.5–12.5 人天；若 1B/2A 均判定采纳并连带清理旧路径，约 12–17 人天；AM 的环境等待不计入。多 agent 并行只压缩 wall-clock，不减少评审与回归样本。

关键约束（影响估算的事实核对）：

1. **1A 淘汰不可断言具体键**：Caffeine 容量淘汰不保证选中哪个键，测试只能断言容量内、命中后结果与 Quartz 一致、超限后按同一规则重算。`Cache.get` 的异常传播与现状 `computeIfAbsent` 一致（表达式非法时不缓存并抛 `IllegalArgumentException`）。容量需按“活跃租户 × 调度 × 时区”估，建议先用内部常量并记录依据。
2. **1B 的库 API 不按返回值判定重试**：`spring-core 7.0.10` 的 `RetryPolicy` 只有 `shouldRetry(Throwable)` 和 `predicate(Predicate<Throwable>)`；`RetryTemplate` 失败时抛 `RetryException`（中断时恢复 interrupt 后也抛 `RetryException`）。现有代码按 `TaskResult.statusCode >= 500` 决定重试且需还原最后一次响应、保留 `attempts`/`durationMillis` 输出和 `EXTERNAL_OUTCOME_UNKNOWN` 分类，因此必须外置结果持有并自造可重试信号异常。这条正是 §7.2.3 的撤回条件，先花 0.5–1 人天验证再定。
3. **2A 扩展策略不在 Commons CSV 语义内**：`EscapePolicy.BACKSLASH` 把 `\r`/`\n` 写成字面量、`NONE` 完全不转义，Commons CSV 无等价配置；只替换标准组合会形成双路径，维护量不降。现有断言只在 `GenerateStepTest` 的 4 处策略上下文，缺双引号/反斜线/CRLF 的字节级证据，夹具本身有独立回归价值。
4. **2B 的“不共享环检测”是既有设计决定**：四处注释已声明差异（首个环即停 vs 多处上报；报节点 vs 报边 vs 报行号；错误码 `V1`/`CYCLE_DETECTED`/`error.workflow.dag.cycle_detected`）。共享只能是“返回环集合/环边”的纯算法，映射仍留在入口，合并时要同步改注释和文档。

#### 必要性判定

必要性按“不做的可观察后果 × 发生概率”判断，与改造量分开；不改变 §7 各行状态。

| 批次 | 必要性 | 主要依据 | 建议动作 |
|---|---|---|---|
| AM 通知联验 | 高 | `batch.console.alertmanager.enabled` 默认 true，旧 `AlertEscalationNotifier` 为 `matchIfMissing=false` 默认关闭；AM 只随 observability overlay 部署，送达和静默未在目标环境验证 | 优先做 §7.1 联验，证据到手再定旧链路退役 |
| 2A 字节基线 | 中 | 导出近半年 17 次提交，含 charset/BOM/CRLF 和 #1445 修复，而格式级没有单测；导出文件是客户可见契约 | 只做夹具；Commons CSV 暂不引入 |
| 1A Cron 缓存 | 低（卫生项） | 条目只随进程内出现过的“表达式 × 时区”增长，无生产 `evict`；单条内存小；git 历史无相关故障 | 可做，收益是有界资源纪律而非缺陷修复 |
| 2B 环原语 | 低（条件触发） | 四处环检测输出合同不同且是既有设计；`WorkflowGraphs` 已在 #1356 抽出；无一致性缺陷记录 | 暂缓；出现第五个入口或需同时改四处时再抽 |
| 1B HTTP 重试 | 不必要 | 近半年 23 次提交均为重构类，无重试缺陷；10 个用例已锁住语义；库 API 只按异常判定 | 直接记录“不采纳”，不再花 spike 人天 |

按必要性排序为 AM → 2A 夹具 → 1A →（2B、1B 按触发条件）；按成本/风险排序则是 1A 在前。两者不一致时以必要性为准，避免为了“用上成熟组件”先做低收益项。

按成本排序的落地顺序（供排期参考）：1A（0.5–1）→ 2A 夹具（2–3，先出字节基线）→ 2B 原语（2–3）→ 1B 验证（1–1.5，结论落计划后决定是否继续）。先做 1A 是因为它独立可回退且净减代码；1B 放最后是因为价值最低且要动生产重试路径。

未覆盖：未运行任何模块测试或编译，未核 AM 运行态与目标环境开关；`HttpTaskExecutionClient` 的 claim/renew/report 重试按 §7.2.3 仍不在本批。

各批实现后回填实际范围、执行命令及结果，在对应计划记录采纳或暂缓原因。应用范围内的复用变更以各自代码差异回退，告警按已有开关回滚。本阶段保留方案与基线证据。

<a id="remaining-self-built-components-2026-11-27"></a>

## 8. 剩余自研组件抽取与替换复核（2026-11-27）

基线与前节一致（`origin/main` `699229706`）。复核范围是 main 代码中「依赖已在 classpath」或「同仓已有等价实现」却仍自研的基础方法。对同名类做了字节级比对，未发现完全相同的副本，因此下列各项均为**同功能、不同实现**，不是复制粘贴。本轮只出方案，未改动代码。

### 8.1 判定总表

| 项 | 现状与证据 | 判定 |
|---|---|---|
| SHA-256 私有方法 | 18 处：String→hex 14 处（replay 6 处，`AlertFingerprints` / `AliyunSmsProvider` / `TencentSmsProvider` / `JobPushIntakeService` / `RedisOidcAuthorizationRequestRepository` / `DefaultConsoleAiApplicationService` / `BatchDayCatchUpOperationStore` / `ImportIngressScanner` 各 1 处）、byte[]→hex 2 处、Map→hex 1 处、文件流 1 处；另有 `DispatchManifestSupport` 的 `MessageDigest` 工厂 1 处；输出统一为小写 hex | 采纳：抽取 |
| Webhook 重试 | `WebhookDispatcher` 1 处、`SubscriptionRuleWebhookDispatcher` 2 处，均为 3 次尝试、250ms 起、逐次翻倍；`sleep` 实现重复 2 份 | 采纳：抽取 |
| Console Redis 熔断器 | [RedisRateLimitCircuitBreaker](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/RedisRateLimitCircuitBreaker.java) 92 行手写 CAS 状态机；orchestrator 同名类已用 Resilience4j，[DownstreamFallback](../../batch-common/src/main/java/io/github/pinpols/batch/common/resilience/DownstreamFallback.java) 已有共享 `CircuitBreakerRegistry` 先例 | 采纳：替换 |
| 空值/字符串工具 | `EmptyChecks` 4 547 处调用 / 734 个文件，`Texts` 1 097 / 266，`Nullables` 111，`Guard` 517；另有 3 处仍用 Spring `StringUtils` | 不采纳整体替换，仅对齐 3 处 |
| 限流算法 | console `SlidingWindowRateLimiter`（Redis ZSET + Lua）与 orchestrator `TokenBucketRateLimiter`（Bucket4j）并存，语义不同 | 暂缓 |
| Pipeline 进度缓存 | `PipelineStageProgressCache` 253 行，3 个 `ConcurrentHashMap` + 每分钟扫除 | 暂缓 |
| 时间源 | `BatchDateTimeSupport` 之外 10+ 生产类直接 `Instant.now()` / `System.currentTimeMillis()` | 暂缓（单独批次） |
| 跨模块 POJO/Mapper | 58 个同名文件（6 个 `package-info`），集中为 `JobInstanceEntity`、`ApprovalCommandEntity`、`RetryScheduleEntity` 等 | 另立项 |

`EmptyChecks` 的调用量决定了整体替换的收益为负：改动 5 000 余处、行为零变化，且回退面覆盖全部模块。只把 [TenantConfigReferenceResolver](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigReferenceResolver.java) 第 128、166 行与 [DefaultConsoleTenantConfigCopyService](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java) 第 870 行的 3 处 `StringUtils.hasText` 改为项目内 `EmptyValues.hasText`（`#1474` 已把原 `Texts` 合并进 `EmptyValues`）。SDK 的 `EmptyChecks`/`SdkEmptyValues` 保持独立，SDK 不依赖 Spring。

> **2026-11-27 实施**：3 处 `StringUtils.hasText` 已改为 `EmptyValues.hasText`（`#1475`），未扩大 `EmptyChecks`/`Guard` 的替换范围；规则见[编码规约 §1.8](../coding-conventions.md#18-空值判断写法)。

### 8.2 批次 1：哈希收敛（抽取，零语义变化）

1. 在 [Hashes](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/Hashes.java) 增加 `sha256Hex(String)` 与 `sha256Hex(byte[])`，沿用现有 `sha256Short` 的异常处理（算法不可用抛 `IllegalStateException`）。输出必须是小写 hex，与 `HexFormat.of().formatHex` 一致。
2. 替换 String→hex 的 14 处与 byte[]→hex 的 2 处；`ImportIngressOwnershipIdentity` 的 Map 版本先经现有 `JsonUtils` 序列化后调用 `sha256Hex(byte[])`，保持键序稳定；`AlertFingerprints` 的 `String.format("%02x")` 一并改为 `HexFormat`（输出不变）。文件流式的 `StoreStep.sha256Hex(Path)` 保留，避免整文件读入内存。
3. 验收：新增 `HashesTest` 覆盖空串、中文、长文本、字节数组与旧实现逐字节一致；`AlertFingerprintsTest` 通过；受影响模块 `test-compile`。回退为单批代码差异。

> **2026-11-27 实施**：`Hashes` 增加 `sha256Hex(String|byte[])`；15 个文件删除私有实现并改为调用该工具，`DefaultConsoleAiApplicationService` 保留 null 语义但去掉不可达的 `%08x` 兜底；新增 `HashesTest`（4 个用例，含公开向量与前缀一致性）。`HashesTest` 4/4、`AlertFingerprintsTest` 3/3、console 受影响 80/80、orchestrator replay 与补偿 119/119、worker import 扫描器 16/16，四模块 `test-compile` 与 `spotless:check` 通过。

### 8.3 批次 2：Webhook 重试抽取（抽取，行为不变）

1. 新增 `BurstRetryExecutor`（console `support/notification`），入参为最大尝试次数、初始退避、单次尝试 `Supplier` 与成功判定，返回「实际尝试次数 + 最后一次结果」；中断时记录并恢复中断标志，本次退避直接返回，后续尝试照旧执行（与原实现一致）。
2. `WebhookDispatcher.deliverPersisted` 与 `SubscriptionRuleWebhookDispatcher` 的两处循环改用它；成功/失败的投递日志持久化移到循环之后，使用返回的尝试次数与最后结果；两份 `sleep` 合并为一份。
3. 验收：`WebhookDispatcherTest`、`SubscriptionRuleWebhookDispatcherTest`、`WebhookDeliveryRelayTest` 保持通过；新增用例断言实际请求次数、退避间隔序列、中断标志恢复、最后一次响应/错误摘要与投递日志状态。不引入 `resilience4j-retry`：三个调用点的持久化回调差异较大，抽取执行器比套库更小且更易回退。

> **2026-11-27 实施**：新增 `BurstRetryExecutor` 与 5 个用例（首试成功、翻倍退避、耗尽结果、中断语义、非法配置）；`WebhookDispatcher` 与 `SubscriptionRuleWebhookDispatcher` 的三处循环改为调用该执行器，成功/失败的投递日志持久化移到重试结束后，两份 `sleep` 合并。`BurstRetryExecutorTest` 5/5、`WebhookDispatcherTest` 12/12、`SubscriptionRuleWebhookDispatcherTest` 17/17、`WebhookDeliveryRelayTest` 13/13。

### 8.4 批次 3：Console 熔断器改 R4J（替换）

1. 用共享 `CircuitBreakerRegistry`（`ObjectProvider` 取，缺省 `CircuitBreakerRegistry.ofDefaults()`）注册 `consoleRateLimitRedis`：`slidingWindowSize = minimumNumberOfCalls = redisFailureThreshold`、`failureRateThreshold = 100`、`waitDurationInOpenState = redisCircuitOpenSeconds`、`permittedNumberOfCallsInHalfOpenState = 1`。这与现有「连续 N 次失败才打开、冷却期只放一个探针」等价。
2. 调用侧语义不变：`allowRedisCall` → `tryAcquirePermission`，`recordSuccess` / `recordFailure` → `onSuccess` / `onError`，`isOpen` → `getState() == OPEN`。保留 `batch.console.rate_limit.redis.failure` 与 `batch.console.rate_limit.redis.circuit.open` 两个指标名和 fail-open 方向。
3. 验收：`RedisRateLimitCircuitBreakerTest`、`ConsoleRateLimitFilterTest`、`ConsoleOidcAuthorizationRateLimitFilterTest`；补充「阈值前放行、达到阈值后短路、冷却期仅一个探针、成功后清零」用例，并核对指标名未变。

> **2026-11-27 实施**：状态机改为 Resilience4j（共享 `CircuitBreakerRegistry`，实例名 `consoleRateLimitRedis`）；调用侧保留 `allowRedisCall` / `recordSuccess` / `recordFailure` / `isOpen` 四个方法，进程内不再维护计数与冷却时间。`RedisRateLimitCircuitBreakerTest` 扩为 6 个用例（阈值前放行、达到阈值、半开单探针、探测成功关闭、探测失败重开、指标名），与 `ConsoleRateLimitFilterTest`、`ConsoleOidcAuthorizationRateLimitFilterTest` 合计 24/24。

已知风险（未在本批加防护）：

- **共享 registry 同名优先**：`registry.circuitBreaker(name, config)` 在实例已存在时忽略传入配置。当前仓库没有 `consoleRateLimitRedis` 的 YAML 定义，但若将来在 `resilience4j.circuitbreaker.instances.*` 下定义同名实例，代码里的阈值会被静默覆盖。可按需加启动断言或改用专属 registry。
- **指标双写**：R4J autoconfig 另发 `resilience4j.circuitbreaker.*`，原 `batch.console.rate_limit.redis.*` 继续保留；告警口径必须继续引用原指标。

### 8.5 暂缓项的触发条件

- **限流统一到 Bucket4j**：先取得 login/敏感操作的阈值清单与边界突发要求，再用同一组场景对照严格窗口与 greedy refill 的放行差异；差异可接受才进入实施。当前两套算法各自有注释说明取舍，不在本批合并。
- **`PipelineStageProgressCache` 收编 Caffeine**：只有该类再次因容量或淘汰被改动时，才把 `taskStore` 的 TTL 与扫除换成 `expireAfterWrite + removalListener`；反向索引与 claim 身份仍自管。
- **时间源注入**：作为独立批次逐类确认是否刻意取墙钟，避免把「必须取系统时间」的路径误改为业务时钟。
- **跨模块 POJO/Mapper**：需要模块所有权与契约边界的专项，先定共享读模型的归属，不在基础组件复用的范围内推进。

### 8.6 交付方式

三批互不依赖，各自一个 PR，可按批次独立回退；均不新增依赖（Caffeine、Resilience4j 已在 `pom.xml`），无需 SBOM 变更。本轮未运行任何构建或测试，上述验收条件全部待实施时执行。

---

*依据:2026-07 两轮深度审计 + 对抗式复扫(#766–784),及范围边界文档。工作量为单人粗估,多 agent 并行可压缩 wall-clock。*
