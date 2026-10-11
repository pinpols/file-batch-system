# 租户作业配置与资源边界收敛计划

> 状态：配置包/API、资源写入口和前端体验在特性分支实施，完整验收仍未完成。运行期规则已收敛为：允许编辑；执行/重试使用实例创建时已解析的执行参数；监控阈值实时生效于现存实例。不建设通用配置快照平台，不因定义缓存版本差异拒绝 Launch，不做无证据的 Worker 主链改造。跨租户复制的目标已有渠道值由 MERGE 保留，新目标须在目标环境配置。本计划不建设平台资源备份/恢复产品，也不承接计划外的通用部署、CI 或主链路重构。
> 方案复核基线：`main` @ `daa42986e`（2026-10-10）。[阶段 0 基线报告](../audit/tenant-job-configuration-phase0-baseline-2026-10-10.md)是改造前的历史快照，不代表当前实现状态；当前决议、进度和验收证据以本计划为准。

## 范围决策澄清（2026-10-11）

本次主目标是让有批量运维经验的租户更容易选择、维护和导入作业配置。**不禁止作业运行期间编辑或发布新配置**。执行参数在实例创建时确定，同一实例的排队、恢复和重试沿用该实例的执行语义；以新参数重新处理应创建新实例。监控/SLA 是运维策略，不是执行参数：配置变更可即时影响现存实例的告警判定，避免为了改阈值而冻结配置或重建执行。

执行参数只复用现有 `job_instance.params_snapshot` 保存少量实际影响执行/重试的字段，并由现有 CLAIM 响应提供；不复制模板、Pipeline、通道、路由及任意动态 JSON，不引入新表或通用版本平台。监控策略继续从当前策略表读取，允许立即调整。Launch 不因缓存版本不同而将请求拒绝为终态；新实例使用 Launch 当前已解析的作业定义。模板/Pipeline 等其他运行时配置若有同实例漂移证据，先记入独立待办并提供复现，再单独评估，不顺手扩大本轮实现。此前关于“监控策略也快照”及“版本不一致拒绝 Launch”的调查/验证记录仅作历史，已被本决策取代。

Excel SQL 注释/字符串关键字误判属于旁支质量问题，记录为后续修复项；本轮不改 SQL 校验器，不以此扩大配置包主线。

## 阶段 0 基线状态（2026-10-10）

## 当前实施进度（2026-10-11）

后端与前端实施分支均已确认并保留为 `feature/tenant-job-config-simplification`。本节只记录当前分支内容，不代表已合并或已完成阶段验收。

阶段状态矩阵（2026-10-11；依据本计划现有实施与验证记录汇总，不构成独立代码复审或阶段签收）：

| 阶段 | 范围 | 当前状态 | 未关闭的退出条件 |
|---|---|---|---|
| 0. 基线与决议 | 固定前后端基线，确认权限、配置语义、规模与运维边界 | **部分完成**：历史基线报告已归档；运行期配置语义等决议已写入本计划 | 目标租户规模、路由公平性量化及平台运维相关决议/证据仍未全部闭合 |
| 1. Excel/API 契约 | 场景模板、旧包、MERGE、预览与 Apply 契约 | **实现及定向验证已推进，阶段未签收**：本计划记录了真实 PG/HTTP、冲突、回滚、幂等和上传边界验证 | 完整契约矩阵及计划定义的阶段退出条件尚未整体复核签收；不得用若干定向测试等同全阶段通过 |
| 2. 资源写入口收敛 | 租户可达写入口权限、资源映射与审计 | **实现及定向验证已推进，阶段未签收**：Job/Pipeline/Workflow、文件通道和配置包路径已有授权及真实 PG/HTTP 验证记录 | 目标租户规模下的容量/公平性结论和资源映射预览等退出条件仍需闭合 |
| 3. 业务向导与配置包体验 | 向导、场景模板、角色态、预览差异与错误定位 | **核心体验及相关联测已完成多项，阶段未签收**：计划记录了角色化浏览器流程、模板和配置包 E2E | 完整业务旅程、组合场景矩阵及完整 Sim 等整体验收仍未完成 |
| 4. 迁移、联测与发布 | 只读盘点、映射预览、分租户启用、回滚与发布 | **未完成** | 迁移 dry-run、分租户灰度、部署故障恢复及生产同构验证需在目标环境完成 |

交付切片 A–F 与阶段 0–4 的映射见后文“阶段与交付切片映射”。D 横跨阶段 0/4，不另算阶段。阶段 0 基线报告只承担改造前事实快照；后续阶段的当前状态和证据统一在本计划维护，不为每阶段复制一份内容重复的审计报告。整体计划仍未完成。

续验记录（2026-10-11）：后端针对配置包、资源写入口、SLA/监控、Launch/Trigger 与 Worker 配置边界的 Maven 定向组 **138 项通过，0 失败、0 错误、0 跳过**；使用 Docker Testcontainers PostgreSQL 17.11、Kafka 4.1.2、MinIO 与 Valkey。前端在隔离特性 API `127.0.0.1:18089`、Vite `127.0.0.1:5181` 上运行 `E2E_BASE_URL=http://127.0.0.1:5181 BC_API_BASE=http://127.0.0.1:18089 npm run test:e2e -- e2e/job-definition-crud.spec.ts e2e/job-definition-wizard-detail.spec.ts --project=chromium --workers=1 --retries=0`，**7 项通过**；复跑前修正了过时的步骤标题及告警空态断言，并新增平台/租户管理员资源字段可见性对照，无产品逻辑变更。前端 `e2e/excel-import.spec.ts` 同环境复跑 **13 项通过**；合计 **20 项相关 Chromium E2E**（两个命令独立运行，非一次 20 项套件）。`npx prettier --check e2e/job-definition-wizard-detail.spec.ts` 与 `npm run typecheck` 通过。以上不替代完整 SIM；运行全套 `sim-harness all` 会清理平台与业务运行态，当前共享 Docker 栈/主环境未证明具备独立业务库、Kafka、对象存储和 Valkey 隔离，因此未对共享数据执行破坏性 reset。阶段 F 的 staging dry-run、灰度与恢复演练仍待部署环境。

本轮补充验证（2026-10-11）：角色化真实后端浏览器流程新增导出配置包→回传上传→预览校验用例，使用隔离特性环境和租户管理员登录态，Playwright Chromium **1 项通过**，预览 `invalidRows=0`。首次运行发现导出包内 3 个大写模板引用与导入后小写规范化不一致，导致预览误报未知模板；已修复引用解析/写入的规范化，并增加保留自由文本与非 JSON 旧值的回归。后端 `TenantConfigReferenceResolverTest` + `ConsoleTenantConfigPackageExcelUploadIntegrationTest` 共 **29 项通过，0 失败、0 错误、0 跳过**；前端 `npm run build` 通过（含 vue-tsc、i18n 检查和 Vite 生产构建）。这只关闭回导主路径的一项已复现缺陷；旧包/恶意输入与上传资源限制矩阵、租户/平台角色其余运维流程、完整 Sim、部署恢复及阶段 F 仍未完成，整体计划仍未完成。

真实配置包回导复核补充（2026-10-11）：按本地宿主机运行拓扑连接 Docker PostgreSQL/Valkey/MinIO，并使用独立克隆库 `batch_platform_tenantcfg_e2e_20261011`（Flyway V232）运行特性后端。完整配对前端 Chromium `e2e/excel-import.spec.ts` **13 项通过，0 失败、0 跳过**，覆盖多场景精简模板下载、Apply 冲突后重预览、真实上传→预览→确认应用、租户管理员导出回导预览（`invalidRows=0`）及平台管理员导出。复跑发现 `FIXED_WIDTH` 导入模板映射使用 Worker 契约 `start/length/target`，但通用 Excel 校验器错误要求 `name`；这会连带造成同包 Job 引用被误报为未知模板。已按格式/方向增加窄范围校验：定长导入要求非空映射、非空 `target`、非负整数 `start` 和正整数 `length`；其他格式仍维持原有 `name` 规则。新增合法/非法/缺失映射回归后，`ConfigPackageExcelValidatorTest` **16 项通过，0 失败、0 错误、0 跳过**；后端 `spotless:check` 通过。以上是本地特性环境证据，不代表完整 Sim、生产同构灰度或阶段 F 完成。

本地收尾验证（2026-10-11）：在隔离特性环境对 `e2e/excel-import.spec.ts` 全文件实跑，Chromium **13 项通过、0 跳过**，覆盖场景模板下载、完整上传→预览→Apply、Apply 冲突重预览、租户管理员导出回导，以及平台管理员真实导出。后端真实 Postgres/HTTP：配置包上传/结构/安全边界与导出 writer 共 **35 项通过、0 失败/错误/跳过**；资源写入口与 Apply 幂等/patch 相关集成 **40 项通过、0 失败/错误/跳过**；资源授权、包引用规范化和工作簿结构限制单测 **20 项通过、0 失败/错误/跳过**。工作簿边界覆盖未知 Sheet/未知与重复表头、旧包缺少可选监控 Sheet、公式/外链/宏/损坏文件、超最大行号、ZIP 膨胀比拒绝、导出公式写为文本；POI 结构上限单测覆盖 Sheet/行/列。Spotless 先发现本次六个改动文件格式不符，按仓库规则格式化后 `spotless:check` 通过。上述为本地 Testcontainers/浏览器证据，不等于全 Sim、目标租户规模容量、部署级故障恢复或 staging/生产灰度。

特性分支前端收尾复验（2026-10-11）：将场景模板选择器拆为独立组件、把配置包向导双语文案拆为独立 locale 模块，向导由 2117 行降至 **1865 行**；同时保留字段指南按具体导入/导出格式筛选的选项，并在 E2E 中断言 Excel 导入与定长导出筛选仍可见。使用隔离特性 API `127.0.0.1:18089` 与前端 `127.0.0.1:5181`，`e2e/excel-import.spec.ts` Chromium **13/13 通过、0 跳过**；另一次组合运行中的 Apply 冲突用例仅在浏览器上下文关闭时遇到 trace 临时文件 ENOENT，业务断言由单测重跑通过，随后整份配置包 E2E 重新运行全绿。`npm run verify:local` 全门禁通过：Vitest **820/820**、typecheck、lint、i18n、OpenAPI drift、架构/维护性/UI 复杂度、生产构建、bundle size 与 npm audit。门禁发现并修复了一项真实前后端契约漂移：同步生成作业监控分类筛选字段后 `gen:api:check` 通过。此证据仅覆盖配置包相关前端路径，不等于完整 SIM 或阶段 F 发布验收；完整 Sim reset 会清理运行态及业务数据，当前共享 Kafka/MinIO/Valkey 环境未证明隔离，因此本轮未执行破坏性全套 Sim。

同实例重试策略闭环补验（2026-10-11）：对抗检查发现 CLAIM 已按 `job_instance.params_snapshot.executionPolicy` 提供执行/重试策略，但失败后创建 `retry_schedule` 仍读取当前 `job_definition`，导致实例创建后修改重试策略会改变旧实例行为。新增回归先复现了该错误（快照 `NONE/0`、定义更新为 `FIXED/3` 时错误排程），随后改为优先读取实例快照，仅无快照的历史实例回退到作业定义。`DefaultRetryGovernanceServiceTest` **20/20**、真实 PostgreSQL `JobRetryFlowIntegrationTest` **1/1** 通过，均为 **0 失败、0 错误、0 跳过**；集成测试确认实例固定 `FIXED/1` 后将作业定义改为 `NONE/0`，同一实例仍按快照排程并派发重试。该改动未改变新的 Launch 状态流或 Worker 派发协议。完整 Sim 和阶段 F 仍未完成。

上传并发容量探测（2026-10-11）：在隔离 Console API `127.0.0.1:18089`（Java 进程最大堆 4 GiB）上，以真实租户管理员会话通过 HTTP 上传，不执行 preview/apply。构造的合规 XLSX 为 4,803,761 字节，`file_template_config` 有 1,500 行 × 41 列随机文本；双并发均返回 HTTP 200 / `SUCCESS` 且解析行数为 1,500，耗时分别 1.748s、2.041s。请求期间 `ps` 采样 RSS 峰值 815.1 MiB（采样起点 166.4 MiB，增量 648.7 MiB）；请求后 JMX heap snapshot 显示 heap committed 526 MiB、used 510 MiB，尚未强制 GC，不能等同存活对象量。此负载刻意接近列上限且远大于现有 16–24 KiB fixture，适合作为资源压力探针，不代表普通配置包。结果说明仅以“两个上传成功”不足以验收并发容量；目前没有经业务/部署负责人确认的目标并发、目标文件大小及容器内存预算，因此不擅自改全局 multipart 上限或引入解析架构。待给定目标后，按生产形态复测峰值堆/容器 RSS、延迟、拒绝率及临时文件清理，再决定是否需要配置包专属限流/大小边界或解析优化。上传仅在 Valkey 创建短期 session，未 Apply 或修改业务配置。

