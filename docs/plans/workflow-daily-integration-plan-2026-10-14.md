# Workflow 与每日连续性隔离整合计划

> 修订日期：2026-10-24；契约 `2026-10-14.2`。**用户确认：不兼容旧系统，全新数据库部署。**
> 状态：整合实施中；B192当前为23项已验证、13项部分验证，R03因3个调度首红重新开放，R25新增本地功能缺口，详见验收矩阵§2.1。以下B172的24/12及36/36仅为历史冻结构建快照。本地收尾已完成 36/36 项（正式关闭 24 项 + 本地例外范围完成 12 项：R05、R12、R21、R23、R24、R25、R26、R27、R29、R32、R34、R36）；B172时本地场景待办0项。彼时12项例外仍列部分验证，不能视为发布验收通过。B172 已完成冻结联合门；B173 补齐独立 Job 预检的目标边界并回写 I0–I6 证据。业务范围裁定、质量门禁和目标环境签收分别跟踪；不重开已完成的数据库治理。例外见[验证矩阵 §2.3](workflow-daily-integration-validation-2026-10-14.md)，不降低 D0–D5 放行条件。

> B172 联合结果：SIM **28/28**、strict **21/21**；整轮 WARN **101条**（98原策略分类、3条本轮精确证据附录，默认首次失败保留）；正常运行态默认审计 **4/4**。8后端服务UP、前端可用、真实认证开启、运行/专属夹具残留0。**用户已确认保留 Workflow 批量发起、预检分开；该承接已在B182完成本地真实认证浏览器、HTTP与运行结果核对；质量门禁仍开放，不能据36场景完成宣称整个I1完成**；目标环境例外及暂缓格式检查保持。
> **B197 最新功能进展**：Quartz元数据与恢复回调保留57个不同定向用例通过（51组件+6真实JDBC）；Java SDK Error迟到重投与已入队晚执行修复62项通过（59单测+3真实Kafka/HTTP/业务PG）。此前Console编译阻断已由所属会话修复，最终SDK命令exit0。B197首次容量等待接纳已改为同事务持久化，95个不同定向/关联用例通过；完整Orch readiness/截止时间移交及WR-04候选仍开放，正式23已验证/13部分验证不变；未新增JAR、全量SIM、CI或目标环境验收。详见[功能复核](../audit/workflow-integration-functional-review-2026-10-24.md)及执行日志B196–B197。
> **B192 历史功能收尾**：代码规约类按用户分工移交另一会话。本会话只修功能：SDK相关111个不同用例组合通过，WR-02真实平台故障证据仍待；审批并发关联72项及真实PG4场景组合通过；调度3项首红已确认，等待、SDK外部动作标记等仍开放，详见[功能复核](../audit/workflow-integration-functional-review-2026-10-24.md)。R34 prefix 19例组合通过、归档成本Probe 1/1已收齐。B172本地36/36仅为当时冻结构建，不能覆盖本轮未关闭缺陷；最新统一运行包及发布门槛仍待验证。
> B182 业务增量：批量发起本地真实认证验收完成；修复直接END派发及冻结图未派发分支终态判断，保留跳过下游时END未激活的语义。后端52个不同用例、前端31项和最终JAR两条控制图通过，夹具已清理。当时PMD166项，现已按B189收口至0；该批Console/Orchestrator已更新，其他6个JAR未变，未重复全量SIM。
> 分支：`feature/workflow-daily-integration`；当前权威：[ADR-048](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md)。[ADR-047](../architecture/adr/ADR-047-workflow-scheduling-and-daily-obligations.md)已被取代。
> 本轮按已授权的整合方案修改隔离工作区内代码、测试和新库迁移；验证结果与未完成项见[验证矩阵](./workflow-daily-integration-validation-2026-10-14.md)。

### AUTO 停机补跑的已确认语义（2026-10-20）

用户确认 **逐 fire、有界分批补齐**：已写入 occurrence 的每个计划 fire 保留独立义务，按原 scheduled fire time 与冻结 bizDate 恢复，不合并执行或静默遗留 PLANNED。当前扫描每轮至多 100 条，并复用既有条件更新、请求/Outbox 幂等与恢复队列；Quartz 重复回调不能增加同 fire 的执行义务。该边界已实施并有定向 PostgreSQL/Quartz 证据，见[执行记录 R05](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-20-b2-r05-auto-misfire-逐-fire-有界恢复)；目标规模下追平时间仍按 EX-03 单独验收。

### 主线开发任务的补充缺陷清单

2026-10-14 按用户要求将[设计闭环复核](../audit/design-closure-review-2026-10-14.md)纳入本分支：后端 SEAM-1–6 与前端/跨端 UI-SEAM-1–9 的逐项状态、责任边界和关闭条件统一维护在 [TODO Master](todo-master.md)。报告中的 FE-1–9 对应 UI-SEAM-1–9，不与 TODO 中历史已完成 FE 编号混用。

执行恢复相关项与 I4 联合核对，前端身份绑定、配置保存/导入、操作反馈及生成契约与 I5 联合验收；修复应分别保留失败用例、实现和结果证据，不因报告已转入开发分支而标记完成。配对 OpenAPI 必须以实际整合后端为契约源；验证矩阵记录的 2026-10-15 生成漂移检查已通过，但不代替真实前后端业务结果验收。后续每次契约改动仍须重跑该检查。

### 数据库逐表引用完整性整改（2026-10-18）

已按本机独立验收实例 PostgreSQL 17.11 / V291 的实际 catalog 核对 **176 张逻辑表、107 张分区子表、51 条逻辑外键**，详见[逐表核对与整改方案](database-reference-integrity-remediation-2026-10-18.md)及其结构清单。逐表区分应补租户/聚合约束、不宜添加热表外键、已有替代校验及其缺口；PostgreSQL 17 支持分区表外键，当前限制须按分区键和唯一键说明。

REF-01–08 作为 I1/I4/I5/I6、R20/R33/R36 的系统问题整改推进：先修独立 Workflow 归档丢 JOB 关联、Pipeline progress 漏归档/清理及主链引用校验，随后落实运行树/Outbox 并发归档协议、复合外键和历史/跨库生命周期。数据库防御补强与需业务裁定项按专题分别排期；不一律删外键或加外键，不将所有建议都算发布阻断。初始盘点仅完成结构与源码审查；后续P0独立Workflow归档及叶子隔离已补局部实现/动态证据，详见验证矩阵文末，完整并发与其余整改仍开放，不改变现有 R 项状态；应用层缺口、分批实施任务及动态验收矩阵见专题方案 §2.2。优先推进 RT-2 Workflow JOB 引用准入，再处理 RT-4 等待请求一致性、RT-5 Job 依赖并发环保护和 RT-6 父子归档并发闭环；当前唯一进度见 TODO Master 的 `ALIGN-DB-REF`。

2026-10-18 后续已落地位点归档切片：V292租户复合FK、固定父/位点锁、冷热整行核对和应用写入0行拒绝；V300补齐分区JobInstance到TriggerRequest的同租户复合FK及保留请求ID前缀的复合索引，真实PG空库迁移和正反例通过。位点的并发与脚本证据见引用专题§2.5，TriggerRequest证据见§2.15和验证矩阵；两者均不关闭其余I/R门槛。

同日 PART-01 继续完成真实 RLS 安装/strict/rollback 的原子性、writer 角色拒绝及策略检查修复；相关37项本地定向回归通过。灾备脚本的 RLS 重建失败已传播到失败结论并保留恢复库，mock 组件验证通过；没有执行真实 DR、多物理库联合演练或新版本全链验收，详见验证矩阵文末。

RT-2常规Console发布/启用、严格与逐项导入、根运行准入已接入引用检查；18项真实API/PG与根入口4项通过，相关8类最终报告累计116项，原失败夹具及定向修复见验证矩阵。系统自动受管图、错父租户元数据与父状态并发仍开放，继续按引用专题§2.6/RT-4/5/6推进。

REF-04后续通过V293为图节点、边及根容量等待补已验证的父定义租户复合FK，原生SQL与父租户变更/子写交错的8项PG检查已通过；全新库迁移/重复迁移/校验通过。其它运行归属、WAIT请求身份和归档并发仍开放，详见引用专题§2.7与验证矩阵。

应用层外键守护还须与数据库逐关系对齐，按[数据库整改总方案 §3.2](database-integrity-index-partition-remediation-2026-10-18.md)和[引用完整性专题 §2.3](database-reference-integrity-remediation-2026-10-18.md)执行：同一租户/聚合/业务日身份、FK列及目标键、NULL/MATCH 与删除/更新动作、草稿/停用/软删、归档/恢复、弱引用例外及错误契约均需明确。覆盖 API、导入、启用/发布、启动/重试/恢复、Mapper/运维 SQL，并保留父子变更的双事务并发证据。应用预检必须在正确写库和事务上下文中按相同引用身份执行；数据库可表达的强引用由 FK 等约束兜底，数据库拒绝需回滚副作用并映射为稳定领域错误。FK 无法表达的状态、图环和历史弱引用另列业务规则及并发协议，不能只用 Service `EXISTS` 替代，也不能把应用守护称作等效 FK。该要求映射 DBSAFE-3 与 REF-02–08，纳入现有 I/R 验收，不新增完成项；代码和动态证据按逐关系条目推进。RT-5 已以普通数据库角色复现并修复配置导出丢依赖、导入RLS拒绝及目标事务继承源只读/rollback-only：源读取按源tenant绑定，每个目标在独立事务内绑定自身tenant，成功目标不随其它目标失败回滚。混合业务日持久等待恢复和该事务切片的局部证据见[引用完整性专题 §2.28](database-reference-integrity-remediation-2026-10-18.md)；其余关系和联合验收继续开放。

### 数据库索引与分区生命周期专项整改（2026-10-18）

在引用完整性之外分别建立[索引逐表方案](database-index-review-remediation-2026-10-18.md)与[表分区及扩展方案](database-partition-lifecycle-remediation-2026-10-18.md)：前者覆盖176逻辑表/589逻辑索引（含子索引1,634目录对象），后者覆盖10父表/107子分区及全部1,045子索引挂接。三专题分别保持 `ALIGN-DB-REF`、`ALIGN-DB-INDEX`、`ALIGN-DB-PARTITION` 进度，不混成一个“数据库验证”任务。

配置冷启动先落实包内业务日历，再创建 Job 与 Workflow。可恢复的单项错误继续使用保存点；后置受管 Workflow 等租户级阶段异常返回租户失败时，必须回滚该目标全部配置写入，且保留其它已成功目标。普通角色下的冷包与失败零残留证据见[引用专题 §2.29](database-reference-integrity-remediation-2026-10-18.md)，不据此关闭全部 I5/新库联合验收。

父对象在应用预检后被并发删除时，配置导入仍由数据库强引用兜底，并按同一事务边界回滚。批量失败结果依据 SQLSTATE、Schema、表和约束身份识别已知 Job 依赖拒绝，使用稳定错误键；未知数据库异常及其包装不向调用方返回 SQL/路径，原始诊断保留在服务端。普通删除入口因仍被引用而失败时保持 `409/CONFLICT`。真实双事务竞态及7类81项局部回归见[引用专题 §2.30](database-reference-integrity-remediation-2026-10-18.md)，其余引用关系和全链验收保持开放。

源配置导出/复制/预览使用独立只读 `REQUIRES_NEW + REPEATABLE_READ` 快照，避免分次查询拼接不同时间的Job与Workflow，也避免继承调用方的READ COMMITTED。目标配置仍按租户独立写入，不把源只读事务用于目标写入。真实并发先红、修复与3类18项回归见[引用专题 §2.31](database-reference-integrity-remediation-2026-10-18.md)；该保证限定单次数据库读取，不等于跨请求Preview→Apply版本锁定或跨环境快照，相关完整矩阵及容量保持开放。

Config Sync初始审计须先于目标写入独立提交，最终结果再以审计所属租户事务落盘；日志失败不能回滚、重做或伪报已提交目标。瞬态只重试相同审计更新，未知结果保留RUNNING与关联摘要，前端不把未确认计数显示为确定的零成功/零失败。日志行数及ID必须核实，内部失败返回系统错误且诊断不进入用户日志详情。已完成切片及真实PG/配对前端证据见[引用专题 §2.32](database-reference-integrity-remediation-2026-10-18.md)；崩溃后精确对账、永久故障恢复和完整导入幂等继续开放，不据此关闭I5/R30。

优先推进普通业务角色直接访问无RLS叶子的隔离缺口、PROCESS淘汰的在途/恢复保护，以及REF已列的数据完整性问题；随后补平台未来分区维护入口、扩建事务/多物理库覆盖、DEFAULT/时区与锁处理。索引方面先修日期比较和Webhook partial谓词漂移，再以实际负载决定冷热查询、关联/FK与队列索引候选；低风险重叠收敛靠后。只读EXPLAIN已确认简化日期条件计划涉及37→1分区，尚未修改Mapper或验证实际性能。现存子索引挂接完整不代表生命周期验收完成，R20/R33/R36与总体完成统计保持原状态。

