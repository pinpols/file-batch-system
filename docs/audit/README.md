# 审计文档索引

本目录保存日期化专项审计记录，报告结论只适用于成稿时点。当前事项见 [TODO Master](../plans/todo-master.md)，持续守护总账见 [规范目录](../standards/convention-drift-guard-index.md)，当前机器清单见 [统计目录](../stats/java-readability-inventory.md)。

| 文档 | 范围 |
|---|---|
| [后端 Controller / Service / Entity 边界复核](backend-controller-service-entity-boundary-review-2026-11-01.md) | bug-fixes 工作树的控制层与实体边界复核、Service 清单、阶段性定向及容器测试；2026-11-01 暂停，后续改动与完整验证未完成 |
| [Console Controller 边界清单](console-controller-boundary-inventory-2026-11-01.csv) | Console 97 个 Controller 的职责复核与证据边界；整改及验证进度以边界复核记录为准 |
| [后端 Entity 边界清单](backend-entity-boundary-inventory-2026-11-01.csv) | 101 个基线 Entity 的表行、投影和混合字段分类；迁移项待验证 |
| [Worker Service 边界清单](backend-worker-service-boundary-inventory-2026-11-01.csv) | 31 个 Worker Service 候选的职责与入口复核进度 |
| [后端 25 工作包全仓治理进度](backend-25-package-governance-2026-10-26.md) | 当前开发工作树 25 包及 6 项补充的实际静态/定向证据、已修项、未验范围与下一步；不继承历史清单的完成状态 |
| [后端治理问题证据复核索引](backend-25-package-bug-evidence-index-2026-10-29.md) | 台账内问题编号和人工复核状态快照；区分独立缺陷、补证、修复回归及待核项 |
| [后端代码规约与仓库卫生复扫](backend-code-convention-hygiene-scan-2026-10-26.md) | 当前 `fix/wdi-current` 动态工作树；PMD 4 处、判空 18 处、Spotless 1 文件，Javadoc 守卫与规范冲突；补查非 CI 的 Service 职责、测试 Mock/失败断言及真实 Mapper 验证缺口 |
| [后端规约治理 Skill 覆盖复核](backend-convention-skill-coverage-review-2026-10-26.md) | 四个既有主入口主责收敛；25工作包及6项规范补充、统一Bug方法；撤回重复入口并保留历史提炼与分阶段验证 |
| [本地规约门禁整改与复扫](local-hooks-and-convention-remediation-2026-10-26.md) | 修复 2026-10-25 扫描失败项；后端 pre-commit 53/53、pre-push 22/22，前端治理 18 项与生产构建通过；明确真实数据库和产品 E2E 未执行 |
| [本地双钩子全量只读扫描与代码规约扫描](local-hooks-and-convention-full-scan-2026-10-25.md) | pre-commit 53 项（45✅/8❌）与 pre-push 21 项（18✅/3❌）逐门禁全量只读复跑；失败按并行工作流新增代码/文档、只读口径差异、钩子外检查分类；未修改代码、未暂存、未提交 |
| [现行文档图整理](document-diagram-refresh-2026-10-25.md) / [59份清单](document-diagram-catalog-2026-10-25.csv) | 双仓图块59→127，明暗渲染与语义核对；归档零写入，完整构建体积门禁另列 |
| [基础设施边界六项修复](infrastructure-abstraction-boundary-fix-2026-10-25.md) | 五处JDBC异常识别/构造下沉和Quartz时间源隔离；origin/main增量门禁6→0，152项定向测试通过 |
| [测试编码约定核查](test-code-conventions-review-2026-10-25.md) / [逐项定位](test-code-conventions-findings-2026-10-25.csv) | 最新wdi-current测试编码静态审查；SQL具名承接21处、JDBC断言26处、Mock/AssertJ/LENIENT及前端候选，未批量整改或执行产品测试 |
| [固定投影 Map 与长构造后续](model-map-and-constructor-followup-2026-10-24.md) / [651 项定位快照](model-next-candidates-2026-10-24.csv) | 7 个长构造调用点改用具名 Builder；20 个固定查询投影改用 record view（含集群诊断6项）；其余 138 个长构造与 486 个 Map 快照项仍待核对，未运行项目构建、测试或 MyBatis 映射 |
| [实体与构造治理](entity-and-constructor-governance-2026-10-24.md) / [101模型](entity-model-governance-2026-10-24.csv) / [123构造裁定](nullable-constructor-governance-2026-10-24.csv) | 30生产文件、48构造和1固定Map；57对象字段及实参调用顺序核对；只做静态，不编译或测试 |
| [测试 SQL 与 JDBC 断言收尾](test-sql-and-assertion-governance-2026-10-24.md) / [1529 项清单](test-sql-and-assertion-governance-2026-10-24.csv) | 102 个测试文件；24 个 SQL 实参、1504 个 JDBC 结果和 62 个轮询边界；Shell 默认枚举修复，只有静态证据 |
| [复合判断集中整改](compound-condition-readability-2026-10-24.md) / [482 项清单](compound-condition-inventory-2026-10-24.csv) | 双仓 287 处具名条件、197 文件；保留 195 处，语义/AST 核对及静态规则执行边界 |
| [workflow-integration-functional-review-2026-10-24.md](workflow-integration-functional-review-2026-10-24.md) | 当前整合源码审批、触发等待及SDK功能缺陷；首红/修复/定向验证分开，规约归另一会话 |
| [worker-pipeline-description-drift-review-2026-10-24.md](worker-pipeline-description-drift-review-2026-10-24.md) | 当前Worker分类、共享模板、阶段/配置来源和运行职责漂移核查；32内容文件修正/补注、78项初轮回归；后续摘要键/格式闭环（55项Java、28项守卫） |
| [database-table-necessity-review-2026-10-24.md](database-table-necessity-review-2026-10-24.md) / [190表清单](database-table-necessity-catalog-2026-10-24.csv) | V346双库190表初审；后续V347退役9表、DQ归档收尾、逐表理由及本地验证，运行库未改动 |
| [development-branch-convention-closure-review-2026-10-24.md](development-branch-convention-closure-review-2026-10-24.md) | B184–B190 参数/长方法增量复核；PMD、定向测试、扫描原件及性能/目标环境边界 |
| [document-classification-and-naming-review-2026-10-24.md](document-classification-and-naming-review-2026-10-24.md) | 资产与增量报告分类、归档命名、原路径迁移清单及静态验证边界 |
| [go-live-realism-audit-2026-06-21.md](go-live-realism-audit-2026-06-21.md) | 2026-06-21 上线真实性静态审计报告；当前审核方法见 [上线审计检查清单](../standards/go-live-audit-checklist.md) |
| [design-pattern-review-2026-10-21.md](design-pattern-review-2026-10-21.md) | 开发分支新增抽象与职责边界抽查；登记配置初始化处理器及Trigger核心服务两项P3设计改进，其他抽查模式未发现误用 |
| [nested-callsite-closure-2026-10-24.md](nested-callsite-closure-2026-10-24.md) / [逐文件覆盖清单](nested-callsite-coverage-2026-10-24.csv) | 配对仓库 3,441 个生产源码文件全量候选分类；本轮 65 个源码文件集中提取；静态语义核对完成，未编译或运行测试 |
| [nested-callsite-inventory-2026-10-21.md](nested-callsite-inventory-2026-10-21.md) | 嵌套调用治理历史过程、保留决定及逐批语义证据；当前全量结论以 2026-10-24 集中整改报告为准 |
| [development-branch-code-review-2026-10-21.md](development-branch-code-review-2026-10-21.md) | 2026-10-21 恢复、SDK、归档性能与安全扫描快照；当前进度见 TODO 与2026-10-24增量复核 |
| [database-reference-catalog-2026-10-18.csv](database-reference-catalog-2026-10-18.csv) | PostgreSQL 17.11/V291只读目录快照，283物理关系的列/约束/索引/触发器/RLS/分区；解读与整改见[逐表方案](../plans/database-reference-integrity-remediation-2026-10-18.md) |
| [database-index-catalog-2026-10-18.csv](database-index-catalog-2026-10-18.csv) | 1,634索引目录对象，定义/谓词/约束依赖/挂接与短窗口统计；[索引专项](../plans/database-index-review-remediation-2026-10-18.md) |
| [database-partition-catalog-2026-10-18.csv](database-partition-catalog-2026-10-18.csv) | 10父/107子，共117行；边界、RLS/ACL、参数与索引；[分区专项](../plans/database-partition-lifecycle-remediation-2026-10-18.md) |
| [database-job-date-pruning-explain-2026-10-18.json](database-job-date-pruning-explain-2026-10-18.json) | PG17.11/V291只读EXPLAIN，日期列转换与DATE条件37→1规划分区；未执行ANALYZE或性能验收 |
| [design-closure-review-2026-10-14.md](./design-closure-review-2026-10-14.md) | 后端六项及前端/跨端九项设计闭环缺陷；区分真实 SQL 复现、前端源码逻辑复现、静态调用链和未完成的联调验收；当前状态见 TODO Master |
| [code-readability-governance-2026-10-13.md](./code-readability-governance-2026-10-13.md) | 前后端方法调用、多个 `null`、合法状态、字符串、设计模式与数据结构治理闭环及验证边界 |
| [cross-repo-governance-drift-review-2026-10-12.md](./cross-repo-governance-drift-review-2026-10-12.md) | 前后端 CI 守卫范围、基线与人工/AI 周期复核的证据快照；当前缺陷状态须重新核对 |
| [tenant-job-configuration-phase0-baseline-2026-10-10.md](./tenant-job-configuration-phase0-baseline-2026-10-10.md) | **历史基线快照**：记录 2026-10-10 改造前的前后端事实与未决决策；阶段 0–4 当前状态统一见[租户作业配置改造计划](../plans/tenant-job-configuration-simplification-plan-2026-10-09.md)的阶段状态矩阵 |
| [sensitive-logging-and-runtime-config-drift-2026-10-12.md](./sensitive-logging-and-runtime-config-drift-2026-10-12.md) | 动态日志敏感字段、Loki 验收边界，以及 Pipeline/模板/渠道运行时配置漂移核查 |
| [product-journey-adversarial-audit-2026-10-10.md](./product-journey-adversarial-audit-2026-10-10.md) | 配置、作业创建与入口的用户旅程对抗审计（进行中；含后续覆盖矩阵） |
| [typed-contract-governance-verification-2026-10-07.md](./typed-contract-governance-verification-2026-10-07.md) | 固定契约、既有枚举与精准门禁的本地实施验收、保留项和未执行边界 |
| [backend-adversarial-audit-2026-07-27.md](./backend-adversarial-audit-2026-07-27.md) | 后端安全、一致性和故障恢复对抗审计 |
| [backend-six-round-adversarial-audit-2026-07-27.md](./backend-six-round-adversarial-audit-2026-07-27.md) | 六轮对抗审计汇总 |
| [backend-deep-scan-2026-06-19.md](./backend-deep-scan-2026-06-19.md) | 后端深度扫描快照 |
| [database-and-class-comment-audit-2026-08-21.md](./database-and-class-comment-audit-2026-08-21.md) | 数据库、字段与类注释覆盖审计 |
| [sql-java-responsibility-audit-2026-09-08.md](./sql-java-responsibility-audit-2026-09-08.md) | SQL 与 Java 职责边界审计 |
| [resource-lock-pool-cache-transaction-audit-2026-10-01.md](./resource-lock-pool-cache-transaction-audit-2026-10-01.md) | 资源、锁、线程、缓存、幂等与事务边界审计 |
| [module-architecture-audit-index-2026-05-23.md](module-architecture-audit-index-2026-05-23.md) | 2026-05-23 九模块架构审计汇总 |
| [orchestrator-arch-audit-2026-05-23.md](./orchestrator-arch-audit-2026-05-23.md) | Orchestrator 架构审计 |
| [common-trigger-arch-audit-2026-05-23.md](./common-trigger-arch-audit-2026-05-23.md) | Common 与 Trigger 架构审计 |
| [console-api-arch-audit-2026-05-23.md](./console-api-arch-audit-2026-05-23.md) | Console API 架构审计 |
| [worker-core-import-export-arch-audit-2026-05-23.md](./worker-core-import-export-arch-audit-2026-05-23.md) | Worker Core、Import、Export 审计 |
| [worker-process-dispatch-arch-audit-2026-05-23.md](./worker-process-dispatch-arch-audit-2026-05-23.md) | Process 与 Dispatch 审计 |