分支状态快照（2026-10-11，已 fetch）仅作为历史记录：当时后端特性分支落后 `origin/main` 9 个提交、前端落后 2 个提交，未 rebase。范围复核时曾把计划外的 CI/CodeQL/Sonar、通用 Compose/Helm、观测栈等变更移入可恢复 stash；D 的执行参数快照及监控策略快照草案曾分别暂存，前者按最小边界恢复，后者经范围决议不纳入当前实现。当前工作区包含此前改动与本轮 D 改动，分支仍保留；创建 PR 前须刷新远端并评估同步方式。

范围审查补充（2026-10-11）：配置模板、Apply 权限/预览并发、租户可达资源路由写入口和凭据动态 JSON 掩码属于本计划。执行策略快照仅保存影响同一执行/重试的少量字段；监控策略动态生效。曾实现的监控策略实例快照、Launch 版本不一致拒绝及为快照扩展的 SQL 已收窄移除。SLA 告警候选事务/错误恢复属于独立可靠性切片，当前定向单测和真实 PG IT 54 项通过，但不应描述为配置 UX 的必要机制。仍不扩展成通用部署治理、CI 性能优化或平台资源备份/恢复产品。

旁支待办（不阻塞本计划）：Console API 日志进入 Loki 的投递/标签链路此前报告为缺失，需单独复核采集配置与实际查询证据；不在本轮租户配置体验与实例配置稳定性改造中顺手调整观测栈。

范围护栏补充（以本节“范围决策澄清”为准）：配置允许在执行期间编辑；本轮不要求逐类配置补齐快照/版本引用。仅对有证据表明同一实例会因运行中编辑而发生语义漂移的具体配置，评估可复用的现有实例参数/引用并做最小修正；否则记录风险与复现条件，留待独立决策。

实现调查记录（2026-10-11）：`file_template_config` 与 `pipeline_definition` 的更新/导入路径存在原位更新，Worker 某些阶段按业务键读取定义；单独记录 `id/version` 未必能冻结这些对象。该调查用于界定潜在语义漂移，不构成本轮为所有模板/Pipeline 增加不可变版本、复制 JSON 或改造 Worker 的要求。只有在本轮修改直接触及的配置上复现同一执行/重试语义漂移，才做该对象的最小修复；其他对象按下方旁支台账处理。

实施范围原则（2026-10-11）：本轮验收以“降低租户配置负担、维护入口可理解、实例执行语义稳定且不回归”为主目标。Excel 校验只负责必要的文件安全/资源边界、工作簿 schema 与表头识别、必填/枚举/格式校验、租户内引用解析及能阻断错误配置的业务约束；不递归扫描或推断任意插件/SQL/动态 JSON 的内部语义，不为低频配置新增复杂校验框架，也不把所有后端配置字段暴露给租户。已有的安全限制保留，但不以“覆盖更多字段”为理由扩大校验范围。发现与目标无直接关系的问题时，先记入对应待办/审计文档，不阻塞主线；只有会导致配置误写、实例语义漂移、租户越权或主链路回归的缺陷才纳入本轮修复。涉及 Worker/Launch/Mapper/数据库结构的改动须先有明确失效场景、最小兼容方案和真实边界测试；缺少这些证据时不实施推测性重构。

后续项（2026-10-11）：配置包 SQL 预览校验使用关键字正则，可能把注释/字符串中的 `INSERT/UPDATE` 误判为写语句；本轮暂不改动，后续单独修复并补充单测。该问题不阻塞配置场景模板、默认值和向导主目标。

## 本轮主线与旁支台账（2026-10-11）

以下是目前从阶段 0 报告、实施记录和当前差异中识别出的已知事项。状态以本表为入口，正文较早的阶段 0 调查/历史验证保留背景，不得覆盖本表的范围决议。

### 主线验收项

| 项目 | 当前状态 | 收尾要求 |
|---|---|---|
| 场景化模板、服务端默认值、字段说明和前端向导 | 核心租户/平台角色浏览器路径已完成本地联测 | 若继续扩展角色或配置路径，先按权限矩阵补验；staging 灰度仍单独验收 |
| 配置包旧包/新包兼容、MERGE 与 Apply 冲突 | 本地矩阵已覆盖可选 Sheet 保留、未知 Sheet/列拒绝、重复列拒绝、预览过期/并发变更拒绝、整包事务回滚、同键 Apply 拒绝重放；真实 HTTP/PG 已通过 | 运行完整 Sim 前确认 fixtures 覆盖范围；保留向后兼容窗口与停用日期的发布决议 |
| 上传文件安全与资源限制 | 已覆盖公式/外链/宏/损坏文件、ZIP 膨胀比、行/列/Sheet 上限、通用 multipart cap 和导出公式文本化；真实 HTTP/PG 与 writer 测试通过 | 并发/超时/临时文件专项尚无独立压力证据；先验证当前内存解析路径在目标并发和上传上限下的容量，不引入无证据的通用上传框架 |
| 资源所有权与写入口 | 租户路由字段的 API/Excel 守卫、Job/Pipeline/Workflow/文件通道写入口均有真实 HTTP/PG 和授权用例；资源切片本轮 40 项通过 | 只读容量/公平性结论需由目标租户规模、并发和服务等级目标驱动；不在本轮改队列架构 |
| 运行中配置变更语义 | 允许编辑；执行/重试字段从现有实例参数快照读取，监控策略实时读取 | 验证执行字段更新不改写已有实例语义、新实例读取当前定义；监控策略更新会即时影响现存实例告警。模板/Pipeline 未经复现不加快照 |
| 模板/Pipeline/通道等运行时漂移 | 调查发现部分路径按业务键读取当前配置，但尚无证据证明本轮覆盖的实例因此改变结果 | 记录具体实例、变更时间和前后行为；复现后按单一对象建立独立缺陷，只修必要的读取/引用边界，不建设通用配置快照系统 |

### 后续旁支（不阻塞主线）

| 事项 | 已知证据/限制 | 后续处理边界 |
|---|---|---|
| SQL 预览关键字误判 | `default_query_sql` 仍使用正则检查，注释/字符串中的写关键字可能误报 | 单独替换为语法级校验并补正反例；不扩成本轮配置包范围 |
| Excel 校验范围膨胀 | 关键字误判已知；任意插件、SQL 或动态 JSON 内部语义不属于工作簿 schema 校验职责 | 当前只验证文件安全边界、schema/字段格式、租户内引用和可阻断错误配置的业务约束；不要为低频字段引入通用语义扫描框架 |
| Console API 日志进入 Loki | 既有本地观测记录曾未找到 Console 标签，未在本轮重新核验 | 独立复核采集器、标签和查询结果，不改配置包或应用主链 |
| 全仓动态 JSON/自由文本敏感信息分类 | 已对部分配置和运行态响应加掩码；全仓任意诊断载荷/日志正文审计未完成 | 单独安全审计；若发现当前可达的明文凭据泄露，升级为安全缺陷立即修复，不按普通旁支延期 |
| SecretVersion 到 Dispatch 运行时接入 | 已确认：实例创建时解析并固定 SecretVersion 引用；queued/running 实例及其重试继续使用该版本。当前尚未证明 Dispatch 已按此语义接入；撤销/紧急阻断策略仍未决 | 独立设计和验收；验证实例创建时的版本解析、后续轮换不改变既有实例、重试版本稳定，以及显式撤销/紧急阻断行为。不在本轮引入 Secret 平台或变更派发契约 |
| 平台资源快照/恢复 | 不属于租户业务配置包；没有在本轮实现 | 独立平台运维项目，不与租户导入/恢复授权混用 |
| 路由容量、公平性与大规模租户量化 | 阶段 0 只读分析尚未给出目标规模下的容量证据 | 在扩大租户规模或切换物理路由模型前评估；本轮不改队列/调度模型 |
| 完整 DST/misfire/readiness/retry 组合及多副本恢复演练 | 现有定向 SLA/PG 用例不等价于该组合及部署级证据 | 仅在本轮改动影响对应时间/状态语义时补最小回归；完整演练另行排期 |
| 生产灰度、迁移 dry-run/回滚、生产同构验收 | 本地分支与 Testcontainers 证据不能替代 staging/生产演练 | 作为发布前运维门槛；不伪称为代码本地可完成事项 |

旁支若被主线改动触发、成为租户越权/数据丢失/凭据泄露/执行结果错误的可复现路径，立即转入主线缺陷处理；否则只维护在本表或对应安全/运维待办，不顺手扩大实现。

版本字段基础收口（2026-10-11）：作业定义维护、启停、批量启停、物理路由清理及 Trigger 启停写入口递增 `job_definition.version`，用于版本识别和审计。定向真实 PG IT `ConsoleJobDefinitionMutationIntegrationTest`（12 项）与 `TriggerServiceIntegrationTest`（3 项）此前通过。当前 Launch 不将缓存版本不一致直接终结为 REJECTED；是否需要更强的新实例配置新鲜度保证，应由缓存失效契约的独立证据决定，不在本轮新增拒绝状态。

下方“阶段 0 基线盘点”内容是改造前的只读快照，用于说明当时观察到的问题；实施进度以本节前面的“已进入实现的范围”和“本切片尚未完成验收”为准。

已进入实现的范围：

