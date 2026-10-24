# ADR-049：Replay 操作范围与身份

- 提案日期：2026-10-20。
- 状态：**提案，待用户确认；未授权新增 API、数据模型或迁移实现。**
- 关联：[ADR-048](./ADR-048-workflow-fresh-database-cutover.md)、[整合计划 R25](../../plans/workflow-daily-integration-plan-2026-10-14.md#r25-重放与补偿启动的收口契约2026-10-20)、[验收矩阵 R25](../../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)。

## 背景

R25 要求按单日/日期区间、指定 Job、失败节点和整个 Workflow 发起重放，并为每类操作固定业务日、配置/结果版本、来源身份、幂等和副作用语义。当前 `BatchDayReplayScope` 只有 `ALL`、`ALL_FAILED`、`SUBSET_JOB_CODES`、`OUTPUTS_ONLY`；Replay session 只有单一 `biz_date`，entry 以日期范围之外的 JobInstance 为来源。`DefaultCompensationService` 可对单个 JobInstance 发起 `JOB_RERUN`，但没有按 `WorkflowNodeRun` 操作的入口，也没有重放整个历史 Workflow 的服务。`STEP` 补偿指 Pipeline step，不等同 Workflow DAG 节点。

以上范围来自 R25，不应通过让前端循环调用单日 API、用 `jobCode` 猜 Workflow 节点或重开终态运行来冒充实现。计划要求如果必须增加模型能力，须先定义运行所有权、稳定身份、日期/版本继承、终态与恢复语义，再经用户确认后实施。本 ADR 提出一套可验证的默认方案；确认前不得加代码、迁移或 Console 控件。

## 推荐决策

### 1. 单日与日期区间

- 保留当前单日 session 作为每个业务日的执行单元；新增一个持久化 Replay Operation 聚合做用户操作与审批/审计边界。
- 一个区间操作先以固定 `tenant + calendar + fromDate + throughDate + scope + executionMode + policies + reason + requestedBy` 生成完整预览摘要，再一次性消费预览凭证。受理后按日建立子 session，每个子 session 独立推进和终结；某日失败不回滚其它日期已经产生的运行或外部副作用，父操作最终为 `SUCCEEDED`、`PARTIAL_FAILED` 或 `FAILED`。
- 区间按日历逐日枚举业务日，保留 `fromDate/throughDate` 为闭区间；每个子 session 使用自己的 frozen `bizDate`、来源实例/计划快照和配置版本选择。请求重复使用同一 operation identity，不增加子 session；相同日期的不同操作用新的 operation identity 表示显式重新处理。
- 最大日期跨度需由服务端明确上限并在 preview 拒绝超限，不能静默截断。具体上限须由本地数据库/调度负载验收确定，暂不在本提案中猜测数值。

### 2. 失败 Workflow 节点

- 请求必须包含租户、来源 `workflowRunId` 和精确 `workflowNodeRunId`；该节点必须属于该 RootRun、处于可重放失败终态，并为可执行 JOB 节点。拒绝通过 `jobCode` 或节点编码推断目标。
- Replay 创建新的受管 Workflow root/request 与新的 JobInstance，并持久关联来源 RootRun、NodeRun、JobInstance；来源运行保持终态且不可改写。JOB 的 `bizDate`、日历、有效参数、分区/系统 Pipeline 与结果消费版本按来源运行快照选择或按审批明确指定，不从当前日期/定义静默推导。
- 该操作只重跑所选失败 Job 节点，不隐式重跑其下游，也不宣称来源 DAG 已恢复为成功。若业务需要从指定节点继续并重放下游，属于另一种“分支续跑”语义，需独立 ADR 和显式结果输入/幂等定义。
- WAIT/GATEWAY/START/END 等控制节点不能独立作为 Job 重放；依赖等待按既有 WAIT 恢复语义处理，若需人工重启控制节点则另行定义状态机和审计行为。

### 3. 整个 Workflow

- 请求必须指向一个历史 `workflowRunId`，创建全新的 WorkflowRun/root request，保存 `sourceWorkflowRunId` 和稳定 operation identity；原 WorkflowRun、NodeRun、JobInstance 不重开、不覆盖。
- 新 RootRun 从来源 `WorkflowRun.executionSnapshot` 使用同一图版本、根参数和冻结业务日重新执行所有可执行节点；不存在可验证完整快照时失败关闭。显式选择 `USE_ORIGINAL_CONFIG`、`USE_LATEST_CONFIG` 或 `USE_SPECIFIED_VERSION`，且在新 WorkflowRun 建立时一次性解析并固定所有 Job 定义，避免节点间配置版本漂移。
- 每个跨日/外部结果依赖都须在新 RootRun 启动时解析并固定实际 `resultVersionId`；缺失/过期/不可授权版本导致受理失败，不回退到“当前 latest”。重放新产生的结果遵循明确的 result policy，只有允许策略可改变 EFFECTIVE 版本；外部非事务副作用必须由接收端稳定幂等键或人工 UNKNOWN 处置保障，平台不承诺物理 exactly-once。
- 重放所有节点意味着成功节点的业务副作用也会再次发生。因此 preview 必须显示完整目标 DAG、外部副作用类型、已知幂等能力及缺少幂等保证的节点；缺少恢复协议时拒绝整个 Workflow 自动重放，不能只显示失败节点。

### 4. 所有范围共用的执行与恢复规则

- Preview 冻结来源身份、业务日、候选实例/节点、计划/运行配置摘要、结果依赖版本、目标副作用和风险；提交消费同一预览凭证，内容变化必须重新预览。
- 操作身份在数据库唯一约束下持久化；`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT` 继续为唯一执行链。接纳、派发、拒收、恢复和终结均可从 operation→子 session→request→新 RootRun→目标节点/Job→结果版本追溯。
- 审批与租户权限覆盖父操作及所有子项；拒收和创建前失败必须终结相应子项，父计数通过锁定/重算保持正确。局部失败可重试同一子项，不重复已终结日期、节点或外部效果。
- Console 展示日期/节点/Workflow 三种范围及 preview 明细、审批人、子项进度和失败恢复入口；OpenAPI/SDK 若有受支持客户端需同步。禁止 Console 自行循环请求拼出一个看似原子的区间/Workflow 操作。

## 考虑过的方案

- **前端逐日循环调用现有单日 API**：无父操作身份、无法原子预览/审批、超时后无法可靠判断哪些日期已接纳，拒绝。
- **在原 WorkflowRun 中重开失败节点**：破坏终态单调性和历史审计，也难区分之前已成功的下游副作用，拒绝。
- **以失败 `jobCode` 重跑或以它推断 DAG 节点**：同一 Workflow 可多次引用同一 Job，无法稳定识别执行节点，拒绝。
- **整个 Workflow 重放时只重跑失败节点**：会隐含复用成功节点输出、跨版本依赖和外部副作用，当前没有完整 lineage/恢复合同，不能称为 whole-Workflow replay。

## 影响与验收

若确认推荐决策，将需要新的 operation 身份/表与父子状态模型、单日 session/entry 的来源范围扩展、相应 Mapper/API/OpenAPI、Console、权限审计、执行器和部署初始化迁移。实现前需先补验收场景：区间中途失败与恢复、重复提交/并发提交、过大范围拒绝、失败节点来源节点身份、整图副作用重复与 UNKNOWN、冻结 Workflow/Job/日历/resultVersion 快照、拒收和 REPORT 回滚。使用真实 PostgreSQL 并发屏障和至少一条真实 Kafka/Worker E2E；完成受影响后端、前端和 API 契约定向门后，再进入全量联合验证。

此提案未完成决策、实现或验收，不改变 ADR-048 的当前合同，也不把 R25 标记为完成。
