# Workflow 与每日连续性隔离整合计划

> 修订日期：2026-10-16；契约 `2026-10-14.2`。**用户确认：不兼容旧系统，全新数据库部署。**
> 状态：整合实施中；定向编译和部分 PostgreSQL 验收通过，I2/I3 仍有运行语义、并发和恢复缺口，不能发布。
> 分支：`feature/workflow-daily-integration`；当前权威：[ADR-048](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md)。[ADR-047](../architecture/adr/ADR-047-workflow-scheduling-and-daily-obligations.md)已被取代。
> 本轮按已授权的整合方案修改隔离工作区内代码、测试和新库迁移；验证结果与未完成项见[验证矩阵](./workflow-daily-integration-validation-2026-10-14.md)。

### 主线开发任务的补充缺陷清单

2026-10-14 按用户要求将[设计闭环复核](../audit/design-closure-review-2026-10-14.md)纳入本分支：后端 SEAM-1–6 与前端/跨端 UI-SEAM-1–9 的逐项状态、责任边界和关闭条件统一维护在 [TODO Master](../analysis/todo-master.md)。报告中的 FE-1–9 对应 UI-SEAM-1–9，不与 TODO 中历史已完成 FE 编号混用。

执行恢复相关项与 I4 联合核对，前端身份绑定、配置保存/导入、操作反馈及生成契约与 I5 联合验收；修复应分别保留失败用例、实现和结果证据，不因报告已转入开发分支而标记完成。配对 OpenAPI 必须以实际整合后端为契约源；验证矩阵记录的 2026-10-15 生成漂移检查已通过，但不代替真实前后端业务结果验收。后续每次契约改动仍须重跑该检查。

## 0. 完成口径与明确范围

方案的完成表示职责、接口、状态、失败处理、切换和验收可按步骤实施；系统可用必须另有同一整合版本的 A1–A6 与 I6/R25–R36 验收证据。测试失败不得以“已编译”关闭；语义盘点发现的必要改造未实现时，也不得以增加测试或风险说明关闭。

### 0.0.1 治理与整改的统筹闭环

本方案同时统筹三类性质不同的工作，分别维护台账、优先级和关闭证据，不合并成一个笼统的“治理整改”结论：

| 维度 | 本方案承接范围 | 权威跟踪与关闭依据 |
|---|---|---|
| **系统问题整改** | Workflow/批量调度主链的业务语义、并发恢复、依赖、告警、分区分片、跨端契约及发布风险；由 I0–I6、A1–A6 和 R01–R36 逐项承接。高风险问题按影响定级，必要时优先于常规代码规约工作，且未完成的发布阻断项不得以文档或静态检查关闭。 | 本方案 §0.1/I6、对应 I 切片；[TODO Master](../analysis/todo-master.md) 的 SEAM、UI-SEAM、ALIGN 项；[验证矩阵](./workflow-daily-integration-validation-2026-10-14.md) 中对应行为证据。 |
| **代码规约整改** | 本次整合代码及其必要调用方中的可读性、结构、测试约定、SQL/Mapper、注释、日志和门禁存量问题。只修实际命中且影响本次交付或维护安全的项；逐项保留代码位置、整改决定和验证证据，不以放宽规则或扩大豁免清账。 | [TODO Master](../analysis/todo-master.md) 的 ALIGN-GATES 及相关质量治理项；[规约漂移守卫索引](../audit/convention-drift-guard-index.md) 和对应 Java/SQL/脚本专题文档。存量数量与门禁结果以当前检查和验证矩阵记录为准。 |
| **治理规范与防退化建设** | Skill、规范、文档入口、CI 路由和基线守护等规则体系调整。与代码规约整改分开排期，默认在代码规约整改之后实施；不得为了减少既有违规先降低标准。当前交付必需的精确门禁修复和记录可同步进行，但不代表规范体系整体治理已关闭。 | [TODO Master](../analysis/todo-master.md) 中的治理项、[Agent Skill 使用指南](../standards/agent-skills-guide.md)、[文档治理规范](../standards/document-governance.md) 及对应治理计划；以守卫正反例、适用 CI 结果和文档同步证据关闭。 |

三类工作分别标注“已评估、待实施、实施中、待验收、已关闭”，并在 [TODO Master](../analysis/todo-master.md) 保持唯一当前状态。专题方案可承载详细清单，但必须从本节或 TODO Master 可发现；设计覆盖、代码修复、自动化验证和目标环境验收分别报告，不能互相代替。

- **交付**：单一 Workflow 根运行、Job 节点执行、系统 Pipeline、Workflow 定时账本、文件/事件预期、业务日结算、持久化等待、新模型的重试/恢复、配对 Console 与配置入口。
- **退出范围**：旧 Job 顶层入口、旧 wrapper/虚拟父任务、旧 FILE_STEP 转换、旧 key 映射、旧 Pipeline 消息/配置包读取、旧库升级与旧历史导入、混合版本运行。
- **保留的新系统能力**：运行快照、执行版本支持声明、数据消费版本、归档、业务重放、审计与故障恢复。新模型上线后的版本演进必须保护已产生的数据。
- **实施约束**：复用 Quartz、现有事务/Outbox/状态模型；不另建调度引擎、通用事件总线或通用义务框架。共享字段只为已列场景引入，不能为假设扩展增加抽象。
- **资料保全**：原分支和原数据保留；新库部署不等于授权删除旧环境。来源方案中的兼容要求及旧测试记录只作为历史材料，不继续纳入实施任务。

## 0.1 批量调度既有语义迁移（实现范围与发布门槛）

本次主链切换必须完成 `origin/main` 既有批量调度行为的逐项盘点和承接设计。此项是 I1–I5 的横向实施工作，不是额外的“多跑几项测试”：凡 Workflow 根、节点 Job Instance、occurrence 或 BatchDay 义务模型不能保持既有业务结果的地方，先明确新语义并改造代码、持久化、API/Console 操作、告警和 Runbook，再以验证矩阵证明。未完成语义裁定或必要改造，不得进入 D4 新链路预演和 D5 批量开放。

盘点以 `origin/main` 的 `ee01e97515b04fd167ed17977535ec5cab8572f8`（2026-10-15 本轮审查时解析值）为源码基线；第 1 节记录的 `17c245...` 是本次整合最初的历史分叉点，二者用途不同。数据库状态、Console 操作、告警规则和运维脚本也须按该源码版本关联核对。I6 先回溯盘点当前已实施切片，再将发现映射到 I1–I5，避免把已写代码当作旧语义已承接。逐项记录入口、状态/身份键、事务与幂等边界、业务日期口径、计数/结算影响、外部副作用、操作权限、证据及目标承接点；标记为“等价保留 / 有意变更 / 明确退役 / 不适用（给出理由）”。“有意变更”必须写明新规则、初始化策略、用户可见变化和恢复行为。

I6 按以下顺序形成可审阅产物：①固定源码 SHA 与 Console/告警/脚本版本；②按本节语义域逐项列出 `main` 的生产者→持久化事实/稳定身份→消费者→外部副作用；③为每项指定决定、I1–I5 责任切片、实现责任人、关联 R 场景及证据链接；④未决语义先记录决策与受影响代码边界，依赖该决定的实现不得自行假设；⑤实现后回填差异、回归证据和关闭结论。台账至少包含：`语义 ID | main 基线路径/行为 | 生产者 | 持久化事实/身份 | 消费者/副作用 | 承接决定 | 实现切片 | R 场景 | 证据/状态`。每项必须有代码/配置位置，不能以一段概述代替逐项台账。

| 语义域 | 必须盘点的既有行为 | 新主链必须明确的承接/改造决定 |
|---|---|---|
| 触发机会与调度 | CRON/FIXED_RATE、手工/审批/文件/事件触发、重复 fire、misfire、pause/resume、日历变更 | Workflow occurrence 的唯一身份、触发来源、版本生效边界、暂停对日程与运行的分别作用、迟到/拒绝处置和可恢复性 |
| 批次日生命周期 | 开日、cutoff、结算/关闭、空日、人工跳过/豁免、重开及历史缺口 | BatchDay 状态迁移、覆盖证明、未决义务和在途运行如何阻断结算；跳批是否豁免业务义务、是否影响后续日期及依赖，审计和重开规则。开日只建立业务日及预期义务，不预建当天所有 JobInstance；接纳的触发请求建立 WorkflowRun，JobInstance 在对应 JOB 节点实际派发时创建。必须用持久化记录和并发/恢复测试核实各入口都遵守此时序，不能仅凭类名或设计描述认定现状符合 |
| 重放、补跑与重试 | 按日/区间/作业/失败实例重放，catch-up，人工指定日期，节点/Job/Workflow 重试，补偿与 dry-run | 明确重放范围和新 Run/attempt 身份；原业务日、参数/版本快照、结果来源、幂等键、外部副作用与审计如何保持；不同层级重试不能混为一种操作 |
| 依赖与跳过 | 同日/跨日 Job 结果依赖、Workflow 内 DAG 控制边、跨 Workflow 的 Job 结果依赖与“等待上游整条 Workflow 终态”依赖、等待超时、结果版本、失败传播、条件分支、人工 skip | 分清 Job 结果就绪和 Workflow 整体成功：上游 Job 的 EFFECTIVE 结果不能自动代表所属 Workflow 已成功；跨 Workflow 若要求整体成功，必须有 WorkflowRun 终态身份/等待记录/重放语义，不能只用 `jobCode` 或 DAG 边推断。现有依赖到底要求 Job 产物还是完整流程完成须按 main 逐项盘点并定裁；跳过还须有原因/操作者及对必需业务义务的影响 |
| 执行治理与统计 | 并发/配额/优先级/批次窗口、SLA、成功失败跳过取消计数、报表 | Workflow、节点、Job、分区/任务的计数口径和资源占用归属；控制节点不占 Worker 资源；SLA 起止点、聚合与去重键 |
| 作业分区与数据/库分片 | STATIC/DYNAMIC/AUTO 等分区数策略、动态 fan-out、`partition_key`/`input_snapshot`、Worker claim/lease/retry、按 JobInstance 汇总；业务结果分区键、PostgreSQL 表分区与租户物理分片路由 | 明确分区数和分片计划何时冻结（Workflow 接纳、JOB 节点派发或重放时），Job 配置变更是否影响已接纳 Run；同 Job 多节点/多次触发的执行分区按独立 JobInstance 隔离，稳定关联 node/run；业务数据结果键须定义同一 Job/date 多节点产出时的覆盖/并存策略；控制节点不创建执行分区、不占分区配额；DB 分片按 tenant 路由并保持 Workflow/Job/partition/task 同分片事务边界，核对迁移、RLS、归档和运维分区维护 |
| 告警与运营处置 | 作业失败/超时/SLA、依赖等待超时、漏触发、预期输入缺失、积压、结算阻塞及告警去重/恢复 | 告警实体关联 Workflow Run、节点和 Job Instance 的规则；保证原有告警不漏报/重复报，告警链接、处置权限、确认/恢复生命周期和低基数指标同步迁移 |
| 配置、权限与审计 | Job/Workflow 启停、编辑/删除冲突、审批、配置包复制/导入、审计及运行查询 | 两菜单分工后的操作等价性；被多 Workflow 引用的 Job 影响提示；参数/日历/调度配置归属、租户边界、拒绝原因和审计记录 |
| 故障恢复与运维 | 服务/数据库/Kafka/Worker 停机、积压释放、旧入口残留、手工处置 | 从持久化状态恢复且不丢/重做副作用；Runbook、安全操作 API、回滚/前向修复和未决义务处理方式 |

盘点结果及决定写回本整合计划与 [TODO Master](../analysis/todo-master.md)，场景进入验证矩阵 R25–R36，并映射到 I1–I5 的代码责任切片。R27/R32（告警与终态事件）、R28/R36（资源与容量）、R30/R35（权限与配置）存在覆盖交叉：每个具体场景指定一个主验收行，其余行只引用该证据并验证自身独有断言，避免重复计数或相互替代。只有文档盘点而没有必要的实现改造不算关闭；只有实现/单测而缺少旧行为基线和业务结果断言也不算关闭。未决语义须在依赖它的代码切片前解决。

### 0.1.1 I6 逐项台账（持续更新）

以下只记录已完成代码追踪并有当前测试证据的语义项；未列项仍未盘点，I6 不得据此关闭。基线为本节固定的 `origin/main` `ee01e97515b04fd167ed17977535ec5cab8572f8`。