- 前端主入口选择 IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC / WORKFLOW / SCHEDULE 后下载精简空白模板；`ALL` 展开为全部租户业务场景，不含物理资源队列。调度场景提供业务日历、批次窗口和仅含业务完成 SLA 的策略列。旧 `/template` 全量模板及导入旧包解析保留兼容；新模板不再附带跨场景 Worker/文件束大示例页。
- 场景选择不再静默回退到 ALL；未选择时不允许下载，未知场景明确拒绝。
- 文件场景模板新增“方向 × 格式”选项：导入支持分隔符文本、定长、Excel、XML、JSON；导出只提供已有策略实现的分隔符文本、定长、Excel、JSON，不提供仅作输入兜底的 BINARY 或尚无导出策略的 XML。格式化模板按所选格式投影列，并把文件模板类型/格式下拉限制到已选方向和格式；Excel 下拉校验按表头定位，避免裁列后校验落到相邻字段。旧 IMPORT/EXPORT 和 ALL 模板的宽格式行为保持兼容。工作簿字段投影单测与真实 HTTP 模板接口/工作簿响应检查已通过；浏览器联测现已覆盖多场景选择、请求参数与下载文件名。
- 旧工作簿可省略非必需数据 Sheet，必要结构列仍由对应 Sheet 明确校验；不认识的 Sheet 仍拒绝，避免静默丢数据。
- 作业、通道、文件模板、Pipeline、Workflow 更新时，空白选填字段保留现值；Pipeline step 与日历 holiday 集合改为按稳定键 upsert，不因本次文件缺项而删除旧项。删除仍通过对应配置维护入口显式执行。日历/窗口字段的真实 PG 稀疏 MERGE 断言、监控策略保留平台阈值的真实 PG 断言均已完成；其余尚未明确纳入本业务包的实体不能据此推断已覆盖。
- 场景示例不再引用未随模板提供的资源队列、日历或窗口；平台资源 Sheet 不进入场景模板。
- 配置包 apply 现允许 `ROLE_ADMIN` 与 `ROLE_TENANT_ADMIN`；服务端按可信 `ConsolePrincipal` 二次授权。租户管理员的包若包含资源队列行、作业物理队列/Worker 分组，或平台运行阈值则拒绝整包；平台管理员仍可处理旧全量包。业务 SLA 字段仍可由租户管理员维护。前端只对平台/租户管理员显示 Apply。
- SIM 阶段 29/30 已于 2026-10-11 本地实跑。阶段 29 在隔离 Console 实例上完成 9 组真实模板下载、Sheet/列投影、上传与预览，全部 `invalidRows=0`、`issues=[]` 且返回 `previewToken`；未 Apply。此前旧 Console 的一次 `HTTP 500 SYSTEM_ERROR` 在本次新打包实例上未复现，原进程没有可回溯日志，故不能声称已定位旧 500 根因。首轮复跑曾被共享 Redis 的昂贵操作限流返回 `429`，最终验证仅在临时 Console 进程把该接口限额提高到 100/min，未改共享 Redis 状态或生产配置。阶段 30 使用隔离 Kafka、隔离数据库和本地 Trigger/Orchestrator/Atomic Worker：实例 A 在运行时修改定义后仍以创建时的 `/bin/sleep`、`EXPONENTIAL`、300 秒快照成功完成；实例 B 使用更新后的 `/bin/echo updated-config`、`NONE`、60 秒快照并成功完成；结束后已确认恢复作业原始配置。验证时发现并修正脚本竞态：Trigger 接受请求是异步的，需等待实例快照落库后再断言，不能在立即查询为空时提前进入 `finally` 恢复配置。隔离数据库 schema 为 V233 且无待执行迁移；其克隆 Flyway checksum 与当前源码不一致，因此本次临时 Orchestrator 关闭了 Flyway checksum 启动校验及对应自检项，未执行 `repair`、未改迁移历史。该限制不影响本次已应用 schema 上的运行时快照验证，但本轮不作为 Flyway 迁移一致性证据。监控策略即时影响在途实例的告警判定仍由既有真实 PG IT 覆盖，不在这两条 SIM 中重复实现。
- Job Definition、Pipeline 和 Workflow REST 写入均在服务层守卫平台物理路由：租户不能新设或改写队列/Worker 分组；复制作业会清除来源物理路由；Workflow 全图更新在租户省略字段时保留原有平台分组。旧 Excel 配置包 Apply 也检查 Job、Pipeline、Workflow 节点和资源队列中的平台字段并拒绝租户整包写入。对应真实 HTTP/PG IT 与权限单测通过。批量初始化、跨租户复制、Config Sync、Job Bundle 和配置发布的所有 `batchInit` 调用方已核对：其入口均受 `ROLE_ADMIN` 控制；资源快照独立交付与恢复仍未验收。
- 本地真实 PostgreSQL IT 覆盖单场景、组合场景和旧 `ALL` 模板的生成/上传/预览；另验证重复 MERGE 时空白作业选填字段、文件模板描述和 Pipeline 描述保留，工作簿省略的现有 Pipeline steps 不被删除。Apply HTTP IT 验证租户管理员可应用不含平台资源的业务场景包，且旧全量包含资源队列时整包被拒绝；授权逻辑另有单测。调度场景真实 HTTP + PostgreSQL IT 验证省略平台监控列后保留平台运行阈值，同时更新业务 SLA；旧工作簿省略监控策略 Sheet 的真实 HTTP + PostgreSQL IT 也验证六项策略值均保留。日历/窗口的稀疏 UPSERT 另由真实 PG + HTTP IT 覆盖；渠道 JSON 的 Excel 与 API 序列化边界有专项测试。MERGE 的真实 PG 证据不能外推到其他实体或所有权限/安全边界。
- 渠道导出不再序列化 `file_channel_config.config_json` 与 `target_endpoint`；场景指南将凭据字段改为可选，空白同租户 MERGE 保留数据库现值，新建渠道仍须显式提供连接参数。真实 PG + HTTP 集成测试验证导出 Excel 单元格为空、租户管理员回导后 JSONB 凭据和 endpoint 均不变；JSON Config Sync/Job Bundle DTO 也将两字段标记为 write-only。该修复不证明 `default_params`、`step_params`、`node_params` 等动态 JSON 不含敏感信息，也不代表 SecretVersion 引用已接入 Dispatch 运行时。
- 跨租户配置复制现保留渠道业务字段但清除来源 `targetEndpoint` / `configJson`，避免把来源环境地址或凭据写入目标租户；真实复制服务单测覆盖目标 init 请求。`default_params`、`step_params`、`node_params` 和实例 `params_snapshot` 已有递归脱敏/移除路径；所有诊断类动态载荷的分类审计仍未完成，且配置复制目前不负责将 `secret_version` 引用解析到 Dispatch 运行时。
- 本轮继续检查了批量初始化、跨租户复制、Config Sync、Job Bundle 与 Job Definition REST 写入口：初始化/复制/同步/Job Bundle 端点均为 `ROLE_ADMIN`；租户 Job REST 的队列与 Worker 分组由服务层守卫阻止创建/变更，克隆不继承物理路由。发现 JSON Config Sync/Job Bundle 导出仍会序列化 `FileChannelSpec.configJson`，现以 Jackson write-only 限制响应输出，保留受控导入能力，并补充序列化契约测试；其他动态 JSON 的敏感信息审计仍未完成。
- 新增 `/scenario-template`，前端原先独立的“完整空白模板”和“示例模板”下载改为一个场景选择 + 空白模板主动作；旧 `/template` 与 `/sample-template` API 保留兼容，未执行删除或重命名。新增 `SCHEDULE` 场景，SLA 模板不暴露平台运行耗时/启动宽限阈值。
- （历史状态，已被 2026-10-11 决议取代）执行策略实例快照和监控/SLA 实例快照曾暂存为独立切片。D 仍在本计划内，但当前只保留执行/重试参数快照；监控策略快照草案已移除，不作为本次交付要求。
- Excel 上传会话绑定服务端认证的上传操作人和权限集合；预览、行内修正、预览工作簿下载及 Apply 共用访问校验。会话通过现有 `BatchObjectCryptoService` 加密后存入共享 Valkey/Redis，支持 Console 多副本间读取；租户一致但账号不同或权限集合变化仍拒绝复用。真实 Valkey 集成用两个独立 Store 实例验证共享密文会话。授权检查先于预览 token 轮换，避免未经授权请求使合法预览失效。
- Excel 配置包 Apply 的占用、行内编辑和预览版本更新由 Valkey Lua 原子状态转换保护：并发 Apply 只有一个实例取得占用，Apply 期间禁止编辑；事务提交后按占用 owner 删除会话，失败/回滚只释放自己的占用。Apply 占用有 1 小时 TTL；TTL 到期会删除整份上传会话，同一 token 无法再次 claim，因此不会仅因锁到期而让同一会话出现第二个 Apply。超长事务仍需受服务端运行上限约束。另由 V232 数据库级租户配置修订栅栏检测不同上传会话及其他数据库写入口造成的预览后变更。
- 每次预览/行内修正返回新的随机确认 token；行内编辑必须带当前 token 并通过跨副本 CAS 替换会话，旧页面/并发旧请求被拒绝。Apply DTO 同样必须提交最新 token，API 与前端均按 OpenAPI 生成类型校验；真实 Valkey IT 覆盖陈旧 token、会话替换、两个独立 Store 实例间的 Apply claim 竞争及 owner-fenced 完成/释放。V232 在事务中锁定租户配置修订并与预览修订比较，陈旧预览以 `STATE_CONFLICT` 拒绝，比较发生在任何配置包写入之前。
- 全量 Excel 导出按当前认证角色投影字段：租户管理员拿到的 `resource_queue` 仅含表头，作业中的 `queue_code` / `worker_group`、Pipeline/Workflow 节点的物理 Worker 分组及平台运行阈值为空；业务 SLA 字段保留。平台管理员/审计员仍可导出平台字段。租户回导时空白字段按 MERGE 语义保留已有平台配置。真实 PostgreSQL + HTTP 集成测试覆盖两类角色的导出差异和回导不改路由；这不等于平台资源独立快照/恢复已实现。
- 文件通道 list/detail/create/update 的 `config_json` 响应现对嵌套 Map/数组中的敏感键递归掩码；非法历史 JSON 不原样回显。更新请求回传 `****` 会按 JSON 路径保留数据库原值，显式删除字段才清除凭据，新建/更新的 `config_json` 必须是 JSON 对象。真实 PostgreSQL + HTTP IT 验证凭据不出现在响应、掩码回传不覆盖存量值及非法 JSON 不落库。此项只覆盖文件通道 CRUD 的动态 JSON 响应，不代表其他动态 JSON 已完成审计，也不代表凭据已迁移到 SecretVersion 或由 Dispatch 运行时解析。

（历史审查记录，范围已于 2026-10-11 收窄）当时列出的跨实体 MERGE、动态 JSON 敏感字段、租户可达写入口和路由公平性缺口仍按旁支台账跟踪。旧记录提出的监控/SLA 快照、缓存版本拒绝和模板/Pipeline/渠道/Secret/路由实例绑定不再是本轮要求。D 的当前验收仅检查执行/重试参数是否在同一实例内稳定，及监控策略更新是否即时影响现存实例；SecretVersion Dispatch 和平台资源备份/恢复另行立项。

动态 JSON 敏感信息审计已延伸到运行态查询：作业/步骤结果、实例详情和执行日志分别递归掩码 `result_summary`、`params_snapshot`、`extra_json` 中的凭据类字段，AI 工具复用同一响应 DTO，不会另行取得明文。查询单测覆盖实例、步骤结果和日志扩展字段，HTTP + PostgreSQL IT 覆盖实例参数快照与结果摘要响应掩码。此处只按敏感键名做凭据掩码，不等同于对日志正文/诊断文本中的任意明文做脱敏；全仓动态 JSON 与非 JSON 日志内容的分类审计仍未完成。

本轮继续扩展只读响应脱敏：告警详情、审批载荷、文件流水线步骤输入/输出摘要、配置变更审计摘要、通知投递日志载荷及结构化错误参数仅在查询/本地化渲染时递归掩码凭据类 JSON 键；不改持久化值、worker 执行载荷或实际通知发送内容。对应单测验证敏感键掩码和普通业务字段保留。配置发布 payload、ResultVersion 业务 payload、worker claim/report 契约及自由文本日志/外部响应正文仍需按数据所有权逐项分类，不能统一盲目脱敏。

### 本轮验证记录（2026-10-11）

- 当前分支验收复跑（2026-10-11）：前端 `npm run preflight:changed:all` 通过，`npm run test:unit -- --run` **819 项通过**，`npm run build` 成功；Playwright 配置向导/状态/冲突恢复和完整导入向导浏览器组分别 **10 项、2 项通过**，合计 12 项。为修复隔离 origin 下的登录态丢失，新增共享 `seedStorageForConfiguredOrigin` helper 并由两组 spec 复用；`upload-full-chain` 的 upload/preview/apply/audit 与上传失败均使用 route stub，不是真实后端联测。后端配置包/作业配置组运行 `./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest,ConsoleTenantConfigPackageExcelPatchIntegrationTest,ConsoleJobDefinitionMutationIntegrationTest,ConsolePipelineDefinitionMutationIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,TenantConfigPackageExcelApplyAuthorizationTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,TenantConfigPackageRowProjectionsMonitoringPolicyTest,ConfigPackageExcelValidatorTest,ConfigPackageWorkbookGuidanceTest,ConfigPackageExcelSchemaConsistencyTest,ConfigPackageExcelWorkbookWriterExportPreviewTest,ConfigPackageEnumDropdownConsistencyTest,NotificationMapResponseJacksonTest,DefaultConsoleNotificationApplicationServiceTest,SecretMaskingTest,ConsoleFileProjectionMapperTest,ConsoleFileChannelMutationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**153 项通过，0 失败、0 错误、0 跳过**；其中配置包上传真实 HTTP/PG 集成类单独复跑为 **24 项通过，0 失败、0 错误、0 跳过**。未知表头与重复表头测试均先复现旧行为会返回 HTTP 200、静默忽略/覆盖，再验证现实现返回 HTTP 400 并指出错误列。真实 IT 使用 Testcontainers PostgreSQL 17.11、Kafka、MinIO、Valkey。另有 D 定向 54 项通过。上述是本地特性分支证据；没有运行全 Sim、角色化真实浏览器联测或 staging 灰度。

- SLA 告警持久化故障恢复（初版验证，已被当前实现及下方复验取代）：曾将事务放在整轮 `scanViolations()`，真实 PostgreSQL IT 注入 `alert_event` 写入失败并验证回滚。由于整批事务可能让单个坏候选阻塞后续候选，事务边界及测试已改为每候选独立；旧结果不作为当前实现的验证依据。

- D 切片早期定向验证（历史，2026-10-11，已被当前语义取代）：曾报告 56 项通过，但其中包含现已删除的监控策略快照及 Launch 版本拒绝行为，**不得作为当前代码验证证据**。
- D 切片当前定向验证（2026-10-11）：`./mvnw -pl batch-orchestrator -am -Dtest=DefaultLaunchServiceTest,DefaultTaskAssignmentServiceTest,JobSlaSchedulerTest,JobMonitoringMapperIntegrationTest,JobSlaSchedulerRecoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**54 项通过，0 失败、0 错误、0 跳过**。真实 PostgreSQL Testcontainers 用例验证监控策略更新会影响现存实例后续 SLA 候选判定、SLA 告警候选并发与故障恢复；单测覆盖 Launch/CLAIM 的执行参数边界。该组不覆盖跨进程多副本故障演练、完整 DST/misfire/readiness/retry 组合或 staging。

