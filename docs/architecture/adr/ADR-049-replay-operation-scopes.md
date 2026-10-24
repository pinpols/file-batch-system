# ADR-049：Replay 操作范围与身份

> **当前实现阅读入口（2026-10-24）**：开发分支已有持久化 ReplayOperation/预览 token、区间受理、失败节点与整图快照、审批后按快照启动及外部能力检查。下方按批次追加的“尚未完成”只描述当时状态；当前进度以[验收矩阵](../../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)和[执行日志](../../plans/workflow-daily-integration-execution-log-2026-10-14.md)为准。未知外部结果与未知 SDK/插件能力不得推断安全；本 ADR 不构成所有语言 SDK 和所有目标环境验收通过声明。

- 提案日期：2026-10-20。
- 状态：**已批准实施（2026-10-22 用户确认采用下方推荐方案）。**
- 关联：[ADR-048](./ADR-048-workflow-fresh-database-cutover.md)、[整合计划 R25](../../plans/workflow-daily-integration-plan-2026-10-14.md#r25-重放与补偿启动的收口契约2026-10-20)、[验收矩阵 R25](../../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)。

## 背景

R25 要求按单日/日期区间、指定 Job、失败节点和整个 Workflow 发起重放，并为每类操作固定业务日、配置/结果版本、来源身份、幂等和副作用语义。当前 `BatchDayReplayScope` 只有 `ALL`、`ALL_FAILED`、`SUBSET_JOB_CODES`、`OUTPUTS_ONLY`；Replay session 只有单一 `biz_date`，entry 以日期范围之外的 JobInstance 为来源。`DefaultCompensationService` 可对单个 JobInstance 发起 `JOB_RERUN`，但没有按 `WorkflowNodeRun` 操作的入口，也没有重放整个历史 Workflow 的服务。`STEP` 补偿指 Pipeline step，不等同 Workflow DAG 节点。

以上范围来自 R25，不应通过让前端循环调用单日 API、用 `jobCode` 猜 Workflow 节点或重开终态运行来冒充实现。用户于 2026-10-22 批准采用本 ADR 推荐方案；按范围逐步实施并保留逐项验收证据。

## 推荐决策

### 1. 单日与日期区间

- 保留当前单日 session 作为每个业务日的执行单元；新增一个持久化 Replay Operation 聚合做用户操作与审批/审计边界。
- 一个区间操作先以固定 `tenant + calendar + fromDate + throughDate + scope + executionMode + policies + reason + requestedBy` 生成完整预览摘要，再一次性消费预览凭证。受理后按日建立子 session，每个子 session 独立推进和终结；某日失败不回滚其它日期已经产生的运行或外部副作用，父操作最终为 `SUCCEEDED`、`PARTIAL_FAILED` 或 `FAILED`。
- 区间按日历逐日枚举业务日，保留 `fromDate/throughDate` 为闭区间；每个子 session 使用自己的 frozen `bizDate`、来源实例/计划快照和配置版本选择。请求重复使用同一 operation identity，不增加子 session；相同日期的不同操作用新的 operation identity 表示显式重新处理。
- 最大日期跨度需由服务端明确上限并在 preview 拒绝超限，不能静默截断。当前采用可配置的 31 个日历日上限；这只是同步预览/受理的防护阈值，须由容量验证决定是否可作为部署目标。
- operation key 重投时以租户和业务请求指纹核对同一操作；trace ID 仅用于关联观测，不进入业务指纹。换 trace ID 的同一重投返回原操作，业务字段变化仍拒绝。

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

推荐决策已批准，实施与验收尚未完成。区间 Replay Operation 已有一次性预览凭证、父/逐日子 session、审批、查询与失败 entry 的受限恢复入口；B113 已验证真实 Spring/PostgreSQL 服务的第二日失败全事务回滚及同凭证并发去重。精确失败 JOB 节点已有审批前预览、父 operation、审批拒绝/批准重投及新 RootRun 执行证据，详见执行记录 B108-B110。

截至 B150，整 Workflow Replay 已接通一次性加密预览快照、审批、TriggerRequest/Outbox、新 RootRun、认证 Console 和 Worker CLAIM 后执行前能力核验；配置、模板/通道和结果依赖版本按来源或预览策略冻结，Worker 静态能力目录沿用既有 register 契约。B150 当前源码 E2E 以真实认证 Console 和独立 Export Worker 验证 `jdbc_mapped_export` 成功路径及 UNKNOWN 能力失败关闭：在预览后出现更新的模板/结果版本，执行仍采用冻结来源；UNKNOWN 目录导致预览阻断、提交 409、operation 不落库且 token 未消费。详见[执行记录 B150](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b150-r25-整-workflow-replay-当前源码端到端)。

B121–B137 已贯通 Job 模板、文件束模板、DISPATCH 通道/密钥及结果版本的捕获、冻结和 Worker 消费；仅具备已声明恢复合同的插件可进入执行，UNKNOWN/漂移继续失败关闭。B145–B147 补齐日期区间多失败日并发恢复及真实 Console→独立 Worker 场景，B148/B149 复核 Worker 能力围栏和五类 Worker 的报告丢失/reclaim 回归。剩余范围是其它插件与外部接收端副作用矩阵、区间容量/恢复预算依据，以及严格 SIM/CI 联合验收；R25 仍部分验证，不能据单一 Export 路径宣称全矩阵完成。

B162 增加真实对象存储写入后注入确认丢失的 Dispatch 链路验收，UNKNOWN 不自动重发；B163 以真实 PostgreSQL 验证 31 个业务日的父操作重复提交、按 session 批次推进、原业务日/来源保持及 31 个新运行和子会话完成，边界场景 1/1。该本地功能证据不等于部署容量或物理 S3 网络故障/Worker 强杀验收，详见执行记录 B162/B163。

本 ADR 已完成决策，实施与验收仍进行中；不改变 ADR-048 的当前合同，也不把 R25 标记为完成。

### 实施进展补记（2026-10-23，B123）

整 Workflow 预览现签发五分钟一次性 operation key/token，token 表仅保存摘要；完整来源图、节点定义、启动参数密文和能力风险快照经平台密钥加密后保存。`/submit` 锁定并消费凭证，重新解析来源及 ONLINE Worker 能力目录，拒绝快照漂移或存在 blocker 的提交，再创建 `WORKFLOW_RUN` 待审批父操作。新增单测覆盖密文/摘要边界、待审批父记录受理和过期凭证不消费，15/15 通过，详见[执行记录 B123](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b123-r25-整-workflow-replay-一次性预览凭证与待审批父操作)。审批后的拒绝/批准、RootRun 派发、Console 操作、实际模板/通道和结果版本冻结、CLAIM 后能力复核及 PG/Kafka/Worker E2E 尚未完成；待审批 operation 当前不能启动执行，R25 保持部分验证。

### 实施进展补记（2026-10-23，B124）

新增内部整 Workflow Replay 拒绝端点。服务按 tenant 锁定 `WORKFLOW_RUN` 父操作，仅允许 `PENDING_APPROVAL → REJECTED` 条件更新；已拒绝时幂等返回。内部 HTTP MockMvc/Mapper 定向测试 14/14，改动 Java 文件定向 Spotless 通过。该切片未包含 Console 回调、审批批准、RootRun 启动或 Outbox；批准后的 operation 快照执行和 R25 的其它 ADR-049 验收仍未完成，不能将操作视为可执行。证据见[执行记录 B124](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b124-r25-整-workflow-replay-审批拒绝状态转换)。


### 实施进展补记（2026-10-23，B125/B126）

批准后的 Orchestrator 链路已从经过 hash/解密/身份校验的 operation 快照创建稳定 TriggerRequest 与 `WORKFLOW_LAUNCH` Outbox，再由现有消费者创建关联的新 RootRun；RootRun 终态更新父 operation。Console 已接入整图预览、提交及认证审批人批准/拒绝，预览列出完整图和阻塞原因，有 blocker 时不允许提交。Orchestrator 定向回归 40/40、真实 PostgreSQL 17.11 空库迁移至 V343 的 Mapper 场景 1/1、Console 34/34、前端 13/13 和定向静态检查通过。由于 CLAIM 后真实配置能力复核尚未实现，所有启用 JOB 节点都失败关闭；模板/通道和结果 lineage 冻结、跨服务执行、区间恢复/容量证据仍待验收，R25 不关闭。具体证据见[执行记录 B125](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b125-r25-整-workflow-replay-批准后从冻结快照启动)和[B126](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b126-r25-console-整图-replay-审批入口)。

### 实施进展补记（2026-10-22，B105）

精确失败 JOB 节点补偿现创建 `WORKFLOW_NODE` Replay Operation 父记录，保存来源 WorkflowRun/NodeRun/JobInstance、target request、请求/快照摘要、操作者和原因；新 RootRun 创建时按 tenant+request 关联目标 WorkflowRun，RootRun 终态由公共终态出箱事务更新父操作。V340 为非空 target request 增加租户范围唯一索引。真实 PG/Kafka 重放链、V340 Mapper 终态集成和映射单测已通过。该切片尚未提供 ADR 要求的统一 preview/approval API 与 Console 操作入口，也未实现整个 Workflow 重放；R25 仍部分验证。

### 实施进展补记（2026-10-22，B106）

失败节点 Console 操作现提交到既有补偿审批队列；`WORKFLOW_NODE` 直接执行入口在未提供已批准审批单时失败关闭。批准执行时，Replay Operation 分别从租户审批记录保存申请人和批准人；真实 PG/Kafka E2E 已覆盖审批记录创建、批准、精确节点派发与新 RootRun 关联。该操作尚无审批前一次性 preview token/冻结快照，Replay Operation 父记录仍在批准后的执行提交时创建，待审批/拒绝/取消生命周期未纳入父操作；不视为 ADR preview/approval 全合同完成。

### 实施进展补记（2026-10-22，B107）

为审批生命周期增加独立 `REJECTED` 状态和 V341 状态约束；Mapper 仅允许 `PENDING_APPROVAL` 操作一次性转为 `REJECTED`，或在审批后一次性转为 `RUNNING` 并绑定 target request/批准人。Console 对带 `replayOperationId` 的 Workflow 节点审批拒绝会回调 Orchestrator；拒绝回调在审批已拒绝但前次同步失败时可幂等补偿。真实 PostgreSQL 已迁移到 V341 并验证批准启动/拒绝状态转换与重复调用拒绝，Console 审批应用定向测试通过。当前 Console 还没有预览及待审批父操作创建入口，因此生产流程暂未提供 `replayOperationId`，B106 所述端到端缺口仍开放；不得据此声称审批前快照或拒绝链路已完整闭环。

### 实施进展补记（2026-10-22，B108）

失败 JOB 节点现接入审批前预览 API、一次性预览凭证、冻结来源/策略快照及待审批 Replay Operation 父记录；Console 节点操作先展示精确来源与策略，再提交同一预览令牌。补偿执行入口要求已存在且与来源/快照摘要一致的待审批父操作，并在审批后原子推进为 RUNNING。来源解析现从对应 JobInstance 的任务记录检查 `EXTERNAL_OUTCOME_UNKNOWN`，在预览和受理前拒绝；避免仅依赖 WorkflowNodeRun 的错误码投影。定向 Orchestrator 单测 31/31 通过；真实 PostgreSQL/Kafka 目标 E2E 覆盖预览→父操作→批准→新 RootRun，以及 UNKNOWN 预览拒绝，2/2 通过，日志见整合执行记录 B108。E2E 后 Testcontainers 容器完成清理。此前 B107 的 Console 预览缺口已由该切片补上，但 Console HTTP 真实认证/审批全链、拒绝/取消父操作恢复、并发/重复提交仍待端到端验证；日期区间事务与整 Workflow 重放仍未闭环，R25 保持部分验证。

### 实施进展补记（2026-10-23，B114）

整 Workflow replay 增加来源解析组件：只接受租户匹配的终态运行、有效 v2 冻结 Workflow/Job/Pipeline 图及成功 START 节点，并从 START 节点的加密载荷恢复根参数；来源数据无效时失败关闭。解析组件单测 3/3 通过，执行记录见 [B114](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b114-r25-整-workflow-重放来源快照解析)。当前尚未有整 Workflow preview、副作用评估、三种配置策略解析、Replay Operation 审批/新 RootRun 派发、结果依赖固定或 Console 操作链；该来源解析组件不构成整 Workflow replay 功能，R25 仍部分验证。


### 实施进展补记（2026-10-23，B115）

整 Workflow preview 无法仅靠 Orchestrator 的冻结 Job 定义安全判定：`WorkflowRunExecutionSnapshot` 不含运行时实际选中的插件身份/版本或 `IdempotencyCapability`；该能力由 IMPORT/PROCESS/EXPORT Worker 的插件注册表暴露，未声明时各插件接口默认 `UNKNOWN`。从 jobType 或参数猜测幂等会误把副作用节点标安全。R25 增加能力发现前置合同：Worker 在现有 register 契约上报插件能力目录，声明必须是对该插件所有配置均成立的保守上界；无法作此保证的配置依赖插件只能返回 UNKNOWN。预览按冻结配置选择目录项、对所有可调度实例逐项比对并冻结；Worker 在 CLAIM 后、任何业务副作用前仍须按真实配置解析，并核验其不超出冻结声明。未知、实例不一致、漂移、无恢复协议或能力目录读取失败均阻止整图提交。不得新增 Worker HTTP 路由。该合同不改变 ADR 的范围目标，R25 仍部分验证。

### 实施进展补记（2026-10-23，B116）

新增 `ReplayCapabilityDeclaration` 共用能力合同，显式携带插件 ID/版本、外部效果类别、幂等声明、幂等键作用域、恢复协议 ID 和合同版本；缺省声明固定为 `UNKNOWN` 且不能通过完整性判定。IMPORT、EXPORT 插件和 ATOMIC/DISPATCH `BatchTaskExecutor` 增加按冻结配置解析能力的扩展点；IMPORT/EXPORT 注册表、PROCESS 阶段执行器及 ATOMIC/DISPATCH 注册表经实际选中插件/执行器调用解析，不按 JobType 推断。当前已完成的仅为 SPI 与本地解析器，不含 Worker HTTP 能力解析接口；该接口违反仓库 Worker HTTP 边界，已撤回。六类 Worker 模块定向测试 31/31 通过并编译成功。Orchestrator 能力读取/多实例核对、能力目录注册、快照冻结、派发前复核及全 DAG preview 尚未实现；生产插件仍默认 UNKNOWN，阻止整 Workflow replay，R25 继续部分验证。执行证据见 [B116](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b116-r25-worker-重放能力合同基础)。

### 实施进展补记（2026-10-23，B117）

五类 Worker 现从本地执行器注册表生成稳定排序的能力目录，并通过现有 `/internal/workers/register` 上报；能力目录以 JSONB 保存到 `worker_registry`，可按租户、Worker 分组和可调度状态读取全部实例声明。新迁移 V343 不增加新的表或 HTTP 路由；运行态 `worker_registry` 已受租户 RLS 保护。定向模块测试 99/99、真实 PostgreSQL/Flyway 迁移与写入/读取 1/1 通过。能力目录持久化不代表完整 Replay 能力已验证：当前生产插件仍声明 UNKNOWN，且逐实例合同比较、预览快照冻结、CLAIM 后副作用前的 Worker 再校验、全 Workflow Replay 及 E2E 尚未实现；R25 继续部分验证。执行证据见 [B117](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b117-r25-worker-注册能力目录持久化)。

### 实施进展补记（2026-10-23，B118）

为避免把空配置下的插件结果误认成跨配置安全保证，静态注册能力目录与冻结配置运行时解析现已拆成独立 SPI：静态 `replayCapabilityDeclaration()` 缺省 UNKNOWN，配置解析 `replayCapability(frozenConfig)` 不再被用于生成注册目录。Import/Export/Process/Atomic/Dispatch Worker 均从静态声明接口生成目录。新增回归证明配置专属声明不会泄漏到静态目录；定向测试 51/51 通过。当前 production plugin 尚无跨配置完整静态声明，Replay 仍须失败关闭；实例目录比较、必要插件与配置映射、审批快照冻结和 CLAIM 后副作用前复核未完成，R25 继续部分验证。证据见 [B118](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b118-r25-分离静态能力目录与配置解析)。

### 实施进展补记（2026-10-23，B119）

新增 `WorkflowReplayJobDefinitionResolver`，将整 Workflow Replay 的配置版本选择按 JOB 节点独立解析。来源配置只从同 nodeCode 的来源运行冻结快照取得；最新配置校验租户与 Job 身份；指定版本只从历史 WorkflowRun 冻结快照按来源 JobDefinition 身份和版本读取，缺失/不匹配时拒绝，不回退当前定义。Orchestrator 定向测试 10/10 通过，覆盖历史查询返回错误版本时失败关闭；证据见 [B119](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b119-r25-整-workflow-replay-按节点解析冻结-job-配置版本)。该解析器尚未接入 whole Workflow preview、审批、派发及结果依赖固定，Worker 能力比对/快照冻结和跨服务 E2E 仍未完成；R25 继续部分验证。


### 实施进展补记（2026-10-23，B120）

整 Workflow 的只读预览现已通过内部 Controller 接上来源图、逐节点配置与 ONLINE Worker 的选定插件声明比较，返回完整节点/边及 UNKNOWN、未固定插件/结果依赖等阻塞明细。最新配置对同 Job 在一次预览中只读一次；指定历史配置允许同一 Job 被多个节点重复引用，但拒绝相同身份/版本的不同内容。区间 operation 的五个响应接口改用独立 DTO，26 个公开字段与现有 Console 消费契约一致，冻结参数与内部哈希留在服务端。定向证据见 [B120](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b120-r25-整-workflow-只读预览与-replay-响应边界)。该接口尚不签发提交凭证，也没有整图审批/派发/Console 链路；模板/通道与实际结果依赖固定、Worker 执行前再校验和真实跨服务验收仍待完成，R25 保持部分验证。

### 实施进展补记（2026-10-23，B121）

IMPORT RECEIVE 与 EXPORT PREPARE 共用冻结模板解析器；普通执行可复用已加载上下文，修复 EXPORT 总是重读 latest 导致同一次执行配置漂移的问题。整 Workflow 的模板必须从原始 CLAIM payload 中的 `frozenFileTemplateProtected` 读取，使用平台 KMS 认证密文；schemaVersion=1，保存 tenantId/templateCode/templateType/templateId/templateVersion 和完整配置，并要求头部身份/版本与配置内容一致、与实际任务租户/模板匹配。字段存在但为空、密文被篡改、身份不符、根级字段重复或整图重放缺少快照时，返回 `FROZEN_FILE_TEMPLATE_INVALID`，不回退 latest，不在异常中携带解密配置。解析原始 payload 采用流式扫描，只提取根级 `operationType` 与受保护模板字段，跳过内联文件内容及嵌套同名字段。

这是 Worker 消费合同，尚无控制面快照生产、operation 审批绑定或实际整图派发；不能据此去掉只读预览的模板未固定阻塞。控制面后续必须将 Mapper 的 JSONB 值规范化为 JSON 数据，冻结到审批快照，再随已有 Outbox/CLAIM 链传入，并绑定 operation 与节点。通道配置、实际结果依赖版本及 CLAIM 后插件能力复核仍开放；本批无数据库迁移、Worker 新 HTTP 路由或 Console 契约变更。定向验证及失败记录见[执行记录 B121](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b121-r25-importexport-冻结文件模板消费)。R25 保持部分验证。

### 实施进展补记（2026-10-23，B122）

普通 IMPORT/EXPORT 及文件束现由 Orchestrator 在 Job 创建事务中捕获有效模板：数据库将完整模板行转为原生 JSON，配置经平台认证加密后写入 `paramsSnapshot.effectiveParams`，同一组参数交给后续分区派发。来源 JSONB/时间列不再携带 PGobject 等驱动类型。文件束按模板编码去重捕获，以 `_frozenFileTemplatesProtected` 保存集合，派发时使用分区绑定的模板编码选择一份密文，移除集合，避免根参数覆盖分区模板或把其它模板配置附带给当前任务。现有 CREATED 恢复从同一 effectiveParams 回读，须继续补真实恢复与 Worker 主链证据。

`USE_ORIGINAL_CONFIG` 的模板只从同租户、同作业身份、同业务日来源实例的实际持久化快照回读，并重新验证密文及模板身份；请求方提供的内部快照不能直接受信任。原快照缺失时拒绝，不查询 latest。普通无匹配模板的载荷执行保留原语义，但不据此生成虚假模板版本，之后也不能宣称已固定原模板。指定 Job 定义版本不能推导独立的文件模板版本；缺少独立模板版本证据时明确拒绝，后续须将模板版本选择纳入 preview/approval，不能把两种版本号视为同一序列。整 Workflow 的普通启动旁路仍拒绝未经 operation 绑定的模板快照。业务拒绝在 T1 回滚后将请求置 REJECTED，基础设施故障保留重试边界。

本批不新增数据库结构或 Worker HTTP 路由。整图审批快照绑定和批量预览捕获仍未接通，不能取消 whole-Workflow 的模板未固定阻塞；指定模板版本、源快照到真实 CLAIM 的跨服务恢复、通道配置与结果 lineage 仍未闭环。R25 保持部分验证；定向 43/43（含真实 PostgreSQL 模板捕获 1/1、双模板 Export Worker E2E 1/1）通过；命令与边界见[执行记录 B122](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b122-r25-模板捕获持久化与文件束分区绑定)。


### 实施进展补记（2026-10-23，B131）

Worker 执行前的能力解析现已接通 PROCESS 与 IMPORT，并新增 EXPORT 请求级解析：EXPORT 从同一冻结模板解析 PREPARE 配置，再用 GENERATE 真实插件选择调用配置级能力接口；定向回归 47/47 通过。IMPORT 定向回归 32/32。当前 Export/Import 生产插件的静态声明仍为 UNKNOWN。更重要的是，整图 preview 尚未把选中 JOB 的实际模板配置及 DISPATCH 通道配置捕获进 Replay Operation 快照，`selectedPlugin` 仍将文件模板和通道选择视为未冻结。因此即使 Worker 解析器已接通，不能解除启用 JOB 的 preview blocker，也不能批准执行。下一闭环仍须在 preview/approval 快照中固定逐节点参数、模板/通道身份及配置，并使静态目录所选声明与 CLAIM 后按同一冻结输入解析的运行时声明匹配。详见[执行记录 B130/B131](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b131-r25-export-请求级能力解析)。R25 保持部分验证。

### 实施进展补记（2026-10-23，B132–B150）

整图 operation 现冻结来源配置、文件模板、DISPATCH 通道/密钥和实际跨日结果版本，并将经过静态目录确认的插件能力沿 Outbox/CLAIM 传递；Worker 在业务适配器执行前复核真实配置能力。B150 当前源码 E2E 覆盖完整 DAG 的认证预览、审批、新 RootRun 和独立 `jdbc_mapped_export` 执行；同一运行先冻结原模板/结果版本，之后产生更新版本，仍以原快照完成。将 Worker 能力目录改为 UNKNOWN 后，整图预览和提交失败关闭，凭证保持未消费。B148/B149 的执行前能力围栏定向回归 62/62、五类 Worker REPORT 丢失/reclaim 整类回归 9/9。

该证据只证明已声明 `jdbc_mapped_export` 路径及 UNKNOWN 拒绝路径，不覆盖其它插件/远端渠道的真实副作用恢复组合；日期区间容量依据、跨服务部分失败的完整恢复矩阵及严格 SIM/CI 仍待验收，R25 保持部分验证。命令与原始输出见[执行记录 B150](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b150-r25-整-workflow-replay-当前源码端到端)。


### 实施进展补记（2026-10-23，B164）

整 Workflow Replay 包含来源成功节点；同一 Job 定义出现多次时，各节点创建独立 Job 实例。EXPORT 的默认最终和临时键按平台 Job 实例隔离，已登记产物只能由同实例重领复用，并验证远端 SHA-256。新实例不能丢弃新生成内容而借用来源文件；归属由平台执行上下文写入，用户 metadata 不得覆盖。显式键命中不同或不可验证归属的已登记记录时拒绝；未登记显式键的并发首次写入不在该检查保证内。

真实 JWT/CSRF 双人审批与独立 Export Worker 场景已验证：来源一失败、一成功且同 Job 定义的两个节点均重新执行，新文件包含变更后的业务数据，来源节点记录和原文件字节不变。当前整图定向 1/1，同实例进程重领与最终组合结果见[执行记录 B164](../../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b164-r25-整图重复-job-节点与导出产物实例归属)。R25 保持部分验证，不替代其它插件副作用矩阵及冻结版本联合门。