| 语义 ID | main 基线路径/行为 | 生产者 | 持久化事实/身份 | 消费者/副作用 | 承接决定 | 实现切片 | R 场景 | 证据/状态 |
|---|---|---|---|---|---|---|---|---|
| SCHEDULE-OWNER-IDENTITY | `batch-trigger/.../mapper/TriggerDefinitionMapper.xml` 从 `job_definition` 读取日程；`service/DefaultTriggerService.buildScheduledDedupKey` 使用 tenant/Job/fire；`batch-orchestrator/.../service/DefaultLaunchService.launch` 以 JobInstance 为顶层启动身份。 | Trigger 定义读取、Quartz callback、API 及 Console Workflow 触发 | 新 `scheduled_occurrence` 为 tenant/Workflow/fire 唯一身份；TriggerRequest 绑定 Workflow；根 WorkflowRun 关联 request，JOB 节点再关联独立 JobInstance | `DefaultWorkflowRunLaunchService` → JOB 节点派发 → Worker/查询；控制节点不创建 JobInstance | **有意变更**：Workflow 是唯一顶层日程及运行所有者，Job 保留执行配置；不保留 Job 根触发或 wrapper，系统 Pipeline 按 Job 类型固定。新库初始化及新模板只创建新模型；缺根目标失败关闭。 | I6 → I1/I2；Console/模板由 I5 承接 | R01、R02、R03、R04、R12、R25 | 旧 Job 根 HTTP 404、空库约束/种子、Quartz 复合 DAG、根/节点并发及字段链证据见矩阵；2026-10-16 新库应用至 V283、八应用 UP。历史脚本全面退出、所有配置入口/查询/统计消费者及完整 SIM 仍未联合关闭，不能据本行认定 I1/I2 完成。 |
| APPROVAL-ORIGINAL-REQUEST | `batch-trigger/.../service/DefaultTriggerService.approvePendingCatchUp` 从请求摘要重新构造 LaunchRequest，仅保留日期/Job 与审批参数，发布 Outbox 后即标 LAUNCHED；首次完整参数及区间未保存。 | 定时 misfire/BatchDay 补跑登记；Console 按业务 requestId 审批 | V281 `trigger_request.launch_payload` 保存首次完整请求；V282 原 fire 来源；pending 通过数字外键关联请求，但 API 使用业务 requestId；请求 CAS、pending APPROVED、occurrence REQUESTED 与 Outbox 同事务 | Trigger Outbox → Kafka → 根准入/持久等待；Orchestrator 实际建根后才标 LAUNCHED；Root interval/bizDate 传递至 JOB 子实例 | **有意收紧**：原日期、区间、目标、参数/版本按首次接纳保存，审批仅追加审批元数据；缺失/非法快照拒绝，不重新推算。重复恢复保留原 pending 状态及过期时间。PROCESSING 表示尚未证明已创建根运行；不得用于业务成功统计。 | I6 → I2/I4；审批代理与运营由 I5 承接 | R05、R08、R14、R26、R33 | 首红与修复、Trigger 273 项、受影响 102 项、审批/跨日 Worker E2E 2 项、完整停用 Stage 24 的原 fire/Root/Job 日期区间版本与双 SUCCESS 均见矩阵；Stage 25 同 pipeline 5000 行恢复通过。Worker 按区间实际筛选、配置变更后旧快照执行、全状态统计/归档血缘仍开放。 |
| PAUSE-EXISTING-MISFIRE | `batch-trigger/.../mapper/TriggerDefinitionMapper.xml.updateEnabled` 修改 Job enabled，常规日程查询排除停用定义；`infrastructure/scheduler/TriggerReconciler` 按 Job 重新注册/删除 Quartz，未具有独立 Workflow 义务恢复准入。 | Console Workflow 启停/Trigger 调度暂停；Quartz misfire listener 和持久对账器 | 日程启停与 Workflow 执行开关分别存储；普通定时请求/首次 readiness 观察在 Workflow 行锁内检查；已有 misfire 由原 tenant/Workflow/fire/request/bizDate/version + provenance 证明 | 常规 Quartz 日程停止；独立恢复 Job 不重新启用日程；已审批原 CATCH_UP → Root → 子 Job；租户暂停、Job 执行准入及原审批仍有效 | **有意变更，用户已确认**：完整停用 Workflow 或仅暂停日程都保留并继续恢复既有 misfire，新普通请求不放行。已有日程 readiness 等待按自身准入推进，不以任意调用创建 WAITING 状态绕过暂停；不恢复终态、不把普通 CATCH_UP 自报当授权。旧“停用后等重新启用”的实现/历史验证不再是验收目标。 | I6 → I2；Console/Runbook 由 I5 承接 | R05、R06、R08、R26、R30 | 真实暂停 1→4 首红、数据库双事务锁序及 readiness 定向 57 项，完整 Trigger 273 项；Stage 24 原 2 秒 cron 暂停 1→1、真实 callback 后完整停用且双 SUCCESS/保持停用；dedup 1/1、Outbox 12/12、storm 80/80、残留 0。完整应用进程崩溃、多实例容量与授权浏览器仍未关闭。 |
| DEP-CROSSDAY-VERSION | `batch-orchestrator/.../CrossDayDependencyResolver.java` 按租户、Job、业务日解析 `result_version`，将结果 payload 注入 Workflow 节点输入；原输出缺少稳定 `resultVersionId`，`SPECIFIC_VERSION` 通过最近 50 条展示列表查找，旧版本会被误判缺失。未知策略落入空结果，必需项会无限期等待，可选项会被静默忽略。 | Workflow 配置校验及节点派发 | `workflow_node.cross_day_dependencies`；上游 `result_version.id/business_key/version_no/status` | Workflow JOB/TASK 节点输入、Worker/业务消费者 | **有意收紧配置错误语义**：仅接受已支持的策略、选择器和 scope；未识别值在启用校验及运行期均失败关闭。解析结果携带 `resultVersionId`；指定版本按租户+业务键+版本号精确查询，不经过展示分页且不回退到其他版本。JOB 节点会将冻结的 `crossDay` 参数传入子 Job；inline JSON 被解析为 `output` 对象，handler 按 `output.<key>` 消费且不能重查 latest。当前 resolver 对 `EXTERNAL_REF` / `FILE_RECORD` 失败关闭，外部存储支持仍未实现；只有读取、租户授权、保留期与恢复契约经验证后才能扩大支持范围。 | I6 → I4；配置入口由 I5 承接 | R07、R17、R29、R33 | 最终单测 `./mvnw -o -ntp -pl batch-orchestrator -Dtest=CrossDayDependencyResolverTest,WorkflowGraphValidatorTest,ResultVersionQueryServiceTest test` 48/48 通过；真实 PostgreSQL `CrossDayDependencyReconcilerIntegrationTest` 3/3，包含 51 版本中精确命中第 1 版并确认其他租户无权命中。`ChildJobLaunchSupportTest` 3/3、`DefaultTaskAssignmentServiceTest` 29/29、`TaskDispatchExecutorTest` 5/5 通过；Java SDK 曾忽略 CLAIM 的 payload 而使用消息参数，现已改为 CLAIM payload 唯一业务参数来源，`PlatformHttpClientTest` + `TaskDispatcherTest` 26 项、0 failures/errors、1 skipped；SDK core 全量复跑 472 项、0 failures/errors、14 skipped。用冲突参数证明 CLAIM 胜出，用非对象 JSON 证明失败关闭。跨服务恢复及外部引用消费者未验证。 |
| COUNT-CONTINUITY-SCOPE | `CountContinuityOutboxService` 对实时定义图的所有入边无条件比较上游 outputCount 和当前 inputCount；控制依赖、数据范围及计数单位没有共同证明。 | Worker 节点产出及 `TaskOutcomeDagProgressor` 成功回报 | 节点 output、根运行快照、连续性告警 Outbox | 运维告警及连续性判断 | **实现与组件验证通过，联合验收开放**：按冻结图声明和持久化成功执行定位关系；报告范围/单位相同才核对，缺证据显式未验证。非零差额要求精确排除记账和取证引用，事件按执行 ID 去重，日志在提交后记录；不把报告一致性当作实际消费血缘证明。见 §0.1.2。 | I6 → I4；事件/观测由 I5 承接 | R17、R29、R32、R33 | 完整 SIM run `sim-harness-20261016-092352-ee01e9751` 中三个 Workflow 导入 1 行、随后导出包含既有 5 行共 6 行，产生三条 alert-only 告警；真实 DB 与脚本核对证明范围不同，不能据此判定丢行。修复已通过 33/33 单测与 PostgreSQL 7/7；本地运行包载入及 SIM 尚待复验。历史严格 WARN 审计失败记录保持，A1–A6 及 I6 不关闭。 |
| IMPORT-STAGING-LIFECYCLE | `PrivateTempFiles` 将导入中间文件写入私有子目录，但 `StaleTempFileCleanup` 仅扫描临时根；成功早返回会留下文件，按年龄补扫又缺活跃文件保护。 | PARSE / 分区过滤 / VALIDATE 与 DefaultImportStageExecutor | 当前进程文件句柄与 OS 侧锁；锁不进入 JSON 载荷或执行快照 | Worker 定时清理、其他 Worker 进程及崩溃恢复 | 运行中持锁跨越全部阶段和错误产物收尾；全部成功后回收，失败仅解锁保留取证，过期策略仍以最后修改时间为准；强杀由 OS 释放锁，清理不改业务状态或数据库。 | I4，R14/R15 恢复配套 | R14、R15、R17 | 两个失败断言先复现，修复后 71/71 定向回归与 ImportPipelineE2eIT 1/1 通过；独立 JVM 验证跨进程锁、数据已删的锁保留和强杀后孤立锁清理。最近完整 SIM 在此前版本通过，不自动代表本变更全部场景验收；目标环境/长时容量仍未验证。 |

### 0.1.2 COUNT-CONTINUITY-SCOPE 的实施契约

- 控制依赖边本身不声明数据连续性。下游节点通过冻结的 `nodeParams.countContinuity` 显式启用；
  声明 `enabled: true`、单个 `upstreamNodeCode` 和非负整数 `expectedDelta`（缺省 0）。
  非零差额必须同时声明非空 `deltaReason`，精确核对 `upstreamOutputCount - currentInputCount == expectedDelta`，不使用任意容差。
  多入边只核对声明的那条启用边，不把所有上游计数分别与总输入比较。
- 运行期从 `WorkflowRun.executionSnapshot` 读取声明和边，从已持久化的成功 `WorkflowNodeRun` 读取当前/上游产出及执行身份。
  不读取实时 Workflow 定义；快照、节点或产出缺失/失效时不能切回实时图。
- 产出沿用 `inputCount/outputCount`，增加可选 `inputCountContext/outputCountContext`：
  `{ "scopeId": "不可变的数据集/分区/结果版本身份", "unit": "RECORDS" }`。
  双方的非空 `scopeId` 和 `unit` 必须完全相同。范围证据由实际读取/产出方上报，
  调度器不能仅用同一 `bizDate`、节点类型、启用声明或 readiness 观察结果合成实际消费证明。
  计数只接受范围内的非负整数，拒绝小数、溢出、负数和字符串隐式转换。
- 非零差额还要求下游 `inputCountContext.excludedCount` 与冻结声明精确相等，并保留非空
  `exclusionEvidenceRef`（对应排除/拒绝记录的不可变取证引用）。仅有配置值与原因不能标为匹配；
  记账缺失或不一致必须未验证。这里核对 Worker 报告之间的一致性，引用本身的实际读取/保留与业务合法性仍由对应产出方契约验真。
- 未启用保持无核对；显式启用后产生 `MATCHED`、`MISMATCH` 或 `UNVERIFIED`。
  核对结果与节点产出在同一事务写 Outbox；只有真实可比差异使用原连续性告警事件，
  匹配及证据不足使用独立 checked 事件，并保留原因和执行身份。
  该阶段继续只对账、不翻转作业状态；`UNVERIFIED` 不能作为完整性通过证据。
- 事件幂等身份包含当前和上游的 `WorkflowNodeRun.id`，同一执行重复检查不重复写入，
  后续重试不被此前事件遮盖。数据库唯一约束及重复写入行为须用真实 PostgreSQL 验证。
- 诊断日志只在核对事务提交后记录；回滚不能遗留成功核对或差异告警的日志。Outbox 保持同事务持久化，日志回调不承担事件投递。
- 最低验收：控制边 1→6 不误报；同范围/单位真实差异仍告警；合法差额精确记账；
  缺范围、不同单位/范围、非法计数、非成功上游、无效快照、未声明/禁用边均不能标为匹配；
  重试和重复回报验证 Outbox 身份与事务。内置 Worker 未提供范围证据的链路保留未验证，不能从默认关闭推导端到端完整性。

## 0.2 主链迁移的横向影响与伴随改造

Workflow Run 成为新顶层身份后，不能只检查 Trigger 和 Orchestrator 的启动代码。必须从 `origin/main` 的身份生产点追到所有消费者，核对 SQL/Mapper、事件、API、Console、Worker/SDK、配置导入导出、监控和运维脚本。对 `jobCode/jobInstanceId/instanceNo/triggerRequestId` 的每处读写，标明迁移后应使用 Workflow、节点还是 Job 身份；发现旧根 Job 假设时实施改造，不以“全新数据库”免除新模型内部的完整性要求。