- D 快照及查询早期定向验证（历史）：`DefaultLaunchServiceTest`、`DefaultTaskAssignmentServiceTest`、`JobSlaSchedulerTest`、`JobMonitoringMapperIntegrationTest` 共 **53 项通过**；测试语义已变化，不作为当前验证结果。
- Apply 事务失败回滚真实 HTTP + PostgreSQL 验证：`./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**17 项通过，0 失败、0 错误、0 跳过**。测试在后续 Job Definition 写入处注入数据库异常，确认同一事务内先前配置写入回滚；异常日志为测试刻意注入。该结果不覆盖同幂等键 HTTP 重放。曾尝试在当前集成 profile 下增加重放测试，发现 profile 开启 `batch.security.bypass-mode`，幂等拦截器按设计绕过；该无效测试已移除，不能将 404 结果当成幂等守卫证据。应在非 bypass 的认证测试环境中补充真正经过拦截器的 API 重放验证。
- 配置包 Apply 的幂等重复键拦截器定向验证：`./mvnw -pl batch-console-api -am -Dtest=ConsoleIdempotencyInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`，**8 项通过，0 失败、0 错误、0 跳过**。新增用例使用真实 `ConsoleTenantConfigPackageExcelController.apply` HandlerMethod，覆盖控制器类级 `@Idempotent` 识别及 DONE 键返回 409；真实 JWT/CSRF HTTP 路径另有下一条记录。首次 Apply 成功后同键重试并核对 PG 只写一次仍是 B 的未完成验收项。
- Excel 配置包恶意工作簿拒绝：真实 HTTP 上传验证公式单元格、外部工作簿关系、`.xlsm` 扩展名，以及伪装为 `.xlsx` 但包含 VBA 项目关系的文件均返回 400；旧 `.xls` 解析兼容未改。解析器异常对客户端只返回通用错误，原始 POI 异常细节不再回显，服务端记录清理后的诊断摘要。运行 `./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**22 项通过，0 失败、0 错误、0 跳过**。目前尚未覆盖 ZIP bomb/解压尺寸上限、最大行列/文本长度、解析并发与超时、临时文件清理、限流及导出公式注入；不能据此宣称 Excel 恶意输入验收完成。
- 配置包 Apply 类级幂等声明真实认证路由验证：`./mvnw -pl batch-console-api -am -Dtest=ConsoleCsrfCookieIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**2 项通过，0 失败、0 错误、0 跳过**。用真实 JWT Cookie、CSRF 双提交与 Valkey 幂等存储预置 DONE 键，确认 `POST /apply/{uploadToken}` 返回 409 且不进入 apply；首次成功后的重放及 PG 对账见紧随其后的验证记录。
- 首次成功 Apply 后同键重放：`./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageAuthenticatedIdempotencyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**1 项通过，0 失败、0 错误、0 跳过**。使用非 bypass 的真实 JWT/CSRF HTTP 链路，经上传、预览、Apply 写入唯一文件通道后重放同一路径与同一幂等键，第二次返回 409；PostgreSQL 对应业务行数前后均为 1。测试运行 PostgreSQL、Kafka、MinIO、Valkey Testcontainers。该证据只关闭 B 的成功重放子项，不代表完整旧包/安全矩阵、Apply 并发和全计划验收完成。

- Workflow 配置包 MERGE 真实 HTTP + PostgreSQL 验证：`./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，该次 **13 项通过，0 失败、0 错误、0 跳过**。新增用例先导入完整、拓扑有效的工作流，再用仅含 START/END 与直接边的稀疏工作簿 MERGE；验证空白可选 description 保留、未提交的中间节点与旧边不删除，旧边与新边最终均存在。该记录形成时尚未覆盖跨会话配置版本冲突，后续已由 V232 增加检测（见下一条）；本用例仍不覆盖完整恢复包、前端浏览器或 Sim 验收。
- 旧工作簿省略监控策略 Sheet 的真实 HTTP + PostgreSQL 回归：同一命令现为 **14 项通过，0 失败、0 错误、0 跳过**。用例在独立租户中先写入平台运行阈值和业务 SLA，再应用完全没有 `job_monitoring_policy` Sheet 的旧式工作簿，断言六项策略值均保留。它证明当前解析器对该 Sheet 的确可选；不代表旧包全部 schema 版本或任意租户配置包已兼容。
- 租户配置包数据库级并发修订栅栏：新增 V232，追踪配置包涉及的租户配置表；Apply 在同一事务内锁定并比较预览时的修订，预览后被其他写入口修改则返回 `STATE_CONFLICT`，且不执行部分写入。真实 PostgreSQL + HTTP 用例验证冲突拒绝和正常含 pipeline step 的 Apply；首次复跑暴露子表 `pipeline_step_definition` 无 `tenant_id` 列，已改为经 `pipeline_definition` 解析租户并纳入主表修订追踪。定向 upload 集成类最终 **15 项通过，0 失败、0 错误、0 跳过**。这是单租户全局修订，可能使无关配置变更也要求重新预览；该保守冲突行为已知，后续应结合实际误冲突数据再决定是否按配置域拆分。尚未覆盖生产多副本部署、并发压力和不同数据库角色权限矩阵。
- Apply 整包事务回滚：新增真实 PostgreSQL + HTTP 故障注入测试，在配置包已写入前置文件模板/通道后，于作业定义写入时由租户限定的临时触发器抛错；断言请求失败且作业、文件模板、通道记录均无部分残留，finally 清理测试触发器与函数。定向 upload 集成类复跑 **17 项通过，0 失败、0 错误、0 跳过**。此证据覆盖单次事务失败回滚，不替代跨实体 MERGE 全覆盖、同幂等键成功重放或生产级并发压力验收。
- 前端冲突恢复联测：Apply 收到 `409 STATE_CONFLICT` 时自动重新拉取预览、退回预览步骤并要求用户复核；刷新失败时提示重新手动拉取，其他错误保持原错误展示。`npm run preflight:changed:all` 通过；`npm run test:unit -- src/api/tenantPackageExcel.test.ts` **8 项通过**；Playwright Chromium 冲突恢复用例 **1 项通过**（Vite 运行于 `127.0.0.1:5180`，upload/preview/apply 由浏览器路由桩模拟，不代表真实浏览器到后端 HTTP 联测）。首次 E2E 失败是路由 glob 未匹配 `tenantId` 查询参数，修正匹配后复跑通过。
- 后续响应边界回归：作业实例快照递归脱敏测试 `./mvnw -pl batch-console-api -am -Dtest=ConsoleJobExecutionLogQueryTest -Dsurefire.failIfNoSpecifiedTests=false test`，4 项通过，0 失败/错误/跳过。该命令只覆盖目标查询服务单测，完整配置回归已于下方复跑并纳入本用例。
- 本轮配置回归最新复验：执行完整配置包/作业配置测试组（下方长命令），`batch-console-api` **145 项**、`batch-common` 7 项，总计 **152 项通过，0 失败、0 错误、0 跳过**。覆盖场景工作簿上传/预览/apply、Apply 权限、日历/窗口/监控策略 MERGE 投影、Job/Pipeline/Workflow 写路径、实例/步骤快照、结果摘要、执行日志动态 JSON 敏感键脱敏、文件/通知渠道敏感信息投影与凭据保留、配置模板与 schema。真实 IT 使用 Docker Testcontainers PostgreSQL 17.11、Kafka、MinIO、Valkey；新增 HTTP + PostgreSQL 实例详情用例已通过。仍不覆盖 SecretVersion Worker 运行时解析、完整恢复包、Sim 全链路或配对前端浏览器联测。
- Pipeline/Workflow 动态参数凭据保护：新增真实 PostgreSQL + HTTP 集成用例，分别验证 create、detail GET、update 的 `step_params` / `node_params` 不回显凭据，带 `****` 的更新保留原凭据且正常字段可修改。测试同时复现并修复 Workflow 更新时空 `node_params` 触发的 NPE（空参数节点不进入待保留参数映射）。执行 `./mvnw -pl batch-console-api -am -Dtest=SecretMaskingTest,ConsolePipelineDefinitionMutationIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，`SecretMaskingTest` 7 项、Pipeline IT 6 项、Workflow IT 8 项通过，0 失败、0 错误、0 跳过。真实 IT 使用 PostgreSQL 17.11、Kafka、MinIO、Valkey Testcontainers；不代表 SecretVersion 运行时解析、所有动态 JSON 形态审计或完整实例快照已完成。
- 配对前端切片：`npm run typecheck`、场景模板 API 单测（6 项）、`npm run build`（含 i18n 检查）及 `npm run gen:api:check` 均通过。未运行浏览器 E2E：本机 5173 指向其他工作区，不能作为本特性分支的 UI 验收；5174 当前没有服务。前端浏览器验证需用本分支启动前端并连到匹配的后端实例。
- 配置包与作业配置回归组：`./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest,ConsoleTenantConfigPackageExcelPatchIntegrationTest,ConsoleJobDefinitionMutationIntegrationTest,ConsolePipelineDefinitionMutationIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,TenantConfigPackageExcelApplyAuthorizationTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,TenantConfigPackageRowProjectionsMonitoringPolicyTest,ConfigPackageExcelValidatorTest,ConfigPackageWorkbookGuidanceTest,ConfigPackageExcelSchemaConsistencyTest,ConfigPackageExcelWorkbookWriterExportPreviewTest,ConfigPackageEnumDropdownConsistencyTest,NotificationMapResponseJacksonTest,DefaultConsoleNotificationApplicationServiceTest,SecretMaskingTest,ConsoleFileProjectionMapperTest,ConsoleFileChannelMutationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**135 项通过，0 失败、0 错误、0 跳过**。覆盖配置包上传/行内修正/Apply 权限、作业/流水线/工作流 REST 写路径、监控策略与模板 schema；真实 IT 使用 Docker Testcontainers 的 PostgreSQL 17.11、Kafka、MinIO、Valkey。该组仍不覆盖 SecretVersion 的 Worker 运行时解析、完整业务/平台资源快照恢复、全仓动态 JSON 敏感字段审计或 Sim 全链路。
- 文件通道与通知渠道敏感值保护扩展：`./mvnw -pl batch-common,batch-console-api -am -Dtest=SecretMaskingTest,ConsoleFileProjectionMapperTest,ConsoleFileChannelMutationIntegrationTest,NotificationMapResponseJacksonTest,DefaultConsoleNotificationApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，`batch-common` 5 项、`batch-console-api` 35 项通过，0 失败、0 错误、0 跳过。覆盖动态 JSON 递归脱敏、文件 endpoint URI 凭据遮罩、回传遮罩值时保留存量凭据，并由 PostgreSQL + HTTP IT 验证文件渠道持久化行为；不代表 Job/Pipeline/Workflow 动态参数或实例快照已完成敏感值审计。
- 文件通道动态 JSON 脱敏切片：`./mvnw -pl batch-common,batch-console-api -am -Dtest=SecretMaskingTest,ConsoleFileProjectionMapperTest,ConsoleFileChannelMutationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**batch-common 2 项、batch-console-api 11 项通过；合计 13 项通过，0 失败、0 错误、0 跳过**。覆盖递归敏感键脱敏、更新保留掩码凭据、无效 JSON 拒绝且不落库与真实 PostgreSQL HTTP 写入/响应；不覆盖全仓动态 JSON 审计和 SecretVersion 运行时解析。测试启动项目既有 Testcontainers PostgreSQL、Kafka、MinIO、Valkey。
- 前端 `npm run gen:api` 后，`npm run preflight:changed:all` **通过**，API drift 检查确认生成类型同步。浏览器定向验证 `E2E_SKIP_GLOBAL_SETUP=1 E2E_BASE_URL=http://localhost:5174 npm run test:e2e -- e2e/excel-import.spec.ts --grep "选择多个作业场景" --project=chromium --workers=1 --retries=0` **1 项通过**；该用例验证配置包场景选择模板请求/下载，不是文件通道凭据编辑的浏览器端到端验收。