### PROCESS staging generation 身份与引用约束（2026-10-18，PART-02 局部实施）

PROCESS 在平台库为实际 COMPUTE 生成并持久化新的 staging generation；合法阶段续跑沿用该代，重新 COMPUTE 才轮换。业务库 `process_staging` 以 `(tenant_id, staging_generation_id, batch_key)` 复合 NO ACTION 外键引用 generation 账本，触发器另行拒绝非 OPEN 代次；应用写入、读取和清理均按同一 generation 与 batch key 过滤。`process_staging_generation` 和 staging 数据都启用 FORCE RLS：账本策略限定 tenant，staging 行进一步限定 generation；应用查询同时显式限定与事务 RLS context 相同的 tenant，避免将物理分库当作唯一隔离。最旧行 Gauge 只从后台 tenant/generation-scoped scan 更新缓存，不在 scrape 中进行无上下文查询。平台热任务与冷归档镜像同步记录当前身份。该实现按全新数据库部署，不提供旧表兼容迁移。

后续已加入 PART-02 的有界旧 generation 回收切片：候选只来自超期 ledger；平台事务持有与 Worker/reclaim 共用的 JobInstance advisory fence 并重读 task 的当前 generation，当前代或无法解析的 task 一律保留；业务库事务锁定精确 ledger、OPEN→SEALED 封口并按批量删除精确 tenant/generation/batch key 行，保留 tombstone 供失败重入。generation 页查询可继续扫描被保留的当前代，单 tick 删除量有界；整日分区 DROP 已从 Mapper 删除入口移除，不能按日期直接丢整分区。该协议以新 generation 取代旧代作为安全依据，不读取 Workflow/Job 终态来清理当前代；当前代暂不自动回收。`deletion-enabled` 仍必须保持关闭，直至暂停/人工重试/归档/长 SQL、跨物理分片、崩溃重入、部署与容量验收完成。验收证据与失败边界见验证矩阵最新 PART-02 记录。

RT-4等待准入已局部落实同租户原请求身份、业务日/dryRun、状态与必需写入行数保护；容量恢复与Job准备事务复核相同身份，行锁读取强制重新执行SQL，具体保证与未完成的原生FK/保留期/完整Job恢复矩阵见[引用专题§2.8](database-reference-integrity-remediation-2026-10-18.md)。6类178项受影响定向回归通过，故障拒绝后同请求恢复有3项真实根入口证明；证据和失败原件以验证矩阵为准，不能把应用/Mapper保护称为完整数据库外键或关闭R项。 Job等待恢复继续按[引用专题§2.9](database-reference-integrity-remediation-2026-10-18.md)落地提前身份核对、事务租户绑定及原子取消，并保护已准备实例和过期扫描结果；真实PG类55项及普通角色取消1项分批通过；连同69项单测及15项旧恢复链路，140个不同用例最终观察通过（原回归命令含1个夹具错误，已只跑失败项修复，不改写原命令结论）。子请求拒绝向Workflow节点/根传播及取消提交后重扫已按[专题§2.10](database-reference-integrity-remediation-2026-10-18.md)局部落实，绑定具体节点尝试并保护暂停与新重试；8类146个不同用例最终观察通过，原145项命令有1个新错误契约失败已定向修复，原失败记录保留。原请求/两类等待保留与终态审计、同租户NO ACTION外键V294及显式清理已按[专题§2.11](database-reference-integrity-remediation-2026-10-18.md)局部落实，4类50项与SDK单项通过（51个不同观察）；退出后台线程超时记录保留。下一步推进其它RT-4弱引用账本并发、完整热冷/清理恢复矩阵和RT-5/6全入口协议及目标部署；不把本轮局部外键证明扩为全局生命周期闭环。

## 0. 完成口径与明确范围

方案的完成表示职责、接口、状态、失败处理、切换和验收可按步骤实施；系统可用必须另有同一整合版本的 A1–A6 与 I6/R25–R36 验收证据。测试失败不得以“已编译”关闭；语义盘点发现的必要改造未实现时，也不得以增加测试或风险说明关闭。

### 0.0.1 治理与整改的统筹闭环

本方案同时统筹三类性质不同的工作，分别维护台账、优先级和关闭证据，不合并成一个笼统的“治理整改”结论：

| 维度 | 本方案承接范围 | 权威跟踪与关闭依据 |
|---|---|---|
| **系统问题整改** | Workflow/批量调度主链的业务语义、并发恢复、依赖、告警、分区分片、跨端契约及发布风险；由 I0–I6、A1–A6 和 R01–R36 逐项承接。高风险问题按影响定级，必要时优先于常规代码规约工作，且未完成的发布阻断项不得以文档或静态检查关闭。 | 本方案 §0.1/I6、对应 I 切片；[TODO Master](todo-master.md) 的 SEAM、UI-SEAM、ALIGN 项；[验证矩阵](./workflow-daily-integration-validation-2026-10-14.md) 中对应行为证据。 |
| **代码规约整改** | 本次整合代码及其必要调用方中的可读性、结构、测试约定、SQL/Mapper、注释、日志和门禁存量问题。只修实际命中且影响本次交付或维护安全的项；逐项保留代码位置、整改决定和验证证据，不以放宽规则或扩大豁免清账。 | [TODO Master](todo-master.md) 的 ALIGN-GATES 及相关质量治理项；[规约漂移守卫索引](../standards/convention-drift-guard-index.md) 和对应 Java/SQL/脚本专题文档。存量数量与门禁结果以当前检查和验证矩阵记录为准。 |
| **治理规范与防退化建设** | Skill、规范、文档入口、CI 路由和基线守护等规则体系调整。与代码规约整改分开排期，默认在代码规约整改之后实施；不得为了减少既有违规先降低标准。当前交付必需的精确门禁修复和记录可同步进行，但不代表规范体系整体治理已关闭。 | [TODO Master](todo-master.md) 中的治理项、[Agent Skill 使用指南](../standards/agent-skills-guide.md)、[文档治理规范](../standards/document-governance.md) 及对应治理计划；以守卫正反例、适用 CI 结果和文档同步证据关闭。 |

三类工作分别标注“已评估、待实施、实施中、待验收、已关闭”，并在 [TODO Master](todo-master.md) 保持唯一当前状态。专题方案可承载详细清单，但必须从本节或 TODO Master 可发现；设计覆盖、代码修复、自动化验证和目标环境验收分别报告，不能互相代替。

- **交付**：单一 Workflow 根运行、Job 节点执行、系统 Pipeline、Workflow 定时账本、文件/事件预期、业务日结算、持久化等待、新模型的重试/恢复、配对 Console 与配置入口。
- **退出范围**：旧 Job 顶层入口、旧 wrapper/虚拟父任务、旧 FILE_STEP 转换、旧 key 映射、旧 Pipeline 消息/配置包读取、旧库升级与旧历史导入、混合版本运行。
- **保留的新系统能力**：运行快照、执行版本支持声明、数据消费版本、归档、业务重放、审计与故障恢复。新模型上线后的版本演进必须保护已产生的数据。
- **实施约束**：复用 Quartz、现有事务/Outbox/状态模型；不另建调度引擎、通用事件总线或通用义务框架。共享字段只为已列场景引入，不能为假设扩展增加抽象。
- **资料保全**：原分支和原数据保留；新库部署不等于授权删除旧环境。来源方案中的兼容要求及旧测试记录只作为历史材料，不继续纳入实施任务。

### 0.0.2 模型边界与复杂度约束

本次整合以 [ADR-048](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md) 为唯一模型决策依据。目标是统一运行归属和既有批量语义，不借整合之机扩展通用流程引擎。

| 模型 | 本次定义 | 边界 |
|---|---|---|
| **Workflow** | 顶层运行与同一运行内 Job 编排；图是静态、无环 DAG | 可表达串行、已有条件分支及汇合；不是严格树，不支持递归或子 Workflow 执行 |
| **Job** | 可复用的执行配置；由 Workflow 的 JOB 节点引用 | 不作为第二种顶层运行入口；不以旧 `job_type=WORKFLOW` wrapper 包装子流程 |
| **Pipeline** | 由 Job 类型选择的系统固定执行阶段 | 属于单个文件类 Job 的内部执行与观测，不作为租户可编辑 DAG 或独立顶层调度入口 |
| **运行实例** | `WorkflowRun → WorkflowNodeRun → JobInstance` | Orchestrator 推进 Workflow 状态；START/END/GATEWAY/WAIT 等控制节点不创建 JobInstance。JobInstance 在对应 JOB 节点派发时创建；批次开日不预建当天全部实例 |

复杂度约束：

- 优先用直接边表达顺序；仅在实际业务需要分支/汇合时使用现有边、GATEWAY 与 join 语义。不得为视觉层级把 DAG 改造成树，也不得增加仅用于包装节点的父子层级。
- 本次不新增节点/边/join 类型、嵌套执行、运行时动态拓扑、通用表达式语言、第二套状态机、通用义务框架或旧新模型兼容分支。现有 TASK、WAIT 等能力按其已定义职责处理，不因本条扩大其语义。
- 不以“简化模型”为由删除已确认的批量业务语义。依赖、重放/补跑/跳批、分支与汇合、告警、分区分片、跨日日期、快照、恢复和归档仍按 §0.1 与验证矩阵逐项承接；模型统一不能改变它们的业务结果。
- 若真实用例确需新增模型能力，先在代码实施前补充用例、所有权、稳定身份、日期/配置继承、状态与终态传播、幂等/并发、失败恢复、API/Console/SDK、迁移及验收影响；由独立 ADR 决策并经用户确认后，才调整本方案范围。未完成决策不得用临时 wrapper、隐式推断或测试豁免落地。

本约束用于审查设计与实现是否越界，不单独证明模型实现或验收已完成；实现事实和验证状态仍以对应 I/R 条目及验证矩阵为准。

## 0.1 批量调度既有语义迁移（实现范围与发布门槛）

本次主链切换必须完成 `origin/main` 既有批量调度行为的逐项盘点和承接设计。此项是 I1–I5 的横向实施工作，不是额外的“多跑几项测试”：凡 Workflow 根、节点 Job Instance、occurrence 或 BatchDay 义务模型不能保持既有业务结果的地方，先明确新语义并改造代码、持久化、API/Console 操作、告警和 Runbook，再以验证矩阵证明。未完成语义裁定或必要改造，不得进入 D4 新链路预演和 D5 批量开放。

盘点以 `origin/main` 的 `ee01e97515b04fd167ed17977535ec5cab8572f8`（2026-10-15 本轮审查时解析值）为源码基线；第 1 节记录的 `17c245...` 是本次整合最初的历史分叉点，二者用途不同。数据库状态、Console 操作、告警规则和运维脚本也须按该源码版本关联核对。I6 先回溯盘点当前已实施切片，再将发现映射到 I1–I5，避免把已写代码当作旧语义已承接。逐项记录入口、状态/身份键、事务与幂等边界、业务日期口径、计数/结算影响、外部副作用、操作权限、证据及目标承接点；标记为“等价保留 / 有意变更 / 明确退役 / 不适用（给出理由）”。“有意变更”必须写明新规则、初始化策略、用户可见变化和恢复行为。

I6 按以下顺序形成可审阅产物：①固定源码 SHA 与 Console/告警/脚本版本；②按本节语义域逐项列出 `main` 的生产者→持久化事实/稳定身份→消费者→外部副作用；③为每项指定决定、I1–I5 责任切片、实现责任人、关联 R 场景及证据链接；④未决语义先记录决策与受影响代码边界，依赖该决定的实现不得自行假设；⑤实现后回填差异、回归证据和关闭结论。台账至少包含：`语义 ID | main 基线路径/行为 | 生产者 | 持久化事实/身份 | 消费者/副作用 | 承接决定 | 实现切片 | R 场景 | 证据/状态`。每项必须有代码/配置位置，不能以一段概述代替逐项台账。

