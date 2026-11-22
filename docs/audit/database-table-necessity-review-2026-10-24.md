# 数据库表必要性与冗余审查（2026-10-24）

## 后续实施范围（2026-10-24）

用户已授权退役3张候选表及2张旧Pipeline定义表，同时核实并收尾5张冷镜像的生命周期。以下审查结论保留其采集时点；实施以新库不生成退役结构、当前代码/契约/脚本无依赖及真实空库验证为完成条件。业务库PROCESS和Pipeline运行事实继续保留；不修改当前运行数据库，不提供旧库兼容。进度：代码、增量迁移、契约及本地定向验证完成；以下初审保留其采集时点，不作为实施后的结构快照。

## 实施决定与逐表退役理由

本节记录用户授权后的实施，覆盖5张原退役候选和5张冷表。**最终退役9张表，保留并完善1张DQ执行证据冷表。**没有以“本地空表”或“搜索不到引用”单独作为裁定依据。

| 表 | 设计初衷 | 当前承担者 / 明确理由 | 本轮处理 |
|---|---|---|---|
| `batch.compensation_checkpoint` | 逐步记录补偿提交、失败与逆向处理位点 | 实际补偿服务从未接通此协议；命令状态由 `compensation_command` 承担，现有业务未承诺Saga逆向回滚。为预留表扩建补偿体系不符合当前范围 | 退役预留表、运维清理引用；补偿命令与实际业务副作用核查继续保留 |
| `batch.trigger_runtime_state` | 旧时间轮的下次fire、marker、misfire及readiness状态 | 当前Quartz JDBC JobStore、Workflow occurrence和补跑待审模型已接管；残留旧身份会产生第二套无消费者调度状态 | 退役旧表及misfire旧列/外键/约束；保留当前补跑审批与Workflow去重 |
| 平台库 `batch.process_staging` | PROCESS计算到校验、提交之间的WAP暂存 | 当前计算、校验、提交必须在业务数据源同库事务执行；平台副本不承担该事务，还容易误导运维切错库 | 只退役平台副本；业务库同名表、generation围栏、分区和恢复链继续保留 |
| `batch.pipeline_definition` | 保存租户编辑的文件Pipeline配置 | 已选择按Job类型解析 `SystemPipelineCatalog`，不再开放租户阶段编排；保留旧定义会留下无法影响新执行的配置面 | 删除表、API/兼容别名/页面、Mapper/Service、seed及治理依赖 |
| `batch.pipeline_step_definition` | 保存上述Pipeline的步骤实现、参数与重试策略 | 固定步骤来自系统目录，业务阶段参数归属Job；DB回退会重新引入已退役模型，产生两套执行来源 | 删除表、旧定义ID及DB回退；配置包只输出当前10张业务sheet，旧sheet明确拒绝 |
| `archive.atomic_task_config_archive` | 独立冷存可复用原子任务配置 | 在线配置仍由热表管理，当前没有按保留期搬运/冷读需求；空镜像未提供历史版本恢复能力。既有运行执行合同不依赖它 | 移除预留镜像和漂移登记，保留热配置及既有执行快照；不新增热清理 |
| `archive.custom_task_type_registry_archive` | 冷存SDK自定义任务类型登记 | 类型注册/查询是在线目录职责；运行合同来自已持久化执行配置。此镜像既不服务当前注册，也不形成类型版本协议 | 移除预留镜像，保留在线注册表与运行合同 |
| `archive.data_quality_rule_archive` | 冷存DQ规则定义 | 在线规则由热表管理；独立无版本镜像不能恢复任意历史表达式，执行结果历史由检查证据承担。移除镜像不删除任何在线规则 | 移除预留镜像；保留热规则；若未来要求表达式版本回溯，须定义独立版本契约 |
| `archive.workflow_definition_version_archive` | 再次冷存Workflow历史版本快照 | 热表 `workflow_definition_version` 本身已保存版本化不可变快照且无自动TTL清理；第二份无人消费的镜像没有额外生命周期职责 | 移除预留镜像，继续保留热版本历史与现有版本查询 |
| `archive.data_quality_check_archive` | 长期保留Job的DQ实际判定、指标与样本 | 这是运行证据，随Job归档后仍须按租户/实例回查，具有独立冷存职责 | 保留并接通完整行搬运、删除前验证、冲突回滚、冷热读取、查询索引和运维入口 |

### 迁移与代码边界