### 历史验证记录（2026-10-10）

- 后端在 Docker Desktop / Testcontainers 环境运行定向测试：`./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest,ConsoleJobDefinitionMutationIntegrationTest,ConsolePipelineDefinitionMutationIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,DefaultConsoleTenantConfigCopyServiceTest,TenantConfigPackageExcelMonitoringPolicyApplyTest -Dsurefire.failIfNoSpecifiedTests=false test`，结果 **42 项通过，0 失败、0 错误、0 跳过**。其中配置包上传测试迁移 PostgreSQL 17.11，并启动 Kafka、MinIO、Valkey 容器；覆盖租户管理员禁止提交平台资源字段等路径。该结果只证明这些定向用例，不代表所有配置对象 MERGE、完整实例快照或生产网络部署已验收。
- 新增租户/平台角色导出投影后运行 `./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest,TenantConfigPackageRowProjectionsMonitoringPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`，**13 项通过，0 失败、0 错误、0 跳过**。上传测试使用 Docker Testcontainers 的 PostgreSQL 17.11、Kafka、MinIO、Valkey，并通过 HTTP 验证租户角色不获得平台路由/队列字段、平台角色保留完整导出。
- 新增会话操作者/权限绑定后，单独复跑 `./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**11 项通过，0 失败、0 错误、0 跳过**；包含真实 PostgreSQL 17.11、Kafka、MinIO、Valkey 容器及 HTTP 角色校验。
- 跨副本预览版本 CAS 与 Apply owner fencing 最终定向复验：`./mvnw -pl batch-console-api -am -Dtest=InMemoryTenantConfigExcelStoreTest,ConsoleTenantConfigPackageExcelPatchIntegrationTest,ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**17 项通过，0 失败、0 错误、0 跳过**。Valkey IT 使用两个独立 Store 实例；内存替身另验证占用 owner 不匹配时不能释放/完成他人占用。最终测试过程发现并修正了测试替身对 `ConcurrentHashMap.contains` 的误用，最终复跑全绿。
- 场景模板字段契约复验：`./mvnw -pl batch-console-api -am -Dtest=ConfigPackageWorkbookGuidanceTest -Dsurefire.failIfNoSpecifiedTests=false test`，**16 项通过，0 失败、0 错误、0 跳过**。新增独立断言精确锁定租户 IMPORT/EXPORT 模板的作业列与文件模板列，并确保平台队列/Worker 路由、重试/分片等非用户场景字段不回流；当前 IMPORT 文件模板为 19 列，EXPORT 为 22 列，作业定义为 12 列。此结果不代表完整 12-sheet 恢复包被裁剪，完整导出仍保留兼容字段。
- 格式场景模板真实 HTTP 验证：`./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，**12 项通过，0 失败、0 错误、0 跳过**。新增用例逐一请求 5 种导入和 4 种导出格式模板，解析 HTTP 返回的 XLSX，核对格式相关列裁剪及方向/格式下拉选项；其余配置包上传集成用例也在同一测试类通过。测试使用项目既有 Testcontainers 环境（PostgreSQL 17.11、Kafka 4.1.2、MinIO、Valkey）。
- 前端真实浏览器验证：`E2E_SKIP_GLOBAL_SETUP=1 E2E_BASE_URL=http://localhost:5174 npm run test:e2e -- e2e/excel-import.spec.ts --grep "选择多个作业场景" --project=chromium --workers=1 --retries=0`，**1 项通过**；浏览器确认选择 `IMPORT_EXCEL` 与 `PROCESS` 后发出对应场景请求，并下载名称匹配的精简模板。测试使用特性分支 Vite 服务 5174，避免连接到 5173 上另一个工作树的服务。
- 执行策略快照在移出前曾通过上述 34 项定向测试；仅作为独立 stash 实现的历史验证，不计入当前特性分支验收，恢复后须重新运行。
- 前端 `npm run preflight:changed:all` 通过；包含编码、注释、ESLint、TypeScript、i18n、OpenAPI drift、架构、可维护性、测试 fixture 来源和 changelog 检查。`npx vitest run src/api/tenantPackageExcel.test.ts` 此前通过（4 项），本轮未重跑该 Vitest 命令。Playwright Chromium 已可用，本轮场景模板浏览器定向用例通过。
- 验证未覆盖完整 Sim、前后端联测或生产同构环境；Sonar 未运行（按本轮范围约定）。

- 阶段 0 只读盘点时的主干基线：后端 `file-batch-system/main` `f22bd50fe946a0643ddaf270149fdb8f917ca59d`；前端 `batch-console/main` `fb3f603d7c969a9ce5ed095df42a71ec33265d9a`。这是改造前历史快照，不代表当前主干或特性分支 HEAD；当前分支关系见上方快照。
- 已确认配置包实际为 12 个数据 Sheet、170 列；当前场景示例只改变样例行，未按场景减少 Sheet 或列；41 列文件模板的表头在当前解析器中必须全部出现。
- 改造前基线确认：租户管理员可上传和预览配置包，但 apply 端点为平台管理员专属；前端未隐藏 apply 操作。作业定义 CRUD 则允许平台管理员与租户管理员，且请求仍包含队列/Worker 路由字段。上述权限状态已由本轮实施变更，不能作为当前行为描述。
- 继续追到运行时：模板/Pipeline/通道与监控策略存在按当前配置读取的执行路径；作业参数快照并未冻结完整执行依赖。配置包通用指南把留空描述为“不覆盖”，但作业/模板/通道更新实际会用空值覆盖，Pipeline steps 和日历 holidays 另有集合替换语义。`config_json` 还允许包含凭据 JSON，导出未发现脱敏，必须收紧 Secret 引用契约。
- 以上是阶段 0 的源码静态证据；完整字段所有权矩阵、路由容量分析、Secret 轮换语义、配置生效决议、角色确认和运行验证仍未全部完成。后续未决事项不得被当前代码切片的实现状态替代。

## 结论

当前租户配置包把“作业业务定义”“文件技术细节”“资源分配”和部分运行治理放在同一个工作簿中。**41 列和 23 列分别是 Excel 配置包的 `file_template_config`、`job_definition` sheet 列数，不是数据库物理表的列数。**配置人员需要理解大量与当前场景无关的字段。`resource_queue` 还暴露并发、QPS、Worker 分组、公平权重等平台资源参数，租户配置包可以导入并写库，不符合 SaaS 的控制面边界。

目标不是简单删列或拆表，而是：

1. 租户只描述作业业务语义和必要的外部业务连接。
2. 平台拥有配额、队列容量、并发、QPS、Worker 路由及资源隔离。
3. 首次配置只展示当前作业类型需要填写的字段；完整导出仍保留无损迁移能力。
4. 默认值只补充确定、安全且不改变业务结果的值；不能推断的业务语义必须显式填写。

## 当前基线与工作区差异

- 方案复核时的 `main` 基线为 `daa42986e`；本轮实施盘点更新到 `f22bd50fe946a0643ddaf270149fdb8f917ca59d`。当前代码包含必需的 `job_monitoring_policy` sheet、12-sheet 完整配置包及对应迁移；旧的 9+2 设计文档仍描述 11-sheet 基线，并明确不兼容缺少该 sheet 的旧工作簿。方案目标是评估并调整该兼容契约，不把当前实现误写成尚未合入。后续兼容决策以实施时最新 `main` 的代码/迁移为准。见 [9+2 配置包设计](../design/tenant-config-package-excel-9plus2-design.md)。
- `resource_queue` 不只出现在 Excel：还被批量初始化/复制、租户就绪检查及独立 CRUD 等路径引用。当前独立队列 CRUD 和配置包 apply、批量初始化/复制入口均为 `ADMIN_ONLY`；Excel 上传/预览允许租户管理员，但 apply 不允许。故当前证据支持的是“资源定义与租户业务配置包耦合、平台运维流程边界需收敛”，不应误写成“租户管理员现在可直接持久化队列容量”。实施仍须覆盖全部写入口和现存作业引用。
- 配置包中的 `job_definition` 当前基础 schema 为 23 列；作业定义的 API、初始化请求和持久化模型也包含队列/Worker 相关字段。将资源配置移出 Excel 后，还要决定这些 API 字段是仅平台可写、租户只能选受限逻辑档位，还是由服务端派生，不能留下可绕过 Excel 的写路径。
- 当前配置包模板与完整导出复用 sheet 规格；`file_template_config` 的字段虽由统一 schema 声明，但不同格式、导入/导出方向和高级能力只适用其中一部分。目标应是按场景投影字段，而不是删掉底层模型字段。
- 必须分别判断两类问题：Excel 41/23 列是配置易用性与契约投影问题；数据库宽表是否拆分是范式、所有权、写入原子性和查询成本问题。先精简 Excel 不要求立即拆表；单凭物理列数也不能作为拆表依据。
- 本方案中的“完整快照不含敏感明文”“快速创建已完成”等状态，必须由当前代码、配对前端和测试逐项证明后才能标记完成。本方案不将它们视为既成事实。

实施前以目标分支 merge-base 和对应前后端 commit 为准；每份审计记录需注明基线 SHA。工作区中的未提交代码只作为待核查材料，不能作为已发布契约或安全保证。

## 目标配置边界

| 配置对象 | 所有者 | 租户可编辑范围 |
|---|---|---|
| 作业业务定义 | 租户 | 作业编码/名称/类型、业务参数、依赖关系；在使用调度时填写调度语义 |
| 文件模板 | 租户业务配置 | 对应场景的格式、映射、校验及导入/导出定义；高级字段按场景与权限显示 |
| 外部文件/派发通道 | 租户与平台共同治理 | 租户可维护获批的目标与业务属性；凭据只引用 Secret，不允许明文；平台限制协议、网络出口和凭据范围 |
| 业务日历、批次窗口 | 业务规则由租户业务负责人确认；平台提供受治理的日历能力与共享日历目录 | 可引用平台批准的共享日历；租户专属节假日/账期规则由有权限的业务配置入口维护。不能把平台默认日历等同于所有租户的业务事实 |
| 物理资源队列、租户配额、Worker 分组/标签 | 平台运维 | 租户无权创建/修改容量、并发、QPS、公平权重、Worker 路由和隔离参数 |
| 逻辑服务档位/路由选择 | 平台定义、租户按授权选择（若确有产品需求） | 只能选择有限、可解释的服务档位；不能提交任意 `queue_code` 或物理资源属性。首期也可完全由平台按租户与作业类型派生，不新增租户可见档位 |
| 执行观测阈值（运行时长、启动宽限） | 平台运维策略 | 按作业类型/服务等级提供平台默认值；租户不在快速创建时填写。例外由平台授权修改并受上下限约束 |
| 业务完成时限（截止钟点/日期偏移、依赖作业完成窗口） | 业务负责人声明；平台负责校验和告警执行 | 仅在确有账期/窗口 SLA 时显式填写；不设猜测性默认。必须定义时区、触发日、依赖起算点和空值语义；不得隐式改变调度、取消或重试 |
| 告警级别、通知路由和投递策略 | 平台运维 | 通知拓扑由平台控制；级别由平台默认或受控策略决定，不作为基础作业配置必填项 |

`queue_code` 可继续作为内部持久化/路由字段，但不应成为任意的租户资源分配入口。**不预设物理队列可跨租户共享**：现有表和查询按 `tenant_id` 隔离，阶段 0 必须追踪 Orchestrator/Worker 的 queue claim、配额、公平性和租户过滤语义，再决定路由模型。推荐先由平台维护“租户 + 作业类型（必要时加授权档位）→ 已 provision 的租户逻辑队列”映射；内部队列是否共享、如何落实隔离，需以执行链路证据和并发测试决定。租户请求不能直接设置 `worker_group`、`resource_tag` 或容量参数。路由变更只影响之后创建/发布的作业实例；运行中实例保留创建时解析出的路由快照，除非另有经过验证的显式迁移流程。

