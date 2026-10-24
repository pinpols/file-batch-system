# TODO Master · 当前待办唯一索引

## 当前专用工作树工程治理（2026-10-24）

用户19个工作包见[逐项台账](development-worktree-governance-2026-10-24.md)，整体尚未完成。此前双仓去重快照为386个源码文件：后端362、前端24；本批模型Map后续整改的新增/修改路径与快照范围有重叠，当前全局并集尚未重算；Python守卫和回归源码单列。保留当前专用工作树及工作分支，不改main或提交。

前期[生产嵌套调用](../audit/nested-callsite-closure-2026-10-24.md)与[287个复合判断](../audit/compound-condition-readability-2026-10-24.md)完成所列静态范围；后续[测试SQL/断言](../audit/test-sql-and-assertion-governance-2026-10-24.md)提取24个SQL实参与1504个JDBC结果；[模型阶段](../audit/entity-and-constructor-governance-2026-10-24.md)改30个生产文件、48处构造和1处固定Map，并对101实体及123个多null构造逐项裁定为38整改/85保留；[后续阶段](../audit/model-map-and-constructor-followup-2026-10-24.md)现改45个长构造调用点和24个固定Mapper Map。各批文件有重叠，不简单累加。

Shell默认枚举缺口已修复，默认扫描通过；3个回归源码未运行。651项模型候选快照已处理45个长构造调用点及24个固定投影Map，余100个长构造和482个Map候选未逐项裁定。配置导入的两条主要写路径已补事务后缓存失效，并修复 dry-run 仍可能 provision 托管 Workflow 的缺口，详见[配置导入影响审计](../audit/runtime-config-import-impact-2026-10-24.md)；仅静态核对，未编译或测试。继续处理剩余模型候选、审批快照消费者、Controller/Service及事务资源，不把静态扫描替代其未完成审查。

## 当前功能收尾与会话归属（2026-10-24，B192）

用户已明确代码规约类由另一会话负责；当前R01–R36为23项已验证、13项部分验证，R03重新开放；B172的36/36保留历史范围。本会话停止相关扫描、整改和门禁，只推进功能缺陷及定向验证。当前功能清单及唯一关闭条件见[功能复核](../audit/workflow-integration-functional-review-2026-10-24.md)：SDK相关111个不同用例组合通过（含WR-01/WR-03）；B195普通SDK真实恢复、独立进程重启、无位点消费和终态404保护共43个当前专项用例组合通过；B196已修复Error后同invocation迟到重投及已入队晚执行再入，59单测+3真实恢复共62项通过；审批 C01 首红5例、关联72项及真实PG4个不同场景组合通过，本地缺陷已关闭；调度S03日期/终态子项B194关联109个不同用例组合通过，B195容量等待冻结快照与密文持久化89个不同用例通过，B196调度元数据/回调保留51组件+6真实Quartz/JDBC用例通过；B197首次容量等待原子性已修复，95个不同定向/关联用例通过；完整Orchestrator readiness与截止时间移交仍待收口；C02按租户ID清理已在B193关闭，相关21项通过；WR-02及WR-04候选继续按证据排队。历史 36/36 不表示这些当前源码缺陷已关闭。R34 prefix 历史19例保留对应源码范围；B193清理关联21例含prefix回归，不与历史19重复累加。归档成本Probe 1/1不外推正式容量签收。

## Worker/Pipeline描述漂移核查（2026-10-24）

