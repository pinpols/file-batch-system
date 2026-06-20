# ADR-044 · 实例 pause/resume + 批次日严格串行

- **Status**: Proposed
- **Date**: 2026-06-20
- **Related**: `docs/analysis/system-wide-capability-gap-analysis-2026-06-20.md`(全系统缺口分析,本 ADR 的源,缺口 #15)、`docs/plans/settlement-gap-remediation-roadmap-2026-06-20.md`(Phase 4.3)、ADR-018(跨日依赖,**边界对照**)、ADR-042(WAITING/有界队列,**状态复用对照**)、ADR-027(范围红线)
- **Plan**: 本 PR 仅设计文档,评审定方向后再逐 PR 落地。架构级:动 `job_instance` / `workflow_run` 状态机(orchestrator 唯一状态主机),**先拍板状态迁移再写码**。

## 范围边界(实施 PR 必答)

判定提问:**「这是『暂停 / 续跑一个运行实例的生命周期』+『同一 job 按批次日串行』,还是『挑机器 / 扩缩容 / 资源编排』?」** 实例生命周期 + 串行依赖 → 属本 ADR;挑机器 / 扩容 → ❌ 不属(ADR-027 红线)。

**做(✅)**:
- ✅ 给 `job_instance`(及 `workflow_run`)加 **`PAUSED` 态**:暂停 = **停止派发新分区 / 新 DAG 节点**,在途的让其自然终结,不破坏性 kill;resume = 重新进入派发。支持「周五停、周一续」。
- ✅ **批次日严格串行**:同一 `job_code` 在批次日 `N+1` 的 launch,**gate 在批次日 `N` 已终态成功**(防止前一日没跑完 / 没跑对就叠下一日,结算链路要求)。
- ✅ pause/resume 经 console-api → orchestrator 既有运维通道(`ConsoleOrchestratorProxyService` 类比),orchestrator 内做状态迁移,**worker 不直写状态**。

**❌ 不做(明确边界)**:
- ❌ **不做 mid-task 抢占 / 序列化运行中 task 内存态**(那是 checkpoint 范畴,ADR-038):PAUSED 是「分区 / 节点**调度**层暂停」,**不冻结**正在 EXECUTE 的单个 task;在途 task 跑完即止,新的不再派。
- ❌ **不挑机器 / 不扩缩容**(ADR-027 红线):pause/resume 是实例调度状态,与节点资源编排无关。
- ❌ **不复用 `CANCELLED`/`TERMINATED`**(那是破坏性终态,不可续):PAUSED 是**可逆中间态**,必须新增。
- ❌ **不与 ADR-042 的 `WAITING` 混淆**:`WAITING` = 等容量(系统自动、容量释放即跑);`PAUSED` = 人工暂停(系统不会自动续,必须显式 resume)。两态语义正交,不合并。

## 背景

缺口分析(2026-06-20)编排侧 Top 缺口 #2/#5:

1. **无实例 pause/resume**:`JobInstanceStatus` 有 `CREATED/WAITING/READY/RUNNING/PARTIAL_FAILED/SUCCESS(_DRY_RUN)/FAILED(_DRY_RUN)/CANCELLED/TERMINATED`,`WorkflowRunStatus` 有 `CREATED/RUNNING/SUCCESS/FAILED/TERMINATED/..._DRY_RUN`——**均无 PAUSED**。现状要中止一个跑飞 / 需人工介入的长批,只能 `cancel`(破坏性、不可续)。运维诉求「先停一下、查清楚再继续」「周末停、工作日续」无法表达。
   - 注:`TriggerStatus.PAUSED` 已存在,但那是**触发器定义**层(停止再 fire),**管不到已 launch 的运行实例**。
2. **批次日串行未强制**:同一 job 多个批次日的实例可并行推进;现有 `CrossDayDependencyResolver`(ADR-018)需**每个 workflow 节点显式声明** cross-day 依赖,**不是 job 级的全局串行闸**。结算场景常要求「批次日严格按序、上一日没成功不开下一日」,目前靠人工纪律,无系统强制。

## 决策

### 1. 新增 `PAUSED` 状态

- `JobInstanceStatus` 增 `PAUSED`;`WorkflowRunStatus` 增 `PAUSED`。
- **可迁移性**:`RUNNING/PARTIAL_FAILED/WAITING/READY → PAUSED`(可暂停);`PAUSED → RUNNING`(resume,回到派发);`PAUSED → CANCELLED/TERMINATED`(暂停后决定弃)。终态(`SUCCESS/FAILED/CANCELLED/TERMINATED`)**不可**进 PAUSED。
- **语义**:实例进 PAUSED 后,派发器(`WaitingPartitionDispatchScheduler` / workflow 节点 dispatcher)**跳过**该实例的新分区 / 新节点派发;在途分区 / 节点跑完照常 REPORT,但**不推进**到下一波。CLAIM/租约/心跳机制对在途 task 不变。
- resume:实例回 `RUNNING`,派发器重新纳入,从「已完成 + 待派发」边界继续(天然 checkpoint:已 SUCCESS 的分区 / 节点不重跑,靠既有幂等)。

### 2. 批次日严格串行(可选开关,按 job 配)

- `job_definition` 增可选 `batchDaySerial`(默认 `false`,向后兼容)。
- 为 `true` 时:批次日 `N+1` 的 launch(无论 trigger fire 还是手动)在 orchestrator launch 入口 gate——查同 `job_code` 批次日 `N`(上一日历批次日)`job_instance` 是否终态 `SUCCESS`;否 → **defer 到 WAITING(等上一日完成)** 或拒绝(策略可配,默认 defer)。
- 与 ADR-043「依赖感知 fire」区别:043 是**触发器声明跨 job 依赖**(A 等 B);本条是**同一 job 自身跨批次日串行**(job J 的 day N+1 等 day N)。两者可叠加。

### 3. 控制面入口

- console-api 增 `pauseInstance` / `resumeInstance`(高危操作,建议纳 ADR-043 兄弟的 maker-checker / 至少审计留痕),经 orchestrator internal API 落状态迁移,**不直写 `job_instance`**(守架构硬约束:console-api 不直写状态、worker 不直写状态)。

## 影响

- **正向**:长批可暂停排查再续,不再「一刀 cancel 重跑」;批次日串行从人工纪律变系统强制,堵结算级「叠日」风险。
- **状态机改动**:`PAUSED` 进 2 个核心 enum + 迁移校验 + 派发器跳过逻辑 + archive 冷表镜像(`ArchiveSchemaDriftCheck` 要求 `*_archive` 同步)。**影响面大、需充分测**(状态迁移守护测 `MultiTenantIsolationIntegrationTest` 类同级别覆盖)。
- **幂等 / checkpoint 叠加**:resume 依赖既有分区 / 节点幂等(已 SUCCESS 不重跑);若叠 ADR-038 checkpoint,单 task 内也可续(正交)。
- **风险**:PAUSED 与在途 task 的边界要讲清——「暂停 = 不派新,不冻结在途」,避免运维误期望「点暂停立刻全停」。文档 + UI 提示需明确。

## 备选(未采纳)

- **用 `cancel` + 重新 launch 模拟暂停**:破坏性、丢进度、不可精确续,否决(正是现状痛点)。
- **复用 `WAITING` 表达暂停**:语义冲突——`WAITING` 是系统自动等容量、容量释放即自动跑;`PAUSED` 必须人工显式 resume。混用会让「自动续」误触发暂停的实例。否决。
- **批次日串行做成全局锁表**:粒度粗、跨 job 误锁;改用 per-job `batchDaySerial` 开关 + launch 入口 gate,精确且默认关。
