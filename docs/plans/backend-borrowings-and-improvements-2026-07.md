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
| **Checkpoint / 断点续跑** | ⚠️ 心跳带 heartbeat_details 快照,但「从断点恢复执行」链路未打通 | **值得借,P2** |
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

### 2.1 可观测性:从「有指标」到「降级可见」(P1,进行中)

审计核心结论:**保护基础设施的行为逻辑测得扎实,但「降级发生了/保护旁路了」这一层系统性缺失**。已补 outbox 熔断 OPEN gauge、限流拒绝/fail-open counter、apikey 缓存命中率、advisory lock 争用 Timer。**继续方向**:

- 把散落的指标收敛成「控制面健康仪表盘」的语义(而非一堆孤立 metric);
- 关键降级(outbox 集群熔断、限流 fail-open、Redis 慢故障)接告警规则,而非只在日志;
- 执行时间线(见 1.1)作为诊断的一等入口。

### 2.2 Checkpoint / 断点续跑(P2,借鉴 Spring Batch)

见 1.2。这是「上万 fan-out」方向的真实前置,也是最能体现批处理平台成熟度的一块。

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

*依据:2026-07 两轮深度审计 + 对抗式复扫(#766–784),及范围边界文档。*
