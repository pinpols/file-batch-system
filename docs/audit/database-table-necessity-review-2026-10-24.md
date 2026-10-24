# 数据库表必要性与冗余审查（2026-10-24）

## 后续实施范围（2026-10-24）

用户已授权退役3张候选表及2张旧Pipeline定义表，同时核实并收尾5张冷镜像的生命周期。以下审查结论保留其采集时点；实施以新库不生成退役结构、当前代码/契约/脚本无依赖及真实空库验证为完成条件。业务库PROCESS和Pipeline运行事实继续保留；不修改当前运行数据库，不提供旧库兼容。进度：实施中，尚未宣称验收通过。

## 结论与范围

当前表并非全部属于平台不可缺少的运行模型。按数据库身份逐表核对后：**190张逻辑表中，166张有保留依据，3张为退役候选，2张为旧模型整体收尾候选，5张存在归档生命周期缺口，1张是有意维护的默认值审计目录，13张属于本地演示/验收环境。**这些分类不能直接作为删表清单。

- 工作树：`/Users/dengchao/.codex/worktrees/workflow-daily-integration/file-batch-system`，分支 `feature/workflow-daily-integration`，HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8`；包含当前暂存、未暂存和未跟踪工作，不以冻结主分支代替开发工作树。
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