## 专项扫描与治理记录

| 文档 | 职责 |
|---|---|
| [infra-port-abstraction-sonar-review-2026-09-25.md](infra-port-abstraction-sonar-review-2026-09-25.md) | 基础设施端口化与 Sonar 增量复核结论（2026-09-25） |
| [interface-abstraction-governance-2026-09-25.md](interface-abstraction-governance-2026-09-25.md) | 面向抽象接口治理快照 |
| [java-readability-phase-0-classification-2026-08-12.md](java-readability-phase-0-classification-2026-08-12.md) | Java 可读性治理阶段 0 分类总账（2026-08-12） |
| [java-semantic-expression-scan-2026-08-26.md](java-semantic-expression-scan-2026-08-26.md) | Java 语义表达扫描记录（2026-08-26） |
| [redis-mq-abstraction-governance-2026-09-25.md](redis-mq-abstraction-governance-2026-09-25.md) | Redis / MQ 抽象治理 |
| [robustness-deep-scan-2026-06-10.md](robustness-deep-scan-2026-06-10.md) | 全库健壮性深扫报告 2026-06-10 |
| [schema-optimization-review-2026-09-07.md](schema-optimization-review-2026-09-07.md) | 数据库表结构审查与优化落地方案 |
| [single-worker-capacity-assessment-2026-06-21.md](single-worker-capacity-assessment-2026-06-21.md) | 单文件 / 单 worker 处理"上万个任务"能扛住吗 —— 容量分析 |
| [sonar-debt-triage-2026-10-10.md](sonar-debt-triage-2026-10-10.md) | Sonar 全量债务分级（2026-10-10） |
| [sql-config-governance-report-2026-06-08.md](sql-config-governance-report-2026-06-08.md) | SQL / 配置混杂治理报告与整改计划 |