| 影响面 | 需追踪的读写方与可能断点 | 必须决定并实施的配套改造 | 最低验收证据 |
|---|---|---|---|
| 身份、幂等与关联 | Trigger/文件束/外部事件/SDK 的生产者；TriggerRequest、WorkflowRun、NodeRun、JobInstance 的 Mapper/唯一键；结果、告警、补偿、审计消费者可能仍假定根 Job Instance 非空 | 明确每类记录的权威主键、父子关联、重复投递/显式重放的不同身份；删除隐式同编码推导和空值猜测；所有新入口租户隔离 | 全链追踪同一 request/occurrence 从接纳至副作用和查询；并发重复不丢不重，缺失/伪造身份零写入 |
| 状态、终态事件与消费者 | Job/Workflow 状态机、Outbox schema/topic、SLA、通知、Webhook、审计订阅者；Job 成功可能早于 Workflow 失败或持续等待 | 定义 Job 与 Workflow 终态各自的事件和成功口径、顺序/去重键、失败/取消/干运行语义；迁移所有消费者与事件契约，不把节点产物成功当整流程成功 | 真实 Outbox→Kafka 消费；重复/乱序事件下告警、SLA 与外部动作正确且只生效一次；旧字段拒绝或新 schema 契约通过 |
| 运行查询、历史、归档与血缘 | Console 列表/详情、批次日统计、报表、冷热归档、结果版本、Lineage 和数据资产查询 | 明确根运行可能无 JobInstance、一个 Workflow 多个/多次 JobInstance 的投影、分页/排序/过滤/统计口径和归档外键；补 Workflow 与节点关联查询及血缘来源，不制造虚拟父实例 | 单作业、组合/并行、无根 JobInstance、失败/归档样本端到端查询；页数/计数一致，血缘能从消费结果追到生产节点 |
| API、SDK 与自动化调用方 | OpenAPI/生成类型、运维 CLI、SDK/E2E helper、部署/初始化/验收/SIM/SQL 脚本及第三方回调 | 统一 Workflow 根 Launch、状态查询、取消/重放目标与幂等键；更新所有脚本和调用方；非法旧 Job 根调用须明确失败，不能静默 fallback | 全仓调用方清单归零或列明例外；契约生成无漂移；CLI/API 对合法 Workflow 成功、旧目标拒绝；脚本在隔离 PG/HTTP 环境执行 |
| 权限、审批与审计 | Job/Workflow 两菜单权限、审批目标/payload、跨租户查询、人工跳过/补偿/结算操作 | 将资源授权绑定到实际 Workflow/节点/Job 目标；审批批准后重新校验目标未漂移；记录操作者、原因、前后状态和影响范围 | 多角色/跨租户正负例；审批目标篡改、资源变更竞态被拒；审计可从操作追到 Run/Node/Job |
| 配置来源、导入导出与初始化 | Job 表单、Workflow 调度表单、JSON/Excel bundle、租户复制/初始化、模板/种子 | 每个字段有唯一权威所有者；单作业受管 Workflow 可稳定重建；组合 Workflow 引用可移植且导入原子；不静默丢弃仍有效的旧 Job 配置 | 空租户创建→导出→导入/复制→回读→实际运行；非法引用及冲突原子失败；调度与 Job 参数分别读回 |
| 批次日、统计与资源治理 | SLA 起止、配额/并发/优先级/窗口、成功/失败/跳过数量、空日判定、趋势仪表盘 | 明确 Workflow 控制节点是否参与计数、同 Job 多节点如何计费、节点/根终态如何聚合；更新看板、告警阈值和容量查询，不把控制节点计作 Job/Worker | 已知输入样本下前后口径对账；并行、多节点同 Job、等待/重放、空日和部分失败覆盖；claim 前限制与结算口径一致 |
| Job 分区、业务分片与库路由 | `shardStrategy`/partition count resolver、动态 fan-out、`partition_key`/`input_snapshot`、partition idempotency、Worker 运行参数、asset/result-version key、租户 DB shard 目录与 PostgreSQL 分区维护 | 保留每个子 JobInstance 独立的分区数、partition/task/lease/retry 生命周期；Workflow node/run 身份能追溯到实际分区；明确分区配置快照与晚派发/重放边界；相同 Job/date 多节点的结果资产键不得意外互相覆盖，若覆盖是预期需写明选胜规则；按 tenant 路由的根、节点、实例、分区和任务须落在同一物理分片，控制节点不得虚增分区配额 | 固定/动态/自动分区数，单 Job/同 Job 多节点/并行/fan-out/失败重试/重放；对账分区、任务、节点和 Workflow 计数；业务结果多产者冲突及跨日读取；空库迁移、RLS、归档/表分区和分片路由验证 |
| 部署、恢复与环境资产 | Quartz JobStore、Kafka topic/consumer group、DB seeds/Flyway/RLS、对象存储回调、Runbook/仪表盘/告警规则 | 统一新环境的 Workflow 身份命名空间和首次接纳边界；部署/重启后从持久状态续跑；更新回退/前向修复、积压清理和人工恢复步骤 | 新库初始化与重启恢复；Quartz/Outbox/Kafka 中断后续跑；Runbook dry-run 与隔离演练；不允许以直接改业务表作为正常恢复路径 |

执行时先用符号/字段搜索和调用图建立生产者—持久层—消费者清单，再用真实主链追踪抽样对象。每个断点必须产生归属到 I1–I5 的实现任务或明确的不适用结论；测试/静态检查只在对应改造落地后作为验收证据。验证矩阵 R31–R36 覆盖横向契约，不能替代主链功能矩阵 R01–R30。

## 1. 基线与来源保全

整合从 `origin/main` 的 `17c245b934df0ea80234a13fad21814d8ddba29e` 建立，已有迁移最高 V235。没有将两个来源分支直接合并进 main，没有提交、推送或发布整合结果。

| 来源 | 分支 | 捕获 HEAD | 变更路径数 |
|---|---|---|---|
| Workflow / Pipeline | `docs/workflow-unification-plan` | `486ad0bed95df991e572d2021c09f50be70e0365` | 228 |
| 每日连续性 | `fix/daily-trigger-continuity` | `b1b24def1a346e7ea4435267c6564e780d4ddd4a` | 241 |

两份快照于 `2026-10-14T12:38:22Z` 捕获，有 52 个共同路径，合计 417 个来源变更路径。快照包含已跟踪与未跟踪文件、各自 HEAD 版本、补丁和逐文件 SHA-256。按各来源自己的 HEAD 做三方合并，保留 main 后续变更；不是整目录覆盖。

本机可恢复材料位于 `/Users/dengchao/.codex/integration-snapshots/workflow-daily-20261014T123822Z`；整合工作区位于 `/Users/dengchao/.codex/worktrees/workflow-daily-integration/file-batch-system`。这些本机快照没有随文档纳入 Git；后续提交前仍需保留其来源记录。

| 快照清单 | SHA-256 |
|---|---|
| `workflow/manifest.json` | `f06d22c18711384d2bd3ef0d6c2aa47ed7d53931bdedaba4a8b76f7d5ce4b4ce` |
| `daily/manifest.json` | `45fe5c2634ae50eaccd793cba61604d64938f9683bb14d9d0ae9514c3cbe74f3` |

首轮代码整合只修改隔离整合工作区；本次 `.2` 修订同步了两个来源分支的方案、落实清单、TODO 与文档 Changelog，生产代码、测试和 SQL 均保留。暂停其他任务尚未实际执行。建议两来源任务暂停共享契约及代码写入，待下面 I1/I2 确定后再按明确范围恢复；后续增量必须重新记录来源 HEAD/差异，不能从持续变化的目录整包覆盖整合结果。

## 2. 成熟系统借鉴与本系统取舍

以下为 2026-10-14 核对的官方资料；只借鉴语义，不照搬引擎、表结构或默认参数。

