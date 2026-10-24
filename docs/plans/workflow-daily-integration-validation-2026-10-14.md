# Workflow 与每日连续性整合验收总览

> **适用范围**：按全新数据库和新模型部署；旧系统兼容、旧库升级及旧历史转换不属于验收条件。契约基线为 `2026-10-14.2`，见 [ADR-048](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md)。
>
> **主验收链**：计划/事件/人工触发 → 唯一 occurrence 与 Workflow Run → 按冻结业务日派发 Job → 等待、依赖与重试恢复 → 有证据的终态或空日结算。逐项状态以[验收矩阵](workflow-daily-integration-acceptance-matrix-2026-10-14.md)为准；局部测试通过不等于整体放行。
>
> **文档导航**：按下表定位信息，避免在总览、矩阵和执行日志之间重复维护状态。

| 阅读目的 | 入口 |
|---|---|
| 先看当前进度与下一步 | [当前状态与下一步](#1-当前状态与下一步) |
| 确定工作边界和推进批次 | [执行计划与范围边界](#2-执行计划与范围边界)：[验收规则](#21-验收原则与范围红线)、[批次路线](#22-批次路线)、[工作队列与例外](#23-工作队列与外部例外) |
| 选择最小验证并查找入口 | [验证流程与证据层级](#3-验证流程与证据层级)：[按变更选择](#31-按变更选择最小充分验证)、[批次节奏](#32-批次内验证节奏)、[测试索引](#33-测试入口索引)；可执行命令见[验证命令](#4-验证命令与用例入口) |
| 判断是否达到放行条件 | [最终放行清单](#5-最终放行清单) |
| 查逐项验收状态或原始执行证据 | [验收矩阵](workflow-daily-integration-acceptance-matrix-2026-10-14.md)；[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md) |
| 查目标架构、实施顺序和切换方案 | [整合计划](./workflow-daily-integration-plan-2026-10-14.md) |

## 1. 当前状态与下一步

**B197 最新功能进展**：Quartz元数据与恢复回调保留57个不同定向用例通过（51组件+6真实JDBC）；Java SDK Error迟到重投与已入队晚执行修复62项通过（59单测+3真实Kafka/HTTP/业务PG）。此前Console编译阻断已由所属会话修复，最终SDK命令exit0。B197首次容量等待接纳已改为同事务持久化，95个不同定向/关联用例通过；完整Orch readiness/截止时间移交及WR-04候选仍开放，正式23已验证/13部分验证不变；未新增JAR、全量SIM、CI或目标环境验收。详见[功能复核](../audit/workflow-integration-functional-review-2026-10-24.md)及执行日志B196–B197。

**B192 当前阶段：功能缺陷修复与定向验证。** 当前R01–R36为23项已验证、13项部分验证，R03重新开放；B172的36/36保留历史范围。用户已将代码规约类移交另一会话，本会话不重复相关扫描或门禁。SDK相关111个不同用例组合通过，WR-02真实平台故障证据仍待；审批C01关联72项及真实PG4场景组合通过；调度S01–S03首红3例已确认，WR-02及其它候选按[功能复核](../audit/workflow-integration-functional-review-2026-10-24.md)追踪。R34 prefix 19例组合通过、归档诊断成本Probe 1/1。当前存在功能开放项，不能以历史本地36/36认定当前源码可发布。

**B182 业务增量**：批量发起通过真实JWT/CSRF浏览器与HTTP核对，明确失败只按原键重试该项，未知结果刷新后仅GET核对，初批每Workflow恰好1请求/1运行。实测发现并修复START直接到END的错误任务路由及冻结图未派发分支提前终结风险；保留END未激活的跳过收口语义。后端52个不同用例有通过证据（38+修正旧Sensor夹具后14），前端3文件31/31、6项真实认证拒绝检查通过；最终JAR两条控制图真实运行SUCCESS。两批自有夹具11类回读均0。Console/Orchestrator已更新，其他6个JAR保持原冻结版本；PMD仍166，格式/CI/发布门禁未通过，未重复全量SIM。详见执行日志B182。

**最新进展 B172**：R20/R27/R31 四文件增量已按源文件 SHA-256 核对后整合，暂存区未改变。真实 PostgreSQL 相关回归 16/16、R31 API/MANUAL 并发到实际 Import Worker 与业务表 2/2 通过，原有入口证据完成对账；R20/R31 正式销项，R27 本地范围完成。**本地完成 36/36，本地场景待办 0 项；R05/R34/R36 已在 B170–B171 完成**。正式状态为 24 已验证/12 部分验证；冻结联合门与目标环境例外独立。

> B172 联合结果：SIM **28/28**、strict **21/21**；整轮 WARN **101条**（98原策略分类、3条本轮精确证据附录，默认首次失败保留）；正常运行态默认审计 **4/4**。8后端服务UP、前端可用、真实认证开启、运行/专属夹具残留0。**用户已确认保留 Workflow 批量发起、预检分开；该承接已在B182完成本地真实认证浏览器、HTTP与运行结果核对；质量门禁仍开放，不能据36场景完成宣称整个I1完成**；目标环境例外及暂缓格式检查保持。

**B172–B190历史冻结证据（当前批次见上方B192）：B184–B190规约增量的PMD及定向行为回归已有通过证据，后续源码漂移另验，批量承接已在B182完成本地实测。** 本地完成 36/36（正式关闭 24 项 + 本地例外范围完成 12 项）。R30/R32 增量及真实认证浏览器已整合验收（B169），通知提供商目标验证保留 EX-04；R35 已修复取证生成键和日期序列化缺陷，真实认证结算、拒绝零变更及取证审计回读 1/1、取证单元 8/8 通过（B168）；R34真实取证及四调度动作、R05/R36历史mixed与跨构建/分区维护均完成（B170–B171）。已通过证据直接复用，B1–B4 冻结候选联合回归已执行一次：SIM28/28、strict21/21，整轮101条WARN按原策略98条与本轮精确证据附录3条完成分类；默认首次失败保留（B172）。

| 项目 | 当前结论 |
|---|---|
| 验收状态 | R01–R36 的状态、数量和关闭口径只以[验收矩阵 §2.1](workflow-daily-integration-acceptance-matrix-2026-10-14.md#21-当前验收状态与销项统计2026-10-23)为准；项数不等于待开发任务数。 |
| 当前批次 | B4 本地范围及 B5 冻结联合门已完成（B172）；B173 独立预检有定向证据；Workflow批量承接已确认且源码已实现，本地真实认证联调已在B182完成，质量门禁继续收口。R14–R17、R22 已验证，R25 本地范围完成；[B166 证据表](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-23-b166-r25-本地销项与剩余条件收敛)定义恢复矩阵边界。 |
| ADR-049 最近进展 | 区间受理事务、并发失败恢复、整图一次性预览与审批快照、新 RootRun/Worker 执行、冻结结果 lineage、31 日有界执行及同 Job 多节点重新执行均已有本地证据（B113–B166）。未知/不一致能力保持失败关闭；正式容量仍按 EX-03 待签收，冻结联合门已在 B172 完成；不再把早期“尚未实现”记录当当前阻断。 |
| R11 当前结论 | 已验证 R11/A3 本地双顺序与失败关闭条件；触发/文件/事件/预期项/节点释放及终态围栏均有 PostgreSQL 证据。持续吞吐和批准目标容量不属于 R11/A3 销项，分别留在 R05/R21/R23/R36 与 EX-03/EX-04。完整证据见[验收矩阵](workflow-daily-integration-acceptance-matrix-2026-10-14.md)和[执行日志](workflow-daily-integration-execution-log-2026-10-14.md)。 |
| 暂停与后续 | B1 数据库扩项暂停，仅在可复现问题直接阻断主链时重开。B1–B5 本地证据已对账；Workflow批量发起本地验收已在B182完成；下一步收口仍失败的代码质量门禁，目标部署/容量按 EX 处理。 |

**B173 增量**：独立预检不再按 Job 编码查同名 Workflow 或校验 Job 旧日程字段；41 个定向用例及 5 个真实 JWT/CSRF HTTP 检查通过，8 类运行表前后计数相同。新 JAR 仅两个预检编译类变化，其余条目与 B172 相同；B172 的完整 SIM 仍是原冻结版本结果，本增量通过定向验证承接，未重复全量运行。两仓类型/生成契约/i18n 检查通过。

**本地场景完成不等于所有待办清零**：§0.1.1 业务范围已获确认，批量发起源码和B182本地真实认证验收已完成；B184–B190规约增量的PMD、单测/组件及真实数据库定向回归已收齐；架构/约定门及后续源码漂移按 `ALIGN-GATES` 收口，不适用 EX-01–EX-04。格式检查继续按用户要求暂缓；CI/发布门槛仍未签收。I0–I6 的当前状态已回写主计划 §5，历史实施日志保留原结论。

**B174–B175 规约收口（历史阶段）**：抑制登记当前通过；PMD47→40，新增原生null比较命中清零，判空门禁仍有132条字符串/集合/Optional等检查，测试规约249处待收口。Java AST定位的644个null比较保持原操作数、求值次数与顺序；全模块主/测试源码编译通过，定向实际271通过、14个SDK条件跳过、0失败/错误，未将跳过计为通过。该源码增量尚未重新打包/部署，B172全量SIM及B173运行JAR证据保持各自原版本结论。下一批先分类保留异常与未知状态语义，不将null、空容器和Optional缺失合并成同一业务事实。

**B176/B177集中收口（历史阶段）**：测试规约当前0缺口、基线未扩大；184处var改显式类型后可读性守卫通过；完整编译暴露的匿名类类型及嵌套builder名称问题已修正，Console/Orchestrator主与测试源码定向重编译通过（B178）。PMD按完整reactor与既有Mapper≥5/Service公共接口≥6/其它≥7边界的同一口径报告174条，旧40为部分模块快照，不能直接相减；状态结构首组全模块编译、76/76定向用例通过。门禁误报/漏检已分别用真实PMD与Python正反例复现，不以扩大豁免制造通过。判空、可读性、文本块守卫已通过；深层调用最终检查、其余PMD/调用方及SQL绑定、结构模板、统一包更新仍开放；具体事实和失败原件见执行日志B176/B177。主计划§0.1.1的批量发起承接方案已获用户确认，实施与验收仍开放，不能据此关闭I1或I6。

**B179 批量发起（源码实现、本地定向验证）**：已按用户裁定提供Workflow多选和逐项结果，预检独立。前端29项、后端24项定向通过；当前实际类型、生成类型和i18n检查通过。真实平台库使用生产Mapper XML验证原键/别名与租户/Workflow隔离、停用后核对及缺失身份，单连接强制回滚，3类夹具残留0。真实JWT/CSRF浏览器/HTTP与统一运行包尚待本批整合后验证，不提前关闭I1/I6。三个旧智能体原交接范围经22路径与后续运行证据核对已完成，详见B178更正，不重复列为待办。


**B180 质量结果（最新见B181）**：完整reactor主/测试源码编译2分07秒通过；后续仅注释变动以90文件可执行文本等价证明，Orchestrator夹具修复另已编译。判空、可读性、文本块、测试规约和新增Java注释语言已清零。结构模板5处已修；位置参数守卫首轮6项中1失败，6个具名参数引用提取后只复跑失败项通过，原5项结果复用。发现并修复两测试文件11处单行SQL被错误转为文本块后的反斜杠/空白回归：恢复到原暂存字面量，Sensor19/19、真实PG只解析9/9通过，1919个当前Java文件再次解析未见运行字符串行末反斜杠残留。PMD同口径174→172（55普通参数、78Mapper、13Service、26NCSS），仍未通过。批量入口新增存储失败恢复保护，当前前端不同场景累计31项有通过证据；最终运行包及真实认证联调继续开放。

**B181 增量（历史，当前见B182）**：Replay预览/恢复参数按已批准配置聚合，清理无调用的进度覆盖Mapper；启动消费与首次依赖等待按阶段拆分。修复终态对账测试未模拟先读身份而提前返回的夹具失真，补父子锁顺序/身份变化保护。9类135个不同用例有通过证据（45+12+78，其中终态对账11项原通过+失败项1/1复验），无跳过；不是完整SIM或数据库并发验收。PMD同口径当前166（52普通参数、78Mapper、13Service、23NCSS），测试规约/本批注释检查通过；B180最终前端构建/ESLint/文档结果已取回均0。下一步先完成批量入口统一运行包及真实认证联调，当前运行仍B173；详见执行日志B181。

## 2. 执行计划与范围边界

### 2.1 验收原则与范围红线

R01–R36 是联合验收要求，不是待跑测试清单。状态、数量和关闭口径只以[验收矩阵 §2.1](workflow-daily-integration-acceptance-matrix-2026-10-14.md#21-当前验收状态与销项统计2026-10-23)为准；局部通过不能关闭未满足完整条件的 R 项。按根因合并批次，一个批次可覆盖多个 R 项；同一 R 项需要多类证据时，逐类满足其关闭条件。

**每批执行规则**：开始前核对当前代码、测试和近期有效日志，将缺口分类为“实现缺口”“仅缺验证证据”或“业务/环境输入阻塞”。每次只推进一个根因批次，并冻结本批 R/I/A/REF、文件/模块范围、受影响入口、最小测试集合和证据位置；实现已存在且证据仍有效时不重复开发或重跑。批次结束时统一回写状态、证据有效性和下一批入口。批次路线是执行顺序和归属索引，不是测试清单；同一测试只有实际覆盖对应断言时才能复用，跨批次的 R 项只设一个主记录，其它位置引用该证据。

**范围与扩项规则**：本轮只修复或验证直接影响 Workflow/每日连续性主链不变量的问题。数据库身份、租户隔离、迁移和归档只作必要支撑；其它 REF 问题留在专题台账（例如 DQ 冷归档/回查缺口 REF-06）。仅当具体关系可复现地阻断主链时，才核对数据库约束、应用写入守护及租户/父子身份、NULL、更新、删除和归档语义；新迁移或应用守护还必须映射到现有 REF/R 行和明确不变量。DB-only 关系只补数据库保护与证据，不虚构应用服务；弱引用或有意例外按 REF 分类，不转成强外键。新发现只有在现行契约或负例证明既有不变量被破坏时才加入当前批次，否则登记后续治理，不改变 B1–B5 退出条件。专题方案见[数据库完整性总方案](database-integrity-index-partition-remediation-2026-10-18.md)与[引用完整性专题](database-reference-integrity-remediation-2026-10-18.md)。

### 2.2 批次路线

批次表用于说明先后和归属。B1–B4 本地范围已完成，B5 的本地历史负载、SIM/strict 和 WARN 证据见 B170–B172；目标环境签收仍按精确 EX 范围保留。B173 发现并修复独立 Job 预检的目标误判，不重跑未受影响的主链。

| 批次 | 根因/验证边界 | 主验收行 | 一次性验证方式与退出条件 |
|---|---|---|---|
| B1 数据库身份与新库完整性（暂停） | 迁移、租户/父子引用、RLS、种子、归档/清理属于同一数据库结构与生命周期边界 | R18、R20、R30、R33；关联 `ALIGN-DB-REF` | 暂停新增扩项。仅当当前主链出现可复现的直接阻断时才按关系家族重开；届时复用 REF 清单核对未闭环差异并合并同族验证，不做全库重扫或逐条 FK 启动 reactor。 |
| B2 调度与业务日连续性（本地批次退出已满足） | occurrence 身份、业务日、日历变更、依赖/输入预期、结算与 catch-up 共享 Trigger/Orchestrator 状态路径 | R03–R06、R10–R13、R23、R26 | 已有重复/并发、两种结算顺序、重启恢复和无遗漏终态的定向证据。R12 全量 SIM/历史基线运行作为冻结版本联合门；R05/R23 目标容量与 R26 目标拓扑仍按 EX-03/EX-04 处理。 |
| B3 Workflow 执行与副作用恢复（本地恢复范围完成） | DAG 派发、WAIT、重试/补跑、lease、Outbox、Worker 业务副作用和终态传播 | R01–R02、R07–R09、R14–R17、R22、R25、R32 | 先合并 Orchestrator/Worker 定向测试，再用一组真实依赖 E2E 覆盖关键跨服务失败窗口；每个失败窗口保留唯一结果断言。R29 先固定跨 Workflow 依赖的业务契约和身份语义，不凭测试方便臆造实现。 |
| B4 配置、API、Console 与运行观测（本地范围完成） | 导入/复制、鉴权、配置快照、诊断、版本/血缘和前后端契约 | R19、R27–R31、R33–R35 | 后端 API/PG 与前端生成契约、定向组件测试合并为一个接口批次；浏览器只覆盖真实认证和目标冻结等 mock 无法证明的行为。 |
| B5 容量、隔离部署与最终联合验收 | 目标规模、跨服务故障、环境切换与全链路日志审计，不属于重复跑单测能解决的缺口 | R21、R23、R24、R36 | R24 本地子证据已覆盖 Kafka 旧 envelope 拒绝、真实 PG/Kafka 新 namespace Trigger Outbox 到 Orchestrator 接纳且旧 topic consumer 未收到 request、Quartz 不同 `SCHED_NAME` 共用 JobStore 的记录隔离与同 namespace 集群、Redis key/SCAN/Bucket4j namespace、对象 key 前缀及 SIM 清理守卫；B152 已补 Trigger、终态通知、OpenLineage 与 Import Worker 消费组的 broker 分配证据；B153 已补 Export/Process/Dispatch/Atomic 独立 Worker 的真实 broker 分配证据（4/4）。B154 已在旧 SIM 与 8 个本地 JAR 服务仍运行时，真实启动并重启独立 Compose 项目的 SFTP/MockServer；容器名、network、SFTP 卷和宿主端口隔离，标记重启后保留，旧容器 ID/健康及 JAR PID 不变，临时资源已清理。R24 本地隔离子项收尾，目标环境切换/回切仍属 EX-01。Webhook URL 属于平台数据库内租户配置的新环境外部目的地址，不按 namespace 改写；新库启用投递前须单独配置并验证，禁止未经审查复制旧库订阅。之后取得容量阈值并证明目标环境 DB/Kafka/对象存储/端口/Worker 隔离，再在源码冻结点串行执行必要的 SIM、strict、WARN 审计和清理。没有隔离证据或验收阈值时保持未验证，不用本地单测替代。 |

R24 是隔离部署门槛，R29 是业务契约边界，R05/R12/R21/R23/R36 的目标容量签收依赖经批准的规模和恢复预算；这些门槛不能靠增加本地测试次数关闭。推进记录分别标明**实现**、**定向验证**和**联合验收**，不合并成单一百分比。

### 2.3 工作队列与外部例外

未关闭 R 项数不等于本地开发任务数。按缺失断言和证据类型分流；EX-01–EX-04 的精确范围及重开条件见本节下方。

| 工作轨道 | 范围 | 执行与收尾规则 |
|---|---|---|
| 本地实现与定向验证 | B1–B4 中可在当前分支、自动化测试或本地隔离环境复现和验证的缺口，包括迁移与应用守护、API/SDK/前端契约、SIM、strict/WARN 分类、故障注入、恢复脚本、Runbook dry-run、资源清理和跨服务本地 E2E。 | 按根因批次冻结文件/模块、受影响入口和最小测试集合；同一 Maven reactor 一次合并运行。源码、迁移、fixture、配置和入口未变时复用仍有效的证据。格式检查按用户要求暂缓，仍属于未执行的本地工作。 |
| 冻结版本联合回归 | 跨 Trigger、Workflow、Worker、Console 的运行链路。 | B1–B4 完成并冻结候选 SHA 后，串行执行适用的 SIM、strict、WARN 审计和清理。冻结后若主链再次变化，只重验受影响场景，并在新冻结点完成必要联合验收；不在每个小补丁后重跑全链路。 |
| 外部环境或业务输入 | R24 隔离部署；R29 可选的跨 WorkflowRun 终态依赖；R05/R12/R21/R23/R36 的目标容量签收；D1/D4/D5 目标环境执行与运行期观察。 | 等待相应拓扑、身份、外部端点、发布窗口、批准的容量/恢复目标或业务契约输入。R29 无具体业务用例前不新增能力；R24 等待隔离环境；目标容量签收等待目标规模、停机积压和追平时限等 D0 输入。 |

测试较慢、尚未执行或失败都不是外部例外理由。WARN 审计的原始输入、分类计数和退出结果见[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)。非严格启动审计未阻断启动，不代表审计通过；存在 `UNKNOWN` 或 `ACTION_REQUIRED` 时，严格审计按失败处理。2026-10-21 全量 SIM 后对 `logs/current/app` 执行 WARN 审计，结果 `FAIL RUNTIME_WARNINGS: total=47 expected=31 action_required=0 unknown=16 expired=0`；当前 16 条 UNKNOWN 包含本轮 SIM 的死信创建、UNKNOWN 自动重试阻止与 lease reclaim 等 WARN，未据此扩大策略白名单。 2026-10-22 当前源码全量 SIM 04–30 再次全部通过；按 `ta/tb/default-tenant` SIM 死信、`default-tenant` Atomic UNKNOWN 防重试和 `ta` Stage 25 lease reclaim 的精确 WARN 文本及同日志 local profile 加入三条分类规则，`test_runtime_warning_audit.py` 35/35 通过；当前全量审计为 `PASS RUNTIME_WARNINGS: total=67 expected=67`、exit 0。外租户、不同错误上下文仍由负例验证为 UNKNOWN；之前 47/16 审计保持为历史失败记录，不回写。详见执行记录 B41。较早完整 SIM 审计 `total=66 expected=50 unknown=16` 仍作历史失败记录；另有 `66/12/54` 历史快照未找到可复核的原始输入，不作为通过证据。例外只移出本节定义的精确范围，不扩展为整条 R 行。

本节只界定哪些**精确范围**暂时不计入本地收尾队列，不改变[验收矩阵 §2.1](workflow-daily-integration-acceptance-matrix-2026-10-14.md#21-当前验收状态与销项统计2026-10-23)中的 R 状态。所有能在本地编码、自动化测试或隔离 SIM 中完成的子项仍留在本地队列。EX-01/03/04 仍须在对应发布阶段取得环境或容量签收；EX-02 表示当前交付不支持该可选能力，不得描述为已实现。

#### 集中收尾顺序（2026-10-23）

R01–R36 的本地场景已在 B171 收口。当前仅执行一次冻结候选联合门并同步最终证据；目标环境例外按 §3.3 保留，旁支问题登记后退出本批。

| 顺序 | 范围 | 本批退出条件 |
|---|---|---|
| 1 | R35、R30 | 真实认证人工空日结算与审计回读；Config Sync 提交后审计故障的结果确认。优先争取 R35 本地销项。 |
| 2 | R20、R27、R31、R32、R34 | 先按现有扫描/告警/来源/调用方盘点缺失证据，再只补实际缺口；R32 保留生命周期是真实实现缺口。浏览器、脚本及 API 共用同一受控环境串行验收。 |
| 3 | R05、R36 | 一次历史 mixed 负载及跨构建/跨服务故障恢复演练，同时收集积压追平、业务副作用、运行计数和清理结果。 |

R25 已完成本地范围，不继续追加任意插件配置、厂商或远端集群组合；正式部署范围按已有例外及联合门处理。

#### 并行实施安排（2026-10-23）

原始目标仍为按本计划完整落地；不因分工缩小验收范围。主会话维护唯一验收结论，两个实施会话只在独立工作树工作，原整合工作树保留现有暂存/未暂存状态。用户已确认将 A/B 增量整合到当前特性分支并清理临时分支；仍未授权提交、推送、创建 PR 或合入 main。

| 责任方 | 专用工作树与分支 | 本批职责与文件所有权 |
|---|---|---|
| 主会话 | workflow-daily-integration，feature/workflow-daily-integration | 总计划/矩阵/执行日志/ADR/Changelog；共享 DTO/枚举、OpenAPI、Flyway、共享 fixture、运行配置和脚本；环境排队与最终整合。完成 B164 的记录后，本批不再并行修改 A/B 所有的业务文件。 |
| 会话 A：R25 后端恢复 | r25-recovery-lane/file-batch-system（独立工作树），codex/r25-recovery-lane-20261023 | R25 剩余真实副作用/回报丢失矩阵；本批已选 ATOMIC HTTP 接收端产生副作用、结果 UNKNOWN 后的 reclaim 防自动重发组合场景。拥有后端 Worker 运行实现及相邻测试、R25 专属 E2E 测试；Orchestrator 恢复实现修改先列出准确文件。不改配对前端、共享合同/迁移/fixture及主记录。 |
| 会话 B：Console 恢复入口 | console-closure-lane/batch-console（独立工作树），codex/console-closure-lane-20261023 | R25/R35 的恢复交互；本批已选批量日区间 Replay 切换日期/租户后的旧预览、迟到响应与提交身份隔离；拥有相关前端页面、API 封装、工具与相邻测试、必要 i18n。配对后端只读；生成类型和共享权限/路由/客户端变更先交主会话协调。 |

**基线**：后端 HEAD ee01e97515b04fd167ed17977535ec5cab8572f8、前端 HEAD 662d254e2f8b97f93021de548815470d2291c997 加两仓当时全部非忽略源码改动；补丁、非忽略未跟踪文件与 SHA-256 清单保存在主工作树 tmp/log-artifacts/coordination/20261023-parallel/。两个分工各有完整配对仓副本；逐路径校验后端 6791、前端 1329 个路径与快照一致，含 3 个审计 CSV 的原始换行字节。各分工 Git diff HEAD 包含继承基线，不能将整份 diff 当本会话新增修改。

**本批整合结果**：A/B 已完成交付，7 个增量文件已合入原整合工作树；本轮追加的类型修复和 Spark 入口由主会话按明确文件归属处理。临时分支已按用户要求清理，源码及证据可从 `tmp/log-artifacts/coordination/20261023-parallel/integration-20261023/` 恢复；后端工作树归档请求仍待桌面执行。后续仅在主整合工作树推进，旧分工不再持有写权限。

**原派发会话**：A `01a168bf-9efd-7030-bbce-e9c78aa78176`；B `01a168bf-b17d-7163-a881-ceb629113ffc`。任务 ID、路径、文件归属及环境时段同步记录在 tmp/log-artifacts/coordination/20261023-parallel/assignments.json。

**冲突与环境规则**：各会话以快照清单计算自身增量，报告准确文件/命令/原始错误/结果/未验证边界，不修改另一路或原整合目录。初始后端真实依赖测试时段归 A；B 只并行运行本工作树的类型/契约/单元测试，真实浏览器与服务联调等主会话排队。Maven 不 install、不并行 reactor，不复用原工作树 target；不复制 .env、密钥、node_modules 或构建目录。共享合同有必要变动时先记录需求，主会话裁定后统一改，不擅自加白名单/数据库结构。不得启动全量 SIM、格式批改或未授权环境清理。

**整合**：主会话先核对增量文件是否在归属范围，再按任务导出相对快照的补丁；核对原工作树同一文件自快照后未变化再应用，有变化则逐段处理，禁止整文件覆盖。每批只跑受影响回归并更新矩阵，冻结后集中联合验收。该并行安排不自动关闭任何 R 项或替代最终验收。

#### EX-01：R24 目标环境切换

- **暂时例外**：依赖真实目标拓扑、真实旧消息/回调来源及业务窗口的部署级切换和回切演练。
- **本地子项已收尾**：旧消息/回调隔离负例与零写入证据见 R24 矩阵及 B100/B152；跨工作树生命周期脚本 PID 守卫见 B45；Compose/SIM 旧新资源共存、启动/重启和精确清理见 B154。本地可复现的污染路径已收敛。
- **重新开启**：获得隔离环境和业务切换窗口后、D1/D3/D4 前，完成演练并记录目标版本、旧消息边界、副作用对账和回切结果。

#### EX-02：R29 未纳入的可选能力

- **暂时例外**：当前没有具体业务用例要求“等待指定 WorkflowRun 整体终态”的跨 Workflow 依赖；按当前契约将此能力标记为不支持/不适用，不为它新增代码或测试。
- **仍属本地队列**：R29 其余本地语义，包括分支、skip、失败传播、重放及现有跨 Workflow Job EFFECTIVE 产物依赖。不得把 Job 结果依赖描述成 WorkflowRun 成功依赖，也不得猜测目标运行。
- **重新开启**：业务方提出真实用例并批准目标身份、发生机会/业务日、超时、失败传播和重放语义后，重新开项设计和验收。

#### EX-03：R05、R12、R16、R21、R23、R36 目标容量签收

- **暂时例外**：仅指需要经批准的生产规模、最大停机/积压 horizon、目标追平时限、DB/Kafka/连接池预算及保留/SLA 阈值才能作出的目标容量签收。历史 mixed 负载基线可支持本地对照，但不能推出这些目标值。
- **本地已完成**：历史 mixed 负载本地对照、R05 跨构建升级窗口、有界并发新到达/积压推进及 R36 分区维护/有限 Runbook 验证均见 B170–B171；不另增无终点的持续负载门槛。R21 本地配额原子性、公平竞争、fan-out 恢复和 Console 实际读数已在 B22/B23 收尾；R12 当前源码 full SIM、R16 原定本地 dry-run 契约、R23 有界追平与不饥饿逻辑已有矩阵所列证据，不再列作本地待办。仅目标规模和 SLA 数值签收暂缓。
- **重新开启**：目标责任方确认规模与恢复预算后，在目标或等价隔离环境执行测量；结果进入 D0/D4 签收。

#### EX-04：D1、D4、D5 目标环境发布与运行期验收

- **暂时例外**：仅限各 R 项明确要求 staging/生产、目标环境、真实运行观察或独立故障域的子项，包括依赖目标环境拓扑、身份提供方、外部端点和发布窗口的部署执行、至少一个真实业务周期的上线观察，以及实际备份/恢复或故障域切换结果。R26 的 Console JWT 签名密钥跨进程切换和会话重新认证执行也列入此例外；本地已确认单密钥实例互不接受对方签发的令牌，部署不得在同一流量池混用不同密钥，切换时需安排全实例密钥一致并验证重新登录。不含 EX-01 的旧/新域切换回切，也不含 EX-03 的容量目标签收。
- **仍属本地队列**：当前版本空库迁移、构建/启动、完整本地 SIM、strict-verify、WARN 原始日志分类、真实本地认证浏览器、不依赖目标发布拓扑的跨进程/服务故障注入、备份恢复脚本及 Runbook dry-run、资源隔离与清理。R34 的脚本/CLI/forensic/回调需先在本地或隔离服务逐项执行；只有必须依赖目标身份和真实外部端点的运行才属此例外。本例外不把任何开放 R 行改为已验证。
- **重新开启**：获得匹配版本的 staging/目标环境和批准的发布与观察窗口后，在 D1/D4/D5 执行并保存版本/schema/config 身份、真实请求到业务结果及结算证据、告警/WARN 处置、备份恢复或故障域恢复结果；再按各 R 行条件更新，不以本例外代替验收。

**计数规则**：EX-01 覆盖 R24 的目标环境切换/回切范围；EX-02 覆盖 R29 的可选业务能力；EX-03 只覆盖所列 R 项中的目标容量签收部分；EX-04 只覆盖各 R 行明确要求的目标环境发布/运行期证据，并排除 EX-01/03 已分别界定的部分。四类例外下对应 R 项的适用本地范围已完成，合计 12 项（R25 见 B166，R27 见 B167，R32 见 B169）；连同正式关闭的 24 项，本地收尾完成数为 36/36。对应 R 行仍保留“部分验证”，不据此改成“已验证”或正式关闭，也不把例外当作 D0–D5 放行证据；本地队列只排除上述精确范围。EX-01/03/04 不能作为部署验收通过证据；EX-02 须在交付范围中明确“不支持跨 WorkflowRun 终态依赖”，如无业务用例则作为不适用的可选能力处理。

## 3. 验证流程与证据层级

本节的 L0–L4 是**验证证据层级和可复用入口**，不是每轮开发必须从 L0 顺序跑到 L4 的流水线。实际执行以 §2.2 的 B1–B5 根因批次为单位：先确认本批变更触及的行为和依赖，再选能证明该不变量的最小验证层；同一批受影响测试合并成一次 Maven reactor 命令。Maven 不加 `-T`；不同测试写同一 `target` 或共享依赖时串行。先读命令输出和 Surefire XML，确保选择类实际执行而非零测试/跳过。

### 3.1 按变更选择最小充分验证

| 变更范围 | 本轮最小验证 | 批内扩大条件 | 不自动触发 |
|---|---|---|---|
| 单一状态机/纯业务规则 | 目标单测方法或类 | 同一实现被多个入口复用，或改了共享状态转换 | 全模块、SIM、全仓回归 |
| Mapper、事务、RLS、并发或迁移 | 目标 PostgreSQL 集成类；涉及迁移时合并空库迁移/校验类 | 同一身份/生命周期批次内确有受影响的归档、清理或应用入口；R18 的完整 schema/seed 独立对账作为 B1 一次性退出门槛 | 不因无关数据库改动重复跑完整 manifest；未完成的 R18 manifest 不得因定向迁移类通过而关闭 |
| API/SDK/前端契约 | 受影响 API/契约用例及对应生成类型检查 | 契约生成源或共享 DTO 变化时扩大到所有直接调用方 | 与契约无关的后端 SIM |
| Quartz/Kafka/Worker 跨服务链 | 对应失败窗口的定向集成/E2E 或单个 SIM Stage | 改动穿过多个服务边界或影响真实副作用时 | 每次改动都跑 `sim-harness.sh all` |
| 环境隔离、容量、最终切换 | 对应隔离环境/批准阈值下的演练 | 只按各自验收方案扩展 | 用单测或本地空库替代环境验收 |

命令列表是入口参考，不要求逐条照序执行。每批记录一次最终命令、实际执行的不同用例数、首轮失败及其定向修复、未执行项和证据边界；不要把重跑次数重复计入用例总数，也不要因局部测试通过就改写整行 R 状态。

### 3.2 批次内验证节奏

每个批次固定采用以下节奏，避免反复启动整套环境：

1. **首红/定位**：新增行为测试时只运行目标方法或目标类，确认它能复现缺口；无需为纯验证缺口制造代码改动。
2. **修复回归**：修复后先重跑首红类；通过后，把本批其它已确认受影响的类合并到一条命令。首轮命令中断 reactor 时，只补跑未执行的目标类，不重跑已通过部分。
3. **证据复用**：源码、迁移、fixture、配置和调用入口未变，且已有日志对应当前 HEAD 与同一验收断言时，复用原证据；发生相关变化才使证据失效。一个测试可支撑多条 R 行，但只关闭它实际证明且该行所有其它退出条件也已满足的断言。
4. **最终联合门禁**：状态主链冻结后再串行运行必要的完整 SIM、strict 和 WARN 审计一次，并做环境清理复核。最终门禁前不因单项修复反复重跑全 SIM。R24 隔离部署、R29 业务契约、R36 容量目标必须取得各自输入/环境证据，不能用本地 Maven 结果代替。

### 3.3 测试入口索引

这些入口在当前整合工作区存在；多数仍按来源分支模型编写，需按上表补充或调整，不能凭文件名认定覆盖。

| 领域 | 现有入口 |
|---|---|
| Trigger 注册/账本 | [TriggerReconcilerTest](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerReconcilerTest.java)、[TriggerScheduledOccurrencePlannerTest](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/service/TriggerScheduledOccurrencePlannerTest.java)、[ScheduledOccurrenceMapperIntegrationTest](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ScheduledOccurrenceMapperIntegrationTest.java) |
| 日期与冻结 | [CalendarBizDateResolverTest](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolverTest.java)、[WorkflowRunExecutionSnapshotTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshotTest.java) |
| 依赖与预期 | [JobDependencyWaitingLaunchServiceTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchServiceTest.java)、[ExpectedWorkRegistrationServiceTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/batchday/ExpectedWorkRegistrationServiceTest.java)、[ExternalEventExpectationServiceTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/batchday/ExternalEventExpectationServiceTest.java) |
| 结算 | [BatchDaySettleSchedulerTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleSchedulerTest.java) |
| Workflow 恢复 | [WorkflowSensorApplicationContextRestartIntegrationTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java)、[WorkflowNodeTerminalFinalizerTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodeTerminalFinalizerTest.java)、[WorkflowRunPauseResumeTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunPauseResumeTest.java) |
| 系统 Pipeline | [SystemPipelineStepConfigurationTest](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/SystemPipelineStepConfigurationTest.java)、[WorkflowNodePayloadBuilderSystemPipelineTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilderSystemPipelineTest.java) |
| 全新库/API | [LocalFlywayPlatformMigrationsIntegrationTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/LocalFlywayPlatformMigrationsIntegrationTest.java)、[BatchConsoleApiApplicationIntegrationTest](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/BatchConsoleApiApplicationIntegrationTest.java) |

旧迁移兼容测试退出本轮发布标准时，要把其保护的**仍然需要的业务语义**迁到新模型 fixture，例如参数不丢失、租户引用正确、Pipeline 身份可查；不能把相关行为保护一起删除。旧入口可达性须补负例证明已经拒绝。


## 4. 验证命令与用例入口

### 4.1 L0 文档和静态

只在文档/迁移/静态规则相关文件变更时执行对应检查；最终发布复核再补齐适用的完整 L0。格式检查依用户要求留到后续，不作为本批阻塞项。

```bash
bash scripts/python.sh scripts/ci/check-docs-structure.py
bash scripts/python.sh scripts/ci/check-doc-timestamp-policy.py
bash scripts/python.sh scripts/ci/check-code-doc-references.py
bash scripts/python.sh scripts/ci/check-changelog-sync.py
bash scripts/ci/validate-flyway-schema.sh
bash scripts/ci/check-migration-safety.sh origin/main
git diff --check
```

L0 不证明业务运行。迁移安全工具缺依赖时修复本机隔离工具环境或由正常 CI 执行；不得关闭该检查。

### 4.2 L1 测试装配、定向行为与契约

从下列入口中选择本批实际受影响的类，并将同一批目标合并运行；不要把示例命令逐条串行全跑。

```bash
./mvnw -ntp -pl batch-trigger -am -Dtest=TriggerReconcilerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -pl batch-trigger -am -Dtest=TriggerScheduledOccurrencePlannerTest,CalendarBizDateResolverTest,TriggerReconcilerTest,TriggerSchedulerFacadeTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -pl batch-orchestrator -am -Dtest=BatchDaySettleSchedulerTest,JobDependencyWaitingLaunchServiceTest,WorkflowRunExecutionSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test
```

上述为已存在的基线入口；新模型实现时按 R01–R36 添加所缺正/负例。`failIfNoSpecifiedTests=false` 用于上游模块，不允许因此忽略目标模块未执行用例。本轮 `LaunchRequestIntegrationContractTest` 中的旧请求兼容断言须改为新接口校验/旧入口拒绝证据后，才能纳入 `.2` 合同验收。

### 4.3 L2 数据库与状态恢复

只在数据库/持久状态路径相关批次选择相应集成类。共享 PostgreSQL 生命周期下合并同批目标；没有改迁移时不重复运行空库迁移类。

```bash
./mvnw -ntp -pl batch-orchestrator -am -Dtest=LocalFlywayPlatformMigrationsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -pl batch-trigger -am -Dtest=ScheduledOccurrenceMapperIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -pl batch-orchestrator -am -Dtest=WorkflowSensorApplicationContextRestartIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

测试使用共享 fixture 提供的受支持 PostgreSQL。Docker 缺失导致 `disabledWithoutDocker` 跳过时，L2 未通过。现有空库测试只断言部分表/索引，必须增加 `.2` 目标 schema、Workflow occurrence 约束、版本冻结与二次 migrate/validate 的独立断言；执行迁移成功不能替代这些结构验证。

### 4.4 L3 真实链路与配对前端

只运行受影响的真实链路场景；完整 SIM 和前端全套只在对应源码/契约冻结及最终联合验收时执行。

后端按 [验收 runbook](../runbook/be-acceptance.md)、[Trigger 运维](../runbook/trigger-operations.md)选择新模型场景。保留真实 Quartz callback、requestId、Outbox、claim/report、业务输出/校验和、版本和 BatchDay 审计。脚本 fixture 若仍用旧 Job 根入口，先改成新模型后再执行；不为凑绿恢复旧入口。seeded-fallback 只能证明补跑下游，不关闭真实 misfire 项。

前端在配对 `batch-console` 的隔离分支，从本次后端 OpenAPI 生成类型；当前可用入口包括 `npm run gen:api`、`npm run gen:api:check`、`npm run typecheck`、`npm run lint:check`、`npm run test:unit` 和 `npm run build`。执行前核对生成脚本指向本次后端契约，不能取另一个工作区的旧 API。浏览器使用新库账号和新环境，验收 Job/Workflow 双向定位、手动运行、等待原因、失败/重放、业务日详情及权限负例；登录页截图不能证明运行旅程通过。

### 4.5 L4 故障、容量与切换

按对应验收行单独准备隔离环境和业务阈值；这些演练不纳入每轮本地开发回归。

执行 R11/R14/R15/R22–R24；性能与恢复预算在运行前依据项目 SLO 填定。测试租户、任务数、火力、积压、依赖故障持续时间、恢复时间、重复/漏处理、锁等待和清理范围全部留证。目标环境未执行时只报告本地结果；不得以本地容器成功宣称生产切换可用。

## 5. 最终放行清单

> 以下均为最终联合验收的放行条件，不代表当前已满足；当前状态以 [验收矩阵 §2.1](workflow-daily-integration-acceptance-matrix-2026-10-14.md#21-当前验收状态与销项统计2026-10-23)为准，逐次命令和结果见[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)。

- [ ] I1–I6 的实现、契约/Schema 和调用方矩阵均已按新模型审查；本批失败项已保留原始证据、修复根因并复验受影响集合。
- [ ] 每个适用 R 场景都有对应实现/环境/结果证据；没有未解释的跳过，也没有以环境失败冒充产品通过；A1–A6 均有业务结果证据。
- [ ] 新模型不再调用旧根入口、旧 wrapper 或旧消息转换；新环境拒绝旧输入且不会消费旧队列。
- [ ] 真实消费版本、外部副作用和配额/统计口径通过核对；未支持能力已明确标识，未列作已实现。
- [ ] 空库初始化、配置创建、完整业务日及切换前后故障恢复达到各自验收条件；首次正式写入后的恢复不会丢失新数据。
- [ ] 同一待交付版本的 CI、代码审查和配对前端检查完成；只有全部放行条件齐备后才能标记“实现完成、可发布”。