32个内容文件的说明已修正或标注历史范围，初轮78项相关回归通过。用户要求继续处理的摘要键和格式门禁已闭环：核实原5条命中为2条真实混用、3条误报；修复代码与检查器后，28项守卫测试、摘要键全仓检查、14文件Spotless及55项Java回归通过。见[完整记录](../audit/worker-pipeline-description-drift-review-2026-10-24.md#后续摘要键与格式修复2026-10-24)，不推导全仓其他门禁或正式验收通过。

| 项目 / 所有权 | 当前状态 | 完成条件 |
|---|---|---|
| WORKER-SUMMARY-KEYS / Console、Orchestrator、Worker | 已完成：2处真实混用改用已有常量，3处误报按词法与引用来源修正检查器；28项守卫回归、全仓摘要键扫描及55项Java回归通过 | 已核对Map/SQL键值未变；真实混用正例仍拦截，无新豁免或模块依赖 |
| WORKER-COMMENT-FORMAT / Worker Core | 已完成：按用户授权修正布局，原13文件及新增分发仓库共14文件Spotless通过 | 当前范围apply/check退出0，消费路由/背压等55项Java定向测试通过；全仓其他文件未据此认定通过 |

## 数据库表必要性收尾（2026-10-24）

范围为当前开发工作树及V346平台/业务双库190张逻辑表，详见[本轮审查](../audit/database-table-necessity-review-2026-10-24.md)和[逐表清单](../audit/database-table-necessity-catalog-2026-10-24.csv)。已按用户决定实施退役和DQ归档收尾，本地定向验证完成；未修改运行库。后端276个不重复定向用例、前端8项为既有记录；本轮静态复核282个变更/新增迁移仍有73条Squawk发现位于未修改行，V336新增行定向检查0 issues；运维SQL头部检查410份资产WARN30/FAIL0，数据库注释覆盖通过。既有高风险操作预算问题仍开放；本轮未运行Flyway或数据库，详细边界见报告。后续只面向空库新部署，不新增旧库兼容。

| ID / 优先级 / 所有权 | 当前结论 | 完成条件 |
|---|---|---|
| TBL-01 / P2 / 配置、DQ、Workflow及归档 | 已裁定4张配置/版本镜像退役，DQ检查冷表保留并接通原子搬运、全行验证和冷热回查；本地定向验证完成 | 逐表裁定保留或退役；保留则验证幂等搬运、同租户冷读和热清理前置条件，退役则清理新库结构/策略/守护依赖；不因冷表存在宣称已保障留存 |
| TBL-02 / P3 / Trigger | 已在V347移除旧时间轮表及misfire旧列/约束，保持当前Workflow去重；本地定向验证完成，未执行运行库升级 | 一并清旧依赖和reset/检查引用，保留仍活跃的misfire表及Workflow去重；空库验证定时、readiness、misfire申请审批与补跑 |
| TBL-03 / P3 / 补偿 | V347退役未接通的checkpoint，清运维引用并更新手册；保留补偿命令，本地定向验证完成，未执行运行库升级 | 无明确checkpoint需求则退役预留表及运维引用；保留 `compensation_command`，不为保表扩建Saga体系；验证空库及现有补偿 |
| TBL-04 / P3 / PROCESS、数据库 | V347仅退役平台副本；业务库暂存与generation、续跑继续保留，本地定向验证完成，未执行运行库升级 | 仅清平台旧副本及初始化/清理/治理依赖；双库验证WAP、代际围栏和恢复，保留业务库2张基础表 |
| TBL-05 / P3 / Worker、Console | 旧API/页面/兼容别名/DB回退/定义ID及seed/模板引用已清理；固定系统阶段与Job参数继续保留，本地定向验证完成，未执行运行库升级 | 整体移除旧API/前端契约、步骤DB回退、seed/治理依赖和两表；保留Pipeline运行事实及step_registry；验证固定阶段、配置包与续跑 |
| TBL-06 / P3建议 / 配置治理 | 默认值表是约定的审计目录，非运行配置源；YAML/文档/种子人工维护，文档V23与实物V24漂移 | 裁定DB审计需求：需要则生成/校验目录并纠正来源，不需要则从新库去掉；保留动态 `system_parameter` 的独立用途 |
| TBL-07 / P3建议 / 通知、Console | 两套Webhook配置均有分发消费，存在功能交集；不是废表 | 只有确定统一通知入口时实施，保留订阅身份、凭据、重试、审计和终态回执语义；当前保留，不据此认定重复通知已发生 |

13张环境专用表是本地演示/验收资产，不列运行故障；生产初始化按真实业务契约隔离，当前不删除fixture。166张有保留依据不等于每个部署都必须启用全部功能。


## 架构与契约资产同步（2026-10-24）

五类目录原文、图和协议的适配见[逐文件核查](../audit/architecture-and-contract-assets-review-2026-10-24.md)及[总方案](workflow-daily-integration-plan-2026-10-14.md)。本次是文档核查，不改变R01–R36、EX或当前质量回归结论。

| 边界项 / 归属 | 当前结论 | 后续触发与完成条件 |
|---|---|---|
| SDK-REPLAY-CONTRACT / SDK与重放域 | 平台协议已有Replay能力/围栏，五语言通用SDK未消费；文档已纠正，保守不开放未知能力，不宣称本次已全部对齐 | 若要求通用SDK参与安全Replay，补声明上报、执行前核对、非幂等开始证据及结果未知恢复，并有跨语言契约与真实平台证据；不自动扩张本轮范围，不以普通claim/report通过替代 |
| WF-DESIGNER-CAPABILITY / Console | 画布目前START/END/JOB/GATEWAY；WAIT后端实现与前端编辑能力分开；旧TASK fanOut不作为新根能力 | 若需求包含画布WAIT或动态JOB mapping，先确认契约与范围，再实施/验收；当前文档只说明事实，不自行新增节点、子流程或抽象 |



## 脚本与CI索引治理复核（2026-10-24）

目录、头注释、中文说明及索引整理已实施，十项逐项证据见[本轮审计](../audit/script-and-ci-governance-review-2026-10-24.md)。不据此宣称全部质量门禁通过。

| ID / 优先级 | 当前情况 | 完成条件 |
|---|---|---|
| SCRIPT-GATE-2026-10-24-01 / P2 | Pipeline守卫原来误扫tmp历史源码，已修正并保留未跟踪源码覆盖；仍有5处当前Java键名混用命中，包含Mapper/SQL参数命名空间 | 按协议消费者复核5处命中，区分实际键名漂移与不同语义；修正应保留正反例和真实仓库断言，不扩大白名单 |
| SCRIPT-GATE-2026-10-24-02 / P2 | 高风险操作守卫仍有23个路径/类别相对历史预算增长；本轮基线只迁目录，字节不变 | 逐项评审已有迁移、清理及资源操作的目标/确认/恢复边界；仅在有证据时登记或修正误报，不全量刷新吞掉差异 |


## 定向验收工具缺口（2026-10-22）

| ID / 归属 | 事项 | 处理边界与完成条件 |
|---|---|---|
| R34-CLEANUP / R34 | 管理员按前缀清理受管 Workflow 的请求及 Outbox 闭包 | 本地范围完成：真实首红后准确身份、依赖顺序、歧义拒绝、冷热邻居保留及中途异常回滚，共19个不同用例组合通过。by-ids真实闭包在B193按独立首红及21项关联回归关闭；详见功能复核及执行日志。 |

## B191 阶段记录（规约已移交，功能结论见上方 B192）

- B191状态写入与跨域收口已定向通过：Replay3类52项、空参守卫/行为2类17项、Console原10类82项已有组合通过证据（首轮1failure/1error，final-record夹具1/1与查询守卫3/3复验覆盖）。架构原30类已执行，后续两类E2E守卫、最新R34 XML与最终PMD/运行包仍需验证。R34真实API首红已确认请求外键阻断，补丁另被复核拦住同ID跨业务日过删风险，正补歧义拒绝和邻居保留；独立归档成本Probe已实现待运行。历史B184–B190 PMD0与本地36/36各自保留原快照范围，不冒充本批最终验收；格式暂缓、CI及EX独立。
- 数据库专项仍按既有 P0/P1/P2/P3 排序。租户隔离、归档丢失、引用身份或清理破坏恢复的已证实缺口必须处理；全表加外键、冗余索引和扩展分区治理不在本轮重启。PROCESS staging 删除仍默认关闭，不因局部证据开启。数据库专项整体未关闭，亦不把专项建议全部算成本轮发布阻断。
- R01–R36 本地范围完成 36/36 指 B172 冻结构建；正式 24 已验证/12 部分验证及 EX 例外不变。最新源码还需编译、受影响回归与质量门；暂缓格式、CI 与目标环境分别列示。

## 设计模式审查待办（2026-10-21）

证据与审查边界见[开发分支设计模式审查](../audit/design-pattern-review-2026-10-21.md)。

| ID / 优先级 | 事项 | 完成条件 |
|---|---|---|
| **DP-01 / P3** | TenantConfigInitApplyHandlers 仍集中 Job、Workflow 和多种运营配置的领域规则 | 后续相关改造时按稳定领域所有权拆分处理器；保留调用方事务/savepoint、严格与部分成功统计、依赖次序和 dry-run，并由领域测试及批量初始化回归证明行为不变。当前仅登记设计债务，不阻塞无关交付 |
| **DP-02 / P3** | DefaultTriggerService 聚合人工、事件、Quartz、补跑审批和状态查询用例 | 后续相关改造时按用例拆分 command/application handler 或轻量委派门面；保留各用例的幂等身份、事务、租户围栏、occurrence 与 Outbox 语义。静态结构建议，不是已确认故障，不要求立即跨模块重构 |


## 开发分支代码、安全与性能审查（2026-10-21）

证据、定位、复现层级与验收条件见[本轮审查报告](../audit/development-branch-code-review-2026-10-21.md)。各项按下表实际证据区分已修复、待验证和后续审查，不增加或关闭 R01–R36 验收项。

| ID / 优先级 | 事项 / 责任边界 | 当前状态与完成条件 |
|---|---|---|
| DEV-REVIEW-1 / P1 | 暂停期间失败节点恢复 / Orchestrator | ✅ 已按持久化的 SUCCESS/FAILED/SKIPPED 结果恢复边并保持 skip cascade；定向单测及真实 PostgreSQL 覆盖暂停失败回报、故障回滚、FAILURE/ALWAYS 分支各一次和重复恢复。完整分支发布验收仍由 R01–R36 矩阵独立判定 |
| DEV-REVIEW-2 / P1 | START 原始参数恢复 / Orchestrator | ✅ 根参数以 `SecretPayloadProtector` 加密存入 START 快照；真实 PostgreSQL 验证首次派发前暂停后按原参数和条件边恢复，且数据库未保存明文 |
| DEV-REVIEW-3 / P2 | CLAIM payload 替换语义 / Go SDK | ✅ 有 payload 时解码到新 map；定向覆盖旧键替换、空对象清空、非对象拒绝且旧 map 不变；`go test ./client` 通过 |
| DEV-REVIEW-4 / P2 | 归档诊断重复全量汇总 / Orchestrator、数据库 | 🟡 代码修复及定向测试已有证据。2026-10-21 已有真实PG17.11、50租户/5万终态/2.5万父引用的暖缓存EXPLAIN探针（14.087/15.781/19.382秒、父引用Index Only Scan）；当时有并发测试干扰且探针源码未保留，不能当作当前受控吞吐基准。仍需可复现的多批归档对照、冷热完整性及等待/吞吐数据，目标容量独立。B190补核原件，不再写EXPLAIN从未执行，亦不因此扩展索引/分区治理。 |
| DEV-REVIEW-5 / P2 | 停用定义后已接纳启动重投 / Orchestrator | ✅ 已有根运行按冻结快照恢复；新启动仍拒绝停用定义。真实 PostgreSQL 端到端覆盖 CREATED EVENT 根运行重投并结清既有预期；单测覆盖停用定义的新预期拒绝与不匹配请求拒绝；不扩展旧数据兼容 |
| DEV-REVIEW-6 / P3 | Push Mapper 位置参数 / Console API | ✅ 四个 5–8 参数 Mapper 方法改用语义参数 record；单测和真实 PostgreSQL Push 流程 28/28 通过，覆盖决定/设备写入、claim、finish 绑定 |
| DEV-REVIEW-SEC / 待核实 | 工作区凭据候选 / E2E、安全治理 | B190已逐项核实历史9候选为2份E2E中的2个确定性测试值，旧字面值已替换；当前工作区快照3672文件发现1个Controller操作UUID误报，保留原字段断言后改运行期UUID，Controller7/7。原快照与变化文件定向补扫同配置组合覆盖3687文件、0候选，原exit1报告保留。此项有限候选核实及工作区secret扫描完成；依赖/镜像/完整SAST/DAST及CI/Sonar仍未执行，不表示全安全通过。 |


## 跨模块设计闭环缺陷（2026-10-14）

证据与修复方向见[设计闭环复核](../audit/design-closure-review-2026-10-14.md)。最后核查日期为 2026-10-16。SEAM-1 已完成两处代码修复及 PostgreSQL 定向回归，但并发交错和 Worker 报告/重派仍待验收；SEAM-2 已增加已登记对象复用与摘要漂移保护，真实对象存储/平台库重派闭环仍待验收；SEAM-3 已实现三种 RERUN 配置策略解析并固定到新 WorkflowRun 快照，定向数据库查询和 Orchestrator 全量回归通过，但实际 CLAIM 配置闭环仍待验收；SEAM-4 已修正 Worker 取消码识别且 Orchestrator 全量回归通过，跨 Worker 报告/无重派闭环仍待验收；SEAM-5 已完成真实 PostgreSQL 的取消/成功混合收口及两种 CLAIM 竞态验证；SEAM-6 已修正单条/批量 CLAIM 网络等待时间未计入租约年龄的问题，定向 32/32、Worker Core reactor 259/259、Stage 25 checkpoint/reclaim 业务 SIM 通过，但真实 DB 已回收租约后仍存活的旧 Worker invocation 被拒绝且无副作用的跨进程场景仍待验收。上述新增代码问题仍需按各自验收边界判断，不沿用历史条目的“只待环境验收”结论。未执行提交与发布。

| ID | 事项与负责人边界 | 当前证据 | 下一步 / 完成条件 |
|---|---|---|---|
| **SEAM-1** | PROCESS staging 清理与发布完整性；Process Worker / 业务库 | PostgreSQL 17.11 测试覆盖同 `ctid` 分区隔离、batchSize 上限、业务持锁时 SKIP LOCKED 清理及行迁移保留；WAP 发布数与快照不一致时事务回滚；Worker 阶段执行器将该错误记录为失败步骤 | 仍需真实平台库持久化失败步骤、完整 Worker→Orchestrator 报告/重派闭环及清理/发布负载证据；这些通过后关闭 |
| **SEAM-2** | Export 已登记产物的重试保护；Export Worker / 对象存储 | STORE 先查租户/桶/路径对应 file_record；登记摘要与对象 SHA-256 一致时沿用对象、摘要和登记安全元数据，不触发加密、上传或复制；摘要/对象异常时失败关闭。单测 2 项；`S3ExportStorageIntegrationTest` 真实 PostgreSQL 17.11 + MinIO 预置真实 AES-GCM 密文和 file_record 后重跑 STORE，确认密文可解密且摘要、记录、安全元数据不变 | 尚未实际运行 STORE→REGISTER→COMPLETE 前故障→worker/Orchestrator 重派的完整链路；该跨层重派场景验收后关闭 |
| **SEAM-3** | 补跑原配置/指定版本选择；Console 契约 / Orchestrator | 已在根 RERUN 新 WorkflowRun 的 execution snapshot 中固定被选中的完整 JobDefinition；原/指定版本从同租户、同 Job 的不可变 WorkflowRun 快照读取，支持组合 Workflow（其 single_job_definition_id 为空）；无快照、来源/版本不匹配和非整数版本拒绝 | 三种策略的实际 CLAIM 有效配置、真实 JobInstance/Task 执行、Console 原始配置与策略的端到端一致性仍需验收；当前没有可恢复快照的指定版本必须明确拒绝 |
| **SEAM-4** | 主动取消与失败重试协议；Worker Core / Orchestrator | Orchestrator 已识别 Worker `WORKER_EXECUTION_CANCELLED`，且仅在 `cancel_requested=true` 时记 Task CANCELLED 并跳过自动重试；定向结果/重试测试 35/35 通过。2026-10-16 新库 Stage 21 的真实 Atomic 取消已落库：task=106 CANCELLED、retry/dead-letter=0、仅初次派发事件 1 条。SEAM-5 修复后单跑 Stage 19 PROCESS 取消通过：实例 768、partition=FAILED、task=CANCELLED、`WORKER_EXECUTION_CANCELLED`；该结果见验证矩阵，不代表完整 SIM 通过 | 其它 Worker 的实际取消与无重派仍需验证。Atomic 父 partition/Job/Workflow 实际为 FAILED，需按 I6/R27 核定父终态与取消计数，不把 Task 取消等同于整流程取消；保留 R15 开放 |
| **SEAM-5** | 分区取消后的 task 与父实例收口；Orchestrator | PostgreSQL 17.11 空库迁移到 V284；一分区取消、另一分区成功后 task/partition/Job/Workflow 终态与计数一致，且无活跃分区；另以真实锁等待覆盖 CLAIM 先赢与取消先赢两种顺序。聚焦 41/41；Orchestrator reactor 1902/1902，0 failures/errors/skips | 本地数据库与 Orchestrator 收口闭环完成；Worker 跨进程主动取消/重派仍按 SEAM-4 验收，不归入本项 |
| **SEAM-6** | 批量 CLAIM 与续租/执行生命周期；Worker Core / Orchestrator | 单条和批量 CLAIM 现在在远程调用前采集单调时钟，并按该时刻登记成功 lease；同步确认过期 lease 后才允许产生业务副作用。新测试先红后绿；定向 32/32、`./mvnw -o -ntp -pl batch-worker/core -am test` 259/259，0 failures/errors/skips；最终 Worker 构建的真实 Stage 25 checkpoint/reclaim SIM 达到原 pipeline SUCCESS、5000/5000 行 | Stage 25 杀死旧进程后证明 lease 回收与 checkpoint 恢复，但尚未覆盖“真实 DB 已回收、旧 Worker 仍存活并恢复执行”的竞态。需补 Orchestrator/PostgreSQL 与 Worker 跨进程围栏证据，证明拒绝/超时均不产生旧 invocation 副作用。批量领取开关保持默认关闭，容量/负载验收通过前不启用 |

> **ALIGN-DB-REF 增量（2026-10-18）**：V296 为 misfire pending、V297 为 Trigger Outbox 增加同租户 `NO ACTION` 复合外键；V298 为 `job_dependency` 上游、V299 为 `job_monitoring_policy` 增加同租户复合外键；V300 为分区 `job_instance` 到 `trigger_request` 增加同租户复合外键并验证父删除保护；V301 在数据库触发器和 Console 校验间共享租户级事务锁，拒绝并发合成依赖环；V302 为日历依赖增加同租户上下游复合外键及数据库并发防环触发器，`MultiCalendarCoordinationIntegrationTest` 全类21/21通过。 V303将calendar_holiday升级为同租户复合外键，Console预检身份与数据库引用键一致，日历协调22/22、Console日历类5/5通过。 V304为business_calendar与calendar_holiday.group_code增加同租户calendar_group复合FK，并要求GROUP假期代表日历属于该组；`MultiCalendarCoordinationIntegrationTest` 当前23/23通过，见专题§2.19。V305为热 `workflow_node_run.job_instance_id` 与分区 JobInstance 添加子写父行锁及父删/改键触发器，Workflow归档双事务类7/7、管理清理2/2、冷归档类13/13，共22/22；应用成功实例归档仅延后本批不会删除的独立/其它 Workflow 热节点引用，旧 wrapper 仍同批归档，冷节点保留 Job ID 后再归档父实例，见专题§2.20。当前未发现 calendar_dependency 生产应用写入口，因此只记录数据库守护已验证；未来新增写入口必须与DB引用身份、租户锁和冲突错误契约对齐。V296 13/13、V297 outbox/Quartz 18/18、V298 Console 作业定义集成类15/15、V299同类16/16、V300空库迁移集成类2/2、V301双事务防环及作业定义集成类19/19通过。relatedFileId补偿入口新增真实 PostgreSQL 跨租户拒绝及无命令落库证明。V307为scheduled_occurrence_catch_up_audit与occurrence绑定相同五列请求/业务日身份，真实PG定向回归28/28通过。V308将batch_day_replay_entry的(tenant_id,session_id)绑定到同租户session，应用Service先检查相同租户、真实PG跨租户直写被拒，干跑/OUTPUTS_ONLY受影响回归38/38通过，见专题§2.22。V309为preview token与可空session_id建立同租户复合FK，Mapper关联时检查同租户session，过期token先清理再允许session清理；真实PG及应用入口定向回归42/42通过，见专题§2.23及验证矩阵。V310为batch_day_waiting_launch建立(tenant_id,request_id)到trigger_request同租户复合NO ACTION FK，应用同步核验tenant/request/job/Workflow/bizDate/dryRun及原请求状态；等待生命周期清理按子先父，真实PG应用与scheduler回归37/37通过，见专题§2.24和验证矩阵。V311为batch_day_catchup_control增加(tenant_id,calendar_code)到business_calendar同租户复合NO ACTION FK；应用预检相同自然键的启用日历，FK竞态23503映射409并回滚审计副作用，wipe/租户克隆按子先父清理。全部受影响的日历/清理fixture与控制服务回归189/189通过（首次发现并修复Quartz重启fixture违反既有V295子先父顺序）；随后新增真实父删/子写双事务竞态用例1/1通过，日历协调全类与服务单测28/28复跑通过，见专题§2.25和验证矩阵；这仍是REF-05局部证据，不关闭DBSAFE-3或R项。每条关系均须对照应用Guard/Mapper身份、实际数据库候选键/约束、NULL和生命周期语义、错误契约及全部写入口；局部通过不关闭REF-04/DBSAFE-3。完整 SIM、容量、跨服务故障和目标部署仍开放，不增加 R 项完成数。

> **ALIGN-DB-REF / RT-5 增量（2026-10-18）**：Excel 配置包 Apply 现在在事务内先取得与 V301 trigger 相同的租户依赖图 advisory lock，再锁配置 revision 并重验；真实 PostgreSQL 双事务用例观测到 Apply 在 `pg_locks/pg_stat_activity` 等待图锁，竞争边提交后重验拒绝整包且无 channel 副作用（1/1）。`ck_job_dependency_acyclic` 通过 JDBC PSQLException 的 ServerError constraint 字段精确映射为稳定 `INVALID_ARGUMENT`（Console 异常处理器14/14）。见数据库引用整改计划 §RT-5 和验证矩阵；配置同步其它入口、受管初始化/运维脚本、普通 RLS 角色及 `biz_date_alignment` 运行恢复仍开放，RT-5 不关闭。

> **ALIGN-DB-REF / REF-07 增量（2026-10-19）**：`resource_tag` 多 Workflow 版本并发新增/删除补充一组真实 PostgreSQL 17.11 屏障回归（1/1）：并发新增两版并删除旧版时标签保持，剩余版本并发删除后才清标签。证据见数据库引用完整性计划 §2.37 和整合验证矩阵；该单一交错不关闭 REF-07/08、DBSAFE-3，也不替代更广事务排列、锁容量、SIM或部署验收。

> **ALIGN-DB-REF / REF-05 增量（2026-10-19）**：V315 为 `file_channel_health(tenant_id,channel_code)` 增加到同租户 `file_channel_config` 的复合外键，软删除保留健康状态、物理删除级联清理；直接SQL真实PG覆盖合法写、错租户/孤儿拒绝及物理删除。网关在外部 adapter 已返回后把健康快照持久化失败作为辅助状态告警，保留真实投递结果，防止重复副作用。`DispatchChannelGatewayTest` 与 `DispatchChannelHealthServiceIntegrationTest` 定向回归24/24通过，空库迁移到V315；详见数据库引用完整性计划§2.38。REF-05/08、DBSAFE-3及整体验收仍开放。

## 前端与跨端设计闭环缺陷（2026-10-14）

按用户要求纳入“主线开发任务”的 `feature/workflow-daily-integration` 分支；证据与修复方向见[设计闭环复核的前端补充](../audit/design-closure-review-2026-10-14.md#前端补充审查2026-10-14)。以下 UI-SEAM-1–9 分别对应报告 FE-1–9，使用独立编号避免与下文历史已完成 FE-1/2/3 混淆。九项原始问题均有源码证据；UI-SEAM-1–3 已补本地代码修复和单测，但真实浏览器、审批到执行及数据库逐类回读仍未验收，其余六项待实施。不得将局部单测描述为跨端问题关闭。

| ID / 优先级 | 事项与责任边界 | 当前证据 | 完成条件 |
|---|---|---|---|
| **UI-SEAM-1 / P1** | 通知渠道切租户后错写；Console UI / 通知配置 API | **本地关闭**：已增加租户请求 generation fence、切租户清除列表/编辑态、行与表单冻结租户身份；`NotificationChannelsTab.test.ts` 组件回归 4/4 覆盖校验 pending 时 A→B 不提交、未切换保存、同码迟到响应隔离和删除确认竞态；fence helper 3/3。新增真实浏览器场景在 A/B 创建同码不同名渠道，切到 B 编辑后经 API 分别回读，确认 A 不变、B 已更新；通知/订阅/Webhook E2E 4/4、租户隔离 E2E 1/1，ESLint/typecheck 通过。源 SHA 清单及限制见执行日志 | 本地问题与回归已闭环；未覆盖目标环境发布验证，纳入 R35/发布门槛，不阻止关闭本地 UI-SEAM-1 |
| **UI-SEAM-2 / P1** | 自助重跑目标错位；Console 表单 / 审批 / Orchestrator | 实例搜索绑定 tenant/job/date 请求快照，条件变化清空候选和选中实例并拒绝旧响应；前端组合式测试 3/3。Orchestrator 在实例号/ID解析后校验请求 job/date，新增 mismatch 拒绝及匹配目标启动测试；`DefaultCompensationServiceTest` 18/18 通过 | 补真实表单操作与审批执行链路；从请求到批准后核对实际 Workflow/Job/date 与选择一致，并验证 mismatch 时不启动、不产生业务副作用 |
| **UI-SEAM-3 / P1** | 配置同步导入遗漏五类配置；Console / 配置映射 | `DefaultConsoleConfigSyncApplicationService` 现在将九类当前配置完整映射到 batch init，summary/total 与审计类别同步覆盖九类；Console API/生成类型同步新增五个 summary 计数，前端移除已退役的 `PIPELINE` 选项。映射/汇总测试 2/2、控制器测试 4/4、前端选项测试 2/2、typecheck、API drift 通过 | 在空目标租户执行导出→导入，并逐类回读九类结果；证明真实 init 持久化、配置依赖和 API 返回统计与源 bundle 一致 |
| **UI-SEAM-4 / P2** | 标签行修改目标被筛选草稿替换；Console UI / 标签 API | Console 保存/删除改用加载结果的 tenant/resource 快照；筛选草稿不匹配时禁用行操作并在处理器再次校验，删除确认使用同一快照；迟到查询按 generation 丢弃。身份 helper 测试 5/5，相关三组前端测试 11/11、目标 ESLint 与 typecheck 通过 | 补真实组件或浏览器场景：A 结果切换为 B 草稿时保存/删除不发出 B 写请求；删除确认等待期间切换租户/资源不产生写入；并回读 A/B 数据证明目标正确 |
| **UI-SEAM-5 / P2** | Bundle 失败仍提示成功；Console UI / 创建和导入结果 | 三个入口现使用 OpenAPI 生成结果类型和统一逐租户/逐项失败判定；业务失败或计数不一致时不提示成功、不跳转并保留表单/导入内容。结果判定测试 4/4，目标 ESLint、i18n、typecheck 通过 | 补三入口组件/API 行为测试，断言 all-failure/partial failure 不关闭界面且提示失败租户；验证成功结果才关闭/跳转；确认部分成功后的失败租户可单独重试并持久化回读 |
| **UI-SEAM-6 / P2** | Job 编辑字段未持久化；表单 / DTO / Service / SQL | `executionHandler`、`paramSchema`、`priority` 已贯穿维护参数和 Mapper UPDATE；遗漏字段保留原值。Service 测试 8/8、真实 PostgreSQL Mapper 2/2 通过；priority 范围已统一为数据库/创建 DTO 的 1–9，并同步更新更新 DTO、OpenAPI 和 Console 输入范围 | Console HTTP/浏览器逐字段编辑并重新查询验证端到端回读后关闭 |
| **UI-SEAM-7 / P2** | 节点跳过准入条件相反；Console / Workflow 运行管理 | **本地关闭**：Console 仅对 FAILED 显示跳过按钮，状态 helper 单测 1/1、目标 ESLint 与 typecheck 通过；真实 JWT/CSRF Console HTTP + PostgreSQL/Worker E2E 验证 AUDITOR 对 FAILED 节点返回 403、ADMIN 跳过后进入 SKIPPED，RUNNING WAIT 节点的操作返回 409 且状态不变；WAIT 分支仍未释放时 Run 保持 RUNNING、不派发 Join 下游，释放后 WAIT 与 WorkflowRun 按 DAG 汇合为 SUCCESS（B33） | 本地 UI/API 准入、失败节点操作、Join 推进和非 FAILED 状态拒绝闭环；目标环境发布验证仍按 R35 发布门槛执行 |
| **UI-SEAM-8 / P2** | 自助补偿缺必要目标标识；Console / 审批 / 补偿执行 | 自助入口收敛为 JOB 实例补偿；前端按 tenant/job/bizDate 搜索并强制选择实例，后端再次解析校验并把实例 ID 同时绑定审批目标与 payload；STEP/PARTITION/FILE/BATCH/DLQ 请求被拒绝。后端服务/Controller 定向测试 8/8，前端实例搜索/相关 helper 测试 8/8；全量前端测试 156 文件 / 910 项、生产构建、typecheck、i18n、OpenAPI 生成漂移通过 | 增加真实 Console→审批→实际补偿的端到端验证；核对拒绝请求不创建审批、批准后只作用于所选实例，验证完成后关闭 |
| **UI-SEAM-9 / P2** | 业务 409 被误判为幂等重放；全局拦截器 / 操作反馈 | Console 对已完成重复键返回增加 `idempotentReplay: true`；进行中重复键不带此标记。前端仅据该标记静默，自动幂等键下 `STATE_CONFLICT` 仍记录并提示。后端幂等/补偿定向测试 16/16、前端拦截器集成测试 14/14、全量前端测试 156 文件 / 910 项、构建、typecheck、API 生成漂移通过 | 真实服务验收确认业务冲突展示、已完成重放静默后关闭 |

配对整合后端的 OpenAPI 一致性检查另有失败，沿用 **ALIGN-CONTRACT / I5** 跟踪，不增加重复缺陷编号。类型检查通过不能关闭上述任务；禁止以允许 400/500/503 或仅断言 HTTP 状态码小于 600 的用例作为业务闭环验收。

## Workflow 与每日连续性共同整合门槛（2026-10-14 修订）

用户确认不兼容旧系统、全新数据库部署；当前契约 `2026-10-14.2` 见 [ADR-048](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md)。按唯一[验收矩阵](workflow-daily-integration-acceptance-matrix-2026-10-14.md) B171，36 个有限本地场景已完成，正式24已验证/12部分验证；12项只保留各行EX-01–04精确范围。B172冻结SIM28/28、strict21/21通过；WARN101条经原策略98条及本轮精确证据3条分类通过，默认首次失败保留；格式检查按用户要求暂缓。主计划§0.1.1的旧多目标批量发起及混合预检能力边界已再次提交用户裁定，裁定前不把旧入口404视作等价承接或整I1完成；这属于业务范围问题，不能用测试通过代替。

| ID | 事项 | 当前状态 / 完成条件 |
|---|---|---|
| **ALIGN-CONTRACT** | 新模型单一入口、DTO/调用方、旧入口拒绝 | 用户已裁定保留 Workflow 多选批量发起、预检分开。B173 完成独立 Job 预检的目标、权限与零运行副作用核对；B182 完成批量真实 JWT/CSRF 浏览器、HTTP、逐项运行与认证拒绝验证。旧 Job 根入口已退役，不声称旧混合预检与新模型等价。B184 起规约整理尚待当前源码统一编译/回归；I1 与质量、目标环境放行继续独立跟踪。 |
| **ALIGN-BULK-TRIGGER** | Workflow 多选独立启动、逐项结果及失败重试 | 范围已由用户确认；B182 已完成本地承接：每个 Workflow 使用独立稳定幂等键，逐项返回结果，明确失败只按原键重试该项，未知结果刷新后只查询核对。真实 JWT/CSRF 浏览器与 HTTP 验证每项恰好 1 请求/1 运行、重复提交、失败重试、收据核对及权限拒绝。预检单独操作。最新规约整理后仍须受影响契约回归；不能据此关闭全部 I1、CI 或目标环境条件。详见总计划 §0.1.1 及执行日志 B182。 |
| **ALIGN-DRY-RUN** | 独立预检与正式执行分开 | 已按用户确认取消批量混合预检/正式执行。B173 的独立 Job 预检通过 41 项定向用例及 5 项真实认证 API 检查，覆盖目标范围、权限、零运行表副作用；正式 Workflow 发起不携带 dry-run 模式。B182 保留独立多选正式发起。两者职责明确，不宣称与旧混合接口等价；后续按最新源码受影响测试回归，部署/发布条件独立。 |
| **ALIGN-WORKER-BATCH-REPORT** | Worker 与 Orchestrator 的批量回报接入状态 | 本版明确支持可选claim-batch后逐项report，不新增批量回报优化；服务端report-batch没有Worker生产调用方，保留协议但不宣称端到端支持。Stage27验证claim批处理，不能代替批量report证据。未来启用另行实施部分失败/重试/容量验证，当前不作为回归缺陷或新增实现项。 |
| **ALIGN-OBSERVABILITY** | Workflow 主链切换后的采集指标、告警、看板与 Runbook 口径 | B167按R27/R31/R36有限合同完成本地指标/告警身份对账；修复PROCESSING无根请求漏计及根启动时延，PG/指标16场景通过，Docker/Helm规则同步守护通过；B171实际负载保存root/node/job、队列、Kafka lag和资源采样。目标持续运行观察按EX-04，生产容量按EX-03；不宣称整仓观测专项全量审查通过。 |
| **ALIGN-TEST** | 恢复整合测试基线 | B168–B171 完成本轮有限缺口：真实认证人工结算/取证、ConfigSync提交事实核对、通知冷热保留与恢复、32调用方、运维四动作、跨构建/历史mixed与分区维护。精确命令、失败和回归见[执行日志](workflow-daily-integration-execution-log-2026-10-14.md)；当前24正式/12部分，本地36/36。B172冻结SIM28/28、strict21/21及本轮限定WARN分类已完成，默认首次失败保留，B173 补独立 Job 预检目标缺陷，41个定向用例及5个真实HTTP检查通过、运行表计数不变。业务场景36/36不代表质量门禁或整个I1已关闭；格式检查暂缓，EX不降低发布门槛。 |
| **R35 / 恢复审批执行** | 审批动作/载荷绑定与任务、分区 RETRY 派发 | B26 已修复并有 59 个不同用例最终通过证据：运维端口校验批准动作和完整业务载荷、认证操作者出站、移除文件绑定关闭入口、补齐 RETRY 派发。真实 JWT 多角色→失败 JOB 节点跳过/自助补偿→正确 Run/Node/Job 持久结果与审计关联，以及 RETRY 的完整真实业务链，继续按 R35 收尾；B34 验证自助补偿审批后 active JobDefinition 从 v1 变为 v2 时，来源快照仍驱动真实新 Run/Job 执行 v1；B35 的真实 PostgreSQL 测试验证 Workflow 受理后共享 JobDefinition 变化不改写既有运行的冻结计划/SLA，并验证两个 Workflow 对共享 Job 保持各自定义版本快照；B36 又验证暂停期间失败报告后，即使已接纳 Workflow 的 JOB 出边和 WAIT 节点被停用，恢复仍按冻结 DAG 派发 FAILURE/ALWAYS 后继且只创建一次；B37 通过真实 JWT/CSRF Console→独立审批→Workflow/Outbox/Kafka/IMPORT Worker，验证真实失败来源 JobInstance 可自助补偿，修复同一对象存储键后按冻结 v1 完成新运行，原失败 Run/Node 不变（成功源/失败源参数场景 2/2，新增失败源场景 1 个）。B38 已验证 BatchDay replay 成功后仍可对原失败来源自助补偿，`run_mode=RERUN` 固定且同类 E2E 回归 3/3；R35 仍部分验证，其它独立验收条件未全部闭环，不提前销项 |
| **R25 / 审批恢复幂等** | 同一批准补偿在业务成功后因响应或 EXECUTED 回写丢失而重投 | 已复现重复 commandNo/重复调用并以 V337、载荷指纹、热/归档查询修复；真实 PG 生产服务定向 4/4。B28 已通过真实 Console HTTP 回执丢失重试及审计回读；B39 当前源码真实 PG/Kafka/Console HTTP E2E 验证 UNKNOWN 核实未执行后一次性恢复、同审批回执丢失重投幂等，并由 Import Worker 完成新 Workflow/Job/Task，唯一客户业务记录写入，原 UNKNOWN 源任务/实例保持 FAILED。测试配置使用 USE_LATEST_CONFIG 适配无冻结快照的 JDBC 源夹具；security bypass 不作为 JWT/CSRF 证据。R25 仍因其它 Worker/插件跨 attempt 副作用矩阵和 ADR-049 操作范围未收口，不增加 R01–R36 分母 |
| **R25 / ADR-049 模板冻结** | 普通文件作业、文件束、原配置重放与整图预览的模板版本一致性 | B132–B150已把完整模板/通道引用从预览贯穿加密审批快照、Root节点和子Job T1，缺失失败关闭；实际Worker消费及lineage在R17/R25证据内，B166本地销项。旧B122“整图未完成”不是当前待办。 |
| **ALIGN-RUN** | Workflow occurrence、根/节点运行、日期和覆盖冻结 | 本地R01/R03–R09/R23已有实际PG/Quartz/Worker证据，B170补跨构建持久pending恢复，B171补历史mixed有界积压收敛；目标容量和部署分别按EX保留，不重开已完成主链。 |
| **ALIGN-DAY** | 输入及业务义务、接纳/结算事务围栏 | R10/R11本地场景完成，B168补真实JWT/CSRF人工空日结算、多角色/跨租户拒绝零变更及实际取证审计回读；目标运行期按EX-04保留。 |
| **ALIGN-READ** | 新模型实际消费版本、Pipeline/参数及恢复 | R17/R25/R33本地合同已有冻结配置、实际消费lineage、整图/节点/区间审批到Worker及冷热引用证据，B165–B166收口；目标提供商和故障域保留EX。 |
| **ALIGN-TEMP** | 导入阶段文件持锁、成功回收与失败取证 | 2026-10-16 定向 71/71 与真实 Import E2E 1/1 通过：运行中超期文件不被删、成功统一删除、失败解锁保留；独立 JVM 强杀后允许清理，并覆盖数据已删除但锁仍存活/崩溃的窗口。源版本和部署范围见验证矩阵，不替代全部恢复/容量验收 |
| **ALIGN-COUNT** | 节点计数连续性的范围、单位和冻结图 | 实现及组件验证通过：33/33 单测、PostgreSQL 7/7；v4 新库完整 SIM 28/28 和严格数据 21/21。v5 新增资产缓存修复后完整 reactor 3151 项（19 条件跳过）、冷数据分发和严格数据 21/21 通过；计数代码未再改变。不等同于实际消费版本或端到端完整性验收 |
| **ALIGN-WARN** | 运行 WARN 按事实和场景闭环 | B41完整SIM原始审计已为67/67 EXPECTED，历史66/50/16失败保留；B171新JAR启动4/4通过。B172冻结SIM28/28与strict21/21后收集28日志段、101WARN：98原策略分类，3同一PROCESS取消竞态经持久状态和精确原文附录核对；附录9正反例通过，整轮审计exit0。通用策略不变，默认首次exit1保留，不覆写历史失败。 |
| **ALIGN-ASSET-CACHE** | REPORT 物化资产身份的事务可见性（R17/R32/R33） | 本地缺陷已修复并验证：真实 PG 并发/回滚先红，48/48 定向、完整 reactor 3151 项（19 条件跳过）通过。v5 运行包与 5592 项输入对应；新 Job 首次资产数 0，三日期实际分发成功、资产 1/分区 3/结果版本 3、HTTP 500=0、无瞬时 REPORT 失败。保留提交后缓存复用，不新增或替代 R 验收行 |
| **ALIGN-ASSET-READ** | 资产分区的稳定结果版本身份（R07/R17/R33） | 本地 Mapper 缺失版本 ID 已经真实 PG 先红后修复；去掉旧构造入口，服务按持久 ID 核对。资产/readiness 54/54，随后与依赖恢复联合 118/118 通过；真实恢复实例的两个观察版本 ID 与来源记录一致。处理器实际按指定版本消费、归档及跨服务证据仍开放 |
| **ALIGN-DEP-SNAPSHOT** | 作业依赖等待的快照校验和恢复隔离（R07/R08/R14） | 异常输入首红 55 项中 42 failures、6 errors；修复后 55/55，联合真实 PG 共 118/118、0 跳过。旧字符串、空/损坏条目、缺失对齐、未知字段和非法日期不再过滤/回退；保留 WAITING 与原 JSON，记录 `DEPENDENCY_SNAPSHOT_INVALID`，同租户其它合法等待仍可创建唯一实例。合法日期及原解析错误保持冻结；完整跨进程/多实例恢复仍开放 |
| **ALIGN-RETRY** | 自动预算、终态与死信处置归属 | 根因及回归通过：186/186 组件、3166 项受影响单测（19 条件跳过）、原失败数据 210 秒稳定；v5 完整 Orchestrator reactor 补齐终态拒绝、活跃父级分片重试与取消/CLAIM 对账后通过。新库完整 v4 SIM 28/28；v5 收尾 12 条历史死信、无重复源、自动预算最大 0、待重放/重试/REPORT/Outbox 为 0。下游恢复和真实 500 故障已验证，整体恢复矩阵继续开放 |
| **ALIGN-UNKNOWN-RECONCILIATION** | R25 `EXTERNAL_OUTCOME_UNKNOWN` 人工核对后的平台恢复 | 按用户已裁定的边界，仅在认证操作员记录“外部动作未执行”结论、原因和证据并经独立审批后允许一次性恢复；已执行或仍不确定时继续阻塞，平台不伪造成功。V336 持久化核实版本/来源身份并防重复尝试。B39 当前源码真实 PG/Kafka/Console HTTP/Import Worker E2E 验证 UNKNOWN 来源、证据审计、一次新 Workflow Launch、同审批在回执丢失后复用原 commandNo、新 Workflow/Job/Task 成功及唯一客户业务记录写入，原 UNKNOWN JobTask/JobInstance 保持失败。JDBC 源夹具显式使用 `USE_LATEST_CONFIG`；测试 profile 使用 `batch.security.bypass-mode=true`，不作为 JWT/CSRF 证据。未知结果盲目重放仍被围栏拒绝；R25 其它 Worker/插件副作用矩阵及 ADR-049 扩展范围仍开放 |
| **ALIGN-SFTP-KEYS** | 仿真配置的 SFTP 字段来源（R34） | 此缺陷本地修复并验证：移除 bootstrap 旧字段转换，准备事务验证并锁定完整的三个自有通道；只接受规范非空字符串，不转换旧别名，缺失/类型错误/部分更新整批回滚。真实 PG 先红 11 项中 10 失败，修复后 11/11、相关数据库回归 49/49 与脚本 10/10 通过；现场入口成功且随后三个探针 HEALTHY。完整最终版本 SIM 及 R34 其它脚本/CLI/回调盘点仍开放 |
| **ALIGN-GATES** | 当前整合工作区的严格静态门禁 | 按用户最新分工由另一会话继续，本会话停止规约扫描、整改及门禁，不据此认定通过。B184–B191结果保留对应源码快照；本会话功能问题统一见B192功能复核，最新统一运行包及CI/EX独立。 |
| **ALIGN-WF-WRITE** | Workflow 写入并发与公开契约（R28/R35） | `/full` 强制版本 CAS、设计锁和快照；真实 PG 定向证明全字段写入、租户隔离、过时版本零写入与并发 schedule CAS。已确认基础 `PUT /{id}` 无前端调用方，退役对应 Controller/Service/Mapper/OpenAPI 与前端 wrapper；真实 HTTP 负例先复现 200，改后验证 405 且三表 DAG 不变。受影响 Console 测试 27/27、OpenAPI 412 routes、前端 API drift 和 `workflow.test.ts` 3/3 通过 | 尚需对完整 Workflow 设计/审计/并发历史验收，以及真实浏览器保存回读重新验收；R28/R35 整体不关闭。真实部署仍未包含本次改动 |
| **ALIGN-FE-TYPES** | 前端类型检查空跑与源码类型/契约错误（R19/R34/R35） | 旧根配置 `files: []` 的空跑问题已修复，真实配置正反例能阻断错误。本工作树在 2026-10-18 执行 `npm run typecheck`：0 TS 诊断；`npm run test:typecheck` 的合法 TS/Vue/Vite 与三类错误探针 5/5；`npm run gen:api:check` 对配对后端 OpenAPI 显示 in sync；相关 Workflow/Trigger API 与设计器单测 9 文件 / 60 项通过；`npm run build`、其中的 i18n 检查及独立 `npm run check:i18n` 通过。先前 132 条/66 文件是更早快照，当前不再是类型门禁失败 | 全量前端 Vitest、lint/format、真实浏览器和跨端业务验收尚未在本轮运行；R19/R34/R35 仍按各自矩阵条件开放 |
| **ALIGN-GOV** | 三类治理/整改的方案映射与独立排序 | 总方案新增系统问题、代码规约、治理规范三维统筹；详细事项仍分别由 I6/SEAM/ALIGN、ALIGN-GATES/规约专题、治理规范与 CI 专题跟踪。当前仅完成文档映射，不代表代码规约清单已清零或治理规范建设已关闭 | 复核每个当前治理/整改事项均可追到来源专题、唯一 TODO 状态、优先级和关闭证据；系统发布风险按影响优先，治理规范调整默认排在代码规约整改之后。完成文档闭环后再按各自台账实施与验收 |
| **ALIGN-DB-REF** | 数据库逐表引用完整性；数据库 / Orchestrator / Console / 文件与运维，按 REF-01–08 分工 | **局部整改与验证，未闭环**；保留176逻辑表/107分区/51逻辑FK的初始V291目录证据。独立Workflow节点JOB关联已修复，冷根/节点整行核对、冲突保留热数据及复制失败回滚已有5项真实PG证明；受影响归档/Process回归35/35。详见[专题§2.4](database-reference-integrity-remediation-2026-10-18.md)。位点V292复合FK、冷热保全/冲突回滚、父子锁并发及写入0行拒绝已本地实现与验证（见专题§2.5）；RT-2常规写入/启用/导入及根运行准入已局部落实，18项API/PG及真实根入口4项通过，受影响8类最终报告累计116项（原命令1个旧夹具失败已定向修复），见专题§2.6；V293已为图节点/边/容量等待补同租户父定义FK并通过双事务及Console回归4类31项（含前述18项），见专题§2.7；RT-4等待准入/容量恢复和Job准备T1已局部落实原请求身份、状态、必需0行回滚及无缓存行锁重检，新增3项真实根入口故障拒绝后同请求恢复通过，6类178项受影响定向回归通过（详见专题§2.8与验证矩阵，包含前述部分用例，不累加完成数）；Job等待恢复已补提前身份核对、事务内租户读取与原子取消，保护已准备实例、过期扫描及同租户失败隔离；真实PG类55项及普通角色取消1项分批通过（23项为本切片新增），见专题§2.9。延期拒绝已按§2.10绑定具体节点尝试并重扫CANCELLED，复用DAG失败/终态Outbox事务；含普通RLS角色、回滚重扫、暂停、新尝试与失败边验证，8类146个不同用例最终观察通过（原145项命令1个新错误契约失败已定向修复）。V294已为两类等待补原请求NO ACTION复合FK；V295再为expected/scheduled occurrence补同租户原请求NO ACTION复合FK，等待/occurrence/批量日定向回归共156项通过，后台线程退出超时仍留日志，见专题§2.11。 V301为依赖图增加租户级事务锁与数据库防环触发器，Console类及INSERT/UPDATE/legacy/双事务真实PG用例19/19通过，见专题§2.16。 V302为calendar_dependency增加同租户上下游自然键复合FK与数据库并发防环，真实PG完整类21/21通过；当前无生产应用写入口，数据库保护已验证但无应用Guard可声称完成，未来写入口须按专题§2.17与数据库身份/锁/错误契约对齐。 V303为calendar_holiday补(tenant_id,calendar_id)复合NO ACTION FK，Console按请求租户解析相同父ID；真实PG日历协调类22/22、Console日历变更类5/5通过，见专题§2.18及验证矩阵。 V304为日历成员与GROUP假期代表关系补同租户组FK，真实PG完整类23/23通过，见专题§2.19及验证矩阵。V305为热 `workflow_node_run.job_instance_id` 增加子写父行锁与分区JobInstance父删/改键守护；应用归档仅延后本批不会删除的独立/其它Workflow节点引用，旧wrapper仍同批归档，冷节点保留Job ID后再归档父实例；Workflow归档双事务类7/7、Admin清理2/2、冷归档13/13，共22/22通过，见专题§2.20。自动受管图、其它运行/等待身份、其它RT-4账本/完整热冷清理及Job恢复矩阵仍开放；RT-5的依赖环并发保护已按专题§2.16局部完成，但全量导入/初始化旁路、错误契约和更大并发矩阵，以及RT-6的全局锁/父子关系、完整冷回查与部署仍开放；本地V300复合FK要求淘汰不可达跨租户归档夹具，非生产故障，首次Maven/Docker权限失败亦非当前阻塞 | 完成RT-2剩余生命周期/元数据边界；RT-4按§2.11继续其它账本并发写入与保留清理保护、完整Job恢复/热冷边界及全部wipe/seed动态验收，再按§2.2矩阵推进RT-5/6及运行树/Outbox并发保护、复合约束、冷表/清理契约；每项以实现、真实PG双事务/API/主链证据关闭。同步[数据库总方案§3.2](database-integrity-index-partition-remediation-2026-10-18.md)的DBSAFE-3要求：应用/Mapper与实际FK逐关系对齐身份、NULL/状态、删除归档及例外，覆盖全入口、双事务和错误契约；按关系继续实施，不另增完成项。V306为Outbox delivery/retry落实同一三列复合FK、应用行锁身份、归档迟到写冷表和子先父清理；真实PG定向合并回归31/31及分区约束目录断言1/1通过（共32个不同用例），REF-02其余运行树/分区淘汰/全链路验收仍开放。 V307为scheduled_occurrence_catch_up_audit与occurrence绑定相同五列请求/业务日身份；错request与错bizDate直写拒绝、合法catch-up路径保留，真实PG定向回归28/28通过，见专题§2.21与验证矩阵；REF-04/DBSAFE-3其余范围仍开放。 V308将batch_day_replay_entry的(tenant_id,session_id)绑定到同租户session，Service预检与复合FK身份一致；真实PG跨租户直写拒绝，dry-run/OUTPUTS_ONLY受影响回归38/38通过，见专题§2.22及验证矩阵；V309为可空preview token session引用补同租户复合FK与Mapper Guard，expired token先清理再释放session，真实PG及应用定向回归42/42通过，见专题§2.23及验证矩阵。应用层与数据库必须按同一候选键和租户/业务身份守护，并对齐NULL、父侧生命周期、并发写删和错误契约；REF-04/DBSAFE-3剩余范围仍开放。V314为多态resource_tag补Console与数据库同身份守护；目标/异租户、四类自然键改名、disabled/软删元数据、父删除/改名与附加竞争及Workflow版本新增与最后版本删除的两种提交顺序均有真实PG覆盖，初始生命周期/并发命令6/6通过；加入三版本并发删除后同组定向回归7/7通过，详见专题§2.33–2.34及§2.36。已静态盘点唯一应用写入口与两个tenant wipe清理入口；两条tenant wipe脚本在隔离PG含等待/控制/Pipeline引用fixture动态执行通过（见专题§2.35），其它运维入口、更广多版本并发组合和锁容量仍开放，不关闭REF-07/08或DBSAFE-3。应用与数据库逐关系契约对照要求见专题§2.3。结构盘点不关闭R20/R33/R36；不机械全库补tenant或热表FK。2026-10-19 V324为热job_partition.source_file_id增加同租户NO ACTION FK、应用精确并发错误映射和租户wipe/seed/SIM子先父清理；真实归档首红发现冷分区复制漏source_file_id/template_code/target_ref，现已补齐；ArchiveColdStorageIntegrationTest 15/15、JobPartitionEntity 26列与冷复制列对照通过，详见专题§2.48与验证矩阵V324。V325为 `data_quality_check.job_instance_id` 保留弱历史语义并统一应用/DB同租户父锁、直接SQL拒绝与父身份冻结，8项真实PG场景及执行器、结果版本和迁移manifest回归合计32/32通过；冷DQ归档与回查仍开放，详见专题§2.49及验证矩阵V325 |
| **ALIGN-DB-INDEX** | 全库索引与查询路径；数据库 / Orchestrator / Trigger / Worker / Console | **已评估、待实施**；176表/589逻辑索引，含子索引1,634对象，逐表结论与IDX-01–08见[专项方案](database-index-review-remediation-2026-10-18.md)。未见无效或完整定义完全重复；日期列转换只读计划37→1分区，Webhook partial谓词漂移已确认；其余为待代表负载验证候选 | 先修查询与重试谓词，随REF补关联/冷热/FK及主链队列计划；保留约束/幂等/公平排序，比较读写成本后决定增删，最后收敛访问重叠。只读计划不等于性能或整改验收 |
| **ALIGN-DB-PARTITION** | 表分区与持续扩展；数据库 / PROCESS / 归档 / 业务库与运维 | **局部整改与验证，未闭环**；保留10父/107子、1,045子索引的初始目录证据。RLS/角色29项及Process分区类4项通过；新叶子创建/策略同事务回滚、普通角色读写隔离有动态证据。见[专项方案](database-partition-lifecycle-remediation-2026-10-18.md)与验证矩阵；真实安装/strict/rollback整事务及失败回滚、writer角色拒绝/策略绕过检查已本地修复，相关37项通过；DR调用方失败传播3项mock组件通过。全部业务表非空数据、多物理库、在途清理及持续扩展仍开放 | P1先修叶子访问隔离、淘汰保护；再补维护入口、DO事务语义、多物理库、DEFAULT/时区、DDL锁与权限、统计/参数及告警。以普通角色、跨日恢复、失败重入和真实环境证据关闭；不改变R20/R33/R36状态 |
| **ALIGN-FRESH-DB** | 最终新库结构、种子、隔离环境、停旧启新 | 本地新模型空库与冻结构建证据见 B172 及各关系切片；早期“279 个迁移至 V284”仅是当时快照，不能代表最终源码或运行包。最新源码完成整理后须核对最终迁移资源、空库 migrate/validate、种子及启动结果，保留首次失败原件。I1/I5、D1 目标命名空间、停旧启新和部署观测按 EX-01/EX-04 保留；A6 不含旧库升级或旧历史导入。 |

> **PART-02 安全保护增量（2026-10-18）**：已实现持久 generation、租户/generation/batch key 复合 `NO ACTION` 外键、OPEN 状态触发器，以及按“已被新代取代”准入的跨库有界回收：平台实例锁保护当前 generation 重读，业务事务封存并分块删除；末块完成后 ledger 进入 `RECLAIMED`，保留拒绝迟到写的 tombstone，部分删除的 `SEALED` 可重入续删。tenant context 在业务事务启动前设置以路由到正确物理库，事务内再绑定 RLS GUC；generation ledger 和 staging 行都执行 FORCE RLS，候选 SQL 显式限定 tenant。去除 tenantless 最旧年龄 Gauge，改为定时 RLS-scoped 扫描并缓存。按年龄直接删除和整分区 DROP 入口已移除；`deletionEnabled` 仍默认关闭，当前 generation 和整分区仍保留。清理/RLS/回滚/分块重入、平台围栏顺序、启动审计及双库恢复故障注入最终定向组27/27通过；独立跨库类3/3，空库应用启动3/3。另有真实 PostgreSQL Worker fence 集成4/4证明新 invocation 重跑 COMPUTE 轮换 generation、同 invocation checkpoint 续跑复用当前代；Orchestrator JobRetryFlowIntegrationTest 3/3确认自动重试后 CLAIM 产生新 invocation ID，二者尚非同一 Worker E2E 旅程。双库故障注入证明业务库提交后平台事务回滚可幂等重试、业务事务失败整体回滚后可重试，以及首块已提交而后续块失败时 SEALED 可续清；尚未覆盖进程强杀、断连/提交确认丢失或多业务分片。此前身份/FK切片真实 PG47/47、空库迁移至 V312和业务建表脚本3/3仍有效。最终协议仍未经暂停/人工重试/归档恢复、多物理业务分片、长 SQL、容量与完整 SIM 验收，故 ALIGN-DB-PARTITION 继续开放，详见[分区生命周期整改方案](database-partition-lifecycle-remediation-2026-10-18.md)及[验证矩阵](workflow-daily-integration-validation-2026-10-14.md)。


## Workflow 统一运行与 Pipeline 按类型关联（2026-10-14）

> 以下为来源分支实施记录；当前任务以顶部 `.2` 整合门槛为准，旧兼容/转换事项不再排期。

目标与验收见[实施方案](workflow-unified-execution-and-system-pipeline-plan-2026-10-14.md)。Pipeline 模型保留，按 Job 类型选择系统固定定义；作业定义和工作流定义是两个独立、联动的实体。当前实施中，系统 Pipeline/Worker、显式单作业 Workflow 关系、Trigger 调度迁移、双菜单部分联动和旧配置兼容转换已部分落地；组合 Workflow 统一运行和真实端到端验收未完成。

| ID | 事项 | 状态 | 完成条件 |
|---|---|---|---|
| **WF-0** | 目标契约、现状差距、实施切片与验收矩阵 | 文档已编写，静态检查通过 | 区分目标与现状，明确两个菜单、四类文件 Pipeline 和统一顶层运行 |
| **WF-1** | 系统 Pipeline 目录与 Job 执行参数迁移 | 部分实施 | 类型/阶段/版本匹配；PROCESS 插件与业务参数保留；存量 DB 数据对账、滚动恢复验证 |
| **WF-2** | 单作业受管 Workflow 与统一节点执行 | 部分实施 | 唯一起止边界；组合图节点实例、并行汇合、失败、去重与恢复验证 |
| **WF-3** | Workflow 触发、调度与批量日入口 | 部分实施 | 组合 Workflow 根 Launch；Workflow 身份的 Trigger/批量日/补跑；真实 misfire 和恢复验证 |
| **WF-4** | 双菜单联动、整体运行视图与配置包/API | 部分实施 | 双向联动和配置包旧值转换已落地；Workflow Run 节点可展开 Job Instance、分页分区/阶段和 Pipeline 观测入口；真实前后端链路与恢复旅程仍待验收 |
| **WF-5** | Job-Pipeline 手工关联与旧引用收敛 | 依赖前置切片与兼容验收 | 仅退出 Job 手工选择/绑定租户 Pipeline 的配置入口，不删除 Pipeline/Definition/Step/Instance 模型或历史查询；旧节点字段/包装任务、归档和运行兼容逐步收敛并通过迁移恢复验证 |
| **I6 / BATCH-SEMANTICS** | `main` 批量调度语义与主链横向影响迁移 | 进行中；仍阻断 D4/D5 | 基线固定为 `ee01e97515b04fd167ed17977535ec5cab8572f8`，逐项台账已增加 Workflow 日程/根身份、首次审批载荷及停用后既有 misfire 三项生产者—持久身份—消费者追踪，保留未完成边界；跨日依赖结果版本身份、未知策略处理及冻结载荷到 Java SDK handler 的传递也已追踪；代码/定向测试证据见实施方案 §0.1.1 与验证矩阵 I6 记录。外部 payloadRef 消费、归档生命周期和跨服务恢复仍未验收。其余域仍须盘点重放/补跑、跳批、三类依赖、告警/SLA/计数、身份幂等、终态消费者、查询/归档/血缘、分区策略/分片键/DB 路由、API/SDK/脚本、权限配置、容量和恢复；差异须先定规则并实施代码/契约/Console/通知/脚本/Runbook 改造。Job EFFECTIVE 不能推断 Workflow SUCCESS；同 Job/date 多节点结果键须明确并存或覆盖策略；R25–R36 是实现后的行为证据，不替代基线改造。D0 前容量目标必须填入，D4 前 I6 与适用验收关闭。 |

## 前后端约定与 CI 漂移治理（2026-10-12）

范围和静态证据见[前后端治理漂移复核](../audit/cross-repo-governance-drift-review-2026-10-12.md)；下表是待修缺口，不代表 CI 或目标环境已验证。

| ID | 事项 | 状态 | 完成条件 |
|---|---|---|---|
| **GDRIFT-1** | 前端 staging 普通 E2E 的 URL、API origin 和测试账号来源对齐 | ✅ 本地实现与配置回归通过；待 PR/CI 和真实 staging run | staging 显式使用前端/API origin 与凭据，拒绝 loopback/本地 API 回退；配置负例测试通过；真实 staging 仍须运行留证 |
| **GDRIFT-2** | 后端机器可读治理契约的 PR 变更范围路由 | ✅ 本地实现与分类器正反例通过；待 PR/CI | 四份治理契约精确触发 CI 守卫，普通 Markdown 保持 docs-only；PR/Full 对治理基线文件执行相应检查 |
| **GDRIFT-3** | 前后端存量治理基线受控变更 | ✅ 本地实现与正反例通过；待 PR/CI | 两端基线与目标分支比较；扩张要求 CODEOWNERS 审核和限期审批记录，内容须匹配新增/调高值；过期/陈旧审批失败 |
| **GDRIFT-4** | 前端发布必验 E2E 的条件跳过审计 | ✅ 本地实现与报告器回归通过；待 PR/CI 和真实 staging run | 必需 seed 缺失或必验用例跳过/未运行阻断；没有发现必验场景也阻断；可选跳过进入 Actions Summary；目标环境通过仍待 staging 证据 |

## 产品用户旅程对抗审计（2026-10-10）

✅ **编码实施完成；端到端验收未签收**：用户旅程改造方案阶段 1–4 的计划内编码已在当前本地交付范围落地，包括重放预览绑定/分页/失效及提交断连后的原请求结果查询、取消/终止状态反馈、批量逐项结果和版本选择；作业创建向导、独立配置入口、任务式导航；SQL AST 只读预览、严格零容差笔数/金额门禁及连续 Import→Export→MinIO→Dispatch 链路。前端 mocked-API Chromium 18/18、相关 Vitest 17/17、类型检查、变更文件 ESLint、i18n、Prettier、生产构建通过；定向检查覆盖 390px 移动端、820px 页面溢出、指定 axe 规则及键盘导航。后端配置包校验/模板/上传预览、JWT-CSRF 权限、写操作授权与幂等测试合计 85/85；另有 Apply 和作业创建认证 Testcontainers IT。Dispatch 幂等键定向测试 32/32，连续链路及 Import/Dispatch 故障 E2E 4/4 通过。上述是本地代码及定向测试证据，不是 PR/CI 或完整旅程签收。仍属**验收而非待编码**：真实四角色浏览器跨租户与完整审计、全 Console 屏幕阅读器/无障碍验收、staging 资源策略、真实外部下游响应丢失恢复、NAS/HTTP 配置环境及大/多文件端到端。SMTP 等不承诺 exactly-once。阶段 3 并发冲突由现有 CAS/idempotency 保护；真实并发操作与端到端审计仍待验收，不为填矩阵引入通用调度/补偿复杂度。逐项范围见[对抗审计](../audit/product-journey-adversarial-audit-2026-10-10.md)。

**新增真实认证证据（2026-10-12）**：作业创建 API 的 JWT/CSRF 四角色 Testcontainers 测试 1/1 通过，ADMIN/TENANT_ADMIN 成功并留下审计，AUDITOR/TENANT_USER 拒绝且无作业落库；这是单项 API 授权证据，不代表完整浏览器旅程。

## 租户作业配置简化与旁支（2026-10-11）

权威方案：[租户作业配置与资源边界收敛计划](tenant-job-configuration-simplification-plan-2026-10-09.md)。**阶段 0–3 的计划内编码已完成**，并有定向单测、真实 PG/HTTP/Testcontainers 和前端浏览器验证记录；当前不再把这些编码工作列作待开发。阶段 4 以及阶段 0 中依赖目标租户规模/平台运行条件的退出项属于目标环境验收，尤其迁移 dry-run、分租户灰度、部署恢复和生产同构验证尚未完成。配置简化主要覆盖产品旅程阶段 2，不能代替整份产品旅程方案。详细证据继续维护在方案中，本表只跟踪尚未完成的验收与独立旁支。

### 主线与发布门槛

| ID | 事项 | 状态 | 下一步 / 完成条件 |
|---|---|---|---|
| **TCFG-MAIN** | 租户作业配置简化阶段 0–3 编码 | ✅ 编码完成，待 PR/CI 与阶段验收 | 不新增重复编码任务；交付前复核前后端契约、配置包/权限矩阵及受影响 E2E。阶段签收只依据方案中尚未关闭的验收门，不以本地编码完成代替。 |
| **TCFG-REL** | 阶段 4 迁移与灰度发布 | 🔒 目标环境门槛未完成 | 在目标环境完成只读盘点、迁移 dry-run/回滚、分租户 canary、作业创建/运行/恢复 smoke 和数据对账；本地 Testcontainers 不能替代。 |

### 可独立排期的旁支

| ID | 事项 | 优先级 / 状态 | 下一步 / 验收条件 |
|---|---|---|---|
| **TCFG-S1** | 配置包 SQL 预览关键字正则误报 | ✅ 本地实现并定向验证 | 已改为 JSqlParser 单语句只读 SELECT/WITH AST 校验；回归覆盖注释/字符串关键字、DML/DDL、多语句、写入型 CTE、SELECT INTO 和 FOR UPDATE。Maven reactor 定向测试 20 项通过；待 PR/CI。 |
| **TCFG-S2** | 动态 JSON 与自由文本敏感信息分类 | ⏳ 多个日志出口及下游 API 错误响应已定向收敛；导出/诊断载荷全量分类仍未完成 | 已修正 telemetry/MDC、Push endpoint、SDK 错误响应体、配置 URL 日志，以及 Console 下游错误原文日志/回包；继续按响应、导出和诊断载荷分级。不得据此宣称全仓审计完成。证据见[核查记录](../audit/sensitive-logging-and-runtime-config-drift-2026-10-12.md)。 |
| **TCFG-S3** | SecretVersion 到 Dispatch 运行时接入 | ✅ 本地已实现；待本轮复核与 CI | 实例创建固定版本；Worker 外发前核对撤销/有效期并从加密载荷取凭据，既有实例重试不切换版本。复核租户边界、迁移和事务语义，并运行定向 IT；不扩建 Secret 平台。 |
| **TCFG-S4** | Console API 日志进入 Loki | 先取证 | 核对采集配置、标签和真实查询结果；确认缺失后再修投递/标签，避免基于旧观察重复改观测栈。 |
| **TCFG-S5** | 配置包上传并发容量 | 等待容量目标 | 先明确最大包大小、并发数和容器内存预算，再测堆/RSS、延迟、拒绝率及临时文件回收；不据单次探测改全局 multipart 上限。 |
| **TCFG-S6** | 路由容量与多租户公平性画像 | 等待规模输入 | 明确租户数、作业/队列基数、并发和公平性目标后，测 claim 查询、锁竞争与租户隔离；无数据前不切换队列模型。 |
| **TCFG-S7** | DST/misfire/readiness/retry 多副本组合恢复 | 独立验证，非配置 UX 扩建 | 仅按调度主链与发布风险制定组合演练；现有定向 SLA/PG 测试不等于部署级恢复通过。 |

### 条件暂缓与范围护栏

- **平台资源快照/恢复**：当前明确不属于租户配置包；只有平台运维提出独立需求、授权边界和恢复目标后再立项。
- **Excel 任意插件/SQL/动态 JSON 的通用语义扫描**：不作为待开发功能。校验继续限于文件安全、Schema、字段格式、租户引用和能阻断错误配置的业务约束。
- **模板/Pipeline/通道运行时漂移**：尚无证据证明本轮覆盖实例发生错误结果；只有获得可复现的实例、变更时间和行为差异后，才按具体对象建缺陷。

## Java 设计表达与可审查性（2026-10-08）

阶段 0–5 已完成代码实施与本地定向验证（LocalImplemented），CI Full Gate、隔离 sim 和性能复测仍待发布验收，不能标为全量闭环。调用关系、兼容裁定、资源所有权及证据统一见 [改进方案](java-design-expression-improvement-plan-2026-10-08.md)。动态 Map、事务/锁/Outbox 顺序与 SDK 旧入口保留；常驻批量任务环境未重置或重启。

## 固定契约与协议值治理（2026-10-07）

本地实现与定向验收已完成；后端 PR #1160、前端 PR #284 均于 2026-10-07 合并（PR 元数据确认）。合并不单独证明 Full Gate、sim 或生产验收完成；这些验证状态以各自运行记录为准。不能按“全仓 S1192 清零”扩展范围或将动态 Map、SQL 业务状态集合机械统一。范围见 [治理计划](typed-contract-enum-constant-governance-plan-2026-10-07.md)，本地证据见 [验收记录](../audit/typed-contract-governance-verification-2026-10-07.md)。

## 静态分析与 suppression 治理（2026-10-12）

实施阶段与验收见[治理计划](static-analysis-governance-plan-2026-10-12.md)，工具职责、门禁事实和例外细则见[静态分析治理标准](../standards/static-analysis-governance.md)。Sonar、CodeQL、SpotBugs、IDE inspection 的工具职责、触发条件、门禁属性及例外流程已集中；生产 Java/YAML suppression 守护已扩围并有正反例测试。当前安全敏感五规则已复核，S2077 类级抑制已改为 sink 方法级，S5443 临时文件问题已修并有单文件 Sonar 证据。其余存量 suppression 仍需按规则分批复核；Sonar Code Smell 和最新主干全量扫描也未因规范/脚本落地而自动清零。2026-10-12 后续本地增量 Sonar 扫描覆盖 14 个 Java 文件，变更行 OPEN issue/hotspot 均为 0；新代码周期 OPEN issue 由 86 降至 68，其中 S2259 从 18 降至 0。扫描没有运行 Quality Gate，后续修复分支 CI 也尚待 PR 检查；这些状态以[治理计划](static-analysis-governance-plan-2026-10-12.md)和[Sonar 运行手册](../runbook/sonar.md)为准，不得把本地增量结果表述成 PR/Full Gate 通过。

## 部署与持续交付

Compose CD、生产拓扑、硬件起步规格和 Kubernetes/GitOps 最终目标的权威待办统一维护在 [Compose CD → Kubernetes GitOps 路线](../runbook/compose-cd-roadmap.md#8-待办)。本总表只保留领域入口，避免复制 P0/P1/P2 checkbox 后产生状态漂移。

> 核查日期：2026-10-16。本文只登记当前仍有效的事项；历史分析和计划中的旧待办不计入本表。
> 状态分类、证据要求和归档规则见 [`../standards/document-governance.md`](../standards/document-governance.md)。

> 本文早期的统计数字和日期快照可能已过期；后续以事项表、证据路径和最后核查日期为准，不以历史总数为准。

> 本轮复核结论：AI、使用率统计、维护/降级三项均已有部分基础能力，不能再按“从零未实现”处理；剩余项已在各表中拆成明确缺口。五类 Worker 的核心 Runtime/SPI 与当前整合代码的本地 Sim 主链已验证；2026-10-16 新库 `sim-harness all` 完整执行 04–30，28/28 通过，证据为 `tmp/log-artifacts/runs/sim-harness/sim-harness-20261016-030737-ee01e9751/sim-summary.txt`。Stage 29 覆盖模板生成、上传和预览，不执行 Apply；本地安全 bypass、测试拓扑与数据不能替代 staging/生产授权、容量和恢复验收。

---

## 一、状态总览

本表不再维护“完成 X 项”的汇总数字。数字会随历史快照、重复计划和外部验证状态变化而失真；当前判断以每条事项的状态、证据和最后核查日期为准。

| 状态 | 当前口径 |
|---|---|
| ✅ **完成** | 有代码、测试、门禁或验证记录支撑；完成项不再重复列入待办 |
| ⏳ **当前待做** | 本地仍有明确交付物，且没有被更新文档或决策替代 |
| 🔒 **外部阻塞** | 本地材料已准备，等待 staging、运维、DBA、业务方或外部凭据 |
| 🟡 **暂缓** | 已明确不立即实施，并记录触发条件和复审周期 |
| ❌ **不做** | 明确超出系统边界或收益不足，仅保留决策理由 |

### 当前核查边界（2026-10-08）

以下事项仍可从现行文档确认存在，但不能仅凭历史计划宣称“代码未完成”：

| 主题 | 当前归类 | 权威来源 |
|---|---|---|
| staging 真实恢复、容量、发布与回滚留档 | 🔒 外部阻塞 | [`full-project-test-plan.md`](../testing/full-project-test-plan.md) |
| 生产 PG/Kafka/Redis HA、PITR 和真实故障演练 | 🔒 外部阻塞 | [`ha-readiness.md`](../runbook/ha-readiness.md) |
| 前端 dry-run、跨日 DAG、批次日 replay 页面 | ✅ 已完成 | 本表 §二 FE-1/2/3 |
| CI dry-run guard、dry-run 审计与指标维度 | ✅ 已完成 | `DryRunGuardConventionTest`、`TAG_DRY_RUN` 及 worker/plugin 守护已落地 |
| Quartz → Wheel 时间轮替换 | ❌ 不做（旧提案已撤销） | [ADR-033](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md)；当前继续使用 Quartz |
| 冷热分层、资源亲和 | 🟡 暂缓 | 对应 ADR 的触发条件 |

其余来源文档中的“未完成 / TODO / 缺口”必须按 [`document-governance.md`](../standards/document-governance.md) 复核后，才能加入当前待办。

### 🟡 暂缓清单(2026-05-21 集中索引)

| 项 | ADR / 文档 | 触发条件 |
|---|---|---|
| ADR-024 冷热分层 | [ADR-024](../architecture/adr/ADR-024-archive-tiering.md) §"实施触发条件" | 数据量 / 备份 / 监管阈值达 |
| ADR-027 资源亲和 / 地理调度 | [ADR-027](../architecture/adr/ADR-027-resource-affinity.md) | K8s 自研调度需求出现 |
| ADR-022 v0.2 `*_history` 影子表 + OSS 对象锁 | [ADR-022](../architecture/adr/ADR-022-forensic-audit-bundle.md) status | 7 年保留合规要求触发 |
| LIC-2 SBOM 嵌入 artifact | todo-master §H | 合规审计 / 客户 SBOM 要求 |

**环境分布**（可执行性切片）：

| 类别 | 当前结论 | 说明 |
|---|---|---|
| ✅ **本地可独立完成** | 本轮已完成 | RLS 运行账号校验、Trigger 真入口 E2E、Kafka 恢复验证、SDK 配置对齐和 Python 统一入口均有代码或测试证据 |
| 🔒 **本地不能做（挂起）** | 见 §九 | 需 ops / staging / prod / DBA / BIZ 配合；不使用易失真的汇总数字 |

---

## 二、ADR 优先级三阶段进展（priority-scope 镜像）

权威源：[`worker-capability-priority-and-scope-2026-05-06.md`](../archive/standards/worker-capability-priority-and-scope-2026-05-06.md) + 各 ADR 顶部"范围边界（Scope Discipline）"小节 + docs/agent-baseline.md "ADR 实施范围纪律" 章。

### 第 1 阶段 P0（已落 backend）✅

| ADR | 主题 | 落地证据 |
|---|---|---|
| ADR-012 | 失败分类 | V111 |
| ADR-023 | 多日历联动 | V112-V114 |
| ADR-025 | Workflow 静态校验 | 15 条规则全员到齐（V1-V15） |

### 第 2 阶段 P1（已落 backend）✅

| ADR | 主题 | 落地证据 |
|---|---|---|
| ADR-021 | 数据对账 v1.0 | V118 + DataQualityCheckExecutor + EFFECTIVE gate |
| ADR-022 | Forensic v0.1 | V116 + 同步 bundle + SHA-256 |
| ADR-026 | dry-run 全链路 | V115 + V117 + DryRunGuard SPI + L1/L2/L3 service + SUCCESS_DRY_RUN / FAILED_DRY_RUN 终态 + L3 真接 SQL EXPLAIN / MinIO bucketExists / HTTP HEAD |

### 第 3 阶段（暂缓）

| ADR | 主题 | 触发条件未达 |
|---|---|---|
| ADR-024 | 冷热分层 | archive 行数未到阈值 |
| ADR-027 | 资源亲和 | worker_group ≥ 8 / 异构硬件 / 多机房 / 合规隔离 等条件未出现 |

### 前端能力（已完成）✅

| ID | 主题 | 完成证据 |
|---|---|---|
| **FE-1** | ADR-026 Console UI 演练模式 | `batch-console/src/components/dialogs/DryRunPlanDialog.vue` + Job 定义详情接线 |
| **FE-2** | ADR-018 跨日 DAG Console UI | `WorkflowMermaidViewer.vue` + `crossDayMermaid.test.ts` |
| **FE-3** | ADR-020/026 批次日重放与整批量日演练 | `BatchDayReplay.vue` 支持 REPLAY/DRY_RUN、EXISTING_INSTANCES/SCHEDULE_PLAN，提交归一化单测覆盖无副作用策略 |

### 横切关注点（历史编号，已完成）✅

| ID | 主题 |
|---|---|
| ~~**CC-1**~~ | CI lint 守护：step plugin 必经 DryRunGuard | ✅ |
| ~~**CC-2**~~ | audit + metric label 加 dry_run 维度 | ✅ |

> **状态校准（2026-09-11）**：CC-1/CC-2 已完成。证据为 `batch-worker-core` 的
> `DryRunGuardConventionTest`、`batch-common` 的 `BatchMetricsNames.TAG_DRY_RUN` 及相关审计字段。
> 本表保留编号用于追溯，不再作为当前待办。

---

## 三、⏳ 待做

### G6. 资源、并发与事务治理（2026-10-01）

权威审计：[resource-lock-pool-cache-transaction-audit-2026-10-01.md](../audit/resource-lock-pool-cache-transaction-audit-2026-10-01.md)。代码侧 P1 已收口；以下只保留需要运行环境证据的事项。

| ID | 主题 | 状态 | 验收证据 |
|---|---|---|---|
| **RES-1** | Redis 完成态失败后的 Console 幂等数据库兜底 | ✅ | `ConsoleIdempotencyInterceptorTest`；V38 `batch.idempotency_record` |
| **RES-2** | 读副本 JDBC 执行期连接故障隔离 | ✅ | `ReadReplicaRoutingDataSourceTest`；需 staging 主从断链演练 |
| **RES-3** | 文件治理对象存储 I/O 与数据库短事务分离 | ✅ | `FileGovernanceCommitService`；需 MinIO 延迟/断连压测 |
| **RES-4** | Outbox scheduler 优雅关闭 | ✅ | `OrchestratorAsyncConfiguration`；outbox 重启后续投验证 |
| **RES-5** | 多副本连接池总量预算 | 🔒 外部阻塞 | 按 Pod 副本数、池上限、PgBouncer/PG `max_connections` 出具 staging 容量记录 |
| **RES-6** | 缓存 Redis 故障回源与配置失效告警 | 🟡 代码已补，运行证据待收口 | `batch.console.cache.failure{operation=read|write}`；Redis 故障注入、回源 QPS、失效发布失败告警证据 |

不得把 RES-5/RES-6 的“代码已具备降级”写成“容量和告警已验证”；它们需要真实部署拓扑和故障注入证据。

### G7. 程序内配置 Key 读取治理 · P1/P2 · ✅ CFGKEY-1/2/3 已闭环，存量已清零；§4「第二批再评估」「第三批（测试源码）」「第四批（全工程字面量）」「第五批（缺归属类补齐）」已收敛

权威规范：[程序内配置 Key 读取治理](../runbook/config-key-access-governance.md)。本项只治理生产代码中通过字符串 key 读取配置的场景，不一刀切 `@Value`、`spring.*`、JVM 系统属性、测试/压测 `-Dxxx` 参数。

| ID | 主题 | 状态 | 验收证据 |
|---|---|---|---|
| **CFGKEY-1** | P1 存量收敛：`batch.*` 直接读取与高风险 / 多字段同前缀 `@Value` 提取到 `@ConfigurationProperties` | ✅ 已闭环 | §4 清单 P1 7 条 + P2 3 条全部收敛；登记项及扫描数量会随代码变化，不在此复制易过期计数；以 `scripts/ci/check-config-governance.py` 和 `scripts/ci/check-direct-config-key-access.py --report` 的当前输出为准。|
| **CFGKEY-2** | CI 报告模式：生成直接 key / `@Value` inventory，区分 `PROJECT_CONFIG`、`SECRET_CONFIG`、`SPRING_INFRA`、`JVM_SYSTEM`、`TEST_ONLY` | ✅ 已闭环 | `scripts/ci/check-direct-config-key-access.py`（`--report`/`--json`/`--write-baseline`/`--check-baseline`）已登记 `scripts/ci/README.md`，接入 Full Gate `static-checks` 报告模式并上传 `build/config-key-access.json` 产物（30 天），不阻断历史存量 |
| **CFGKEY-3** | CI 增量拦截：只阻断新增高风险 `batch.*` 直接读取和敏感 `@Value`，历史存量走 baseline | ✅ 已闭环 | `--check-baseline` 接入 PR Gate `PR_DIRECT_CONFIG_KEY_BASELINE`（java/ci 触发）和 Full Gate `FULL_DIRECT_CONFIG_KEY_BASELINE`（main push 回退），只对相对基线新增的高风险命中失败。当前基线文件仅含生成说明注释、没有存量条目；该文件已在 Git 跟踪，基线格式见 `docs/governance/direct-config-key-access-baseline.txt`。|

本项不要求把 `local.server.port`、`java.io.tmpdir`、`user.home`、`spring.application.name` 等合理例外强行改造；否则会制造噪声和第二事实来源。

存量清零：第一批收敛后基线仍剩 11 条 `batch.*` 高风险读取，后续已提取为 `@ConfigurationProperties`，基线归零（过程见[治理文档 §4.1](../runbook/config-key-access-governance.md)）。当前门禁对相对基线新增的高风险读取执行阻断；登记数量以检查脚本输出为准，不在此维护易过期的静态计数。脚本会忽略注释，避免迁移说明中的 `@Value` 示例被误报。

第二批再评估（2026-10-06 收敛）：治理文档 §4 明确保留的 4 项已全部完成，均为**去重与集中**、不新增业务配置语义、不迁移 key。

| 项 | 收敛去向 |
|---|---|
| `spring.datasource.url` / `spring.data.redis.*` 只读 inspector | `RuntimeInfrastructureInspector`（batch-common），供 `QuotaRuntimeBackendGuard`、`WorkerReportOutboxBackendGuard` 复用；不复制 `spring.*` 默认值 |
| `spring.application.name` 统一读取 | `ApplicationNameProvider`（batch-common，含供早期装配使用的静态变体）；默认值仍由各调用方传入 |
| worker `maxConcurrentTasks` 重复 `@Value` | `WorkerConcurrencyProperties`（`batch.worker.max-concurrent-tasks`，保留原 key 与默认值） |
| worker 本地路径默认值集中 | `PrivateTempFiles.tempRoot()` / `resolveUnderTempRoot()`（batch-common）；`java.io.tmpdir` 仍为 JVM 系统属性，不包装成业务配置 |

`RuntimeInfrastructureInspector` 的引入同时解除了 §6 阶段 2 中 `BatchSecurityProperties#spring.datasource.password` 白名单条目的复查条件（只读 inspector 已就位），该条目的白名单登记仍保留，仅作为“明确允许的例外”。`sdk/java/core` 的 `ShellAtomicHandler` 有意不接入临时目录策略：`batch-worker-sdk` 是对外发布的独立制品，刻意不依赖 `batch-common`。

第三批：测试源码口径与字面量收敛（2026-10-06）：

- **口径**：`src/test` **不拦截、不进基线**（测试本就要用 `MockEnvironment#withProperty(...)` /
  `@SpringBootTest(properties = ...)`），但扫描器 `--report` / `--json` 新增 TEST_SOURCE 快照，
  让「测试里新硬编码了哪个 key」可见；新增读取类型 `TEST_PROPERTY_FIXTURE`、`TEST_PROPERTY_ENTRY`。
- **收敛原则**：精确读取点（`System.getProperty(...)` / `@Value("${...}")` / `withProperty("key", ...)`）
  若 key 有归属类，一律引用其公开常量，不再重复字面量。
- **收敛清单**：`java.io.tmpdir` → `PrivateTempFiles.TEMP_ROOT_PROPERTY`（batch-common）/ `ShellAtomicHandler.TEMP_ROOT_PROPERTY`（sdk/java/core，不依赖 batch-common）；`local.server.port` → `ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY` / `LOCAL_SERVER_PORT_PLACEHOLDER`（batch-console-api）；`spring.kafka.bootstrap-servers` → `TriggerKafkaProducerConfiguration.BOOTSTRAP_SERVERS_KEY`（batch-trigger）；`batch.test.storage.backend` → `AbstractIntegrationTest.s3BackendActive()`（batch-test-support，判定与既有 `storageBackend()` 同源，三处 `@EnabledIf` 的本地重复方法删除）。
- **保留不改**：`user.dir`、`maven.multiModuleProjectDirectory`、`boundedContext.report` —— 由 JVM / Maven / 测试自身注入，无应用侧归属类，扫描器归 `TEST_ONLY` / `JVM_SYSTEM`（原先被误判为 `PROJECT_CONFIG`，本轮修正分类口径）。
- **夹具字面量 `properties = {"key=value"}` 保留**：量大且多为一次性覆盖，收敛收益低于改动成本，key 改名风险由 TEST_SOURCE 聚合段暴露。
- **量级变化**：生产命中 27 → 21（`--check-baseline`：新增高风险 0 项、白名单 1 项）；测试源码精确读取点 16 → 5（`TEST_PROPERTY_FIXTURE` 2 → 0、`VALUE_INJECTION` 1 → 0、`SYSTEM_PROPERTY` 13 → 5），夹具字面量 209 → 208（仅 `TriggerKafkaProducerConfigurationTest` 一处改为常量）。
- **扫描器口径修正**：`VALUE_INJECTION` 的 key 只取标识符形态，使 `@Value("${" + X.KEY + "}")` 这类「已收敛到常量」的写法不再被误报为字面量 key（收敛到常量正是目标状态，报成违规等于惩罚正确做法）。

第四批：全工程字面量收敛（2026-10-06，口径见 [治理文档 §4.2](../runbook/config-key-access-governance.md)）：

- **背景**：仓库里大量「同一字符串概念，一处用常量、一处写裸字面量」；典型是同一个方法里旁边已引用
  `PipelineRuntimeKeys.TRACE_ID`，紧邻的 `detailSummary.put("externalRequestId", ...)` 仍是字面量。
- **判据**：字符串**充当键或状态值**且**已有归属类**才收敛；缺归属类的登记待办，不新建常量。
- **收敛清单**：
  - 审计字段（`operationType`/`operationResult`/`operatorType`）→ `FileAuditOperationType` /
    `OperationResult` / `AuditLogConstants`，23 处；
  - dispatch 链路键 → 新建 `DispatchRuntimeKeys`（模块内键表，29 处）；跨模块的 `dryRunSkipped`
    登记在 `PipelineRuntimeKeys`；
  - `batch-worker/*` 的 pipeline attributes 键 → `PipelineRuntimeKeys`，243 处；
  - 枚举码值 → 对应枚举 `code()`：`FileStatus` / `FileReceiptStatus` / `OperationResult` /
    `FileAuditOperationType`，脚本 84 处 + 人工 18 处；
  - 审计操作者 → 新增 `AuditLogConstants.OPERATOR_TYPE_API`，console-api 3 处收敛；
    `FileGovernanceScheduler.ACTOR_SYSTEM` 改引 `AuditLogConstants.OPERATOR_TYPE_SYSTEM`。
- **契约补全**：`FileReceiptStatus` 增 `NONE`——DDL `ck_file_dispatch_receipt_status` 允许
  `('NONE','PENDING','SUCCESS','FAILED')` 且默认 `'NONE'`，枚举此前只有 3 个值，导致 3 处只能写裸 `"NONE"`。
- **明确不改**：MyBatis mapper 参数名（`params(...)` / `@Param("x")`）、`@JsonAlias`、出站响应体字段名
  （`plannedAction`，无归属类）、同名不同域的字面量（`retryPolicy` 的 `"NONE"`、`receipt_policy` 的
  `"NONE"`/`"SYNC"`、`result_version.status` 的 `"ARCHIVED"`）、无归属类的 `sourceType("GENERATED")`。
- **遗留登记**：`PlatformRuntimeValues`（batch-worker/core）持有一份与 `PipelineRuntimeKeys` /
  `StructuredLogField` 同值的私有常量表，服务 MyBatis 参数构造；是否合并单独评估。
- **顺带修复**：`batch-e2e-tests` 的 `ExportStorageFailureE2eIT` 残留本地 `s3BackendActive()`，与第三批
  在 `AbstractIntegrationTest` 新增的 `public static` 版本签名冲突（子类降权限），本轮删除。

第五批：缺归属类补齐（2026-10-06，口径与清单见 [治理文档 §4.2](../runbook/config-key-access-governance.md)）：

- **背景**：第四批按判据把「缺归属类」的 34 处登记为待办、不新建常量。本轮按明确授权补齐归属类，全部收敛
  （74 处引用点，另删除 3 个同义别名常量）。
- **补齐的归属类**：
  - 新建 `NodeOutputKeys`（batch-common）——ADR-009 节点产出键 / ADR-041 count 信封里**没有 attributes 归属类**
    的键（`inputCount` / `outputCount` / `batchKey`）。放 batch-common 是因为生产方（各 worker adapter）与消费方
    （orchestrator `CountContinuityOutboxService` / `WorkflowGraphValidator`）都要看见同一份键名；与 attributes 键
    同名同义的产出键仍复用各自键表常量，**不**新建同义别名。收敛 14 处。
  - 新建 `ImportRuntimeKeys`（batch-worker/import）——import 模块内跨类共享的 attributes / file_record metadata
    键（`successCount` / `failedCount` / `manualReviewRequired` / `charsetSuspect` / `replacementCount` /
    `detectedCharset` / `lastBadRecord` / `lastProcessedRecordNo` / `lastErrorCode` / `lastErrorMessage`）；
    `LoadStep` 的两个私有同义常量删除改引本表。收敛 31 处。
  - 新建 `ExportRuntimeKeys`（batch-worker/export）——export 跨 stage 私有键（`exportPayload` / `exportBatch` /
    `exportCharset` / `exportWithBom` / `exportLineSeparator` / `exportDataRef`）。收敛 22 处。
  - 扩 `PipelineRuntimeKeys.PAYLOAD` / `STEP_CODE`（跨模块 pipeline attributes 键），并删除
    `DefaultStepExecutionAdapter` 的私有同义别名 `CONTEXT_PAYLOAD`、收敛 `DefaultTaskExecutionWrapper` 写入点。
    收敛 6 处。
  - 扩 `ProcessRuntimeKeys.PROCESS_STAGING_MODE`（`processStagingMode`）。收敛 1 处。
- **同批收口**：`DefaultProcessStageExecutor` 的 output_summary `batchKey`、`ProcessPublishedCountVerifier` 的
  evidence `batchKey` 与产出键同名同义，一并改引 `NodeOutputKeys.BATCH_KEY`。
- **明确不改**：`SqlTransformComputeSpec.RESERVED_PARAMS` / `PARAM_BATCH_KEY` 等 SQL 参数名；
  `output_summary` 的 `stepCode` / `stage` / `implCode` / `tenantId` / `workerId` 等展示字段；只在单个类内流通的
  私有键（`parseSkippedCount` / `snapshotMode` / `totalAmount` 等）保留为类内常量。
- **顺带满足守护**：改既有行触发的 5 处 `== null` / `!= null` 统一改用 `EmptyChecks`（零行为变更）。

### A. POSITIONAL-ARGS 治理（V6-P2-POSITIONAL-ARGS）· P2 · ✅ 已闭环

> 状态：v4 已闭环，并行会话产出 + 守护测试到位。历史方案见 [`../archive/analysis/positional-args-cleanup-plan-2026-05-01.md`](../archive/plans/positional-args-cleanup-plan-2026-05-01.md)。docs/agent-baseline.md "调用方约束" 子节由本方案沉淀。

历史详细计划项（POS-1 ~ POS-5）已全部完成，归 §五。

### B. Query Record 工厂 · P2 · ✅ 已闭环

QF-1/QF-2/QF-3 全部完成，包含守护测试 `QueryRecordConstructionConventionTest`。归 §五。

### C. ADR-009 Stage 4（业务方按需触发）· P2 deferred

| ID | 主题 | 来源 |
|---|---|---|
| **ADR9-S4** | 7 workflow 配 DSL 引用上游节点 output | hardening-backlog v6 / ADR-009-workflow-param-dsl.md |

> 状态：Stage 1 / 1.2 / 2 / 3 代码 ✅（`DefaultWorkflowNodeDispatchService.mergeNodeParams` 集成 `WorkflowParamResolver` + 4 worker `*StepExecutionAdapter` 填 `NODE_OUTPUTS`）。Stage 4 deferred — 现 seed 节点间 `mergeUpstreamPartitionOutputs` 自动透传 `fileId` 已够用，业务方设计跨节点参数串联时按 §10 文档配。

### D. ADR-010 灰度 + 物删 · 已完成（历史记录）

> ADR-010 的异步 Kafka 路径已固化为唯一路径，灰度开关和同步 HTTP 适配器已删除。以下旧清单不再是待办或操作指引；当前架构以 [`ADR-010`](../architecture/adr/ADR-010-trigger-async-decoupling.md) 与 [`Trigger 运维手册`](../runbook/trigger-operations.md) 为准。

| ID | 主题 | 来源 | 关联门禁 |
|---|---|---|---|
| **ADR10-S6/S7** | 灰度、唯一异步路径及旧同步入口清理 | ADR-010 实施后记 | 已完成；不再执行旧灰度 SOP |

> 注意：旧版本评估快照中的分阶段状态只用于还原历史，不应重新登记为当前待办。

### E. Quartz → HashedWheelTimer · 已撤销（历史记录）

> [ADR-033](../architecture/adr/ADR-033-quartz-to-wheel-scheduler.md) 已于 2026-07-23 标记 `Superseded`，Wheel 运行路径已移除，当前统一使用 Quartz。下表旧切换任务与阈值均已失效，不是待办；若未来重新评估，须基于新的容量证据另立决策。
>
> 以下仅保留原计划条目作为历史记录，不应执行或纳入当前待办。

| ID | 主题 | 来源 |
|---|---|---|
| **QZ-pre-1** | 业务方明确 cron 精度 SLA | quartz-replacement-design:848 |
| **QZ-pre-2** | cron 兼容性扫描确认 L/W/# 表达式为 0 | quartz-replacement-design:849 |
| **QZ-pre-3** | trigger_runtime_state schema DBA 评审 | quartz-replacement-design:850 |
| **QZ-pre-4** | trigger_request fire 唯一约束不冲突验证 | quartz-replacement-design:851 |
| **QZ-prep-1** | Redis ShedLock 在 trigger 模块就位 | quartz-replacement-design:855 |
| **QZ-prep-2** | Quartz `auto-start=false` 切换路径验证 | quartz-replacement-design:856 |
| **QZ-prep-3** | 4 个 Quartz health metric 在 Grafana 显示 | quartz-replacement-design:857 |
| **QZ-decision-1** | 集成测试矩阵全部通过 | quartz-replacement-design:861 |
| **QZ-decision-2** | 性能测试达标 | quartz-replacement-design:862 |
| **QZ-decision-3** | failover IT 100 次循环无双 fire / 漏 fire | quartz-replacement-design:863 |
| **QZ-stage-1** | Staging 环境跑 2 周无回归 | quartz-replacement-design:867 |
| **QZ-stage-2** | 生产灰度方案制定与验证 | quartz-replacement-design:868 |
| **QZ-stage-3** | 监控告警 3 项就位（QPS/lag/duplicate）| quartz-replacement-design:869 |
| **QZ-rollback-1** | 回滚配置项验证过 | quartz-replacement-design:873 |
| **QZ-rollback-2** | Quartz 数据迁回 SQL 验证 | quartz-replacement-design:874 |
| **QZ-rollback-3** | trigger_runtime_state 表保留不删 | quartz-replacement-design:875 |

### F. Workflow 设计规范 8 校验项 · P1

> 性质：设计 review checklist，非实施工作。Workflow 写入数据库前 PR review 时勾选。

| ID | 主题 | 来源 |
|---|---|---|
| **WF-design-1** | GATEWAY 节点显式写 joinMode | workflow-dependency-guide:323 |
| **WF-design-2** | joinMode=N_OF 必须带 joinThreshold | workflow-dependency-guide:324 |
| **WF-design-3** | 非 START 节点至少一条入边 | workflow-dependency-guide:325 |
| **WF-design-4** | 非 END 节点至少一条出边 | workflow-dependency-guide:326 |
| **WF-design-5** | JOB 节点 related_job_code 有效性验证 | workflow-dependency-guide:327 |
| **WF-design-6** | CONDITION 边 condition_expr 必须配置 | workflow-dependency-guide:328 |
| **WF-design-7** | Workflow 不能有循环 | workflow-dependency-guide:329 |
| **WF-design-8** | workflow definition enabled=true | workflow-dependency-guide:330 |

### G. 删除策略决策清单 · P1 · ✅ 已落地为 PR 模板

5 项 checklist 嵌入 `.github/PULL_REQUEST_TEMPLATE.md` "涉及删除语义的接口" 段，reviewer PR-time 勾选即可。同模板顺带嵌 4 类常见 review checklist（console-api / 字典 / 方法参数 / i18n / 规范条款）。

### G2. REST / Command API Toggle 语义治理 · P2 · ✅ 已完成

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| ~~**API-TOGGLE-1**~~ | 6 个 `POST .../toggle?enabled=` 接口命名与显式状态契约收敛 | [`../backlog/rest-command-api-toggle-governance-2026-09-26.md`](../archive/plans/rest-command-api-toggle-governance-2026-09-26.md) | ✅ 2026-09-27 统一改为 `PATCH .../enabled`，前端及测试已切换，旧接口已删除 |

### G3. 批量平台能力演进 · P0/P1/P2 · 🟡 部分完成，证据待收口

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| **PLAT-OTEL-1** | OpenTelemetry 全链路运行验收：Console/API → Trigger → Orchestrator → Kafka → Worker → Report | [`platform-capability-evolution-plan-2026-09-26.md`](platform-capability-evolution-plan-2026-09-26.md) | 🟡 Worker MDC 最小字段集已补齐；运行证据采集脚本和验收步骤已补；真实链路、Tempo/Loki 关联和告警触发仍需 staging 证据 |
| **PLAT-BP-1** | 背压与容量大盘收口：admission、claim/report、Outbox、Kafka lag、Hikari、PG 锁等待、Worker lease | 同上 | 🟡 容量仪表盘、Prometheus 告警和证据脚本已补；真实压测阈值和容量结论仍需 staging |
| **PLAT-WR-1** | 五类 Worker Runtime / SPI 行为一致性复核 | 同上 | ✅ 核心实现和本地 Sim 已完成；🔒 staging/生产级证据仍需补齐 |
| **PLAT-KEDA-1** | KEDA staging 验证：dynamic sharding + backlog / lag 扩缩 + drain | 同上 | P1；需要真实 K8s + KEDA operator |
| **PLAT-GITOPS-1** | GitOps staging 接入：镜像、ops repo、Argo CD、Helm values、smoke | 同上 | P1；当前只有骨架 |
| **PLAT-CDC-1** | CDC / Streaming 方案设计 | 同上 | P2；业务触发后再立项 |

### G4. Console AI 助手与成本治理 · P1/P2 · 🟡 本地开发已收口，治理证据待外部验收

权威方案：[`ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md`](ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md)。本节只登记仍可能实施的后端工作；AI 方案中的暂缓和不做项单独列出，不能当作当前开发任务。

| ID | 主题 | 状态 |
|---|---|---|
| **AI-CTX-1** | 版本化页面上下文、会话语义、租户/角色授权、领域拒答和来源引用契约 | ✅ v1 白名单上下文、SSE 流式回复、服务端取消、多轮上下文、会话所有者隔离、删除和过期清理均已落地；完成响应返回去重后的结构化来源名称，配对前端已展示引用且不暴露片段或相似度 |
| **AI-COST-1** | 输入/输出 token 上限、并发舱壁、日/月预算、provider 错误分类、成本与拒答指标 | 🟡 后端已实现 provider 费率估算、原子月预算预留、UTC 台账和摘要 API；供应商账单核对、真实价格校准和 staging 并发/故障验证待做 |
| **AI-AUDIT-1** | AI 审计默认只保留元数据/哈希/成本信息，复核原文预览留存策略和查询权限 | 🟡 新请求原文预览默认关闭并暴露成本/token 字段；历史预览清理、生产留存和审计查询角色合规确认仍待做 |

以下是已冻结的范围决策，不重新排成开发待办：

| 决策 | 范围 | 状态 |
|---|---|---|
| **AI-DEC-1** | Spring AI M3 → GA | 🟡 等上游发布，当前不做升级 |
| **AI-DEC-2** | 外部 AI provider 契约接入 CI | ❌ 不做；需要外部 secrets，保留人工/受控验证方案 |
| **AI-DEC-3** | AI 上线判定和受控试生产 | 🟡 暂缓，等待真实质量/成本证据 |
| **AI-DEC-4** | Phase 3 AI 直接写操作/HITL | 🟡 后置，继续复用现有审批闭环 |

### G5. Console 使用率统计 · P1/P2 · 🟡 本地日聚合已落地，生产对账与保留策略待外部验收

权威方案：[`console-usage-statistics-plan-2026-09-29.md`](console-usage-statistics-plan-2026-09-29.md)；前端配套清单见配对仓库 `batch-console/docs/backlog/ai-and-usage-statistics-todo-2026-09-29.md`。第一版只做可解释的 PostgreSQL 日聚合，不引入 Kafka、ClickHouse 或通用行为分析平台。

| ID | 主题 | 状态 |
|---|---|---|
| **USAGE-1** | 固化指标目录、成功口径、数据来源优先级、权限和保留期 | ✅ 后端指标来源、成功口径、租户权限和派生数据边界已固化；长期保留期仍需运维策略确认 |
| **USAGE-2** | Flyway `console_usage_daily` 月分区、严格 RLS、索引和保留策略 | ✅ V216 已落地分区、严格 RLS 和索引；长期分区保留/归档仍需 staging 策略 |
| **USAGE-3** | 后端事件标准化、有界批量 upsert、`usage-summary` DTO/API/OpenAPI | ✅ 已落地操作审计投影、并发累加 upsert 和独立 summary 契约；当前为同步 best-effort 投影，有界异步批量明确不在本轮范围 |
| **USAGE-4** | 与前端埋点、操作审计和业务终态对账，补齐租户隔离/并发/失败语义测试 | 🟡 新增真 PG 集成测试覆盖并发累加、RLS 隔离和事务租户上下文；前端展示/业务结果对账及 staging 容量与保留策略仍待验证，接口不能替代业务结果对账 |

### G6. Console 维护与服务降级完善 · P0/P1 · 🟡 基础能力已落地，增强项未完成

权威方案：[`../plans/maintenance-degradation-hardening-plan-2026-09-29.md`](maintenance-degradation-hardening-plan-2026-09-29.md)。本项只完善现有维护模式、下游降级、前后端状态契约和多副本收敛，不扩展为通用服务治理平台。

| ID | 主题 | 状态 |
|---|---|---|
| **MAINT-BE-1** | PostgreSQL 维护状态唯一事实源、版本 CAS、实例确认和重启恢复 | 🟡 V215 已落地；已实现启动读取、5 秒轮询、版本 CAS 和失联写保护，双实例/staging 证据待补 |
| **MAINT-BE-2** | 维护 503 body/header、权限绕过和审计契约收口 | ✅ 后端 503 body、`X-Maintenance`、`Retry-After`、版本 Header、管理员热切换和审计已收口；细粒度旁路权限与前端联测仍按后续边界治理 |
| **DEGRADE-BE-1** | `X-Degraded-Source` 标准化输出，读 fallback / 写 fail-fast 守护 | ✅ 后端 fallback 统一写来源 Header，写路径继续 fail-fast；配对前端已完成 Header 消费、桌面/移动提示及受控恢复联测，其他下游和真实移动设备仍按环境验收项管理 |
| **DEGRADE-BE-2** | 复用现有 Micrometer 增加维护状态、维护 503、fallback 比例和耗时告警 | ✅ 后端维护状态 gauge、维护请求结果、下游 fallback 计数、副本健康和容量/背压告警已补；真实阈值校准待 staging |
| **MAINT-JOINT-1** | 双 Console 实例维护切换、下游断路器和恢复联测 | 🔒 需多实例/staging 环境验证 |

明确不做：服务网格、通用动态路由、工单/通知中心、独立配置中心和写接口自动成功降级。

### G7. CI 外部 Actions 版本治理 · P2 · 🟡 基线升级已验证，镜像链路待补证据

专题记录：[`ci-external-action-upgrade-review-2026-09-30.md`](../audit/ci-external-action-upgrade-review-2026-09-30.md)。责任范围：仓库 CI/发布流程维护者。2026-10-01 复核确认通用 Action、Hadolint、SBOM Action、Squawk 和 oasdiff 已升级，相关 PR 门禁及最新 `main` Full Gate/CodeQL 已通过；Docker Buildx/Bake 的真实镜像 workflow 尚无升级后运行记录，继续单列。

| ID | 主题 | 状态 |
|---|---|---|
| **CI-ACTION-1** | checkout、setup-python/java/node/go、upload-artifact 与 Hadolint Action 升级 | ✅ 已统一升级，相关 PR 门禁及最新 `main` Full Gate/CodeQL 已通过；self-hosted runner 和 GHES 兼容性仍按部署环境单独确认 |
| **CI-ACTION-2** | Docker Buildx setup action v3→v4、Docker Bake v6→v7 | 🟡 引用和静态门禁已完成；仓库尚无升级后的真实镜像 workflow 运行记录，需在下一次镜像构建时确认缓存、driver 和元数据 |
| **CI-ACTION-3** | 其余第三方 Action、扫描器和浮动版本引用复核 | 🟡 已完成盘点并保留兼容引用；GitHub Actions Dependabot 已开启每周版本队列，`trivy-action`、Checkov、发布 Action 等仍按上游安全公告和契约单独复核 |
| **CI-SEC-1** | Gitleaks 8.30.1 CI Linux artifact 正向/负向检测验证 | ✅ Docker linux/amd64 基础正负样例及 PR/Full Gate 安全扫描已通过；该证据不替代后续规则更新与新增密钥类型回归 |
| **CI-SEC-2** | 更新 Squawk CLI 与 oasdiff 固定版本 | ✅ 已升级到 Squawk `2.65.0` / oasdiff `1.32.1`，OpenAPI 变更门禁已实跑；无迁移变更时 Squawk 按规则跳过，下一次迁移继续验证兼容/破坏样例 |

### G8. 随机数 / 加密 / ID 治理 · P1/P2 · ✅ 已完成

专题审查：[`../review/random-crypto-id-review-2026-10-01.md`](../review/random-crypto-id-review-2026-10-01.md)。责任范围：后端安全基础库与五语言 SDK。当前结论是服务端主加密算法选型基本正确，未采用 Snowflake；本地可修的 SDK 随机质量、业务编号碰撞余量、KMS 启动期校验和请求签名输入边界已完成。

| ID | 主题 | 状态 |
|---|---|---|
| **CRYPTO-ID-1** | TypeScript SDK claim/report `Idempotency-Key` 从 `Math.random()` 改为 `node:crypto.randomUUID()` | ✅ 已完成 |
| **CRYPTO-ID-2** | Rust SDK idempotency-key / signing nonce 从时间种子 xorshift 改为 `getrandom` CSPRNG | ✅ 已完成 |
| **CRYPTO-ID-3** | `IdGenerator.newBusinessNo` 随机后缀从 8 hex 扩到 16 hex，修正 `newBusinessNoBatch` 注释/测试 | ✅ 已完成 |
| **CRYPTO-ID-4** | KMS key 启动期校验 AES key 长度（16/24/32B）和 `defaultKeyRef` 存在性 | ✅ 已完成 |
| **CRYPTO-ID-5** | 请求签名 header 增加 timestamp / nonce / signature 长度与格式边界 | ✅ 已完成 |
| **CRYPTO-ID-6** | Go SDK 处理 `crypto/rand.Read` 错误，失败时 fail-fast | ✅ 已完成 |

### H. 合规收尾 · P3

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| ~~**LIC-1**~~ | NOTICE 文件 copyright + 上游聚合完整化 | license-risk-assessment:171 | ✅ 2026-05-01 NOTICE 升级：从 16 行指针式扩到 ~110 行，聚合 Spring Boot/POI/Kafka/Flyway/MyBatis/MinIO/Logback/SLF4J 等 20+ 主要上游 attribution（满足 Apache-2.0 §4(d)）|
| **LIC-2** | SBOM 与第三方清单嵌入 artifact | license-risk-assessment:173 | 🟡 **暂缓** — 🔒 部分 ops(本地能改 maven,CI 注入 + artifact 校验需 ops);触发条件:合规审计 / 客户 SBOM 要求时启动 |
| ~~**LIC-3**~~ | 新依赖 license 检查与约束 | license-risk-assessment:174 | ✅ 2026-05-01 `scripts/ci/check-dependency-licenses.sh` 落地 |

### I. Worker 灰度升级 runbook 验证 · P2

> 本地 E2E 已覆盖 drain 发起与 claimed-tasks 查询、心跳指令驱动真实 Kafka listener 暂停、超时接管和 force-offline 回收；真实 Pod 驱逐、负载均衡与多副本滚动升级仍需 staging worker 集群 — 见 §九。

| ID | 主题 | 来源 |
|---|---|---|
| **WK-up-1** | ✅ 本地 E2E 已验证 drain 发起、claimed-tasks 查询和心跳驱动 Kafka listener 暂停；staging 验证真实 Pod | rolling-upgrade-workers:76 |
| **WK-up-2** | ✅ 本地 E2E 已验证超时后 Orchestrator 回收；staging 验证真实重调度 | rolling-upgrade-workers:77 |
| **WK-up-3** | ✅ 本地 E2E 已验证 force-offline 即时回收；staging 验证紧急操作流程 | rolling-upgrade-workers:78 |

### J. @Deprecated forRemoval 物删积压 · P3

> 代码线索：grep `forRemoval=true`
> 🔒 DEP-1 需灰度门禁，DEP-3/4/5 已物删；DEP-2 本地可做 — 见 §九

| ID | 主题 | 代码位置 | 备注 |
|---|---|---|---|
| ~~**DEP-1**~~ | `HttpOrchestratorTriggerAdapter` 物删 | batch-trigger:HttpOrchestratorTriggerAdapter.java:27 | ✅ 已随 ADR-010 唯一异步路径落地完成；保留历史记录 |
| ~~**DEP-2**~~ | `BatchSecurityProperties.testingOpen` 物删 | batch-common:BatchSecurityProperties.java:45,51 | ✅ 已完成；代码与配置键已物理删除，保留历史记录 |
| ~~**DEP-3**~~ | `ConsoleAlertRoutingExcelController` 4 处旧端点物删 | batch-console-api | ✅ 2026-05-01 物删 + OpenAPI 同步 |
| ~~**DEP-4**~~ | `ConsoleFileTemplateExcelController` 4 处旧端点物删 | batch-console-api | ✅ 同上 |
| ~~**DEP-5**~~ | `ConsoleResourceQueueExcelController` 4 处旧端点物删 | batch-console-api | ✅ 同上 |

### K. 代码内 follow-up · P3 · ✅ 已完成

| ID | 主题 | 代码位置 |
|---|---|---|
| ~~**EXT-1**~~ | V5-P2-4-ext: JOB / BATCH compensation happy-path | ✅ `DefaultCompensationServiceTest` 已覆盖 JOB/BATCH submit happy-path（约 269-328 行）；原 TODO 索引已过期 |

### L. 历史一次性失败修复 · P3 · ✅

`HIST-1` 4 个 E2E ConditionTimeout 失败已修（2026-05-01 的历史记录；当时结论见已归档的 `docs/archive/testing/e2e-coverage-2026-05-03.md`，不代表当前 E2E 状态）。

### M. IPv6 Happy Eyeballs 渐进治理 · P1

权威方案：[`ipv6-happy-eyeballs-rollout-2026-09-24.md`](ipv6-happy-eyeballs-rollout-2026-09-24.md)。本事项采用 transport 抽象和应用级适配器，不做全工程直接替换。

| ID | 主题 | 状态 |
|---|---|---|
| **HE-1 ~ HE-4** | 平台 transport、JDK 外部调用迁移、全地址 SSRF、既有 OkHttp DNS 对齐 | ✅ 已完成 |
| **HE-5** | IPv4/IPv6 黑洞与单栈/双栈故障注入 | ✅ 本地已完成，staging 证据归 HE-7 |
| **HE-6** | 五语言 SDK 控制面 HTTP 双栈治理 | ✅ 本地已完成；生产同构网络全矩阵归 HE-7 |
| **HE-7** | staging DNS/路由/NetworkPolicy/出口代理验证 | 🔒 外部阻塞 |

---

## 四、🟡 半成

### V6-P2-EXCEL-GODCLASS 6/7 完成

- 进度：6 个 god class 拆完平均 -67% LOC，1 个保留
- 保留项：`ConfigPackageExcelValidator` 874 LOC — 已是 single-purpose validator，内部 8 个 `validateXxxRows` 共享 cross-reference 数据，split 反而 fragment + overhead → 评估为"不拆"
- 来源：hardening-backlog v6
- 下一步：无（评估为完整状态，7 类标 6/7 是因第 7 类决定不动）

### V5-P1-1 ADR-009 Workflow DSL · 代码 100%，业务配置 0%

- 代码：Stage 1（V72）/ 1.2（worker outputs）/ 2（`WorkflowParamResolver` 160 LOC + 10 单测）/ 3（`mergeNodeParams` 集成）全栈
- 业务：Stage 4（7 workflow 配 DSL）deferred，等业务方触发
- 来源：hardening-backlog v6 / project-assessment v1

### ADR-010 Stage 6 灰度 / Stage 7 物删

- 状态：文档 + 代码 ✅，operational 0%
- 未做：见 §三-D
- 🔒 全部 7 项需 ops / staging / prod — 见 §九

### Worker 4 模块单测密度（V6-D-5）✅

- 已完成：4 个 `Default*StageExecutor` 均已有 5 个以上场景；Import / Export / Process / Dispatch 的 `*StepExecutionAdapterTest` 均补齐 5 个核心契约场景。
- 来源：hardening-backlog v6

### 历史 flaky 测试根治 ✅

- `JobLaunchToFinishLifecycleIntegrationTest`：失败上报补齐 claim 生成的 `partitionInvocationId`，恢复 CAS 真实契约后取消禁用。
- `RlsStrictModePreflightIntegrationTest`：回滚策略对齐生产 SQL 的空字符串 GUC 语义后取消禁用。
- 全仓仅保留 `ShellTaskExecutorTest` 的 Windows 平台条件禁用，不再存在无条件 `@Disabled`。
- 2026-09-11 连续 3 轮目标集成验证通过。

---

## 五、✅ 已完成（简表）

> 维护：每月 review，把 ✅ 项移到 §归档（避免本节越写越长）

### deep-issue §5（全 6 项 ✅）

- §5.1 trigger Spring Security：`cd389a0b`（2026-04-22）— `TriggerSecurityConfiguration:42-46` 真起 SecurityFilterChain
- §5.2 X-Console-Token：✅ commit `ff20c36f` 主代码 + yaml + OpenAPI + 测试 9 文件 +20/-168 物理删除；grep 全仓 0 残留
- §5.5 / §5.6 / §5.10 idempotency 三层边界：ADR-011 定稿，3 层代码已实施
- §5.7 trigger → orchestrator 异步：ADR-010 全栈 7 stage（`9587b8bf` / `087f6b7a` / `1ca3a957` / `22b330ea` / `788b637d` / `68bc49e8`），22 测试全部通过
- §5.11 webhook durability：V81 + `WebhookDeliveryRelay` 278 行 + 7 单测全部通过
- §5.12 Console Job god-class：`DefaultConsoleJobApplicationService` 现 90 LOC + 6 兄弟类 1278 LOC

### ADR 路线图（5 完成 / 2 deferred / 2 暂缓）

- ✅ **ADR-009** Workflow DSL Stage 1 / 1.2 / 2 / 3 全栈
- ✅ **ADR-010** trigger 异步 Stage 1-5 代码 100%
- ✅ **ADR-011** idempotency boundary 定稿
- ✅ **ADR-012** 失败分类（V111）
- ✅ **ADR-021** 数据对账 v1.0（V118 + DataQualityCheckExecutor + EFFECTIVE gate）
- ✅ **ADR-022** Forensic v0.1（V116 + 同步 bundle + SHA-256）
- ✅ **ADR-023** 多日历联动（V112-V114）
- ✅ **ADR-025** Workflow 静态校验（15 条规则 V1-V15）
- ✅ **ADR-026** dry-run 全链路（V115 + V117 + DryRunGuard SPI + L1/L2/L3 service）
- 🟡 ADR-009 Stage 4 deferred / ADR-010 Stage 6+7 灰度+物删 deferred
- 🟡 ADR-024 / ADR-027 暂缓（触发条件未达）

### v2 评估 4 项硬化（全 ✅）

- V6-OPS-1 Kafka topics（含 `scripts/ci/validate-kafka-topics.sh` 守护，三向 diff）
- V6-OPS-2 Prometheus 3 告警：`0c623eb0`
- V6-Q-1 9 处 FQN 违规：`8dc6eac1`
- V6-NOISE-1 运行日志噪声治理：`aa249bf8` / `0d650fab`

### v6 P2 god-class 拆分（全 ✅）

- V6-P2-WEBHOOK-DURABILITY：`b74e0a0c`
- V6-P2-ORCHESTRATOR-GODCLASS：`7d6faad6`（`DefaultTaskOutcomeService` 926→795 / `DefaultWorkflowNodeDispatchService` 840→371）
- V6-P2-EXCEL-GODCLASS：6/7 拆完平均 -67% LOC（`002b8864` + `bd0f0532` + `b9eefb47`）
- V6-P2-CONSOLE-IDEMPOTENCY：3 层代码各自归位
- V6-P2-POSITIONAL-ARGS v4：闭环 + 守护测试到位

### Query Record 工厂（QF-1 / QF-2 / QF-3 ✅）

12+ Query record 静态工厂补齐 + `QueryRecordConstructionConventionTest` 守护到位 + ~25 处 `new XxxQuery(t, null, null, null, ...)` call site 替换完成。

### v5 历史 P0-P3（19 项 ✅）

详见 [`hardening-backlog.md`](hardening-backlog.md) §四（已完成 v5 清账表）。

---

## 六、❌ 不做（归档）

| ID | 主题 | 理由 |
|---|---|---|
| V5-P2-1 | 6 渠道非 SFTP dispatch 单 adapter IT | 业务接入对应渠道时再做，无前置 |
| V5-P2-9 | Workflow PIPELINE/MIXED + GATEWAY/FILE_STEP 节点 | 依赖 V5-P1-1 完整落地后再做（现 ADR-009 Stage 4 也 deferred）|
| V5-NEW-1 | workflow steps 协议错位 | 不构成 bug — worker 代码不读 task_payload.steps，4-24 commit `3dbb6d22` 修了 resolveJobCode + node_params 后未复现 |
| V5-NEW-2 | exp_settlement_csv_v1 模板源头 | default-tenant 7 个 "system" 模板之一，业务无引用，不影响主链路 — 历史遗留 + 不影响，不再追溯 |

---

## 七、维护规则

1. **新发现待办**：加进对应主题段（A-L），用编号 `<主题缩写>-<序号>`
2. **状态变更**：
   - ⏳ → 🟡：开干时改
   - 🟡 → ✅：验收门禁通过（单测 / IT / 灰度后）
   - ✅：本节保留 1 个月，然后归档到 hardening-backlog
3. **每月 review**：用 grep + 真实代码状态校验每条，避免"顶部已完成 / 明细未更新 / 引用悬空"的不一致
4. **删除 / 不做**：进 §六，带理由
5. **源文档变更同步**：hardening-backlog / deep-issue / project-assessment 状态变更时，同步本文件；反向不做（本文件是聚合视图，不取代源文档）

---

## 八、对账校验

| 校验项 | 命令 | 说明 |
|---|---|---|
| Query record 工厂数 | `for f in $(find docs/query/*.java); do grep -c 'public static.*\bof' $f; done` | 已闭环，详见 §五 Query Record 工厂段 |
| 代码 TODO 总数 | `rg '\b(TODO\|FIXME\|XXX\|HACK)\b' --glob '*.java'` | 当前应重新扫描；旧的 `DefaultCompensationServiceTest:167` V5-P2-4-ext 已由现有 happy-path 测试覆盖 |
| `@Deprecated forRemoval=true` | `rg 'forRemoval' --glob '*.java'` | 5 类（HttpOrchestratorTriggerAdapter / BatchSecurityProperties / 3 个 ExcelController），后 3 个已物删 |
| 多 null 占位 inline new（≥2 null）| `rg -nU --multiline 'new \w+\([^)]*null[^)]*null[^)]*\)' --glob '*.java'` | POSITIONAL-ARGS v4 闭环后回归 0；新增由守护测试拦截 |

---

## 九、🔒 本地不能做（需 ops / staging / DBA / 业务方 配合）

> 用途：本仓库内的自动化 agent / 开发者**无法独立完成**的项，挂在这里直至外部条件就绪。
> 图例：每条标注阻塞类型 — `[ops]` 部署/CD · `[staging]` 预发环境 · `[prod]` 生产环境 · `[DBA]` 数据库变更评审 · `[BIZ]` 业务方决策 · `[client]` 外部 API 客户端确认

| ID | 主题 | 阻塞类型 | 卡在哪 |
|---|---|---|---|
| **ADR10-S6/S7** | 异步路径灰度与同步入口清理 | — | 已完成；历史灰度步骤不再适用 |
| **WK-up-1** | drain 接口和 claimed-tasks 的真实集群验证 | `[staging]` | 本地定向 Playwright API 用例于 2026-10-12 复验通过（`3. drain 端点真实进入 DRAINING`，1/1）；剩余真实 Pod drain / 负载均衡验证 |
| **WK-up-2** | 超时后接管的真实集群验证 | `[staging]` | 本地 E2E 已完成；剩余 Pod 驱逐后的真实重调度验证 |
| **WK-up-3** | force-offline 紧急流程的真实集群验证 | `[staging]` | 本地 E2E 已完成；剩余多副本和运维操作留痕验证 |
| **LIC-2** | SBOM 嵌入 artifact + 第三方清单 | `[ops]` 部分 | 本地能改 maven 配置，但 CI 注入 + artifact 校验需 ops |

本节只维护逐项状态，不维护易随去重口径变化的汇总数字。FE-1/2/3、CC-1/2 已在 §二标记完成，不再列入待办。

2026-10-07 复扫结论：当前权威待办中没有可脱离外部环境独立完成的后端编码项。旧专题文档里 Python SDK 心跳退避/暂停类型、租户 Worker 模板与 Topic 脚本、JVM Helm 诊断挂载、Telemetry 收敛、ShedLock provider 失败观测等陈旧勾选项已按真实代码状态校正；剩余均为本节所列 staging/ops/生产验收边界。

## 批量账号开户（Bulk User Provisioning）

**状态：前后端已编码 / P1，本地真实 PG/Redis 核心事务已验，尚未通过 staging/生产验收。** 已有批量创建租户、租户配置批量初始化和单账号 CRUD。

后端设计及验收边界：[批量账号开户后端设计](../design/bulk-user-provisioning.md)；配对前端实现位于 `batch-console` 的账户管理页面。最后核查：2026-10-07。后端责任域为 Console/RBAC。

- [x] 批量账号模板、Preview、逐行 Patch、原子 Apply、非敏感批次查询 API 与 OpenAPI；前端不循环调用单账号接口。
- [x] 批量创建复用现有 `ConsoleUserAccountService` 的租户作用域、角色授予和哈希创建守卫；平台角色固定 `system`，租户角色须绑定 ACTIVE 业务租户。
- [x] V220 增加 `lower(username)` 唯一索引；批次内大小写重复、存量冲突预检与事务回滚由数据库约束兜底。
- [x] 四角色授权边界；TENANT_ADMIN 只能创建本租户 TENANT_ADMIN / TENANT_USER。
- [x] 一次性随机初始密码只在 Apply 回包出现；`must_change_password=true`，当前登录改密仅提示不阻断。
- [x] 单账号创建路径与批量路径统一业务租户存在/状态校验；平台角色只能绑定 `system`，租户角色必须绑定 ACTIVE 业务租户。
- [x] 跨租批次查询支持按目标租户过滤；`user.batchCreate` 操作审计使用 `requestId` 作为 `user_batch_operation` 聚合键，批次记录用同一 `requestId` 关联。
- [x] 本地真实 PG/Redis 联测：成功原子提交、预览删除、约束失败整批回滚并恢复预览、并发 Apply 仅一个成功。
- [ ] staging/前台验收：V220 历史冲突预检、过期/多副本预览、前台丢包恢复、初始密码安全交付与多用户冲突。

### ALIGN-DB-REF 增量（RT-5 Config Sync，2026-10-18；多上游保真，2026-10-19）

当前实现：Config Sync 按“配置项 × 目标租户”记录结果，复制/导入保留多上游与逐边业务日规则；源读取绑定源tenant，各目标使用独立REQUIRES_NEW事务并绑定自身tenant，避免RLS丢边/拒绝与源只读、其它目标rollback-only污染。冷启动先建包内日历；租户级后置阶段失败回滚该目标全部写入，普通单项错误继续由保存点隔离。普通角色已证明成功目标保留、失败目标零Job/依赖/Workflow残留，双目标全成功日志6/6及混合结果日志3成功/3失败。配置受影响回归为5类48/48（PG16、单测32），随后只跑新增混合结果方法1/1通过；运行侧98项证据继续保留，原首红及两项旧夹具失败未被覆盖。后续真实双事务复现“应用预检后上游删除”的失败响应泄露SQL/路径，已按结构化约束身份修复，并保留strict整体回滚/non-strict保存点语义。包装后的未知数据库异常统一返回系统错误键；删除仍被引用的上游继续409。该切片受影响回归7类81/81（PG19、单测62）。随后源读取并发复现旧Job/新Workflow混包，改为独立只读REPEATABLE_READ快照；仅定向复测2/2，受影响3类18/18（PG11、单测7），目标独立写入及失败隔离继续通过。两轮存在重叠，不相加或覆盖首红。Config Sync另已修复初始审计随外层回滚而丢失：审计先独立提交，最终统计单独写入，瞬态只重试日志；未知结果保留RUNNING及关联摘要，不把已提交目标伪报全失败。该轮5类37/37（PG24、服务/MockMvc13），随后仅收口未知异常详情并补场景，服务类8/8通过（与前轮重叠）；配对前端待确认展示及组件/类型/i18n验证通过；崩溃及永久故障后的精确对账仍开放。证据见[引用专题§2.26–2.32](database-reference-integrity-remediation-2026-10-18.md)及[并发引用失败验收](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-配置引用删除竞态与数据库失败响应)。

消费边界：Job依赖结果快照仍为gate观察证据。独立的Workflow crossDay冻结INLINE_JSON契约已在当前V313新库以单条真实SDK进程E2E复验：旧版归档及强杀/lease重投后仍实际消费v1，业务只写一行，Workflow成功且终态Outbox已PUBLISHED。补齐该E2E启动前控制topic初始化后，Kafka缺topic警告为0；这不代表启动脚本WARN严格审计通过。记录见[A4验收](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-a4-当前新库受控sdk消费进程接管与终态发布)。九类配置完整引用/角色/并发、源节点/边完整并发矩阵及跨请求版本核对、审计崩溃/永久故障恢复与导入幂等、任意外部消费者、外部提交后REPORT丢失、全类型故障恢复、最终SIM/strict审计/部署仍开放；不关闭REF/RT/I/R条目，格式检查继续暂缓。仅本地修改，无提交或PR。

数据库完整性切片（2026-10-19）：Workflow `calendar_code` 与两类 recurring expectation 的父子身份已由 V316 复合外键、Console 父侧预检、Orchestrator 竞态错误映射和显式子定义删除路径共同保护；新库 PostgreSQL 与双向并发定向回归通过。随后 V317 将 `job_task` 的 partition 引用绑定到同租户、同 JobInstance，保留无 partition 任务与父删除级联；真实 PostgreSQL 正负例、目录、调度和归档回归分开串行跑66个用例通过。详见[数据库引用完整性整改方案§2.39–2.40](database-reference-integrity-remediation-2026-10-18.md)及[验证矩阵 V317 条目](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-v317-jobtask-分区引用的租户与实例身份)。V317 仅完成 DB 局部约束，不证明 `job_partition` 到 JobInstance 或 `job_step_instance` 的身份完整性，也未加应用预检；REF-04/05、DBSAFE-3、其余配置/运行引用、锁容量和目标部署仍待验收。V318 随后补齐 `job_partition→job_instance` 的租户/实例/业务日复合 NO ACTION 外键、分区创建应用预检与冷归档字段镜像；真实 PostgreSQL 父子双事务屏障、生产创建 Mapper、Flyway、归档、Console 与 E2E 定向回归通过，详见引用完整性方案§2.41和验证矩阵。该切片仍不关闭 REF-04/DBSAFE-3；V318 单文件 Squawk 为0 issues，全分支迁移安全门禁仍开放。

V319 Step/Task 归属切片（2026-10-19）：原生复合FK覆盖租户、实例、精确可空分区身份，DB生成派生键并同步冷热表；应用构造身份与指定约束冲突映射对齐。真实PG两个隔离级别/两个改键与插入顺序、级联及应用事务回滚已通过；种子重放修复既有 Task4212/Step4312 错绑；Console 专属fixture已补精确租户清理，合并受影响两类及E2E5/5通过；本切片10类106个不同用例最终通过，原失败记录保留，精确命令和分批结果见[引用专题§2.42](database-reference-integrity-remediation-2026-10-18.md)与验证矩阵。生成列重写4条Squawk告警按新库部署边界保留；REF-04/DBSAFE-3及R项不计作关闭。

V320 JobTask→JobInstance 父身份切片（2026-10-19）：针对 partitionless Task 的真实父关系恢复 `(tenant_id,job_instance_id,job_instance_biz_date)` `NO ACTION` 外键；应用单/批写入日期传播、稳定并发冲突映射和冷归档镜像已补。按全新数据库部署契约，最终迁移直接增加原生 NOT NULL 日期列，不保留旧数据回填路径。首红确认原实现可写孤儿；首轮回归暴露 nullable 元数据断言与三个过期 fixture，改为原生 NOT NULL 并修 fixture 后，只补跑失败/受阻的 Console/E2E 类。多条定向命令覆盖的最终受影响类级结果为123个不同用例通过。新库迁移与schema目录断言通过，两份 platform seed 重放两次后8条 Task 均匹配同租户/实例/业务日；两条真实 tenant wipe 也在V320新库动态验证删除与白名单保留，2/2通过。首轮测试断言和fixture问题均修正并保留日志。V319–V321合并回归一条 Maven 命令运行4类31项，0 failures/errors/skips；82个迁移文件 Squawk 0 issues、Testcontainers 清理为0。SIM/strict/WARN、普通RLS角色全链和环境验收仍开放。详见[引用专题§2.43](database-reference-integrity-remediation-2026-10-18.md#243-ref-04-jobtask-到分区-jobinstance-的完整父身份2026-10-19局部实施并定向验证)、[§2.35](database-reference-integrity-remediation-2026-10-18.md#235-ref-08-tenant-wipe-外键顺序动态验收2026-10-19)及[整合验证矩阵V320](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-v320-jobtask-到-jobinstance-父身份与定向补跑)。不关闭REF-04/08、DBSAFE-3或任何R项。

V321 asset_freshness_policy→Job Definition（2026-10-19）：以 `(tenant_id,asset_code)` 外键绑定同租户 `(tenant_id,job_code)`，物理删除 Job 级联清理策略；Console create/update 先通过 `FOR KEY SHARE` 锁定同租户 Job 并以稳定 `INVALID_ARGUMENT` 拒绝缺失/错租户 code。策略可先于 `data_asset` 产物配置，disabled Job 的身份引用仍合法。真实 PostgreSQL + 服务定向回归15/15通过，新库应用至V321；V321 单文件 Squawk 0 issues。全分支迁移安全门禁现对82个文件报告0 issues并通过；详见[引用专题§2.44](database-reference-integrity-remediation-2026-10-18.md#244-ref-05-asset-freshness-policy-到-job-definition-同租户引用2026-10-19局部实施并定向验证)与[整合验证矩阵V321](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-v321-asset-freshness-policy-job-引用与受影响回归)。不关闭REF-05/DBSAFE-3或任何R项。

V322 JobExecutionLog→JobPartition 分区/租户/实例身份（2026-10-19）：复合FK保留日志审计行并仅在分区删除时清空分区ID；生产日志写入以父行锁定同租户实例，真实PG并发和两条tenant wipe回归通过。V323 WorkflowRun→JobInstance 历史弱引用（2026-10-19）：子侧同租户父行 `FOR KEY SHARE` 与父侧引用守护串行化关联、父删除和 ID/租户/业务日改写；关联提交后仍可归档删除父热行并保留历史 ID。TriggerRequest→JobInstance 与 parent WorkflowRun 历史弱引用（同日）：V323 子侧锁定同租户 JobInstance、父身份守护防止 JobInstance/WorkflowRun 被改写，并冻结 TriggerRequest 的父 WorkflowRun/node 快照；保留两类父记录归档删除与历史 ID。V286 已在业务日围栏后 `FOR UPDATE` 重验父 WorkflowRun，未重复添加附件锁。删除未使用的 `batch-trigger.updateRelatedJobInstanceId` mapper/XML。父链接并发/跨租户/身份用例3/3，TriggerRequest mapper 13/13、归档类12/12；含迁移、归档、Task身份、结算围栏、Trigger 与 tenant wipe 的 B1 合并回归通过，V323 Routine/Trigger manifest 已同步，84个迁移安全扫描0 issues。详情见[数据库引用整改§2.45–2.47](database-reference-integrity-remediation-2026-10-18.md)、[验证矩阵V323 TriggerRequest](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-b1-v323-triggerrequest-历史父引用身份)。以上只关闭可在本地证明的关系切片，不关闭ALIGN-DB-REF、RT-6、REF-04/08、DBSAFE-3、B1或任何R验收项；普通RLS全链、运行树/归档清理组合及SIM/strict/WARN/目标环境验收仍开放。

V324 JobPartition→file_record 热源文件引用（2026-10-19）：`job_partition(tenant_id,source_file_id)` 以 `NO ACTION` 复合 FK 绑定同租户 `file_record`；应用只映射精确并发 FK 冲突为 `NOT_FOUND`。完整热/冷行快照首红发现分区归档同时遗漏 `source_file_id`、`template_code`、`target_ref`，现已一并补齐 INSERT/SELECT；真实 `ArchiveColdStorageIntegrationTest` 全类15/15通过，证明三项历史执行身份保留。租户 wipe 与 seed 清理按分区先于文件，SIM 清理脚本先检查热引用并在事务内失败关闭，解除引用后先删 dispatch 回执再删文件。真实 PostgreSQL B1 合并回归64个目标用例通过；SIM 输出清理真实 psql 正反路径后租户清理类3/3通过。schema governance 通过，Flyway 319个文件无 checksum drift（保留 V31/V238/V242 版本间隙 warning），Squawk 85个变更迁移0 issues，DB script safety WARN 30/FAIL 0。该切片不关闭REF-04/DBSAFE-3、R20/R33/R36或任何R行；普通RLS、完整运行树清理、全量SIM/strict/WARN、容量与目标环境仍开放。详见[数据库引用整改§2.48](database-reference-integrity-remediation-2026-10-18.md#248-ref-04-jobpartition-source-file-同租户热引用与清理边界2026-10-19局部实施并定向验证)和[整合验证矩阵V324](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-b1-v324-jobpartition-source-file-同租户引用与-sim-清理保护)。

## 文档收敛后保留的延期与历史复核入口（2026-10-24）

| 事项 | 状态和边界 | 责任范围 / 来源 / 下一步 |
|---|---|---|
| 到达组确定性坏数据隔离终态 | 延续原“暂不做”裁决；仅当生产重复出现坏组并确认需要自动隔离时复审，不因归档而自动实施 | Orchestrator / 运维；[2026-06-21 原裁决](../archive/architecture/arrival-group-malformed-data-quarantine-decision-2026-06-21.md)；先取运行证据并确认状态机语义 |
| 跨平台脚本旧延期项 | 2026-05-24 的保留项只作为复核来源，不宣称当前仍为缺陷 | 脚本维护者；[历史记录](../archive/plans/cross-platform-scripts-2026-05-24.md)；有支持平台变化或故障复现时对照当前脚本核实 |
| 历史吞吐阶段剩余的容量/故障演练 | 历史 P0/P1 完成不等于当前环境容量/生产放行；既有 R/容量方案继续计数，不重复新增任务 | Worker / 验收维护者；[旧阶段记录](../archive/plans/worker-throughput-benchmark-plan-2026-06-07.md)与[容量方案](pre-production-capacity-optimization-plan-2026-06-08.md)；按现行验收范围推进 |

## 文档站体积复核（DOCS-SIZE-2026-10-24）

- 优先级：P2；责任：文档维护者 / 前端文档站。
- 状态：完整 docs:build 未通过；VitePress 编译和渲染完成，但 `nested-callsite-inventory-2026-10-21.md` 与 `workflow-daily-integration-execution-log-2026-10-14.md` 的页面 JS 超过既有 raw/gzip 预算。它们是持续追加的大型现有记录；本轮目录迁移不把该失败写成通过。
- 下一步：先核实当前追加入口和历史锚点，再评估按已有批次/阶段拆分或生成独立明细附件，保持全部历史证据及入口；不通过扩大预算、忽略页面或隐藏内容绕过门禁。
- 证据：本轮目录治理审计记录中的实际构建命令与结果。