映射模型还要做高租户数容量评估：不能未经验证地按“每租户 × 每作业类型/档位”创建大量物理队列。阶段 0 需量化队列行数、claim 扫描/索引、锁竞争、公平性和配置变更扇出；逻辑租户路由与物理队列可以是不同层次，但共享物理资源不得削弱租户隔离或配额。

## 租户最小输入与默认策略

### 通用作业

最小必填为 `job_code`、`job_name`、`job_type`，以及该类型必需的业务执行定义。`tenant_id` 从认证上下文注入，不由表格决定。

| 字段/语义 | 目标行为 | 安全边界 |
|---|---|---|
| 触发方式 | 默认 `MANUAL` | 只有选择 CRON/FIXED_RATE 时要求填写表达式及相关时区策略 |
| Worker 分组/资源队列 | 平台自动路由 | 不给租户默认一个可自行修改的物理队列 |
| 执行模式 | 默认 `FULL` | `INCREMENTAL`/CDC 必须显式声明水位或变更语义 |
| 分片策略 | 默认 `NONE` | 只有作业具备可验证的分区键/分片契约时才开启 |
| 重试 | 租户不配置重试策略；平台只对明确声明且经过验证的幂等操作应用有界重试，其他操作不自动重试 | 未知幂等性按不可自动重试处理；不得因“少填”扩大重复写入风险 |
| 超时/运行治理 | 使用平台按类型设置的安全默认和上限 | 租户不能通过超大值绕开平台上限 |
| 启用状态 | 导入后先预览；新作业默认草稿/禁用，显式发布后才可调度 | 避免导入即触发定时作业 |
| `param_schema`、`default_params` | 无业务参数时用空结构；有业务参数时按场景表单生成/校验 | 不猜测表名、字段映射、冲突键、账期或金额规则 |

### 按 Worker 场景显示字段

- **IMPORT**：模板与来源/目标、字段映射、冲突/坏行策略按实际导入方式必填；编码、CSV 常见分隔符、BOM 等可用经确认的格式默认。
- **EXPORT**：目标格式和查询定义属于业务输入；命名规则、换行符等可用默认。SQL 仍属于高级配置，必须经过权限、只读限制和预览校验。
- **PROCESS**：目标数据集和计算/映射定义不可猜测；不展示文件通道、文件编码等无关字段。
- **DISPATCH**：目标通道必需；网络地址和认证引用按租户可用的连接器展示，凭据不得进入 Excel 明文单元格。
- **ATOMIC**：仅展示允许的原子操作参数；HTTP/SQL/shell 能力必须受平台安全策略和授权约束，不能由默认值扩大权限。
- **WORKFLOW / PIPELINE**：仅在租户选择需要编排时出现；普通单作业不要求创建空 pipeline/workflow sheet。

### 文件模板 41 列收敛原则

保留后端完整领域模型和无损导出，不为降低表面列数而丢弃真实能力。新建模板按“方向 × 文件格式 × 高级能力”生成字段子集：

1. 公共身份字段只出现一次。
2. IMPORT 与 EXPORT 各自只显示适用字段；导入 SQL/目标表参数和导出查询 SQL 不互相占位。
3. CSV/定长/Excel/XML/JSON 只显示当前格式相关字段。
4. 高级性能参数、加密/脱敏、checksum/压缩和审批策略放在高级区，默认不展开；安全策略不因模板隐藏而失去服务端强制校验。
5. 条件字段由同一字段元数据定义驱动模板、前端表单、说明和后端条件校验，避免另维护几套列清单。

目标是快速模板不再显示 41 列；完整导出仍可包含全部列以便备份/迁移。不得用“列隐藏”作为权限控制。

### 数据库宽表拆分判据

本方案不把 `file_template_config` 或 `job_definition` 的物理拆表作为第一步。当前 `file_template_config` 聚合模板身份/版本、格式参数、映射/校验定义、导出查询、性能参数和部分安全策略；`job_definition` 聚合调度、路由、重试、分片、执行器和默认参数。它们看起来宽，但仍可能是各自配置聚合的权威行。未经读写和生命周期证据先拆表，会增加 JOIN、事务一致性、迁移、归档/快照和前后端契约成本。

阶段 0 对每组字段输出列名、NULL/默认分布、读写调用方、变更频率、权限所有者、查询过滤/索引用途、是否需与主对象原子更新、实例快照需求。满足以下一项或多项且有实测/调用证据时，才提出独立子表方案：

- 字段组有独立权限或独立发布/审批/版本生命周期，不能随父对象一起修改。
- 字段被大量场景排除，长期形成高空值/类型专属约束；且子表边界能由稳定类型判别，不靠前端隐藏来维护。
- 大型 JSON/文本或高频更新字段造成明确的读放大、写放大、锁冲突、TOAST/索引成本或归档膨胀。
- 同一组配置需要被多个模板/作业引用并独立复用，当前复制会造成实际漂移。

即使满足判据，也应先比较“维持聚合表 + API/Excel 场景投影”与“拆分版本化子表”的成本。映射/校验 JSON 若只随模板整体版本化并整体读取，暂留 JSON 可能更简单；若需要单项检索、复用、增量审计或独立授权，再考虑规范化为子表。拆分必须保留事务边界、乐观锁、租户复合约束、软删除/归档语义和实例引用版本，并以迁移前后数据对账与查询计划验证。

## 配置入口与工作簿形态

1. **快速创建向导**：首选单作业入口。选一个作业场景后展示少量必需项和安全默认，逐步配置；不要求下载后理解所有 sheet。创建下一作业不代表必须再下载/上传 Excel。
2. **多场景批量模板**：用户一次选择本批要维护的多个作业场景，生成一个组合工作簿、一次上传、一次全量预览和一次 apply；不要求 IMPORT、EXPORT 等场景各自重复上传。组合包只包含这些场景所需的 sheet/字段，公共作业定义可在同一 sheet 多行表达，文件模板按格式/方向分区，仅出现被选中的技术字段；未选场景的字段、资源、平台阈值、未使用的 Pipeline/Workflow sheet 不出现。若跨场景引用已有租户配置，允许通过稳定业务键引用并在预览中解析。
3. **完整配置快照**：仅用于租户自身业务配置的备份/迁移，包含所有已配置业务定义，不包含平台资源控制面秘密/策略；资源队列由平台单独导出和导入。
4. **平台资源管理入口**：队列、配额、服务等级映射和 Worker 池只由平台管理员操作，具有独立权限、审计和变更预览。

### 新增作业监控配置的归属

`job_monitoring_policy` 把不同所有权的字段放在同一行；当前工作簿解析器已将该 Sheet 作为可选项，但 `SCHEDULE` 场景模板仍提供它，且平台运行阈值与业务 SLA 仍共用同一存储行。因而不能整体归类为“租户作业配置”，也不能让所有新建/导入用户被迫填写：

- `soft_runtime_seconds`、`start_grace_seconds` 及其告警级别属于平台运行观测策略。应由平台按作业类型/服务档位设默认和上限；不进入租户快速模板，只有经授权的运维入口可修改。
- `completion_deadline_local_time`、`completion_deadline_day_offset`、`dependency_completion_window_seconds` 表达业务 SLA。只有业务确有结算/窗口要求时，租户业务负责人显式声明；必须明确作业时区、业务日/计划触发日、依赖起算点、DST gap/overlap、misfire/catch-up、readiness defer 和空值/关闭语义。它们只驱动监控/告警，不得隐式改变调度、取消、重试或作业终态。
- 建议统一时间锚点：调度作业以原始计划触发时间和实例 `bizDate` 计算业务截止，不以扫描时间、实际启动、readiness 解除或重试时间平移；手动作业用显式 `bizDate`，缺省时才按已声明时区的创建日期。依赖窗口从本次实例所依赖的上游有效成功结果时间计算；多依赖时按全部必需上游都满足后的最晚有效时间起算。重试不重置实例级 SLA，attempt 只用于诊断和告警去重。DST 不存在/重复本地时刻的处理策略必须固定并测试，不能交由 JVM 默认值隐式决定。
- 运行时长告警与启动宽限使用不同时间戳：启动宽限从计划触发（手动作业从创建）到首次实际执行开始；运行时长从首次实际执行开始计算，并明确重试等待是否计入。实例级完成截止保持不变。持久化字段必须能区分 planned、ready、first-start、attempt-start 和 terminal 时间，不能用单个 `started_at` 同时表示调度延迟和执行耗时。
- 通知路由、投递策略和平台告警策略由平台控制；租户不能通过 Excel 调整 on-call 拓扑或放宽平台告警边界。
- 不要求为拆分上述所有权立即拆数据库表。可先在 API/校验/权限层分开，再依据模型所有权和演进需要决定物理存储。
- 监控不能只改 Excel：阶段 0 必须盘点 Job CRUD 的 create/update/copy/read、配置包导入导出、实例创建、监控扫描、告警去重/claim/outbox 和前端编辑入口。当前决策是监控策略动态读取，策略更新立即影响现存实例的后续告警判定；不得为此复制策略快照或增加 rebaseline 操作。若业务未来要求保留历史阈值判定结果，应先提出审计/监管需求，再单独评估不可变审计记录，不改变现有运行状态。
- 多副本监控扫描必须证明同一 instance/attempt 的告警只生成一次；claim 后投递失败不能丢告警，需可重试且消费幂等。重复扫描、进程在 claim 与 outbox 写入之间崩溃、通知端超时/恢复均纳入测试。
- 执行参数/重试策略在实例创建时解析并保存到现有 `params_snapshot`，同一实例恢复和重试沿用该值；需要新参数时创建新实例。不得据此推导出模板、映射、Pipeline、通道、路由或任意 JSON 都必须复制进实例。对这些对象仅在复现同一实例语义漂移后建立独立缺陷单，限定最小修正；Secret 轮换/撤销仍按独立 SecretVersion 待办处理。

配置包规则建议：快速创建和场景模板完全不含平台运行阈值；租户业务 SLA 是可选扩展，未提供表示“不修改已有 SLA”，显式清除必须有专门操作并留下审计。版本化完整快照可以表达租户 SLA 的完整状态，但不能把平台运行阈值打包给租户。旧包缺少新增策略字段时应保留已有策略；不能让新增必需 sheet 静默造成旧工作簿全量拒绝。

旧包缺少 `job_monitoring_policy` Sheet 的解析兼容已由真实 HTTP + PostgreSQL 回归验证：重导不清除既有监控策略。监控策略按当前表动态读取；执行参数快照与监控策略快照是两种不同语义，不得混为一谈。实例执行参数在创建时解析的一致性、DST/misfire/readiness/retry 组合及多副本部署级恢复仍按阶段验收，不能把“Sheet 可省略”或“已有参数快照字段”误当作这些问题已完成。

模板与快照必须区分“导入意图”，但首期不增加用户可见的 manifest sheet，也不要求租户维护版本号。由下载模板的 API 元数据/导入请求明确 `package_kind`（场景模板、业务合并包、受控恢复包）；服务端按列头签名识别受支持的 schema 版本，并在预览结果中回显识别结果。没有来源元数据的旧文件只有在 schema 唯一匹配时才接受；有歧义则拒绝并给迁移提示，不能猜版本。租户身份始终来自认证上下文，文件和请求参数均不能覆盖。未知字段/工作表不得静默丢弃。若未来确需文件内版本标记，应采用机器生成且非用户编辑的隐藏元数据，并先证明 Excel 客户端往返不丢失；这不是首期前置条件。

列头指纹仅能识别结构不同的 schema，不能区分列头相同但含义已变的版本。因此 schema 演进规则必须禁止在保持同一指纹时改变字段语义/默认含义；若确需此类变更，必须引入可验证的机器版本标记或新的显式 API 版本，并保留旧版本解析器及迁移测试，不能把“指纹识别”当成通用版本控制。

配置写入默认采用 **MERGE**：未出现的行/字段表示“不修改”，不表示删除或清空；显式清空只能通过预览可见、带权限和审计的操作表达。完整业务快照下载并不自动意味着“导入即替换”。租户业务配置的精确恢复由具备该租户破坏性配置权限的管理员执行，可按现有审批策略要求二次审批。平台资源备份/恢复不属于当前方案实施范围；未来如单独立项，须由平台运维权限控制且不能复用租户业务恢复授权。请求参数本身不能授予恢复权限。预览新增/更新/删除及外部引用映射，在单一事务边界内应用；失败回滚到导入前状态。重复提交需有幂等请求键，且请求键绑定租户、操作者/授权范围、包摘要和导入意图。恢复与普通业务导入不可共用一个模糊的“覆盖”按钮。

