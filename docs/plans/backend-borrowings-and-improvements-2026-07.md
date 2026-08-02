# 后端借鉴与改进规划（2026-07）

> 本文回答两个问题:**该向哪些成熟系统借鉴什么理念**,以及**fbs 后端自身的演进方向**。
> 原则贯穿全文:借鉴理念,不搬运框架;守 [`bfs-open-source-scheduler-boundary-roadmap`](./bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md) 的范围边界;把已有深度打磨到无懈可击,优先于往功能广度铺开。

## 0. 定位前提

fbs 是**批量运行控制面 + 文件/任务交付闭环**,不是通用工作流引擎、不是数据治理平台、不是容器编排器。经两轮深度审计,它已经具备:

- 编排状态机(CAS 纪律 + version 乐观锁)、outbox→Kafka→CLAIM→EXECUTE→REPORT 主链;
- worker 心跳 + 租约续期 + 超时兜底;
- Workflow DAG(GATEWAY + 补偿 + 审批 + 信号)、Pipeline 固定 stages;
- 可配置重试(RetryPolicyType NONE/FIXED/EXPONENTIAL + jitter);
- 多租户隔离(RLS + 租户路由)、五语言 SDK 的 wire 契约;
- 月分区、advisory-lock 串行化、批量 SQL、resilience4j 熔断、bucket4j 限流;
- console AI 助手(已接 Spring AI + RAG + 只读工具,默认关闭)——AI 方向见 [`ai-integration-plan-2026-07`](./ai-integration-plan-2026-07.md),本文只谈后端架构。

**所以「借鉴」的重点不是补功能,而是补理念与打磨。** 下面每一条都先标注 fbs 现状,再说借什么。

## 0.1 现状校准（2026-08-02）

本文最初形成于 2026-07，以下结论以当前 `main`（合并 PR #866 后）代码为准。不能继续把已经落地的能力写成“待建设”，也不能把有测试资产写成“生产演练已完成”。

| 方向 | 当前真实状态 | 仍需补齐 |
|---|---|---|
| Spring Boot 工程化 | ✅ 已补启动失败诊断、配置边界校验、自动装配条件测试、生命周期 phase、readiness、`batchruntime` 脱敏诊断和 feature-switch CI 校验 | 目标环境 Helm/滚动发布/强杀恢复证据 |
| Checkpoint / Resume | ✅ 平台位点存储已接入 Import `LOAD` 与 Export `GENERATE`，有 chunk/page 推进、完成标记、崩溃续跑和补偿清位点测试 | 不是所有 worker 都适合复用；需继续验证 process/dispatch/atomic 的语义边界与生产同构演练 |
| Pipeline 进度 | ✅ `pipeline_progress`、worker progress sink、orchestrator cache、Console 查询和 SSE dirty event 已形成链路 | 指标告警与前端展示体验仍需按上线场景验收 |
| 执行时间线 | ⚠️ 现有 execution log、outbox、审计和 trace 可关联，但尚未聚合成统一 timeline 读模型 | 先做诊断查询，不引入 Temporal Event History 实现 |
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

- **Event History 的理念(不是它的实现)**:Temporal 的价值在「执行过程是一等公民、可回放、可审计」。fbs 已有 job_execution_log + outbox + OTel trace,但它们分散、不构成「一条任务实例的完整可回放时间线」。**改进方向**:把一个 job_instance 的关键状态转移(claim/report/retry/compensate/escalate)聚合成一条结构化、可查询的执行时间线(不要求确定性重放,只要求「运维能一眼看清这个实例经历了什么」)。这对 stuck 诊断、事后复盘价值最大,投入可控。

### 1.2 Spring Batch —— 借「Chunk / Checkpoint / Restart」

| 理念 | fbs 现状 | 借鉴判断 |
|---|---|---|
| Chunk 处理 | ✅ worker pipeline 分 stage 处理 | 已有 |
| **Checkpoint / 断点续跑** | ✅ ADR-038 平台位点已接入 Import LOAD / Export GENERATE,按 chunk/page 持久化并支持崩溃续跑 | **继续借理念**,但不强行覆盖不满足幂等条件的 worker |
| Skip / Retry 策略 | ✅ 失败分类 + 重试治理 | 已有 |

**这是最值得实打实借鉴的一条。** fbs 有 checkpoint 快照的存储位(heartbeat_details),但缺少「任务失败/超时后从上次 checkpoint 而非从头重跑」的完整链路。对大文件导入/导出(ADR-046 上万 fan-out 方向)收益显著:一个跑了 80% 的分区不该因单点失败从头再来。**改进方向**:定义 checkpoint 契约(worker 定期上报进度游标 → orchestrator 持久化 → 重新 claim 时下发游标 → worker 从游标续跑),Spring Batch 的 `ItemStream`/`ExecutionContext` 是设计参考。

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

### 2.2 Checkpoint / 断点续跑(P1→P2,部分已落地)