- 历史迁移保留**本轮开始时内容**；退役通过 `V347__retire_unused_platform_tables.sql` 增量完成。既有缺号V31、V238、V242、V243、V244以显式保留号位文件补齐，不重排其他版本、不伪造历史DDL，V1至V347连续且唯一。
- V347先检查9张目标表无记录；若有旧数据则在任何DDL前拒绝，保持原结构与记录。不迁移旧表数据，不在运行库执行repair或清库。
- 移除两张Pipeline定义表的旧查询/写入接口、SSE、兼容别名、前端路由重定向和CRUD客户端，OpenAPI及生成类型同步；文件Pipeline运行观测和系统固定阶段元数据仍保留。
- Worker仅消费系统目录内联步骤，缺失时返回既有步骤缺失失败；不查旧定义。运行/血缘DTO、SQL、归档热冷镜像不再带旧定义ID。
- SIM/seed与配置包改为直接设置Job业务参数；配置包不生成旧sheet或旧示例，旧格式只保留拒绝输入的负向校验，不保留转换兼容。

### DQ归档、回查与恢复边界

归档批次先锁父Job，再锁DQ检查行；按同租户父子身份复制全部14列（包含 `updated_at`），对照冷热完整JSON行。内容缺失、同ID异租户或内容冲突均失败关闭并回滚整批；只有验证成功才删热检查及热运行树。跨租户引用仍由现有数据库触发器/写入围栏拒绝，未放松。

应用Mapper支持按租户/Job冷热读取并去重；[运维SQL](../../scripts/db/inspect/select-data-quality-evidence.sql)提供同样的可发现只读入口。冷表增加 `(tenant_id, job_instance_id, id)` 索引，不声称已完成生产规模性能验收。成功/失败保留期直接清理脚本在任何删除前拒绝携带DQ证据的实例，要求走完整归档。归档日志记录DQ复制/删除数量。