| 语义域 | 必须盘点的既有行为 | 新主链必须明确的承接/改造决定 |
|---|---|---|
| 触发机会与调度 | CRON/FIXED_RATE、手工/审批/文件/事件触发、重复 fire、misfire、pause/resume、日历变更 | Workflow occurrence 的唯一身份、触发来源、版本生效边界、暂停对日程与运行的分别作用、迟到/拒绝处置和可恢复性 |
| 批量发起与逐项结果 | Console `POST /api/console/jobs/batch-trigger`（最多 50 项）；每个 Job 请求独立触发、逐项返回 SUCCESS/FAILED，幂等键按请求键与项序号派生；与 Worker claim/report 的传输批量接口区分 | Job 根触发与 `/batch-trigger` 已退役。用户已确认保留 Workflow 多选发起、预检分开；B179 已实现复用单项 API 的 Console 有界批量发起、逐项结果、稳定键和未知结果只读核对。源码与本地定向证据已具备，真实认证联调及质量门禁未完成前不能关闭 I1。Worker `claim-batch`/`report-batch` 是执行传输接口，单独核验其实现和调用状态，不可作为批量触发替代品 |
| Job trigger 内的 dry-run 预检 | 旧 `POST /api/console/jobs/trigger` 接受 `dryRun=true`，只校验租户、Job、业务日及触发类型，不创建 TriggerRequest 或执行实例；批量入口还允许逐项混合预检和正式触发 | 旧 Job trigger 路由已退役；当前 Workflow trigger DTO 不含 dry-run 字段。独立 `POST /api/console/ops/dry-run/plan` 仍存在，按 JobCode 提供 CONFIG_VALIDATE/SCHEDULE_PLAN/EXECUTION_PLAN。不得仅凭独立接口存在就认定旧预检等价：核实 Console/SDK 调用方、权限与校验范围；若继续支持，明确 Workflow 绑定、Job 级预检、批量逐项预检和正式触发的边界，并验证无持久化/副作用；若退役，记录原因和迁移路径 |
| 批次日生命周期 | 开日、cutoff、结算/关闭、空日、人工跳过/豁免、重开及历史缺口 | BatchDay 状态迁移、覆盖证明、未决义务和在途运行如何阻断结算；跳批是否豁免业务义务、是否影响后续日期及依赖，审计和重开规则。开日只建立业务日及预期义务，不预建当天所有 JobInstance；接纳的触发请求建立 WorkflowRun，JobInstance 在对应 JOB 节点实际派发时创建。必须用持久化记录和并发/恢复测试核实各入口都遵守此时序，不能仅凭类名或设计描述认定现状符合 |
| 重放、补跑与重试 | 按日/区间/作业/失败实例重放，catch-up，人工指定日期，节点/Job/Workflow 重试，补偿与 dry-run | 明确重放范围和新 Run/attempt 身份；原业务日、参数/版本快照、结果来源、幂等键、外部副作用与审计如何保持；不同层级重试不能混为一种操作 |
| 依赖与跳过 | 同日/跨日 Job 结果依赖、Workflow 内 DAG 控制边、跨 Workflow 的 Job 结果依赖与“等待上游整条 Workflow 终态”依赖、等待超时、结果版本、失败传播、条件分支、人工 skip | 分清 Job 结果就绪和 Workflow 整体成功：上游 Job 的 EFFECTIVE 结果不能自动代表所属 Workflow 已成功；跨 Workflow 若要求整体成功，必须有 WorkflowRun 终态身份/等待记录/重放语义，不能只用 `jobCode` 或 DAG 边推断。现有依赖到底要求 Job 产物还是完整流程完成须按 main 逐项盘点并定裁；跳过还须有原因/操作者及对必需业务义务的影响 |
| 执行治理与统计 | 并发/配额/优先级/批次窗口、SLA、成功失败跳过取消计数、报表 | Workflow、节点、Job、分区/任务的计数口径和资源占用归属；控制节点不占 Worker 资源；SLA 起止点、聚合与去重键 |
| 作业分区与数据/库分片 | STATIC/DYNAMIC/AUTO 等分区数策略、动态 fan-out、`partition_key`/`input_snapshot`、Worker claim/lease/retry、按 JobInstance 汇总；业务结果分区键、PostgreSQL 表分区与租户物理分片路由 | 明确分区数和分片计划何时冻结（Workflow 接纳、JOB 节点派发或重放时），Job 配置变更是否影响已接纳 Run；同 Job 多节点/多次触发的执行分区按独立 JobInstance 隔离，稳定关联 node/run；业务数据结果键须定义同一 Job/date 多节点产出时的覆盖/并存策略；控制节点不创建执行分区、不占分区配额；DB 分片按 tenant 路由并保持 Workflow/Job/partition/task 同分片事务边界，核对迁移、RLS、归档和运维分区维护 |
| 告警与运营处置 | 作业失败/超时/SLA、依赖等待超时、漏触发、预期输入缺失、积压、结算阻塞及告警去重/恢复 | 告警实体关联 Workflow Run、节点和 Job Instance 的规则；保证原有告警不漏报/重复报，告警链接、处置权限、确认/恢复生命周期和低基数指标同步迁移。Job SLA、分区失败及 Worker 执行告警继续按 Job/分区/任务事实判断；Workflow 终态、批日结算和根运行无进展另按根运行与业务日口径判断，不能将单个 Job 成功投影为 Workflow 成功 |
| 配置、权限与审计 | Job/Workflow 启停、编辑/删除冲突、审批、配置包复制/导入、审计及运行查询 | 两菜单分工后的操作等价性；被多 Workflow 引用的 Job 影响提示；参数/日历/调度配置归属、租户边界、拒绝原因和审计记录 |
| 故障恢复与运维 | 服务/数据库/Kafka/Worker 停机、积压释放、旧入口残留、手工处置 | 从持久化状态恢复且不丢/重做副作用；Runbook、安全操作 API、回滚/前向修复和未决义务处理方式 |

盘点结果及决定写回本整合计划与 [TODO Master](todo-master.md)，场景进入验证矩阵 R25–R36，并映射到 I1–I5 的代码责任切片。R27/R32（告警与终态事件）、R28/R36（资源与容量）、R30/R35（权限与配置）存在覆盖交叉：每个具体场景指定一个主验收行，其余行只引用该证据并验证自身独有断言，避免重复计数或相互替代。只有文档盘点而没有必要的实现改造不算关闭；只有实现/单测而缺少旧行为基线和业务结果断言也不算关闭。未决语义须在依赖它的代码切片前解决。

### 0.1.1 I6 逐项台账（持续更新）

下表记录重点语义的来源、承接决定和证据；完整范围还包含 §0.1/§0.2 各域及 R25–R36。B173 已按 I0–I6 复核当前证据，历史局部结果不代表本行整体关闭；未决范围与质量/发布门槛仍须单独处理。基线为本节固定的 `origin/main` `ee01e97515b04fd167ed17977535ec5cab8572f8`。

| 语义 ID | main 基线路径/行为 | 生产者 | 持久化事实/身份 | 消费者/副作用 | 承接决定 | 实现切片 | R 场景 | 证据/状态 |
|---|---|---|---|---|---|---|---|---|
| JOB-TRIGGER-DRY-RUN | 旧 Console Job `/trigger` 将 `dryRun=true` 作为无副作用前置校验；旧批量入口允许同一请求混合预检与正式触发。 | Console Job trigger 调用方 | 预检不创建 TriggerRequest/JobInstance；正式项独立接纳 | 调用方在提交前检查租户/Job/业务日/触发类型；正式项进入旧 Job 根链 | **入口退役，等价性待核**：现存 `/ops/dry-run/plan` 按 JobCode 提供多级计划校验，Workflow trigger 无 dry-run 字段。需确认调用方及新旧校验项/权限/返回码对应；若保留，定义并验证 Workflow/Job 预检和批量混合项边界及零持久化；若退役，记录依据与调用方迁移。 | I6 → I1/I5 | R01、R25、R30、R36 | 旧触发路由退役；独立 DryRun Plan API 存在，但当前证据未建立与旧 Job trigger 预检的逐项等价关系，也未核实批量混合预检调用方。 |
| BULK-TRIGGER-CAPABILITY | Console `/api/console/jobs/batch-trigger` 最多接收 50 个 Job 目标，循环独立触发并逐项返回成功/失败；幂等键由原请求键和项序号派生，非整批原子事务。另有 `claim-batch`/`report-batch` 作为 Worker 执行协议，语义不同。 | Console Job 批量入口及其调用方 | 每个 TriggerRequest 独立持久化，按项幂等；批量请求本身无整批运行身份 | 人工/集成方批量发起；各 Workflow/Job 执行链；Worker claim/report | **承接已确认、源码已实现，真实联调待验收**：保留 Workflow 多选发起，复用单项 trigger 并发最多4项、每批最多50项，稳定键及原请求核对支持部分失败恢复；预检独立操作。不能把源码或单测通过写成真实部署验收。Worker `claim-batch`/`report-batch` 保留与否及实际 Worker 接入另行跟踪，不视为此能力替代。 | I6 → I1/I5；必要时扩展 Console/API | R01、R25、R30、R36 | 旧 `/batch-trigger` 已退役；新批量操作是多个独立 Workflow 请求，不新增批量事务 API。原调用方盘点已在 B170 完成，承接裁定与源码在 B179 落实；真实认证端到端及质量门禁仍开放，I1 不因旧路由返回404或局部用例通过而关闭。 |
| WORKER-BATCH-REPORT-PATH | Orchestrator 保留 `claim-batch`/`report-batch` 服务协议；Worker 的可选批量 claim 开关默认关闭，开启后仍逐任务 report。 | Worker 消费循环及 Orchestrator Task API | 各 Task 独立 lease、report 和结果；批量调用逐项回报 | Worker 批量降低 claim 往返；Orchestrator 对批量 report 逐项处理 | **本版边界明确**：生产Worker支持可选claim-batch后逐项report，不新增批量回报优化。服务端report-batch当前无Worker生产调用方，保留协议但不声明端到端生产能力；今后启用需单独实施部分失败、重试与容量验收。 | I6 → I4；Worker/Orchestrator 协同 | R15、R28、R36 | B171静态核对WorkerBatchClaimProperties、AbstractTaskConsumer与HttpTaskExecutionClient：批量claim默认关闭，启用后逐项report；Stage27覆盖实际claim批处理，不把它当作批量report证据。 |
| SCHEDULE-OWNER-IDENTITY | `batch-trigger/.../mapper/TriggerDefinitionMapper.xml` 从 `job_definition` 读取日程；`service/DefaultTriggerService.buildScheduledDedupKey` 使用 tenant/Job/fire；`batch-orchestrator/.../service/DefaultLaunchService.launch` 以 JobInstance 为顶层启动身份。 | Trigger 定义读取、Quartz callback、API 及 Console Workflow 触发 | 新 `scheduled_occurrence` 为 tenant/Workflow/fire 唯一身份；TriggerRequest 绑定 Workflow；根 WorkflowRun 关联 request，JOB 节点再关联独立 JobInstance | `DefaultWorkflowRunLaunchService` → JOB 节点派发 → Worker/查询；控制节点不创建 JobInstance | **有意变更**：Workflow 是唯一顶层日程及运行所有者，Job 保留执行配置；不保留 Job 根触发或 wrapper，系统 Pipeline 按 Job 类型固定。新库初始化及新模板只创建新模型；缺根目标失败关闭。 | I6 → I1/I2；Console/模板由 I5 承接 | R01、R02、R03、R04、R12、R25 | 旧 Job 根 HTTP 404、空库约束/种子、Quartz 复合 DAG、根/节点并发及字段链证据见矩阵；2026-10-16 新库应用至 V283、八应用 UP。历史脚本全面退出、所有配置入口/查询/统计消费者及完整 SIM 仍未联合关闭，不能据本行认定 I1/I2 完成。 |
| APPROVAL-ORIGINAL-REQUEST | `batch-trigger/.../service/DefaultTriggerService.approvePendingCatchUp` 从请求摘要重新构造 LaunchRequest，仅保留日期/Job 与审批参数，发布 Outbox 后即标 LAUNCHED；首次完整参数及区间未保存。 | 定时 misfire/BatchDay 补跑登记；Console 按业务 requestId 审批 | V281 `trigger_request.launch_payload` 保存首次完整请求；V282 原 fire 来源；pending 通过数字外键关联请求，但 API 使用业务 requestId；请求 CAS、pending APPROVED、occurrence REQUESTED 与 Outbox 同事务 | Trigger Outbox → Kafka → 根准入/持久等待；Orchestrator 实际建根后才标 LAUNCHED；Root interval/bizDate 传递至 JOB 子实例 | **有意收紧**：原日期、区间、目标、参数/版本按首次接纳保存，审批仅追加审批元数据；缺失/非法快照拒绝，不重新推算。重复恢复保留原 pending 状态及过期时间。PROCESSING 表示尚未证明已创建根运行；不得用于业务成功统计。 | I6 → I2/I4；审批代理与运营由 I5 承接 | R05、R08、R14、R26、R33 | 首红与修复、Trigger 273 项、受影响 102 项、审批/跨日 Worker E2E 2 项、完整停用 Stage 24 的原 fire/Root/Job 日期区间版本与双 SUCCESS 均见矩阵；Stage 25 同 pipeline 5000 行恢复通过。Worker 按区间实际筛选、配置变更后旧快照执行、全状态统计/归档血缘仍开放。 |
| PAUSE-EXISTING-MISFIRE | `batch-trigger/.../mapper/TriggerDefinitionMapper.xml.updateEnabled` 修改 Job enabled，常规日程查询排除停用定义；`infrastructure/scheduler/TriggerReconciler` 按 Job 重新注册/删除 Quartz，未具有独立 Workflow 义务恢复准入。 | Console Workflow 启停/Trigger 调度暂停；Quartz misfire listener 和持久对账器 | 日程启停与 Workflow 执行开关分别存储；普通定时请求/首次 readiness 观察在 Workflow 行锁内检查；已有 misfire 由原 tenant/Workflow/fire/request/bizDate/version + provenance 证明 | 常规 Quartz 日程停止；独立恢复 Job 不重新启用日程；已审批原 CATCH_UP → Root → 子 Job；租户暂停、Job 执行准入及原审批仍有效 | **有意变更，用户已确认**：完整停用 Workflow 或仅暂停日程都保留并继续恢复既有 misfire，新普通请求不放行。已有日程 readiness 等待按自身准入推进，不以任意调用创建 WAITING 状态绕过暂停；不恢复终态、不把普通 CATCH_UP 自报当授权。旧“停用后等重新启用”的实现/历史验证不再是验收目标。 | I6 → I2；Console/Runbook 由 I5 承接 | R05、R06、R08、R26、R30 | 真实暂停 1→4 首红、数据库双事务锁序及 readiness 定向 57 项，完整 Trigger 273 项；Stage 24 原 2 秒 cron 暂停 1→1、真实 callback 后完整停用且双 SUCCESS/保持停用；dedup 1/1、Outbox 12/12、storm 80/80、残留 0。完整应用进程崩溃、多实例容量与授权浏览器仍未关闭。 |
| DEP-CROSSDAY-VERSION | `batch-orchestrator/.../CrossDayDependencyResolver.java` 按租户、Job、业务日解析 `result_version`，将结果 payload 注入 Workflow 节点输入；原输出缺少稳定 `resultVersionId`，`SPECIFIC_VERSION` 通过最近 50 条展示列表查找，旧版本会被误判缺失。未知策略落入空结果，必需项会无限期等待，可选项会被静默忽略。 | Workflow 配置校验及节点派发 | `workflow_node.cross_day_dependencies`；上游 `result_version.id/business_key/version_no/status` | Workflow JOB/TASK 节点输入、Worker/业务消费者 | **有意收紧配置错误语义**：仅接受已支持的策略、选择器和 scope；未识别值在启用校验及运行期均失败关闭。解析结果携带 `resultVersionId`；指定版本按租户+业务键+版本号精确查询，不经过展示分页且不回退到其他版本。JOB 节点会将冻结的 `crossDay` 参数传入子 Job；JOB_RERUN 再从来源 JobInstance 的持久参数快照复用该结果，并核对来源 Workflow 快照中的依赖定义未改变，不能静默解析到新版本。inline JSON 被解析为 `output` 对象，handler 按 `output.<key>` 消费且不能重查 latest。当前 resolver 对 `EXTERNAL_REF` / `FILE_RECORD` 失败关闭，外部存储支持仍未实现；只有读取、租户授权、保留期与恢复契约经验证后才能扩大支持范围。 | I6 → I4；配置入口由 I5 承接 | R07、R17、R29、R33 | 最终单测 `./mvnw -o -ntp -pl batch-orchestrator -Dtest=CrossDayDependencyResolverTest,WorkflowGraphValidatorTest,ResultVersionQueryServiceTest test` 48/48 通过；真实 PostgreSQL `CrossDayDependencyReconcilerIntegrationTest` 3/3，包含 51 版本中精确命中第 1 版并确认其他租户无权命中。`ChildJobLaunchSupportTest` 3/3、`DefaultTaskAssignmentServiceTest` 29/29、`TaskDispatchExecutorTest` 5/5 通过；Java SDK 曾忽略 CLAIM 的 payload 而使用消息参数，现已改为 CLAIM payload 唯一业务参数来源，`PlatformHttpClientTest` + `TaskDispatcherTest` 26 项、0 failures/errors、1 skipped；SDK core 全量复跑 472 项、0 failures/errors、14 skipped。用冲突参数证明 CLAIM 胜出，用非对象 JSON 证明失败关闭；RERUN 版本固定回归当前覆盖来源版本与定义一致性。跨服务恢复及外部引用消费者未验证。 |
| COUNT-CONTINUITY-SCOPE | `CountContinuityOutboxService` 对实时定义图的所有入边无条件比较上游 outputCount 和当前 inputCount；控制依赖、数据范围及计数单位没有共同证明。 | Worker 节点产出及 `TaskOutcomeDagProgressor` 成功回报 | 节点 output、根运行快照、连续性告警 Outbox | 运维告警及连续性判断 | **实现与组件验证通过，联合验收开放**：按冻结图声明和持久化成功执行定位关系；报告范围/单位相同才核对，缺证据显式未验证。非零差额要求精确排除记账和取证引用，事件按执行 ID 去重，日志在提交后记录；不把报告一致性当作实际消费血缘证明。见 §0.1.2。 | I6 → I4；事件/观测由 I5 承接 | R17、R29、R32、R33 | 完整 SIM run `sim-harness-20261016-092352-ee01e9751` 中三个 Workflow 导入 1 行、随后导出包含既有 5 行共 6 行，产生三条 alert-only 告警；真实 DB 与脚本核对证明范围不同，不能据此判定丢行。修复已通过 33/33 单测与 PostgreSQL 7/7；本地运行包载入及 SIM 尚待复验。历史严格 WARN 审计失败记录保持，A1–A6 及 I6 不关闭。 |
| IMPORT-STAGING-LIFECYCLE | `PrivateTempFiles` 将导入中间文件写入私有子目录，但 `StaleTempFileCleanup` 仅扫描临时根；成功早返回会留下文件，按年龄补扫又缺活跃文件保护。 | PARSE / 分区过滤 / VALIDATE 与 DefaultImportStageExecutor | 当前进程文件句柄与 OS 侧锁；锁不进入 JSON 载荷或执行快照 | Worker 定时清理、其他 Worker 进程及崩溃恢复 | 运行中持锁跨越全部阶段和错误产物收尾；全部成功后回收，失败仅解锁保留取证，过期策略仍以最后修改时间为准；强杀由 OS 释放锁，清理不改业务状态或数据库。 | I4，R14/R15 恢复配套 | R14、R15、R17 | 两个失败断言先复现，修复后 71/71 定向回归与 ImportPipelineE2eIT 1/1 通过；独立 JVM 验证跨进程锁、数据已删的锁保留和强杀后孤立锁清理。最近完整 SIM 在此前版本通过，不自动代表本变更全部场景验收；目标环境/长时容量仍未验证。 |