预览需生成短时有效、一次性消费的服务端 token，绑定 tenant、actor/permission scope、文件摘要、package kind、识别的 schema 版本、预览差异摘要和被比较的配置版本；apply 时重新校验权限和配置版本，过期、已消费、文件变化或并发修改均拒绝并要求重新预览。导入引用使用 tenant-scoped 稳定业务键解析；数据库主键不能由租户包指定。若当前旧包携带内部 ID，阶段 0 明确其兼容映射和跨环境行为，不能静默信任或丢弃。

Excel 导入/导出作为文件边界还需明确安全和资源限额：最大压缩/解压体积、sheet/行/列/单元格长度、解析时限和并发数；拒绝宏、外部链接及不支持的工作簿结构，防止 ZIP bomb/超大共享字符串耗尽资源。公式单元格不得被当作可信配置执行或静默求值；导出用户可控文本时防止公式注入。临时上传文件需限定访问、设置保留期限并清理；错误回显不得泄露 Secret、SQL、内部路径或其他租户数据。预览和 apply 都按租户授权并受速率/并发限制。

### 多场景批量导入语义

- 用户在导出模板时勾选一个或多个场景；即使不经该入口直接上传，后端也根据实际 sheet/header 识别包内容，不信任客户端声明的场景范围。无需为每个场景单独上传；单场景 Excel 仍可作为多场景包的子集导入。
- 一个工作簿代表一次配置变更集合。预览按 sheet/行列出新增、更新、无变化、阻断错误和警告，并解析跨 sheet 及对现存租户配置的引用。引用解析不依赖 sheet 顺序；循环依赖、重复业务键、缺失引用和跨租户引用均给出可定位错误。
- 有任何阻断错误时整个包不可 apply，不允许“有效行先成功、错误行部分失败”；修正后重新预览。只有非阻断警告时，用户明确确认后可 apply。apply 在事务边界内整包提交，异常整包回滚；大于同步事务安全上限的包明确拒绝并说明上限，不悄悄退化成部分成功。
- 默认 MERGE 使未提交的场景/行保持不变，不删除既有作业。多场景包中的跨场景依赖先做全包解析；增量导入可以引用已有配置，但不自动复制或改变被引用对象。
- 预览结果提供按场景和 sheet 的汇总，让用户能一次看懂“本次会改哪些作业/模板”，并可下载逐行错误报告；成功后生成一个可审计的导入操作记录和包摘要，而不是每个场景各留一条难以关联的记录。

## 迁移与兼容策略

- 已存在的 `resource_queue` 数据不删除、不自动改容量；上线前由平台管理员确认每个租户/作业的现有逻辑队列并建立平台映射。
- 先以只读盘点输出 tenant/job/queue/worker_group 关系、队列参数、quota/fairness 约束和运行中引用；按租户生成映射预览、冲突和未映射报告。映射确认前不切换任何默认路由。
- 新租户包不得创建或修改资源容量。非空旧 `resource_queue` sheet 不静默忽略：租户导入返回可操作错误并附迁移预览；平台管理员使用单独受权的迁移/平台资源入口确认映射。空 sheet 可在兼容期接受。
- 新“租户业务快照”不包含物理队列、配额、Worker 池和平台路由策略。平台资源备份/恢复属于独立后续项目，不是当前方案产物或验收门槛。当前业务包跨环境导入仍须做引用预检，并要求环境专属 endpoint/secret reference 映射；导出须经扫描/测试确认不会泄露凭据明文，未验证前不能宣称安全。
- `job_definition.queue_code` / `worker_group` 旧字段先保留用于兼容读取；写路径逐个收敛。对于新作业，只有路由解析成功且授权策略确定后才能发布；无映射必须 fail closed，不能落入隐式通用池。对于存量作业，迁移预览和确认后才更新未来实例的解析来源。
- 路由映射变更不在本轮改造实例路由生命周期；按现有持久化路由行为处理，不能借本方案新增通用 route snapshot。若发现更新路由会改写已领取/运行实例的实际执行目标，必须先提供可复现证据，再作为独立主链缺陷评估。
- 业务日历、窗口的现有租户定义保留。平台可提供经审批的共享目录；账期、节假日和跨日语义不能由平台默认值代替，迁移时显式映射或要求业务负责人确认。
- 当前基线的监控策略 sheet/API 曾把平台运行告警阈值与业务完成时限放在同一策略行；当前分离规则是平台阈值由平台控制、业务 SLA 可按需声明，监控判定动态读取最新策略。配置包形态、页面权限、字段授权、敏感字段、MERGE 语义和旧包兼容仍按主线验收；不要求实例策略快照。
- 不通过数据库迁移删除业务字段。首期改变的是用户/API 写权限和表单/模板投影；底层字段在兼容窗口后再评估是否有条件退役。
- 配置包 apply 必须先完成预检，再在单一事务中写入；任一引用、权限、唯一键或约束失败时整包回滚。预览与 apply 之间检测配置版本/摘要变化，避免过期预览覆盖并发更新；发生并发修改应返回冲突，要求重新预览，不能静默 last-write-wins。
- 文件解压/解析、外部 Secret/连接器引用检查和预览计算在数据库写事务外完成，并有资源上限；apply 事务只执行已验证的本地数据库写入，不在持锁事务内调用对象存储、Secret Manager 或远端 API。先根据最大支持包规模验证 apply 时间/锁范围；超出预算的包拒绝或拆成有明确原子性保证的作业，不能偷偷降为逐行部分成功。

## 分阶段实施（共 5 阶段）

### 阶段 0：契约与权限盘点

- 记录前后端基线 SHA；核查配置包 schema、必填 header、默认值、模板/导出/apply、REST CRUD、复制/批量初始化、发布审批、Console 角色权限和前端模板/向导。
- 对每个字段登记：所有者、适用场景、是否可默认、默认事实源、是否敏感、是否可由租户覆盖。
- 按 Worker × 文件格式输出当前列数、必填数、条件适用数、隐藏高级数和实际需要人工决定数；先有量化基线，再确定每个快速场景模板的验收上限，不能凭主观宣称“字段已精简”。
- 沿 `job_definition.queue_code` 追踪至 claim、capacity、fairness 和 worker selection；核实队列是否租户局部、是否存在共享语义及运行实例的现有路由行为。产出只读存量映射报告，并测算目标租户规模下映射/队列的基数与数据库成本；不把新增路由快照作为本阶段交付。
- 追踪 job definition、file template、channel、monitoring policy 从编辑到 instance/task/worker 的实际读取时点；明确执行参数的实例级稳定语义、监控策略动态生效语义，以及 Secret 轮换/撤销的独立待办。对模板/Pipeline/通道等未证实漂移的问题记录复现条件，不先建通用版本/快照机制。
- 对监控时间字段逐一映射到已持久化事件时间，确认 planned/ready/first-start/attempt-start/terminal 的来源、时区和重试语义；用业务样例核对账期日、跨日、依赖晚到和 misfire 下的告警时间。
- 决议平台路由模型、共享日历与租户业务日历边界、文件渠道 secret reference、监控配置中“平台运行阈值”和“租户业务 SLA 声明”的字段归属，以及执行参数实例快照和 Secret 轮换策略。监控策略动态读取；此阶段不做 schema 删除或批量路由改写。

### 阶段 1：安全默认与场景字段模型

- 先以现有后端 Excel schema/API 校验定义为权威，建立可测试的字段清单：类型、场景条件、所有者、租户可见/可写、默认值来源、敏感级别、平台上限和验证规则。仅在至少模板、导入校验、向导三处确实重复时抽取共享元数据；避免把执行规则、OpenAPI 文档和 UI 文案硬耦合到一个超大通用元数据框架。
- 对可安全默认字段做服务端默认，不能只依赖 Excel 样例值；默认值同步向导、Excel 和 OpenAPI。
- 建立可组合的按 Worker/文件格式场景模板：既支持单场景，也支持用户选多个场景后生成一个组合包；不相关 sheet/header 缺省。监控业务 SLA 扩展仅在业务需要时出现，平台运行阈值永不随租户包写入。完整业务配置包仍无损保留其业务配置；这里指配置包导出能力，不是把配置复制到运行实例。资源资产单独导出，不承诺租户配置包可独立重建平台资源。

### 阶段 2：平台资源治理

- 租户侧配置包不再写 `resource_queue`、租户配额、`worker_group`、`resource_tag` 和物理容量。
- 先复用现有资源队列管理入口并收紧为平台运维权限；只有产品确需租户选择服务等级时再增加有限档位。平台管理映射、资源变更和审计必须有 preview/影响范围；暂不因方案引入新模块或新的抽象资源服务。
- 采用“先读兼容、再收写入口、后切路由”的迁移：旧字段可读；tenant Excel/CRUD/复制/批量初始化等租户写入先被拒绝或投影；映射通过后，对新建实例启用平台解析。回滚优先依赖向后兼容读路径和可重放映射，不默认新增永久开关或双写；若分批发布确需临时开关，必须指定负责人、关闭条件/日期、监控指标和删除版本。
- 幂等确认键应绑定租户、作业和路由版本；冲突/缺映射时阻止发布并审计，不自动挑选其他队列。
- 保证 tenant guard/RBAC 在 API 和服务层强制；隐藏前端字段不能代替后端授权。

### 阶段 3：业务端向导和完整业务配置包分离

- 首次创建单作业走场景向导；需要批量维护多个作业/场景时支持一份组合 Excel，避免按 Worker 类型重复导入；高级字段按需展开。
- 快速模板、业务 MERGE 包、受控 RESTORE 包的下载/上传入口和权限明确区分；预览展示包识别结果、字段默认来源、引用映射、增删改影响、策略生效范围和不可逆风险。Apply 前若底层配置已变化，强制重新预览。
- 组合包一次性显示跨 sheet 引用关系、场景统计和整包错误；有阻断错误不允许部分提交，修复后重新预览；普通 MERGE 缺失的场景/行不删除已有配置。
- 统一中文字段说明、错误定位、默认来源和“平台托管/租户可改”标识。

### 阶段 4：迁移、联测和发布

- 提供只读盘点及平台映射预览，按租户展示现有 queue/job route 影响；确认后才启用新权限。
- 对旧文件给兼容/拒绝策略和明确错误，不自动丢弃或覆盖环境专属配置。
- 在前后端联测后逐批启用，不做全租户一次性隐式重写。

## 阶段与交付切片映射

实施计划固定为阶段 0–4，共 5 阶段。下表 A–F 是阶段内可独立审查、可回滚的交付切片，不是额外阶段；一个阶段可以拆成多个 PR，切片也不要求一对一对应 PR。阶段 0 决策门通过后才开始行为变更：

| 交付切片 | 范围与产物 | 前置/退出门槛 |
|---|---|---|
| A. 基线与契约决议（阶段 0） | 固定前后端 SHA；字段/权限/场景矩阵；资源路由与租户规模报告；实例配置读取时点图；SLA 时间样例；MERGE 与旧包兼容决议 | 产品/业务确认 SLA 和默认；平台运维确认资源所有权；未决项不能带入实现 PR |
| B. Excel/API 契约（阶段 1） | 单场景及多场景组合模板投影、可选业务 SLA 扩展、旧包解析策略、服务端默认、MERGE 预览契约、必要输入限制 | 后端单测 + PG IT；旧包/新包/场景组合矩阵通过；阻断错误整包不写、Apply 幂等/并发冲突/事务回滚通过；OpenAPI 锁定 |
| C. 资源写入口收敛（阶段 2） | Excel、Job CRUD、复制/初始化等租户可达写入口授权；只读队列盘点/映射预览；资源管理审计 | 权限矩阵逐入口测试；未映射/冲突 fail closed；不改既有实例路由；目标租户规模有容量结论 |
| D. 执行参数与监控语义（阶段 0/4 的必要子项） | 执行/重试字段复用现有实例参数快照；监控策略动态读取；只验证本次触及的 deadline、告警去重和恢复行为，不引入通用配置版本/快照 | 定向单测/真实 PG IT 证明旧实例参数稳定、新实例使用当前参数、监控策略更新影响现存实例后续判定；DST/misfire/readiness/retry 及部署级演练按主线影响与发布风险分别验收，不作为配置 UX 的隐式扩建项 |
| E. 前端配置体验 | 向导、场景模板、权限态、预览差异、错误定位、OpenAPI 生成类型、文案与 i18n | 以 B 的后端契约为准；typecheck/lint 和关键配置流程 E2E 通过；租户管理员与平台运维角色分别验证 |
| F. 灰度与收尾（阶段 4） | 只读盘点、批准映射、分租户启用、监控/告警、runbook、旧包停用时间表 | 先 dry-run，再 canary；配置数据对账、作业创建/运行/恢复 smoke 通过；无回滚触发项后才扩大范围 |