配置/定义热表与DQ冷表未新增自动TTL删除。DQ冷读用于历史取证和恢复核对；不把终态Job重新插回热表充当业务重放，数据库灾难恢复仍走备份/PITR，业务重放走正式Replay。操作与冲突处置见[恢复手册](../runbook/forensic-replay-howto.md#dq执行证据的冷热回查)。

### 最终验证结果（2026-10-24）

后续清理无用Worker定义仓库注入后，四类Worker的62项复验通过；PROCESS两个委托构造器的旧参数遗漏已修正并保留首次失败。继续核查说明漂移的最终78项回归和当前门禁缺口见[专项记录](worker-pipeline-description-drift-review-2026-10-24.md)；其中新增说明涉及的AbstractTaskConsumer保留原有业务代码布局，Spotless首因未通过。下表Spotless通过仅指此前退役改动的已验范围。其后用户授权继续修复摘要键和格式，当前相关14文件Spotless、摘要键全仓门禁、28项守卫及55项Java回归已通过，见专项记录的后续收口章节；此前失败保留为历史证据。

| 命令 / 范围 | 实际结果 | 证明边界 |
|---|---|---|
| `./mvnw -pl batch-orchestrator -am -Dtest=LocalFlywayPlatformMigrationsIntegrationTest,PipelineProgressArchiveIntegrationTest,ArchiveColdStorageIntegrationTest,SuccessInstanceArchiveServiceTest,DataQualityCheckReferenceIntegrityIntegrationTest,SameJobMultiRunArchiveLineageIntegrationTest,CapacityProfileMapperIntegrationTest,FileGovernanceStaleSweepIntegrationTest,ArchiveSchemaDriftCheckIntegrationTest,ArchiveSchemaDriftCoverageIntegrationTest,LineageEvidenceArchiveFallbackIntegrationTest,BatchDayReplayOutputsOnlyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | **72/72通过，0失败/错误/跳过，Maven退出0** | 真实PostgreSQL空库/增量、DQ全行搬运/冲突回滚/冷热读、已有运行树/血缘与位点、psql直接清理保护 |
| `./mvnw -pl batch-console-api,batch-trigger,batch-worker/import,batch-worker/export,batch-worker/process,batch-worker/dispatch -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false test`（精确选择器见汇总命令） | **191/191通过**：Core13、Import8、Export7、Process29、Dispatch5、Console129；0失败/错误/跳过，Maven退出0 | 固定阶段/缺失失败/续跑、当前10-sheet配置包、旧查询别名404、请求未知字段拒绝、运行DTO和当前调用方；已先clean删除旧类/资源的模块 |
| `./mvnw -pl batch-orchestrator,batch-trigger -am -Dtest=LocalFlywayPlatformMigrationsIntegrationTest,TriggerMisfirePendingMapperIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | **17/17通过**，含已计入前项的迁移4项及额外misfire13项 | 最终V347全链及空表/RLS检查前置条件、V346→V347只移除裁定9表、旧表有数据时完整拒绝；补跑去重/审批/驳回/过期真实持久化 |
| 上述后端合并口径 | **276个不重复定向用例通过** | 重复执行的迁移4项不重复计数；不代表全仓测试或R验收矩阵全部闭环 |
| 新增6份迁移的Squawk；V347最终内容再单独复验 | **0 issues** | 只对明确退役语句逐句声明既有客户端破坏风险；未修改全局规则。V1至V347连续唯一，源目录与编译资源347份逐字节一致 |
| 本轮历史迁移恢复比对 | 332份有编辑前备份的历史文件全部字节一致；其余历史文件本轮未修改 | 相对本轮开始状态；不宣称既有工作树已与Git发布基线相同 |
| `npm run gen:api`、`npm run gen:api:check`、`npm run typecheck`、所改客户端/路由ESLint、两份Vitest测试 | **通过；Vitest8/8** | OpenAPI生成类型同步、旧路由无重定向；未执行真实浏览器端到端联调 |
| Python清理清单18项、高风险守卫自测11项；文档结构/日期、脚本治理、schema治理资产、Console OpenAPI路径、所改Java的Spotless | **通过** | 门禁保持启用，未扩大规则覆盖豁免 |
| 变更迁移安全门禁（本轮静态复核） | **未通过**：282个变更/新增迁移中有73条Squawk发现落在未修改行；V336新增行复扫0 issues | 73条为 `constraint-missing-not-valid` 59、`changing-column-type` 3、`renaming-table` 4、`ban-drop-table` 2、`ban-drop-not-null` 1、`adding-not-nullable-field` 2、`disallowed-unique-constraint` 2。V336两项新增约束改为 `NOT VALID` 后显式 `VALIDATE CONSTRAINT`；全门禁仍未通过，不扩基线或豁免。历史checksum差异仍按既有专项记录；本轮未执行Flyway或数据库 |
| 全仓高风险操作清单 | **未通过**：仍有其他路径超预算 | 本轮仅新增V347的9次DROP TABLE，逐表理由、空数据前置检查、无CASCADE与真实回归审查后精确登记9；其他预算值完全保留。任务文件计数差异证明没有其他新增删除类操作 |
| 运维SQL头部风险检查 | **通过：WARN30 / FAIL0**；410份SQL资产 | 已为 `load-tests/sql/drop-control-plane-metrics-fixture.sql` 补充中文用途、隔离库前置条件及重建恢复说明；30条为非阻断 `ON CONFLICT` 提示。数据库注释覆盖门禁通过。脚本静态治理83项守卫、4项直接可执行调用通过；均未执行SQL或连接数据库 |

命令、退出码、分模块计数与日志入口见 `logs/verification/workflow/table-retirement-20261024-summary.json`。核心日志：`table-retirement-20261024-orchestrator.log`、`table-retirement-20261024-worker-console.log`、`table-retirement-20261024-flyway-misfire.log`（均在同目录）。首轮有效失败保留在 `table-retirement-20261024-first-regression-failure.log`。

失败项均按首因修正后复验：完整行校验发现DQ漏拷 `updated_at`，现补齐14列；旧定义fixture/引导断言按当前模型收敛；反向归档覆盖发现5张既有冷表漏登记，现精确补登记；位点测试改为按服务有界批次推进并清理自有DQ夹具，未扩大生产批次或放松数据断言。

Orchestrator合并测试进程退出时出现Surefire 30秒退出超时提示，测试结果0失败且Maven退出0；本轮未定位退出线程原因，不据此宣称测试资源生命周期治理全部通过。迁移拒绝/回滚负向用例中的预期ERROR日志不代表迁移全链失败。

本地实施与定向验证完成；**未修改当前运行库、未重打运行JAR、未提交/推送/建PR、未运行全量SIM、CI或staging/生产验证**。既有R01–R36、数据库专项和正式服务端到端验收口径不变。以下原审查是实施前快照。

## 结论与范围

当前表并非全部属于平台不可缺少的运行模型。按数据库身份逐表核对后：**190张逻辑表中，166张有保留依据，3张为退役候选，2张为旧模型整体收尾候选，5张存在归档生命周期缺口，1张是有意维护的默认值审计目录，13张属于本地演示/验收环境。**这些分类不能直接作为删表清单。

- 工作树：`~/.codex/worktrees/workflow-daily-integration/file-batch-system`，分支 `feature/workflow-daily-integration`，HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8`；包含当前暂存、未暂存和未跟踪工作，不以冻结主分支代替开发工作树。
- 实库：本机 PostgreSQL 17.11；平台库 `batch_workflow_jar_20261021_platform`、业务库 `batch_workflow_jar_20261021_business`；目录快照为2026-10-24 12:31:08 +08:00，平台最新成功迁移V346，与当前源码最高版本一致。
- 使用 `project-engineering-review` 总控，结合 `database-migration-safety`、`module-config-boundaries`、`script-sql-governance`；报告按 `documentation-governance` 留档。
- 用户已确定只支持空库部署，不提供旧数据库兼容。后续退役应同步移除当前消费者及初始化/治理依赖，不以历史兼容为理由永久保留旧模型。
- 本轮只有只读目录/统计查询、源码与调用链审查、文档记录；没有DROP、DELETE、迁移重写、业务数据读取、提交或发布。既有变更保留。

逐表分类、说明、列数、子分区数、引用证据、外键入边及统计见[190表清单](database-table-necessity-catalog-2026-10-24.csv)。当前事项统一见[TODO Master](../plans/todo-master.md)。既有数据库风险仍由[数据库整合整改方案](../plans/database-integrity-index-partition-remediation-2026-10-18.md)及[引用完整性方案](../plans/database-reference-integrity-remediation-2026-10-18.md)管理，本报告不关闭其验收项。

## 可操作发现

优先级表示当前工程影响；没有把可维护性债务直接认定为正在发生的运行故障。实施次序另见本报告末尾。

### TBL-01 / P2：5张冷表只有结构，尚无完整归档消费链

涉及：

- `archive.atomic_task_config_archive`
- `archive.custom_task_type_registry_archive`
- `archive.data_quality_check_archive`
- `archive.data_quality_rule_archive`
- `archive.workflow_definition_version_archive`

当前静态链路核对未找到上述冷表的生产搬运、冷读回退或恢复实现。能确认的是DDL建表与 `ArchiveSchemaDriftCheck` 动态拼接热表名加 `_archive` 的列/类型检查；**结构漂移检查不执行归档。**当前数据库函数也未检出这些冷表写入。

证据：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/archive/ArchiveSchemaDriftCheck.java:65–79,88`；`db/migration/V118__data_quality_reconciliation.sql:84–87`、`V159__create_custom_task_type_registry.sql:57`、`V165__create_atomic_task_config.sql:56`、`V167__create_workflow_definition_version.sql:62`。现有[索引审查](../plans/database-index-review-remediation-2026-10-18.md)也登记过这5张表的生命周期缺口，本轮重新核实，未视为新发现的已实施能力。

影响：表存在容易被误认为已保障配置历史/质量证据留存；若后续热清理依赖这种假设，将缺少冷证据。当前未复现热数据丢失，不能将此写成正在发生的数据事故。

处理：由配置、DQ、Workflow与归档负责人分别裁定。需要冷存的表补搬运条件、幂等、热删除前置条件、同租户回查及恢复验证；不需要的预留镜像从新部署结构、种子/策略、漂移清单和文档一并移除。不可仅因冷表为空删除；也不为凑齐镜像而强行开发归档。

验收：空库建库后，若保留，证明终态/版本可搬运、重复执行无重复、冷读身份完整，故障时不误删热证据；若退役，证明热表保留策略明确、配置和漂移守护不再依赖冷表。

### TBL-02 / P3：旧时间轮运行态表可退役，但有真实外键依赖

`batch.trigger_runtime_state` 来自V67时间轮模型。当前Trigger使用Quartz JDBC JobStore、Workflow调度及 `scheduled_occurrence`，生产源码未检出此旧表的读写；数据库函数未检出引用。当前统计估算0行仅作旁证。

但实库 `batch.trigger_misfire_pending.trigger_runtime_state_id` 仍有指向它的外键，带 `ON DELETE CASCADE`，并保留旧唯一约束。当前 `TriggerMisfirePendingMapper.insertPendingWithStatus` 已按 `(tenant_id, workflow_definition_id, scheduled_fire_time)` 写入/去重。**旧运行态表可列退役候选，补跑待审批表仍然必需。**

证据：`db/migration/V67__create_trigger_runtime_state.sql:14`；`batch-trigger/src/main/resources/application.yml:32–50`；`batch-trigger/src/main/resources/mapper/TriggerMisfirePendingMapper.xml:26–36`；本轮实库外键目录。旧表只在本地reset/seed检查等运维资产中有残留。

处理与验收：Trigger负责人清理旧FK、旧列/唯一约束及reset/清理/检查引用，再从空库结构退役旧表；禁止用 `DROP ... CASCADE` 顺手删除仍活跃的依赖。新库验证定时fire、readiness重检、misfire申请/审批/补跑及重复投递。

### TBL-03 / P3：补偿checkpoint表尚未接通执行写入

`batch.compensation_checkpoint` 有DDL、错误字段、索引和租户清理SQL，但当前生产源码和数据库函数未检出执行写入/读取。`docs/runbook/compensation-cleanup.md:3,64` 明确说明 `DefaultCompensationService` 不写此表，不得依赖它判断步骤已提交或回滚。当前估算0行与此一致，但不是判断依据。

证据：`db/migration/V63__compensation_checkpoint.sql:23`；`scripts/db/wipe-tenants.sql:30`、`scripts/db/wipe-non-system-tenants.sql:41`、`scripts/sim-4day/sql/clean-platform-runtime.sql:24`。

处理：当前需求若不包含持久化Saga/checkpoint，建议退役这张预留表及相关清理/说明；不要为保住一张表新增补偿体系。`compensation_command` 是仍在使用的补偿命令主记录，须保留。验收应证明空库建库和命令补偿正常，不把本表退役误报为已实现副作用逆向回滚。

### TBL-04 / P3：平台库process_staging是旧副本，业务库同名表在使用

两库的 `batch.process_staging` 不能靠名称合并判断。平台库为V75旧普通表；当前PROCESS的Mapper和COMPUTE/COMMIT/清理绑定 `processBusinessSqlSessionFactory` / `processBusinessDataSource`，实际使用业务库的分区表及 `process_staging_generation` 代际围栏。

证据：`db/migration/V75__add_process_staging_table.sql:14`；`batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/BatchWorkerProcessApplication.java:26–35`；`batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/config/BusinessDataSourceConfiguration.java:90–105`；`batch-worker/process/src/main/resources/mapper/business/ProcessStagingMapper.xml:53,98`；`scripts/db/business/create_biz_tables.sql:30–33,48,91`。本轮统计中平台副本估算0行/0插入，业务表累计插入与删除均超过15000，只作为数据源定位旁证。

处理：退役平台库旧副本及平台reset、迁移说明、RLS/表目录中相关假设；**保留业务库的2张PROCESS基础表与其分区/清理/围栏。**验证须使用平台/业务双库隔离，覆盖COMPUTE→VALIDATE→COMMIT→FEEDBACK及崩溃恢复，不能用单库fixture验证代替。

### TBL-05 / P3：旧租户Pipeline定义两表可收尾，须连消费者一起退役

`batch.pipeline_definition`、`batch.pipeline_step_definition` 不再是新任务执行的定义来源：`AbstractPipelineStepExecutionAdapter` 按类型查 `SystemPipelineCatalog` 并生成步骤，`pipelineDefinitionId` 为null；Controller的创建、更新、开关入口直接拒绝旧写入。

但当前仍有GET列表/详情、Service/Mapper CRUD残留、Worker缺少内联步骤时的DB回退、seed/清理SQL、租户配置revision触发器；两表之间仍有外键。配对前端 `../batch-console/src/api/system.ts:143–174` 也保留接口封装。因此是**旧模型整体退役候选，不能宣称已经无引用或直接删表**。

证据：`batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:170–195`；`AbstractStageExecutor.java:221–241`；`batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformPipelineDefinitionRepository.java:39–46`；`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/web/ConsolePipelineDefinitionController.java:45–96`；`db/migration/V6__create_file_tables.sql:45–78`。

处理与验收：在空库新契约下同时移除旧API/前端调用/生成契约、步骤DB回退、seed/清理/治理依赖和两表。保留 `pipeline_instance`、`pipeline_step_run`、`pipeline_progress` 运行事实，以及实际仍在使用的 `step_registry`。验证四类文件Worker固定阶段、配置包、Console和续跑；不实现旧客户端或旧载荷兼容层。

### TBL-06 / P3建议：默认值目录属于有意冗余，不是运行配置源

`batch.batch_runtime_default_parameter` 仅有迁移种子与说明，应用不从此表加载生效配置。`docs/design/runtime-default-parameters.md:3–5` 明确约定其用途是可审计基线目录，实际配置来自Spring YAML/环境变量。因此不能凭无生产Mapper就定为废表。

当前目录需要与YAML、文档人工多份维护，且该文档仍写V23，而实际创建迁移为 `V24__batch_runtime_default_parameter.sql:8`，存在可定位的来源漂移。建议先裁定是否确有DB内审计需求：保留则从权威配置生成/校验目录；不需要则从新部署移除，保留可审计的版本化配置来源。`batch.system_parameter` 有实际动态配置消费，不能一起删或直接合并。

### TBL-07 / P3建议：通知配置有功能交集，但两路都在运行

`webhook_subscription` 与 `subscription_rule` + `notification_channel` 都支持Webhook，但当前 `ConsoleWebhookDomainEventListener.java:36–58` 会调用两路分发器。它们是有当前消费者的并行模型，不能作为“没用的表”删掉。

后续若决定统一通知入口，再裁定订阅身份、筛选、凭据、投递日志、重试和终态回执语义，并同步Console。不能因表名相近合并，也不能从本轮静态审查断言已经发生重复通知。

## 逐表分类与数量

| 分类 | 逻辑表数 | 说明 |
|---|---:|---|
| 有保留依据 | 166 | 当前源码、框架、数据库约束/触发器或已实现的运维/归档用途；不表示每个部署必须启用全部功能 |
| 退役候选 | 3 | 平台库 `compensation_checkpoint`、`trigger_runtime_state`、`process_staging`；处理依赖后再退役 |
| 旧模型收尾候选 | 2 | 旧Pipeline定义两表；整体清消费者 |
| 归档生命周期缺口 | 5 | 结构镜像不等于已实现归档；保留或退役待裁定 |
| 审计目录冗余待裁定 | 1 | `batch_runtime_default_parameter`，有明示审计用途 |
| 环境专用 | 13 | 平台SIM导入账本1张 + 业务 `biz.*` 演示/验收表12张 |
| 合计 | **190** | 每个数据库/schema/逻辑表一行 |

平台库为 `batch` 134 + `archive` 31 + `quartz` 11 =176逻辑表；业务库为 `batch` 2 + `biz` 12 =14逻辑表。共有10个分区根和107个子分区，因此目录中的表对象数为297（190+107），不能将分区重复算成业务表。分区父表也不是独立存储全部数据的一份副本。

12张环境业务表为 `customer_account`、`import_stage2c_customer`、`process_account_summary`、`process_event_copy`、`process_order_event`、`process_stage4_source`、`process_stage4_target`、`risk_alert`、`risk_score`、`settlement_batch`、`settlement_detail`、`transaction`。`create_biz_tables.sql` 明示为导入/导出演示模型，其他场景表由fixture/验收入口创建；平台 `sim_tenant_fixture_import_state` 由 `scripts/sim/03-import-tenants.sh:66,132,143` 读写/建表。保留本地验收所需表，生产新部署只按真实业务契约初始化，不能直接在当前运行库删除它们。

## 已排除的误判

- 热/冷表：同一数据库中按列名、类型、非空性比较发现28组完全相同列结构，均为热表/归档镜像；未发现其他完全相同列结构组。结构不同不证明语义不重复，本轮还单独核对了Pipeline、通知、审批和PROCESS模型。
- `calendar_group`：无应用管理入口，但两处同租户FK引用，GROUP假期按 `group_code` 参与业务日推导；`calendar_dependency` 也被开批调度读取。手工/初始化配置不等于闲置。
- `scheduled_occurrence_supersession_history`：Java无直接写SQL，实际由 `scheduled_occurrence` 上的触发器写入；本轮读取当前数据库函数体及触发器目录确认。不是死表。
- Quartz11表、`flyway_schema_history`、`shedlock`：框架按约定消费，不能要求每张都有业务Mapper；当前Quartz仍是JDBC JobStore。
- `worker_report_outbox`：PG/SQLite是受支持的两种存储选择，默认SQLite不代表PG后端退役；`stateful_backend_binding` / `stateful_backend_cutover_history` 是该类有状态后端身份与切换围栏。
- `approval_command` 与 `config_approval`：前者支持运行操作/补跑/重放审批，后者支持配置审批与发布，状态及副作用不同。
- Workflow/Pipeline：前者管理跨Job编排，后者承载单Job文件处理阶段；运行表不可因定义模型退役一起删除。定义、运行、预览令牌、幂等账本、投递记录与终态回执也不是同一事实的随意复制。

## 覆盖矩阵与证据边界

| 范围 | 本轮证据 | 限制 |
|---|---|---|
| 两库190/190逻辑表 | 当前pg_class、列、约束、触发器、分区、对象说明、按父子汇总的统计；逐表CSV无重复/遗漏 | 元数据快照与源码采集非原子，活跃开发/运行可能继续变化 |
| 107子分区 | 归属于父表统计，不作为冗余独立业务表 | 未重新评估分区预建/淘汰与容量 |
| 源码/SDK/运维/fixture | 对生产src/main、SDK、scripts、db、deploy/config及测试数据分组索引，候选人工追踪调用链 | CSV中的literal_hits只是定位信号；不是调用次数、写入证明或无用证明 |
| 动态间接使用 | 框架表前缀、归档命名拼接、当前DB函数/触发器、跨库Mapper绑定与FK入边 | 未读取任意部署外部脚本、第三方插件或目标环境查询历史 |
| 生命周期及恢复 | 对候选核对建表/写读/清理/归档与手册；复用既有专项待办 | 没有执行故障注入、归档搬运、恢复或端到端业务测试 |
| 实库统计 | `pg_stat_user_tables`，包含分区子表统计；两库 `stats_reset` 均为NULL | 观察窗口不能确定；零行/零扫描/零插入均不构成删除依据 |
| 安全/租户 | 只读会话、只读事务、15秒超时；检查清理依赖与表身份 | 未重做全库RLS、性能、安全/迁移验收 |

## 建议实施次序与验收

1. 先处理已有归档生命周期缺口，确定各表是需要冷存还是可移除预留结构，不先开放未经验证的热清理。
2. 新库结构收尾：3张退役候选与2张旧Pipeline定义表分别按所有权清理消费者/约束/运维引用；不引入旧库兼容。上述为建议实施范围，本轮没有实施。
3. 保留本地环境fixture；生产初始化入口明确隔离示例业务模型。默认值审计目录、通知双模型属于后续裁定/收敛建议，不扩张为立即重构。
4. 实施后执行迁移lint、空库全量建库、schema/归档漂移检查、相关模块测试与双库端到端验证；分别报告本地与正式环境证据，不关闭无关数据库专项。

本轮实际命令与结果：

- `git worktree list --porcelain`、`git branch --show-current`、`git rev-parse HEAD`、`git status --short`：确认上述开发工作树与dirty范围，主分支未改动。
- 经 `scripts/lib/env-common.sh` 载入本地连接配置，执行临时只读目录查询 `/private/tmp/bfs-table-audit-20261024.py` 和函数核对查询 `/private/tmp/bfs-table-audit-20261024-functions.py`：两库均成功、`transaction_read_only=on`；未输出密码或业务载荷。目录SQL使用 `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`，查询 `pg_class/pg_namespace/pg_attribute/pg_constraint/pg_trigger/pg_stat_user_tables`；函数核对读取 `pg_proc`，不执行所读取函数。
- 临时分组索引 `/private/tmp/bfs-table-audit-20261024-index.py`，结合逐候选 `rg` 与调用链读取：完成190逻辑表的证据归类。机械计数用于导航，人工结论见上文。
- 清单校验：190个唯一数据库/schema/表身份、107个子分区、分类合计190，每行具有可定位证据。
- `python3 scripts/ci/check-docs-structure.py`：通过。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：通过（dated=284、undated=281、current-dated-legacy=0）。
- `git diff --check -- docs/audit/README.md docs/plans/todo-master.md docs/changelog.md`：通过；新报告/CSV另行检查无行尾空白、路径和行号有效、190表分类与分区计数一致。
- 未运行产品编译、单元/集成或端到端测试；没有执行候选退役、归档搬运及恢复验证，不将审查等同于运行验证。