**B173 独立 Job 预检与调用方核对（2026-10-24）**：旧批量 API 的仓内前端引用只发现生成类型，未发现手写批量调用方；旧单项 Job trigger 的手写调用方已迁到明确的受管 Workflow ID。当前独立预检仍以 `tenantId + jobCode` 定位，权限为 ADMIN/TENANT_ADMIN/AUDITOR；旧 Job trigger 预检允许 TENANT_USER，故不能认定二者权限或校验项目等价。L1 现仅核验启用 Job 定义及必需参数，返回 `validationScope=JOB`、`workflowValidated=false`，不按相同编码查询 Workflow；L2/L3 仍为指定 Job 的执行计划/只读探针。前端提示和生成契约同步，41 个定向用例及 5 个真实认证 HTTP 检查通过，运行表计数前后一致。此修复不批准旧批量能力退役，也不新增混合预检/多 Workflow 批量发起；该业务选择仍待用户裁定。外部调用方是否使用旧批量接口仍需由责任方确认。

**2026-10-24 业务承接决定（用户已确认，实施与验收中）**：用户确认“采用建议：保留 Workflow 批量发起，预检分开”。保留多选批量发起，将发起对象统一为 Workflow；预检继续独立操作，同一提交不混合只预检与正式执行。旧 Job 根接口不恢复，单个 Workflow 编排多个 Job 与多个独立 Workflow 发起保持明确区别。

- Console 多选最多50个已有 Workflow，显式指定业务日并逐项确认；复用 `POST /api/console/workflow-definitions/{id}/trigger`，每项经现有租户、启用状态、权限、参数、日历和准入校验创建独立 WorkflowRun。
- 采用有界客户端提交与逐项结果，无跨 Workflow 的全部成功事务，不引入新的父运行或第二套调度器。每项稳定幂等身份在首次提交前冻结，重试或列表排序不能改变；同一标签页按租户保存原键，刷新后在途项恢复为结果未知。重试仅针对明确失败项，网络结果未知须使用只读 `GET /api/console/workflow-definitions/{id}/trigger-status` 按原键核对；查询404仍保持未知，不能换键盲目重发。
- Job DryRun Plan 保持 `validationScope=JOB`、`workflowValidated=false`，独立显示探针和权限边界，不把预检通过当作正式运行准入或外部动作已完成。独立预检继续沿用现有权限；与旧混合接口的权限不等价，不隐式放宽角色。用户已确认预检独立操作，不再将混合提交作为本版交付能力。
- 验收包含：混合成功/失败逐项显示、一次失败不撤销已接纳项、双击与失败重试不重复建根、结果未知可核对、租户/角色/停用拒绝零副作用，以及预检零运行写入。多选入口、对应前后端定向用例与真实认证联调均完成后才可关闭该承接项。