## 专题入口

| 文档 | 职责 |
|---|---|
| [ci-external-action-upgrade-review-2026-09-30.md](ci-external-action-upgrade-review-2026-09-30.md) | CI 外部 Actions 版本升级与验收记录 |
| [runtime-config-import-impact-2026-10-24.md](runtime-config-import-impact-2026-10-24.md) | 运行时配置导入影响审计（2026-10-24）；仅用于追溯当时基线 |

- [test-fixture-path-migration-2026-10-24.json](test-fixture-path-migration-2026-10-24.json)：36份场景 SQL/JSON/XLSX 的原路径、现路径与迁移前 SHA-256；证明路径迁移内容等价。

- [sonar-full-scan-and-remediation-2026-10-01.md](sonar-full-scan-and-remediation-2026-10-01.md)：SonarQube 全量扫描与治理记录。

- [脚本目录、说明与CI索引治理](script-and-ci-governance-review-2026-10-24.md)：连续十项要求、目录/基线迁移、守卫覆盖、实际验证和未关闭项。

- [architecture-and-contract-assets-review-2026-10-24.md](architecture-and-contract-assets-review-2026-10-24.md)：当前Workflow分支五类系统资产逐文件核查、架构图/模型/协议修正及能力验证边界。

## 归档存量逐文件处置

[archive-document-disposition-2026-10-25.csv](archive-document-disposition-2026-10-25.csv) 记录初始149个归档文件的原路径、字节哈希、当前裁定、承接入口与理由；删除正文的决定已按后续纠偏恢复；内容复核和边界见[分类记录](document-classification-and-naming-review-2026-10-24.md)。

## 历史经验保留与技能提炼

[逐篇提炼映射](archive-experience-skill-extraction-2026-10-25.csv)记录120篇恢复原文的来源、哈希、方法与技能去向；恢复纠偏与实际检查见[分类复核](document-classification-and-naming-review-2026-10-24.md)。

## 文档与脚本的全量技能复核

[复核记录](document-script-skill-review-2026-10-25.md)说明范围、方法、技能优化与验证边界；[逐文件清单](document-script-skill-coverage-2026-10-25.csv)记录2157个冻结来源的路由和复核层级。