见 1.2。Import LOAD 与 Export GENERATE 已完成平台位点接入，并有数据库位点、chunk/page 推进、崩溃续跑和失败补偿测试。这是「上万 fan-out」方向的真实前置。

边界必须保持清楚：checkpoint 不是所有 worker 的通用开关。只有处理游标可稳定编码、业务写入可幂等重放、位点与业务写入的一致性已验证时才允许打开；process/dispatch/atomic 需要分别证明语义，不能因已有公共接口就默认复用。

### 2.3 状态机心脏的可维护性(P2,增量)

`DefaultTaskOutcomeService`(1046 行、38 方法、四类职责)是并发/状态机 bug 高发区(两轮审计的 report O(N²)、advisory lock、B2 批停摆都在它附近)。**不必专项拆**,但每次动这块时顺手拆出 DAG 推进 / 分区推进 / 计数聚合,让单次改动 blast radius 变小、可单测。

### 2.4 容量与背压的确定性(P1→P2)

- bucket4j timeout 已从 2s 降 500ms 缓解线程饥饿,但 Redis 长慢故障仍叠延迟——补「连续超时进短路窗口」(P1);
- 批量 SQL 已加 chunk 护栏(防 PG 65535 参数上限),为放开 maxPartitionCount=256 上限铺路;
- launch 消费单线程仍是已知控制面瓶颈——若要提吞吐,这里是杠杆(非 Citus)。

### 2.5 契约与类型安全的收口(P2)

- console 无类型 `Map` 响应体(~28 处)是前端字段漂移的同一 bug 类,写路径已治理,响应侧分批换 DTO;
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

### P1 收口（确定性小-中改动,可用多 agent + 评审流程压缩 wall-clock）

| 项 | 人天 | 说明 |
|---|---|---|
| IM/SMS sender SSRF 真根治 | 2–3 | Slack/钉钉/企微迁 OkHttp(各半天,组件已铺好)+ SMS SDK 单列 |
| resolveTenant web 路径 fail-close | 0.5–1 | 改语义 + 全调用方回归 |
| Redis 慢故障短路熔断 | 2–3 | 连续超时进短路窗口 + 测试 |
| arch guard 补租户谓词检测 | 2–3 | AST 检测 + 豁免白名单(误报面大) |
| advisory lock 死锁真 IT | 2–3 | 驱动完整 applyTaskOutcome 的重型 fixture |
| SQL 校验器配置源统一 | 1 | |
| DispatchChannel 熔断指标 | 0.5–1 | |
| **P1 小计** | **10–14** | 约 2–3 周 |

### P2 架构演进

| 项 | 人天 | 说明 |
|---|---|---|
| **Checkpoint / 断点续跑** | **3–8** | Import/Export 主链已落地;剩余为 process/dispatch/atomic 适配裁定与生产同构演练 |
| console Map 响应体收敛(~28 处) | 5–8 | 分批,每处小时级但要同步 OpenAPI + 前端核对 |
| alert_routing_config 接通 | 3–5 | |
| god class 拆分 | — | 不专项,下次动时顺手 |
| **AM 完整迁移** | **15–20** | 独立专项;**需真实环境影子期,不能无人值守** |

**判断**:第一梯队是 P1(≈半月,能立刻动)。P2 的主要盘子是 AM 完整迁移(3–4 周,卡在真实环境影子期)和执行时间线/DTO 收口；Checkpoint 主链已落地，不再按全新架构专项估算。god class 不单独排期。

## 5. 2026-08-02 下一步执行顺序

文档 1 的 Spring Boot 工程化改造已合并；本文后续不再重复安排相同的启动诊断、phase、readiness 和 registry 工作。剩余工作按上线收益排序：

1. **P1：降级可见性闭环**。把 outbox/Redis/限流/lease/readiness timeout/DLQ 指标接入告警规则和控制面仪表盘，明确阈值、抑制和恢复条件。
2. **P1：checkpoint 生产证据**。在生产同构 staging 做 Import/Export 崩溃、重派、业务写入幂等和位点一致性演练；process/dispatch/atomic 先做适配性裁定，不能默认打开。
3. **P1：控制面背压优化**。以 launch lag、claim/report 锁等待、outbox 积压、Redis 慢请求和连接池占用为证据，决定是否调整消费并行度或新增 pre-claim 许可，不凭感觉扩大线程池。
4. **P2：执行时间线读模型**。复用现有 execution log/outbox/audit/trace，提供租户隔离、分页和脱敏的实例时间线查询；不引入通用 workflow engine。
5. **P2：Console response DTO 收口**。按高频/高风险端点分批替换无类型 Map，同步 OpenAPI、生成 TS 类型和前端调用方。
6. **P2：真实故障演练**。完成 PG failover、Kafka 短不可用、Redis/ShedLock 故障、全 worker 组崩溃、DLQ replay、PITR 和滚动发布回滚证据。

---

*依据:2026-07 两轮深度审计 + 对抗式复扫(#766–784),及范围边界文档。工作量为单人粗估,多 agent 并行可压缩 wall-clock。*