推荐依据为本项目既有多选接口和统一 Workflow 根目标；当前仓内只发现旧批量接口的生成类型，没有手写批量调用方，因此不能据此宣称外部使用为零。[Argo submit](https://argoproj.github.io/argo-workflows/cli/argo_submit/)将多 Workflow 提交与 dry-run 分开描述，[Airflow Backfill](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/backfill.html)也单列日期预览与执行。这里借鉴其边界，不声称它们直接提供本项目的批量事务、幂等或权限实现。

**B179 当前实施证据**：Console 多选与逐项结果、最多50项/并发4项、稳定键、未知核对及刷新恢复已在源码实现。前端29项模型/API/组件用例、后端24项服务/Controller定向通过；生成类型、实际类型、i18n及fixture事实来源检查通过。真实MyBatis XML已在现有平台库核对原键/别名/租户/Workflow/缺失与停用后查询，事务回滚后3类夹具计数均0。真实认证浏览器与HTTP联调、统一运行包更新仍开放；不能据局部测试关闭I1/I6。

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

### 0.1.3 R32 终态订阅链路的实施约束（2026-10-20）

本轮核对发现：`WorkflowTerminalOutboxService` 虽注释面向 SLA/监控/Webhook，当前 `batch.workflow.terminal.v1` 的实际生产消费者只有可选 OpenLineage 血缘消费者。Console 事件目录已有 `WORKFLOW_SUCCESS/WORKFLOW_FAILED`，但当前只由控制台操作发出通用 realtime changed 事件，不能证明这两个业务事件会产生。`WebhookDispatcher` 每次 dispatch 都新增投递行；`SubscriptionRuleDispatchPolicy` 只有 Redis 滑窗及 fail-open 去重；`SubscriptionRuleWebhookDispatcher` 的渠道投递日志在发送后尽力写入。这些代码不能支持 R32 的可靠业务通知结论，不能用血缘消费通过替代。

后续实施固定在 R32 的终态迁移职责内：

1. 从已提交的 Job/Workflow 终态事实接入既有业务订阅，使用独立消费者职责，避免某个 Job 成功被解释成 Workflow 成功。事件身份包含租户、实体类型、运行 ID 和 terminal 身份；RERUN 的新 Run 是新的事件，不能按 Job/date 合并。
2. 每个订阅/渠道在确认 Kafka offset 前持久化待投递意图和稳定身份。优先沿用既有投递记录与重试器，仅增加本链需要的幂等键、状态或索引；不重建通知平台、不引入无关数据库治理。Redis 滑窗只能挡风暴，不能承担业务幂等或补偿责任。
3. 终态载荷保持原 `runStatus`、bizDate、Workflow/Job 归属及 trace；dry-run、取消和失败显式区分，不能静默变成正式成功通知。未来/未知版本在外部动作前拒绝；当前生产者固定 `schemaVersion=v2`。
4. 外部发送采用稳定事件身份，接收方支持幂等时按该协议保证唯一逻辑动作。响应丢失仍可能重发，不能宣称 HTTP 物理恰好一次；无幂等协议的渠道按 R14 的结果不明/人工处置边界处理。
5. 验证先覆盖 Job SUCCESS 而所属 Workflow FAILED、取消、dry-run、RERUN 新 Run；再覆盖并发重复、乱序到达、待投递提交前失败、Kafka ACK 后恢复及外部响应丢失。回读实际投递记录与接收端结果，并核对既有 Job SLA/告警扫描仍按自身实体处理。

当前已实现 Workflow 终态业务订阅的 Kafka 消费、同事务回执/收件人展开，以及既有两张投递表上的租约认领与 token 回写守护。V328 增加最小消费回执（强制 RLS）和两类投递记录的同租户回执外键；回执冻结首次目标，重复消息不触发后来新增的订阅。Webhook 复用稳定幂等键；无幂等声明的 provider 首次失败或中断后保留结果不明审计，不自动重发。正式成功/失败、dry-run/终止的接纳边界以及停机、次数预算已有定向证据。

R32 仍保持部分验证：Job 终态来源、SLA 当前实体复核与目录边界已补；Push 持久交付已接入并有定向 PG/HTTP 证据，实际 provider/浏览器及其它来源的生产者核对仍需收口。真实 DAG 的 Job SUCCESS/Workflow FAILED、RERUN 新 Run 到实际通知及接收端提交后响应丢失已有端到端 1/1 证据；独立 Console JVM 接收提交后强杀及认领自然到期恢复已定向 1/1 通过；人工核对后原目标重投、强审计同事务、持久请求去重、真实 HTTP/PG 与审计 API 回读已通过，配对前端类型/组件验证通过。**代码核对发现 `notification_delivery_log` 尚无归档表、归档策略白名单或归档清理入口，因而渠道投递/人工恢复审计的本地生命周期治理仍缺实现；新增归档必须先确定保留值，并保证不早于 Kafka 重放、在途认领和人工恢复窗口。**实际 provider 适配、真实浏览器与最终保留期联验仍待完成（与 R30/B4、R36 共证）。上述缺口不以本轮组件测试关闭。命令、原始失败及证据见[业务通知实施记录](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-20-b3-r32-workflow-业务终态订阅与持久投递实施)，协议见[运维说明](../runbook/workflow-terminal-notifications.md)。


#### R32 现有消费者来源核对（2026-10-20）

| 消费/观测对象 | 当前真实来源 | 本轮边界与未闭项 |
|---|---|---|
| Workflow 正式终态订阅 | WorkflowTerminalOutboxService → workflow terminal Kafka → Console 持久回执/投递 | 已实现并有真实 DAG、重投、进程恢复证据；不能把它当作 Job/SLA/Push 的生产者。 |
| Job 正式 SUCCESS/FAILED 业务订阅 | ConsoleEventCatalogController 列有 JOB_SUCCESS/JOB_FAILED；当前已由 REPORT/直接终态/派发业务拒绝事务的 JobTerminalOutboxService → 独立 Job Kafka → Console 持久接纳/投递 | 已补生产来源，真实双 Job DAG/失败根/RERUN/两类消息重投已有证据；保留 JobInstance/所属 WorkflowRun 与快照版本，复用原投递恢复，不把 Job 成功转换成 Workflow 成功。 |
| Job SLA/监控告警 | JobMonitoringMapper 的 job_instance 候选与 JobSlaViolationProcessor；实际告警类型 JOB_SLA_VIOLATION/其升级，不读取 Workflow 终态 | 继续保持 Job 粒度；扫描后终态竞争与 dry-run 隔离已先红后绿修复；首次标记与升级发送前均复核当前 Job，43 个不同定向用例已有有效通过证据（含真实 PG 行锁等待）。AM 告警已沿用持久化指纹区分 Job，单条静默/关闭与 severity 抑制按同一实体限定；真实 AM 与相关回归 13/13 通过，通知 group_by 不含指纹。目录 JOB_SLA_BREACH 无生产者，明确退役该业务事件编码；SLA 继续由上述告警通道负责。规则/Webhook 写入口拒绝这三个 SLA 编码，按最终规范化保存值校验并提示告警通道；目录补齐两个终态 Topic 和原子任务 Topic。相关后端 67/67、前端 7/7 已通过，真实浏览器与其余目录来源的盘点仍待 B4 验收。 |
| Job Web Push | Job terminal Kafka → 同事务首次决定/设备意图 → JobPushDeliveryService；ConsolePushJobNotifier 仅驱动有界队列 | 已移除 lookback/insertIgnore 旧生产路径。逐租户绑定数据库上下文，认领后事务外发送，token 防旧完成；UNKNOWN 停止自动重投，PUSH 人工恢复复用强审计。真实 PG/HTTP 的恢复、撤销/换钥、预算、停机窗口与普通角色已定向验证；本地真实签名/加密 HTTP 和独立原设备解密已通过，已关闭底层隐藏重试及客户端资源泄漏。真实 IMPORT→Job Kafka→加密 Push 的独立进程强杀/自然到期 UNKNOWN 已 1/1 通过；外部 provider/浏览器与保留期联验仍待完成。 |
| 告警升级通知 | 既有 alert_event tier → 本地升级通知 Outbox → alerts/ALERT_ESCALATED；Alertmanager 外发另有路径 | 与 Workflow 终态消费者独立，保留自身告警身份与来源；R27 继续验收实际生产/去重/恢复/授权回读。 |
| 其它事件目录与 Console 实时 Webhook | `ConsoleEventCatalogController` 声明 14 个事件类型；通用 `ConsoleRealtimeDomainEventPublisher` 的 AFTER_COMMIT listener 也将实时事件送入旧 Webhook/规则分发器 | 当前源码静态盘点确认终态四类及 `ALERT_ESCALATED` 有对应生产链；`JOB_TIMEOUT`、`FILE_ARRIVED`、`FILE_PROCESSED`、`FILE_ERROR`、`WORKER_OFFLINE`、`WORKER_DRAIN`、`ALERT_TRIGGERED`、`APPROVAL_PENDING`、`APPROVAL_COMPLETED` 未找到同码生产者。实时发布器实际还发出 `job-instance-updated`、`workflow-run-updated`、`alert-updated` 等目录外类型。目录是否应精简、补齐或将实时状态事件与业务通知分开仍待 B4 契约盘点；见[执行记录](workflow-daily-integration-execution-log-2026-10-14.md)。静态源码扫描不能替代运行时/外部生产者核验。 |


#### R32 Job 终态来源的实施身份（2026-10-20）

- Job 有独立实例、run_attempt 和 version；现有 TaskOutcomeStatePolicy 明确允许完成重试/补偿后将陈旧 FAILED/PARTIAL_FAILED 修正为 SUCCESS。不能把 JobInstance 的整个历史压成一条永不变化的 terminal 快照。
- Job 终态事件是已提交的历史事实，身份为 `tenant:job:jobInstanceId:terminal:instanceStatus`。同一实例/同一终态的重复收口只保留首次事实；失败修正为成功是另一事实，载荷保留 terminalVersion、finishedAt、JobInstance ID、runAttempt、JobCode、bizDate、所属 WorkflowRun ID 和 trace。RERUN 新实例独立。该流不把到达顺序当作当前状态；实时 SLA/监控仍查询当前 Job，不消费历史事实推断当前状态。
- 生产写入固定在 REPORT 终态收口、直接终态服务和派发业务拒绝收口的原平台事务中，读回已持久化 Job 快照后经现有 DomainEventPublisher 写 Outbox。失败必须回滚状态/Outbox；不从 metrics 的 afterCommit 路径补发。
- 使用独立 `batch.job.terminal.v1` 合同和 Console 消费组，复用既有终态回执、首次目标展开、投递与人工审计恢复，不引入第二套通知平台。两类实体键和 schema 严格隔离，未知版本拒绝。
- JOB_SUCCESS 只来自正式 SUCCESS，JOB_FAILED 来自正式 FAILED/PARTIAL_FAILED；取消、终止及 dry-run 仅留原始回执，不转正式成功/失败。Webhook 使用原稳定事实键幂等。实际 DAG 必须观察到子 Job 成功/失败各自通知与根 Workflow 失败通知，且重投不增加逻辑动作。

#### R32 Job Web Push 的收口顺序（2026-10-20）

1. **发送结果先明确**：逐设备同步有界调用区分 provider `ACCEPTED`、`GONE`、暂时失败、永久失败、`UNKNOWN` 与 `NOT_READY`。provider 接纳不代表浏览器展示/用户已读（[RFC 8030 §5](https://www.rfc-editor.org/rfc/rfc8030.html#section-5)）；接纳后的订阅时间更新失败不能改写已观察到的 ACK。404/410 只清理原 tenant/user/endpoint，不能按 endpoint 跨租删除。此步骤已实现；以下持久队列与人工恢复也已接入，最终链路验收仍按步骤 6 收口。
2. **唯一事实来源**：在既有 Job terminal Outbox 冻结提交人身份，沿独立 Job terminal Topic 消费；接纳 Job 回执与 Push 决策/首次设备投递意图同一平台事务。取消、终止保持 Job 自身语义，dry-run 不创建正式推送。替换当前 lookback + insertIgnore poller，不能保留双生产路径或把窗口外未处理当作无义务。
3. **首次接纳冻结目标**：以原 Job 事实键和 tenant/user/device 身份幂等；首次接纳记录开关、无提交人/无设备等明确决定，不在重复消费时扩展后来新增设备。后续发送重新核对同一订阅与凭据指纹，撤销或改派不得发送给新目标；载荷/tag 和点击目标携带同一 Job/tenant 身份。设备凭据沿已有订阅存储管理，不在日志或恢复列表暴露。
4. **持久认领与有限恢复**：先按租户注册表有界枚举，在每个租户事务显式 SET LOCAL 后扫描 FORCE RLS 队列，不能把无 GUC 的全局扫描为空当作无积压。设备投递使用状态、token fencing、认领期限、次数上限和有界 FIFO 批次。`NOT_READY` 保留等待；明确拒绝可在预算内延迟重试；超时/传输中断、接纳后落库前崩溃和过期在途认领均保留 `UNKNOWN` 证据，不盲目自动重投。不能把通知 tag 当作完整幂等承诺。
5. **人工恢复与运维**：复用现有核对后重投的权限、原因/凭证、持久请求去重和强审计规则，按原设备而非当前用户全部设备恢复。保留期须覆盖 Kafka、在途认领和人工恢复窗口；清理依照消费回执与投递子项生命周期，不另建通用通知平台。
6. **验收**：先定向验证发送分类及同 endpoint 跨租户/用户清理；再验证真实 PG 的首次意图、事务回滚、重复消费、多实例认领、旧 token、每设备独立恢复、超 lookback 停机、撤销/换钥、UNKNOWN 人工处理；最后串 Job terminal Kafka 与隔离接收端、进程终止及真实浏览器。没有浏览器证据只能报告 provider 接纳，不能报告已送达/已读。

### R25 重放与补偿启动的收口契约（2026-10-20）

1. JOB/BATCH 补偿与 Replay 通过既有 Workflow 根请求受理。触发请求、首次冻结的 LaunchRequest 与平台 Outbox 同事务提交，再由现有 Trigger Launch 消费者启动；不得在未提交的补偿事务内同步调用独立快照事务。提交成功表示已受理，`launchRequestId` 可追踪；未创建子 Job 时 `newInstanceNo` 和 Job 关联保持空，不将 Workflow ID 或来源 Job 当执行实例。
2. Replay 终态回填沿原请求 `replay-{sessionId}-{entryId}`、Workflow、最新 JOB 节点及实际 Job 定位；同时核对租户、业务日、Job 编码与来源。旧来源终态、同编码未知执行和旧节点尝试不完成新条目；不保留猜 jobCode 的兼容路径。
3. 回填与 REPORT 同事务，回滚须覆盖 Job/条目/会话；同会话核算先锁会话再统计，防止并发完成丢计数。dry-run 与正式终态不匹配时不回退条目或写完成时间。
4. 异步受理后若启动请求明确 REJECTED/GIVE_UP，不能永久留未绑定的 RUNNING 条目；按原请求关联记录失败并核算会话。创建启动请求前的提交失败也须在后续周期按失败条目收口；会话计数不依赖本轮是否恰好收到拒收或终态回调。暂时传输失败仍保留既有 Outbox 恢复义务，不把受理当执行完成。
5. 验证先覆盖真实补偿启动/生产消费者、来源与执行身份、REPORT 回滚及并发，再核对受理后配置变化、dry-run、拒收和原请求重投；已有五类 Worker 提交/回报丢失证据复用。用户于 2026-10-22 批准 [ADR-049](../architecture/adr/ADR-049-replay-operation-scopes.md) 推荐范围。日期区间 Replay Operation 已实现父操作与逐日子 session 查询/详情展示，但真实受理事务/并发、日内 entry 级失败恢复和容量上限依据仍未验收；精确失败 JOB 节点已有审批前预览、一次性凭证、待审批父操作及真实 PostgreSQL/Kafka 精确节点重放证据。整 Workflow Replay 已连接加密预览快照、审批、TriggerRequest/Outbox、新 RootRun、Console 操作及 Worker 执行前能力核验。B150 当前源码真实 E2E 证明 `jdbc_mapped_export` 在原业务日、来源配置和预览冻结的跨日结果版本下经独立 Worker 成功完成；在线能力目录改为 UNKNOWN 后整图预览阻断、提交拒绝且凭证未消费。日期区间并发失败恢复已补服务级及 Console/独立 Worker 证据；B151 补上 31 日含首尾接受、32 日查询前拒绝的服务单测（7/7）；B163 进一步以真实 PostgreSQL 验证 31 个失败业务日的父操作去重、子会话有界分批、新运行日期/来源身份及 31 项完成收敛（1/1）。该证据是本地功能边界，不替代部署容量签收。其它插件/外部接收端完整副作用矩阵及严格 SIM/CI 仍待验收，R25 保持部分验证；详见 [ADR-049](../architecture/adr/ADR-049-replay-operation-scopes.md) 与[执行记录 B150](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b150-r25-整-workflow-replay-当前源码端到端)。
6. 历史重放与失败补跑按 `JobInstance.calendarCode/bizDate` 的运行快照选择候选。Workflow 拥有日历后，不再依赖当前 Job 定义上的日历字段；修改或清空当前定义不能使既有运行从原业务日候选中消失。
7. 配置选择时点按显式策略区分：`USE_ORIGINAL_CONFIG` 从来源 WorkflowRun 快照恢复；`USE_SPECIFIED_VERSION` 从同租户历史 WorkflowRun 快照精确恢复指定版本；`USE_LATEST_CONFIG` 在 Workflow 实际启动时读取届时的当前定义，随后把所选配置冻结进新 WorkflowRun 快照。LaunchRequest 尚未被消费者处理时发生的定义变更会影响 `USE_LATEST_CONFIG`，这是该策略含义；若操作要求提交时配置，必须使用原配置或指定版本策略。三种策略均需以真实 PostgreSQL 执行验证。
   - **运行模板版本独立性（B122）**：文件模板的 ID/版本独立于 Job 定义版本，禁止把 `configVersion` 的 Job 版本号用于推断模板版本。普通首次执行在子 Job 的 T1 中捕获所选模板并持久化到 effectiveParams；原配置重放只读取来源实例中实际固定的模板密文，缺失时拒绝。整 Workflow 必须在 preview/approval 中一次性固定所有节点所选模板及身份，并随 operation/RootRun 传递；不能在各子节点运行时重新读取 latest。指定模板版本缺乏独立证据时保留未完成，不以“同号版本”或当前模板代替。
8. Replay 结果策略也须在 Job 的 `rerun_policy_snapshot` 中冻结并由真实终结链消费：`CREATE_NEW_VERSION` 按 AUTO_LATEST 生成并生效新版本；`KEEP_BOTH` 和 `MANUAL_CONFIRM_EFFECTIVE` 生成 PENDING/MANUAL_APPROVAL 版本，不自动替换 EFFECTIVE；`DRY_RUN_ONLY` 只允许 DRY_RUN 结果，不能进入正式结果版本链。验证需同时断言运行快照、结果版本状态/晋升策略与原 EFFECTIVE 版本是否保留。 EXPORT 产物须区分新 Job 与同 Job 重领：默认最终对象键及临时键按平台 Job 实例隔离；同实例仅可复用归属于本实例且存储校验通过的已登记产物，新实例不得借用来源成功产物并丢弃本次生成内容。登记归属只取平台执行上下文，用户 metadata 不得覆盖；显式固定键命中其它实例或无法验证归属的已登记记录时返回冲突。整图 Replay 必须包含来源已成功节点，同一 Job 定义出现多次时分别保留节点与执行身份；验收应在来源业务数据改变后验证新产物内容，并回读来源产物字节保持不变。显式固定键尚未登记时的并发首次写入不由此归属检查保证，不能宣称已具备全局对象键互斥。
9. ATOMIC HTTP 的非幂等方法在非预期响应状态或传输/结果处理异常时必须上报 `EXTERNAL_OUTCOME_UNKNOWN` 与 `FailureClass.UNKNOWN`，按人工核对/补偿路径收口；Orchestrator 不安排 Job 自动重试，也不给死信自动重放预算。HTTP 方法响应被配置为符合 `expectStatus` 时沿原成功语义处理；GET/HEAD/PUT/DELETE 继续保留已有幂等重试策略。DISPATCH 的 API/API_PUSH 对发送后的非 2xx 或传输异常，只有 `config_json.receiver_idempotency_supported=true` 明确声明且接收端按 `Idempotency-Key` 去重时才允许有限自动重试；未声明时标记 `EXTERNAL_OUTCOME_UNKNOWN` 并进入人工核对路径，平台发送该请求头本身不构成对端幂等保证。以上规则只处理 Worker 已能观测到的失败结果；Worker 在远端提交后被强杀、REPORT 未提交且接收端不兑现幂等键的恢复仍需独立协议或人工对账，不能宣称 exactly-once。 LOCAL 与本地 NAS 存根的文件 outbox 同样受外部副作用围栏约束：开始写出可被目录消费者观察的信封后，写入或旁挂清单失败均须保留 UNKNOWN 及稳定请求/回执身份；仅在写入前校验失败时保留确定未发送失败。不得把已可见信封的清单失败当作无副作用失败自动重发。
10. UNKNOWN 外部结果的人工恢复与死信重放使用一致、可审计的契约：只有操作员在平台提交“已核实未执行”的明确确认、理由和证据引用后，才允许对应死信执行一次重试；服务端以认证主体记录提交人和审批人，并要求死信来源精确匹配唯一 UNKNOWN 任务。核实结果为已执行或仍不确定时保持阻塞；普通已知失败不能套用该授权。原失败 Workflow/Job/Partition/Task 保持终态；服务端以 V336 授权版本 CAS 消费一次性核验，并通过既有 JOB 补偿入口创建新的受管 Workflow 根运行，DLQ 成功状态、启动请求、Workflow Launch Outbox 与授权标记在同一业务事务提交。普通内部 DLQ 重放不能绕过该审批/补偿入口直接重置未知结果任务。审计记录单独持久化，即使业务事务失败仍可追溯。最终 Console→Orchestrator→新 Workflow 根端到端 1/1、真实 PG 直调拒绝及旧 JOB/BATCH 围栏 4/4、Console 身份与审批单测 8/8 通过，详见[执行记录](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-21-b11-r25-unknown-终态来源下审批后创建新-workflow-根运行)。此前“同分区原地重排”PG 用例只验证活动父实例的旧实现，不再作为终态源证据。B47 已用真实 JWT/CSRF Console→Orchestrator→新 Workflow/Worker 的 E2E 验证核实未执行、双人审批、回执丢失重试、唯一补偿命令、业务结果和审计回读，关闭本地可验的一次性 UNKNOWN 恢复运维链。R25 仍因其它 Worker/插件副作用恢复矩阵与 ADR-049 操作范围未收口而保持部分验证；真实外部系统模糊结果不由本地 E2E 声称覆盖。

**整 Workflow Replay 的 Worker 能力发现前置项（R25，2026-10-23）**：整图 preview 必须逐节点显示外部效果与重放安全能力。Worker 在既有 register 契约上报由本地执行器提供的保守静态目录；静态声明必须对该插件的所有配置都成立，按冻结配置解析的运行时能力不能通过空配置调用来推断为静态保证。Orchestrator 需按 Workflow 涉及的 worker group 读取所有 ONLINE 实例目录并比对一致性，使用冻结配置选中声明后连同摘要冻结到 Replay Operation snapshot。Worker 在 CLAIM 后、任何业务副作用前须按真实配置解析能力，并校验实际合同不超出快照声明；缺失声明、未知能力、实例漂移、目录不一致、无恢复协议或查询失败均失败关闭。不得新增 Worker HTTP 路由；不新增 R 项或宣称功能完成，追踪见[ADR-049 与 B115/B117](../architecture/adr/ADR-049-replay-operation-scopes.md#实施进展补记2026-10-23b117)。

## 0.2 主链迁移的横向影响与伴随改造

Workflow Run 成为新顶层身份后，不能只检查 Trigger 和 Orchestrator 的启动代码。必须从 `origin/main` 的身份生产点追到所有消费者，核对 SQL/Mapper、事件、API、Console、Worker/SDK、配置导入导出、监控和运维脚本。对 `jobCode/jobInstanceId/instanceNo/triggerRequestId` 的每处读写，标明迁移后应使用 Workflow、节点还是 Job 身份；发现旧根 Job 假设时实施改造，不以“全新数据库”免除新模型内部的完整性要求。

| 影响面 | 需追踪的读写方与可能断点 | 必须决定并实施的配套改造 | 最低验收证据 |
|---|---|---|---|
| 身份、幂等与关联 | Trigger/文件束/外部事件/SDK 的生产者；TriggerRequest、WorkflowRun、NodeRun、JobInstance 的 Mapper/唯一键；结果、告警、补偿、审计消费者可能仍假定根 Job Instance 非空 | 明确每类记录的权威主键、父子关联、重复投递/显式重放的不同身份；删除隐式同编码推导和空值猜测；所有新入口租户隔离 | 全链追踪同一 request/occurrence 从接纳至副作用和查询；并发重复不丢不重，缺失/伪造身份零写入 |
| 状态、终态事件与消费者 | Job/Workflow 状态机、Outbox schema/topic、SLA、通知、Webhook、审计订阅者；Job 成功可能早于 Workflow 失败或持续等待 | 定义 Job 与 Workflow 终态各自的事件和成功口径、顺序/去重键、失败/取消/干运行语义；迁移所有消费者与事件契约，不把节点产物成功当整流程成功 | 真实 Outbox→Kafka 消费；重复/乱序事件下告警、SLA 与外部动作正确且只生效一次；旧字段拒绝或新 schema 契约通过 |
| 运行观测、指标与告警 | 旧 Job 根入口的请求到实例延迟、作业 SLA/失败率、批日完成率、依赖等待、计划覆盖/misfire、Worker claim/report、Outbox/Kafka 积压、结算与通知告警；Prometheus 规则、Grafana 面板、告警路由和 Runbook | 建立 `TriggerRequest → WorkflowRun → WorkflowNodeRun → JobInstance → partition/task` 的观测身份映射。将接纳到根运行创建、根运行到 JOB 节点派发/JobInstance 创建分为独立时延；Workflow 状态与批日完成/阻塞按根运行及义务统计，Job SLA、分区失败、Worker/REPORT 仍按各自 Job/partition/task 事实统计。逐条决定旧规则保留、改造或退役；同步指标名称/查询、阈值、标签、面板、告警链接、去重/恢复及 Runbook。Prometheus 标签限低基数，不放 tenant/run/request/node/instance 等逐条 ID；详细身份由日志、审计和 trace 关联 | 已知样本覆盖单 JOB、组合/并行、同 Job 多节点、跨日 WAIT、RERUN、dry-run、根终态与活跃子 Job；对账请求→根运行→节点派发分段时延和根/Job/分区计数；关键告警触发、去重、恢复与通知失败有证据；Prometheus 规则/Helm 副本同步检查、面板查询和 Runbook 均按新身份可定位，且不把 Job SUCCESS 误报为 Workflow SUCCESS |
| 运行查询、历史、归档与血缘 | Console 列表/详情、批次日统计、报表、冷热归档、结果版本、Lineage 和数据资产查询 | 明确根运行可能无 JobInstance、一个 Workflow 多个/多次 JobInstance 的投影、分页/排序/过滤/统计口径和归档外键；补 Workflow 与节点关联查询及血缘来源，不制造虚拟父实例 | 单作业、组合/并行、无根 JobInstance、失败/归档样本端到端查询；页数/计数一致，血缘能从消费结果追到生产节点 |
| API、SDK 与自动化调用方 | OpenAPI/生成类型、运维 CLI、SDK/E2E helper、部署/初始化/验收/SIM/SQL 脚本及第三方回调 | 统一 Workflow 根 Launch、状态查询、取消/重放目标与幂等键；更新所有脚本和调用方；非法旧 Job 根调用须明确失败，不能静默 fallback | 全仓调用方清单归零或列明例外；契约生成无漂移；CLI/API 对合法 Workflow 成功、旧目标拒绝；脚本在隔离 PG/HTTP 环境执行 |
| 权限、审批与审计 | Job/Workflow 两菜单权限、审批目标/payload、跨租户查询、人工跳过/补偿/结算操作 | 将资源授权绑定到实际 Workflow/节点/Job 目标；审批批准后重新校验目标未漂移；记录操作者、原因、前后状态和影响范围 | 多角色/跨租户正负例；审批目标篡改、资源变更竞态被拒；审计可从操作追到 Run/Node/Job |
| 配置来源、导入导出与初始化 | Job 表单、Workflow 调度表单、JSON/Excel bundle、租户复制/初始化、模板/种子 | 每个字段有唯一权威所有者；单作业受管 Workflow 可稳定重建；组合 Workflow 引用可移植且导入原子；不静默丢弃仍有效的旧 Job 配置 | 空租户创建→导出→导入/复制→回读→实际运行；非法引用及冲突原子失败；调度与 Job 参数分别读回 |
| 批次日、统计与资源治理 | SLA 起止、配额/并发/优先级/窗口、成功/失败/跳过数量、空日判定、趋势仪表盘 | 明确 Workflow 控制节点是否参与计数、同 Job 多节点如何计费、节点/根终态如何聚合；更新看板、告警阈值和容量查询，不把控制节点计作 Job/Worker | 已知输入样本下前后口径对账；并行、多节点同 Job、等待/重放、空日和部分失败覆盖；claim 前限制与结算口径一致 |
| Job 分区、业务分片与库路由 | `shardStrategy`/partition count resolver、动态 fan-out、`partition_key`/`input_snapshot`、partition idempotency、Worker 运行参数、asset/result-version key、租户 DB shard 目录与 PostgreSQL 分区维护 | 保留每个子 JobInstance 独立的分区数、partition/task/lease/retry 生命周期；Workflow node/run 身份能追溯到实际分区；明确分区配置快照与晚派发/重放边界；相同 Job/date 多节点的结果资产键不得意外互相覆盖，若覆盖是预期需写明选胜规则；按 tenant 路由的根、节点、实例、分区和任务须落在同一物理分片，控制节点不得虚增分区配额 | 固定/动态/自动分区数，单 Job/同 Job 多节点/并行/fan-out/失败重试/重放；对账分区、任务、节点和 Workflow 计数；业务结果多产者冲突及跨日读取；空库迁移、RLS、归档/表分区和分片路由验证 |
| 部署、恢复与环境资产 | Quartz JobStore、Kafka topic/consumer group、DB seeds/Flyway/RLS、对象存储回调、Runbook/仪表盘/告警规则 | 统一新环境的 Workflow 身份命名空间和首次接纳边界；部署/重启后从持久状态续跑；更新回退/前向修复、积压清理和人工恢复步骤 | 新库初始化与重启恢复；Quartz/Outbox/Kafka 中断后续跑；Runbook dry-run 与隔离演练；不允许以直接改业务表作为正常恢复路径 |

执行时先用符号/字段搜索和调用图建立生产者—持久层—消费者清单，再用真实主链追踪抽样对象。每个断点必须产生归属到 I1–I5 的实现任务或明确的不适用结论；测试/静态检查只在对应改造落地后作为验收证据。验证矩阵 R31–R36 覆盖横向契约，不能替代主链功能矩阵 R01–R30。

**资源准入收口（2026-10-22）**：WAITING 候选预评估只确定窗口、路由和公平排序，不预留突发额度或消费派发令牌；真正释放在独立事务内按最新父实例状态重新准入。新 Job 按 global → fair-share group → tenant 的固定锁顺序检查并保持至状态/Outbox 提交；租户与队列启用 Job 或分区上限时，新启动与等待释放共用租户事务锁。已有活跃 Job 的后续分区仅消耗新增分区额度，不能再加一个 Job 槽位。父实例 CAS 失败时整笔释放回滚。实现与定向证据见执行日志 B22；此处未增加数据库结构或新的运行事实来源。

**恢复审批执行绑定（2026-10-22，R35）**：附带审批号的补偿、重跑、DLQ、任务/分区重放和 catch-up，执行前须校验批准动作与完整业务载荷；只排除认证流程回填的 `approvalId` 和 `operatorId`，补偿出站操作者固定取当前认证上下文。批准载荷按执行 DTO 严格还原后比较，未批准的实例、业务日、Workflow 根/节点、策略或外部动作核实证据变化均拒绝；文件目标绑定不再可关闭。中央执行器按 `RETRY` 的 `JOB_TASK`/`JOB_PARTITION` 目标派发，业务成功后才标记 EXECUTED。B26 已验证上述缺陷修复，不替代 R35 的完整多角色运行操作旅程，也不关闭 R25 中业务成功但回执丢失后的审批恢复幂等条件；后者须以执行端实际持久结果验证。

**审批补偿的执行端幂等（2026-10-22，R25）**：同一 `approvalId` 绑定唯一补偿命令，保存排除 `traceId` 和认证 `operatorId` 后的规范业务载荷 SHA-256 指纹；嵌套映射键递归排序，避免键插入顺序改变导致同一 JSON 业务输入产生不同身份。相同载荷重投在命令仍 RUNNING/FAILED 时失败关闭，SUCCESS 时返回原 `commandNo`；同审批号业务载荷变化拒绝执行。V337 在热表及归档表建立 `(tenant_id, approval_id)` 唯一索引，查询同时覆盖两处，归档写入保留指纹。该机制解决重复执行端受理，不代替 Console HTTP 回执丢失、EXECUTED 重试及完整审计旅程验收，详见矩阵 R25 和 B27 执行记录。

**R21 本地验收收尾（2026-10-22）**：在 B22 的事务配额、公平竞争与 fan-out 恢复证据上，B23 已补 PostgreSQL → 真实 Console HTTP → Chromium DOM 的 19 条对账断言。两个实际受管 Workflow 运行时，队列上限 1 保持一项 RUNNING、一项 WAITING，活跃 Job/分区均为 1；策略与队列表格读数和配置上限一致。验收同时修复 Console 队列创建/查重/查询未按 Job 引用规范编码而导致的配额失配，定向回归 4/4。R21 本地条件已满足，仅目标容量/SLA 数值签收保留 EX-03；不据此宣布发布容量通过。详见[执行记录 B23](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-22-b23-r21-console-实际读数与本地收尾)。

**R28 执行定义与分区计划冻结（2026-10-22）**：T1 选定并用于实例快照的 Job 定义必须直接进入 T2 计划构建，分片策略、默认参数和路由不得在晚派发时重新读取当前 Job 版本。资源准入仍在实际派发时读取当前租户/队列限制。CREATED 恢复复用已有启动校验读取父 Workflow 的冻结定义，并要求其 ID/版本与原实例一致；不新增旧运行或旧库兼容。`partitionPlanVersion=1` 表示分区输入格式版本，Job 定义版本另行冻结。每个 Job 接纳时确定自己的 SLA deadline/预期时长，晚派发节点不复用前驱或根运行的时间预算；数据库 `created_at` 是事务时间，测试以实际接纳调用前后时间界定 deadline。停用 Job 阻止新的 Workflow 图接纳，已接纳运行继续使用原快照；不同 Workflow 可引用同一 Job 并分别冻结接纳版本。B24 的 96 个不同用例已有最终通过证据，R28 本地销项；历史 mixed/目标容量仍由 R36/EX-03 跟踪。详见[执行记录](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-22-b24-r28-冻结分区计划与共享作业边界销项)。

### 系统资产与协议文档适配（2026-10-24）

五类资产按职责更新原文：architecture（架构/模型/原语/边界/主链/ADR）、design（业务能力与逻辑模型）、api（协议与黄金样本）、sdk（接入/通信/发布）、dict（生成参考与术语）。逐文件覆盖、修正与静态验证见[资产核查报告](../audit/architecture-and-contract-assets-review-2026-10-24.md)，各目录README提供当前入口。本次不新增运行模型或改变验收统计。

- 图与说明按实际分支采用Workflow新根、JOB按需实例化、固定系统Pipeline及冻结日期/配置；通知回执、去重台账、预期义务和Replay各自保留职责。
- 平台Replay声明/执行围栏字段存在，不代表五语言通用SDK均已消费；当前保持UNKNOWN/失败关闭。若后续开放通用SDK Replay，须完成上报、执行前匹配、非幂等开始证据及未知结果恢复的专项实现与验证，不能凭旧基础fixture关闭该范围。
- 后端WAIT支持与当前画布4类节点分开；旧TASK fanOut不是新根动态JOB展开能力。不据残留辅助代码恢复FILE_STEP、旧根Job或嵌套Workflow。
- 文档/协议静态通过不提升行为验证等级，不代替最新构建回归、CI、提供商或目标环境签收；历史ADR和实施日志保留原时点。

## 1. 基线与来源保全

整合从 `origin/main` 的 `17c245b934df0ea80234a13fad21814d8ddba29e` 建立，已有迁移最高 V235。没有将两个来源分支直接合并进 main，没有提交、推送或发布整合结果。

| 来源 | 分支 | 捕获 HEAD | 变更路径数 |
|---|---|---|---|
| Workflow / Pipeline | `docs/workflow-unification-plan` | `486ad0bed95df991e572d2021c09f50be70e0365` | 228 |
| 每日连续性 | `fix/daily-trigger-continuity` | `b1b24def1a346e7ea4435267c6564e780d4ddd4a` | 241 |

两份快照于 `2026-10-14T12:38:22Z` 捕获，有 52 个共同路径，合计 417 个来源变更路径。快照包含已跟踪与未跟踪文件、各自 HEAD 版本、补丁和逐文件 SHA-256。按各来源自己的 HEAD 做三方合并，保留 main 后续变更；不是整目录覆盖。

本机可恢复材料位于 `<Codex 数据目录>/integration-snapshots/workflow-daily-20261014T123822Z`；整合工作区位于 `<Codex 数据目录>/worktrees/workflow-daily-integration/file-batch-system`。这些本机快照没有随文档纳入 Git；后续提交前仍需保留其来源记录。

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

验收的基础设施身份以所选 `COMPOSE_ENV_FILE` 和 `COMPOSE_PROJECT_NAME` 为准。在 reset、输入清理或应用重启前必须校验 PG/Kafka/MinIO/Valkey 容器的项目标签，以及应用和 SDK 的 Kafka 地址与受管容器实际发布端口；存在的 SFTP 容器同样核对归属与映射。显式 host/docker 模式优先，自动识别只能参考当前项目的持久 Compose 归属，不能因共享栈存在或当前容器暂时停止而切换运行方式；识别失败停止执行。SFTP 的公钥文件按合法项目名分目录，探针和 Stage 20 使用同一个项目路径，避免私有测试覆盖共享环境的信任。验证记录须区分只读目标检查、脚本回归和实际完整 SIM，新增拦截通过不能替代部署切换演练。

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
| V245 节点 Job 关联与父上下文 | 保留节点归属；去除只服务旧 wrapper 的父 Job/虚拟 task 字段和调用路径 | 同图同 Job 多节点身份隔离、计数/配额/终态；不验证或实现子 Workflow 执行 |
| V246 及热/冷 Pipeline 身份 | 采用新系统 Pipeline 身份；清理仅供旧配置定义的引用 | 新模型运行归档后查询与版本追溯 |
| V261/V262 occurrence/coverage | 以稳定 Workflow 身份和有效区间设计；重读所有 ON CONFLICT、索引、统计与清理查询 | 并发唯一性、终态保护、历史覆盖、租户边界 |
| V263–V266 输入预期及请求关联 | 明确输入义务与结果义务、规则版本、目标路由、终结条件 | 重复/乱序/迟到、条件分支、失败结算 |

本轮已删除未发布的 V238/V242/V243/V244 旧数据回填/转换迁移及只验证这些转换的专属测试；全新数据库由新 API/配置流程创建 Workflow 关联、调度和 Job 参数。用户确认本次仅部署到全新空库，因此本整合分支允许改写此前已提交的 Flyway 文件，包括历史注释与新模型所需 DDL；由此产生的历史 checksum 变化是预期结果，不能将该迁移集应用到已有 Flyway 历史的数据库，也不能用 `repair` 将旧库伪装成兼容。最终 schema 中所有保留表/列仍须说明新模型用途，不以旧运行兼容作为理由。整理后必须重新执行空库全链初始化、二次 migrate/validate 和核心约束测试。新系统成为发布基线后，后续版本重新遵循已发布 Flyway 迁移不可变规则。

### 4.2 部署步骤与检查点

| 步骤 | 动作 | 放行证据 / 失败处置 |
|---|---|---|
| D0 构建冻结 | 固定后端/Console/SDK SHA、镜像 digest、schema 清单及配置版本；冻结本次 I6 基线与台账 | A1–A6、I6 台账和必要改造关闭，R01–R36 适用项有证据；容量目标已由目标环境责任方填值并有测量方法；否则禁止进入切换 |
| D1 环境隔离 | 新数据库与明确隔离的 Kafka topic/consumer group、Quartz、缓存、锁、对象输出前缀和回调地址 | 旧消息/服务无法访问新运行域；不能仅换 consumer group 后读取旧 topic |
| D2 新库初始化 | 受支持 PostgreSQL 空库初始化；二次 migrate/validate；通过新配置入口创建租户/日历/Job/Workflow/预期 | schema、RLS、外键、唯一性、种子幂等及配置引用通过；失败保持未接流量 |
| D3 停旧与确认边界 | 停止旧入口接受新工作；生成切换台账，逐项处理活跃 Job/Workflow、未完成 TriggerRequest、待审批 misfire、依赖等待/catch-up、文件/事件到达及已发起外部副作用/回调 | 每项明确“在旧环境完成 / 取消并留痕 / 等待收敛 / 经核对后由新环境重新接纳”，指定业务日与幂等身份；不得静默把旧未决工作重发到新环境；旧数据原位保留 |
| D4 新链路预演 | 仅在 I6 台账、必要改造及 R25–R36 适用验收关闭后，新环境匹配版本启动；定向租户 dry-run 后经受控真实场景验证 | 观察请求→Run→节点→任务→业务结果→结算；dry-run 不能替代真实副作用验收；任何语义缺口退回 I6，不以预演发现代替基线迁移 |
| D5 开放与观察 | 按已验场景逐步开放新系统业务；旧入口持续停用 | 至少覆盖一个完整开日→cutoff→结算周期、实际日程周期以及故障恢复；观察窗口按目标日历确定 |

D1 的 Kafka 隔离配置使用同一个 `BATCH_KAFKA_NAMESPACE`（Spring 属性 `batch.kafka.namespace`）：生产 Helm overlay 必填，所有应用服务必须取相同值；topic/consumer group、Outbox 路由、Worker 订阅、终态通知/血缘、死信和 lag 观测均须使用该前缀，Kafka 初始化及按租户建 topic/ACL 脚本也须按相同值操作。空值只保留本地单部署默认，不构成隔离证据。Trigger 的 Quartz `SCHED_NAME` 也由此部署命名空间派生（`BatchTriggerScheduler-<namespace>`），同一部署内的 Trigger 实例共享该名称加入同一集群；未设置命名空间时仅本地默认使用 `BatchTriggerScheduler`。Quartz 表仍由新数据库隔离，不改表结构或表前缀；真实 PostgreSQL 集成已证明同一 Quartz 表中不同 `SCHED_NAME` 的 Job 记录互不可见，同名节点仍形成同一集群。Redis ShedLock 与 `@DistributedLock` 复用其服务级 key 前缀，并在非空部署命名空间下增加 `<namespace>:`，空值保留本地既有前缀。`batch-common` 的 `application.yml` 显式绑定 `${BATCH_KAFKA_NAMESPACE:}` 到 `batch.kafka.namespace`。所有 Spring 管理的 `StringRedisTemplate` key 由统一 serializer 自动加 namespace，覆盖 Console 缓存、会话、幂等、实时状态以及 Orchestrator 配额/分片等 Redis key；SCAN pattern 也经 namespace 映射，读取游标时还原逻辑 key。Pub/Sub channel、绕过模板的 Bucket4j key 及实时回放原始 pipeline 写入显式使用同一 namespace。空 namespace 保持本地现有键。`BatchObjectStore` 装饰器将物理对象 key 放入 `<namespace>/`，并在读取、列举及 presign 时透明映射回逻辑 key；空 namespace 保持本地现状。Webhook 的 `callback_url` 与通知渠道中的 Webhook URL 是租户级外部目的地址，由当前平台数据库中的订阅/渠道配置决定；Flyway 只建表、不播种旧地址。不能把任意外部 URL 机械拼上部署 namespace；新数据库启用通知前必须单独创建并验证该环境的接收地址，禁止未经审查复制旧库订阅。上述实现不等于目标环境隔离验收：实际部署间 namespace 唯一性、旧消息/旧回调污染负例及目标环境切换仍需分别验证。

容量与恢复目标不得留作“有记录”或由实现者代填。D0 前由业务/平台责任方基于目标环境填写下表；没有业务量依据时标为待决并阻断 D0，不得虚构数值。

用户在容量问题上选择“根历史保持一致”，结合该问题按“跟历史保持一致”沿用历史基线处理。本地回归采用 [2026-09-20 mixed 报告](../verifications/worker-local-acceptance-and-mixed-load-2026-09-20.md) 已记录的负载：30 秒，Process/Dispatch/Atomic 各 1 launch/s、Trigger Atomic 3 launch/s、调度读取 1 user/s；预期 180 次启动均完成，观察约 240 个 HTTP 请求、错误率、各模块终态、staging、Dispatch ACK 和 Kafka lag。新模型须另行对账唯一请求→WorkflowRun→子 Job 身份，不能仅复用旧 Job 数量结论。该历史记录提供本地回归工作负载与观测依据；生产租户/日程规模、停机追平预算及保留目标仍按下表填写，不从本地 6 launch/s 推导。

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
| I0 来源与决策 | 来源快照、ADR-048 全新库与无旧兼容范围已固定；源码、运行 JAR 和配对前端身份由 B172 留证，B173 的预检增量另有文件及 JAR 条目差异证明。用户已确认保留 Workflow 批量发起、预检分开，承接源码和B182本地真实联调已完成，质量门禁仍开放，不由新库部署决定代替。 | 两份来源方案标明当前范围；后续增量不覆盖整合结果 |
| I1 契约/Schema/测试基线 | 本地新契约、系统 Pipeline、旧 Job 根/wrapper 拒绝、空库初始化与种子、配置入口及新根调用方已有 R01/R02/R18/R19/R31/R34 证据；B170 盘点 32 个根调用方，B172 完成 SIM28/28、strict21/21。B173 修复独立 Job 预检误查同编码 Workflow 和旧 Job 日程字段的缺陷，41 个定向用例与 5 个真实认证 HTTP 检查通过。**整体仍未关闭**：§0.1.1 的 Workflow 批量发起与独立预检范围已确认，源码、定向用例及B182本地真实认证联调已完成，质量门禁仍开放；质量门禁另列，未当作通过。 | 新入口合法组合、拒绝旧目标；旧入口/队列不可接纳；空库初始化/约束通过；变更清单可审查 |
| I2 统一运行与计划 | R03–R06/R23/R26 的本地 occurrence 唯一性、日期/版本冻结、分页/有界逐 fire 补齐、真实 Quartz、多实例与停用后原义务恢复已有证据。B170 补跨构建持久 pending 恢复，B171 对账历史 mixed 负载，B172 再验 Stage24/25。目标规模、积压追平预算及目标故障域分别按 EX-03/EX-04 保留；不再把早期缺少本地重启/复合图证据的记录作为当前待办。 | A1/A2/A5：全链同一 Workflow 身份；OS/容器进程终止恢复、并发恢复及有界积压下无漏触发或重复业务执行；旧根路径退出 |
| I3 义务与结算 | R10/R11/R13/R16/R26 的本地义务、coverage、接纳/释放与结算双顺序围栏、迟到拒绝、dry-run 隔离、跳批/重开及生产结算链已按矩阵留证。B168 补真实 JWT/CSRF 人工空日结算、越权拒绝和取证审计回读；B172 strict21/21。目标环境的完整真实业务周期观察属于 EX-04；不将缺少实例或单 Job 成功作为空日/整流程成功证据。 | A3：同一权威计划、完整覆盖、Workflow 业务结果及全部并发围栏通过；到此才能启用自动空日结算 |
| I4 执行与恢复 | R07–R09/R14–R17/R22/R25/R28/R29/R32/R33 的本地 DAG/WAIT、冻结参数/系统 Pipeline/模板/通道、实际消费 lineage、lease/取消/回报、重放审批及冷热查询恢复已由各行证据承接。B165–B169 收口副作用恢复和终态通知本地范围，B172 覆盖实际 Worker 主链。跨 WorkflowRun 整体终态依赖仍不支持（EX-02），外部提供商/目标故障域按 EX-04 保留；不包含子 Workflow 执行。 | A4/A5：支持的读取真实 pinning，未知消费者明确；新模型恢复不变更业务日或版本，不重复外部副作用；不得借恢复路径引入嵌套运行 |
| I5 配对交付/切换 | 两仓在同名整合分支；生成契约、配置包/ConfigSync、真实认证浏览器、人工恢复和审计已有 R19/R20/R25/R30/R33–R35 证据。B169 证明目标配置提交后审计核对不重复应用；B170 真实执行取证回放及四个调度运维动作；B173 同步独立 Job 预检说明、生成类型和中英文提示。目标部署切换/回切及上线周期仍属 EX-01/EX-04，未执行 Git 提交/发布。 | A6 及全部回归矩阵有完整证据，D0–D5 可执行；之后才具备发布评审条件 |
| I6 批量调度语义与主链横向影响迁移 | 本地语义承接按 §0.1–§0.2、R25–R36 和 B166–B173 对账，覆盖身份、审批快照、misfire、重放、告警/指标、终态通知、配置/权限、归档、调用方及运维。Worker 本版支持可选批量 claim 后逐项 report，不宣称批量 report 已接入。**仍阻断 D4/D5**：用户已确认 Workflow 批量发起、预检分开；该承接已在B182完成本地真实认证联调，质量门禁仍未完成；跨 WorkflowRun 终态依赖/目标容量/目标部署按精确 EX 范围处理；质量和发布门禁须另行通过。 | R25–R36 对应场景具备业务结果与恢复证据；所有变更有审计、权限、幂等、日期、身份、告警和观测口径；观测指标、规则、面板与 Runbook 的触发/去重/恢复证据齐全；不适用项有证据；此门槛关闭前不得进入 D4/D5 |

> 下列带日期条目保留当时实施事实；当前进度以前表、验收矩阵及 B173 为准，不把已完成的本地缺口重新列为当前待办。

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
- 运行观测：计划覆盖落后、已到期未接纳机会、等待/审批最老年龄、Outbox lag、运行无进展、结算阻塞原因、实际消费未追踪、拒绝旧载荷计数。必须按 §0.2“运行观测、指标与告警”逐项核对旧规则到新身份的承接；拆分请求→WorkflowRun 与 WorkflowRun→JOB 派发时延，分别核验 Workflow/BatchDay 与 Job/partition/task 聚合口径，并更新 Prometheus 规则、Grafana 面板、告警路由及 SLO/Runbook。指标采用低基数维度，详细 ID 放审计/日志；未完成映射和触发/去重/恢复验收，不得据现有规则数量判定观测侧完成。
- 容量与恢复：沿用 [SLO/SLI](../runbook/slo-sli-catalog.md)和 [Quartz 容量基线](../runbook/quartz-capacity-baseline.md)，在执行前记录租户数、日程数、峰值 fire、积压天数、并发、锁等待/扫描时延及恢复预算；未确定预算或无实际测量不能签收容量。
- 验收绑定具体源码/构建/环境。上游相关代码或依赖变动后重新执行受影响用例；SKIPPED、未发现测试、fixture fallback、环境失败和业务失败分别列出，不能合计为通过。
- 文档检查通过仅关闭本轮方案维护，不关闭实现阶段。最终发布仍须现行 CI/代码审查及授权；不能用文档齐全替代真实链路验收。

### R01–R36 分批验收与避免重复验证（2026-10-19）

- **按未闭合验收边推进，不按测试数量清零**：截至 2026-10-19，R01–R36 当时为 13 项已验证、22 项部分验证、1 项专项验收未开展（R24），共 23 项未关闭；该历史快照不代表当前状态，当前数量只读验收矩阵。部分验证项只补缺失的验收边，不重做已有且源码未变的证据。R 项关闭条件保持原样，不因加速而降级。
- **每个实现切片固定验证阶梯**：先编译受影响模块并运行改动直接覆盖的单元/契约测试；涉及 SQL、事务、并发、租户或恢复时，随后对该边界运行最小真实 PostgreSQL 集成组；仅当 API/SDK/Worker/Console 链路被修改或验收项要求时，运行对应跨模块/E2E。一个切片关闭前一次更新代码与矩阵，避免边改边重复跑大套件。
- **证据复用有条件**：只有当相关生产源码、迁移、调用方和测试夹具与已记录证据的源码状态一致，且没有上游契约变化时，才复用既有通过结果；目录/日志证据必须保留原始命令、结果和环境。未验证、跳过、旧源码、mock 或单测不能替代目标层证据。
- **合并运行和资源约束**：同一 Maven 模块、同一数据库生命周期的独立测试合并到一条定向命令；会并发启动多套 Testcontainers 或共享平台基础设施的测试串行运行，避免已复现的连接 EOF/共享环境竞争。禁止为每个小修复重跑全部 36 项或整套 SIM；只重跑受影响案例及被该改动失效的依赖项。
- **最终收口顺序**：所有实现批次冻结后，按模块各运行一次合并回归；再按 R 场景只跑仍缺失的业务 SIM 阶段；最后执行一次整合版全链路 SIM、严格审计和适用的全量门禁。此前已验证且输入未变的阶段不重复执行，所有失败/环境阻断保留原结论。容量、部署/DR 和业务输入仍按原责任方验收。

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

- 2026-10-17 R11 对抗式并发复核新增发现：终态收口器在无 RootRun 行锁时先读节点集合、再更新父 Run；真实 PostgreSQL 屏障复现 child `EVENT` request 与 READY 节点提交后，终态器仍按旧快照将 RootRun 置 SUCCESS。现已先行锁定并重读 RootRun，再读节点集合，并强制调用方事务；首红/首绿与 Job/Sensor 调用方回归见验证矩阵。R11/A3 仍须完成全入口终态矩阵及多实例压力验证。

- 2026-10-20 R11 人工批日终态写入口复核：真实 PostgreSQL 屏障确认人工 `SKIP/CLOSE/RELEASE` 原先未获取与 Trigger admission 共用的 settlement fence，Trigger 可在终态操作事务提交前基于旧 `CUTOFF` 状态提交。现将除只修改冻结标记的 `FREEZE` 外的批日操作统一放在该 fence 内；不新增迁移，不改变状态与审计契约。首红/首绿、受影响回归和残余边界见[执行证据](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-20-b2-r11-人工批日终态操作与-trigger-接纳共享围栏)。该入口缺口已修复，R11 仍须完成其它终态写入者对账与混合多实例业务压力验收。
- 2026-10-20 R11 自动批日跳过与已接纳 Trigger 竞态：节假日、灾害 override 和依赖人工 SKIP 现在都在写入 `SKIPPED` 前获取同一批日 fence，并通过顶层 Workflow 或父 WorkflowRun 的日历身份检查尚可派发请求；请求先接纳时延后终态跳过，Catch-up 游标停在该日直至请求派发或终结；跳过先取得 fence 时，后续 Trigger 等待并收到 `BDA01`。无数据库迁移；首红/双顺序与邻近回归见[执行证据](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-20-b2-r11-自动批日跳过与-trigger-接纳双顺序)。此修复只覆盖自动/依赖跳过写入口，R11 仍须完成其它终态写入者对账与混合多实例业务压力验收。
