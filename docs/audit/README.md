# 审计文档索引

本目录保存日期化专项审计记录，报告结论只适用于成稿时点。当前事项见 [TODO Master](../plans/todo-master.md)，持续守护总账见 [规范目录](../standards/convention-drift-guard-index.md)，当前机器清单见 [统计目录](../stats/java-readability-inventory.md)。

| 文档 | 范围 |
|---|---|
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

- [test-fixture-path-migration-2026-10-24.json](test-fixture-path-migration-2026-10-24.json)：36份场景 SQL/JSON/XLSX 的原路径、现路径与迁移前 SHA-256；证明路径迁移内容等价。

- [sonar-full-scan-and-remediation-2026-10-01.md](sonar-full-scan-and-remediation-2026-10-01.md)：SonarQube 全量扫描与治理记录。

- [脚本目录、说明与CI索引治理](script-and-ci-governance-review-2026-10-24.md)：连续十项要求、目录/基线迁移、守卫覆盖、实际验证和未关闭项。

- [architecture-and-contract-assets-review-2026-10-24.md](architecture-and-contract-assets-review-2026-10-24.md)：当前Workflow分支五类系统资产逐文件核查、架构图/模型/协议修正及能力验证边界。