### 阶段 0 的强制决议

阶段 0 评审必须产出带决策人和日期的决议记录，至少定下：

1. 当前只决议业务配置 MERGE 与受控 RESTORE 的角色、审批和删除范围；平台资源备份/恢复不在本方案内，未来如立项须另行定义权限，不能与租户业务恢复共用授权。
2. 场景模板/业务包的请求类型及旧包识别方式；如果旧工作簿存在无法从列头区分的语义版本，决定机器版本标记方案，不允许猜测解析。
3. tenant-scoped 稳定业务键、数据库主键和旧包 ID 的关系；跨环境导入的引用映射规则。
4. 已决议：影响执行/重试的少量参数在 instance 创建时写入现有参数快照；监控策略运行时读取并立即影响现存实例后续告警判定。SecretVersion 引用也采用实例创建时固定版本、排队/运行/重试复用该版本的语义，但它仍是独立的 Dispatch 运行时接入待办；撤销/紧急阻断策略另行定义。不得复制 Secret 明文或扩建通用快照。
5. 资源路由是否继续 tenant-scoped；队列基数目标、claim 查询成本、公平性和租户隔离的验收负载。
6. SLA 的业务时区、`bizDate` 来源、上游有效成功定义、DST 规则、readiness/misfire/retry 时间锚点，以及“只告警、不改执行状态”的边界。
7. 旧包兼容窗口、停止接收日期、映射责任人、灰度租户、回滚条件和数据保留/清理期限。

任何一项没有业务/平台负责人确认，相关实现切片不得自行选一个默认值继续；可先交付不依赖该决策的字段盘点或 parser 防护。

## 验证与回滚策略

- **契约层**：字段矩阵和 schema 一致性、OpenAPI/API drift、Excel seed/export round-trip、未知字段/旧包解析、MERGE/RESTORE 权限、审计脱敏。
- **数据库层**：所有已发布 Flyway migration 只追加，不修改历史迁移；新增迁移优先 additive。回填须幂等、可分批/可续跑，记录迁移前后行数、冲突和未映射项；先 dry-run，确认无误再 apply。
- **并发/故障层**：并发预览与 apply、同幂等键重试、不同版本冲突、监控多副本扫描、claim/outbox 中断、通知失败恢复、路由更新期间创建实例。
- **端到端层**：真实 PG 的配置包 apply；配对前端按角色走创建、导入预览、修正、apply、作业发布、监控查看；Sim 覆盖至少一个账期/晚到依赖/重跑场景。mock 只能补快速反馈，不能代替上述持久化和链路验证。
- **回滚**：先保留旧字段的读路径与旧包解析窗口；新路由只用于新实例，旧实例不回写；失败时停止创建新版本/新路由，恢复旧解析或映射，不执行反向破坏性迁移。若必须使用开关，应在对应 PR 明确 owner、默认值、指标、失效日期和删除版本；不得无期限双写。
- **发布门槛**：每切片先本地相关门禁，再 CI；有 API/权限/迁移改动时必须有对应 IT。Full Gate 绿不替代数据对账和灰度观察。每批启用后检查配置导入失败率、未映射队列、实例路由分布、SLA 告警重复/投递失败和非终态积压。

## 责任边界

| 责任方 | 决策/交付责任 |
|---|---|
| 业务负责人/租户管理员 | 确认业务日历、账期、SLA、字段映射和租户业务包恢复范围；不负责选择物理容量 |
| 平台运维/安全 | 资源配额与路由、Secret/连接器策略、平台运行阈值、灰度及回滚批准；平台资源备份/恢复若未来单独立项，由其项目另行明确责任 |
| 后端模块负责人 | Excel/API schema、授权、事务/幂等、执行参数快照边界、监控状态机和迁移安全 |
| 前端负责人 | 按后端 OpenAPI 实现场景向导、角色可见性、预览/错误反馈和国际化 |
| 验证负责人 | 维护场景矩阵、真实 PG IT、前后端 E2E、故障注入、迁移 dry-run 与验收证据 |


## 验收门槛

- 新建一个基础作业时，租户不需要创建资源队列、不需要输入 Worker 分组/容量/QPS；系统可解释并展示平台路由结果。
- 有效路由按租户隔离或已验证的共享隔离策略解析；未配置/未授权/失效映射均 fail closed。修改映射不改变既有运行实例的路由快照，且有测试覆盖新旧实例边界。
- 五类 Worker 的快速模板只要求当前场景的业务必填项；普通作业无须维护空的 Pipeline/Workflow/Channel 配置。
- 一个批次包含多个 Worker 场景时可通过一个工作簿完成一次上传、一次整体预览和一次 apply；不要求每个场景重复导入。跨 sheet 新旧引用可解析，任何阻断错误都不会产生部分持久化；未出现在 MERGE 包中的场景和作业保持不变。
- 每个场景模板都有可复现的字段盘点：没有任何当前场景用途的列为零；可安全默认字段不要求人工填写；必填列有业务理由、默认来源和对应校验测试。配置人员需要人工决定的字段数相对阶段 0 基线下降，具体目标数在字段盘点评审后固化，避免先拍任意列数指标。
- 空缺的安全默认可追溯到服务端权威值；非默认业务字段缺失时在 preview 阶段报出具体 sheet/字段/原因。
- 非平台角色通过 Excel、REST API、批量复制等所有入口写资源管理字段均被拒绝；平台角色可维护并有审计记录。
- 业务配置快照经受控 RESTORE 导出/导入可精确还原；普通 MERGE 包只验证预期的新增/更新且不因缺项删除。自动化敏感值扫描证明不含凭据明文，且包内不包含平台物理资源策略。平台资源备份/恢复明确不属于本方案的交付与验收范围。
- MERGE 缺行/缺字段不删除；受控 RESTORE 明确列出删除并具备独立授权、审计、事务回滚和重放保护。两种意图的重复提交、并发修改冲突及失败恢复均有契约/集成测试。包类型由 API 请求意图确定，schema 版本按无歧义列头签名识别；旧包升级预览、未知字段拒绝、跨环境连接映射和重复导入均有契约测试。租户标识只来自认证上下文，跨租户请求拒绝。
- Excel 资源与恶意输入限制有测试：压缩/解压体积、超长单元格/超多行、ZIP bomb、宏/外链/公式、并发上传、超时、临时文件清理、限流和错误脱敏；导出内容在常见表格软件打开时不执行注入公式。
- 旧包迁移测试覆盖有/无 `resource_queue`、已有 queue 引用、未知 queue、跨环境 endpoint、旧包缺少监控策略字段时保持已有策略，以及跨租户拒绝。执行参数更新不得改写已有实例参数；监控策略更新应影响现存实例的后续告警判定。业务日、原始计划触发时间、依赖 EFFECTIVE 时间、DST gap/overlap、misfire/catch-up、readiness defer 和 retry/attempt 边界按上文锚点和本轮实际触及范围安排专项验证。
- 多副本监控扫描、重复告警抑制、告警 outbox 投递失败/恢复、claim 进程崩溃、接收端重复投递幂等均通过真实持久化层验证；不能只以 mock 单测作为验收。
- 更新 OpenAPI、前端生成类型、向导、Excel seed/示例、测试脚本和文档索引；运行 API drift、配置包单测/真实 PG IT、受影响前端 typecheck/e2e 与 Sim 场景。

## 风险与控制

| 风险 | 控制措施 |
|---|---|
| 租户队列模型与“平台资源”概念不一致，迁移后破坏 claim/公平性 | 阶段 0 追踪实际执行链和表约束；先保留租户边界，基于证据设计映射；用多租户并发/配额测试验证后再切路由 |
| 作业被映射到错误或不存在的队列 | 先做只读映射预览；无映射 fail closed，不默认进通用池；路由只对新实例生效 |
| 隐藏字段造成已有配置丢失 | 快速模板与完整业务配置包分离；导入事务前展示差异；不识别的旧字段明确拒绝 |
| 默认值改变账期、重试或幂等语义 | 业务日、依赖、冲突策略、CDC、水位及非幂等重试不设猜测值 |
| 租户从其他写入口绕过 Excel 限制 | 服务层/RBAC 统一授权，覆盖 CRUD、复制、批量开户/初始化等路径 |
| 全量 schema 减列破坏现有测试/客户包 | 首期保留底层字段和完整业务配置包，通过场景字段子集及权限收敛；之后按真实调用证据退役字段 |
| 运行监控阈值和业务截止时限混成一个租户配置面 | 分别定义字段所有者、默认事实源和执行语义；平台阈值不进入租户快速模板，业务 SLA 只在确有需求时显式声明，且仅告警、不隐式改变执行 |
| 当前已有监控策略实现被误认为已满足本方案 | 以记录的基线 SHA 对照代码/API/前端/测试；监控字段按所有权决定是否进入租户业务包；旧包缺失字段默认保持现状，明确清除必须显式表达并审计 |
| 配置编辑导致同一实例执行/重试参数漂移 | 仅将影响执行/重试的已解析参数保存到现有实例快照；监控策略动态生效；模板/Pipeline 等其他配置先要求同实例漂移复现，再独立评估最小修正；Secret 明文永不进入快照 |
| 多副本扫描或通知故障导致漏报/重复告警 | 以 instance/attempt 幂等键、持久化 claim/outbox、可重试投递和接收端幂等形成闭环，并用故障注入验证 |
| Excel 解析造成资源耗尽或公式注入 | 设文件/解压/行列/时限/并发上限，限制工作簿特性；公式输入按不可信数据处理，导出做公式注入防护 |
| 列头指纹无法识别同结构不同语义 | 禁止同指纹改变语义；确需变更时引入可验证版本标记/API 版本并保留旧解析路径 |
| 逐租户映射导致队列和索引基数膨胀 | 阶段 0 做目标租户规模基数/查询成本/公平性评估，再选逻辑映射与物理池方案；不预设每租户独占物理队列 |
| SLA 使用错误的时间锚点造成误报、漏报或补跑漂移 | 固定业务日/计划触发/依赖 EFFECTIVE/首次执行/attempt 时间语义；显式处理 DST 和 retry，不允许监控扫描时刻隐式参与业务截止计算 |

## 方案复核结论（阶段 0 历史记录，非当前实现要求）

方案方向成立，但落地前必须同时定清配置包导入语义、实例配置版本、资源映射规模和监控告警恢复语义，不能只做 Excel 减列。此次复核补齐了：

- MERGE 与受控 RESTORE 分离；缺项默认不删除，精确恢复需要预览、独立权限、审计和事务回滚。
- 不增加租户可见 manifest sheet；用 API 导入意图和可验证 schema 识别，承认列头指纹的能力边界，禁止同指纹语义漂移。
- （历史建议，已由 2026-10-11 范围决议取代）作业定义/模板/路由/监控策略对实例固化版本引用，监控策略更新不追溯影响已创建实例。当前只固定少量执行/重试参数，监控策略动态读取；不要求通用实例快照。
- 明确业务日历责任、SLA 时间锚点、多副本监控告警的去重与 outbox 恢复，以及 Excel 恶意输入和资源耗尽防护。
- 平台资源迁移避免未经评估的队列基数膨胀，不默认增加长期兼容开关或双写。

本段保留的是阶段 0 实施前的历史复核记录，不代表当前分支状态或当前验收要求。当前状态、未完成项和旁支边界以本文前部的“当前实施进度”及“本轮主线与旁支台账”为准。尚未完成的独立事项包括动态 JSON 敏感信息审计、SecretVersion 与运行时派发集成、平台资源快照/恢复（如未来立项）及路由规模/公平性评估；这些不构成通用实例配置快照的理由。移除租户资源 Sheet 与底层路由模型切换不得合并为一次不可回滚改造。