| 来源 | 借鉴点 | 本系统落点与限制 |
|---|---|---|
| [Airflow DAG Runs](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dag-run.html) | logical date/data interval 与执行时间区分，catch-up 与 backfill 分开；叶节点成功可能掩盖中间失败 | 日期/区间冻结；END 节点完成不能替代业务成功；手动区间显式校验 |
| [Temporal Schedules](https://docs.temporal.io/schedule) | 日程独立于运行；暂停日程与暂停已启动运行不同；重叠与补跑策略需要明确定义 | 保持现有有界准入与等待，记录 overlap 处置；不默认引入其全部策略，也不默认丢弃第二次合法 fire |
| [Spring Batch domain](https://docs.spring.io/spring-batch/reference/domain.html) | 逻辑运行身份与 execution attempt 区分，隔日重试仍处理原日期的数据 | occurrence/业务身份与运行尝试分开；不能只靠冻结参数证明 Reader 真的消费相同数据版本 |
| [Quartz CronTrigger misfire](https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/tutorial-lesson-06.html) | cron、时区与 misfire 是调度层策略 | 用当前依赖版本的 Quartz 枚举与真实 callback；保留平台审批和业务日逻辑，不能把 seeded-fallback 当真实 misfire 证据 |

## 3. 入口契约、持久化与不变量

### 3.1 入口按职责收敛

| 入口 | 必填与权限 | 持久化结果 | 禁止行为 |
|---|---|---|---|
| 顶层手动/API/定时/事件运行 | 租户、明确 Workflow ID、来源身份、日期依据；API 权限校验 | Trigger request → 一个 Workflow Run，完整冻结上下文 | Job/Workflow 双目标猜测；仅 jobCode 的旧根请求隐式转换 |
| Job 页面“运行” | 后端校验 Job 的受管 Workflow 与当前租户一致 | 使用同一 Workflow 入口，返回可追踪的请求/运行关联 | Console 自建运行规则或后端保留第二条 Job 根路径 |
| Workflow Job 节点 | 由 Orchestrator 内部创建，父 Run/Node、执行 Job、冻结参数明确 | 节点唯一关联 Job Instance，状态/Outbox 原子提交 | 外部伪造父节点上下文；重复启动受管 Workflow；不同节点被 jobCode 去重合并 |
| 文件/事件到达 | 来源身份、事件 ID/到达组、目标路由、业务日/批次；服务身份校验 | 匹配预期与来源投递；后续由唯一运行入口接纳 | 用事件类别代替事件 ID；一个到达被通知和扫描重复运行 |
| retry/replay | 明确来源、授权和原因；区分原尝试恢复与新一轮处理 | 保存来源关系、冻结数据依据和新尝试/运行记录 | 终态直接复活；用当前日期或最新版配置覆盖原证据 |

同一来源 ID 重投携带不同有效载荷时应拒绝冲突并留审计，不能覆盖第一次已接纳的输入；文件内容/版本与选定到达组的完整性证据绑定。

`LaunchRequest`、Trigger DTO、持久化实体、Mapper XML、消息载荷、OpenAPI、SDK 和前端生成类型必须逐字段对照。必填字段、来源/目标合法组合、大小上限和错误响应均有负例。新契约可做破坏性调整，但所有调用方须在同一交付版本更新；旧字段不得被静默忽略后继续执行。

### 3.2 原子性与恢复责任

| 原子单元 | 提交内容 | 崩溃后的事实与恢复者 |
|---|---|---|
| Trigger 接纳 | occurrence/来源投递关联、幂等请求、请求 Outbox | 提交前失败全部回滚；提交后由持久化 Outbox/请求对账继续，原 ID 不变 |
| 根运行接纳 | 请求认领、Workflow Run 与运行快照、起始推进 Outbox | Orchestrator 重投按唯一身份复用，不依赖进程内标记 |
| 节点放行 | 最终依赖结果选择、节点/Job 关联、有效参数快照、派发 Outbox | 失败回滚后仍可释放等待；提交后重复释放不新增节点执行 |
| Worker 结果 | CLAIM/lease 校验、任务/分片/节点状态推进、结果及 Outbox | 过期 lease/乱序回报不能覆盖当前尝试；外部副作用按业务幂等恢复 |
| 计划扫描 | 一批完整 occurrence + 同批覆盖段/水位 | 容量上限、枚举异常或事务失败时保留未完成区间；不得先推进水位 |
| 日切/结算 | 冻结预期证据、状态 CAS 与审计 | Orchestrator 对 SETTLING/失败 catch-up 持久化重扫，不依赖 afterCommit 必达 |

跨服务不能假设一个 Java 事务覆盖全过程。需要分别记录 request/occurrence/run/node/outbox 的关联与状态，故障后通过数据库事实收敛，不通过无限重试掩盖缺失关联。

### 3.2.1 运行内重试与死信的收口（2026-10-16 现场根因修正）

现场确认同一分区在父 Job/Workflow 已 FAILED 后被自动死信重放持续复位，且每次失败创建新死信并重置预算。自动重试只能由实例冻结的 executionPolicy 和 retry_schedule 支配：NONE、零预算或预算耗尽后，死信记录错误分类与人工处理依据，不再分配第二份自动重试预算。终态运行的再次处理通过带来源的新 Workflow Run/attempt 入口进行。

重排队、租约回收和 CLAIM 必须复核父实例及关联 Workflow 的状态；终态拒绝复活。按 ADR-044，暂停停止新的重试派发并保留等待，已经派发的在途任务仍可 CLAIM/续租/回报，不能把暂停当作 404/409 后提交 Kafka offset 造成丢任务。重排队沿用 instance advisory lock，在同一事务内复位 task/partition/step 和写 dispatch Outbox；数据库条件更新再次检查父级，覆盖检查后状态已变化的窗口。死信重放失败时必须回滚业务复位，并独立保留重放次数/失败原因；不能因异常传播把失败记账一起回滚。已有失效的到期工作通过支持的状态迁移收口并保留审计，不直接删表或清 Kafka。

必需验证：NONE/零预算/耗尽不再自动重放；有效预算仍可失败→重试→成功；父 Job/Workflow 终态和暂停阻止重开；重复消息/回报不重复执行或创建死信；重排队中途失败回滚 task/partition/step/Outbox 且失败次数持久化；真实 PostgreSQL 并发及应用重启后无新执行、死信或脏暂存。组件验证通过后再跑一轮完整 SIM 和严格 WARN/残留审计。

### 3.2.2 验收环境与下游恢复的所有权

SIM harness 统一负责 Worker 初始/结束配置；独立场景自行恢复并等待就绪，真实崩溃后的场景内部重启保留。恢复必须保留主失败码，清理失败不能当作通过；临时 chunk 参数只比较恢复本场景注入值，历史 checkpoint 不按租户批量删除。

下游非健康时由调度准入保留 WAITING，恢复必须由成功探针更新健康事实；受健康门控的类型必须有实际生效的恢复探针，检查应用 YAML 绑定结果，避免类型默认值与部署资源漂移。仿真显式定义健康请求与业务请求的协议，故障用例必须核对实际错误原因及持久终态；HTTP 404、缺少 mock 规则或仅“某种失败”不算预期 HTTP 500 通过。定向复验记录场景范围，不能包装成新的全量结果。

### 3.2.3 事务事实与共享缓存

事务内首次创建的数据资产身份只能在成功提交后进入跨线程缓存。不可变 ID 也不能作为提前发布未提交事实的理由；不同业务日使用不同结果版本业务键锁，仍可能共享同一资产身份。回滚后不得留下可命中的失效主键；提交后的缓存复用须保留。用真实 PostgreSQL 双事务屏障、回滚重试和提交后缓存命中对照验证，再以首次创建资产的真实 REPORT 场景核对 HTTP 500 和最终业务结果。业务脚本在 HTTP 内部重试后成功，不能替代错误率与原始日志验收。

### 3.3 日期、覆盖与配置生效

- 业务日、数据区间、原 fire/event 时间和时区分别保存；明确 UTC instant 的毫秒规范化，以及节点继承或显式映射。
- 覆盖必须证明**所有可能归属目标 bizDate 的有效计划区间**已扫描，不能只证明 cutoff 这个时刻落在区间内。节假日滚动、跨午夜、不同 cutoff、一天多次 fire 都进入验收。
- 首次启动设定并审计计划生效起点；缺失起点前证据时保持“未知覆盖”。补齐只处理明确允许且有规则依据的区间，超出范围转人工决策，不伪造连续水位。
- 日程/日历变更明确生效时间，保留变更前已经发生、待审批、在途及终态的计划证据；新版本只替换未来未发生计划。改名不改变身份，禁用不能删除当天已经存在的业务义务。
- 未来未发生计划可重新枚举；已观察机会、请求关联和处置证据不可删除后用当前 cron 重建。日历 SKIP 只说明计划不执行，业务义务豁免仍需明确规则。
- 多实例扫描使用版本/CAS 拒绝迟到的旧扫描覆盖新进度；覆盖按有效版本/区间存证，不能用“最新版本一行”覆盖历史日所需证据。
- overlap、readiness 延迟、日程暂停和运行暂停分别处理；合法的第二个 fire 必须有等待/跳过原因和可恢复记录。补跑枚举、队列和事务批次均有界，遵循已有资源准入。

### 3.4 结算与迟到的并发边界

I3 必须同时修改并审查请求接纳、节点准入和结算入口；只增加一次 `count(*)` 检查不足以解决并发插入。

- 采用同一数据库内按 `tenant + calendar + bizDate` 序列化的事务围栏。优先复用现有行锁/事务 advisory-lock 模式，固定锁顺序；所有可能增加当日工作或终结当日的写路径都必须参与，ShedLock 仅用于减少重复扫描。
- 预期登记、Trigger 请求持久化/绑定、节点释放与结算最后一次检查处于该围栏约束内；新日尚未存在时，先持久化等待，开日时原子纳入未处理请求。不能只让结算器加锁而写入者不遵守。
- 结算先取得围栏，再重读覆盖、义务、请求、运行和失败；有任一未决项则不能 SETTLED。与之竞争的到达要么在结算前纳入，要么明确成为结算后迟到，走审核/catch-up，不可静默归入已结算日。
- 锁只覆盖本地短数据库事务，不跨 RPC、文件读取或 Worker 执行；失败重试保持原请求身份。通过真实数据库屏障控制两种提交顺序并断言业务结果；该证据缺失时禁止启用自动空日结算。

### 3.5 成功、失败与人工动作

Workflow 的 END、输入预期 SATISFIED、请求 LAUNCHED、Job/Workflow 业务成功分别解释。失败分支经过清理节点后成功结束不能掩盖必需业务结果失败；WAIT-only 流程也必须有正确终态。dry-run 的成功不能满足正式业务义务或发布 EFFECTIVE 结果。人工跳过、依赖 override、豁免和结算必须记录角色、原因、对象、原状态及影响范围，不能等同自动成功。

## 4. 全新库结构与切换方案

### 4.1 Schema 收敛

现有 V236–V266 是来源快照的临时编号安排，**不是已验收的发布序列**。按新模型整理本次未发布 SQL：

| 来源结构/转换 | 调整方向 | 必需验证 |
|---|---|---|
| 系统 Pipeline、Job→受管 Workflow、Workflow 调度和快照 | 保留新模型必需结构；以新 API/配置种子创建关系 | 首次配置立即可运行、引用归属与事务回滚 |
| V238/V242/V243/V244 的旧数据回填/转换 | 不作为发布能力；整理未发布脚本时移除旧数据转换目的及其专属测试 | 用新模型配置创建/校验测试替代，业务参数和有效阶段不能丢失 |
| V245 节点 Job 关联与父上下文 | 保留节点归属；去除只服务旧 wrapper 的父 Job/虚拟 task 字段和调用路径 | 同图同 Job 多节点、子流程、计数/配额/终态 |
| V246 及热/冷 Pipeline 身份 | 采用新系统 Pipeline 身份；清理仅供旧配置定义的引用 | 新模型运行归档后查询与版本追溯 |
| V261/V262 occurrence/coverage | 以稳定 Workflow 身份和有效区间设计；重读所有 ON CONFLICT、索引、统计与清理查询 | 并发唯一性、终态保护、历史覆盖、租户边界 |
| V263–V266 输入预期及请求关联 | 明确输入义务与结果义务、规则版本、目标路由、终结条件 | 重复/乱序/迟到、条件分支、失败结算 |

本轮已删除未发布的 V238/V242/V243/V244 旧数据回填/转换迁移及只验证这些转换的专属测试；全新数据库由新 API/配置流程创建 Workflow 关联、调度和 Job 参数。已发布主干迁移保持原样，避免改写既有 Flyway 历史；最终 schema 中所有保留表/列仍须说明新模型用途，不以旧运行兼容作为理由。整理后必须重新执行空库全链初始化、二次 migrate/validate 和核心约束测试。以后新系统版本升级仍须正常维护 Flyway 历史。

### 4.2 部署步骤与检查点

| 步骤 | 动作 | 放行证据 / 失败处置 |
|---|---|---|
| D0 构建冻结 | 固定后端/Console/SDK SHA、镜像 digest、schema 清单及配置版本；冻结本次 I6 基线与台账 | A1–A6、I6 台账和必要改造关闭，R01–R36 适用项有证据；容量目标已由目标环境责任方填值并有测量方法；否则禁止进入切换 |
| D1 环境隔离 | 新数据库与明确隔离的 Kafka topic/consumer group、Quartz、缓存、锁、对象输出前缀和回调地址 | 旧消息/服务无法访问新运行域；不能仅换 consumer group 后读取旧 topic |
| D2 新库初始化 | 受支持 PostgreSQL 空库初始化；二次 migrate/validate；通过新配置入口创建租户/日历/Job/Workflow/预期 | schema、RLS、外键、唯一性、种子幂等及配置引用通过；失败保持未接流量 |
| D3 停旧与确认边界 | 停止旧入口接受新工作；生成切换台账，逐项处理活跃 Job/Workflow、未完成 TriggerRequest、待审批 misfire、依赖等待/catch-up、文件/事件到达及已发起外部副作用/回调 | 每项明确“在旧环境完成 / 取消并留痕 / 等待收敛 / 经核对后由新环境重新接纳”，指定业务日与幂等身份；不得静默把旧未决工作重发到新环境；旧数据原位保留 |
| D4 新链路预演 | 仅在 I6 台账、必要改造及 R25–R36 适用验收关闭后，新环境匹配版本启动；定向租户 dry-run 后经受控真实场景验证 | 观察请求→Run→节点→任务→业务结果→结算；dry-run 不能替代真实副作用验收；任何语义缺口退回 I6，不以预演发现代替基线迁移 |
| D5 开放与观察 | 按已验场景逐步开放新系统业务；旧入口持续停用 | 至少覆盖一个完整开日→cutoff→结算周期、实际日程周期以及故障恢复；观察窗口按目标日历确定 |

容量与恢复目标不得留作“有记录”或由实现者代填。D0 前由业务/平台责任方基于目标环境填写下表；没有业务量依据时标为待决并阻断 D0，不得虚构数值。

| 目标项 | 目标环境需填写的基线/目标 | 验证口径 |
|---|---|---|
| 租户、Workflow、Job/节点、日程数量 | 待目标责任方确认 | 种子/生产配置清单及峰值分布 |
| 每日 fire、事件/文件到达量与峰值并发 | 待目标责任方确认 | 峰值窗口真实触发/到达负载 |
| 最大停机时长与积压恢复时限 | 待目标责任方确认 | 按停机窗口注入积压并实测追平时间 |
| DB 锁等待、连接池/队列、Outbox/Kafka 积压预算 | 待目标责任方确认 | 指标、阈值、观测窗口和失败动作 |
| catch-up/replay 最大并发、租户公平与重试放大上限 | 待目标责任方确认 | 多租户竞争及故障重试负载 |
| 运行/依赖/审计/结果版本保留期及归档读 SLA | 待目标责任方确认 | 保留策略配置、归档量和查询时延 |

工作顺序：先完成 I6 基线台账与依赖语义裁定（对已实施代码做回溯映射），再按 I1–I5 责任切片补实现；I6 关闭并满足 D0 的容量目标后才可 D4。实现中的独立缺陷可以继续修复，但不得以修复进度跳过门槛。

### 4.3 回退分界

- **新系统尚无正式接纳/业务写入**：保持入口关闭，修复或重新准备隔离环境；重新启用旧系统属于业务切换操作，须确认它不会处理新系统已经产生的副作用。
- **新系统已有正式接纳或外部副作用**：停止新增，保留新库、Outbox、结果和审计，优先修复后在新模型恢复。不得删除新库、回灌旧库或重放旧队列来“回退”；如确需业务回切，单独核对已处理输入、幂等身份和外部交付。
- 回滚到旧二进制不是本方案承诺。新模型后续版本的应用回退也必须声明 schema/载荷支持范围；无支持证据时采用前向修复。

## 5. 当前缺口、实施顺序及禁止跨越的门槛

| 阶段 | 当前缺口 / 工作 | 关闭条件 |
|---|---|---|
| I0 来源与决策 | 已固定来源快照；本轮收敛 ADR-048、全新库及无旧兼容范围 | 两份来源方案标明当前范围；后续增量不覆盖整合结果 |
| I1 契约/Schema/测试基线 | 未发布旧回填/转换脚本及专属测试已删除；顶层 Trigger DTO 已移除 JobCode，只接受 Workflow ID；受理前以 tenant + Workflow ID 校验定义存在且启用，WORKFLOW wrapper 已拒绝，V270 排除新建 WORKFLOW Job；Console `/api/console/jobs/trigger` 与 `/batch-trigger` 已退役，作业列表/详情按 `defaultWorkflowDefinitionId` 调用 Workflow trigger，无受管关联 fail-closed；OpenAPI、生成类型和当前 API 协议同步。SDK 自托管 E2E 也显式 provision 单作业 Workflow，以 Workflow ID launch，并按根 request 精确查找实例。Excel 新模板/导出不提供租户 Pipeline sheet，旧 Pipeline sheet 明确失败，validator 不读取 Pipeline Mapper、不转换 Job 参数；Pipeline 写入/复制/ConfigSync/Job Bundle 与公共契约已退出。V271 移除 `workflow_node.related_pipeline_code` 并收紧节点约束；FILE_STEP 从 API/Excel/配置包/前端设计器移除，写入显式拒绝，图解码 fail-closed；有效种子改为 TASK。后端配置包定向测试 107/107、Console Job 根入口退役与 Workflow trigger 测试 3/3、前端 Job API 单测 21/21、typecheck、API 漂移和目标 ESLint 通过；旧入口在独立 Controller 测试中返回 404。本轮 Docker/Testcontainers 可用：`LocalFlywayPlatformMigrationsIntegrationTest` 1/1 从空 PostgreSQL 17.11 执行 267 个迁移至 V272，回放 `platform_seed.sql` 与 `platform_edge_cases.sql`，并断言 TriggerRequest 均有 Workflow ID/日历且无 Job 根目标、PipelineInstance 均使用系统 Pipeline 身份；同一测试也证明可执行 Job 根请求被拒绝，而无 Workflow 的终态 CATCH_UP/REJECTED 审计行可保存，不阻断恢复扫描；`ConsoleHttpIntegrationTest` 9/9 真实 HTTP 集成通过，包含两条旧 Job 根触发路由返回 404。真实浏览器链路仍未执行；Squawk 已对 33 个新增/修改迁移检查并通过（0 issues）。Trigger Workflow-only 单测及 Mapper tenant guard 30/30 通过；seed 验证脚本新增旧 Job 根拒绝及根 WorkflowRun 派生清理，常规 05-load 已将 Job 目标替换为受管 Workflow ID。I1 仍未关闭：其余历史模拟脚本/fixture 需要逐项转到 Workflow 根身份，空库迁移与种子重放已验证，但 Quartz 停机/恢复和剩余历史脚本的真实执行仍待完成 | 新入口合法组合、拒绝旧目标；旧入口/队列不可接纳；空库初始化/约束通过；变更清单可审查 |
| I2 统一运行与计划 | occurrence、coverage、misfire pending 的身份已切为 Workflow；文件束已改走 Workflow 根入口。真实 PostgreSQL 并发测试发现并修复根节点首次派发竞态；misfire callback 现先将预建 occurrence CAS 为 `MISFIRE_PENDING`，再异步注册按 Workflow + fireTime 稳定身份的恢复 Trigger；`TriggerReconciler` 扫描并恢复持久 pending。真实 Quartz/PostgreSQL 集成覆盖 callback→request，以及关闭原 Quartz Scheduler 后新 Scheduler 从同一 JDBC JobStore 和 occurrence 台账恢复；两台共享 JDBC JobStore 的 Scheduler 并发扫描同一 pending occurrence，最终只产生一条恢复 Trigger 和一个 request；handoff 丢失窗口验证 listener 已持久化义务后丢弃进程内 `TaskScheduler` 回调，新 Scheduler 仍从台账恢复并只接纳一个请求。新增完整 Trigger Spring 应用上下文关闭/重启集成验证：首次应用关闭销毁 Quartz 与进程内调度器，第二次应用连接同一 PostgreSQL，启动对账从持久 occurrence 恢复且仅接纳一个请求；测试用例使用生产 Quartz schema 初始化策略 `never`。当前暂停语义：`schedule_enabled=false` 或 Workflow 完整停用后停止新增普通定时接纳，已持久化的 `MISFIRE_PENDING` 保留并继续按原 fire 恢复，人工策略仍需原待办审批，不重新启用日程或 Workflow；普通 CATCH_UP 自报不能绕过。旧“等待重新启用”规则已取代。真实 PostgreSQL/Quartz 定向与最新八应用 Stage 24 验证完整停用后唯一请求、原区间/版本及 Root/Job 最终成功。101 条 pending 的 PostgreSQL Mapper 验证证明先完成 100 条后第 101 条可进入下一页；这只验证分页推进，不是 Quartz 端到端容量/追平证据。仍待 OS/容器级进程终止演练、按 D0 业务目标验收积压恢复速率与追平、Quartz 复合图及旧模拟 fixture/运维脚本迁移。 | A1/A2/A5：全链同一 Workflow 身份；OS/容器进程终止恢复、并发恢复及有界积压下无漏触发或重复业务执行；旧根路径退出 |
| I3 义务与结算 | coverage 定义、批日根运行指标和未跟踪 EVENT/MIXED 门禁按 Workflow；REQUESTED 在 WorkflowRun 创建前阻塞、创建后由 WorkflowRun 状态接管。文件/事件定义与 occurrence 的生产键已迁至 `workflow_definition_id`，受管 Job 文件束解析后以 Workflow ID 记账。开日只为 `workflow.enabled=true` 的定义创建未来预期；禁用停止新增、不删除已创建义务，`schedule_enabled=false` 不影响文件/事件预期。新增 PostgreSQL 集成用例覆盖启用→禁用→重新启用的跨业务日行为。`SETTLE_EMPTY` 新增显式处置：在业务日围栏下锁定未履行的 EXPECTED/ARRIVED 项、CAS 为 CANCELLED，并在同一事务与批日终态共同写入包含操作者、理由及逐项义务身份/原状态/时间的审计；预期项相关事务在受保护 SQL 前设置租户 GUC。`BatchDayMetricsMapperIntegrationTest` 的人工空日场景及 `NOSUPERUSER NOBYPASSRLS` 角色下的预期项租户隔离场景均通过（PostgreSQL 17.11）；R11 请求先提交顺序调用生产结算器，曾暴露并已修复未决请求仍按 JobCode 关联的漏检；结算先提交顺序验证后到普通请求由数据库拒绝。覆盖水位 fencing、节点释放/终态全状态、文件/事件预期终结及跨服务恢复仍未完成 | A3：同一权威计划、完整覆盖、Workflow 业务结果及全部并发围栏通过；到此才能启用自动空日结算 |
| I4 执行与恢复 | 实际消费版本未固定；新模型参数、Pipeline、WAIT/子流程、配额和归档需联合核对 | A4/A5：支持的读取真实 pinning，未知消费者明确；新模型恢复不变更业务日或版本，不重复外部副作用 |
| I5 配对交付/切换 | Console 的 Workflow/Job/Pipeline 改动已迁入同名整合分支，且已从本整合后端 OpenAPI 重生成类型；SDK/配置包剩余契约仍待联合核对，Console 真实 HTTP 集成现已通过；浏览器真实链路、跨服务故障和部署演练仍未执行 | A6 及全部回归矩阵有完整证据，D0–D5 可执行；之后才具备发布评审条件 |
| I6 批量调度语义与主链横向影响迁移 | **进行中，阻断 D4/D5**：基线已固定；逐项台账已覆盖 Workflow 日程/根身份、首次审批快照、停用后既有 misfire，以及跨日结果版本身份/未知策略（见 §0.1.1）；各行保留尚未完成的联合验收，其它语义域仍未完成盘点和 I1–I5 映射，不得进入 D4/D5 | R25–R36 对应场景具备业务结果与恢复证据；所有变更有审计、权限、幂等、日期、身份和告警口径；不适用项有证据；此门槛关闭前不得进入 D4/D5 |

2026-10-15 I1 跨端补充：Console 作业类型列表和显示不再将 `WORKFLOW` 当作 Job 类型；更新整合后端 OpenAPI 说明并从同一 OpenAPI 重生成前端类型。后端 JobType/Workflow 快照/启动校验定向测试、前端完整单测与生产构建通过。I1 仍未关闭：旧 Job fixture/运维入口清理及 Docker 可用时的最终空库迁移/重放验证尚未完成。

2026-10-15 I1 模拟入口补充：发现部分已改为 Workflow 根请求的仿真仍从 `trigger_request.related_job_instance_id` 取结果，造成新旧契约混用。4 日仿真按根 WorkflowRun 等待；stage2/3/4、stage6、原子负载、文件束与 SDK Worker 的主要结果查询已改为通过 `workflow_run → workflow_node_run.job_instance_id` 定位 Job/Task，并清除这些模拟 SQL 对旧请求关联字段的依赖。Shell/Python 静态检查通过，但 Docker/PostgreSQL 不可达，SQL 与真实脚本尚未执行。stage6c/d 的 Quartz Job 管理 API/fixture 已在后续切片改为 Workflow 所有权；其余历史脚本与全套真实数据库/Quartz 验收仍未完成，I1 未关闭。

2026-10-15 I1 Trigger 调度仿真补充：stage6c/6d fixture 改为显式定时 Workflow + JOB 节点，移除为仿真复制租户 Pipeline 的做法；schedule、misfire pending 唯一身份、审批请求、Quartz identity、暂停/恢复和结果查询均改用 Workflow 身份，管理 API 调用改为 `/management/workflow/*`。`TriggerManagementControllerTest` 增加旧 JobCode 路由不存在断言。`bash -n`、两份仿真 Shell 的 warning 级 ShellCheck、`git diff --check` 通过；`./mvnw -o -ntp -pl batch-trigger -am -Dtest=TriggerManagementControllerTest,TriggerSchedulerFacadeTest -Dsurefire.failIfNoSpecifiedTests=false test` 为 BUILD SUCCESS（18/18，无失败/错误/跳过）。SQL fixture 尚未在 PostgreSQL 执行，stage6c/6d 真实 Quartz callback/misfire 与 catch-up replay 未运行；I1 及 I2 继续开放。

2026-10-15 I1 状态写入与 Quartz 旧身份拒绝补充：全模块测试首跑发现 Mapper UPDATE 中 8 个固定状态赋值；按 `MapperStatusWriteConventionTest` 将 misfire、occurrence 和依赖等待状态目标改由 Java 语义方法传入，中心审批拒绝条件仍作为 WHERE 过滤。QuartzLaunchJob 现在要求非空租户、正 Workflow ID 和 WorkflowCode，缺身份任务失败关闭，新增负例。首次全模块运行还因 Docker 不可用产生 6 个 Testcontainers 启动错误；状态守卫与定向单测修复后，`MapperStatusWriteConventionTest` 2/2，Trigger/Orchestrator 定向集合 43/43 通过（无失败/错误/跳过），Spotless check 通过。未能在当前环境运行数据库 Mapper 集成或 stage6 仿真。

2026-10-15 I1 Console 根触发入口退役补充：全仓契约扫描发现 Console 仍对外暴露 JobCode `/api/console/jobs/trigger` 和 `/batch-trigger`，虽内部已转成 Workflow ID，外部仍保留第二套根目标契约。删除两路 Controller/API/OpenAPI 操作和仅供该入口使用的服务/请求/响应模型；作业列表与详情改由 `defaultWorkflowDefinitionId` 调用现有 Workflow trigger，缺关联时 fail-closed；DryRun 继续使用独立计划 API。API 协议和生成类型同步。Console Job Controller/Workflow trigger 单测 3/3，前端 Job API 单测 21/21、全量 Vitest 891 项、typecheck、build、API drift 与目标 ESLint 通过，OpenAPI 路由核对为 412 条一致；旧路由 standalone 测试返回 404。SDK E2E Shell/SQL 静态门禁通过。Docker 不可用，真实 Console HTTP/SDK E2E 集成未执行，I1/I5 继续开放。

2026-10-15 I1 Console Trigger 运维链收敛：继续按 Workflow 唯一调度身份核查发现，Console 运维代理仍调用通用 Job 管理路由且 DTO 仍解析 `jobCode`，与 Trigger 仅暴露 Workflow 管理 API 的实现不一致。运维路径参数、状态/动作 DTO、内部代理和配套前端统一切到 `workflowCode`；新增真实 HTTP JSON 代理测试校验目标路径、租户/Workflow 查询参数及响应映射，OpenAPI/生成类型/协议文档同步。定向后端 17/17，Console 全量 Vitest 151 files/891 tests、typecheck、i18n、API drift、目标 ESLint、生产构建和 OpenAPI 路由校验通过。Docker 不可达，真实 Console HTTP/Quartz 操作及已部署页面未验证；I1/I2/I5 仍开放。

2026-10-15 I1 配置包 fixture 收敛：`sim-harness` 实际上传的四份工作簿原先仍包含已退役 Pipeline sheet，fixture fixer 还按旧租户阶段表维护数据，字段指南与 OpenAPI 仍宣称 12 sheet。现四份配置包、生成/追加脚本及检查器统一为 10 个当前 sheet；旧 Pipeline sheet 继续由导入边界明确拒绝。配置指南也不再公开两个退役表，OpenAPI 和配对 Console 生成类型同步。验证通过不表示已执行完整 SIM 的 apply、文件作业或真实用户浏览器流程。

2026-10-15 I5/UI-SEAM-1–3 代码闭环补充：通知渠道请求以租户及 generation 防止旧响应覆盖，表单/行操作绑定租户身份；自助重跑变更租户/作业/业务日会清空目标并废弃旧实例搜索结果，Orchestrator 执行时校验实例实际 Job/date；配置同步导入补齐资源队列、批次窗口、业务日历、配额策略、告警路由映射，并扩展九类 summary/审计统计，Console selector 删除已退役 Pipeline 类别。两侧新增本地单测通过，OpenAPI/生成类型同步。真实浏览器、审批后执行及空目标数据库逐类配置回读仍未完成，不能关闭 I5 或相关 UI-SEAM。

2026-10-15 I1 状态读取旧身份关闭：状态列表此前仍将旧 `tenant:jobCode` Quartz key 作为有效调度，并从旧 identity 回填 JobCode。现在仅解析 `tenant:WF:workflowCode`，空租户、非 WF 类型和空 WorkflowCode 均跳过；WorkflowCode 以 Quartz identity 为准，避免 JobDataMap 覆盖身份。测试明确验证有效 Workflow 可见、跨租户和旧 Job identity 不读取，`TriggerStatusQueryFacadeTest` 3/3，Spotless 通过。I1 仍需全量空库/Quartz 恢复验收。

2026-10-15 I1/R11 补充：空库种子现按 Workflow 根 ID、日历及系统 Pipeline 身份回放并断言；真实 PostgreSQL 测试发现 V269 会拒绝无受管 Workflow 时应保存的终态 `CATCH_UP/REJECTED` 审计行，现仅放行该不可执行状态，普通 Job 根请求继续被拒绝。两份 P2 压测汇总及清理 SQL 改由 WorkflowRun/节点关系定位执行，空库测试实际执行这些 SQL。迁移 safety lint 33 文件 0 issues，`LocalFlywayPlatformMigrationsIntegrationTest` 1/1、`BatchDaySettleSchedulerTest` 14/14、`KafkaTriggerEventPublisherTest` 4/4 通过；Quartz 停机/恢复及真实压测仍未验收，I1/I2/R11 继续开放。

依赖顺序固定为 I1 → I2 → I3/I4 → I5。I3/I4 可以在已冻结契约下分别实现，但不得同时改共享 DTO/迁移或在同一数据库并发运行破坏性验收。每个切片先列行为变化和负例、补最小失败用例、实现、审查、重跑失败项及受影响集合。任何共享字段变动都重新核对读写者、消息和生成类型。

初始失败中，Trigger 的旧注册断言/Job Quartz key 已按 Workflow 语义修正；Orchestrator 的旧 `launchService` 引用和 catch-up mock fixture 已修复。修复后的选择性测试通过，但现有 Planner 单测和模拟测试不足以证明复合图、并发去重及真实停机恢复，因此 I2 仍打开。

### 当前实现进度（2026-10-14）

仅在隔离整合工作区实施。Occurrence 唯一键改为 `tenant_id + workflow_definition_id + scheduled_fire_time`，schedule version 改取 Workflow version；coverage 及 MANUAL_APPROVAL misfire pending 改由 Workflow 身份约束；Quartz identity 不再接受 Job fallback；空日 schedule coverage 扫描 Workflow 定义。批日指标改为汇总根 WorkflowRun，REQUESTED occurrence 以 `workflow_run.trigger_request_id` 交接给根运行；相关 PostgreSQL 集成已增至 15/15。新增 PostgreSQL 事务围栏，按 tenant/calendar/bizDate 串行化开日、结算及预期/Trigger 接纳；Trigger 数据库防线拒绝关闭日普通触发，子 Job 使用根 Workflow 冻结日历。Settlement/expectation 定向用例 29/29、JobNodeDispatchIntegrationTest 10/10、启动入口单测 6/6 通过。配对 Console 的现有 Workflow/Job/Pipeline 改动现已迁入同名 `feature/workflow-daily-integration` 分支，生成类型基于本整合后端 OpenAPI；前端 API 漂移、typecheck、lint、892 个单测和生产构建通过。两种事务提交顺序的业务结果、Quartz 停机恢复、真实浏览器链路和完整旧入口清理仍未验收；此不代表业务闭环完成。

迁移 V261/V262 仍处于未发布整合阶段，因此按全新数据库直接定义 Workflow 主键/FK；V267 将 misfire pending 绑定 Workflow，并保留可空 job_code 仅作执行目标上下文。移除未发布 V238/V242/V243/V244 旧数据回填/转换；V268 新增 advisory fence，V269 让 Trigger 入库共享围栏并拒绝未显式补跑的关闭日接纳。空库 Flyway 验证 268 个迁移并重复执行成功。尚未证明 occurrence 水位并发、旧扫描 fencing、复合图/同 Job 多节点执行、Workflow 终态结算的全状态覆盖、文件/事件预期终结状态、R11 两种业务提交顺序及部署后恢复；自动空日结算不得据此签收。

2026-10-15：I3 继续将文件组/外部事件预期定义、逐日 occurrence、空日未跟踪触发门禁从 Job key 转为 Workflow ID；`BundleArrivalLauncher` 解析受管 Workflow 后按该 ID 更新预期。`./mvnw -pl batch-orchestrator -am -DskipTests test-compile` 通过；相关注册/满足/文件组配置/到达启动单测 26/26 通过。更新了 internal OpenAPI 请求字段及 PostgreSQL 集成夹具。Flyway 静态校验、internal OpenAPI YAML/路由比对、tenant RLS 检查通过；新建/调整的 V263–V265 迁移经 squawk 单独检查 0 issue。整合分支 32 个迁移的完整安全 lint 仍失败（31 warnings，涉及 8 个迁移文件，需按全新库边界逐项审查）。PostgreSQL 集成尚未执行；Docker daemon 和本地 PostgreSQL 均不可用。I3/A3 保持未验收。

2026-10-15 I2/R05 occurrence 旧扫描 fencing：PostgreSQL 集成先行用例证实旧 schedule version 可将同一未来 `PLANNED` occurrence 的版本和业务日覆盖回旧值。修正 `ScheduledOccurrenceMapper.xml`：仅允许不低于现有版本的计划更新；coverage upsert 拒绝旧版本，并对同版本重叠扫描以 `greatest(coverage_through_at)` 保持水位单调、对新版本/新日历重置区间。修复后 `ScheduledOccurrenceMapperIntegrationTest` 全类 8/8（PostgreSQL 17.11 Testcontainers，空库执行 266 个迁移）通过，覆盖旧版本迟到、同版本较短区间和新值保留；双事务屏障证明旧事务先读版本 4、版本 5 提交后再迟到写入，最终 occurrence 与 coverage 均保持版本 5；真实 Spring planner 在 occurrence 写入后遇到 coverage 持久化异常，验证同事务回滚两者。Quartz 回调到 settlement 全链验收仍缺；R05/I2 仍开放。

2026-10-15 R06 日历/调度变更与 coverage：空日门禁拒绝配置 revision 变更前的 coverage；planner 在读取配置前捕获 revision，在写 coverage 前锁定复核，变化则整批回滚。coverage 过期后按数据库配置更新时间重扫至至少两天 lookahead，只更新生效时点后的 `PLANNED` occurrence，保留历史 coverage 起点和已观察状态。真实 PostgreSQL 延迟恢复测试验证 cutoff 变更后已到期但仍未观察的 occurrence 被重算，生效前义务保持不变。继续核对禁用/再启用发现，重新启用后同一未来 fire 会因唯一键冲突一直保留 `SUPERSEDED`，令覆盖证明通过但 occurrence 不阻塞结算。V272 为 SUPERSEDED 转换增加不可变历史，planner 在新配置重扫时允许匹配当前计划的未观察 SUPERSEDED fire 回到 PLANNED；真实 PostgreSQL 测试验证重新启用重新计入 blocker，并记录 `PLANNED→SUPERSEDED→PLANNED` 两次处置。`BatchDayMetricsMapperIntegrationTest#shouldRequireCurrentScheduleCoverageThroughCutoff` 1/1；`ScheduledOccurrenceMapperIntegrationTest` 13/13、`TriggerScheduledOccurrencePlannerTest` 2/2 合计 15/15；空库 PostgreSQL 17.11 应用 267 个迁移至 v272。日历 disable/re-enable、节假日 SKIP 义务终结、首次开日和完整 Quartz→结算仍未验收；R06/I2/I3 继续开放。

2026-10-15 R26 重开豁免义务：将 occurrence generation 与唯一身份并入未发布的 V263 建表迁移；`SETTLE_EMPTY` 后 REOPEN 只为最新一代 `CANCELLED` 义务插入下一代 `EXPECTED` 记录，历史终态保留，且两项操作共用业务日 advisory fence。操作审计保存新建数量；人工 SKIP/REOPEN 现在要求理由。真实 PostgreSQL 17.11 空库迁移至 V272 后，`BatchDayMetricsMapperIntegrationTest#shouldCreateNewExpectedWorkGenerationWhenReopeningSettledEmptyDay` 1/1 通过，覆盖不可绕过 RLS 角色下的取消、重开、旧终态保留及新代阻塞。

2026-10-15 R26 假日停批与下游依赖：新增真实 PostgreSQL 联合用例，证明上游假日按 `SKIP` 建立 `SKIPPED` 批量日后，下游 `WAIT_SETTLED` 不会把跳过当成成功；下游 wait 保持 `WAITING`，仅有操作者和理由的人工 `RELEASE` 才建下游日并结束等待。另以真实 `TriggerService` 验证普通 `SCHEDULED` fire 与 `CATCH_UP` fire 命中假日均记录 `SKIPPED_CALENDAR` occurrence、空业务日和空 request ID，且不创建 TriggerRequest。两个 PostgreSQL 用例均从空库应用当前迁移通过；详细命令、首次失败原因及证据边界见验证矩阵。R26 仍开放：已结算批日上的迟到触发/输入处置、滚动日期 occurrence 身份、相邻日期依赖，以及重开后从触发到 JOB 节点派发与恢复尚未联合实现验收。

2026-10-15 R26 已结算日迟到 fire 处置：普通 `SCHEDULED` fire 命中已结算批日时，V273 用专用 SQLSTATE `BDA01` 标识数据库准入拒绝；Trigger 事务完整回滚后，以 `CLOSED_DAY_REJECTED` 和必填原因持久化原 fire identity，明确要求显式 `CATCH_UP`/`RERUN`，不创建 TriggerRequest。随后相同 fire identity 的显式 `CATCH_UP` 可通过准入，occurrence 推进到 `REQUESTED` 并绑定 request，同时保留先前拒绝原因。数据库约束校验拒绝状态必须有理由且无 request ID。`TriggerServiceIntegrationTest` 在全新 PostgreSQL 17.11 上从 268 个迁移到 V273，8/8 通过，覆盖普通/补跑假日 skip、关闭日拒绝及同 fire CATCH_UP 收敛；`ScheduledOccurrenceStatus` 和 Mapper 状态转移已同步。随后同一已结算日的显式 Workflow `RERUN` API 准入也以同场景验证为 ACCEPTED/RERUN，且不覆盖原计划 fire occurrence 与 CATCH_UP request 关联。

2026-10-15 R26 Console occurrence 可见性：批次日窗口 Console API 新增 `scheduledOccurrences`，以后端 tenant + calendar + bizDate 条件查询 WorkflowCode、scheduleVersion、fire time、状态、requestId 与 lastRejectionReason；前端详情新增中英文 occurrence 表格，突出关闭日拒绝并展示其持久原因。后端 `BatchDayMapperIntegrationTest` 验证 tenant/calendar/date 隔离和拒绝原因，`ConsoleOpsQueryServiceTest` 验证 DTO 映射，合计 5/5；空库 PostgreSQL 17.11 到 V273。Console `BatchDayWindow.test.ts` 验证状态/原因可见，前端全量 157 文件/911 tests、typecheck、i18n、生成类型漂移、目标 ESLint 和生产 build 通过，`make check-openapi` 412 routes 一致。证据止于读链和本地构建；从 occurrence 直接发起可审计补跑、真实 HTTP/权限浏览器旅程、相邻日期依赖、节点恢复及部署仍未验收，R26 开放。另 `check-openapi-breaking.sh origin/main` 仍报告 31 个 ERR，来自本整合分支移除的旧 Job 根 API/字段；不能将其视为通过或豁免，版本化/发布契约仍待解决。

2026-10-15 R26 Console 处置契约核对与后续落实：早期核对指出普通 Workflow trigger 缺少计划 occurrence 身份和操作理由，不能用于补跑。后续已增加独立的计划 occurrence `CATCH_UP` 路径：Console 要求操作者理由并以认证主体提交，针对 tenant/Workflow/fire time/bizDate 和幂等键调用 Trigger；Trigger 按日历策略接纳或建立中央审批，并将 request 与 occurrence 身份关联。真实 PostgreSQL 的 Trigger 状态/并发用例、Console JWT/CSRF 授权 HTTP 用例和前端组件目标绑定用例分别留证，详见验证矩阵 R26 的 2026-10-15 记录。已有运行的 `RERUN` 仍以 WorkflowRun 身份单独审计，不能用于冒充被拒绝 occurrence 的补跑。当前仍未形成 Console HTTP→Trigger 持久化→中央审批执行→生产 reconciler→WorkflowRun/JOB 副作用的完整跨服务闭环，也未验收相邻日期依赖与节点恢复，因此 R26 保持开放。

2026-10-15 R26 Workflow RERUN 关闭日准入：在同一全新 PostgreSQL 集成场景中，已结算业务日的显式 Workflow `RERUN` 通过 Trigger API 接受为 `ACCEPTED/RERUN`；验证人工重跑不改写该计划 fire occurrence 当前绑定的 CATCH_UP request。执行 `./mvnw -o -ntp -pl batch-trigger -am -Dtest=TriggerServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，8/8 通过，空库应用 268 个迁移至 V273。该证据止于 Trigger request/outbox 准入，不代表 Orchestrator/Worker 运行完成；Console 操作与观察、相邻日期依赖、节点派发恢复仍未验收，R26 继续开放。

2026-10-15 R26 完整服务链验证推进：Console→Quartz `QuartzLaunchJob`→Trigger HTTP→中央审批/生产 reconciler→Kafka/outbox→Orchestrator→Import Worker 的跨服务 E2E 现已到达业务完成；Worker fail-fast 注册并确认 ONLINE，catch-up 链创建唯一 WorkflowRun/JOB/JobInstance，Worker 消费唯一客户输入后 `job_task`、JobInstance、WorkflowRun 均为 SUCCESS，业务库客户记录恰好一行。第一次加强终态断言发现 batch-test-support 将 Outbox 及资源等待派发轮询设为 600000ms，导致节点 RUNNING；本用例覆盖至 200ms 后重跑 1/1 通过。相邻既有 Import 业务入库 E2E 与复合 Workflow Quartz E2E 2/2 通过。该链路暴露并由 V275 修复已获准 WorkflowRun 的 child `EVENT` request 无法继承关闭日准入的问题。V275 保持 root 准入规则不变，仅允许活动父 WorkflowRun 的后代在同一已准入业务日启动，并由数据库强制父子 bizDate 一致；关闭日的子请求必须由活动父 Run 授权，显式 CATCH_UP/RERUN 只适用于顶层请求。E2E 验证父子 bizDate 不一致，以及终态父 Run 在 SETTLED 日提交同日子 CATCH_UP，均被拒绝且零写入。端到端及相邻证据见验证矩阵；认证/CSRF 与该 E2E 同场验证、Quartz 持久化停机恢复、多实例竞争及相邻日期依赖仍未完成，R26/A1 继续开放。
2026-10-15 R26 跨日等待恢复输入：检查 WAITING_DEPENDENCY 的恢复路径发现节点仅记录等待原因，Reconciler 后续用 null 派发，导致根请求参数和条件边上下文丢失。V276 为热/归档 workflow_node_run 增加加密 source payload 快照；首次 WAIT 写入使用现有 SecretPayloadProtector，依赖解决后解密并重派发。21 项相关单测、真实 PostgreSQL 空库迁移及 MyBatis JSONB 读写 3/3、归档冷热表结构 6/6、Mapper XML 1/1 通过；迁移安全 lint 零问题。随后联合 E2E 暴露根 Workflow 没有 relatedJobInstanceId 时恢复器会提前返回；按 WorkflowRun tenant/bizDate 解析并重派发，去除旧父 JobInstance 依赖。单测先失败后修复 8/8，真实 PostgreSQL 根 Workflow 恢复集成 1/1。

2026-10-15 R26 跨日依赖恢复完整 Worker 闭环：新增真实 Console/Orchestrator/Trigger/Import Worker E2E。用例先由 Trigger API 启动根 Workflow，确认 JOB 节点进入 WAITING_DEPENDENCY 且尚无 JobInstance；之后才写入前一业务日 EFFECTIVE result_version，观察生产 `CrossDayDependencyReconciler` 释放节点，Worker 消费恢复后的根参数与固定结果输出，最终断言 JobInstance/WorkflowRun 均 SUCCESS 且业务客户记录恰好一行。最初用例错误地在启动前插入有效结果，导致业务链直接成功而永不等待；按业务时序纠正后执行 `./mvnw -o -ntp -pl batch-e2e-tests -am '-Dtest=WorkflowOccurrenceCatchUpApprovalE2eIT#crossDayDependencyWait_recoversRootPayloadAndCompletesImportBusinessEffect' '-Dsurefire.failIfNoSpecifiedTests=false' test`，全 reactor BUILD SUCCESS、该 E2E 1/1。该证据只关闭根 Workflow 跨日等待恢复的业务闭环，不代表 R26/A1 总体完成；密钥跨进程轮换、多实例恢复竞争、Quartz 持久化停机恢复、相邻日期连续性及其余 R26 场景仍开放。

2026-10-15 R26 跨日恢复夹具隔离与相邻用例回归：类级执行发现跨日 E2E 自动开日与现有 catch-up 用例共用固定日期，造成 `uk_batch_day_instance` 冲突。新场景改用独立业务日后，原 catch-up 审批用例和跨日恢复业务副作用用例同类 2/2、0 failures/errors/skips；`./mvnw -o -ntp -pl batch-e2e-tests spotless:check` 与 `git diff --check` 通过。此项只验证两条相邻 E2E 未互相污染，不替代其余 R26 验收矩阵。
2026-10-15 全 Orchestrator reactor 回归：全量 1864 项首轮出现 7 个与当前 Mapper generation、文件预期终态及关闭日专用 SQLSTATE 不匹配的测试断言。修正旧重载 Mock、文件束中间态断言和 `BDA01` 错误识别后，三类失败测试 42/42 定向通过；`./mvnw -o -ntp -pl batch-orchestrator -am test` 重跑成功，batch-orchestrator 1864 项 0 failures/errors/skips，依赖 reactor 亦成功。`spotless:check`、`git diff --check` 通过。此证据不包括 Console 全量、部署或 staging，R26/A1 仍开放。

2026-10-14 后续实施：`BundleArrivalLauncher` 不再创建 Job 根请求，而是按 Job Definition 的显式 `single_job_definition_id` 关系解析已启用 Workflow，校验同一业务日历后持久化 Workflow 目标并调用 `WorkflowRunLaunchService`；参数、确定性 requestId 和 FILE_GROUP 预期状态推进保持原语义。真实并发测试先暴露目标 ID 未落入 `insertIfAbsent` SQL，以及同一 Workflow Run 首次节点派发的唯一键竞态；Mapper 已写入 Workflow ID，根 JOB 节点通过唯一键认领。Spotless 格式后，单测 17/17、PostgreSQL 并发集成 1/1 通过；集成断言一条根 Run、一个关联 Job Instance、两条分区/dispatch 事件及一条满足的文件预期，完整命令与结果见验证矩阵。此切片不关闭文件/事件预期身份统一及结算竞态。

## 6. 验证、观测与签收

完整命令、现有测试入口、R01–R36 场景和本轮失败证据统一维护在[验证矩阵](./workflow-daily-integration-validation-2026-10-14.md)。本方案不重复记录会漂移的通过总数。

- 正确性门槛：重复根/节点尝试和重复业务交付为 0；不丢已接纳请求；日期/版本漂移为 0；未知覆盖或未决义务导致的错误结算为 0；跨租户读写为 0。
- 运行观测：计划覆盖落后、已到期未接纳机会、等待/审批最老年龄、Outbox lag、运行无进展、结算阻塞原因、实际消费未追踪、拒绝旧载荷计数。指标采用低基数维度，详细 ID 放审计/日志。
- 容量与恢复：沿用 [SLO/SLI](../runbook/slo-sli-catalog.md)和 [Quartz 容量基线](../runbook/quartz-capacity-baseline.md)，在执行前记录租户数、日程数、峰值 fire、积压天数、并发、锁等待/扫描时延及恢复预算；未确定预算或无实际测量不能签收容量。
- 验收绑定具体源码/构建/环境。上游相关代码或依赖变动后重新执行受影响用例；SKIPPED、未发现测试、fixture fallback、环境失败和业务失败分别列出，不能合计为通过。
- 文档检查通过仅关闭本轮方案维护，不关闭实现阶段。最终发布仍须现行 CI/代码审查及授权；不能用文档齐全替代真实链路验收。

## 附录：第一轮来源迁移编号记录

以下仅用于追溯 `2026-10-14T12:38:22Z` 快照。Workflow 来源 V236–V250 当时保持原号；每日连续性来源按下表顺延。第一轮 31 份 SQL 与来源内容 hash 相同；后续按新模型整理后须登记新清单，不再以“保持旧转换 SQL”作为约束。

| 每日连续性来源文件 | 整合文件 |
|---|---|
| `V236__calendar_dependency_wait_reconciliation.sql` | [`V251__calendar_dependency_wait_reconciliation.sql`](../../db/migration/V251__calendar_dependency_wait_reconciliation.sql) |
| `V237__calendar_dependency_wait_timeout_alert_claim.sql` | [`V252__calendar_dependency_wait_timeout_alert_claim.sql`](../../db/migration/V252__calendar_dependency_wait_timeout_alert_claim.sql) |
| `V238__calendar_dependency_wait_rule_snapshot.sql` | [`V253__calendar_dependency_wait_rule_snapshot.sql`](../../db/migration/V253__calendar_dependency_wait_rule_snapshot.sql) |
| `V239__batch_day_open_cursor.sql` | [`V254__batch_day_open_cursor.sql`](../../db/migration/V254__batch_day_open_cursor.sql) |
| `V240__calendar_dependency_wait_manual_action.sql` | [`V255__calendar_dependency_wait_manual_action.sql`](../../db/migration/V255__calendar_dependency_wait_manual_action.sql) |
| `V241__job_dependency_relation.sql` | [`V256__job_dependency_relation.sql`](../../db/migration/V256__job_dependency_relation.sql) |
| `V242__job_dependency_waiting_launch.sql` | [`V257__job_dependency_waiting_launch.sql`](../../db/migration/V257__job_dependency_waiting_launch.sql) |
| `V243__job_dependency_wait_timeout_alert.sql` | [`V258__job_dependency_wait_timeout_alert.sql`](../../db/migration/V258__job_dependency_wait_timeout_alert.sql) |
| `V244__batch_day_catchup_control.sql` | [`V259__batch_day_catchup_control.sql`](../../db/migration/V259__batch_day_catchup_control.sql) |
| `V245__job_dependency_business_date_alignment.sql` | [`V260__job_dependency_business_date_alignment.sql`](../../db/migration/V260__job_dependency_business_date_alignment.sql) |
| `V246__scheduled_occurrence_ledger.sql` | [`V261__scheduled_occurrence_ledger.sql`](../../db/migration/V261__scheduled_occurrence_ledger.sql) |
| `V247__scheduled_occurrence_coverage.sql` | [`V262__scheduled_occurrence_coverage.sql`](../../db/migration/V262__scheduled_occurrence_coverage.sql) |
| `V248__expected_work_occurrence.sql` | [`V263__expected_work_occurrence.sql`](../../db/migration/V263__expected_work_occurrence.sql) |
| `V249__file_group_expectation_definition.sql` | [`V264__file_group_expectation_definition.sql`](../../db/migration/V264__file_group_expectation_definition.sql) |
| `V250__external_event_expectation_definition.sql` | [`V265__external_event_expectation_definition.sql`](../../db/migration/V265__external_event_expectation_definition.sql) |
| `V251__bind_external_event_expectation_to_trigger_request.sql` | [`V266__bind_external_event_expectation_to_trigger_request.sql`](../../db/migration/V266__bind_external_event_expectation_to_trigger_request.sql) |

2026-10-15 R26 根 Workflow 跨日恢复缺陷：在将跨日 WAIT 测试推进到生产恢复链时发现，`CrossDayDependencyReconciler` 仍要求 `WorkflowRun.relatedJobInstanceId` 并从父 JobInstance 读取 tenant/bizDate；但新模型根 Workflow 正常没有父实例，导致恢复扫描命中也会直接返回。调整恢复上下文为 WorkflowRun 本身，解析、告警和节点派发都使用根运行 tenant/bizDate，移除对旧父 JobMapper 的依赖。新增单测先失败后通过（`CrossDayDependencyReconcilerTest` 8/8）；真实 PostgreSQL 集成从根 Workflow 首次等待开始，在依赖结果生效后由生产 Reconciler 创建子 JobInstance，并核实根参数及精确结果版本/输出均进入 snapshot，1/1 通过。恢复后的真实 Worker 副作用、多实例竞争及加密密钥故障/轮换演练仍未完成，R26/A1 不关闭。

2026-10-15 R26 跨日恢复扫描闭环：端到端日志显示 200ms 轮询配置被 ShedLock 固定 30s 最短持锁时间覆盖，移除该不必要的锁下限后配置生效；同时发现已恢复节点的历史 WAITING_DEPENDENCY 行会被每轮重复扫描。新增 `selectLatestByNodeStatus` 只扫描节点最新 run_seq，历史 WAIT 继续保留但不会重复解析/派发。单元 + PostgreSQL 集成 12/12 通过；数据库断言验证历史 WAIT 仍在、最新等待扫描不再返回该节点。真实 Worker E2E 在 200ms 配置下等待→结果生效→恢复→客户写入 1/1 通过，Surefire 运行日志中同一根 Workflow 的 `cross_day_dep resolved` 恰好一次；此前类级回归 2/2 通过，包含旧 catch-up 审批用例。Spotless 和 `git diff --check` 通过。多实例恢复竞争、长时间高积压吞吐及 staging 仍未验证，R26/A1 继续开放。

2026-10-15 R26 恢复调度改动的 occurrence 邻接回归：在最新轮询配置和 latest-run 扫描查询代码下，单独复跑 `approvalOfConsoleCatchUp_reconcilesAndLaunchesWorkflowNode`，真实 Console/Trigger/Orchestrator/Import Worker E2E 1/1 通过，确认关闭日 occurrence 审批 catch-up 与跨日恢复扫描改动并存。

2026-10-15 R10 生产结算器义务门禁验证：在此前未决 misfire occurrence 场景上加入 `ARRIVED` 文件组与 `EXPECTED` 外部事件预期，通过真实 PostgreSQL `BatchDaySettleScheduler` 逐项验证：先终结 occurrence 仍因两项预期保留 CUTOFF；再终结文件预期仍因事件预期保留 CUTOFF；全部终结后才变为 SETTLED。扩展后的定向 Testcontainers 用例 1/1 通过，空库至 V276。该证据证明三类代表性待办各自阻断生产空日结算，不覆盖其他 occurrence 状态、多实例故障或完整 Quartz→Workflow 结算链，R10/A3 仍开放。

2026-10-15 R12 外部事件幂等与先到后匹配：事件请求新增一等字段 `externalEventSource` / `externalEventId`，由 TriggerRequest 持久化并随 LaunchEnvelope 传递；V277 对 `(tenant, workflow, source, eventId)` 建唯一投递键，事件类别或业务日与已接纳身份冲突时拒绝；V278 将不同 HTTP 幂等键登记为请求别名，保证重投响应及状态回查一致。Trigger PostgreSQL 集成 10/10 覆盖并发重投、别名回查、来源/事件区分和多个 Workflow 的独立投递；Trigger API 绑定测试 7/7；Orchestrator PostgreSQL 先到后匹配 1/1；两端均从空库迁移至 V278。自动目标 fan-out、来源身份注册/鉴权仍未实施，因此 R12/A1/A3 保持开放。

2026-10-15 R12 外部 EVENT 来源鉴权：复用租户 `batch.api_key`，严格要求 API Key 名称匹配 `externalEventSource`、租户匹配且显式具备 `trigger.event` scope（`*` 不授权）；仅开放 EVENT 启动和 source-scoped 状态查询，禁止 API Key 访问其他 Trigger 操作，CSRF 豁免也限定在两条允许路由。鉴权/过滤器/Controller/服务定向测试 41/41 通过；Trigger 完整 PostgreSQL 集成类 11/11 通过，空库 V1→V278，覆盖 PBKDF2 API Key 查库、租户/source/scope 判定及 `last_used_at` 写入。完整 Trigger reactor 首轮发现 Mapper 租户守卫要求 `last_used_at` 更新显式按 tenant 收口，已修复；修复后的模块首跑又有 `calendarConfigurationChangeInvalidatesScheduleCoverage` 失败，随后单项和完整 occurrence 类 15/15 通过，完整 reactor 最终 248/248、0 failures/errors、2 skipped。验证还修复多构造器 Spring 注入歧义、XML mapper glob 子目录遗漏和 PostgreSQL null 参数类型推断问题。跨服务撤销窗口为成功验证缓存 1 分钟，expiry 仍在命中时检查。自动目标 fan-out 未完成，R12/A1/A3 仍开放。


### 2026-10-15 SIM 第 24 阶段新发现与停用语义收敛（实施中）

- 用户选择：调度停用后保留并继续恢复已有 misfire。以 ADR-048 的 `schedule_enabled=false` 规则实施；恢复器须独立扫描持久 `MISFIRE_PENDING`，只注册恢复任务，不打开主日程。旧“暂停时等待再启用”的用例须替换为暂停期间完成一次补跑、主日程始终关闭的数据库验收。相关 PostgreSQL/Quartz 定向回归已通过，真实 SIM 复验仍待完成。
- 第 24 阶段旧注入将 Quartz 时间改成不匹配 occurrence 的任意 instant；seed 同时遗留一条无人消费的 ACCEPTED 请求。去除这两项测试误差，改为管理 API 暂停/恢复真实 Quartz fire，并要求原 fire 的 pending/occurrence/Workflow/Job 收敛；不得使用 seeded fallback 关闭真实 callback 项。
- 真实暂停过程中发现独立补跑遇到同业务日活跃 WorkflowRun 会被当成重复请求确认，形成 LAUNCHED 但无根运行。新增真实 PostgreSQL 回归先复现失败；实现改为通过既有 429/Kafka nack 路径等待活跃槽位释放，保留该请求，随后只创建一次根运行。定向用例已通过；类级回归、真实 SIM 复验仍待执行。
- 2026-10-16 在 V283 新库完成当前代码的完整 SIM 04→30：28/28 阶段通过，记录见验证矩阵。该结果关闭本地整套 SIM 执行项；I2/R05 与总体发布门禁仍因区间实际消费、配置变化后的旧版本执行、浏览器授权、目标环境故障/容量及其它未验收语义保持开放。

- 原 2 秒 cron 复验发现 occurrence 逐行物化和启动/周期对账并发会耗尽 Trigger 连接池；10 秒诊断通过不能关闭该项。按现场锁等待证据实施有界批写与统一启动锁，保持版本、终态、租户及 coverage 原子性；须通过 PostgreSQL 边界/回滚回归及原 2 秒频率脚本后再推进完整 SIM。

- 用户再次明确选择 Workflow 停用时已发生的 misfire“保留并继续恢复执行”：`workflow_definition.enabled=false` 不得撤销已持久化的 occurrence 或其恢复义务；恢复器按原 fire time、业务日、冻结启动载荷和幂等身份继续处理，即使 Workflow 仍停用。普通新 `SCHEDULED` 接纳仍受停用门禁；原触发类型要求人工审批时保持待审批，不以恢复执行绕过审批。原查询与根准入拒绝问题已由 V282 持久来源证明和受限恢复准入修复；调度暂停/完整停用的 Trigger 正例，以及 Orchestrator 已持久 misfire 正例、普通请求/伪造身份/跨租户/未审批/租户暂停/过时版本负例已定向通过。完整跨服务自动 CATCH_UP 的最终业务副作用仍待验证，不能将定向通过等同于总体验收关闭。

- 原 2 秒频率下批写修复后的业务断言已达到双成功、93 个 API 请求终态、无前缀残留；但该诊断运行期间修改了正在执行的 Shell 文件，Bash 在 heredoc 返回后按旧偏移读入 Python 尾部，退出 127。此轮不算脚本通过，须对稳定脚本重跑；已补 fixture 开始时通过管理 API 重新注册两目标并恢复全局调度，避免上次失败遗留 durable pause-all 状态。
- 429 等待虽然不丢请求，却会扩大为 Kafka 分区反压。按 R28/R36 的资源等待隔离要求改为 Orchestrator `workflow_launch_wait`：V280 保存首个完整请求，准入前为 PROCESSING；事务持久化后正常确认消息；按租户和空闲 Workflow/date/dry-run 槽位恢复，不重算业务日。原 429 临时实现已替换。已有定向 PG 恢复、独立 Workflow 不被占位阻断、拒绝终态、防篡改和无绕过权限 RLS 通过；完整回归和真实 SIM 仍未完成。

- 资源等待恢复补强：领取 SQL 允许同请求已建立的根运行进入幂等补收尾，避免中断窗口被自身槽位永久阻塞；初始派发检查 RUNNING CAS 结果，终止/暂停抢占后不创建子 Job。两项真实数据库回归均先复现失败、修复后定向通过，完整回归继续。
- 审批快照仍开放：原 `fromScheduledTrigger` 的 scheduledAt、workflowVersion 和半开数据区间在 pending 路径没有完整保存，审批重建请求会丢失。须先保存原 LaunchRequest 并在审批/重启后复用，不得以当前日期或当前日程推导原区间；补配置变化和实际业务读取验证后再关闭相关冻结契约。

### 2026-10-15 审批快照与完整停用恢复补强

- V281 在 TriggerRequest 保存首次完整 LaunchRequest；Quartz 待审批、被关闭 occurrence 显式补跑、失败 BatchDay 补跑均在首次接纳时持久化。审批只叠加审批说明，不重算 bizDate、半开区间、Workflow 版本、dry-run 或原参数；缺失、损坏及身份不符的快照失败关闭，不提供旧请求重建兼容。
- 失败 BatchDay 自动恢复复用首次载荷及来源实例区间；配置或候选实例后续变化不覆盖已接纳输入。V282 保留首次 misfire 来源时间，REQUESTED 后仍可证明其原义务；完整停用不会重新开启主 cron。
- 稳定脚本的 Stage 24 已在原 2 秒 cron 下退出 0：真实 Quartz misfire 的审批、occurrence、Root、Job 全链 SUCCESS；Outbox 12/12、API storm 80/80，残留 pending Outbox 与非终态均为 0。Stage 25 修复误选控制节点的查询后，强杀 Worker、同一 pipeline checkpoint 恢复及 5000 行业务结果已退出 0。两项使用本轮 V280 构建；新增 V281/V282 的最终构建和联合回归另行记录，不混用构建证据。
- 审批快照字段保留、完整停用的受限准入已有真实 PostgreSQL/Quartz 定向回归；完整 SIM 04→30 已于 2026-10-16 本地通过。配置改变后执行旧版本、跨服务审批后实际消费区间、浏览器及目标环境容量/恢复仍开放。详细命令和失败/通过证据只维护在验证矩阵。

- 2026-10-16 A2 字段链补强：新构建 Stage 24/25 已业务通过，但精确 fire 对账发现 Root 区间在 JOB 子实例处丢失，新增 `ChildJobLaunchSupport` 原区间透传实施项；非 Quartz CATCH_UP 必须保持数据区间与触发事件时刻分离。不得把 Root 快照保留或 Job SUCCESS 等同于 Worker 实际使用原区间，相关字段与业务读取验收继续开放。

- 2026-10-16 A2 区间断点已作最小修复：JOB 子请求复制 Root interval，非 Quartz 补跑保留实际接纳事件时刻；受影响单元/PG 回归 102/102、审批及跨日恢复 E2E 2/2 通过。加强真实 misfire SIM，使旧的 Root 成功、Job 区间为空不能通过。最终新构建复验与 Worker 依区间筛选的业务证据分别记录，A2 仍不关闭。

- 2026-10-16 I2 完整停用的 Console 请求契约：修复 requestId 审批未同步批准原 misfire 待办的竞争，待办/occurrence/Outbox 与请求 CAS 原子完成；V283 补关联索引。定向 55 项、完整 Trigger 267 项及审批/跨日 Worker E2E 两项通过。真实 SIM 进一步要求通过 Console API 停用 Workflow 后仍恢复唯一原请求并保持原区间，不以内部 pendingId 用例代替该业务验收；最终结果见验证矩阵。

- 2026-10-16 I2 暂停接纳边界：真实 SIM 复现暂停后仍有普通请求（1→4），确认不是在途实例数量变化。补 Workflow 行锁，把 request/outbox 接纳与调度启停串行化；readiness 首次观察也采用同一边界，保留此前已观察的等待。57 项定向通过；保留原 2 秒 cron 和暂停严格断言，完整回归/真实复验另记，不因测试失败放宽语义。

- 2026-10-16 I2 完整停用真实恢复：最新完整 Trigger 273/273 与审批/跨日 Worker E2E 2/2 通过；八应用 clean build 的 Stage 24 已经真实 callback→Console 完整停用→业务 requestId 审批→Root/Job 双 SUCCESS，且原日期、区间、版本不变、Workflow 保持停用，去重及压力残留断言通过。首次 SIM 数字外键误作业务 requestId 的 404 已修正并保留失败证据。此前“暂停期间等待重新启用”的规则已被用户选择取代；本地场景通过不关闭全套 SIM、容量、部署或 A1–A6/I6 门槛。详细证据见验证矩阵。

- 2026-10-16 新库整套 SIM 首轮：门禁注释/命名缺口修复后 88 项受影响及空库迁移测试通过，另建平台/业务数据库的八应用启动通过；完整 harness 的 Stage 15 揭示历史业务日已结算而压力夹具仍发普通 API 请求，接口又把 `BDA01` 错当 500。按 R26 补明确冲突错误及独立的新请求日期夹具，保留关闭日只允许显式 CATCH_UP/RERUN 的规则；当前整套运行已有失败，验收保持开放。

- 2026-10-16 首轮三失败项修复：四类关闭日 API 的 PG 用例先红后绿，新栈真实 HTTP 返回 409 且请求/Outbox 零写入；完整 Trigger 277/277、审批/跨日 E2E 2/2 通过。Stage 22 使用真实 Quartz 原 fire 与 callback，不再手造无快照审批请求；Stage 15/22/24 的正常压力请求逐项核对同租户 Root/JOB/JobInstance 成功。三项新构建单跑已通过（31、60、93 个正常请求）；一次父 shell 共用 batchNo 的编排错误被外键拒绝，按现有阶段隔离规则复跑通过，未放松守卫。最终同版整套回归及其它 I6/A1–A6 门槛仍开放，详见验证矩阵。

- 2026-10-16 I4 依赖恢复与版本身份：资产分区 Mapper 直接映射持久 `resultVersionId`，去掉旧构造和事后补 ID；服务按稳定身份核对物化结果。等待快照只接受声明过的对象结构；空/损坏条目、旧字符串、非法对齐/日期不得退化为无依赖或重算日历，原 JSON 与 WAITING 保留，记录 `DEPENDENCY_SNAPSHOT_INVALID`。真实 PG 先红后绿并验证同租户其它合法等待继续恢复、重复扫描只创建唯一实例；合法日期与原解析错误保持冻结。准入观察的版本 ID 仍不证明处理器实际消费，最终入场竞争、实际版本读取和完整恢复矩阵继续按 R07/R08/R14/R17/R33 验收。

- 2026-10-16 I1/R34 新库夹具与验证来源：移除 SFTP 旧键转换，三个自有通道按规范字段整批校验与准备；真实 SQL 异常部分更新回滚。Java 字符串门禁补齐未跟踪源码和已删除路径的枚举，原检查规则及抑制范围保持。E2E 生成资源曾残留四个已退役迁移，保留原件后仅 clean 该模块；279 份迁移资源与源码逐项相同，三个真实服务 E2E 干净重跑通过。其余输出目录和当前运行 JAR 没有该四项。正式新库验收继续核对源码、构建输入、运行包和数据库历史，不以增量构建成功或迁移终点版本代替。


- 2026-10-16 I6/R28/R35 写入契约：`/full` 的版本必须由客户端提供并参与 SQL 比较，缺失版本拒绝；定义/版本/调度 SQL 改用按操作分开的具名参数，配置导入保留原事务次序。PG/Controller 定向与 Console 受影响 reactor 通过，跳过原因及 Trigger 动作生成契约已同步。基础 `PUT /workflow-definitions/{id}` 的不比较/递增版本写图入口仍需统一或明确退役，不能用 `/full` 局部通过关闭定义竞态验收。

- 2026-10-16 R19/R34/R35 前端验证收敛：实测旧根项目 typecheck 未读源码，已改为显式检查 app TS/Vue 和 Vite 配置，构建复用入口，临时项目正反例验证能拦截真实类型错误。当前应用仍有源码类型错误；按领域契约修复，禁止排除页面/测试、宽泛强转或关闭严格检查求通过。以前仅据旧空跑命令登记的 typecheck 成功不作有效源码证据，详细失败、来源对照及新验证见矩阵。该缺口属于现有 R 行，不改变 36 个联合验收组的口径；当前仍在实施和验证收敛阶段，尚未到最终同版完整 SIM/发布验收。
