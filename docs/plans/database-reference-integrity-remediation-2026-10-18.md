# 数据库引用完整性逐表核对与整改方案

> 路径表示约定（2026-10-24 文档治理）：历史命令中的本机绝对路径改以 `${BACKEND_WORKTREE}`、`${SQUAWK_BIN_DIR}`、`${NODE_BIN_DIR}` 表示，分别指原后端工作树、Squawk 可执行目录和 Node 可执行目录；不表示重新执行验证，实际参数与输出仍以原日志和快照清单为准。

> 核查日期：2026-10-18，持续更新至2026-10-19。状态：**结构盘点完成；独立 Workflow JOB 关联、pipeline_progress 位点归档与父子并发保护、Workflow 节点到 JobInstance 的热引用并发保护、catch-up audit 与 occurrence 身份绑定，以及 replay entry、preview token、batch-day waiting launch 与父记录的租户/身份约束已本地整改并定向验证；RT-5 多上游配置及普通角色事务已局部整改验证；file_channel_health→file_channel_config 同租户引用已由 V315 与定向 PostgreSQL 回归补强；Workflow 日历与两类 recurring expectation 的父子身份已由 V316、应用守护和真实 PostgreSQL 并发回归局部闭环；job_task→job_partition 已由 V317 补同租户/同 JobInstance 复合外键，job_partition→job_instance 已由 V318 补租户/实例/业务日复合外键及应用日期校验并真实 PostgreSQL 定向验证；Step 与 Task 的租户/实例/可空分区已由 V319 原生外键和应用错误映射实施并定向验证；JobTask→JobInstance 无分区父身份已由 V320 局部闭环；asset_freshness_policy→job_definition 已由 V321 与 Console 同租户写入守护局部闭环；V322 JobExecutionLog 分区身份及历史引用边界、V323 的 TriggerRequest→JobInstance 及 TriggerRequest/WorkflowRun 历史弱引用身份已局部实施并定向验证；V324 为热 `job_partition.source_file_id` 增加同租户 `NO ACTION` 复合外键、应用并发错误映射和 SIM 清理保护，冷归档继续保留历史弱引用；V325 为 `data_quality_check.job_instance_id` 保留历史弱引用并补应用/数据库同租户行锁与父身份冻结，父归档删除仍允许，DQ 冷表搬运和回查仍开放；REF-04/05/DBSAFE-3 其余关系整改和联合验收仍开放**。本专题属于系统问题整改，纳入 [Workflow 与每日连续性总方案](./workflow-daily-integration-plan-2026-10-14.md) 的 I1/I4/I5/I6、R20/R33/R36；唯一当前状态见 [TODO Master](todo-master.md) 的 `ALIGN-DB-REF`。已实施切片与仍开放范围分别见 §2.4–2.49，不将局部证据混作整体验收。

> **最新配置引用增量（V321，2026-10-19）**：`asset_freshness_policy(tenant_id,asset_code)` 现在引用同租户 `job_definition(tenant_id,job_code)`；Console 写入先用 `FOR KEY SHARE` 锁定同一 Job，自然键不匹配时返回稳定 `INVALID_ARGUMENT`。策略仍允许在产物生成前配置，也不以 Job enabled 状态替代引用身份；物理删除 Job 级联删除派生策略。真实 PostgreSQL/服务定向回归15/15通过，V321 单文件 Squawk 0 issues。整分支迁移门禁仍因 V319/V320 的6条告警未通过，详见 §2.44 与整合验证矩阵 V321。

> 配套审查：[索引逐表方案](./database-index-review-remediation-2026-10-18.md)与[分区生命周期方案](./database-partition-lifecycle-remediation-2026-10-18.md)分别记录查询/索引及扩建/清理/叶子RLS问题；[数据库整改总方案](database-integrity-index-partition-remediation-2026-10-18.md)统一 P0/P1 顺序及应用/数据库联合验收，本专题保留逐表证据和 REF 编号。

## 1. 结论与范围

不统一删除外键，也不对所有 `*_id`、`*_code` 补外键。当前需做三种处理：

1. **活跃且同生命周期的强从属关系补齐**：优先升级只检查 ID 的外键为租户复合外键；同租户还不够时，增加同 Workflow、同 JobInstance、同 Pipeline 的归属约束。
2. **分区、归档、快照、多态及跨库关系按语义治理**：保留合理弱关联，明确写入、父对象删除、冷热查询、幂等留存与恢复边界。不能以“Java 有检查”直接宣布等效外键。
3. **修复已经发现的应用路径缺口**：独立 Workflow 归档漏 JOB 关联、Pipeline progress 漏归档/删除、Outbox 复制和删除之间的并发窗口，以及 Workflow 引用校验未覆盖启用入口。这些需要实现改动，不只是补测试。

覆盖今天独立验收实例的所有非系统 schema 普通/分区/外部表；没有按表名过滤。逐表目录同时扫描全部列、自然键、JSON/多态引用、实际外键/主键/唯一键/索引/触发器/RLS、分区父子关系，以及代码中写入、读回、删除、归档的替代校验。没有向其他历史数据库执行迁移，也没有将历史库结构混作当前结构。

### 1.1 数据库实测基线

| 对象 | 实测值 |
|---|---|
| PostgreSQL | 17.11；本机隔离实例 `batch-wdi-s20-postgres-primary`，宿主端口 25432 |
| 平台数据库 | `batch_wdi_s20_20261018_platform`；目录事务时间 **2026-10-18 08:54:38.069825 +08:00** |
| 业务数据库 | 同实例 `batch_business`；目录事务时间 **2026-10-18 08:54:38.362813 +08:00** |
| 平台库逻辑表 | `batch` 129 + `archive` 26 + `quartz` 11 = **166** |
| 业务库逻辑表 | `biz` 9 + `batch.process_staging` 1 = **10** |
| 总覆盖 | **176 张逻辑表 + 107 张物理分区子表 = 283 张物理关系**；平台子表 91，业务子表 16 |
| 逻辑外键 | **51 条**：平台 batch 45、Quartz 5、业务 biz 1；按 `conparentid=0` 去除分区自动派生项。业务 settlement_detail 的 5 条目录外键不是 5 种业务关系 |
| 约束/索引状态 | 目录中未发现未 VALIDATE 约束或 invalid 索引；这不证明缺少的约束已补齐，也不证明业务数据没有逻辑错绑 |
| Flyway | 286 条成功历史 = 285 条 SQL + 1 条 V1 BASELINE；最高 V291，失败 0。285 条 SQL 的文件名和按 Flyway 行规则计算的 CRC32 均与当前源码相符；V1 是 baseline，不能表述为本轮执行过 V1 SQL |
| 旧环境对照 | 默认 `batch_platform` 为 V269，上一轮 `batch_wdi_sim_v6_20261017_platform` 为 V287；只核对版本，不纳入本次 V291 逐表结论 |
| 证据边界 | 以两个独立 READ ONLY/REPEATABLE READ 事务采集元数据，不读取业务行。后续补充查询时容器曾不可用，之后再次出现并确认重建；索引09:06、权限09:08和只读计划09:13分别记录自身实例时间，不能混成连续统计或重复验收 |

[原始结构清单 CSV](../audit/database-reference-catalog-2026-10-18.csv) 每个物理表一行，保留所属数据库、全部列/类型/空值约束、全部约束定义及父约束 OID、索引定义、用户触发器、RLS 与分区边界。107 个子表逐一列出，正文按逻辑父表归纳，避免将派生 FK 重复计数。CSV 为元数据，不含业务行或连接口令。

源码基线为 `feature/workflow-daily-integration` 的 `ee01e97515b04fd167ed17977535ec5cab8572f8` **加未提交工作区**；HEAD 单独不能重现本报告。09:00:02 对 `batch-*`、`db/`、`scripts/` 内 4,547 个 Java/XML/SQL 文件按路径排序及文件 SHA256 汇总，内容摘要为 `8af0bb7c33ed7d1baa305ac0d298a45d722c59c95aac567fd2500f006cb41257`。工作区有其他任务并行修改，后续实施须复核相关文件，不把本报告当永久不变的现状。

CSV SHA256：`a2104eb905b5404ed6d956f48fee35427982f4ddfd5acb4219efa90cce0f112a`。采集结果原件为本机临时目录 `/tmp/fk-audit-20261018/` 的 `platform.json`、`business.json`、`flyway.json`；长期可审阅事实保存在上述 CSV 和本方案。

### 1.2 必须修正的判断

- **PostgreSQL 17 支持分区表外键**。实际业务库 `settlement_detail → settlement_batch` 就有分区表复合 FK。当前 Job/Outbox 引用的限制是：父表唯一键含 `biz_date` / `created_at`，子表往往只有 `id`，不满足引用键条件；不是所有分区表都不能加 FK。依据：[PostgreSQL 17 分区限制](https://www.postgresql.org/docs/17/ddl-partitioning.html#DDL-PARTITIONING-DECLARATIVE-LIMITATIONS)、[外键与唯一键规则](https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-FK)。
- V290 的 `workflow_node_run` 触发器确实在 INSERT/指定列 UPDATE 时查同租户 Job，但没有父行 `FOR KEY SHARE`，也没有父删除/改租户的对应保护，不能称为完整外键替代。父对象归档后允许不允许引用热表消失，必须按运行/历史生命周期明确。
- RLS 限制可见/可写的行，单列 FK 保证所指 ID 存在，两者都不自动证明父子同租户、同实例或同文件。`SELECT EXISTS`、应用读后写、唯一去重键各有覆盖范围；并发插入与删除必须单独证明。
- 新库部署免除旧系统数据转换，不免除新模型内部约束、日常归档、备份恢复、分区淘汰和运维 SQL 的完整性。没有必要为补外键擅自引入旧库迁移工程。

## 2. 整改任务、优先级与关闭条件

以下是建议实施顺序；**没有把所有防御补强都升级为发布阻断**。P1 表示涉及当前主链/数据保全、应先处理的代码缺口或高风险静态路径；P2 为约束补强/生命周期完善；需业务裁定项先保持现有语义。每项关闭都需要实现及相应证据，文档完成不等于整改完成。

| ID | 优先级 / 当前状态 | 整改内容与边界 | 所有者、验收与主方案映射 |
|---|---|---|---|
| REF-01 | P1（JOB关联）/ P2（位点生命周期）本地切片已实现与定向验证；联合验收未关闭 | 独立 Workflow 归档已保留 `workflow_node_run.job_instance_id`；V292 已补 `pipeline_progress` 父身份、归档、并发围栏及热子先父清理。继续检查两条 Workflow 归档路径、完整运行树和冷热字段映射，不把 SuccessInstance 路径的局部闭环推定为所有归档入口完成。 | Orchestrator/归档；真实PG逐行快照对照、独立Workflow与Job归档、失败回滚、无热孤儿、冷查询可追JOB；I4，R20/R33 |
| REF-02 | P2（主链前置）/ Outbox 局部实现及真实 PG 并发验证；联合验收开放 | V306 为两张热 Outbox 子表增加 `outbox_event_created_at`，建立 `(tenant_id,id,created_at)` 父唯一键及三列 `NO ACTION` 原生 FK。应用 delivery/retry 写入以同一三列身份 `FOR KEY SHARE` 写热子表；父已归档时精确身份回退到冷子表，热冷父均不存在时以 SQLSTATE 23503 失败。归档候选 `FOR UPDATE SKIP LOCKED`，父子复制/删除在同一事务内完成；迟到子写被父锁串行化并在归档提交后写冷表。cleanup SQL 按同一身份先删两张子表再删父事件。直接 SQL 和双事务竞争已有真实 PostgreSQL 局部证明，合并回归31/31并有分区约束目录断言1/1通过（共32个不同用例）。完整运行树、其它分区父子淘汰、所有生产写/删旁路、故障恢复、回滚和联合验收仍未闭环；不据此关闭 REF-02/DBSAFE-3/RT-6/R20/R33/R36。 | Orchestrator/数据库；已验证直接 SQL 缺父/错租户/父删除拒绝及归档与迟到 delivery/retry 双事务交错；仍需覆盖全局子写/父更新、其它运行树/分区淘汰、失败恢复、冷热查询、清理入口和完整主链；I1/I4，R20/R33/R36 |
| REF-03 | P2（主链前置）/ 常规写入、导入和运行准入已局部整改，联合验收开放 | Console 写入、诊断、定义/日程启用及配置导入复用 JOB 引用检查，根创建前再次查同租户启用 Job；缺失引用和查询故障失败关闭。草稿可继续编辑、停用不受阻。系统自动创建的受管图、父状态并发和其它生命周期入口仍待核对，证据见§2.6。 | Console/Workflow；缺失/异租户/停用目标、草稿可保存、启用失败零写入、启动失败零子任务；I2/I5，R20/R28/R35 |
| REF-04 | P2 / V293/V294/V308–310/V317–320/V322–323 已局部实施，其余引用及联合验收开放 | 图节点/边/容量等待、原请求等待、replay session/token 等同租户引用见各实施切片；V317 将 Task 的可空 partition 绑定到同租户、同 JobInstance，V318 将 Partition 绑定到含业务日的分区 JobInstance 父键，V319 将 Step 绑定到 Task 的租户/实例/可空分区完整身份并保留级联；V320 再将无分区 Task 绑定到同租户 JobInstance 与业务日；V322 将带分区的 execution log 绑定到同租户/同实例分区，并保留分区删除后的审计行。单独 `job_execution_log.job_instance_id` 因热父可归档删除而保留为历史弱引用，新写入由应用 mapper 按租户/ID锁定父实例；V323 为 `workflow_run.related_job_instance_id` 与 `trigger_request.related_job_instance_id` 保留可随父归档删除的历史弱引用：关联建立时分别锁定同租户父实例，已有历史引用时禁止父 `id`/`tenant_id`/`biz_date` 改写，父热行删除仍允许并保留历史 ID；TriggerRequest 的无调用方 mapper 直写入口已删除。并核验同租户父实例，关联存在时禁止改写父 ID/租户/业务日。两者均不冒称为强数据库 FK。应用构造与精确 FK 冲突映射、冷热镜像与种子/归档回归分别见 §2.40–2.46。其它逐表关系仍需按矩阵完成，不以单条子关系闭环推定完整父树存在。 | 各写入模块/数据库；同租户正例、错租户/错实例/NULL错配负例、批量原子性、父更新删除动作和真实双事务屏障；I1/I2/I4，R20 |
| REF-05 | P2 / 局部数据库实现与验证，整体待实施 | 活跃配置关系逐表补FK或生命周期闸门。`calendar_dependency` 的同租户上下游自然键FK和数据库防环触发器已由V302实现并通过真实PG类验证；目前未发现生产应用写服务，数据库为实际写入完整性的权威边界。`calendar_holiday` 已由V303增加 `(tenant_id, calendar_id)` FK，Console写路径按请求租户解析日历后写相同租户/父ID。V304再为日历和GROUP假期补同租户 `calendar_group` FK，并约束GROUP假期的代表日历确属该组；目前未发现日历组生产写Service/API，DB约束是直接写入边界。V311将 `batch_day_catchup_control` 绑定同租户 `(tenant_id,calendar_code)`，与应用启用日历预检身份一致；父键/删除由 `NO ACTION` FK保护，竞态拒绝映射为409。V315将 `file_channel_health` 绑定同租户通道配置；软删除保留配置行和健康状态，物理删除级联清理派生健康状态，且网关不会把健康快照写失败误报为外部投递失败。V321 将 `asset_freshness_policy(tenant_id,asset_code)` 绑定到 `job_definition(tenant_id,job_code)`；Console create/update 同一事务先以 `FOR KEY SHARE` 检查和锁定 Job，缺失或错租户自然键返回 `INVALID_ARGUMENT`，数据库 FK 拒绝直接 SQL 孤儿并在 Job 物理删除时级联清理策略。disabled Job 仍可配置策略，且不依赖尚未生成的 `data_asset`；这些是所选生命周期语义，不由 FK 推断启用状态。其它配置关系仍待做。FK不能防软删除/禁用、owner错配，要分别定义应用业务规则；未来新增组写入口须沿相同租户/组/代表日历身份验证，并映射数据库冲突。 | Console/日历/配置；全入口、导入/复制/启用、软删、并发配置改动、父子清理顺序；I5/I6，R20/R34/R35 |
| REF-06 | P2 / 局部弱引用写入保护已实施，冷热生命周期仍开放 | 审计/结果/重放/文件/通知及26张冷表维持合理弱关联；V325 为 `data_quality_check.job_instance_id` 提供同租户应用/数据库并发守护并冻结已引用父身份，但不加热 FK，父归档删除仍允许。没有生产搬运路径的冷表决策、热冷解析与保留期仍须明确。 | 归档/文件/运维；完整血缘可读、历史保留、损坏引用阻断并告警、各允许清理入口一致；I4/I5，R14/R20/R33/R36 |
| REF-07 | P2；涉及当前运行的路径前置 / 待裁定并实施 | placement支持catalog/config两种来源，不能直接统一FK；补选定模式的合法性和删除保护。resource_tag、approval/compensation/retry、JSON目标按类型查同租户资源；租户注册/system例外、AI username代际、业务客户/账号外部语义分别确认，禁止为了补FK改变既有契约。 | 配置/权限/业务所有者；未知来源、资源删除、同名重建、外部先到后到、跨库路由负例；I5/I6，R20/R34 |
| REF-08 | P2 / 待实施后防退化 | 建立引用策略清单及结构守护，记录每个有意不加FK的替代责任、允许孤儿条件、读/写/删入口、留存和恢复规则。修复后再补守卫，不用新增规范代替代码修复。归档测试必须覆盖所有生产归档入口及每个字段。 | 数据库/测试/文档；空库迁移、非superuser强制RLS、schema对照、回归负例和当前运行包验收；I1/I6，R20/R36 |

实施批次：**A** 先 REF-01/02/03 及 REF-04 核心运行归属；**B** 复合 FK、活跃配置关系和父子清理顺序；**C** 历史/跨库/多态与冷表完整生命周期；**D** 守卫、文档同步和同一版本联合验收。若 A 的复现推翻静态风险，应记录反证和机制，不为凑整改项改写代码。

### 2.1 迁移和验收要求

- 使用新的 Flyway 迁移版本实现已确认约束，不回写本次实测已应用的 V289–V291。当前发布仍按 ADR-048 新库部署；上线后的正常升级需另评锁、回填及恢复窗口。
- 先明确父组合唯一键、可空语义、删除/更新动作、子索引和导入顺序，再加 FK；`MATCH SIMPLE` 对含 NULL 的组合键可能不检查该引用，必填关系还需 NOT NULL/CHECK。
- 历史/审计不做级联删除；活跃聚合内 CASCADE 仅在整个生命周期确实一致时保留。`ON DELETE SET NULL` 不应顺带清空租户身份，复合键的清空列必须明确。
- 分区 FK 的锁、校验/附加分区与分区淘汰按 PostgreSQL 17 实测；`NOT VALID → VALIDATE` 不能机械套用所有分区场景。所有批量 INSERT/导入/恢复入口需验证，禁止只覆盖 Console 单条入口。
- “完整替代校验”须同时覆盖写入存在性与归属、并发父删/改、归档清理、冷热读取、巡检修复、所有可写入口。无法覆盖时保留为开放缺口，不能用应用权限或RLS说明冲销。
- 动态证据按层级记录：SQL约束负例、真实PG双事务、应用API/批量导入、主链/归档E2E、当前实例运行验证。存量测试是否存在与本轮是否执行分开报告；本轮没有执行这些测试。

### 2.2 应用层守护推进与动态验收（2026-10-18 补充）

本节把逐表盘点中需要应用代码负责的约束收敛成可实施任务。以下结论依据当前源码静态追踪；本次定向动态测试未启动，不代表问题已复现或验收通过。实现时先复核目标 worktree，因为并行改动可能改变调用路径。

#### 实施任务

1. **REF-03 / RT-2：统一 Workflow JOB 引用准入。**复用现有领域校验器，按 `(tenant_id, related_job_code)` 校验 Job 存在、启用，且节点类型为 JOB。Console 写入、诊断及配置导入共用 `WorkflowDefinitionDagInspector`；Orchestrator 保持自身 Mapper/领域模型，在根创建前由 `WorkflowGraphValidator` 重新查权威状态，不为共用校验器引入跨模块实现依赖。草稿允许未就绪目标但仍遵守非空编码及拓扑要求；保存启用态、显式启用定义或日程、批量导入发布和实际 launch 必须失败关闭。独立 validate 接口不能成为唯一执行入口。持久化失败时不得留下部分 definition/node/edge 或初始子任务；系统自动创建的受管图、父状态并发与其它生命周期入口仍逐项验收。
2. **RT-4：收紧等待请求写入。**`job_dependency_waiting_launch` 写入要绑定同租户原 trigger request、目标 Job 和业务日；状态回写影响行数为 0 必须作为冲突/失败处理，不能只记日志后继续。`workflow_launch_wait` 保留当前 `INSERT SELECT` 对同租户 request/Workflow 的匹配守卫，并验证冲突重试和请求终态转换。
3. **RT-5：收敛所有 Job 依赖写入口。**Console 继续校验上游存在、自依赖和环；旧 `depends_on_job_code` 同步触发器、配置导入、迁移/运维 SQL 等旁路必须由数据库外键阻断悬空上游，或改为统一受校验的写入口。依赖图环检查须在同租户范围串行化或采用等效并发控制，并用双事务交错测试证明两个分别合法的更新不能合成环。
4. **RT-6 / REF-02：封闭父对象并发归档窗口。**`workflow_node_run.job_instance_id`、Job 运行子树和 Outbox 子记录不能只做子侧 `EXISTS`。先确定归档/分区清理期间允许保留的热冷引用，再统一父行锁、归档状态栅栏或稳定身份目录；父关键身份更新/删除与子写入必须双事务验证。Workflow 独立归档同时保留 `job_instance_id`，禁止复制成功后丢失血缘。
5. **REF-05 及配置引用：补应用语义约束。**对多态 `resource_tag` 按类型验证同租户目标；placement 明确 catalog/config 来源并在未知拓扑时失败关闭或显式待激活；活跃配置关系校验软删除/停用后的可见行为，避免只在首次写入时校验后因父配置变化而静默失效。保留草稿、全局默认、system tenant 和外部身份等已声明例外，不机械要求全部引用都有父表。

#### 动态验收矩阵

| 场景 | 负例/并发检查 | 通过标准 |
|---|---|---|
| Workflow JOB 引用 | 缺失、异租户、停用 Job；草稿与 enabled 定义分别操作 | 草稿按明确策略处理；enabled 保存、启用、日程启用、导入和 launch 均失败关闭；无部分写入、无子任务 |
| Job 依赖图 | Console、旧兼容字段/导入、直接 SQL；同时提交 A→B 与 B→A | 所有写入口拒绝悬空上游；两个并发事务最多一个提交成环 |
| 等待请求 | request 不存在/异租户、Job 或 biz_date 不匹配、父请求状态已终结、状态更新 0 行 | 等待记录与原请求身份一致；状态冲突事务回滚并可观测 |
| 父子写与归档 | 子写对父删/归档交错；父租户/分区键更新；重试归档 | 无热孤儿、无静默丢血缘；失败可重试，冷热查询可追溯 |
| 多态/活跃配置引用 | 不存在/异租户/禁用目标、来源未知、父配置停用或删除 | 错误在发布/执行前可见；历史快照和合法预配置按约定保留 |

验收必须同时覆盖应用 API/导入路径和真实 PostgreSQL；并发项使用两个独立事务。单元测试只证明被测分支，不替代 DB 约束负例或完整主链。记录 revision、环境、命令、首个失败步骤和清理结果；不把静态检查、mock、Testcontainers 通过表述成 staging/生产验收。

#### 专题首次动态验证状态（历史记录）

以下保留首次尝试时的环境与失败结果；后续可运行环境中的归档切片见 §2.4，不能继续将本段作为当前环境阻塞结论。

- 目标分支：`feature/workflow-daily-integration`，源码基线以本文件 §1.1 记录的 `ee01e97515b04fd167ed17977535ec5cab8572f8` 加当时工作区为准；执行前必须重新核对当前文件。
- 已尝试定向命令：`./mvnw -pl batch-console-api -am -Dtest=WorkflowDefinitionValidationTest,WorkflowDagValidatorTest,WorkflowDefinitionMutationValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`。Maven 在访问用户级 `~/.m2` 文件锁时失败，测试未开始；临时仓库硬链接副本受文件权限拒绝，未作为有效重试。
- 动态数据库前置检查：Docker socket 返回 permission denied，`127.0.0.1:25432` 和 `:5432` 均未监听；没有执行 API/真实 PostgreSQL 负例或并发验收。
- 结论：RT-2 仍有静态代码证据；所有动态场景状态均为 **未执行**。恢复可用 Maven 缓存和隔离 PostgreSQL 后，先跑本矩阵的 RT-2，再依次跑 RT-4/RT-5/RT-6；首红保留，修复后重跑对应负例和正例。

### 2.3 应用层外键守护与数据库逐关系一致性（2026-10-18 补充）

§2.2 的应用层任务同步遵循[数据库整改总方案 §3.2](database-integrity-index-partition-remediation-2026-10-18.md)；该节是 DBSAFE-3 的联合验收要求，本专题仍以 REF-02–08 逐项承接，当前状态继续在 TODO 的 `ALIGN-DB-REF` 维护，不重复计算工作量或改变 R20/R33/R36 状态。

- **每条强引用必须有一份应用/数据库契约对照**：记录引用源列和目标键、租户/父聚合/业务日/版本身份、FK 的 NULL/MATCH 与 ON DELETE/UPDATE/DEFERRABLE 语义、应用校验允许的空值/草稿/停用/软删状态，以及引用变更和父对象并发删除/改键/归档的处理。若数据库 FK 只保证存在和租户，而应用还保证 enabled、业务日或状态，需明确这是额外业务规则；若应用检查比数据库更弱或更强，必须说明差异、错误契约和允许原因，不能笼统标成“应用已有校验”或“FK 已保护”。
- **应用守护不能与数据库外键各自采用不同身份或生命周期**：应用预检和DB约束须针对相同的租户键、父键、可空语义及删除动作。应用更早拒绝数据库可接受的记录，只能来自明确业务规则；数据库拒绝应用已接受的记录必须变为稳定冲突错误并回滚全部副作用。FK不表达的软删除、启用状态、类型和图环等规则要单独写明所有者、并发协议与验收，不能把应用校验叫作FK等价物。
- **父键改动也属于外键契约**：子侧创建/更新通过预检，不代表父侧改键、换日历、停用、软删、物理删除或归档安全。凡父属性参与子关系语义，必须盘点父侧所有写入口，明确是拒绝、级联、原子迁移还是保留快照；应用拒绝要稳定可操作，数据库约束负责并发竞态兜底。若选择原子迁移，父子变更须在同一事务协调并处理唯一键冲突；若选择拒绝，必须提供解除/迁移子关系的真实操作，不能让有效配置永久锁死父对象。
- **逐关系给出应用侧状态**：REF 清单必须标明“存在应用守护”并列出全部写入口和测试证据、“当前无应用写入口（DB-only）”并盘点直接SQL/种子/运维入口，或“有意保留弱引用”并写清替代责任及可接受孤儿条件。增加新写入口时，须同步实现相同引用身份的应用守护、数据库冲突映射及正反例；不得从数据库已有FK推定应用已重复校验，也不得从 Service 预检推定并发与直写已受保护。统一交叉核对与关闭要求见[数据库整改总方案 §3.2](database-integrity-index-partition-remediation-2026-10-18.md)。
- **验收双向漂移**：构造相同引用在应用入口与直接 SQL 下的正反例。应用接受但 FK 拒绝时，应用事务必须映射成稳定、可观测的领域错误且无部分写入；应用拒绝而数据库可接受时，须证明拒绝源于明确业务规则（例如 enabled 状态或依赖环），而不是租户、NULL 或删除语义实现不一致；数据库接受但应用本应拒绝的场景要在 API、导入/复制、启用/发布和运行入口分别覆盖。对并发父子变更使用两个独立真实 PostgreSQL 事务验证，静态 `SELECT EXISTS`、RLS 或单元测试不能替代。
- **交付记录**：在逐表关系矩阵或验证矩阵中关联 Service/Guard、Mapper/SQL、实际约束名、预期差异、正反例命令及结果；关系尚无原生 FK 时，记录应用守护承担的完整边界、全部写入/删除入口、事务锁/隔离策略和冷数据生命周期。发现不一致时按根因修正实现或约束，并重跑受影响正反例；不能通过弱化应用检查、放宽约束或吞掉异常来让两侧表面一致。
- **逐条对照**：在已有关系清单记录 Service/Guard/Mapper 与实际约束名，明确租户/父聚合/业务日/版本身份、NULL/空串/草稿、停用/软删、物理删除/归档、全局及弱引用例外；不只比“父 ID 是否存在”。对历史快照按原生命周期读取，不能强制重新依赖已归档的热配置。若当前没有生产应用写入口，也要如实记为“数据库约束覆盖直接写入；暂无应用守护路径”，不得虚构应用校验已完成；新增写入口前补齐与该约束一致的校验、并发协议和错误映射。
- **全入口与并发**：API、批量导入/复制、启用/发布/日程、launch/补跑/重试/恢复、归档和运维 SQL 均有责任入口；校验使用写库事务和租户上下文。子写与父删/停用/改键/归档须通过真实 PG 双事务验证，既有子侧 EXISTS 或 RLS 不能证明完整关系保护。
- **父子契约双向验收**：除了“子引用父”的应用入口与直接SQL正反例，还要覆盖“父变更使既有子引用失效”的应用/API、批量导入及并发竞态。每个拒绝策略都须有可执行的子配置解除/迁移路径；不得仅增加FK后让父更新变成不可解释的数据库异常，也不得只做应用预检而遗漏并发或绕过写入。
- **数据库与业务规则分工**：能用原生约束表达的强不变量由数据库兜底；enabled、依赖环、多态/JSON 及跨库关系按业务协议保护。应用和 SQL 用例只对照各自承诺的范围；不为追求形式一致擅加跨库 FK、热表强引用或通用动态守护框架。
- **失败及验收**：对齐现有 `ResultCode`/HTTP 契约与数据库兜底拒绝，未知错误不得吞掉；校验失败无部分状态、Outbox 或子任务，跨库副作用有恢复边界。用独立正反例验证应用/API/导入和直接 SQL，补能捕获漏守护、漏约束及删除语义漂移的回归。本次是文档补充，未实施或执行这些新增验收。

### 2.39 REF-05 父配置改键与应用/数据库外键守护一致性（2026-10-19，局部实施并定向验证）

- **发现**：`external_event_expectation_definition` 与 `file_group_expectation_definition` 当前以 `(tenant_id, workflow_definition_id)` FK 绑定 Workflow，并在 Orchestrator 配置入口校验启用状态和 `calendar_code` 相同；但 Workflow 完整更新（`DefaultWorkflowDefinitionService.fullUpdate`）和配置包 Apply（`TenantConfigPackageExcelApplyService.applyWorkflows`）可以修改 `workflow_definition.calendar_code`。现有子侧 SELECT 只保护首次写入，父侧改日历可能使已保存的预期定义与 Workflow 脱离。
- **数据库约束**：V316 为 `workflow_definition` 建立 `(tenant_id,id,calendar_code)` 唯一身份，并为两类 expectation 子表添加对应三列外键及匹配子索引。先检查已有数据，再以 `NOT VALID` 添加并显式 `VALIDATE`；原有 `(tenant_id,workflow_definition_id)` 同租户外键保留。约束使用 `NO ACTION`，防止直接 SQL 改父日历造成失配。历史 occurrence 继续保留其创建时的业务日快照。
- **可操作生命周期**：不自动迁移 recurring expectation 配置。两类服务现提供租户、日历、Workflow 和代码范围内的显式删除入口；调用方先删除不再适用的定义，再更新父日历，已物化 occurrence 不随定义删除。由此避免复合 FK 令父配置永久锁定，同时保留明确的操作步骤。
- **应用实现**：Console Workflow full update、schedule update 和配置包 Apply 在日历变更前检查两类 expectation；存在子定义时返回稳定 `STATE_CONFLICT`。Orchestrator 创建定义时将指定 FK 的 SQLSTATE/约束身份映射为稳定 HTTP 409，覆盖检查与写入之间的竞态。数据库复合 FK 是直接 SQL 和父更新/子写并发的最终兜底。
- **当前已验证范围**：最新合并定向命令 `./mvnw -o -ntp -pl batch-orchestrator,batch-console-api -am '-Dtest=ConsoleApiExceptionHandlerTest,DefaultWorkflowScheduleUpdateTest,WorkflowDefinitionMutationValidationTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,ExternalEventExpectationServiceTest,FileGroupExpectationServiceTest,WorkflowGraphTenantForeignKeyIntegrationTest#shouldBindExpectationCalendarAndAllowChangeAfterExplicitDelete+shouldSerializeCalendarUpdateAgainstConcurrentExpectationInsert+shouldRejectConcurrentCalendarUpdateAfterExpectationInsert+shouldInstallValidatedCalendarIdentityForeignKey' '-Dsurefire.failIfNoSpecifiedTests=false' test`，7类61/61、零失败/错误/跳过、BUILD SUCCESS。真实 PostgreSQL 17.11 新库从头应用至 V316；集成类8/8 覆盖两个子表日历一致性、显式解除后父日历可更改、父更新先到及子插入先到两种并发顺序，并核对约束目录状态。V316 单文件 Squawk 检查0 issues。
- **仍开放**：本切片不关闭 REF-05/DBSAFE-3。配置包发生数据库竞态时多对象整体回滚与错误映射、两类删除 API 的端到端授权/调用方链路、直接运维入口、数据库锁容量及部署环境迁移仍需整合验收；完整迁移安全门禁还需处理其它迁移文件的既有告警。全量 SIM、严格启动审计、容量和目标部署不由本地定向测试替代。

### 2.40 REF-04 JobTask 分区引用的租户与实例身份（2026-10-19，数据库局部实施并定向验证）

- **发现**：`job_task.job_partition_id` 原单列级联外键只证明 partition ID 存在，不能阻止任务引用另一租户或另一 JobInstance 的分区。任务可以与分区分别存在，但其复合身份必须一致；`job_partition_id IS NULL` 继续表示无分区任务。
- **数据库约束**：V317 先对已有任务/分区不一致做失败关闭预检，为 `(tenant_id,id,job_instance_id)` 建立父唯一键，并将任务 partition 引用替换为 `(tenant_id,job_partition_id,job_instance_id)` 复合 `ON DELETE CASCADE` 外键；子索引覆盖相同引用列。复合FK以 `NOT VALID` 安装后显式验证。既有级联删除语义保留，partitionless task 不受影响。
- **真实 PostgreSQL 证据**：新增 `JobTaskPartitionTenantIdentityIntegrationTest` 在 PostgreSQL 17.11 新库上先复现无复合FK时跨租户/跨实例写入被数据库接受；加入V317后同租户同实例与无partition正例通过，跨租户/跨实例负例返回 SQLSTATE 23503，父分区删除继续级联删除任务，目录断言确认约束已验证且删除动作 CASCADE。拆分运行 `JobTaskPartitionTenantIdentityIntegrationTest`、`JobNodeDispatchIntegrationTest`、`LocalFlywayPlatformMigrationsIntegrationTest` 共53/53通过，并单独运行完整 `ArchiveColdStorageIntegrationTest` 13/13通过；后者不再构造被新FK禁止的跨运行树 task，而继续检查未约束的 step/log 关联。合计66个不同的定向回归用例通过。
- **范围边界**：此切片仅证明 `job_task` 与其非空 partition 的租户及 JobInstance 身份一致，不证明 `job_partition` 自身与分区 JobInstance 的租户完整性，不补 `job_step_instance` 与 task/partition 的完整身份关系，也未增加应用预检。上述关系、应用层稳定错误契约、并发父侧归档和锁容量仍开放；不得据此关闭 REF-04/DBSAFE-3 或主方案条目。V317 替换既有 FK 会命中当前迁移安全门禁中的 `ban-drop-constraint` 规则，未加豁免；需在全分支门禁中如实处理。

### 2.41 REF-04 JobPartition 到分区 JobInstance 的同租户和业务日身份（2026-10-19，本地实施并定向验证）

- **发现及方案缺口**：V173 将 `job_instance` 转为按 `biz_date` 分区的父表，并删除 `job_partition_job_instance_id_fkey`。V317 只收紧了任务到分区的关系；分区本身仍可能关联错误租户/缺失父实例，且父业务日或父行生命周期缺少数据库级保护。该父子引用此前只在 §2.40 的边界中提及，没有独立实施与验收条目；本节将它纳入 REF-04，不扩大到其它未相关联的数据库审计项。
- **目标身份和实施选择**：对热表建立 `(tenant_id,job_instance_id,job_instance_biz_date)` 到 `job_instance(tenant_id,id,biz_date)` 的复合 `NO ACTION` 外键；子表保存父实例的业务日，所有应用和直接 SQL 写入都必须显式传入父业务日；未传值由已验证的 `CHECK (job_instance_biz_date IS NOT NULL)` 失败关闭，并拒绝无匹配父行。外键承担并发下父更新/删除屏障；不能以无锁 `EXISTS` 或仅应用层预查替代。保留归档服务先复制并核对分区、再删除热子分区、最后删除父实例的顺序。
- **迁移与冷表要求**：失败关闭检查现有分区的租户/父身份后回填业务日；为父复合引用键建立唯一索引，为分区引用建立索引，约束以 `NOT VALID` 安装后显式 `VALIDATE`。同步更新 `JobPartitionEntity`、生产创建分区路径、Mapper 字段、冷表 archive 镜像和归档复制映射；不重写历史 Flyway，不允许错误归属被静默修正。新部署之外的既有行若不一致，迁移必须明确失败并报告身份，不自行猜测修复。
- **应用层守护**：`DefaultPartitionLifecycleService.createPartitions` 在写分区前按 `(tenant_id,job_instance_id)` 读取父实例，并将其 `biz_date` 显式写入所有待插入分区；缺失或租户不匹配按稳定 NOT_FOUND 业务错误失败，任何分区写入前完成验证。所有其他直接 SQL/种子/测试写入也必须显式提供日期，漏传由已验证的非空 `CHECK` 拒绝，不添加数据库自动代填触发器。数据库复合 FK 是最终竞态/旁路保护，精确匹配该复合 FK 与 SQLSTATE 23503 的竞态错误映射为稳定 CONFLICT，其他完整性错误原样上抛；应用预查不能被描述为等价于外键。
- **验收条件**：真实 PostgreSQL 覆盖同租户有效分区成功、错租户和无父实例失败、父 `biz_date` 改写/父提前删除被拒、先删子再删父成功、归档后冷镜像字段一致；双连接事务覆盖“父删除先取得锁、子写等待后失败”及“子写先提交、父删除等待后失败”两种交错。另验证生产批量创建路径传递业务日、应用缺父失败零写入、约束已验证、索引存在，并运行相关归档/创建集成回归。该切片不关闭 REF-04/DBSAFE-3 或整合主方案验收。
- **实现和本地证据**：V318 增加业务日复合 `NO ACTION` 外键、父/子索引及失败关闭回填；日期由应用/SQL写入者显式提供，遗漏由已验证的非空 `CHECK` 失败关闭，不额外引入数据库代填触发器；验证分区表生成的引用约束也全部为 validated。应用分区创建路径按租户及实例读取父项并固定 `biz_date`，冷归档复制映射同步该字段。真实 PostgreSQL 覆盖有效/错租户/缺父、父日期更新与删除屏障、子先父后删除及两种实际锁等待交错；创建服务缺父零写入及特定 FK 并发冲突映射稳定 409，非目标完整性错误不误映射；生产 Mapper、归档冷表、Flyway 全链路及 Console/E2E 写入 fixture 定向回归均通过。精确命令和限制见[整合验证矩阵 V318 条目](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-v318-jobpartition-与-jobinstance-身份一致性)。全分支迁移安全门禁仍需单独确认；这不关闭 REF-04/DBSAFE-3。

### 2.42 REF-04 Step 与 Task 的完整归属（2026-10-19，局部实施并定向验证）

- **范围与先红**：`job_step_instance` 必须与 `job_task` 使用相同 `(tenant_id,job_instance_id,job_partition_id)`；双方均无分区时才允许 NULL。真实 PostgreSQL 新库上的定向方法已复现跨租户 Step 写入被旧单列 FK 接受，日志 `logs/verification/workflow/v319-step-identity-red-20261019.log`。本切片承接逐表矩阵，不扩展到 task→分区 JobInstance 的其它弱引用。
- **原生约束选择**：可空 partition 直接进入复合 FK 会因 MATCH SIMPLE 跳过校验（[PostgreSQL 17 FK 规则](https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-FK)）；不以读后写触发器替代完整并发约束。V319 在 Task/Step 增加数据库自动生成的 `job_partition_ref_key = coalesce(job_partition_id,0)`，两表 CHECK 明确禁止实际 partition ID 为0；0只表示无分区。使用 `(tenant_id,job_task_id,job_instance_id,job_partition_ref_key)` 原生 CASCADE FK，替换旧 Task/Partition 两个单列 FK；Partition→Task→Step 继续级联。已有 Step 唯一索引 `(tenant_id,job_task_id)` 能按父键定位至多一行，不再加重复子索引。FK负责父键更新及事务并发，包含 REPEATABLE READ；不新增业务状态、API字段或应用手工维护的第二份身份。
- **部署约束**：四张冷热表增加 STORED 生成列会触发表重写，Squawk 单文件报告4条 `adding-field-with-default`。本方案按用户已确认的全新数据库部署，在服务接入写流量前从头执行；不声称支持已有大表在线升级，也不增加触发器或 lint 豁免来隐藏锁成本。全分支迁移安全门禁保持未通过，须在最终签收中如实处理。
- **应用/归档边界**：唯一生产 Step 写入口 `DefaultTaskCreationService` 从刚写入的 Task 复制租户/实例/分区；单条与批量路径必须保持此构造，不增加逐 Task 的数据库预查。精确 Task partition/Step ownership FK 冲突映射稳定 CONFLICT，其它约束失败不误映射；同一事务的任务/步骤写入失败应整体回滚。冷热 Task/Step 同步生成列，现有显式列归档 SQL 省略派生列，由数据库重算，不增加实体/API字段。
- **验收**：同租户同实例同分区及双方无分区正例；错租户/实例/分区与NULL错配负例；Step改键、Task先改键与Step先插入的两个并发顺序（含REPEATABLE READ），父更新拒绝/子删除后更新成功，Task/Partition删除级联，直接批量写原子性、应用稳定错误和真实事务回滚，以及冷表生成字段/整行镜像、种子及调用路径回归。已有归档损坏场景改为仍可构造的弱关联日志异常，不能通过绕过新FK继续制造不可能的 Step。
- **本地证据**：上述原生身份/NULL/级联/目录/双连接两隔离级别由10项真实PG用例验证；应用稳定错误/未知错误与身份构造单测最终22项通过；真实Spring事务注入Step身份错误证明本批Task/Step整体回滚，归档整行镜像与Job节点/Console/E2E受影响回归均通过。最终10类106个不同用例的分批结果与失败记录见[整合验证矩阵 V319](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-v319-step-与-task-归属及批量回归隔离)。新库重放发现的Task4212/Step4312错绑已修正；合并跑发现的Console诊断fixture泄漏已按精确租户收尾，不通过随机业务序号或放宽约束规避。普通RLS角色全链、父Task到JobInstance完整引用、全局归档/保留期并发与容量/目标部署仍开放；不关闭REF-04/DBSAFE-3或R项。



### 2.43 REF-04 JobTask 到分区 JobInstance 的完整父身份（2026-10-19，局部实施并定向验证）

- **缺口**：V173 因 JobInstance 按 `biz_date` 分区移除 Task→JobInstance 外键。V317–V319 已补 Task→Partition、Partition→JobInstance、Step→Task 身份，但当 `job_partition_id IS NULL` 时，Task 仍可能引用不存在或异租户的 JobInstance。V318 已建立可复用父键 `(tenant_id,id,biz_date)`；无分区 Task 的强引用需要显式保存该日期。
- **迁移与约束**：部署契约是全新数据库，V320 运行时热 Task 与冷归档表均为空；迁移直接以原生 `NOT NULL` 增加 `job_instance_biz_date`，不扫描、预检或回填旧运行数据。随后建立 `(tenant_id,job_instance_id,job_instance_biz_date)` 子索引和指向 `job_instance(tenant_id,id,biz_date)` 的 `NO ACTION` 复合 FK，并显式验证分区父表生成的子约束。冷表 `archive.job_task_archive` 保留同一业务日身份，但不对可独立留存的冷历史添加热父 FK。该选择依赖新库部署边界，不表示支持旧库原地升级。
- **应用写入对齐**：`JobTaskEntity` 与单条/批量 Mapper 增加父业务日；分区派发和 Workflow Job 节点派发都从实际父 JobInstance 复制日期。`DefaultTaskCreationService` 在写入前拒绝缺日期，并仅把 SQLSTATE 23503 + 精确 `fk_job_task_instance_tenant_date` 映射为稳定 CONFLICT；其它约束错误不被吞并。成功归档的 Task 冷副本同步日期身份。平台基础/边界种子与受影响 JDBC 测试补齐业务日；使用自建最小表的 PROCESS 锁测试和 load-test 诱饵表不是生产 schema fixture，不引入无关列。
- **测试结果与失败保留**：修改前 `JobTaskPartitionTenantIdentityIntegrationTest#shouldRequireTaskToMatchParentJobInstance` 在 PostgreSQL 17.11 上以 orphan Task INSERT 成功而按预期失败，日志 `logs/verification/workflow/v320-task-instance-fk-red-20261019.log`。首个影响面命令 `logs/verification/workflow/v320-task-instance-fk-b1-20261019.log` 执行117项，其中116通过、1项测试目录断言失败：初版迁移使用已验证 CHECK，但 `information_schema.is_nullable` 仍显示 YES；修为原生 `NOT NULL`，不放宽断言。修正迁移后的命令 `logs/verification/workflow/v320-task-instance-fk-b1-recovery-20261019.log` 使 Orchestrator 迁移/Task/归档 29/29 通过，但暴露3个 Console fixture 错误：两个诊断用例构造了新强FK禁止的无父 Task，分区错误映射 fixture 未填 V318 要求的父业务日。修复为真实租户 JobInstance 和分区父日期后，仅复跑 Console/E2E 受影响类，`logs/verification/workflow/v320-task-instance-fk-b1-console-e2e-20261019.log` BUILD SUCCESS，诊断3/3、分区错误映射1/1、JobStep 关联流水线1/1、Kafka/Worker 死信重放 E2E 1/1 通过。跨命令受影响范围共123个不同用例的最终类级证据：创建服务24、分区派发9、冷归档13、容量3、崩溃恢复3、Job 节点49、Task 身份/并发14、空库迁移2、Console 诊断3、分区错误映射1、JobStep Mapper 1、死信 Kafka/Worker E2E 1；重复执行不重复计数。
- **静态检查与边界**：`python3 scripts/ci/check-schema-governance-assets.py` 通过；`BASE_REF=origin/main bash scripts/ci/validate-flyway-schema.sh db/migration` 通过，315 个迁移文件无既有 checksum drift，V31/V238/V242 版本间隙警告保留；`bash scripts/ci/check-db-scripts-safety.sh` 退出0，WARN 30/FAIL 0。Testcontainers 清理后容器数0。全仓 `git diff --check` 被已有 `V293__enforce_workflow_graph_definition_tenant.sql:39` EOF 空行拦截；本切片改动路径的 `git diff --check` 通过。全分支 Squawk/迁移安全门禁、普通 RLS 角色全链、完整运行树归档/保留并发、最终 SIM/strict/WARN 和目标环境仍未验证；格式检查按用户要求暂缓。此切片不关闭 REF-04/DBSAFE-3、R20/R33/R36 或其他验收项。

### 2.44 REF-05 asset freshness policy 到 Job Definition 同租户引用（2026-10-19，局部实施并定向验证）

- **缺口与业务身份**：`asset_freshness_policy` 只支持 `asset_type=JOB`，其 `asset_code` 语义为 Job code；自然键应为 `(tenant_id,asset_code)`，对应 `job_definition(tenant_id,job_code)`。此前配置入口未查 Job，数据库也允许直接写入孤儿策略。
- **迁移与生命周期**：V321 建立已验证复合外键 `(tenant_id,asset_code) → job_definition(tenant_id,job_code)`，`ON DELETE CASCADE` 与“策略是 Job 配置”的派生关系一致。策略可在首次产物生成、`data_asset` 行创建前配置；不添加到 `data_asset` 的引用。disabled Job 仍允许配置策略，Job enabled 状态与策略引用身份分开；扫描端现有策略启用语义不由本切片改变。
- **应用守护**：Console create/update 在写策略前通过 MyBatis `SELECT ... FOR KEY SHARE` 校验并锁定同租户 Job；不存在或仅存在于其它租户的 code 返回稳定 `INVALID_ARGUMENT`。应用检查与数据库FK使用完全相同的两列身份，避免预检后父记录并发删除；FK继续兜底直接 SQL 和遗漏写入口。
- **先红与最终验证**：修改前真实 PostgreSQL 17.11 用例因孤儿策略 INSERT 成功而按预期失败，日志 `logs/verification/workflow/asset-freshness-job-reference-red-20261019.log`。修复后命令 `./mvnw -o -ntp -l logs/verification/workflow/asset-freshness-job-reference-v321-recovery-20261019.log -pl batch-console-api -am '-Dtest=ConsoleAssetFreshnessPolicyServiceTest,ConsoleAssetFreshnessPolicyReferenceIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：15/15、failures/errors/skips 均0、BUILD SUCCESS；新库上下文应用至V321。覆盖应用稳定拒绝、直接SQL孤儿与错租户拒绝、disabled Job 可配置、无 `data_asset` 时可配置及物理删除 Job 后策略级联清理。
- **静态门禁与边界**：`python3 scripts/ci/check-schema-governance-assets.py` 通过；V321 单文件 Squawk 0 issues。使用已安装平台二进制重跑全分支 `check-migration-safety.sh origin/main` 时仍失败，6条发现均来自先前 V319/V320（4条 STORED 生成列表重写、2条 `SET NOT NULL`），V321 自身无发现；不得把全分支迁移安全门禁写成通过。该切片只为 REF-05 增加一条配置关系证据，不关闭 REF-05/DBSAFE-3、R20/R33/R36 或任何 R 项；完整应用写入口/导入复制生命周期、普通角色矩阵、全量SIM/容量/部署继续开放。

### 2.45 REF-04 JobExecutionLog 分区身份与历史引用边界（2026-10-19，局部实施并定向验证）

- **缺口与生命周期**：旧 `job_execution_log_job_partition_id_fkey` 只检查分区 ID，不能证明日志和分区的 tenant/JobInstance 一致。日志属于审计记录：分区关联存在时必须是强同租户/同实例引用；单独 `job_instance_id` 在 JobInstance 热行归档删除后仍保留作历史身份，因此不能用指向热分区表的 FK，也不能在父删除时清空该历史 ID。
- **数据库关系**：V322 在新库中失败关闭地检查已有分区日志，要求实例、分区和 `(tenant_id,job_instance_id)` 全部一致；增加 `job_partition_id IS NULL OR job_instance_id IS NOT NULL` 已验证 CHECK，并以已验证复合 FK `(tenant_id,job_partition_id,job_instance_id) → job_partition(tenant_id,id,job_instance_id)` 替换单列 FK。分区删除仅 `SET NULL (job_partition_id)`，审计内容及历史 tenant/instance 身份保留。
- **应用写入守护**：生产日志新增入口集中于 `JobExecutionLogMapper.insert`。该入口使用单语句 CTE 按 tenant/ID 查找并 `FOR KEY SHARE` 锁定热 JobInstance；非空 `job_instance_id` 缺失或异租户时不插入并返回稳定内部失败。该应用守护与日志“追加时父实例须存在”的规则一致；它不伪装成热父历史 FK。维护/测试直接 SQL 写入口已盘点，归档直接 SQL 仍保留完整性前置检查。
- **并发与归档**：成功实例归档候选对 root 使用 `FOR UPDATE ... SKIP LOCKED`；受保护日志追加对相同父行使用 `FOR KEY SHARE`，父删除和新增日志不能越过对方锁。真实 PostgreSQL 并发用例覆盖父删除先持锁、日志写等待后因父不存在而拒绝；完整 Archive 回归通过，归档与日志 append 的具体重叠交错尚未单独注入，不能扩大为已完成的整树归档竞态证明。
- **最终回归**：`./mvnw -o -ntp -l logs/verification/workflow/v322-b1-reference-archive-wipe-final-20261019.log -pl batch-orchestrator,batch-console-api -am '-Dtest=LocalFlywayPlatformMigrationsIntegrationTest,JobTaskPartitionTenantIdentityIntegrationTest,ArchiveColdStorageIntegrationTest,TenantWipeScriptsIntegrityIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 共34/34、failures/errors/skips均0、BUILD SUCCESS，Maven总时长1分24秒。真实 PostgreSQL 17.11 空库迁移至 V322；Tenant wipe 测试含带分区日志的被清理租户，验证两条 psql wipe 脚本均清除日志/分区/实例。V322 加入后平台 schema manifest 经差异复核仅约束计数从1764变为1765、seed manifest不变；普通模式全类复验通过。最终 Testcontainers 标签容器清单为空。
- **静态门禁与边界**：`python3 scripts/ci/check-mybatis-generated-key-columns.py`、`bash scripts/ci/check-db-comment-coverage.sh` 通过；指定本地 Squawk 二进制运行 `scripts/ci/check-migration-safety.sh origin/main` 扫描83个迁移文件、0 issues。此切片只关闭日志分区身份、应用追加时的父存在性及两个 wipe 脚本回归；不关闭 REF-04/08、DBSAFE-3、整树归档/保留并发、普通 RLS 全链或任何 R 行。

### 2.46 RT-6 / REF-04 WorkflowRun 到 JobInstance 的历史弱引用身份（2026-10-19，局部实施并定向验证）

- **引用语义**：`workflow_run.related_job_instance_id` 是可选历史血缘，不加指向热分区 `job_instance` 的强外键：创建关联时目标必须存在且 tenant 相同；关联提交后允许归档/删除热 JobInstance，并保留 WorkflowRun 上的 ID。关联仍存在时不能改写父 `id`、`tenant_id` 或 `biz_date`，避免历史 ID 变成悬空、错租户或指向另一分区身份。
- **并发守护**：V323 将子侧触发器改为同租户父行 `FOR KEY SHARE`，与父删除及身份更新串行化；扩展 V305 父侧 Workflow 引用守护，对热 `workflow_node_run` 仍拒绝删除/改键，对 WorkflowRun 历史弱关联只拒绝身份更新，保留关联提交后的父删除语义。父身份先更新/删除时，后来的关联等待后按不存在/错租户失败；关联先提交时，身份更新检查到历史引用并拒绝，物理删除仍可在引用成立后完成。
- **先红与验证**：新增 `WorkflowArchiveIntegrityIntegrationTest#shouldRejectRekeyingJobInstanceWhileWorkflowRunHistoryReferencesIt` 在 V323 父侧守护前以真实 PostgreSQL 首红，旧触发器允许修改父 ID。V323 后同一测试及业务日分区身份负例、跨租户负例、父先删除与关联先提交的双事务交错定向5/5通过；归档类最终12/12通过。首轮 B1 合并回归44项中唯一失败是 catalog manifest 的 Routine/Trigger 哈希，实际差异恰为 V323 改写的父守护函数及触发器，其余对象、schema/约束/索引计数和 seed 未变化；审阅后更新 manifest，再执行最终合并批次46/46、0 failures/errors/skips、BUILD SUCCESS。真实 PostgreSQL 17.11 空库迁移至 V323；门禁 `check-migration-safety.sh` 扫描84个新增/改动迁移文件，Squawk 0 issues。日志：`workflow-run-parent-reference-v323-rekey-red-20261019.log`、`workflow-run-parent-reference-v323-identity-final-20261019.log`、`workflow-run-parent-reference-v323-class-final-20261019.log`、`v323-schema-manifest-generate-20261019.log`、`v323-b1-reference-archive-wipe-final-20261019.log`。
- **边界**：本切片完成的是 WorkflowRun 历史弱引用身份与父身份并发守护；不关闭 REF-04/RT-6/DBSAFE-3/B1，也不证明所有运行树及归档清理组合、普通 RLS 角色全链、SIM、strict/WARN 或目标环境已验收。未更新任何 R 项完成数；格式检查按用户要求暂缓。


### 2.47 RT-6 / REF-04 TriggerRequest 两类父引用的历史弱身份（2026-10-19，局部实施并定向验证）

- **JobInstance 弱引用**：`trigger_request.related_job_instance_id` 是可选历史血缘。因 JobInstance 热表按业务日分区且可归档删除，不增加会阻断历史归档的强外键。V323 BEFORE INSERT/UPDATE trigger 以同租户 `FOR KEY SHARE` 锁定父实例，缺失或异租户目标以 SQLSTATE `23503` 和 `fk_trigger_request_related_job_instance_reference` 拒绝；被历史请求引用的 JobInstance 不可改写 `id`/`tenant_id`/`biz_date`，但允许父热行删除并保留历史 ID。
- **父 WorkflowRun 弱引用**：`trigger_request.parent_workflow_run_id` 同样不能加热 FK，否则会阻断独立归档。该插入入口已有 V286 `trg_trigger_request_batch_day_admission`：先取得业务日 settlement fence，再按 tenant/id `FOR UPDATE` 重验并锁父 WorkflowRun；异租户/不存在的父运行会拒绝，锁定顺序与父终态/删除竞争串行化，因此没有再叠一套重复的插入锁。V323 补齐历史身份冻结：存在子请求引用时父 WorkflowRun 的 `id`/`tenant_id`/`biz_date` 不可改；请求创建后 `parent_workflow_run_id` 与 `parent_node_code` 快照不可改写。删除父运行仍允许，子 TriggerRequest 保留历史 ID。未发现生产 UPDATE 调用方。
- **应用写入口**：Orchestrator 的 `updateAcceptance` / `reconcileStatus` 对 JobInstance 回填保留同租户 EXISTS 条件，数据库触发器补并发和直接 SQL 边界。`TriggerRequestMapper.insert` 的 parent WorkflowRun 同租户检查由 V286 数据库 admission trigger 再次持锁验证。未调用的 `batch-trigger.updateRelatedJobInstanceId` 直写 Mapper/XML 已删除。parent node code 是执行快照，不回连当前可编辑 workflow_node。
- **首红与 PostgreSQL 验证**：真实 PostgreSQL 首红 `logs/verification/workflow/trigger-request-parent-workflow-reference-v323-red-retry-20261019.log` 确认旧 V323 允许被 TriggerRequest 引用的父 WorkflowRun 改写 ID。V323 后三条父链接用例 3/3 通过，覆盖 ID/tenant/biz_date 重写拒绝、父引用和 node 快照不可重绑、跨租户父运行拒绝、父删除后保留历史 ID；并发删除先持锁后，child INSERT 在 V286 `FOR UPDATE` 等待并于父删除提交后失败（`logs/verification/workflow/trigger-request-parent-workflow-reference-v323-attach-delete-red-retry-20261019.log`，1/1）。V286 与父终态/节点终态竞争的现有两例在当前 V323 新库 2/2 通过（`logs/verification/workflow/v323-parent-workflow-reference-v286-fence-regression-20261019.log`）。
- **最终合并回归**：命令 `./mvnw -o -ntp -l logs/verification/workflow/v323-trigger-request-weak-reference-b1-regression-final2-20261019.log -pl batch-orchestrator,batch-console-api,batch-trigger -am '-Dtest=LocalFlywayPlatformMigrationsIntegrationTest,JobTaskPartitionTenantIdentityIntegrationTest,ArchiveColdStorageIntegrationTest,WorkflowArchiveIntegrityIntegrationTest,TriggerRequestMapperIntegrationTest,TriggerRequestSettlementFenceMapperIntegrationTest,TriggerServiceIntegrationTest,DefaultTriggerServiceTest,TenantWipeScriptsIntegrityIntegrationTest,BatchDayMetricsMapperIntegrationTest#shouldRejectChildAdmissionWhenParentTerminatesWhileWaitingForSettlementFence+shouldNotFinalizeWorkflowFromStaleNodeSnapshotDuringChildAdmission' '-Dsurefire.failIfNoSpecifiedTests=false' test` 成功。TriggerRequest mapper 13/13、Workflow archive 12/12、Archive cold storage 15/15、JobTask partition identity 15/15、settlement fence 2/2、迁移 2/2、tenant wipe 2/2、Trigger 模块聚合 66/66；failures/errors/skips均0、`BUILD SUCCESS`。真实 PostgreSQL 17.11 空库应用318个版本化迁移至 V323。
- **静态检查与边界**：`check-migration-safety.sh origin/main` 扫描84个新增/修改迁移文件、Squawk 0 issues；schema governance、DB comment coverage、MyBatis generated-key 检查通过。schema manifest 仅更新 V323 新增/修改函数和触发器后的 Routine/Trigger 指纹（Routine 51、Trigger 70），其它 schema 类别及 seed manifest 不变；测试后 Testcontainers 标签容器清单为空。
- **局部边界**：此切片仅关闭 TriggerRequest 两类父引用的同租户准入、父身份不可重写和弱链接归档删除语义；不关闭 REF-04/RT-6/DBSAFE-3/B1 或任何 R 验收项，不代表普通角色 RLS 全链、完整运行树归档/清理组合、SIM、strict/WARN、容量或目标环境已验收。已登记的本地收尾例外仅适用于验证矩阵 §3.3 精确列明的环境/业务范围；格式检查按用户要求暂缓。

### 2.48 REF-04 JobPartition source file 同租户热引用与清理边界（2026-10-19，局部实施并定向验证）

- **数据库约束与历史边界**：V324 为 `batch.file_record(tenant_id, id)` 建立复合候选键，并以 `fk_job_partition_source_file_tenant` 将热 `batch.job_partition(tenant_id, source_file_id)` 绑定到同租户源文件；约束为 `NO ACTION`，并建立匹配的子侧索引。父文件在热分区仍引用时不能删除；归档分区上的 `source_file_id` 继续作为历史弱引用，不增加会阻断归档生命周期的热表 FK。`SuccessInstanceArchiveMapper` 的运行树复制语句显式搬运 `source_file_id`、`template_code` 和 `target_ref`，避免归档后静默丢失来源或执行目标快照。
- **应用守护与错误契约**：分区创建继续通过 `RelatedFileReferenceValidator` 检查源文件租户与身份；若校验后并发删除父文件，服务只将精确的 `fk_job_partition_source_file_tenant` 约束冲突映射为稳定 `NOT_FOUND`，其它数据库约束错误原样传播。
- **清理顺序**：租户 wipe 和平台 seed 清理先删除热分区、再删除 `file_record`。`scripts/sim/sql/cleanup-sim-output-files.sql` 在事务中先物化精确目标，若仍有热分区引用则失败关闭；解除引用后先清理 dispatch 回执，再删除目标文件。历史 archive 表不参与该热文件约束。
- **PostgreSQL 回归**：首红的同租户外键测试与应用并发错误映射单测曾分别确认旧实现会接受错租户引用、并将竞态约束错误泄漏为数据库异常。最终合并命令 `./mvnw -o -ntp -l logs/verification/workflow/v324-source-file-lifecycle-regression-final-20261019.log -pl batch-orchestrator,batch-console-api -am '-Dtest=DefaultPartitionLifecycleServiceTest,JobTaskPartitionTenantIdentityIntegrationTest,BundleLaunchPartitionExpansionIntegrationTest,ArchiveColdStorageIntegrationTest,SuccessInstanceArchiveMapperXmlTest,LocalFlywayPlatformMigrationsIntegrationTest,TenantWipeScriptsIntegrityIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 覆盖64个目标用例，0 failures/errors/skips，`BUILD SUCCESS`；空库从319个迁移应用到 V324，正常模式 Schema manifest 对账通过。其后 `TenantWipeScriptsIntegrityIntegrationTest` 全类 3/3 通过，真实 psql 先证明仍被热分区引用时 SIM 清理拒绝且事务不删文件/回执，再删除分区后成功删除回执和文件，日志 `logs/verification/workflow/v324-sim-cleanup-script-test-class-final-20261019.log`。
- **静态检查与边界**：`python3 scripts/ci/check-schema-governance-assets.py` 通过；`BASE_REF=origin/main bash scripts/ci/validate-flyway-schema.sh db/migration` 通过，检查319个迁移文件且无 checksum drift（V31/V238/V242 版本间隙警告保留）；Squawk 对85个新增/改动迁移文件报告0 issues；`bash scripts/ci/check-db-scripts-safety.sh` 退出0、WARN 30/FAIL 0。Testcontainers 拥有的 PostgreSQL 容器已清理；格式检查按用户要求暂缓。
- **冷归档快照回归（2026-10-19）**：真实运行树测试为分区填入同租户 `source_file_id`、`template_code`、`target_ref`，按整行 JSON 快照核对热/冷一致。首红先观察到 `source_file_id=NULL`；补它后，扩展快照再观察到 `template_code`、`target_ref=NULL`。根因均为 `SuccessInstanceArchiveMapper.archiveJobPartitionsByInstanceIds` 的 INSERT/SELECT 漏列。补齐三列后，`ArchiveColdStorageIntegrationTest` 全类 15/15、0 failures/errors/skips、`BUILD SUCCESS`，日志 `logs/verification/workflow/v324-partition-snapshot-archive-class-final-20261019.log`。中间两次日志分别保留于 `v324-source-file-archive-red2-20261019.log` 和 `v324-partition-snapshot-archive-red-20261019.log`；首次夹具错误由不合法 `CSV` 值造成，修正为合法 `BINARY` 后确认产品缺陷。
- **局部边界**：此切片证明一条热分区源文件强引用、并发错误契约和相关清理顺序，不关闭 REF-04/DBSAFE-3/B1 或任何 R 行；不代表普通角色 RLS 全链、完整运行树归档/保留清理、全量 SIM/strict/WARN、容量或目标环境已验收。

### 2.49 REF-06 DataQualityCheck 到 JobInstance 的历史弱引用并发身份（2026-10-19，局部实施并定向验证）

- **语义与边界**：`data_quality_check.job_instance_id` 保留历史弱引用；JobInstance 可按既有归档流程删除，因此不增加热外键。关联建立时必须恰好存在一个同租户 `(tenant_id,id)` 父实例；已被检查记录引用后，父 `id`、`tenant_id`、`biz_date` 不可改写；仅有 DQ 弱引用时，归档删除仍允许并保留历史 ID。
- **应用与数据库对齐**：`DataQualityCheckMapper.insert` 使用 materialized CTE 对同租户父实例执行 `FOR KEY SHARE`，并且只在唯一匹配行数为 1 时插入；零行结果失败关闭。V325 的子表触发器对应用与直接 SQL 都按同一租户/ID身份锁定并校验，孤儿或异租户写入以 SQLSTATE `23503`、约束 `fk_dq_check_job_instance_reference` 拒绝。父侧守护扩展现有 JobInstance 引用函数，只在有 DQ 历史行时拒绝身份改写，不将该弱链接改造成强 FK。
- **先红与 PostgreSQL 回归**：保留 `logs/verification/workflow/dq-instance-reference-guard-red-20261019.log`（应用侧缺父/异租户与并发用例复现旧缺口）及 `logs/verification/workflow/dq-instance-reference-database-guard-red-20261019.log`（直写与父改键复现 DB 缺口）。最终命令 `./mvnw -o -ntp -l logs/verification/workflow/dq-instance-reference-final2-regression-20261019.log -pl batch-orchestrator -am '-Dtest=DataQualityCheckReferenceIntegrityIntegrationTest,DataQualityCheckExecutorTest,ResultVersionWriterTest,LocalFlywayPlatformMigrationsIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：32/32，0 failures/errors/skips，`BUILD SUCCESS`。其中 DQ PostgreSQL 类 8/8 覆盖应用写入、直接 SQL、两种父删除/子附加竞争及父身份改写；执行器单测 10/10、结果版本写入单测 12/12、空库迁移/manifest 类 2/2。隔离 PostgreSQL 17.11 新库将 320 个迁移成功应用至 V325，迁移验证 321 个版本、重复 migrate 无待处理项。
- **Schema 与迁移门禁**：首次普通 manifest 对账只发现 Routine 51→52、Trigger 70→71，其他 schema 类别和 seed 指纹未变；审阅 V325 函数/触发器后更新 `fresh-platform-schema-manifest.sha256`，随后普通模式 `LocalFlywayPlatformMigrationsIntegrationTest` 2/2 通过。`python3 scripts/ci/check-schema-governance-assets.py` 通过；`BASE_REF=origin/main bash scripts/ci/validate-flyway-schema.sh db/migration` 检查320个迁移、无 checksum drift（V31/V238/V242 既有间隙 warning 保留）；Squawk 对86个新增/改动迁移 0 issues。Testcontainers 清理已在本轮容器清单中复核为空；格式检查按用户要求暂缓。
- **未关闭项**：`data_quality_check_archive` 仍无生产搬运与冷热回查路径；未验证普通 RLS 角色全链。该关系切片不关闭 REF-06/REF-08/DBSAFE-3/B1 或任何 R 行，不代表完整运行树归档、最终 SIM/strict/WARN、容量或目标部署通过。

### 2.4 P0 独立 Workflow 归档切片实施与验证（2026-10-18）

- `WorkflowRunMapper.archiveWorkflowNodeRunsByWorkflowRunIds` 已补齐 INSERT/SELECT 的 `job_instance_id`；根无 wrapper Job 的独立 Workflow 与 END 节点空关联保持原样。`WorkflowArchiveService` 在同一事务内复制后核对根和节点完整行，冷副本缺失或不一致即抛错，禁止删除热数据；合法一致副本仍允许幂等完成。
- 真实 PostgreSQL 用例先复现 JOB 关联变 NULL、错误冷记录因 `ON CONFLICT DO NOTHING` 被跳过后热数据仍被删；修复后 5 项生产归档入口用例覆盖全字段快照、复制失败回滚/恢复、根/节点冲突及合法幂等。含既有 SuccessInstance/Outbox 归档、schema 漂移、Mapper 与 Process 的受影响回归 35/35 通过；命令和日志见[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末。
- 仅更新 REF-01 的 JOB 关联切片和 REF-02 的冷副本核对证据；`pipeline_progress` 搬运/恢复、并发子写与父归档/分区淘汰、其它应用引用入口仍开放。该核对不等于完整父子并发锁协议，不关闭 REF-01/02、DBSAFE-1/3 或 R20/R33/R36。

### 2.5 Pipeline 位点归档、数据库与应用守护对齐（2026-10-18）

- V292 为热 `pipeline_progress` 添加 `(tenant_id,pipeline_instance_id)` 外键，引用父 `(tenant_id,id)` 唯一索引；RESTRICT 防止删父丢位点。同一 Flyway 事务 ADD NOT VALID 后立即 VALIDATE，不留下待验证的提交状态；冷位点保留历史身份，不引用热父。
- 归档先按ID固定并锁定父Pipeline，再锁已有位点；复制并整行核对冷Pipeline和位点后，显式清理热位点再清父。固定ID集避免把随后新建且未持锁的Pipeline位点纳入删除。真实PG覆盖首次插入与已有位点更新在归档持锁期间等待，提交后分别被FK拒绝或更新0行，无热孤儿、冷标记不被改写。
- 应用 `advance/markCompleted` 要求实际影响1行才记成功；0行抛错并记失败，补偿删除0行仍允许幂等。成功、失败、终止运行的完整/未完成位点均保留到冷表，活跃热父位点不被本批归档。恢复仍读热位点；不增加新运行读取旧冷 completed 标记的回退。
- 三个隔离fixture清理入口按既有runId/pattern先清自有位点，再删父；另一个运行的位点保持不变。两个保留期脚本不提供冷复制，遇到带位点候选在写入前通过SQLSTATE 55000及ON_ERROR_STOP拒绝；独立新库正例确认无位点候选仍可清理、运行中控制行保留。两个显式租户wipe此前仅静态审查，2026-10-19 已增加隔离 PostgreSQL 动态执行；覆盖范围及未覆盖边界见§2.35。
- 本轮6类53项分批证据（含前一命令中通过的完整归档类，命令整体失败与后续修复均单列）分命令保存于[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末；P0切片改造不代表全局引用、完整冷回读/业务恢复或部署通过，REF/DBSAFE/R项目不据测试数量关闭。
- **当前源代码复验（2026-10-19，V320）**：`./mvnw -o -ntp -l logs/verification/workflow/pipeline-progress-archive-current-v320-20261019.log -pl batch-orchestrator -am '-Dtest=PipelineProgressArchiveIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，15/15、fail/error/skip均0、BUILD SUCCESS，44.36秒；测试包含运行树位点归档、冷副本核验、失败回滚及父/子并发保护。Testcontainers 清理后为0。该次只重验未变更的 V292 专项关系，不扩大为全模块回归，也不关闭 REF-01/DBSAFE-3 或任何 R 项。

### 2.6 RT-2 / REF-03 常规引用准入切片（2026-10-18）

Console 的创建/全量更新启用态、显式启用定义和日程、已启用定义的调度更新、严格及非严格配置导入已接入现有 `WorkflowDefinitionDagInspector`。Orchestrator 在根创建前由 `WorkflowGraphValidator` 重新读取启用 Job，JOB 空引用/查不到目标转为错误，查询故障直接传播。两模块的持久化模型不同，各自使用所属 Mapper，未引入 Console→Orchestrator 实现依赖或通用 FK 框架。

| 关系与身份 | 数据库保证 | 应用与本轮证据 | 仍开放 |
|---|---|---|---|
| 配置节点 JOB → `(tenant_id, related_job_code)` | 本轮没有添加条件 FK；草稿允许目标暂未就绪，不能用无条件 FK 强制所有草稿具备目标。现有父定义 FK 不等价于租户/聚合/状态一致性 | Console 同租户查存在/启用；缺失、异租户、停用分别拒绝；18项真实API/PG覆盖发布、两种启用、全量更新、调度更新、严格/逐项导入回滚、合法创建、草稿编辑及停用。错误使用现有VALIDATION_ERROR/400信封 | 系统自动创建的受管图、原生SQL的错父租户绑定及父删除/停用并发，按REF-02/04/RT-6处理，未宣称等效FK |
| 根启动 → 启用 Job 配置 → 运行树 | 后续JobInstance仍受V291租户复合FK及既有运行归属保护；本轮未改DDL | 3个运行校验单测先红；真实根入口3个无效引用场景断言request拒绝、无root/job/task/outbox，并保留合法启动/去重正例 | 定义/节点元数据被SQL错绑、父状态变更与启动交错、完整快照/恢复及目标环境仍需动态验证 |

草稿仍须遵守既有非空编码与拓扑要求；数据库默认的日程标志不等于定义已发布。运行快照不因本轮引用检查被重新解释。首次13项API/导入测试12失败、运行单测3失败；受影响回归116项中一个既有单测夹具缺合法Job而失败，补齐后先单跑失败项，再复跑该类7项通过。8类最终报告累计116个不同用例通过，不是一条命令全绿；前端17项仅为mock组件证据。实际命令、失败原件和清理见[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末。REF-03/ALIGN-DB-REF及R项仍未关闭。

### 2.7 REF-04 图与容量等待的父定义租户约束（2026-10-18）

V293将 `workflow_node`、`workflow_edge`、`workflow_launch_wait` 的父定义引用升级为 `(tenant_id, workflow_definition_id) → workflow_definition(tenant_id, id)`。先添加并验证复合FK，再移除各自旧单ID FK，全部在同一Flyway事务提交。保持NO ACTION删除语义；节点/边复用已有复合唯一索引，等待表补同租户父定义关联索引。适用全新库初始化，没有执行旧库在线升级或数据纠正。

应用侧在Console已解析租户和父定义后写固定父ID；配置导入与运行等待使用各自所属Mapper。数据库现在同时阻止原生SQL错租户插入、子记录改绑异租户父定义，以及父改租户与旧租户子写入交错提交。真实PG的8项用例验证合法插入、SQLSTATE23503、完整catalog/convalidated/删除动作/索引、显式清子后删父与双事务的真实阻塞关系。初次夹具缺日历及父改租户撞唯一键，保留为测试错误；修夹具后的有效首红为8项中5 failures/0 errors，修复后8/8通过。迁移与Console受影响回归命令及最终结果以验证矩阵为准。

本切片不添加JOB业务编码无条件FK，不改变草稿允许未就绪目标的策略；边端点是否存在、WAIT原请求目标/业务日/状态一致、Job运行子树与归档并发、其它活跃配置和系统受管图仍按REF-02/03/04、RT-4/6推进。R20/R33/R36不因此关闭。

### 2.8 RT-4 等待准入、恢复与原请求身份（2026-10-18，局部实施）

本切片按[数据库总方案 §3.2](database-integrity-index-partition-remediation-2026-10-18.md)对齐应用与Mapper保证范围；尚未增加新的数据库外键，不关闭RT-4。

| 关系/入口 | 本地实现的保证 | 数据库及生命周期边界 |
|---|---|---|
| Job依赖等待 → 原请求 | `JobDependencyWaitingLaunchService`在事务内绑定tenant、锁原请求并核对tenant/request/job/Workflow/date/dryRun和可受理状态；已有等待必须同Job/date且仍WAITING；等待插入、检查和原请求WAITING写入均须恰好1行，否则回滚。公开延期包装入口也有事务 | Mapper按同一原请求做`INSERT SELECT`并校验持久载荷，源请求共享锁防单条SQL内的并发改写；该表仍无原生请求FK，直接SQL/父侧保留期不因此受完整保护 |
| Workflow容量等待 → 原请求/槽位 | `WorkflowLaunchWaitService`同样先锁原请求；Mapper绑定Workflow/date/dryRun及载荷身份，冲突时只接受同槽位WAITING并保留首次载荷；PROCESSING更新0行视为冲突，等待插入回滚 | 已有请求tenant/request复合FK及V293父定义tenant FK继续生效；这些FK不验证业务日、dryRun、payload内容或原请求可受理状态 |
| 容量恢复 → 持久请求 | 领取结果返回Workflow/date/dryRun，恢复器在调用启动前与载荷核对，拒绝混入Job目标；根启动首次检查及T1持锁后再次核对原请求身份，包含dryRun | 拒绝路径统一原请求→等待行锁顺序，LAUNCHED保留且等待收敛RESUMED，已有拒绝/重复/放弃终态不复活；必需原请求拒绝更新0行回滚等待状态。不存在等待或已终态的可选CAS仍允许幂等0行 |
| Job就绪检查 → 实例准备T1 | `DefaultLaunchService`在准备事务重新锁定并核对原请求身份、待处理状态与原记录主键；`selectByTenantAndRequestIdForUpdate`清除MyBatis查询缓存并禁用二级缓存，确保重检实际执行行锁SQL | 保护实例创建前边界；已提交实例后的派发/终态收敛、父Workflow归属及完整归档协议仍须独立推进，不能将准入校验扩称为完整运行生命周期保护 |

两个等待Mapper按当前`JsonUtils`真实序列化契约核对`LocalDate`数值数组`[year,month,day]`，没有改全局日期格式或添加旧格式兼容。最初仅负例通过时曾漏掉合法载荷被字符串日期比较拒绝的问题；后续必须同时保留真实载荷正例、首次载荷不覆盖、回滚和双事务用例，不能只凭拒绝测试判定可用。

**后续实施**：Job依赖等待恢复时的payload/原请求身份及取消协议已按§2.9局部落实；接下来统一原请求保留期、等待终态/审计与父删除顺序，随后落地适合的原生FK。当前`cleanup-success-instances.sql`和`cleanup-historical-failures.sql`存在终态原请求删除路径，不能忽略等待清理和取证保留直接加约束。其它RT-4账本（Trigger Outbox、misfire、occurrence与每日预期）的关系、SQL旁路和父侧更新/删除仍逐项开放；RT-5/6及联合验收不受本切片替代。实际失败、回归、环境与证据详见[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末。

### 2.9 RT-4 Job等待恢复与取消协议（2026-10-18，局部实施）

承接§2.8的剩余恢复任务，`JobDependencyWaitingLaunchReconciler`现在先经Service在事务内绑定数据库租户并读取原请求，再核对等待行、载荷和原请求的tenant/request/Job/Workflow/date/dryRun；合法身份才进入依赖就绪检查。依赖未就绪不再掩盖坏载荷；合法但损坏的依赖快照仍按既有`DEPENDENCY_SNAPSHOT_INVALID`保持等待，不改为取消。

| 条件 | 状态/事务处理 |
|---|---|
| 载荷或等待身份损坏，未创建Job | `rejectInvalidWait`按原请求→等待行顺序加锁，重读当前记录，写`LAUNCH_PAYLOAD_INVALID`，等待CANCELLED与原请求REJECTED同事务提交；任一必需写入不是1行均回滚 |
| 扫描后等待已被处理、载荷/目标变化或原请求已合法恢复 | 比对记录ID/Job/date/payload及当前身份，过期拒绝决定不再写状态；下一轮按新事实处理 |
| 原请求已LAUNCHED | 保留原终态并释放等待；REJECTED/DUPLICATE/GIVE_UP保留原终态并取消等待，不复活终态 |
| 原请求亲自创建的Job已提交、派发尚未完成 | 持原请求锁检查同tenant与原请求PK的Job实例，记录`LAUNCH_ALREADY_PREPARED`并保留原请求和等待，留给既有CREATED恢复/派发链处理；不因坏等待拒绝已准备实例 |
| 原请求已不存在 | 取消孤立等待；数据库父删除/保留和原生FK仍由后续生命周期整改承担 |
| 一条等待的数据库事务失败 | 该条回滚并记录错误，继续同租户下一条；不把错误吞作成功，也不让坏行阻断整个租户扫描 |

新增等待行锁读取清除MyBatis缓存并直接访问数据库；实例存在性查询保留既有V196的`trigger_request_id`索引，本轮没有新增索引/迁移或提出性能结论。真实PG类55项及新增普通角色取消1项分批通过（共56个不同用例），其中23项为本切片新增，覆盖上述身份、回滚、普通RLS角色、过期扫描与实例准备/取消双事务真实锁等待；原33项继续通过。受影响旧链路结果、首红及环境边界以[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末为准。

本地应用/Mapper协议不等于原生FK。节点/根传播及取消提交后重扫已按§2.10局部落实；原请求/两类等待保留期、终态审计、父删顺序和复合FK已按§2.11局部落实；其它RT-4账本、归档后的热冷实例引用、跨进程故障与目标部署仍开放。`LAUNCH_ALREADY_PREPARED`的本轮证据证明不会提前拒绝已提交实例，不宣称完成整个CREATED灾难恢复矩阵。

### 2.10 RT-4 延期子请求拒绝向Workflow传播（2026-10-18，局部实施）

真实根启动复现了§2.9之后的缺口：等待CANCELLED、子原请求REJECTED已经提交，但JOB节点仍READY、根仍RUNNING。不能只结束等待账本而宣称恢复收敛。本切片复用现有节点失败、失败边派发、级联跳过和根终态Outbox，不新建事件总线、调度器或数据库结构。

1. **新内部子请求固定节点尝试**：`ChildJobLaunchSupport`在创建内部子请求时把实际`workflow_node_run.id`写入受系统控制的`params._parentNodeRunId`，并将完整命令固定到已有`trigger_request.launch_payload`。来源参数中的同名内部键不能覆盖真实绑定；原请求必须插入1行才能调用启动。该键不是租户配置项，不按节点编码猜测新尝试，也没有旧快照兼容回退。
2. **已取消记录仍可重扫**：既有Job等待恢复器在处理WAITING后另扫CANCELLED+REJECTED，条件包括同tenant/parent run、活跃根、READY且未绑定Job的最新JOB尝试，与原请求快照中的尝试ID精确相等，且没有该原请求亲自创建的实例。即使本轮没有WAITING，也会扫描尚未传播的拒绝；暂停根暂缓，恢复后可继续。
3. **沿既有DAG事务传播**：`WorkflowNodeDispatchService.rejectDeferredChildRequest`事务内绑定数据库tenant，复核原请求/取消记录、租户内父运行、最新节点尝试、原请求快照身份和冻结图中的Job目标。节点锁读取清除MyBatis缓存，锁后重读根状态；终态、新尝试和已绑定实例不被覆盖。随后复用现有失败处理，节点FAILED、失败分支/跳过、符合条件的根终态和终态Outbox同事务提交；并行活跃节点仍按既有DAG收敛，不强制提前终结根。
4. **提交后恢复边界**：取消事务已提交而节点传播事务失败时，节点/Outbox回滚，下一轮按CANCELLED事实恢复；原请求和等待不能在该恢复完成前被保留期脚本提前删除。定向注入验证事务中断后重扫，不扩称为真实进程强杀或目标环境灾备演练。

本轮真实根用例覆盖绑定防覆盖、重复扫描、取消提交后节点写失败/回滚/重扫、暂停/恢复、旧尝试不影响新尝试、冻结失败分支及两个恢复器并发最终唯一终态事件；另有普通非绕过RLS角色的实际传播/Outbox事务验证。受影响回归、首红与夹具错误以[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末为准，不与前几轮用例数量累加进度。

**后续更新**：原请求/两类等待保留与终态审计、父删除及原生复合FK已按§2.11局部落实。仍需推进其它RT-4账本、跨路径暂停/归档与多节点全局锁并发、完整热冷/故障恢复和最终SIM/目标部署。JSON尝试绑定与应用/Mapper复核不是数据库外键；缺失绑定的损坏记录不允许回退到同编码最新节点。R项完成数不变。

### 2.11 RT-4 等待原请求外键、保留与显式清理（2026-10-18，局部实施）

按总方案§3.2对齐同一关系的应用守护、原生约束与父侧生命周期，不以补FK替代业务恢复协议。

| 关系 / 保证范围 | 应用与数据库对齐 | 父侧生命周期 / 未覆盖范围 |
|---|---|---|
| `job_dependency_waiting_launch(tenant_id,request_id)` → `trigger_request(tenant_id,request_id)` | `LaunchRequestIdentityGuard`、Job等待准入/恢复核对目标、业务日、dryRun、可受理状态及必需写入行数；V294新增并验证`fk_job_dependency_wait_request`，父删除NO ACTION，缺父/跨租户直接SQL拒绝23503 | 终态等待必须先按同一保留协议显式清理，再删父；FK只保证声明键，不检查Job/bizDate/JSON/状态，也不是普通角色RLS验收 |
| `workflow_launch_wait(tenant_id,request_id)` → 同一原请求候选键 | 容量等待与根启动复核相同身份/槽位；V280 在表创建时直接建立 `fk_workflow_launch_wait_request_retention`，租户复合键使用 `NO ACTION`，不先创建级联版本；父定义租户 FK继续保留 | 不能因父请求清理级联抹去WAITING或终态取证；原请求、等待、根启动及暂停/恢复业务协议继续按§2.8–2.10执行 |

**保留与锁顺序**：两个原有保留期脚本共用内部`cleanup-trigger-request-retention.sql`，在原事务内按原请求→等待→父删除执行。只选择创建及最后修改均早于窗口的终态请求；FORWARD_FAILED仍可恢复，不属候选。WAITING/未知等待状态、近期更新的等待及原请求均保留。原30日成功/1小时失败窗口是最小年龄，并不承诺到期必删；未传播拒绝仍关联热/冷Workflow时继续保留，包括暂停和已终结但仍在留存的父运行。

候选还排除冷热Job、冷热Workflow、冷热Trigger outbox、scheduled occurrence/替换历史/补跑审计、expected work及misfire的已保存关联。单批默认1000、允许1..10000，按创建时间及ID排序并锁原请求`FOR UPDATE SKIP LOCKED`；终态子等待清理后，只有仍无等待的原请求可删。非法参数、约束或SQL失败非零退出并回滚原事务前序删除。两个入口必须通过psql `-f`保留同目录include解析；共享SQL不能脱离事务独立调用。

**清理边界**：直接实例清理不搬冷表。checkpoint或冷热Workflow节点/根仍引用候选Job时，在任何写入前以55000拒绝，转Orchestrator完整运行树归档。该前置扫描不证明并发归档封口；执行独立销毁SQL仍须确认目标并静默对应写方。显式tenant wipe、validate-seed与SDK fixture按原所有权范围先清两类等待再清父；SDK先固定自有根运行ID，覆盖内部子请求，实例先于原请求删除。同租户其它Workflow与其它租户保留。

**本地证据**：初始21项真实PG用例18 failures/0 errors；实现后21项通过。扩展双事务、批次/回滚及冷热引用后，4个受影响类共50项、0 failures/errors/skips，Maven终态日志BUILD SUCCESS（未单独保存该shell退出码）；另单跑SDK清理范围1项通过、退出0，合计51个不同通过观察。50项命令有Surefire退出30秒后强制终止fork日志，未将其记作正常后台线程退出。V294真实新库迁移及catalog验证、两类父删/子插双事务、父引用保留、显式子父清理和合法批次推进均有动态证据；原始失败、源码指纹、实际命令与静态结果见验证矩阵及本地`wait-request-retention-fk-20261018/`。

**V295 应用层与数据库对齐证据（2026-10-18）**：新增 `fk_expected_work_trigger_request` 与 `fk_scheduled_occurrence_trigger_request`，均为 `(tenant_id,业务请求号)` 复合 `NO ACTION` 外键；应用写入仍负责 Workflow、业务日、事件类型和状态语义。真实 PostgreSQL 新库上 `WaitRequestRetentionIntegrationTest` 34/34、`ScheduledOccurrenceMapperIntegrationTest` 26/26、`TriggerScheduledOccurrencePlannerTest` 2/2、`BatchDayMetricsMapperIntegrationTest` 44/44 通过；覆盖合法父请求、孤儿直接写入拒绝、父删除拒绝和已有业务写入顺序。一次回归首红证明旧夹具直接写请求号而未先建父请求，已按真实 Workflow 日历和请求目标约束修复；未放宽外键。日志：`workflow-daily-ledger-fk-final-20261018.log`、`workflow-daily-ledger-fk-trigger-regression-final-20261018.log`、`workflow-daily-ledger-fk-expected-work-regression-final-20261018.log`。这只关闭两条账本关系的本地定向证据，不关闭 `trigger_misfire_pending.catch_up_request_id`、`trigger_outbox_event.request_id` 或 RT-4 全局并发/热冷恢复。

**2026-10-19 现状复核**：逐表矩阵中旧有“原请求状态更新0行只日志告警”描述与现行源码不符，已按代码和测试修正。`captureOrDefer` 对等待插入、检查记录和原请求状态更新都调用 `requireWaitWrite`；非1行抛 `STATE_CONFLICT`，事务回滚。复跑 `./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=JobDependencyWaitingLaunchServiceTest,WaitingLaunchIdentityIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，69项服务单测 + 56项真实 PostgreSQL 集成测试，合计125/125、failures/errors/skips均0、BUILD SUCCESS。单测参数覆盖插入/检查/请求转换三种零行；PG参数化用例覆盖Job、wrapper和Workflow的原请求CAS零行及此前等待/检查写入回滚。该验证纠正的是文档漂移，没有新增代码或宣称关闭REF-04/DBSAFE-3。

**应用层守护一致性规则**：任何 Service、Mapper、批量导入、初始化、清理和恢复入口写入上述引用时，必须先在同一租户/事务上下文建立或解析 `trigger_request(tenant_id,request_id)`，并使用同一请求号、Workflow 目标和 `bizDate`；应用提前拒绝与数据库 `23503` 拒绝必须映射到同一冲突契约。数据库 FK 只保证父行存在和租户键一致，不替代同 Workflow、事件类型、已受理状态、业务日和快照匹配校验；新增或修改任一侧时必须同步复核另一侧及父侧归档/保留顺序。

**继续开放**：其它RT-4弱引用账本新增写入与清理间的并发保护、原请求完整热冷生命周期、全部wipe/seed场景与恢复矩阵、RT-5/6全入口锁顺序、容量/目标部署和最终SIM。NOT EXISTS排除不是弱引用并发等效外键；本轮未证明生产规模查询代价、普通角色全表隔离或真实进程故障恢复，不关闭REF或R项。

### V296 misfire 请求引用与应用清理拓扑对齐（2026-10-18）

新增 `fk_trigger_misfire_pending_catch_up_request`，以 `(tenant_id,catch_up_request_id)` 指向 `trigger_request(tenant_id,id)`，保持 `NO ACTION`，保护已接纳 misfire 请求的来源身份和租户边界。`TriggerMisfirePendingMapper` 仍须在应用层校验 Workflow、业务日、请求状态和审批生命周期；全新 PostgreSQL 迁移库上定向回归 13/13 通过，覆盖合法关联、孤儿/跨租户写入拒绝和父删除拒绝。首次主链回归暴露清理顺序缺陷，已按外键拓扑将 misfire pending、scheduled occurrence、outbox 置于 `trigger_request` 前清理；真实 Quartz misfire 类已进一步修正为 8/8 通过。

### V297 Trigger Outbox 请求引用与应用事务对齐（2026-10-18）

新增 `fk_trigger_outbox_event_request`，以 `(tenant_id,request_id)` 指向 `trigger_request(tenant_id,request_id)`，保持 `NO ACTION`。这与 `TriggerOutboxDomainEventPublisher` 的同事务写入约束一致，也与热表/归档表先保留、后清理请求的顺序一致；不允许通过数据库级联删除 `NEW`、`PUBLISHING` 或历史投递证据。应用层仍负责同事务创建父请求、payload 信封中的 tenant/request 一致性、Workflow/业务日/触发类型/请求状态校验和 relay 状态推进。

全新 PostgreSQL 迁移库上 `TriggerDedupRequiresNewIntegrationTest` 3/3、`TriggerAsyncLaunchIntegrationTest` 4/4、`QuartzJdbcClusterIntegrationTest` 2/2、`QuartzLaunchJobIntegrationTest` 1/1、`QuartzMisfireRecoveryIntegrationTest` 8/8 通过。首轮回归发现两个手工 Outbox 场景写入孤儿 request，已补为合法 Workflow 父请求；另发现 Quartz fixture 使用大写日历编码而应用规范化为小写，已统一 fixture。日志：`workflow-daily-outbox-fk-regression-20261018.log`、`workflow-daily-outbox-fk-trigger-async-final2-20261018.log`、`workflow-daily-outbox-fk-quartz-fixtures-final-20261018.log`、`quartz-misfire-class-final2-20261018.log`。这关闭本地真实 Quartz misfire 定向证据，不等同于完整 SIM、容量或目标部署验收。

### 2.12 文件引用应用守护与补偿入口验证（2026-10-18）

- `RelatedFileReferenceValidator` 以 `(tenant_id,file_id)` 查询文件，缺失和异租户统一返回 `NOT_FOUND`；Launch、Workflow、Partition、Task 创建及 Compensation 提交共用该守护，调用必须发生在各自持久化副作用之前。数据库当前没有为所有 `related_file_id` 建立复合 FK：这些引用跨 Job/文件/归档生命周期，逐表策略暂按弱关联治理；不得将应用校验描述成数据库约束。若未来要求 DB 直接写也强制存在性/租户归属，必须先设计文件归档、物理清理和运行树冷历史的联合父子生命周期，再补复合唯一键/FK及删除保护。
- 新增真实 PostgreSQL 补偿入口负例：租户 A 提交租户 B 的有效文件 ID，真实 validator 返回 `error.file.record_not_found`；`compensation_command` 无新增记录，租户 B 原文件仍存在。单项1/1、补偿集成类3/3通过，日志：`related-file-compensation-cross-tenant-20261018.log`、`related-file-compensation-class-final-20261018.log`。既有 `JobLaunchToFinishLifecycleIntegrationTest` 和 `BundleLaunchPartitionExpansionIntegrationTest` 另覆盖任务/分区启动负例；这些证据仅证明应用入口，不证明任意 Mapper/JDBC 写入均被数据库拦截。
- 验收边界：后续需继续对齐所有直接写入口、父文件删除/归档与子运行并发、跨库/普通 RLS 角色及 JobTask/Step 的聚合一致性；不得以这一项局部 PG 通过关闭 REF-02、REF-06、DBSAFE-3 或 R20/R33/R36。

### 2.13 Job dependency 上游关系的数据库与应用校验对齐（2026-10-18）

- V298 为 `job_dependency(tenant_id,upstream_job_code)` 增加复合 `NO ACTION` 外键，引用 `job_definition(tenant_id,job_code)`；先 `NOT VALID` 再同一迁移内 `VALIDATE`。下游原 FK 的 `ON DELETE CASCADE` 保持不变：删除下游定义仍可清理其所属边，删除被其它作业引用的上游则必须先显式调整依赖。
- 真实 Console/PostgreSQL 用例验证合法同租户边可写、孤儿/仅存在于另一租户的上游直接 SQL 被拒、legacy scalar更新失败时旧边保留、删除仍被引用的上游被拒且依赖行保留。`ConsoleJobDefinitionMutationIntegrationTest` 全类最终15/15，日志 `job-dependency-upstream-fk-class-final2-20261018.log`。既有 `DefaultConsoleJobDefinitionApplicationService` 在普通保存路径校验同租户上游及依赖环；V256 legacy scalar同步现在也受数据库约束保护。完整迁移安全门禁使用仓库原生Squawk二进制重跑59个新增/改动迁移，0 issues并退出0，日志 `migration-safety-native-20261018.log`。`/usr/local/bin/squawk` 包装器仍缺本机可选依赖，常规入口不可用。
- 本证据没有验证任意 SQL 对依赖环的禁止（环属于图业务规则，DB FK不表达），也没有全量配置导入/初始化旁路与运行恢复验收。后续应同步检视保存错误映射和受管初始化路径，不能据FK通过关闭REF-04/05或R20/R35。

### 2.14 Job monitoring policy 的租户归属约束（2026-10-18）

- V299 将 `job_monitoring_policy.job_definition_id` 的单列 `ON DELETE CASCADE` FK替换为 `(tenant_id,job_definition_id) → job_definition(tenant_id,id)` 复合 FK，仍保留 `ON DELETE CASCADE` 策略从属语义。引用父键由 V237 已有 `uk_job_definition_tenant_id` 支撑；迁移以 `NOT VALID` 后立即 `VALIDATE` 完成，不新增索引或重写行。
- `ConsoleJobDefinitionMutationIntegrationTest` 新增真实 PostgreSQL 用例验证同租户策略成功、租户 A 绑定租户 B 的 Job id 被拒，以及删除同租户 Job 后策略仍按既有级联规则清除。单项1/1、全类16/16通过；最终日志 `job-monitoring-policy-tenant-fk-class-final-20261018.log`。现有 Console 创建/更新/复制与配置导入路径继续同时写 tenant 和 definition id；原生 FK兜底避免直接 Mapper/SQL 错绑租户。
- 全部60个新增/改动 Flyway 迁移通过仓库迁移安全门禁的原生 Squawk 扫描，0 issues、退出0；最终日志 `migration-safety-native-final-20261018.log`。
- 全量配置初始化、RLS普通角色和并发删除/策略写入仍需单独验收；应用可见错误契约及 REF-04 其它运行树关系未关闭，不能据此关闭 DBSAFE-3/R20/R36。

### 2.15 JobInstance 到 TriggerRequest 的租户复合外键（2026-10-18）

- V300 将分区父表 `job_instance` 原单列 `trigger_request_id→trigger_request(id)` 替换为 `(tenant_id,trigger_request_id)→trigger_request(tenant_id,id)`。父复合唯一键由 V247 提供；目标采用用户确认的全新数据库部署，因此在空的分区引用表上直接创建并立即验证约束，沿用 V291 的 PostgreSQL 分区表约束模式，不做旧库数据兼容或回填。
- `trigger_request_id` 可空，但既有 `ck_job_instance_trigger_source` 只允许 `trigger_type='MANUAL'` 时为空；复合 FK 的 NULL 行为不扩大该业务例外。引用请求使用 `NO ACTION` 默认删除语义，保留期删除需先移除热运行引用，现有 retention 脚本已有引用排除条件。
- 为支持父请求删除时的反向查找，并保留按全局请求 ID 的索引前缀查询，V300 将 `idx_job_instance_trigger_request_id` 从 `(trigger_request_id)` 调整为 `(trigger_request_id,tenant_id)`；分区迁移演练脚本同步使用相同租户复合 FK 与查询索引。
- V300 单迁移由仓库随附的原生 Squawk 二进制扫描，0 issues；日志 `logs/verification/workflow/migration-safety-v300-20261018.log`。既有 V236–V299 的60份迁移扫描见 `migration-safety-native-final-20261018.log`。
- `LocalFlywayPlatformMigrationsIntegrationTest` 在全新 PostgreSQL 17.11 上验证迁移应用、结构、重复 migrate 与 validate，并通过直连 JDBC 验证同租户有效引用、跨租户引用拒绝、孤儿引用拒绝、MANUAL 空请求成功、非 MANUAL 空请求被 CHECK 拒绝，以及仍有关联的父请求不能删除。最终全类2/2、0 failures/errors/skips，日志位于 `logs/verification/workflow/job-instance-trigger-request-tenant-fk-20261018.log`。
- 关联的 Trigger 应用路径定向覆盖8个集成类共65项通过：外部事件 API、Quartz JDBC/Job/misfire、异步投递、去重、misfire Mapper 和 TriggerService；Orchestrator 受影响类9类共105项通过。Trigger 两个测试夹具原先全表删除 occurrence/request，导致已有大表锁等待；现已限定到各自创建的 Workflow/request，在每用例后清理夹具，并修正 Outbox→TriggerRequest 清理顺序以符合 V297 NO ACTION。合并定向回归日志为 `logs/verification/workflow/job-instance-trigger-request-trigger-regression-final-20261018.log`；迁移日志为 `job-instance-trigger-request-tenant-fk-20261018.log`，Orchestrator 日志为 `job-instance-trigger-request-orchestrator-regression-20261018.log`。Trigger 回归65项为单条 Maven 命令完整BUILD SUCCESS。
- 此项只关闭 `job_instance.trigger_request_id` 的强引用租户归属证据；不关闭 `related_file_id`、`parent_instance_id`、运行子树子表关系、热冷归档/清理并发、应用全入口错误契约或 REF-04 / DBSAFE-3 整体验收。

### 2.16 Job 依赖图并发防环与应用/数据库锁对齐（2026-10-18）

- V301 为 `job_dependency` 增加数据库 `BEFORE INSERT/UPDATE` 防环触发器，按租户取得事务级 advisory lock，再检查新增边是否能从上游到达下游；命中时以 SQLSTATE `23514`、约束名 `ck_job_dependency_acyclic` 拒绝。覆盖直接 SQL 与 V256 legacy scalar 同步等绕过 Console 的写路径；删除边只会减少可达路径，无需防环锁。
- Console `validateDependencies` 在读取整张租户依赖图之前取得同一 advisory lock，锁持有到定义/边更新事务提交。这样应用给出稳定领域校验，数据库约束直连写入并发防护；两层共同使用 `hashtextextended('batch.job_dependency:' || tenant_id, 0)`，避免锁键/命名空间漂移。外键仍负责上游存在与租户归属，触发器负责图无环，应用层继续负责友好校验与错误契约，不能把三者合并宣称成同一个约束。
- 首红时真实 PostgreSQL 双事务各写 A→B 与 B→A，二者均提交，证明原应用外校验可并发合成环，原件 `logs/verification/workflow/job-dependency-cycle-db-red-20261018.log`；加入 V301 后定向同一场景验证恰有一条提交、一条被约束拒绝，最终边数为1。新增直接 UPDATE 形成环及 V256 legacy scalar 同步形成环两项也均被拒绝，legacy 定义更新和依赖边整体回滚。全新 PostgreSQL 17.11 从空库应用全部296个迁移到V301成功；`ConsoleJobDefinitionMutationIntegrationTest` 全类19/19通过，覆盖应用保存/更新取得锁、直连 SQL 的 INSERT/UPDATE/legacy 写入防环、上游 FK 和父删除语义，最终日志见验证矩阵。
- 静态验证：`python3 scripts/ci/check-schema-governance-assets.py` 通过；`BASE_REF=origin/main bash scripts/ci/validate-flyway-schema.sh db/migration` 通过，保留既有 V31/V238/V242 缺口警告；仓库 `check-migration-safety.sh origin/main` 因其 Squawk 包装器缺少 optional dependency 退出1，不记作通过。改用仓库原生 Squawk 二进制扫描61个相对 origin/main 已跟踪新增/改动迁移，及未跟踪 V301 单文件，合计62个文件均为0 issues、退出0。
- **租户批量初始化的前向引用（2026-10-18）**：新增真实 PostgreSQL 用例验证 `TenantConfigBatchInitRequest.jobDefinitions` 中下游 Job 排在其上游前仍可成功导入。旧实现按请求顺序逐项插入，V256 同步触发器在上游尚不存在时触发 V298 `fk_job_dependency_upstream`，使 strict bundle 整体回滚；首次定向执行因此按预期失败。修复后，对无重复 Job code 的有效 DAG 做稳定拓扑排序，让包内上游先于下游写入；重复/缺失身份继续保持原请求顺序，包内循环仍交由既有数据库防环约束拒绝，不放松 FK 或 strict 原子性。用例断言租户批处理成功、两个 Job 均持久化且依赖边准确；定向新用例通过，随后 `TenantConfigBatchInitStrictAtomicityIntegrationTest` 全类6/6、0 failures/errors/skips、BUILD SUCCESS，空库应用308个迁移至V313。此切片仅关闭合法批量初始化顺序与 FK 的冲突；数据库拒绝的领域错误映射、配置导入所有入口/旁路、RLS 角色及跨入口并发仍待验收。
- **应用侧依赖守护与稳定错误（2026-10-18）**：新增真实 PostgreSQL 负例断言缺失上游应在写入前被拒绝、JobDefinition 零副作用，并返回稳定 `error.common.invalid_argument`。首红证明旧批量初始化泄漏 V298 外键异常的数据库细节。实现现在使用与 V301 trigger 同 tenant advisory lock；在与 FK 相同的 tenant/upstream identity 下核验父 Job，并检查新增边是否构成环。定向新增用例1/1，随后完整初始化集成类7/7、0 failures/errors/skips、BUILD SUCCESS。应用守护仅改善该写入口的契约与早期校验，V298 FK 和 V301 trigger 仍是并发/直写兜底。
- 本切片关闭 RT-5 中“依赖图并发可成环”的数据库保护缺口，不关闭 REF-05/RT-5 全项：全量配置导入、受管初始化/运维旁路的领域错误契约，所有租户 RLS 角色与更大并发矩阵仍需覆盖。依赖图验证按 §2.3 与 DBSAFE-3 逐关系记录；不增加 REF/R 项完成数，不宣称 Workflow/每日连续性整体验收完成。

### 2.17 日历依赖的数据库完整性与应用守护边界（2026-10-18）

- V302 为 `calendar_dependency` 增加同租户上下游自然键 `NO ACTION` 复合FK，并在 INSERT/相关 UPDATE 时用事务级租户锁拒绝自环以外的多节点环。数据库约束与触发器共同保护直接SQL和所有绕过应用层的写路径；FK负责目标日历存在且属于同租户，触发器负责并发下的图无环。
- 源码检查未发现生产代码调用 `CalendarDependencyMapper.insert`，也未发现日历依赖配置的生产写Service/API。因此本轮没有可对齐的既有应用 Guard，不能把数据库保护表述为“应用与DB双层校验完成”。当前事实应记录为：应用写入口不存在；数据库约束负责直接写完整性；`BatchDayOpenScheduler` 读取依赖并处理等待，不是写入守护。
- 后续新增应用写入口时必须先在同一租户与写事务上下文中按 `(tenant_id,calendar_code)` 校验两端日历身份，再使用与数据库完全相同的事务锁键 `hashtextextended('batch.calendar_dependency:' || tenant_id, 0)` 检查启用边成环；重试、批量导入、复制和初始化入口共用该业务语义。复合FK无论边是否启用都要求父日历存在且同租户；环约束只对启用边生效。应用预检与数据库拒绝需映射为稳定的输入/冲突错误；数据库 `23503`/`fk_calendar_dependency_*` 与 `23514`/`ck_calendar_dependency_acyclic` 不得被吞掉或无限重试。父日历物理删除由 `NO ACTION` 阻止；软删除、停用是否允许依赖边继续存在属于应用业务规则，须与开日调度器读取条件一致并单独验收。
- 首红及最终验证、迁移和静态门禁见[验证矩阵 V302 条目](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-18-v302日历依赖引用与环完整性)。该切片只关闭 `calendar_dependency` 的数据库完整性定向证据，不关闭 REF-05、RT-5/REF-05 其它活跃配置关系或 DBSAFE-3 整体，不增加 R 项完成数；应用写路径、软删除语义、SIM、容量与部署验收仍开放。

### 2.18 日历假期租户引用与应用/数据库身份对齐（2026-10-18）

- V303 在 `business_calendar(tenant_id,id)` 增加复合唯一键，并将 `calendar_holiday(tenant_id,calendar_id)` 从单列 `calendar_id` 外键升级为同租户 `NO ACTION` 复合外键。DB 约束负责最终拒绝跨租户配对和父日历物理删除，直接 SQL 也受保护。
- Console 假期导入先以当前租户和路径中的日历 ID 解析父日历，再以同一租户及解析出的父 ID 写子记录；该预检与复合 FK 使用完全相同的引用身份。应用预检承担请求归属和可见性，复合 FK 是并发竞争及绕过应用写入时的最终完整性边界。数据库 FK 冲突由 Console 通用异常处理映射为稳定 `CONFLICT`；不要以预检成功代替数据库约束，也不要因数据库已有约束而省略请求租户校验。
- 全类验证包含两侧证据：Orchestrator真实PG用例证明同租户正例可写、跨租户 `(tenant_id,calendar_id)` 组合被 DB 拒绝；Console API真实PG用例证明租户解析后的日历可经正式假期导入入口保存。API 并发错配错误响应未在本切片独立注入验证，错误映射依据现有异常处理代码路径记录。V112 `calendar_holiday_calendar_id_fkey` 在新库迁移中移除，不保留旧库兼容路径。
- 真实PG、首红、静态迁移门禁和清理证据见[验证矩阵 V303 条目](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-18-v303日历假期同租户外键与应用身份对齐)。本切片只证明该引用关系，不关闭 REF-05、DBSAFE-3、完整 SIM、容量或部署验收，不增加 R 项完成数。

### 2.19 日历组租户归属与 GROUP 假期代表日历（2026-10-18）

- 首红用例发现 `business_calendar.group_code` 可跨租户指向 `calendar_group`，`calendar_holiday.group_code` 可引用异租户/不存在的组；已有 V124 CHECK 只保证 GROUP 行的 `group_code` 非空，不能保证组存在或属于当前租户。GROUP 假期的 `calendar_id` 还必须是该租户且已加入所引用组的代表日历。
- V304 在 `business_calendar` 与 `calendar_holiday` 分别增加 `(tenant_id,group_code)` 到 `calendar_group(tenant_id,group_code)` 的 `NO ACTION` 复合FK；增加 `calendar_holiday(tenant_id,group_code,calendar_id)` 到 `business_calendar(tenant_id,group_code,id)` 的 `NO ACTION` 复合FK，保证GROUP假期代表日历真实属于同租户同组。可空 group_code 保持 CALENDAR 行现有语义；V124 非空 CHECK 继续覆盖 GROUP 行。父组或代表日历被引用时物理删除由数据库拒绝。
- 全仓源码检查未发现 `calendar_group` 的生产写Service/API；Console日历写入口只接受日历/假期，不接收组配置，GROUP组数据目前只能经受管初始化或直接SQL建立。因此不声称当前有应用层组Guard；数据库约束保护已有直接写路径。若增加组管理API/配置导入，应用须用相同 `(tenant_id,group_code)` 和 `(tenant_id,group_code,calendar_id)` 身份预检，并将FK竞态拒绝映射成稳定领域冲突。`calendar_group.enabled` 的禁用是否应阻止新增成员或继续应用组假日，FK不能表达，仍按当前批日读取规则单独评审，不由本迁移改变。
- 定向首红、真实PG全类和静态门禁见[验证矩阵 V304 条目](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-18-v304日历组租户归属与group假期代表日历)。本切片只覆盖三条组引用关系，不关闭 REF-05/DBSAFE-3、全量导入/启用/删除入口审计、SIM、容量或部署验收，不增加 R 项完成数。

### 2.20 Workflow JOB 节点到分区 JobInstance 的父子并发保护（2026-10-18）

- **首红与根因**：`WorkflowArchiveIntegrityIntegrationTest#shouldRejectDeletingJobInstanceWhileHotWorkflowNodeReferencesIt` 在 V290 状态下复现：V290 的 `BEFORE INSERT/UPDATE` 触发器只执行无锁 `EXISTS`，而 `workflow_node_run.job_instance_id` 因 `job_instance` 分区唯一键包含额外分区键、子表又未携带该键，不能直接建立复合外键。直接删除仍被热节点引用的 JobInstance 会成功；子写校验也没有与父删除串行化。首红日志见验证矩阵 V305 条目。
- **V305 数据库保护**：复用 V290 触发器并以 `FOR KEY SHARE` 锁定同租户 JobInstance，父 `DELETE`/身份改键 trigger 检查并拒绝仍被热 `workflow_node_run` 引用的记录，返回 SQLSTATE `23503`、约束身份 `fk_workflow_node_run_job_instance_reference`。于是子写先获锁时，父删除等待后被拒；父写先获锁时，后续子写等待并在目标不存在时被拒。直接改写被引用实例的 id 也被拒绝。保证只覆盖热节点引用；归档节点保留历史身份时与 JobInstance 冷副本按既有归档流程保存，不通过级联删除冷证据。
- **清理与归档拓扑**：`scripts/db/wipe-tenants.sql` 原来先删 JobInstance、后删 Workflow 节点；现调整为先删除节点和根，再删 JobInstance。成功实例归档候选现在排除“Job 归档不会在同一事务删除”的热 Workflow 节点引用；独立 Workflow 冷归档后保留节点中的 Job ID，后续 Job 归档才删除父热记录。旧 wrapper 关联由 Job 归档事务原子冷拷贝并删除，保持原路径。这样应用候选筛选和数据库父侧守护针对同一引用身份，仍以数据库锁/触发器处理候选读取后的并发新引用。AdminTestDataCleanup 两种入口、`wipe-non-system-tenants.sql`、租户种子清理及 SDK fixture 按子先父顺序执行。
- **真实 PostgreSQL 证据**：首红证明无防护时独立 Workflow 引用导致 Job 归档在父删除处失败。修复后端到端断言 Job 首轮保留、Workflow 先冷归档且冷节点保持原 Job ID、Job 后续成功冷归档。`WorkflowArchiveIntegrityIntegrationTest` 7/7、`ArchiveColdStorageIntegrationTest` 13/13、`AdminTestDataCleanupControllerIntegrationTest` 2/2，共22/22通过；日志 `logs/verification/workflow/hot-node-reference-archive-regression-final-20261018.log`。旧 wrapper 和独立 Workflow 两条归档路径及父子锁竞态均覆盖。
- **边界**：V305 不形成 SQL 原生 FK，也不关闭 REF-02/DBSAFE-3/RT-6；仍需核对直接 SQL、分区淘汰、多租户 wipe/seed 的完整入口及其它运行子树/Outbox关系。锁协议只针对 `(tenant_id,id)` 的 Workflow Job 引用，不改变 Job/Workflow 状态、父实例 rerun 或 R01–R36 完成数。容量、完整 SIM、目标部署仍独立开放。

### 2.21 Catch-up audit 与 occurrence 的请求及业务日身份绑定（2026-10-18）

- **应用身份**：`ScheduledOccurrenceRecorder.acceptRejectedOccurrence` 先用租户、Workflow、scheduled fire time、bizDate 和 `CLOSED_DAY_REJECTED`/空 request 状态做条件更新；仅更新一行时，才在同一事务插入带相同 occurrence 身份和新 request ID 的审计行。Mapper 必须保留全部身份列，不得按 fire time 或 request ID 单独关联。
- **数据库守护**：V307 为 `scheduled_occurrence` 增加 `(tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id)` 唯一键，`scheduled_occurrence_catch_up_audit` 使用相同五列指向父 occurrence 的 `NO ACTION` 复合 FK。租户、运行定义、fire time、业务日及被接受的请求因此不可错配；父行保持审计引用期间不可删除或改写身份。它补充更宽松的 occurrence 原请求 FK，不改变 `PLANNED` 等无 request 记录语义。
- **验证**：`ScheduledOccurrenceMapperIntegrationTest#shouldRejectCatchUpAuditWhoseRequestOrBusinessDayDiffersFromOccurrence` 在迁移前先红，证明数据库原先分别接受错 request ID 和错 bizDate；V307 后两种直写都以命名 FK 拒绝。完整 `ScheduledOccurrenceMapperIntegrationTest` 与合法 Trigger catch-up 写入回归共28/28通过，日志 `logs/verification/workflow/scheduled-occurrence-catchup-identity-final-20261018.log`。Flyway Schema 治理与迁移次序门禁通过；Squawk 通过仓库原生二进制运行，对精确约束行的豁免仅适用于全新库、无业务数据/应用流量的初始化迁移。在线升级路径未设计或验证。
- **边界**：只关闭 catch-up audit→occurrence 这条五列身份关系的局部负例，不关闭 REF-04/DBSAFE-3 或 RT 项，不代表所有 occurrence 写入和保留清理已完成。跨服务恢复、SIM、容量及部署后观测仍开放。

### 2.22 批量日 replay entry 与 session 的租户归属（2026-10-18）

- **首红与根因**：`BatchDayDryRunSchemaIntegrationTest#shouldRejectReplayEntryWhoseTenantDiffersFromSession` 在 V308 前通过直连 PostgreSQL 将 `tenant_id=different-tenant` 的 entry 指向 `tenant_id=dry-run-schema-it` 的 session；旧单列 `session_id → session.id` FK 只保证父 ID 存在，不验证父子租户一致。
- **应用守护**：`BatchDayReplayService.submit` 构建批量 entries 后、调用 Mapper 前，核对重读 session 的租户和每个 entry 的租户都等于请求租户；错配时在写入前以状态冲突失败。此检查与数据库 FK 使用相同 `(tenant_id,session_id)` 身份，不改 `ON CONFLICT DO NOTHING` 的批量去重行为。
- **数据库守护**：V308 在 session 增加 `(tenant_id,id)` 唯一键，移除 entry 原单列 FK，并建立 `(tenant_id,session_id) → batch_day_replay_session(tenant_id,id)` `NO ACTION` 复合 FK。保留父子清理语义；冷表仍按独立归档生命周期处理，不把热 FK延伸到 archive 镜像。
- **验证**：先红命令 `./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=BatchDayDryRunSchemaIntegrationTest#shouldRejectReplayEntryWhoseTenantDiffersFromSession' '-Dsurefire.failIfNoSpecifiedTests=false' test`，失败点为断言期待完整性错误但旧Schema未抛出。V308 后真实 PG 负例单项1/1通过；受影响回归 `./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=BatchDayReplayServiceTest,BatchDayDryRunSchemaIntegrationTest,BatchDayReplayOutputsOnlyIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 共38/38、0 failures/errors/skips、BUILD SUCCESS，日志 `logs/verification/workflow/replay-entry-session-tenant-final-20261018.log`。全量迁移次序、Schema 治理和 Squawk 结果见验证矩阵 V308。
- **边界**：只关闭 replay entry→session 的父子租户配对缺口，不关闭 REF-04/DBSAFE-3 或 R 项；source/rerun/resultVersion 跨热冷血缘、session archive 迁移和目标部署仍按各自条目验收。

### 2.23 Replay preview token 与 session 的租户归属和清理顺序（2026-10-18）

- **首红与根因**：`BatchDayDryRunSchemaIntegrationTest#shouldRejectPreviewTokenLinkedToSessionOfDifferentTenant` 在 V309 前用直连 PostgreSQL 将另一租户 token 关联到已有 replay session，断言失败（旧 schema 未抛完整性异常）。V230 仅用 `(tenant_id,token_hash)` 标识 token，V231 将可空 `session_id` 加入 token，但没有验证引用父 session 的租户身份。
- **应用守护**：Mapper `linkSession` 只在父 session 同时满足 `session.tenant_id = token.tenant_id` 与 `session.id = requested sessionId` 时更新；0 行仍由 `BatchDayReplayService.submit` 作为 `active_session_lost` 冲突处理，整个 submit 事务回滚。
- **数据库守护**：V309 建立 `(tenant_id,session_id) → batch_day_replay_session(tenant_id,id)` `MATCH SIMPLE`、`NO ACTION` 复合 FK。`session_id=NULL` 的未链接预览令牌保持合法；对已链接令牌删除/改键父 session 被拒绝，不级联删除 token 或 session。
- **生命周期与验证**：`deleteExpired` 保留原有有界过期清理；只有 token 到达过期/消费保留界限并先被清理后，session 才可删除。`scripts/sim-4day/sql/clean-platform-runtime.sql` 显式纳入预览令牌，清理与父子关系一致。真实 PostgreSQL 测试覆盖跨租户直写拒绝、Mapper 跨租户链接返回 0、保留 token 阻止父删除、过期 token 清理后父删除成功及合法 replay 主链；`BatchDayReplayServiceTest`、`BatchDayDryRunSchemaIntegrationTest`、`BatchDayReplayOutputsOnlyIntegrationTest` 共42/42通过，日志 `logs/verification/workflow/replay-preview-token-session-tenant-final-20261018.log`。空库迁移和迁移安全静态结果见验证矩阵 V309。
- **边界**：只完成 preview token→session 的租户身份及短期保留保护，不关闭 REF-04/DBSAFE-3 或 R 项；session archive、热冷重试查询与其他 session 血缘仍开放。

### 2.24 Batch-day waiting launch 与 TriggerRequest 的应用/数据库引用对齐（2026-10-18）

- **首红与根因**：在 V309 schema 上新增真实 PostgreSQL 负例。跨租户 `tenant_id` 与同一 `request_id` 的等待行写入，以及删除仍被等待行引用的父请求，旧结构均未抛完整性异常；这证明此前只有 `request_id` 唯一身份和应用读取，并没有数据库父子租户约束。有效首红由 `BatchDayWaitingLaunchReferenceIntegrationTest` 覆盖；首次无效 fixture 错误未作为产品失败记录。
- **数据库契约**：V310 对 `batch_day_waiting_launch(tenant_id,request_id)` 建立指向 `trigger_request(tenant_id,request_id)` 的复合 `NO ACTION` 外键，并以 `NOT VALID` 后立即 `VALIDATE CONSTRAINT` 的方式显式完成约束验证。该关系允许请求存在等待记录期间保留父请求；父删除或修改候选键会被拒绝。仅全新数据库部署，Flyway 在服务流量前运行；不包含旧库在线升级或历史数据修复。
- **应用契约**：`BatchDayGateService.waitLaunch` 调用 `LaunchRequestIdentityGuard.requirePending`，比较启动请求与调用链加载的 `TriggerRequestEntity` 的 tenant、request、job、Workflow、bizDate、dryRun，并只允许 `ACCEPTED`/`PROCESSING`/`WAITING` 原请求状态。数据库只保证同租户请求行存在及不能先删/改父键；job、Workflow、日期、dryRun 与状态属于应用业务一致性，不能声称由 FK 保护。用户/自动释放从持久化 payload 重建请求后仍回到统一 launch 校验链路。
- **清理与保留**：`cleanup-trigger-request-retention.sql` 仅把已到保留期的 `RELEASED`/`REJECTED`/`CANCELLED` 等待与终态请求一并纳入候选，先删等待子行后删请求；任何活跃/未知状态或近期更新的等待继续阻止请求进入候选。请求最终删除语句再次检查三类等待均不存在。tenant wipe、非系统租户 wipe、seed cleanup 与 SDK E2E cleanup 显式先删等待再删 TriggerRequest；批量日运行重置沿用已列出的表级清理。
- **真实 PostgreSQL/应用回归**：`BatchDayWaitingLaunchReferenceIntegrationTest` 覆盖合法同租户引用、跨租户直接 INSERT、父 DELETE、父 tenant-key UPDATE；`BatchDayGateServiceTest` 覆盖应用传入身份与已加载请求不一致时在 Mapper 写入前拒绝。回归命令 `./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=BatchDayWaitingLaunchReferenceIntegrationTest,BatchDayGateServiceTest,BatchDayWaitingReleaseSchedulerTest,BatchDaySettleSchedulerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，37/37、0 failures/errors/skips、BUILD SUCCESS；日志 `logs/verification/workflow/batch-day-waiting-request-fk-final-20261018.log`。
- **静态检查与边界**：`BASE_REF=origin/main bash scripts/ci/validate-flyway-schema.sh db/migration` 通过（305 个迁移文件、保留 V31/V238/V242 版本缺口提示、无 checksum drift）；`bash scripts/ci/check-db-scripts-safety.sh` 扫描406个 SQL，WARN 30 / FAIL 0。Squawk 全变更迁移检查扫描71个文件但以15条告警退出失败；V310 自身 `constraint-missing-not-valid` 告警已由 `NOT VALID` + `VALIDATE CONSTRAINT` 消除，余下全量告警仍需复核。未运行完整 SIM、隔离部署、容量、归档恢复或最终 strict WARN 审计；不关闭 REF-04/DBSAFE-3/R 项。

### 2.25 Catch-up 控制行与业务日历引用对齐（2026-10-18）

- **首红与根因**：新增 `MultiCalendarCoordinationIntegrationTest#shouldEnforceCatchupControlCalendarReference`。V310 schema 接受同租户不存在的 calendar_code，未拦截另一租户仅同 code 的日历，也允许删除/改键仍被控制行引用的业务日历；首条负例先因没有异常而失败，确认是数据库引用缺口。
- **数据库契约**：V311 将 `batch_day_catchup_control(tenant_id,calendar_code)` 复合引用到 `business_calendar(tenant_id,calendar_code)`，`ON DELETE/UPDATE NO ACTION`，`NOT VALID` 后立即 `VALIDATE CONSTRAINT`。子表主键和父表既有 `uk_business_calendar_tenant_code` 分别覆盖引用索引与候选键，不增加冗余索引。日历软停用不违反 FK，物理删除或自然键变更必须先显式移除控制状态。
- **应用契约**：`BatchDayCatchupControlService` 对读取和修改均先用请求 tenant、calendar_code 解析启用日历；数据库 FK 使用相同自然键，额外 enabled 语义仍由应用校验。父日历在应用预检完成后被并发删除时，FK 保证不写孤儿；服务只识别该约束的 SQLSTATE `23503` 并映射为 HTTP 409，整个 `TransactionTemplate` 回滚，其他完整性异常不改写。集成用例以独立父删除事务持有父行锁，再调用真实 Service 并观察其 FK 检查阻塞；提交父删除后断言 Service 返回 409 且控制行/审计均未写入。应用单测另以模拟 FK 异常覆盖稳定错误映射。`batch_day_catchup_control_event` 是独立审计历史，不设热日历 FK，也不随日历级联删除。
- **清理顺序**：tenant wipe、non-system wipe 与4-day租户克隆先删 catch-up audit/control，再删 business_calendar。平台 runtime reset 保留当前 catch-up 控制状态以延续暂停配置；其原有事件日志清理语义未因本迁移改变。测试 fixture 在删除日历前清理自有控制行。
- **真实 PostgreSQL 与应用验证**：定向负例首红命令与结果见 `logs/verification/workflow/catchup-control-calendar-fk-red-20261018.log`；V311 后数据库用例覆盖同租户孤儿、异租户同 code、合法引用以及父删除/改键拒绝。`shouldMapConcurrentCalendarDeleteToConflictWithoutAudit` 用两个独立事务实测删除/应用插入交错，观测 FK 锁等待、删除提交后应用返回409及零控制/审计副作用；单项日志为 `logs/verification/workflow/catchup-control-calendar-real-race-20261018.log`。真实日历协调全类与 catch-up 服务单测回归28/28通过，日志为 `logs/verification/workflow/catchup-control-calendar-race-regression-20261018.log`；V311 所有受影响清理 fixture 的跨模块回归另有189/189通过证据。
- **边界**：FK 和应用校验只约束当前控制状态。事件表保留自然键快照；日历 enabled 改变不自动清除 pause 状态。该切片不关闭 REF-05/DBSAFE-3，不代表全部 calendar 配置入口、R 项、SIM、容量或部署验收完成。

## 3. 逐表核对的读法

下面逐表目录保留首次 V291 审查时的引用、数据库保障及建议；已落地变化以 §2.4–2.24 为准，不把原目录当后续迁移或应用修复后的再次实测现状。每张逻辑表均列出引用含义、已有应用替代及应补/不加理由。一张表可同时有应补强关联和应保留弱关联；不使用“某表无FK所以全部有问题”的口径。

- 运行域发现编号 `RT-1–6`、文件域发现序号、配置域 `C01–C08` 是证据分组，实施统一归入上面的 REF-01–08，不单独新增一组验收计数。
- 源码证据缩写：Console指 `batch-console-api/src/main/`，Orchestrator指 `batch-orchestrator/src/main/`；XML在 `resources/mapper/`，Java按列出的类在 `java/io/github/pinpols/batch/` 下检索。带完整仓库相对路径的证据优先。
- 所有 `created_by/updated_by/operator_id/trace_id` 等在确认是身份/追踪快照时，不机械FK到可删除账户或实时实例；这不豁免认证、授权及同租户读取。

## 运行、Workflow 与 Trigger（32 表）

### 基线与边界

- 实际 catalog：`/tmp/fk-audit-20261018/platform.json`，独立本地 PostgreSQL 17.11 / V291；逐表范围为 32 张 `batch` 逻辑表，分区子表不重复计算。
- 源码：`feature/workflow-daily-integration 工作区` 当前工作区；本报告只读审查，未改源码、未运行测试或写数据库。
- 下列“需补”区分原生 FK、写入口校验和生命周期方案；原生 FK 的存在性、租户一致、同一业务父对象归属是三个不同要求。RLS 不代替关系约束；`SELECT EXISTS` 或 V290 BEFORE trigger 只保护执行到该语句时的子侧写入，不提供原生 FK 的父侧删除/更新及并发锁保护。
- 每行已核对实际 catalog 的全部列。`tenant_id` 的租户根实体策略交由全库统一章节处理。`trace_id/operator_id/created_by/updated_by/requested_by/approved_by` 是链路或身份审计值，不自动指向可删除账户；状态、枚举、时间、业务编号、幂等键、worker 路由和快照不因字段后缀而自动建 FK。

### 逐表清单

| 表 | 实际关系与现有保护 | 处置及现有替代的边界 |
|---|---|---|
| `expected_work_occurrence` | `(tenant_id,workflow_definition_id)` 已有 FK；V295 已新增 `(tenant_id,trigger_request_id)→trigger_request(tenant_id,request_id)` NO ACTION FK；`calendar_code/biz_date/source_type/source_key` 是开日义务身份。 | V295 已补请求存在性和租户边界；应用仍必须校验同 Workflow、同业务日、EVENT 类型和已 LAUNCHED，Mapper 不能把 FK 当业务校验。义务历史不反向 FK 到可变 expectation 配置；日历/业务日生命周期与全库方案统一。 |
| `external_event_delivery` | 无 FK；`request_id` 是**聚合投递回执号**，不是任一子 trigger_request 的请求号；`target_workflow_definition_ids` JSON 为首次接纳时冻结的多目标；`event_source/event_id/event_code` 是外部事件身份。 | **不用加 request_id FK，也不用把冻结目标 JSON 改成热定义 FK。**`DefaultTriggerService:183–240` 在同租户下找启用目标、锁事件身份、保存快照，然后另生成每个子请求号；重投复用快照，`ExternalEventDeliveryMapper.xml:71–79` 校验目标成员，不能称 JSON 有数据库外键。保留历史目标并定向验证新投递→全部子请求链。 |
| `external_event_delivery_alias` | `(tenant_id,delivery_id)→external_event_delivery(tenant_id,id) ON DELETE CASCADE` 已有；PK `(tenant_id,idempotency_key)`。 | **现有 FK 足够**，别名随投递身份删除；`ExternalEventDeliveryMapper.xml:58–68,90–98` 读写一致。幂等键是身份，不另加 FK。 |
| `external_event_expectation_definition` | `(tenant_id,workflow_definition_id)` 同租户 FK保留；V316 增加 `(tenant_id,workflow_definition_id,calendar_code)` 到 Workflow 日历身份的复合 FK。`event_code` 无本地父表。 | `ExternalEventExpectationService.configure/validateWorkflowCalendar` 校验启用和同日历；指定 FK 竞态映射为稳定 HTTP 409。Console full/schedule update 与配置包 Apply 在父日历变更前拒绝仍有子定义的更新；提供租户范围显式删除定义路径后可改日历。event_code 是外部事件域代码，不补 FK。端到端授权和配置包竞态整笔回滚仍待验收，详见§2.39。 |
| `file_group_expectation_definition` | `(tenant_id,workflow_definition_id)` 同租户 FK保留；V316 增加 `(tenant_id,workflow_definition_id,calendar_code)` 到 Workflow 日历身份的复合 FK。`file_group_code` 无本地父表。 | `FileGroupExpectationService.configure` 校验同租户、启用及同日历；指定 FK 竞态映射为稳定 HTTP 409。父日历保护、显式删除生命周期与复合 FK 同 external event 规则。catalog 中没有 `file_group` 父表，file_group_code 是相关性代码，**不要凭名称补 FK**。端到端授权和配置包竞态整笔回滚仍待验收，详见§2.39。 |
| `job_definition` | 无外向 FK；`calendar_code/window_code/queue_code` 为配置自然键，`depends_on_job_code` 为旧兼容依赖；`worker_group/job_group_code` 为路由/逻辑分组；`param_schema/default_params/retry_policy_by_class` JSON。 | **不用对所有 code/JSON 加 FK。**依赖标准关系应落在 `job_dependency`，V256 trigger 仅同步旧标量（未查上游存在）；`DefaultConsoleJobDefinitionApplicationService:673–692` 已检查同租户上游及环。calendar/window/queue 要按同租户、全局默认/空值语义统一校验和删除阻断，不能仅写入字符串就称已保护；运行 `DefaultSchedulePlanBuilder:49–84` 使用配置快照/路由，不能代替写入校验。worker_group/job_group 无独立稳定父表。 |
| `job_dependency` | V256下游 `(tenant_id,downstream_job_code)→job_definition` CASCADE；V298新增上游 `(tenant_id,upstream_job_code)→job_definition` NO ACTION；CHECK 禁止自依赖，PK/顺序 UNIQUE。 | V298保护上游存在、租户边界和被引用父删除；下游自己的边仍可随定义删除。应用 Console 校验同租户存在与依赖环；V256 legacy scalar同步和直接SQL也由FK拦截不存在/异租户上游。FK不禁止多节点环；受管初始化、批导入和删除错误契约仍需单独验证。`JobDefinitionMapper.xml:359–373`更新规则先删再批量插入，不违反父引用。 |
| `job_dependency_waiting_launch` | V294 `(tenant_id,request_id)→trigger_request(tenant_id,request_id) NO ACTION`；`job_code`/依赖 JSON/launch_payload 冻结等待上下文。 | `LaunchRequestIdentityGuard` 校验 tenant/request/job/Workflow/bizDate/dryRun 及 ACCEPTED/PROCESSING/WAITING 状态；`JobDependencyWaitingLaunchService.requireWaitWrite` 要求等待插入、检查记录、原请求状态更新各恰好影响1行，零行映射 `STATE_CONFLICT` 并由事务回滚。真实 PG 已验证 CAS 零行不留下等待/检查部分写入。V294 FK保护同租户父请求存在性和父删除顺序；依赖快照不回连可变 job_definition/结果热表，损坏解析 fail closed。 |
| `job_execution_log` | V322 以 `(tenant_id,job_partition_id,job_instance_id)` 复合 FK 约束分区日志归属，并仅在分区删除时 `SET NULL (job_partition_id)`；CHECK 禁止有分区但无实例。可空 `job_instance_id` 仍无 FK；`detail_ref` 为日志对象位置，extra_json 是证据。 | **保留审计独立生命周期；不补热 job_instance 强 FK。**V322 和 PostgreSQL 17.11 集成用例保护有分区日志的同租户/同实例身份及审计保留。`JobExecutionLogMapper.insert` 通过单条 guarded insert 对无分区的实例引用执行同租户存在检查并持有父行 KEY SHARE；成功实例归档在选候选阶段先对根实例 `FOR UPDATE SKIP LOCKED`，覆盖复制、删除与并发追加的竞态。生产日志写入统一经过该 mapper；需以受影响 mapper 与完整归档用例验证。独立 SQL/异常数据仍由归档完整性预检作为纵深防御，不能倒推写入正确。 |
| `job_instance` | PK `(id,biz_date)`；`(tenant_id,job_definition_id)` 与 V300 `(tenant_id,trigger_request_id)→trigger_request(tenant_id,id)` 均为复合 FK；第二个 FK 允许 `trigger_request_id=NULL`，由现有 CHECK 仅准 MANUAL。`parent_instance_id/related_file_id/replay_session_id` 无 FK；job/calendar/queue/code/version、params/rerun/result JSON 为快照。 | V300 为分区父表加入同租户 TriggerRequest 强引用，并把请求查询索引调整为 `(trigger_request_id,tenant_id)`，保留请求号前缀查询；同租户、错租户、孤儿、MANUAL 空值、非 MANUAL 空值及父删除由真实 PostgreSQL 覆盖，见总方案§2.15和验证矩阵。parent/relatedFile/replay_session仍按冷热生命周期和稳定身份分别裁定；relatedFile 入口校验不等于数据库 FK，详见§2.12。parent 跨日/热冷血缘不宜盲加热表 FK；`SuccessInstanceArchiveMapper:598–602` 归档时主动清空热 parent，须确保冷历史仍可追溯。所有子表若要原生引用该分区表，要携带 biz_date 与目标唯一键，**不是 PostgreSQL 不支持分区 FK**。 |
| `job_instance_dedup_key` | 无 FK；全局 PK `(tenant_id,dedup_key,run_attempt)`；`first_instance_id/first_biz_date` 是可空首写证据。 | **不用热实例 FK**，幂等账本应跨热分区删除/归档继续有效。V182:36–55 回填 first id，但当前 `JobInstanceMapper.xml:158–174` 的 claimed-key 只写 first_biz_date，不回写 first_instance_id；不能把该列当完整权威血缘。若产品要展示首实例，另补可靠回填/热冷定位；不能据此删账本释放重复执行。 |
| `job_monitoring_alert_claim` | 无 FK；PK `(tenant_id,job_instance_id,violation_type)`。 | 去重领取账本；不直接绑热实例 FK，以免实例归档重开同一告警身份。当前 claim `JobMonitoringMapper.xml:273–277` 为 VALUES；候选来自业务查询，`SuccessInstanceArchiveMapper:79,592–596` 会按实例删除 claim。**需定稿去重保留期并在写入期确保同租户真实候选**，不能称已有 DB 存在性。 |
| `job_monitoring_policy` | V299已升级 `(tenant_id,job_definition_id)→job_definition(tenant_id,id) CASCADE`；unique `(tenant_id,job_definition_id)`。 | V299复合FK保护存在性、租户归属和原有配置从属CASCADE；Console仍按tenant读取Job再upsert（`DefaultConsoleJobDefinitionApplicationService:194–205`），DB约束补足错误Mapper/导入/直接SQL路径。全量配置初始化和普通RLS角色并发删除验收仍开放，见§2.14。 |
| `job_partition` | 无 FK；`job_instance_id` 强运行归属，`source_file_id` 源文件；`worker_code/current_invocation_id/template_code/target_ref` 是运行租约/调用/模板/目标快照。 | **应补 job_instance 写入与归档并发完整性**，选携 biz_date 复合 FK或共享父锁+父侧清理栅栏（需明确方案），不能只加 BEFORE EXISTS。`DefaultPartitionLifecycleService:49–98` 只校验 sourceFile 同租户，tenant与jobInstance由不同参数构造；Mapper:134–226 纯VALUES。源文件已有替代校验但无父侧删除锁；worker/invocation/target_ref不建 FK到短命注册/外部资源。模板是版本化执行快照，不能 FK到当前 mutable配置。 |
| `job_task` | V317 `(tenant_id,job_partition_id,job_instance_id)→job_partition` CASCADE FK，可空分区；V320 `(tenant_id,job_instance_id,job_instance_biz_date)→job_instance` NO ACTION FK，覆盖无分区 Task 的父存在与租户身份。Worker code/JSON参数为运行快照。 | 全新库的 V320 直接要求父业务日非空，不做旧数据预检/回填；同步热/冷 Task 身份。`DefaultPartitionDispatchService` 与 `DefaultWorkflowNodeDispatchService` 传递父日期；`DefaultTaskCreationService` 拒绝缺日期，并只将精确 Task→Instance FK 竞态映射稳定 CONFLICT。需持续核对其它直接 SQL/种子入口及归档/保留清理顺序；见§2.43。 |
| `job_step_instance` | V319 以 `(tenant_id,job_task_id,job_instance_id,job_partition_ref_key)` 原生 CASCADE FK 替换旧 Task/Partition 单列 FK；生成键把NULL编码为0，CHECK禁止真实分区ID为0。唯一 `(tenant_id,job_task_id)` 保留。 | `DefaultTaskCreationService` 单条/批量从Task复制完整身份，精确 Task partition/Step ownership FK 冲突映射稳定CONFLICT；冷表同样自动生成派生键，显式列归档不手工写派生值。真实PG两种隔离等级与两种改键/插入顺序已验证，详见§2.42。无分区Task到JobInstance的父存在性、related_file父生命周期/直写、归档全局并发仍开放；不能据此称整个运行树已具备强外键。 |
| `scheduled_occurrence` | `(tenant_id,workflow_definition_id)` FK；V295 已新增 `(tenant_id,request_id)→trigger_request(tenant_id,request_id)` NO ACTION FK；workflow_code/calendar_code/schedule_version 为生成时快照。 | V295 保护请求存在性、租户边界及父删除顺序；`ScheduledOccurrenceMapper.xml:129–157` 仍需按同 Workflow/业务日/状态校验，单纯 FK 不覆盖计划快照和业务状态。时间点/快照不应被 current workflow/calendar级联重写。 |
| `scheduled_occurrence_catch_up_audit` | `(tenant,workflow_definition_id,scheduled_fire_time)→scheduled_occurrence`、`(tenant,request_id)→trigger_request` 两个 FK均完整。 | **现有 FK足够保护父存在/租户**，保留审计 NO ACTION；`ScheduledOccurrenceMapper.xml:159–185`写入/读取。请求是否属于该 occurrence仍由 catch-up 事务和状态校验保证；两条独立FK不自动保证相互匹配，专项验收保留。 |
| `scheduled_occurrence_coverage` | `(tenant_id,workflow_definition_id)` FK；calendar/version/configuration_revision为计算覆盖水位快照。 | **现有 FK保留；不用把历史水位 FK到当前 revision/version**；`ScheduledOccurrenceMapper.xml:246–280` 与 `TriggerScheduledOccurrencePlanner:107` 写覆盖水位；revision改变应失效/重算，而非SQL级联覆盖。 |
| `scheduled_occurrence_supersession_history` | 无 FK；workflow定义/id/request/calendar/schedule_version 是替换前后历史快照；AFTER UPDATE supersession trigger生成。 | **不用回连热 occurrence/definition/request强FK**，避免清理当前对象毁审计。需明确保留/查询热冷策略；现有 trigger提供来源生成保证，不能约束任意手工INSERT。trigger函数只记录发生的状态变更，不是外键校验。 |
| `trigger_misfire_pending` | 新 `(tenant,workflow_definition_id)` FK；V296新增 `(tenant_id,catch_up_request_id)→trigger_request(tenant_id,id)` `NO ACTION`；旧 `trigger_runtime_state_id` 单列CASCADE nullable；job_code nullable兼容。 | V296保护已接纳补跑请求存在性、租户边界和父删除顺序；应用仍校验同Workflow/业务日/状态/审批终态。旧runtime引用当前插入路径不再写（Mapper:24–33），先列兼容/退役，不为弃用模型扩建约束；retire后删列/表必须另有迁移方案。 |
| `trigger_outbox_event` | V297新增 `(tenant_id,request_id)→trigger_request(tenant_id,request_id)` `NO ACTION`；request_id、topic/payload为持久消息。 | V297保护父存在、租户边界和父删除顺序；`TriggerOutboxDomainEventPublisher:40–41,52–78` 继续保证同事务写入，应用仍校验 payload 信封中的 tenant/request、Workflow/业务日/触发类型/请求状态。不能CASCADE删除 NEW/PUBLISHING事件；归档表独立保留，热表/归档表清理后才允许请求清理。 |
| `trigger_request` | `(tenant,workflow_definition_id)` FK；`related_job_instance_id` / `parent_workflow_run_id` 保留历史弱引用；`parent_node_code` 是父运行快照节点 code；`job_code` 与 external 事件 code/identity 是来源。 | Workflow FK 保留。`TriggerRequestMapper.xml`（orchestrator）仍核对同 tenant 父 run、JOB 节点、快照与绑定，并在应用层限制实例回填。JobInstance 历史链接由 V323 BEFORE INSERT/UPDATE trigger 以同租户 `FOR KEY SHARE` 锁定父实例，拒绝缺失/异租户关联；已被引用的父实例不能改写 ID/tenant/biz_date，但允许归档删除并保留请求中的历史 ID。父 WorkflowRun 不加热 FK：原有 V286 admission trigger 先取得业务日围栏，再用 `FOR UPDATE` 重验并锁定同租户父运行；V323 补父运行 ID/tenant/biz_date 与请求父运行/node 快照不可改写，仍允许归档删除。直接 SQL 跨租户父运行 INSERT 由 V286 拒绝。未调用的 `batch-trigger.updateRelatedJobInstanceId` 直写方法/XML 已移除。`parent_node_code` 不 FK 到当前可编辑 workflow_node；external_event id 是外部身份，不 FK 外部系统。详见 §2.47。 |
| `trigger_request_idempotency_alias` | `(tenant,request_id)→trigger_request(tenant,request_id) CASCADE`；PK tenant+idempotency key。 | **现有 FK足够**；删除请求同时删除别名会释放重投身份，是否符合保留期限需在请求清理协议验证，不能只看CASCADE执行成功。 |
| `trigger_runtime_state` | 单列 job_definition FK CASCADE；tenant/job_code冗余；表仍存在。 | **旧调度模型，不列为当前主链必补**：当前各 batch-*/src/main 未检出对该表读写，Quartz/Workflow接管。若明确继续承载兼容运行，则需tenant复合FK；若退役，记录数据/表清理单独阶段，不能因无调用就擅删。 |
| `workflow_definition` | `(tenant,single_job_definition_id)→job_definition` 已复合FK；calendar_code/depends_on_job_code无FK。 | 单作业关联已保护；`DefaultWorkflowDefinitionService:325–353`配置时检查同tenant依赖与active calendar；**自然键的删除/改名防护可补复合FK或引用阻断服务**，不能用当次校验覆盖后续配置变化。workflow code+version为自身身份，不再FK到job同码。 |
| `workflow_definition_version` | 无 FK；workflow_definition_id、nodes_json/edges_json、calendar/dependency代码为版本快照。 | **不补到热当前图/作业/日历强FK**；历史版本应在当前配置改变后仍可读。`WorkflowDefinitionWriteSupport:123–149` 从同tenant当前节点/边生成快照；未来删除当前定义要明确版本API是否仍可访问，现有 `DefaultWorkflowDefinitionService:546–578` 先要求当前定义存在，是查询生命周期限制，非DB完整性。 |
| `workflow_edge` | 单列 workflow_definition FK；from/to node_code无FK；tenant+def+端点+type UNIQUE。 | **V293已升级tenant+definition FK，验证见§2.7**。端点可选择 `(tenant,def,code)→workflow_node` 两个复合FK，但必须先调整全量更新删除顺序（`DefaultWorkflowDefinitionService:209–210`目前先删node后删edge）或使用明确可延迟事务策略；不能直接加导致正常保存失败。现有 `WorkflowDagValidator:106–121` 检查输入图端点，提供应用替代，不防外部写入和并发父变更。 |
| `workflow_launch_wait` | `(tenant,request_id)` FK CASCADE；workflow_definition_id单列FK；有forced RLS。 | **V293已升级tenant+definition FK，验证见§2.7**；Mapper:16–26 已经 `INSERT SELECT`同tenant请求且definition一致，是有效入口守卫；FK只保护各自父存在，request/workflow对应关系仍需原SQL。RLS不弥补单列FK。等待中删除请求是否可级联应明确终态清理协议。 |
| `workflow_node` | 单列workflow_definition FK；related_job_code无FK；cross_day_dependencies/node_params JSON、window_code/worker_group为配置。 | **V293已升级tenant+definition FK；JOB业务编码按草稿/启用/运行入口分别保护**（详见§2.6/2.7及RT-2）。可编辑草稿不应机械禁止保存未完成引用，但启用/发布/launch前必须同tenant存在且启用；跨日依赖、DSL/输出键使用graph validator，JSON无法普通FK。配置端点关系与当前执行快照分开，不能让历史run FK当前node。 |
| `workflow_node_run` | workflow_run_id单列FK；该表没有tenant，tenant继承run，单列本身合理；job_instance_id无FK，但 V290 BEFORE insert/update trigger比对run tenant；node_code/output/source_payload为执行快照。 | **保留父run FK，不额外复制tenant只为形式统一。**Job绑定需补并发/父侧归档协议、同run/node归属；`WorkflowNodeRunMapper:85–98` CAS+EXISTS与V290均仅查同tenant和存在。不能FK node_code到mutable配置。独立归档丢job_instance_id是RT-1实际代码缺陷。 |
| `workflow_run` | definition、single_job_definition、trigger_request三条FK均带tenant；`related_job_instance_id` 为可选历史弱引用，无强FK；current_node_code/execution_snapshot为冻结图。 | **3条FK现有足够，保留；related_job_instance_id 保留弱引用语义。**Managed launch 当前创建根运行时为空（`DefaultWorkflowRunLaunchService:257–266`）；赋值时 V323 按 tenant/ID `FOR KEY SHARE` 确认父实例存在，引用仍在时禁止改写父 ID/租户/业务日，但允许关联提交后归档/删除热实例并保留历史 ID。不能做必填或盲加热 Job FK。current_node_code不回连当前workflow_node；执行快照要完整归档，动态证据见§2.46。 |

### 已定位缺陷与整改顺序

#### RT-1（P1，静态确认可达数据丢失；尚未执行归档复现）独立 Workflow 归档丢失 JOB 绑定

- `batch-orchestrator/src/main/resources/mapper/WorkflowRunMapper.xml:252–267` 的 `archiveWorkflowNodeRunsByWorkflowRunIds` INSERT/SELECT均漏 `job_instance_id`。
- 实际 `archive.workflow_node_run_archive` 已有可空 `job_instance_id`；`WorkflowArchiveService.java:48–51` 复制后删除热节点，所以原非空绑定会变为冷表null。
- `SuccessInstanceArchiveMapper.xml:469–483`另一归档入口已带该列，不能据此判全部入口修复。新主链Workflow独立于Job根实例，正好走独立归档。
- 整改：补独立复制列；遍历所有热冷镜像列；验证独立Workflow含多个JOB节点归档后原job id、run_seq、输出/错误/skip/sensor/source payload保持一致且血缘可查。已按两条生产归档路径交叉核对。

#### RT-2（P2，初始静态缺口；后续HTTP复现及整改见§2.6）JOB引用校验入口不一致

- 实际无 `workflow_node.related_job_code` FK。
- create/fullUpdate调用 `WorkflowDagValidator:154–159`只要求非空；`DefaultWorkflowDefinitionService:234–261` 的启用/启用调度不跑引用检查。
- 同tenant存在/启用校验在 `WorkflowDefinitionDagInspector:59–91`，由独立validate接口调用；运行入口虽跑 `WorkflowGraphValidator`，其 `loadJobDefinitions:313–335` 对未找到定义不报错。
- 结果：结构合法的 START→JOB(不存在/其他tenant才存在/disabled code)→END 可保存为enabled；后续实际派发才失败。不能宣称引用校验已有完整替代。
- 整改：统一可复用引用检查用于启用、enabled定义保存、批量导入和launch；允许disabled草稿的未决引用需明确规范。不为保存草稿全量加无条件FK；若改成强引用必须先裁定草稿规则。

#### RT-3（P2，数据库防御缺口）已有单列FK未绑定tenant/业务父归属

- 当前活跃表建议优先改：job_instance→trigger_request；job_monitoring_policy→job_definition；workflow_node/edge/launch_wait→workflow_definition。
- 运行子树：job_task、job_step_instance引用partition/task应增加tenant+job_instance归属；step还要与task的partition一致；job_execution_log保持审计SET NULL语义，不能生硬改CASCADE。
- 归档 `SuccessInstanceArchiveMapper:44–77`已拒绝部分坏树，但检查使用INNER JOIN，不能发现不存在父行；它是后置隔离，不是写入约束。
- 验收需包括异租户有效ID、同租户另一个Job实例ID、缺失ID、正常可空partition任务、父归档与子创建并发、合法归档顺序；不得用管理员全权限单线程成功替代。

#### RT-4（P2，数据库防御/生命周期未决）新增账本的请求链接未闭环

- 强活跃关联：job_dependency_waiting_launch.request_id、trigger_outbox_event.request_id、trigger_misfire_pending.catch_up_request_id、scheduled_occurrence.request_id、expected_work_occurrence.trigger_request_id。
- 优先补tenant复合FK；需要同时定义父请求保留期、WAITING/NEW/PUBLISHING不可删除、终态清理顺序、历史审计保留。
- 同tenant存在还不等于同workflow/date/event，继续保留业务绑定校验。**不要**对external_event_delivery.request_id做同样改造，它是聚合回执号。

#### RT-5（P2；原始上游缺口已由 V298 关闭，环并发缺口已由 V301 局部关闭）Job 依赖关系完整性

- 初始 V291 目录发现下游有 tenant FK 而上游没有；V298 已为上游增加 `(tenant_id,upstream_job_code)→job_definition(tenant_id,job_code)` `NO ACTION` FK，直接 SQL 与 legacy scalar 路径不能再写悬空/异租户上游，且父删除受保护（实施及证据见 §2.13）。
- V301 已在数据库触发器和 Console 事务校验间共用租户级锁，覆盖并发 INSERT、直接 UPDATE 与 V256 legacy scalar 同步形成多节点依赖环的拒绝，证据见 §2.16。
- Excel 配置包已有跨表存在性预检，并将包内替换依赖与现存图合并检测环；Apply 在事务中先取得与 Job 服务及 V301 trigger 相同的 tenant graph lock，再锁定配置 revision、重新读图校验后写入，锁顺序为 graph→revision。真实 PostgreSQL 双事务回归令并发事务在预览后持有图锁并提交反向依赖；测试轮询 `pg_locks/pg_stat_activity` 确认 Apply 正等待同一 advisory lock，释放后 Apply 重新读图并以 `INVALID_ARGUMENT` 阻断。断言并发边保留、目标边未写、包内先行处理的 channel 也无副作用，见 `ConsoleTenantConfigPackageExcelUploadIntegrationTest#shouldRevalidateJobDependenciesAfterConcurrentGraphChangeAndRollbackPackage`。V301 竞态约束异常按 JDBC PSQLException 的 ServerError constraint 字段精确映射稳定 `INVALID_ARGUMENT`，不向客户端返回约束名；单测证据见 `ConsoleApiExceptionHandlerTest#shouldReturnStableInvalidArgument_whenJobDependencyCycleConstraintViolation`。这些只闭合 Excel Apply 的该类环竞态，不代表所有 Apply 失败和所有依赖写入口已验收。
- 尚未关闭的部分：其它写入口跨入口并发时的整包回滚及端到端错误契约；受管初始化其余场景和迁移/运维脚本；完整普通 RLS 角色及失败矩阵；多依赖在实际 Worker 消费、进程故障和阶段重试中的语义。批量初始化和 Config Sync 已携带完整规则；混合业务日持久等待恢复与普通角色配置导出/双目标导入/失败租户隔离已有局部动态证据，源读取及每个目标事务已按租户绑定，详见 §2.27–2.28。这不代表全量入口或 RT-5 完成。不得再把“上游 FK 缺失”记为当前缺口，也不得将 RT-5 整项标记完成。

#### RT-6（P2，生命周期方案未决）分区Job及Workflow节点绑定不是原生FK等价实现

- job_instance PK `(id,biz_date)`；child目前只留id，因此无法直接加id-only FK，PG17本身支持分区表FK。
- V290无父行 `FOR KEY SHARE`、无父DELETE/关键列UPDATE约束；V84兼容workflow_run trigger同样仅子侧校验。不能把二者称为完整外键。
- 方案必须统一：要么为强运行子树携带业务日并建立tenant+id+biz_date唯一/FK；要么为保留热冷引用的关联制定共享父锁、归档栅栏、关系不可变和异步巡检，逐入口证明。两者不能混着宣称数据库已兜底。
- 运行依赖不能被成功Job归档提前切断：需要明确仍有非终态workflow节点、等待、回调、重放引用时的候选排除；历史引用必须能够查冷表，不能强制热父永存。

### 无需加FK的明确分类

1. 聚合/外部身份：external_event_delivery.request_id、event_source/event_id/event_code、file_group_code。
2. 冻结/历史证据：workflow_definition_version.nodes_json/edges_json、workflow_run.execution_snapshot、workflow_node_run.node_code/source_payload、scheduled occurrence版本/日历快照、supersession history、Job参数/路由/模板快照。
3. 生命周期独立：job_instance_dedup_key、审计日志/外部detail_ref、历史parent/replay血缘（先完善热冷查询与保留协议）。
4. 短命注册/外部对象：worker_code/current_invocation_id/target_ref、审计操作者标识；不FK到会删除的Worker/账号/外部系统。
5. 待退役结构：trigger_runtime_state及misfire旧runtime字段；先决定兼容/清理，不为未使用旧模型扩大本轮改造。

### 证据边界

本报告证实当前结构和源码调用关系，不证明数据库中已有脏行，也不证明并发问题已复现。RT-1、RT-2是静态可达具体缺陷；RT-3/4/5是完整性控制缺口；RT-6及历史保留策略属于需落实的生命周期设计。32张表逐行已覆盖；未执行DDL/DML、未运行测试。

## 业务日、日历、审批和告警（20 表）

同一行可能包含多种决定；“保留弱引用”仍须满足注明的生命周期条件，不能据此免除修复。

| 表（batch） | 当前实际外键 | 逐项决定、已有替代与缺口 | 代码证据 |
|---|---|---|---|
| `alert_event` | 无 | 不给 `trace_id`、`detail_json` 内多态业务身份加单一 FK；事件应在源对象归档后继续可读。`tenant_id+dedup_fingerprint` 唯一与状态 CHECK 已有；需保留不可变目标摘要，不能依赖热表存在。 | Orchestrator `AlertEventMapper.xml:27–55` |
| `alert_escalation_notification_outbox` | 无 | `alert_event_id` 有同事务父告警 CAS 后插入，唯一键防重复；现有应用路径保护成立。建议补 `(tenant_id,alert_event_id)` 复合 FK 与父告警保留/先清已投递记录规则；禁止 CASCADE 丢弃未投递通知。属于防御补强，未证明当前路径会产生孤儿。 | Console `AlertEscalationNotificationOutboxService.java:27–55`、`AlertEscalationNotificationOutboxMapper.xml:24–44` |
| `alert_routing_config` | 无 | route/team/receiver/group 为告警路由配置值，不是数据库主键；不加推测性 FK。保留枚举/时长/接收端配置校验，接收端是否有效属运行探针。 | V43 `alert_routing_config` 定义；实际 catalog |
| `approval_command` | 无 | `target_type+target_id` 为多态对象，requester/approver 可为外部主体，不能直接 FK 到某张 Job 或 Console 账户表。已有 tenant+approval_no 读取及 CAS；执行前仍必须按 action 查同租户目标、重新授权并核对冻结 payload。审批状态机不能替代目标校验。 | `DefaultApprovalWorkflowService.java:30–44,51–66,127–133`；Console 审批执行应用服务 |
| `archive_policy` | 无 | `target_table` 是受 CHECK 白名单约束的策略枚举，不应 FK pg_class；每种允许目标必须有实际搬运/清理实现。冷表存在本身不能证明已支持归档，见归档缺口。 | catalog `ck_archive_policy_target_table`；`SuccessInstanceArchiveService` 等专项归档服务 |
| `batch_day_catchup_control` | V311 `(tenant_id,calendar_code)→business_calendar(tenant_id,calendar_code)` 复合 `NO ACTION` FK | 应用 `BatchDayCatchupControlService` 先按请求租户解析启用日历，再以同一自然键写控制状态；并发删除导致的对应 FK `23503` 转为稳定409且事务回滚。数据库拒绝父日历物理删除/改自然键；日历软停用不会删除控制状态。孤立的 `batch_day_catchup_control_event` 是保留审计，故不设 FK；租户销毁显式清控制和事件，再删日历。 | `BatchDayCatchupControlService.java`；V311；`MultiCalendarCoordinationIntegrationTest`；清理脚本 |
| `batch_day_catchup_control_event` | 无 | 操作事实留存，不给 calendar/operator 热配置加 FK；事件与控制更新同事务，历史停用不应抹除证据。 | `BatchDayCatchupControlService.java:66–79` |
| `batch_day_instance` | 无 | calendar_code 为业务日身份，timezone/DST 为冻结快照。保留历史业务日弱引用，不级联追随实时日历删除；创建由日历开日/launch路径提供，必须限制配置物理删除及证明恢复只用快照。不是‘开日必须预建所有Job’。 | `BatchDayOpenScheduler.java:212–251,536–560`；`LaunchBatchDayService.java:111–125` |
| `batch_day_open_cursor` | 无 | 当前日历开日水位，建议 `(tenant_id,calendar_code)` FK；服务锁水位顺序已有，补日历退役与游标删除顺序，不能因配置缺失重置历史日。 | `BatchDayOpenScheduler.java:145–186`；`BatchDayInstanceMapper.xml` |
| `batch_day_operation_audit` | 无 | calendar/bizDate、operator、approval_id 为操作证据，不加热运行 FK；当前操作服务记录原状态与请求，approval_id 当前路径可空，不能误设 NOT NULL。后续关联审批须查同租户审批及动作后才写，不据列名宣称已有验证。 | `BatchDayOperationService.java:250–277` |
| `batch_day_replay_session` | 无 | calendar/config_version/approval_id 是重放来源和审计信息。当前 replay 审批使用 session 自身状态及 approved_by，未证明 approval_id 接入统一审批；保留可空并明确生命周期，不能自动加指向 approval_command 的约束。计划/源实例由预览生成，提交复核快照与令牌。 | `BatchDayReplayService.java:114–194,443–458` |
| `batch_day_replay_entry` | session_id→replay_session.id（单列） | **应升级 `(tenant_id,session_id)` FK**，并给父组合唯一键；原单列FK不保证租户。source/rerun_instance_id、result_version_id 属冷热重放血缘，**不直接指向热表**，应同租户解析与冷表回读。提交服务生成同租户 entries，裸 insertBatch/updateStatus 不能当全局守卫。 | `BatchDayReplayEntryMapper.xml:26–55,117–128`；`BatchDayReplayService.java:146–194` |
| `batch_day_replay_preview_token` | V309 `(tenant_id,session_id)→replay_session(tenant_id,id)` 可空 `NO ACTION` 复合FK | `session_id` 在未成功消费/链接前允许 NULL；Mapper `linkSession` 按 token 租户与父 session 租户同值做 `EXISTS` 守卫，应用在更新 0 行时回滚提交。父 session 被短期 token 引用时拒绝删除；运行清理先按保留期清 token，再删 session。冷 session 归档须保留 token 过期/重试查询语义，不能级联删除未过期 token。 | `BatchDayReplayPreviewTokenMapper.xml:27–63`；`BatchDayReplayService.java:121–132,190–194`；V309 / 验证矩阵 |
| `batch_day_waiting_launch` | V310 `(tenant_id,request_id)→trigger_request(tenant_id,request_id)` 复合 `NO ACTION` FK | 等待创建调用 `LaunchRequestIdentityGuard.requirePending`，核对传入启动请求与已加载 TriggerRequest 的 tenant/request/job/Workflow/bizDate/dryRun 身份及 ACCEPTED/PROCESSING/WAITING 状态；这些业务字段超出 FK 的保证范围。候选键身份与 FK 相同。运行恢复及人工释放重建请求后仍走统一 launch 校验；保留/删除顺序见专题§2.24。 | `BatchDayGateService.java`；`LaunchRequestIdentityGuard.java`；`BatchDayOperationService.java`；V310；验证矩阵 |
| `batch_window` | 无 | 是窗口定义根，window_code 自身不是外键；timezone/策略由类型校验。引用者按当前配置/冻结快照分别判断，不能给全部历史实例强加窗口FK。 | 实际 catalog；V3；`BatchWindowMapper.xml` |
| `calendar_dependency` | V302：上下游同租户自然键FK及数据库防环触发器；原表无生产应用写入口 | V302 添加 `(tenant_id,upstream_code)` 与 `(tenant_id,downstream_code)` 到 `business_calendar(tenant_id,calendar_code)` 的 `NO ACTION` 复合FK，并用按租户事务锁保护图无环不变量。真实PG验证直接INSERT/UPDATE及并发成环均拒绝。**当前未发现生产Service/API调用该Mapper的insert；因此不声称有应用层校验。数据库是当前写入边界。未来增加写入口时必须同身份预检、取得同一租户锁、映射FK/环冲突为稳定领域错误；软删除/停用仍需另定业务语义。** | `CalendarDependencyMapper.xml:21–45`；`BatchDayOpenScheduler.java:327–363`；V302；`MultiCalendarCoordinationIntegrationTest` |
| `calendar_dependency_wait` | 无 | 等待可能在业务日尚未创建时产生，**不能 FK 到 batch_day_instance**。保留 calendar 与 dependency_snapshot，已有同租户等待行锁、重检与超时告警；配置退役必须处理未决等待，快照不受实时依赖重写。 | `BatchDayOpenScheduler.java:369–400,441–486`；`CalendarDependencyWaitMapper.xml:40–54,119–145` |
| `calendar_group` | V304：business_calendar 与 GROUP holiday 对 calendar_group 的同租户自然键FK | 配置根仍无反向父引用；`business_calendar(tenant_id,group_code)` 与 `calendar_holiday(tenant_id,group_code)` 均以 `NO ACTION` 复合FK引用组，父组在成员清理前不能物理删除。当前未发现应用组写入口，数据库约束覆盖直接SQL/初始化；enabled 状态语义仍由批日查询负责，须另行评审。 | V112 的 `uk_calendar_group_tenant_code`；V304；`MultiCalendarCoordinationIntegrationTest` |
| `calendar_holiday` | V303：`(tenant_id,calendar_id)`→`business_calendar(tenant_id,id)`；V304：`(tenant_id,group_code)`→calendar_group及GROUP代表日历三列复合FK，均 `NO ACTION` | Console CALENDAR假期导入按请求租户解析父日历并写相同租户/ID，DB FK兜底直接SQL与竞态错配，FK错误映射为 `CONFLICT`（竞态HTTP未独立注入）。V304还拒绝异租户/孤儿组和不属于组的代表日历；真实PG验证合法组内写入、错租户组、错代表日历及父删除保护。当前无组管理应用写入口，不虚构应用Guard。`GROUP`禁用状态及批量配置旁路仍需单独验收；不盲目改 `calendar_id` 非空语义。 | `DefaultConsoleCalendarApplicationService.java:127–153,156–186`；`CalendarHolidayMapper.xml:44–63`；V112、V303–V304；`ConsoleCalendarMutationIntegrationTest`；`MultiCalendarCoordinationIntegrationTest` |
| `disaster_day_override` | 无 | calendar_code+bizDate 为灾难日处置事实，approved_by 可外部主体，不直接FK Console用户；创建/生效前必须校验同租户日历及权限，历史过期记录保留。现有TTL/action CHECK及同租户有效期查询仅证明读取，未找到统一生产创建入口，不宣称已有完整替代校验。 | `DisasterDayOverrideMapper.xml`；`BatchDayOpenScheduler.java:216–237`；V114 |

本域整改：活跃强从属项按复合键补数据库兜底；历史/多态项保留弱关联并补准入、保留和恢复契约。未执行数据写入或负例测试，裸SQL可绕过应用校验的判断来自实际catalog和源码。

## 文件、Pipeline、Outbox、资产与归档（56 表）

### 范围与证据边界

基线：真实 PostgreSQL 17.11、Flyway V291 的 `/tmp/fk-audit-20261018/platform.json`，源码为 `feature/workflow-daily-integration 工作区` 当前工作区。覆盖 `batch` 30 张逻辑表（不包括另组负责的 `file_group_expectation_definition`）与 `archive` 26 张表；`outbox_event` 分区按父表汇总，物理分区继承情况使用总 catalog。未写数据库、未运行测试；下面的失效路径是结构与代码静态证据，不能当作已经复现。

V291基线中实际已有9条FK：`asset_partition` 2条、`data_quality_check` 1条、`file_audit_log` 1条、`file_dispatch_record` 2条、`pipeline_instance` 1条、`pipeline_step_definition` 1条、`pipeline_step_run` 1条。前7条当时只校验id存在，后2条为无独立tenant_id的从属表。该基线还建议新增 `pipeline_progress` 与 `file_channel_health` 两条关系；V292 已实现前者，V315 已实现后者。此段只描述 V291 目录快照，不代表当前未完成候选。

26 张 archive 表实际均无 FK，逐一与热表对照的列名和类型无缺列/类型漂移。这个结果只证明镜像结构，不证明归档任务存在或 COPY 字段完整。

### 可操作发现（V291 初始审查快照）

以下编号记录的是 V291 静态盘点时发现的问题，不自动代表当前仍开放。`pipeline_progress` 的父引用及运行树归档缺口已由 V292 修复，最新源代码复验见 §2.5 与整合验证矩阵；其余发现按 §2 的当前状态和对应后续实施切片判断，禁止仅凭本节历史描述重复开工。

1. **P1：独立 Workflow 归档漏 JOB 关联。** `WorkflowRunMapper.xml:252–267` 的 `archiveWorkflowNodeRunsByWorkflowRunIds` 未 INSERT/SELECT `job_instance_id`，而真实 archive 表已有该列；`WorkflowArchiveService.java:48–51` 随后删除热节点。`SuccessInstanceArchiveMapper.xml:469–483` 另一条路径已经复制该列，不能覆盖独立归档。应统一两个生产归档路径的完整列清单，补历史取证链验收（属于 runtime 组主发现，本组交叉确认）。
2. **P2：Pipeline 续跑位点未进入实例归档树（V291 基线，已由 V292 修复）。** 当时 `pipeline_progress` 无 FK，`SuccessInstanceArchiveService` 未处理 progress，冷表未发现生产 INSERT；父 pipeline 删除可能留下热 progress 孤儿。V292 已加同租户父 FK及父/子锁，归档复制并核对冷位点后先删热位点再删父；最新实现和15/15 PostgreSQL专项复验见 §2.5 与整合验证矩阵。此处保留基线发现供追溯，不列作当前待实现项。
3. **P2：文件、资产、DQ 的现有 id FK 不约束同租户。** 7 条建议升级关系详见逐表表格。现有 Mapper EXISTS 和 Service 参数同源显著降低正常入口风险，但任一遗漏写入路径/导入/管理 SQL 均可建立 A 子行引用 B 父行。父级删除的 CASCADE/SET NULL 也可能修改另一租户子行。整改先建立父 `(tenant_id,id)` 非部分唯一约束，逐条替换 FK；`SET NULL` 仅清关联 id，保留非空 tenant_id；保留和明确审计保留语义。`asset_partition` 还需校验 asset_code 与 asset_id、result_version 与 business_key/job_instance_id 一致，复合 tenant FK 本身不覆盖这些业务关系。
4. **P2：Outbox 替代守卫没有并发删除闭环。** 真实 `event_delivery_log`、`event_outbox_retry` 均无 FK。`OutboxEventMapper.xml:264–282` 仅筛选现存错租户子行，候选未锁父；`OutboxArchiveService.java:58–63` 分次复制/删除。在 READ COMMITTED 下，“copy 后、delete 前”的新子行可能被删而未归档；“delete 子后、delete 父前”的新子行可能成为孤儿。`KafkaOutboxPublisher.java:314–334` 从 event 同源生成 tenant/id，`EventDeliveryLogMapper.xml:23–54` 仍是普通 INSERT；这种同源构造不是并发完整性约束。整改：选定带 parent created_at 的可引用复合身份或持久身份目录；或所有子写入和归档共享父围栏、锁/状态门禁。需要覆盖迟到投递日志、WAITING/RUNNING retry、父归档、合法跨期查询。
5. **P2：旧运维清理 SQL 仍绕过归档，并有明确漏子表。** `scripts/db/cleanup-outbox-events.sql:45–69` 删除 delivery log 和 outbox，但完全漏 `event_outbox_retry`；真实已没有原来注释声称的 FK/CASCADE，因此执行后可留 retry 孤儿。`cleanup-success-instances.sql:54–175`、`cleanup-workflow-runs.sql:50–54`、`cleanup-historical-failures.sql:30–154` 也是直接 DELETE，不经过 Java 归档的完整性候选过滤与冷表复制。整改：正式保留策略收敛到同一个可审计归档入口；纯销毁脚本应显式定位为授权清空，并同步全引用图/删除顺序，不能仍被当作等价补充归档手段。
6. **P2：冷镜像搬运路径不完整（V291 基线；`pipeline_progress_archive` 已由 V292 补齐）。** `atomic_task_config_archive`、`custom_task_type_registry_archive`、`batch_day_replay_session_archive`、`batch_day_replay_entry_archive`、`data_quality_rule_archive`、`data_quality_check_archive`、`workflow_definition_version_archive` 当时只找到 DDL、结构漂移检查及部分测试/文档，没有应用或运维 INSERT。不能把建表和 `archive_policy` 配置视为完成自动归档；对仍开放的冷表逐一明确“热表长期保留/预留冷表”，或补真正的保留、搬运与查询回退。`compensation_command_archive` 只覆盖关联到被归档 job 的命令，不覆盖独立 FILE/BATCH 等命令，需要独立保留策略。
7. **P2：配置自然键应区分预配置与错误引用。** `asset_freshness_policy.asset_code` 不能 FK 到“首次成功才生成”的 `data_asset`，否则无法预先配置 SLA；但现有 `ConsoleAssetFreshnessPolicyService.java:80–136` 只校验文本、类型、时区等，没有核对 JOB 作业存在，应加同租户作业配置解析/发布校验。`file_channel_health` 是配置派生状态且配置有 `(tenant_id,channel_code)` 唯一键，适合新增自然键 FK。模板的插件/密钥 JSON 引用应使用插件目录/版本绑定校验，不能盲加 SQL FK；当前 Console 模板 create/update 主要做持久化，插件校验多在 Worker 执行时发生，发布前可用性校验需列入整改。

### 证据简称

以下简称均为 worktree 下的真实文件，行号按本次读取快照：

- **SI** = `batch-orchestrator/src/main/resources/mapper/SuccessInstanceArchiveMapper.xml`；**SIS** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/archive/SuccessInstanceArchiveService.java`。
- **OB** = `batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml`；**OBS** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/archive/OutboxArchiveService.java`。
- **WR** = `batch-orchestrator/src/main/resources/mapper/WorkflowRunMapper.xml`；**WRS** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/archive/WorkflowArchiveService.java`。
- **PF** = `batch-worker/core/src/main/resources/mapper/PlatformFileRuntimeMapper.xml`；**PP** = `batch-worker/core/src/main/resources/mapper/PipelineProgressMapper.xml`；**FD** = `batch-worker/dispatch/src/main/resources/mapper/FileDispatchMapper.xml`。
- **AP** = `batch-orchestrator/src/main/resources/mapper/AssetPartitionMapper.xml`；**APS** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/asset/AssetPartitionService.java`。
- **RV** = `batch-orchestrator/src/main/resources/mapper/ResultVersionMapper.xml`；**RVW** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java`。
- **DQ** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dataquality/DataQualityCheckExecutor.java`；**DQM** = `batch-orchestrator/src/main/resources/mapper/DataQualityCheckMapper.xml`。
- **FG** = `batch-orchestrator/src/main/resources/mapper/FileGovernanceMapper.xml`；**CG** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java`。
- **RG** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java`；**RQ** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/RetryRequeueCoordinator.java`。
- **ASC** = `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/archive/ArchiveSchemaDriftCheck.java`。

### batch 逐表矩阵（30 / 30）

通用：tenant_id、created_by/updated_by/operator_id/trace_id 不因名字类似引用就添加 FK；身份域应允许系统/外部操作者和历史审计。JSON 内容逐类区分运行快照、插件配置和真实动态目标。下表“保留无 FK”不等于“不需要校验”。

| 表 | 实际引用与现有 FK | 应补 / 不用加 | 已有替代校验与实际局限 |
|---|---|---|---|
| `asset_freshness_policy` | `(tenant_id,asset_code,asset_type)`，无 FK | 不加到 `data_asset`：资产首次产出才创建，策略必须可提前配置；JOB 类型补同租户 `job_definition` 存在/启用/删除影响校验 | ConsoleAssetFreshnessPolicyService:80–136 仅做类型/取值校验；实际父配置未校验，不能标为闭环 |
| `asset_partition` | `asset_id -> data_asset(id)`；`result_version_id -> result_version(id)`；另 `job_instance_id/business_key/asset_code/payload_ref` | 两条 FK 升级 tenant 复合；job/history/polymorphic payload 不绑热表 FK；补资产编码、结果业务键/作业归属一致性 | APS:79–109、146–156 校验 instance/version 同租户同作业；AP:54–110 冲突 UPDATE 校验版本同租户且 EFFECTIVE，首次 INSERT 仅 VALUES，无全入口兜底 |
| `atomic_task_config` | `task_type`、`parameters` 中插件/SQL/执行参数，无 FK | 不加 task_type 到 custom registry：内置类型来自 Java schema，不是该表；参数按对应类型 schema/配置校验 | ConsoleAtomicTaskConfigService:74–81 locateSchema + validateAgainstSchema；不是数据库约束，外部写入仍要发布检查；冷镜像未接搬运 |
| `compensation_checkpoint` | `compensation_id varchar(128)`、handler、payload 中实例 id，无 FK | 不直接 FK bigint command.id：V63:48–53 明确是 command.id 或 correlationId；先明确是否启用/命令身份模型，再决定 typed reference | 全仓生产代码未找到使用此表的 Mapper/写入；只有 DDL/说明，不能当作已实现 Saga/替代校验；需文档标明预留或补实现和清理策略 |
| `compensation_command` | `target_id` 按 compensation_type 多态；`related_job_instance_id/related_file_id/approval_id varchar/job_code/result_summary`，无 FK | 多态目标和历史关联保留无热表 FK；审批号不能误绑 approval bigint；补明确目标绑定、同租户和历史回退，运行中目标禁止清理 | CG:252/258/287/362/375 按租户读取实际目标；CG:698 调用 RelatedFileReferenceValidator；业务失败命令审计可保留（CG:127–140）。SI:500–513 只归档关联作业的命令，不覆盖所有命令 |
| `custom_task_type_registry` | `declared_by_worker_code`、descriptor JSON，无 FK | 不 FK 到在线 worker：worker 下线/退役不应删类型声明历史；descriptor/code 走注册契约和版本兼容 | DefaultWorkerRegistryService:243–264 用注册请求同租户来源且拒绝敏感 defaults/schema；并非 descriptor 内所有业务引用校验；冷表仅预留 |
| `data_asset` | `owner_job_code`，asset_type/code，无 FK | 资产谱系应跨配置删除/重建保留，不加热 job_definition FK；JOB owner 应记录稳定业务标识/版本来源 | APS:84–86 从实例构造；AP:25–51 按 tenant/code/type upsert+解析 id；当前无运行时删除。其他资产类型需各自 resolver，不能假设 code 都是 job_code |
| `data_quality_check` | `rule_id -> data_quality_rule(id) ON DELETE SET NULL`；job_instance_id/rule_code/metrics/failure_sample | 规则删除只 SET NULL `rule_id`；`job_instance_id` 是历史弱引用，不加阻断归档的热 FK。V325 以同租户 `(tenant_id,id)` 锁定父实例，禁止已有引用后的父 ID/租户/业务日改写，允许父归档删除；`rule_code` 是评估快照 | DQ:77–78 同租户取规则，:215–228 写检查；`DataQualityCheckMapper` CTE 与 V325 触发器按相同租户/实例身份防孤儿及并发删除。`data_quality_check_archive` 仍无生产搬运/回查路径 |
| `data_quality_rule` | `scope_business_key`、expression、threshold_json，无 FK | scope 支持规则/模式匹配，expression 引用业务库不是平台表 FK；保留 Java SQL解析与业务数据源权限/参数绑定 | DQ:151–164 经 sqlRuleProbe 使用参数化只读规则；规则是配置实体，可独立维护，不能绑定某一个 result_version；冷表未接搬运 |
| `dead_letter_task` | `(source_type,source_id)`、payload_ref，无 FK | 保留多态/历史引用；重放必须按类型与租户解析，不应随热源删除级联丢死信 | RG:438–503 重放按 tenant 取死信并回读分区，RQ:74–80/149–169 校验父存在与活跃状态；源归档后走明确 orphan/GIVE_UP，不能称可继续重放冷历史。cleanup-dead-letter-task.sql:69–100 先复制并核对 archive id 后删 |
| `event_delivery_log` | `(tenant_id,outbox_event_id,outbox_event_created_at)` | V306 `NO ACTION` FK 指向分区父键 `(tenant_id,id,created_at)`；应用 `OutboxChildRecordService` 按相同身份 `FOR KEY SHARE` 写热表，父已归档则写对应冷审计表；目标 worker 保持投递时身份快照，不加在线 registry FK | KafkaOutboxPublisher → OutboxChildRecordService；直接 SQL 缺父/错租户及父删除拒绝、归档并发迟到回调写冷表已由真实 PG 局部验证。其它调用方、分区淘汰、全局故障恢复和冷热查询仍开放 |
| `event_outbox_retry` | `(tenant_id,outbox_event_id,outbox_event_created_at)` | V306 `NO ACTION` FK 与 delivery 使用相同三列身份；应用 retry 写入按热父行锁定，已归档时写冷 retry 表；cleanup SQL 与归档都按完整身份处理子行 | DefaultScheduleForwarder → OutboxChildRecordService；直接 SQL完整性和父子归档双事务已有真实 PG 局部验证。其它调用方、重试幂等/恢复、分区淘汰及冷热查询仍开放 |
| `file_audit_log` | `file_id -> file_record(id) ON DELETE SET NULL`；operator/evidence_ref/detail_summary | 升级 tenant 复合 FK并保持只清 file_id；审计行不能 CASCADE 删除；evidence_ref 是外部证据地址不加表 FK | PF:347–358、FG:369–380 同租户 EXISTS；软删除和真实物理删除都要明确历史定位证据，目前不是 DB 同租户约束 |
| `file_channel_config` | channel_code、config_json secret_ref/endpoint 等，无 FK | 主配置不用人为加父；secret_ref/version 位于 JSON，走租户秘密解析和版本 pin，外部 endpoint 无 SQL FK | DefaultConsoleFileChannelApplicationService:66–96 保证 tenant/code，soft-delete 后复活；DispatchSecretResolver:27–58 校验 pin 并按 tenant/secretVersionId 取秘密。配置保存到执行存在生命周期，需发布/轮换/撤销契约 |
| `file_channel_health` | V315 `(tenant_id,channel_code)→file_channel_config(tenant_id,channel_code) ON DELETE CASCADE` | 同租户引用由 FK 守护；软删除不删父行，因此保留探测状态；物理删除通道时清理派生健康状态。数据库直写可写健康行但不能写孤儿/错租户引用 | DispatchChannelHealthMapper:118–143 读取 join 过滤启用配置，:146–166 直接 upsert；`DispatchChannelHealthServiceIntegrationTest#shouldEnforceConfiguredTenantReferenceAndCascadePhysicalDelete` 覆盖直接 SQL 负例及删除清理。软删除后仍保留健康状态的行为继续由原集成场景验证 |
| `file_dispatch_record` | file_id FK CASCADE；pipeline_instance_id FK NO ACTION；channel_code/dispatch_target/receipt_code/external_request_id | 两条 FK 升级 tenant；channel_code/target/receipt 是投递证据快照，不加会随配置清理破坏审计的 FK；若要求 file 与 pipeline 主文件一致需业务规则校验 | FD:31–47 检查同租户 file 和 pipeline 各自存在，但未校验两者业务归属；SI:436–449 先复制，后 delete。现有 file CASCADE 要与冷证据复制的保留策略一起审查 |
| `file_error_record` | file_id/pipeline_instance_id/pipeline_step_run_id，无 FK；raw_record 错误快照 | 不盲加热父 FK/CASCADE：错误证据可长于流水线；补生命周期说明与冷热查询/显式保留，按是否仍执行区别校验 | PF:318–344 检查同租户 file、pipeline 和 step 属于 pipeline；没有锁住父，父归档后热行仍保留。来源不全/无法定位应显式状态，不误当永远非空父关系 |
| `file_record` | source_type/source_ref 多来源；metadata.templateCode/fileGroupCode/成员ID/receipt；存储位置，无 FK | 历史版本文件不 FK 当前模板/组定义/作业热表；存储对象无法用 SQL FK。保留文件 checksum/version 身份与归档证据；解析动态引用须 tenant/业务日/版本约束 | FG:26–34 按 tenant+模板元数据读取配置；PF:275 起写文件记录。SI:334–433 按多个热来源复制同租户文件但不删热文件。对 metadata 不是全键外键保证 |
| `file_template_config` | default_query_code、load_target_ref/export_data_ref/encryption_key_ref；字段映射/校验/preprocess JSON，无 FK | 插件、SQL模板、秘密和业务库表均非同一种父表；不加伪 FK；应在配置发布/启用时解析相应 registry/secret/version 和输入schema | Console模板服务:93–124 主要唯一键检查后持久化，:338–344 仅 trim plugin refs；ExportDataPluginRegistry:41–50 执行时拒绝未知插件，ImportLoadPluginRegistry 同模式；不能把运行时失败等同发布前校验 |
| `outbox_event` | aggregate_type/aggregate_id 多态、payload_json 业务快照；分区PK(id,created_at)，无父 FK | 不 FK 多态 aggregate 到热 task/job；消息需独立于源生命周期投递/取证。子引用需包含 created_at 或持久 identity，不是 PG17 不支持分区 FK | OB:21–59 CTE 抢 dedup 再插事件保证幂等；OBS:58–63 同事务归档；未证明 publisher/late日志与归档共享围栏 |
| `outbox_event_dedup_key` | `(tenant_id,event_key)` 唯一，first_event_id 历史身份，无 FK | 不 FK 热事件：账本必须跨分区轮转/事件归档持续阻止重复；需独立保留期限与源事件冷热定位 | OB:23–33 同语句抢占；first_event_id 不参与当前新写，不能用其空值当作完整性失败；删除账本是幂等策略变化 |
| `pipeline_definition` | job_code 多版本、worker_group，无 FK | 当前系统 Pipeline 已迁到 Java 目录，旧定义是兼容/历史配置；不反向给当前 runtime 加依赖；job_code 版本/删除策略需按实际消费入口校验 | V236 取消 runtime 对该表强依赖；(tenant,job_code,version)唯一。配置修订 trigger 只刷新 revision，不是引用校验 |
| `pipeline_instance` | file_id 单id FK；related_job_instance_id 无 FK；pipeline_definition_id 已解除FK；system_pipeline_code/version | file FK 升级 tenant；related_job 走分区身份/活动校验+归档围栏；不用恢复 legacy definition FK；Java system catalog 校验 code/version | PF:88–119 同租户 file/job EXISTS+ON CONFLICT tenant guard，:122–132 bind file guard；SI:302–316 复制后删，但 progress 漏。EXISTS 无父锁，不等于 DB FK |
| `pipeline_progress` | V291时为 tenant+pipeline_instance_id+stage，无FK；V292后已具备同租户父引用 | 当前有同租户复合FK、热子写入/父删除并发围栏；成功、失败和终止运行按冷副本核验后保留checkpoint，恢复路径继续读取热位点 | V292实现见§2.5；当前版本专项PostgreSQL 15/15，日志 `pipeline-progress-archive-current-v320-20261019.log` |
| `pipeline_step_definition` | pipeline_definition_id FK；step_code/stage/impl_code/step_params | 保留现有 FK，tenant由父继承无需重复 tenant FK；impl/stage 是 Java实现标识不加表 FK；旧配置/系统目录边界必须清楚 | 数据库保证父存在；配置写入须从已授权父进入，父 id 本身不完成授权。修订 trigger 不验证 impl 能运行 |
| `pipeline_step_run` | pipeline_instance_id FK；step_code/stage、输入输出快照 | 保留父 FK；不加到可变 step_definition：运行步骤由 system pipeline version 决定并保留历史快照 | PF:235–243 写父ID，数据库防孤儿；SI:318–332 复制后子先删。tenant继承父，直接id更新仍应由已授权/认领上下文进入，不能额外声称父 FK就是授权 |
| `result_version` | job_instance_id、approval_id bigint、payload_ref 随 payload_storage 多态，无 FK | 作业/审批是历史来源，不加必须随热父存活的 FK；FILE_RECORD payload 按类型校验引用与保留策略，EXTERNAL_REF不加SQL FK；审批关联要按真实审批目标校验 | RVW:72–90 必须在终态事务中并按tenant/business_key锁写；RV:252–311 先复制再改状态，:342–389 删除前检查资产引用；lineage 有冷热回退。仅 RVW 当前写 INLINE_JSON，不证明其他 payload 分支完整 |
| `retry_schedule` | related_type/related_id 多态，无 FK | 保留多态不加单表 FK；只对实际支持的类型派发，活跃目标保护/已清理目标取消与留痕；独立终态清理策略 | RG:166–183 当前只创建 JOB_PARTITION；RQ:54–80/149–169 按tenant取分区/父并锁父确认活跃；不应将 CHECK允许多类型说成所有类型都支持。归档 job 时不处理历史 retry 行 |
| `worker_registry` | worker_group/resource_tag/worker_pool_code；capability_tags/task_capabilities；process/build/sdk identities，无 FK | runtime group/pool不是独立强主表；resource_tag可按资源策略校验，不因代码相等即加FK；worker动态上线/退役，历史任务不可CASCADE | DefaultWorkerRegistryService tenant+worker_code 注册/心跳；同租户唯一键。代码/能力 JSON 是运行声明，需租户认证/版本规则，数据库无跨表校验 |
| `worker_report_outbox` | task_id、partition_invocation_id、payload_json，无 FK | 不加平台热task FK：同协议支持平台PG/Worker本地SQLite，断网回报需要独立持久化；服务端认领/tenant/invocation校验承担实际目标绑定 | WorkerReportOutboxPgMapper:6–18 按tenant/task upsert；WorkerReportOutboxCoordinator:90–110 反序列化、HTTP报告成功后删、失败重试。无本地父存在保证；迟到/未知任务需有终态处置，不无限重试 |

### archive 逐表矩阵（26 / 26）

全部保留“冷表不 FK 热表”的方向：热表独立保留/分区淘汰，历史证据不可随当前配置/运行行删除。冷表之间也不统一补 FK，因为搬迁批次/范围不同、同一谱系可跨热冷两侧；作为替代，必须覆盖列完整、身份/tenant对应、复制成功才删除、并发围栏、冷热查询和可审计清理。ASC:41–79 覆盖全部镜像名称，:83–118 检查结构；并不执行归档或检查行级引用。

| 冷表 | 引用 / 历史字段 | 当前搬运/清理证据 | 结论与必须保留的整改 |
|---|---|---|---|
| `atomic_task_config_archive` | task_type/parameters/created_by | V165建表；ASC:75–76；未找到生产 INSERT | 不加热 schema FK；明确为预留/长期热保留，或补保存配置版本的搬运与查询，不能称已归档 |
| `batch_day_replay_entry_archive` | session_id、source/rerun_instance_id、result_version_id、job_code、plan_snapshot | V110建表；ASC:61–62；未找到生产 INSERT | 保留冷热跨批引用；如启用，session+entry一致搬运，源/结果按tenant冷热回查；目前未闭环 |
| `batch_day_replay_session_archive` | calendar_code、approval_id、scope_payload、config_version | V110建表；ASC:60–62；未找到生产 INSERT | 保留审批/配置版本快照；明确会话与子项共同保留，不依赖当前日历或approval热行 |
| `compensation_command_archive` | 多态target、job/file、approval_no、result_summary | SI:500–513；SIS:58/74 复制后删 | 仅按 related_job_instance_id 覆盖子集；FILE/BATCH等独立命令补生命周期，checkpoint仅预留不可作证据 |
| `custom_task_type_registry_archive` | declared_by_worker_code、descriptor/version | V159建表；ASC:71–72；未找到生产 INSERT | 不绑在线worker；明确类型退役历史/版本归档是否启用 |
| `data_quality_check_archive` | rule_id、rule_code、job_instance_id、metrics/sample | V118/V147建表；ASC:63–66；未找到生产 INSERT | 保留规则快照，源作业可在冷热；补DQ证据保留与回查，不能用rule热FK |
| `data_quality_rule_archive` | scope_business_key、规则expression/threshold | V118建表；ASC:63–66；未找到生产 INSERT | 不FK具体结果；需明确保存已运行规则版本，否则只靠热规则更新不能完整解释历史DQ |
| `dead_letter_task_archive` | 多态source、payload_ref、重放计数/结果 | cleanup-dead-letter-task.sql:69–100 copy+archive exists后delete | 保留不FK源；脚本执行未验证，自动搬运未找到；冷热来源丢失需明确 orphan，不伪装可重放 |
| `event_delivery_log_archive` | outbox_event_id、target_worker_id、summary | OB:320–335，OBS:58/61 | 与parent同租户join搬运；保留跨批历史引用；补迟到日志/归档锁围栏，不能只依赖一次SELECT |
| `event_outbox_retry_archive` | outbox_event_id、event_key、retry状态 | OB:338–351，OBS:59/62 | 同上；活动retry不能随终态父误清；旧SQL漏子表需修 |
| `file_dispatch_record_archive` | file_id、pipeline_id、channel_code、target/receipt | SI:436–449，SIS:54/68 | 不FK当前channel；要保留同tenant文件/流水线和实际投递快照，直接file级CASCADE/旧清理不能绕过copy |
| `file_record_archive` | source_ref、metadata/template/group、storage path/checksum/version | SI:334–433，SIS:53；本归档服务不删除热file | 是关联作业的文件证据快照，不是所有文件的完整自动冷迁移；无作业关联文件需独立保留策略；不可用当前模板覆盖历史 |
| `job_execution_log_archive` | job_instance_id、partition_id、detail_ref/extra | SI:488–497；SIS:57/70；cleanup-job-execution-log.sql:51 起独立copy | 保留源冷热/外部日志引用；清理热日志前确认冷副本及tenant，不让日志冷表FK阻塞分区淘汰 |
| `job_instance_archive` | definition/trigger/parent/file/replay_session、params/rerun snapshot | SI:152–252；SIS:47/77 | 不绑热配置；完整运行身份应含tenant/id/biz_date；父清理和子晚到围栏另补；历史父指针不能因热父不存在认作非法 |
| `job_partition_archive` | instance_id、worker_code、invocation、source_file/template/target_ref | SI:254–268；SIS:48/71 | 与实例同批复制；文件/模板/worker保留当时身份，新增字段与copy守卫一起维护；不能FK在线worker/当前模板 |
| `job_step_instance_archive` | instance/partition/task/file、step_code、summary | SI:288–300；SIS:50/64 | 同批运行树；SI:47–66检查tenant/instance归属但不是所有缺父检查/并发锁；补行级一致性与冷热可回查 |
| `job_task_archive` | instance/partition、worker_code、payload/effective_parameters | SI:270–286；SIS:49/65 | 同批运行树；保留Worker/SDK/执行参数快照；无冷FK需要由事务围栏和完整copy补足 |
| `outbox_event_archive` | aggregate_type/id、event_key、payload | OB:308–317；OBS:60/63 | 保留独立事件历史，不绑热aggregate；id-only冷PK依赖全局id分配，源分区身份及导入冲突要有守卫；旧直接DELETE不等价归档 |
| `pipeline_instance_archive` | file/job/legacy definition/system code/version | SI:302–316；SIS:51/69 | 按关联job搬运，独立pipeline不自动覆盖；冷保留系统目录版本；V292同批搬运其 progress 子记录并先删子再删父 |
| `pipeline_progress_archive` | pipeline_instance_id、stage/position | V164建表；V292生产归档执行冷副本插入与逐行核对 | V292先锁定候选父/子，冷副本一致后删除热子记录；当前版本专项 PostgreSQL 15/15，见 §2.5 |
| `pipeline_step_run_archive` | pipeline_instance_id、step/stage、input/output | SI:318–332；SIS:52/66 | tenant从父推导，父+子一致搬运；需要保留系统pipeline版本，不给历史step_code绑新定义 |
| `result_version_archive` | job_instance/approval、business_key/version、payload refs | RV:252–311、342–375；:216 冷查询 | dedicated copy后热状态变化/清理；资产引用保护RV:383–387；继续保留完整输出/冷热lineage，不绑热job/审批 |
| `trigger_outbox_event_archive` | request_id、payload/trace | cleanup-trigger-outbox-events.sql:52–82 copy+archive exists后delete | 已有独立脚本，未找到应用自动归档调用；request冷热/幂等生命周期另核，不能宣称自动归档已完成 |
| `workflow_definition_version_archive` | definition_id/code/version、nodes/edges/calendar/dependency JSON | V167建表；ASC:77–79；未找到生产 INSERT | 保留 immutable版本历史，不指向可变节点热表；明确热版本长期保留或补搬运，运行快照查询不能因迁冷失效 |
| `workflow_node_run_archive` | workflow_run_id、job_instance_id、node_code、output/sensor/source snapshot | SI:469–483含JOB关联；WR:252–267漏job_instance_id；WRS:48–51复制后删 | 已发现字段丢失，优先修复；不能用结构一致声称完整。无tenant列须父归档可解析，冷热跨边检索要明确 |
| `workflow_run_archive` | definition/singleJob/trigger/relatedJob、biz_date、execution snapshot | SI:452–465与WR:234–249；SIS:55/73及WRS:49/51 | 两条copy均含新trigger/version/interval/snapshot；保留历史，不绑热定义；独立归档需考虑等待/子workflow等新引用（runtime组核查） |

### 验收要求（待实施后执行，本轮未运行）

- 新复合 FK：A child→B parent 应失败；同tenant正确引用成功；parent删除/更新按明确策略处理；SET NULL不清tenant；检查子索引和锁开销。
- 分区引用：跨月父/子、迟到日志/报告、归档并发、分区拆卸/恢复同时覆盖；不能通过关闭FK或只改注释完成整改。
- 归档每表：真实hot写入→copy→delete→冷热查询回退；校验所有列尤其JOB关联、progress、JSON和租户。错误关联不得被静默级联删除；copy id冲突需证明已有冷行与目标身份/内容兼容。
- 清理每入口：Java调度、手工SQL、管理员清理、分区保留策略共用完整引用清单；旧脚本中的“外键会兜底”注释按实际V291修正。
- 预留冷表：逐表定义责任人/热保留上限/是否启用搬运/回查入口；没有生产搬运路径应保持“未实现/预留”，不得写“已归档”。

## 配置、身份、通知、框架与业务库（68 表）

- 证据基线：父任务只读导出的 `platform.json`、`business.json`，PostgreSQL 17.11 / 平台 Flyway V291；逐表核对逻辑父表，分区继承约束不重复算业务关系。
- 本报告仅静态代码追踪与真实目录核对，不代表脏数据为零或并发/归档测试通过。没有修改数据库、源码或执行测试。
- 范围：下列 47 张平台 `batch` 表、11 张 Quartz 表、10 张业务库逻辑表；`business_calendar` 与日历专项报告合并。
- “不直接加 FK”表示该引用不是固定父表外键或存在外部/历史生命周期；不表示可以取消写入校验。所有 `created_by/updated_by/reviewed_by/operator_id` 默认是操作人审计快照，不绑可删除账号。租户根身份问题见 C06，不能把每个 `tenant_id` 都机械列为漏外键。

### 应进入整改计划的问题

| ID | 优先级 | 事实与影响 | 处理建议与验收 |
|---|---|---|---|
| C01 | P2 | `config_approval.release_id` 没有 FK；服务对 `(tenant,release)` 查询并串行化同一配置审批，但 Mapper 可直接写入不存在/异租户 release。 | 补 `config_release(tenant_id,id)` 唯一键及审批复合 FK，默认 RESTRICT；保留历史审批。验证同租户有效、异租户/不存在拒绝、审批并发、父记录清理顺序。证据 E01。 |
| C02 | P2 | `subscription_rule.channel_code` 没有 FK。服务已有同租户通道查询；裸 Mapper/独立 SQL 可写孤儿。通道软删除后，enabled 规则被执行期 INNER JOIN 无声过滤。 | 补 `(tenant_id,channel_code)` FK以约束身份；另明确通道软删/停用时规则停用、受影响列表或不可投递状态。FK 本身不能限制 `is_deleted/enabled`。不能把关闭通道本身定性为错误；整改对象是依赖影响的可见性与合法关系。证据 E02。 |
| C03 | P2 | `webhook_delivery_log.subscription_id` 现有单列 FK只能保存在性，不能防异租户关联。裸 INSERT 未做归属检查；relay 已按同租户查订阅并在无效时 GIVE_UP，降低误投递但不修复错绑数据。 | 升级为 `(tenant_id,subscription_id)` FK，订阅增加 `(tenant_id,id)` 唯一键。保留软删与日志留存，不做 CASCADE DELETE；验证错租户拒绝与软删后重试终止。证据 E03。 |
| C04 | P2 | `business_tenant_placement.placement_key` 无 FK；设计支持数据库 catalog 与 `routing.shards` 配置后备，不能直接一律 FK catalog。当前没有可校验拓扑时跳过合法性检查，catalog 删除也不检查现有 placement。 | 明确 catalog/config 事实来源模式：catalog 模式校验同一权威目录并禁止删除被引用片；配置模式校验已加载配置且未知拓扑 fail-closed 或显式待激活。需要模式/产品决定后实施，不为加 FK取消现有后备。验证删除/禁用被引用片、空拓扑、TTL窗口、进程重启与配置后备。证据 E04。 |
| C05 | P2 | `resource_tag(resource_type,resource_code)` 是多态引用，不能声明单表FK。V314及§2.33–2.34已补四类同租户目标核验、父删除/改名清理及并发保护；disabled与可恢复软删除模板仍保留标签。 | 已验证不存在/异租户拒绝、自然键改名、Workflow多版本最后父身份清理、父删/改名竞争及disabled/软删除语义。仍需核对所有批量/运维写删入口、Workflow多版本并发增删和锁容量；证据 E05、§2.33–2.34；REF-07/08与DBSAFE-3整体不关闭。 |
| C06 | P2 | 大多数 tenant_id 没有指向 tenant 的 FK；ConsoleTenantGuard只做格式/授权作用域，不检查租户注册存在。账号服务对业务角色检查 ACTIVE 租户，system 平台角色有意例外。 | 单列治理项：明确 system/default/外部租户的注册、停用、清理契约；对要求已注册租户的配置写入补存在性/状态校验或按表补 FK。禁止全库机械补；RLS不保证租户主数据存在。验收管理员误输租户、后台写入、系统租户与配置包导入。证据 E06。 |
| C07 | P2/需约定 | AI owner_user_id 实际来自 principal.username，push也按username归属；没有账号 FK。账号可禁用，独立清理 SQL 可删除；历史/对象存储生命周期与账号不同。 | 当前不直接对名称加 FK。补账号删除/同名重建后的会话、附件、push订阅归属验收；如允许同名复用，应使用稳定 account_id / 外部 issuer+subject 或身份代际。未查到普通业务 API调用 deleteById，不能声称当前普通用户可直接利用。证据 E07。 |
| C08 | P2/业务定义 | 业务库 settlement_detail.customer_no、transaction.account_no 与 customer_account 命名相关，但现有 DDL 没声明相同语义；导入/结算的外部业务键可能允许独立到达。 | 不擅自补客户 FK；由业务定义“必须本地已有客户”还是“外部标识/延迟对账”，对应复合 FK 或数据质量/对账规则。已有 settlement_detail→settlement_batch 租户复合 FK应保留；补引用失败/源数据迟到验收。证据 E10。 |

### 平台 batch 逐表清单

| 表 | 引用/关联 | 当前约束与替代校验 | 判定与待补 |
|---|---|---|---|
| api_key | tenant_id；created_by/revoked_by；scopes | 无 FK；scopes/操作人是授权配置与审计属性。 | 不绑操作人账号；tenant按C06；scope按授权词表校验，不是关系表FK。DDL `db/migration/V47__create_api_key.sql:2`。 |
| batch_runtime_default_parameter | module/parameter_key/yaml_path/env_var | 模块默认配置登记，自然键/配置路径，不是数据库父键。 | 不加FK；验证代码配置绑定/重复键，非引用缺口。`db/migration/V24__batch_runtime_default_parameter.sql:8`。 |
| biz_table_schema | schema_name/table_name；columns JSON | Worker上报业务库结构快照，父对象在另一个数据库。 | 不跨库加FK；验证注册TTL、实际表结构和允许的列，旧快照不应成为目标表存在性的唯一保证。`db/migration/V66__create_biz_table_schema.sql:5`。 |
| business_calendar | group_code、cutoff_schedule JSON | 当前无FK；日历自然键依赖由主报告日历章节补充。 | group_code不能仅因字符串就忽略，需同租户组存在性/删除策略；JSON时间表不是FK。 |
| business_shard_catalog | placement_key；secret_ref | PK placement_key；secret_ref明确为外部secrets/vault引用名。 | secret_ref不绑batch.secret_version；被placement引用的删除/禁用策略见C04。 |
| business_tenant_placement | tenant_id；placement_key | PK tenant_id；服务从enabled catalog优先、配置后备找合法key；空集合跳过。 | 不直接加统一catalog FK；C04、C06。 |
| config_approval | release_id；申请/审核操作人 | 无FK；同租户loadRelease+业务锁；pending唯一索引仅防重复申请。 | release身份补复合FK C01；操作人保留快照。 |
| config_release | config_type/config_key；config_payload；gray_scope | 配置类型/自然键/版本唯一，payload为版本快照；应用按类型validate/apply。 | 不给多态config_key或payload加单表FK；目标已删除后的历史版本仍保留。`DefaultConsoleConfigApprovalApplicationService.java:87`、`DefaultConfigReleaseApplyService`。 |
| config_change_log | config_type/config_key/version_no；operator_id/trace_id | 配置历史审计，无FK。 | 不绑当前配置/账号；保持历史快照与版本可追踪。`db/migration/V22__create_config_release_and_secret_version.sql:54`。 |
| config_sync_log | source_env/target_env/config_types/detail_json；operator_id | 跨环境同步执行记录，无FK。 | 环境/远端资源不能FK；保留结果快照及失败明细。`db/migration/V49__create_notification_approval_config_sync.sql:93`。 |
| console_ai_attachment | owner_user_id；conversation_id+turn_no；object_key | 已有 `(tenant_id,conversation_id,turn_no)` FK到turn CASCADE；CHECK保证BOUND时两个目标键必填；服务验证同owner附件。 | 保留FK；owner不盲加username FK，见C07；对象键外部存储，清理队列替代数据库级联对象删除。E08。 |
| console_ai_audit_log | request_id/trace_id/session_id/operator_id | AI审计快照，不是会话表的conversation_id。 | 不加FK；不得把session_id误当AI conversation_id。`db/migration/V11__create_console_ai_audit_log.sql:5`。 |
| console_ai_conversation | owner_user_id | PK(tenant_id,id)，强制RLS；服务检查同owner，owner来自principal.username。 | C07；RLS不能解决同名账号代际问题。 |
| console_ai_monthly_usage | tenant_id/billing_month | 月度计费聚合，可能比会话留存更久。 | 不绑会话/账号；tenant按C06，但不能因删会话级联删计费。`db/migration/V218__console_ai_conversations_and_cost_usage.sql:53`。 |
| console_ai_object_cleanup | object_key | 对象删除重试队列，故意在附件/会话删后保留。 | 不向附件加FK，否则阻碍删除或丢清理任务。E08。 |
| console_ai_turn | conversation_id | 已有(tenant_id,conversation_id)→conversation复合FK CASCADE；服务持有会话行锁分配turn。 | 保留，已覆盖租户/存在性。E08。 |
| console_external_identity | account_id；issuer/subject；linked_by | 已有(tenant_id,account_id)→console_user_account复合FK CASCADE。 | 保留；issuer/subject外部身份自然键不再外键；linked_by审计快照。`db/migration/V222__console_oidc_external_identity.sql:8`。 |
| console_maintenance_state | affected_services；updated_by | 平台维护状态与模块列表。 | 不加FK；模块枚举/配置校验即可。`db/migration/V215__create_console_maintenance_state.sql:3`。 |
| console_operation_audit | aggregate_type/aggregate_id；params；operator_id | 多态历史业务对象与操作者快照。 | 不FK到当前活表，避免资源删除抹除审计；访问查询仍需租户约束。`db/migration/V130__create_console_operation_audit.sql:13`。 |
| console_push_approval_notification | approval_no | 从同租户approval查询候选后写去重标记；唯一(tenant_id,approval_no)。 | 不强绑活审批，允许审批清理后保留幂等标记；必须维护去重记录TTL/对应取候选的tenant范围。`ConsolePushApprovalNotificationMapper.xml:19,42`。 |
| console_push_job_notification（已退役） | job_instance_id | 历史去重标记及旧 Mapper 已在新库 V330 退役；非空旧表拒绝切换。 | 当前设备意图改存 console_push_job_delivery，按 tenant/terminal_event_key 外键关联同事务终态回执，设备凭据仅存摘要，发送时核对原设备；不新增跨归档 Job 外键。见 R32 Push 收口记录。 |
| console_push_subscription | username；endpoint | 按tenant+username+endpoint唯一；认证服务写入；过期/410清理。 | 外部endpoint不FK；username身份代际见C07。`ConsolePushSubscriptionMapper.xml:23,35,65`。 |
| console_usage_daily | tenant_id；source/metric_code/page_code/app_version | 使用量日聚合，页面/版本都是指标维度。 | 不FK当前页面/租户配置，保留历史统计；租户写入身份见C06。`db/migration/V216__create_console_usage_daily.sql:6`。 |
| console_user_account | tenant_id；authorities_csv | 无tenant FK；业务角色服务校验ACTIVE租户，平台角色要求system；角色CHECK。 | 保留system特例后决定tenant FK；C06、C07。 |
| console_user_batch_operation | operation_id/request_id；actor_username；tenant_ids JSON | 批量开户审计/幂等快照，批处理会验证租户；不具有逐账号子表关系。 | 不给历史JSON/操作者FK；校验结果审计与幂等源摘要。`db/migration/V220__console_user_batch_provisioning.sql:5`；`ConsoleUserBatchProvisioningService.java:326`。 |
| flyway_schema_history | version/script/installed_by | Flyway元数据，不是业务实体关系。 | 不加业务FK；版本校验由Flyway负责。 |
| forensic_export_log | job_codes/scope/biz_date范围；storage_path；requested_by | 导出条件和对象存储产物快照。 | 不FK当前Job/账号或对象键，历史取证要能跨归档保存。`db/migration/V116__forensic_export_log.sql:17`。 |
| idempotency_record | idempotency_key；result_payload | 请求幂等记录/结果快照；没有规范化父ID列。 | 不向payload中任意ID补FK；需TTL不短于重试窗口。`db/migration/V38__create_idempotency_record.sql:5`。 |
| notification_channel | channel_code；config_json | UNIQUE(tenant_id,channel_code)；config_json含外部通知地址/凭据配置；软删除。 | 通道是父；C02补子引用。外部地址不FK；软删除依赖状态另管。 |
| notification_delivery_log | rule_id/channel_code/alert_event_id；payload_json | 无FK；记录真实投递结果，规则可硬删、通道可软删、告警可归档；Alertmanager直投可无rule/alert。 | 不一律加FK，保留审计快照和可空来源；正常dispatcher从同tenant规则派生，其他写入口需同tenant校验。`NotificationDeliveryLogMapper.xml`；`SubscriptionRuleWebhookDispatcher.java:360`；`AlertmanagerNotifyService.java:168`。 |
| process_staging（平台库） | batch_key；target_schema/target_table；payload | 实际目录存在，但代码约定真正WAP staging在业务库、共享目标表事务。 | 不向Job或biz_table_schema加FK；确认平台副本为兼容DDL/未使用，防误路由。业务库写入/发布核对同租户batch_key与目标白名单。E10/E11。 |
| quota_runtime_state | quota_scope/owner_code | scope决定tenant/queue/group，owner_code多态；UNIQUE(tenant_id,scope,owner)。CAS version。 | 不向resource_queue单表FK；运行窗口状态允许延迟清理；按scope校验和策略删改时状态收敛。`QuotaRuntimeStateMapper.xml:30,57,60`。 |
| resource_queue | worker_group；resource_tag；fair_share_group | Worker组/标签/公平组是匹配配置，非固定父ID。 | 不加无权威父表的FK；队列代码作为其他表父引用由各专项处理。`db/migration/V3__create_config_tables.sql:5`。 |
| resource_tag | resource_type/resource_code | 多态资源标记，类型CHECK；应用和V314按同一 tenant/type/code 身份校验四类父资源；disabled与file-template软删除不改变持久身份，父物理删除/改键在最后匹配父行离开后清理。 | C05/REF-07局部实现及真实PG证据见§2.33–2.34；全写入/运维旁路及并发容量仍开放，不加单表FK。 |
| secret_version | secret_ref；secret_payload | UNIQUE(tenant_id,ref,version)，当前版本partial唯一；外部密钥引用/加密快照。 | 不以secret_ref再绑自己或外部KMS；引用者需选定版本/当前版本与撤销策略。`db/migration/V22__create_config_release_and_secret_version.sql:31`、`V210__config_release_and_secret_invariants.sql:20`。 |
| shedlock | name/locked_by | 框架分布式锁，locked_by主机实例标识。 | 不FK临时Worker注册表，保留框架生命周期。 |
| stateful_backend_binding | feature_key/backend_identity/last_cutover_id | feature为配置特性键；后端地址是外部资源；guard以同事务advisory+row lock写binding/history。 | 不外键到外部后端；last_cutover_id是可空审计token，不为此引循环FK；保留一致事务验收。E09。 |
| stateful_backend_cutover_history | feature_key/cutover_id | 不可变切换审计；(feature_key,cutover_id)partial唯一；同事务写。 | 不向可退役binding级联；历史应独立留存，补检测不等于强制FK。E09。 |
| step_registry | module/impl_code/impl_class | 应用启动上报bean白名单，不是关系父ID；last_seen心跳。 | 不加FK到Java类；使用配置校验识别当前可用impl_code。`db/migration/V65__create_step_registry.sql:7`。 |
| subscription_rule | channel_code；job_code_filter；event_types | channel服务存在性校验+运行时同tenant JOIN；job_code_filter/event_types为筛选表达式。 | channel补FK C02；表达式不按每个token建FK。 |
| system_parameter | param_key/param_value | 动态参数键值；值可包含外部地址或配置，不是统一父键。 | 不泛化FK；按参数schema校验，tenant根见C06。`db/migration/V44__create_system_parameter.sql:5`。 |
| tenant | tenant_id；created_by | tenant_id UNIQUE；只见状态停用、更新，没有普通删除Mapper。 | 根实体本身无需FK；created_by审计快照；停止级联清库假设，C06。 |
| tenant_configuration_revision | tenant_id | 配置表变更触发器upsert revision；非业务状态。 | 不机械加tenant FK：先清system/global配置与登记顺序。revision用于并发版本，不替代配置关系校验。 |
| tenant_quota_policy | policy_code/fair_share_group | 策略自然键与共享组标识，无独立共享组父表。 | 不对组名FK；租户登记见C06；配置有效性/非负CHECK已有。`db/migration/V3__create_config_tables.sql:31`。 |
| tenant_scheduler_snapshot | policy_code/fair_share_group/detail_json | 调度决策历史快照，策略后续可删除/更新。 | 不FK当前策略/组，保留当时决策值。`db/migration/V16__scheduler_fair_share_snapshot_load.sql:25`。 |
| webhook_delivery_log | subscription_id | 单列FK到subscription；relay按(tenant,id)加载，异常GIVE_UP。 | C03升级复合FK，不级联删审计。 |
| webhook_subscription | callback_url/secret/event_types | 外部地址与密钥/事件筛选配置；软删除。 | 无数据库父引用；保留地址SSRF与配置校验；作为日志父表补租户唯一键。 |

### Quartz 11 表

统一依据：`db/migration/V2__create_quartz_tables_postgres_2_5_2.sql:9` 至 `:150`，实际目录与仓库框架DDL一致。Quartz内部表关系不能直接套用batch业务租户FK；sched_name是调度器命名空间，不是租户外键。

| 表 | 当前引用/约束 | 结论 |
|---|---|---|
| qrtz_job_details | PK(sched_name,job_name,job_group)，job_data为序列化框架数据 | 保留；不FK到batch.job_definition，JobStore任务还包含内部任务。 |
| qrtz_triggers | 已有(sched_name,job_name,job_group)→job_details FK；calendar_name无FK | 保留已有；日历由Quartz API管理，不另加未有框架契约支持的FK。批量业务calendar和Quartz calendar不是同表。 |
| qrtz_simple_triggers | 三列→qrtz_triggers FK | 已足够，保留。 |
| qrtz_cron_triggers | 三列→qrtz_triggers FK | 已足够，timezone值属配置校验。 |
| qrtz_simprop_triggers | 三列→qrtz_triggers FK；prop值类型不代表固定关系 | 保留，不对prop字符串猜测FK。 |
| qrtz_blob_triggers | 三列→qrtz_triggers FK；blob为序列化数据 | 保留。 |
| qrtz_calendars | PK(sched_name,calendar_name) | 根配置，无父引用，不加。 |
| qrtz_fired_triggers | trigger/job/instance名称为已触发执行与恢复记录，无FK | 保留框架恢复语义，不绑定临时scheduler_state或已删除trigger；按Quartz恢复验收。 |
| qrtz_scheduler_state | PK(sched_name,instance_name)，集群心跳 | 无父表，不加FK。 |
| qrtz_paused_trigger_grps | PK(sched_name,trigger_group)，暂停状态可先于该组trigger存在 | 不加FK。 |
| qrtz_locks | PK(sched_name,lock_name)，框架锁名 | 不FK到临时scheduler实例。 |

### 业务库 10 逻辑表

实际目录中 `settlement_detail` 的5条FK目录项包含1个逻辑父FK与4个关联分区内部引用，不能算5种业务关系，也不能删掉“重复FK”。平台库与业务库是独立数据库；不能建立普通跨数据库FK。

| 表 | 引用/业务键 | 当前校验及判定 |
|---|---|---|
| batch.process_staging | batch_key，tenant_id，target_schema/target_table，payload | WAP临时数据；PK包含staged_at分区键，无Job FK。保留跨库关联标识，用tenant+batch_key过滤及合法标识符/目标映射校验；staging与目标表位于同一业务库，发布与对应staging清理保持同事务，跨阶段依靠tenant、batch_key、状态及行数校验收敛；对象/Job已完成后staging独立清理。E10/E11。 |
| biz.customer_account | tenant_id/customer_no；source_file_name/source_batch_no/source_trace_id；currency_code | HASH tenant父表、PK(tenant,id)、UNIQUE(tenant,customer_no)。来源字段是输入快照不是file_record/BatchDay ID；不跨库FK；币种枚举/数据质量校验单列。E10。 |
| biz.settlement_batch | tenant_id/batch_no；source_partitions JSON；snapshot_ts | PK(tenant,id)，UNIQUE(tenant,batch_no)；批次本身是业务父实体。source_partitions为分区/快照说明，不向数据库目录表FK。E10。 |
| biz.settlement_detail | batch_id；customer_no/order_no；source_trace_id | 已有(tenant,batch_id)→settlement_batch复合FK；保留。客户/订单标识外部语义未明确，见C08，不能仅凭名字加FK。E10。 |
| biz.transaction | account_no；txn_no；tenant_id；txn_date | RANGE(txn_date)，PK含tenant/date/id。account_no不等于customer_account.customer_no的证据不足；不擅加FK，C08；验收业务对账。E10。 |
| biz.risk_score | entity_id/entity_type；model_version | 实体多态、模型版本快照；RANGE(score_date)。不向customer/transaction单表FK，需entity_type分类有效性和模型注册校验由业务定义。E10。 |
| biz.risk_alert | entity_id；alert_id；alert_type | 风险事件快照，可能跨客户、交易、外部实体；不假设每条alert均有同日score；不能强加score FK。E10。 |
| biz.process_order_event | account_id/event_id；biz_date | DDL注释明确SQL transform E2E源样例表；无PK/FK。不是customer_account引用，不能把测试输入表当生产主数据。若用于生产需另定义event去重/唯一键，这不是本轮FK改造自动实施项。E10。 |
| biz.process_account_summary | account_id/biz_date；high_water_mark | 聚合输出，PK(tenant,account,date)，允许源事件按保留期清理；不对源event加FK。high_water_mark是进度边界，不是单个父行。E10。 |
| biz.process_event_copy | event_id/account_id；high_water_mark | transform输出示例，PK(tenant,event_id)；复制结果生命周期可独立源表。保留无FK，按写入幂等/摘要对账验证。E10。 |

### 关键代码证据索引

- E01：`batch-console-api/src/main/resources/mapper/ConfigApprovalMapper.xml:43`（裸INSERT）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApprovalApplicationService.java:77`（同tenant load/lock/validate/insert），`:211`（loadRelease），`:218`（业务锁）。
- E02：`batch-console-api/src/main/resources/mapper/SubscriptionRuleMapper.xml:14`（同tenant JOIN）、`:33`（裸写）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleNotificationApplicationService.java:227`（软删入口）、`:248`（createRule）、`:277`（updateRule）；`batch-console-api/src/main/resources/mapper/NotificationChannelMapper.xml:59`（软删除）。
- E03：`batch-console-api/src/main/resources/mapper/ConsoleWebhookDeliveryLogMapper.xml:40`（裸INSERT）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/WebhookDeliveryRelay.java:264`（同tenant查订阅，否则GIVE_UP）。
- E04：`batch-console-api/src/main/java/io/github/pinpols/batch/console/service/ConsoleBusinessTenantPlacementService.java:39`、`:62`（配置后备/空拓扑）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/service/ConsoleBusinessShardCatalogService.java:39`（无引用检查删除）；`db/migration/V171__business_shard_catalog.sql:7`（外部secret_ref）；`batch-common/src/main/java/io/github/pinpols/batch/common/tenant/routing/BusinessRoutingDataSourceFactory.java:36`（运行路由按已经构建的shards连接池）。
- E05：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleResourceTagService.java:44`、`:76`（只校验资源type）。
- E06：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleTenantGuard.java:42`（作用域，不查tenant表）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleUserAccountService.java:254`（system例外）、`:265`（真实ACTIVE业务租户）；`batch-console-api/src/main/resources/mapper/TenantMapper.xml:52`（创建与状态更新，未提供普通删除）。
- E07：`batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleRequestContextFilter.java:131`（operatorId=username）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/web/ConsoleAiController.java:123`（作为附件owner）、`:299`（作为会话owner）；`batch-console-api/src/main/resources/mapper/ConsoleUserAccountMapper.xml:134`（删除SQL，未找到普通业务调用）、`ConsoleAiConversationMapper.xml:150`（只以tenant+owner名称判归属）。
- E08：`batch-console-api/src/main/resources/mapper/ConsoleAiAttachmentMapper.xml:86`（绑定owner）、`:101`（删前对象清理队列）；`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiConversationService.java:87`（锁会话）、`:100`（owner比较）、`:127`（绑定附件）；`db/migration/V219__console_ai_image_attachments.sql:46`（独立清理队列）。
- E09：`batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java:86`、`:109`、`:151`、`:187`（事务/row lock/advisory lock协调binding+history）；`db/migration/V193__stateful_backend_binding.sql:31`（切换token唯一）。
- E10：`scripts/db/business/create_biz_tables.sql:63`（business staging）、`:102`（customer）、`:139`（batch）、`:174`（detail复合FK）、`:224`（transaction）、`:253`（score）、`:282`（alert）、`:306`（明确process样例）、`:308`、`:316`、`:328`（process三表）。
- E11：`batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java:186`（动态标识符校验）；`batch-worker/process/src/main/resources/mapper/business/ProcessStagingMapper.xml:11`（时间保留清理）、`:39`（业务库分区）。标识符合法性不等于目标表/列业务授权，需要与运行期mapper/配置审批链共同验收。

## 4. 本轮核查结果与下一步

- **结构取证完成**：176张逻辑表和107个物理子表已入清单；现有51条逻辑FK与全部列、唯一键、索引、用户触发器及分区边界已核对。正文176个逐表条目与目录一一对应，目录覆盖没有漏表；不意味着每种业务语义或每条并发路径已经动态验收。
- **代码检查完成**：明确列出该补FK/业务归属校验、应保留弱关联、已有替代及局限；审查中的具体缺陷、数据库防御缺口与需业务决定的生命周期分别标识。
- **本轮未执行**：DDL/DML、业务数据孤儿扫描、迁移/负例测试、并发复现、归档/E2E、CI和目标环境验收。源码中引用已有测试或运行记录，只作为后续选择验证范围的线索，不是本轮通过证据。
- **补充查询受阻**：完整目录已取证后，额外的在线外键子索引前缀查询返回 `No such container`；不报告该在线检查通过。现有索引定义保存在CSV，具体新增/调整索引仍需结合真实查询计划、规模和锁等待验收。
- **下一步**：按REF-01至REF-08先完成可复核实现切片，再按§2.1保留首红、修复及复验记录。R20/R33/R36与总方案验收统计维持原状态，本报告不直接关闭任何R项。

## 初始结构审查检查结果（后续实施见§2.4–2.24）

文档结构 `python3 scripts/ci/check-docs-structure.py`、日期策略 `python3 scripts/ci/check-doc-timestamp-policy.py` 和本轮文档范围 `git diff --check` 均通过。176张逻辑表逐表覆盖、283行结构CSV与关联文档链接已核对；这些结果不替代代码整改、跨租户/并发/归档负例及目标环境验收。

### 2.26 RT-5 Config Sync 应用结果与日志计数对齐（2026-10-18）

- **发现**：Config Sync 将包内配置对象数写入 `total_items`，但把 `success_items/failed_items` 写成成功/失败租户数；非 strict 批量初始化即使某个 Job 依赖因同租户父引用检查失败，租户整体仍可成功，因此日志会漏报失败配置项。多目标租户下总数与结果计数单位也不一致。
- **修复**：日志统一按“配置项 × 目标租户”统计。`total_items` 和异常失败日志按目标租户数扩展；正常响应按各类型 `ItemStats` 汇总 created/updated/migrated、failed、skipped；整租户失败及响应缺少的目标租户按该租户全部包内配置项计失败。任意配置项或租户失败时保留 `PARTIAL_FAILED`，配置包响应结构不变。
- **测试**：先红用例复现双目标租户总数仍记为包级9项（期望18、实际9）；修复后 `DefaultConsoleConfigSyncBundleMappingTest` 全类4/4通过，其中覆盖单项依赖失败、两个租户的对象计数、整租户失败。命令：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=DefaultConsoleConfigSyncBundleMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，BUILD SUCCESS。新增真实入口 PostgreSQL 用例 `TenantConfigBatchInitStrictAtomicityIntegrationTest#shouldPersistPartialFailure_whenConfigSyncJobDependencyIsMissing` 通过：1/1、0 failures/errors/skips，PostgreSQL 17.11 空库校验并应用308个迁移至V313，持久化断言 `total=1/success=0/failed=1/skipped=0/status=PARTIAL_FAILED`；单方法命令 BUILD SUCCESS。
- **该轮边界**：本切片验证单目标缺失上游的真实入口链及日志计数；后续双目标成功计数、普通角色与隔离事务局部证据见 §2.28，多目标失败日志及其它类型/并发/运维旁路仍开放。该单方法测试完整启动容器，PostgreSQL 启动约17分钟；测试退出后 Testcontainers 容器清单为空。未运行全量测试、SIM、strict 启动审计、CI 或部署验收；格式检查依用户要求暂缓。

### 2.27 RT-5 批量配置保留多上游规则与逐边业务日对齐（2026-10-19）

- **发现**：`job_dependency` 已支持一个下游多个上游及各边 `biz_date_alignment`，普通 Job API 和 Excel 包携带完整规则；租户批量初始化与 Config Sync bundle 原先只暴露 `dependsOnJobCode`，导出会丢失其余上游和对齐语义，导入也不能执行数据库已支持的合法关系。该缺口会令配置复制后 DAG 与运行业务日语义变化。
- **实现**：批量初始化请求新增最多32条 `dependencyRules`，校验编码、对齐枚举、重复上游和规范化；与单上游投影不一致时拒绝。拓扑排序考虑包内所有上游；应用校验在写入前获取 V301 同租户图锁，核对每个上游同租户存在且更新后无环，再将完整规则写入 Mapper；旧单上游字段仅作为单边 `SAME_DAY` 输入/首边投影。Config Sync 导出完整边及对齐值，OpenAPI 和前端生成类型同步。
- **定向验证**：后端命令 `./mvnw -o -ntp -pl batch-console-api -am '-Dtest=DefaultConsoleTenantConfigInitApplicationServiceTest#batchInit_persistsMultipleJobDependencyRules,DefaultConsoleTenantConfigCopyServiceTest#shouldBuildBundleWithExplicitReferences_whenJobBundleIsAssembled' '-Dsurefire.failIfNoSpecifiedTests=false' test`，2/2通过；新增/扩展的真实 PostgreSQL 用例 `TenantConfigBatchInitStrictAtomicityIntegrationTest#shouldApplyJobDependenciesBeforeDownstream_whenBundleListsDownstreamFirst` 以 strict 包按“下游、第二上游、第一上游”顺序提交，验证两个精确边和 `PREVIOUS_BIZ_DAY`/`SAME_DAY` 分别保存；单方法1/1通过，PostgreSQL 17.11 空库应用308个迁移至V313。配对前端在 `batch-console` 执行 `BE_OPENAPI_PATH=${BACKEND_WORKTREE}/docs/api/console-api.openapi.yaml npm run gen:api`、同一环境变量下 `npm run gen:api:check` 与 `npm run typecheck` 均通过。Testcontainers容器在测试完成后清空；测试结束瞬间观察到的容器于后续核查时已清理。
- **该轮边界**：该轮仅验证多边持久化与依赖拓扑；后续混合业务日持久等待、普通角色和双目标计数的局部动态证据见 §2.28。两轮都不覆盖所有入口并发、实际 Worker 消费、跨进程故障和阶段重试，不能关闭 RT-5、REF-05 或 DBSAFE-3；未运行全量 SIM、strict启动审计、CI、部署验收或格式检查。

### 2.28 RT-5 混合业务日恢复与普通角色配置事务（2026-10-19）

- **运行核对**：真实 PostgreSQL `TriggerTypeLaunchIntegrationTest#shouldRestoreMixedDateDependenciesFromFrozenSnapshot` 按 EVENT、SCHEDULED、CATCH_UP、RERUN 四种根触发类型输入运行，4/4通过。下游周一同时依赖周六 `WORKDAY_OVERRIDE` 的前一业务日结果和同日结果；等待后更改日历并删除实时依赖配置，只有一条上游生效时仍不得建下游实例。两条均生效后重扫两次，只创建一个原业务日实例；等待快照不变，`params_snapshot.dependencyResultSnapshot` 精确记录两条规则、对齐日期、business key、源实例和实际结果版本。该测试没有制造真实 Quartz callback、进程重启、失败重跑或 Worker 数据消费。
- **运行侧普通角色核对**：扩展既有 NOSUPERUSER/NOBYPASSRLS 用例，验证无继承数据库租户上下文时，同租户结果能满足冻结快照，异租户结果不能满足。最初怀疑 readiness 查询漏上下文，但当前结果版本/分区读表并未启用该严格 RLS，SQL 已显式限定 tenant；真实角色用例通过，因此没有为该假设修改运行实现，也不据此声称全表 RLS 已验证。
- **配置侧首红**：新增 `TenantConfigDependencyRlsIntegrationTest` 使用普通角色，并在每个新事务（包括 REQUIRES_NEW）入口重新设置该角色及空的数据库租户上下文。源导出实际把两条规则降成首条 `SAME_DAY`；Config Sync 双目标导入的子项被 `job_dependency` RLS 拒绝，租户级成功响应也不能证明三项都成功。另以只读源事务执行严格复制，复现目标更新继承只读、一个租户失败后另一个租户无法建保存点及外层 `UnexpectedRollbackException`。首红分别保留于 `logs/verification/workflow/config-dependency-role-red-20261019.log`（2失败）和 `config-dependency-transaction-red-20261019.log`（1错误）。
- **修复**：配置读取公共入口建立只读事务，每次读取显式绑定源租户数据库上下文；初始化入口在目标 ThreadLocal 上下文中调用租户执行器，执行器使用 REQUIRES_NEW 并在首条配置查询前绑定目标数据库租户。目标事务提交/回滚后恢复源上下文，源只读属性和一个目标的 rollback-only 不再传到另一个目标。继续保留每项 NESTED/savepoint 与 strict 租户级原子回滚，未取消 RLS、FK 或图环约束。
- **针对失败的复验**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，3/3通过，日志 `logs/verification/workflow/config-dependency-role-green-20261019.log`。普通角色导出精确保留两条规则；双目标各保存三项并记录 `total=6/success=6/failed=0/skipped=0/SUCCESS`；只读源事务下一个目标成功、另一个因现存反向依赖失败，成功目标保留两条新边，失败目标原图保持不变。并验证目标 ThreadLocal/GUC 不泄漏回调用方。
- **额外负例及原失败记录**：多依赖缺失第二上游的 strict/non-strict PostgreSQL 参数化用例2/2通过：strict 回滚整个租户包；non-strict 保留合法兄弟项并返回稳定错误；失败 Job/部分依赖边均为零。先前运行/配置四类受影响回归整体退出1，其中运行侧98/98、配置侧26/28通过；两个旧成功夹具未提供合法同租户上游，补齐后只重跑这两项2/2通过。保留原失败日志，不将该整条命令改写为全绿。
- **最终配置回归**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,DefaultConsoleConfigSyncBundleMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，5类45/45、0 failures/errors/skips、BUILD SUCCESS；日志 `logs/verification/workflow/config-dependency-final-regression-20261019.log`。其中真实PG原子性类10项、普通角色类3项，单测32项；连同未受后续Console改动影响的运行侧98项，本轮两个模块共143个不同用例最终观察通过，未执行整仓全量测试。
- **静态与清理**：`python3 scripts/ci/check-console-openapi-paths.py` 通过（412条Console路由），文档结构、日期策略及前后端工作区 `git diff --check` 通过。最终命令退出后核查 `docker ps -a --filter label=org.testcontainers=true`，容器清单为空；没有宽泛删除共享服务或业务数据。
- **继续开放**：其它配置类型与角色权限的完整负例/清理矩阵、跨入口并发和源配置并发读取一致性、目标提交后日志故障、实际 Worker 读取 pinning、跨进程重启/故障及阶段重试、SIM、strict启动审计、容量和目标部署。格式检查仍按用户要求暂缓。本切片不关闭 RT-5/REF-05/DBSAFE-3 或增加 R 项完成数。

### 2.29 配置冷启动顺序与租户级后置失败原子性（2026-10-19）

- **发现与首红**：在 §2.28 独立目标事务上继续核对空租户。`TenantConfigDependencyRlsIntegrationTest#shouldRollbackTenantWhenManagedWorkflowProvisioningFails` 在目标缺少日历、受管 Workflow 后置创建失败时，响应将该租户标为失败，但 strict 与 non-strict 两种模式都实际提交了3个没有受管 Workflow 的 Job。原因是执行器捕获整租户阶段异常后正常返回，事务未标回滚。另一个用例在同包提供日历和3个合法 Job 时也失败：原顺序先处理 Job/Workflow、最后处理日历，冷启动导入依赖被颠倒。三项先红，日志 `logs/verification/workflow/config-tenant-fatal-and-calendar-red-20261019.log`。
- **修复**：在处理 Job 和 Workflow 前应用包内日历。NESTED/savepoint 继续处理可恢复的单项失败；执行器捕获租户级阶段异常并返回失败前，显式将当前 REQUIRES_NEW 目标事务标记 rollback-only，使结果与实际持久化一致，不影响已提交的其它目标。未添加虚拟默认日历、未放宽引用校验，也未改旧库迁移范围。
- **针对失败的复验**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest#shouldRollbackTenantWhenManagedWorkflowProvisioningFails+shouldApplyBundledCalendarBeforeJobsAndManagedWorkflows' '-Dsurefire.failIfNoSpecifiedTests=false' test`，3/3、0 failures/errors/skips、BUILD SUCCESS；日志 `logs/verification/workflow/config-tenant-fatal-and-calendar-green-20261019.log`。普通角色的每个新事务均设置角色；分别断言失败目标 Job/依赖/Workflow 零残留、成功目标3个 Job/Workflow 保留；冷目标同包建立日历与3个受管 Workflow，2条依赖及对齐值保留。
- **受影响回归**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,DefaultConsoleConfigSyncBundleMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，5类48/48、0 failures/errors/skips、BUILD SUCCESS；日志 `logs/verification/workflow/config-tenant-final-regression-20261019.log`。其中真实PG原子性10项、普通角色6项、单测32项。既有非严格单项失败保留合法兄弟项的场景继续通过；此处全租户回滚只对应返回租户失败的阶段异常，不扩大到所有单项错误。
- **混合目标日志复核**：新增 `TenantConfigDependencyRlsIntegrationTest#shouldRecordPartialSyncWhenOneTenantPostprocessingFails` 单跑1/1通过，命令仅指定该方法，日志 `logs/verification/workflow/config-sync-mixed-tenant-rollback-counts-20261019.log`。真实普通角色Config Sync入口证明成功目标保留3项、失败目标Job/依赖/Workflow为零，持久日志为 `total=6/success=3/failed=3/skipped=0/PARTIAL_FAILED`。这是新增方法的独立复验，不把前述48项命令改记为49项；其它失败形态仍未全覆盖。
- **收尾**：文档结构、日期策略与 `git diff --check` 通过；测试结束后Testcontainers容器与本轮SDK E2E子JVM清单均为空。未执行全量SIM、启动脚本WARN严格审计或格式检查。受控SDK的实际消费/进程接管及终态发布单条E2E证据另见验证矩阵A4当日条目，不将其当作Job依赖观察快照的通用消费保证。
- **归属与边界**：承接 REF-03/DBSAFE-3、受管初始化入口及 I1/I5/R18/R30 的新库导入要求。只证明所列日历前置及租户级后置异常，不代表九类配置全部引用拓扑、所有清理/角色/并发、提交后日志故障或前端实际导入已闭环。相关 R/REF 条目继续开放。

### 2.30 RT-5 上游删除竞态与应用失败结果对齐（2026-10-19）

- **真实竞态首红**：普通应用角色初始化下游，在应用引用预检完成后以精确 tenant/job 的临时数据库触发器及 advisory barrier 暂停插入；独立事务删除上游并提交，随后恢复下游插入。V298 外键正确拒绝并回滚下游及依赖边；strict 模式回滚合法兄弟项，non-strict 模式保留兄弟项，但后者单项结果透出完整 SQL、`fk_job_dependency_upstream` 及本机 Mapper 路径。用例 `TenantConfigDependencyRlsIntegrationTest#shouldRejectConcurrentUpstreamDeletionWithoutLeakingConstraintDetails` 两种模式共2项、1失败，原件 `logs/verification/workflow/config-upstream-delete-race-red-20261019.log`。失败在响应契约，不能误写为数据库未拦截或外键缺失。
- **结构化错误映射**：按 PostgreSQL SQLSTATE、Schema、表和约束名共同识别两种已知依赖拒绝：`23503/batch/job_dependency/fk_job_dependency_upstream` 与 `23514/batch/job_dependency/ck_job_dependency_acyclic`，批量结果返回已有 `error.common.invalid_argument`。其它数据库失败（含异常包装）返回已有 `error.common.system_error`，原始诊断保留服务端日志。应用单项、租户级阶段与外层事务失败使用同一映射；非数据库业务校验保留原错误。共享提取器通过反射读取驱动字段，不改变 PostgreSQL runtime 依赖范围，也不添加迁移。
- **普通 HTTP 约束语义**：Web 处理器优先按 SQLSTATE 分类；仅在字段缺失时对普通约束保留文本兜底，业务依赖环必须精确匹配结构化身份。删除仍被引用的上游继续为 `409/CONFLICT`，不能将所有相同外键错误统一改为参数错误。其它表或 Schema 的同名约束不误报 Job 依赖环。
- **包装失败首红及定向复验**：新增错误契约单测首跑30项、1失败，确认外层业务包装仍能透出未知数据库异常中的SQL/路径，原件 `logs/verification/workflow/config-constraint-error-contract-unit-red-20261019.log`；修复为沿 cause 链识别数据库失败。仅重跑该失败方法及前述竞态参数用例，3/3、0 failures/errors/skips、BUILD SUCCESS，命令 `./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigFailureMessagesTest#shouldHideWrappedUnknownDatabaseFailure,TenantConfigDependencyRlsIntegrationTest#shouldRejectConcurrentUpstreamDeletionWithoutLeakingConstraintDetails' '-Dsurefire.failIfNoSpecifiedTests=false' test`，日志 `logs/verification/workflow/config-upstream-delete-race-fixed-20261019.log`。真实PG 17.11新库应用308个迁移至V313；原始删除已提交保留，下游/依赖零残留，strict/non-strict 兄弟项事务边界符合预期。
- **受影响回归**：7类81/81、0 failures/errors/skips、BUILD SUCCESS；真实PG19项（普通角色9、严格原子性10），单测62项（配置服务32、失败映射10、Web处理20）。命令 `./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigFailureMessagesTest,ConsoleApiExceptionHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；日志 `logs/verification/workflow/config-constraint-error-contract-regression-20261019.log`。该命令包含编译；未重跑未改动的运行侧98项、SDK E2E或全量SIM，不把它们算入81项。
- **边界**：本切片收口所列导入竞态与失败映射，不代表全部九类配置、源并发快照、所有直接SQL/运维写入口、提交后日志故障或热冷生命周期已验收。RT-5/REF-05/DBSAFE-3 及相关 I/R 状态继续开放；全量SIM、strict启动审计、容量和目标部署仍按计划推进，格式检查暂缓。

### 2.31 RT-5 源配置多次查询的一致快照（2026-10-19）

- **发现与首红**：`buildBundle` 原有只读事务默认使用 `READ COMMITTED`，Job与Workflow/节点等分多次查询，无法保证包内数据来自同一时刻；仅设置readOnly也不会提升已加入调用方事务的隔离级别。真实PG用例在Job查询返回后以测试侧MyBatis拦截器插入线程屏障，独立事务同时修改Job和其受管Workflow，再继续Workflow查询；导出保留旧Job却读到新Workflow。读写/只读两种外层READ COMMITTED调用都失败，2项首红，日志 `logs/verification/workflow/config-source-snapshot-red-20261019.log`。屏障不替换SQL或查询结果，也不向生产代码添加测试钩子。
- **实现**：配置复制服务的读边界使用独立只读 `REQUIRES_NEW + REPEATABLE_READ` 事务，避免继承外层较低隔离级别；单次完整包、单Job包、复制、预览和矩阵内部调用共享当前源快照。每个目标仍在自身租户绑定的REQUIRES_NEW事务中写入，源快照不参与目标提交或回滚。接口文档同步去除已退役Pipeline及“10种类型”的陈旧描述，当前9种类型不变，无公开字段或数据库结构变化。
- **定向复验**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest#shouldExportOneSourceSnapshotAcrossConcurrentEdits' '-Dsurefire.failIfNoSpecifiedTests=false' test`，2/2、零失败/错误/跳过、BUILD SUCCESS；日志 `logs/verification/workflow/config-source-snapshot-fixed-20261019.log`。普通应用角色读取保留完整依赖及修改前Job/Workflow，独立写事务确实已提交新值；PostgreSQL 17.11新库应用308个迁移至V313。
- **受影响回归**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest,DefaultConsoleTenantConfigCopyServiceTest,DefaultConsoleConfigSyncBundleMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，3类18/18、零失败/错误/跳过、BUILD SUCCESS，日志 `logs/verification/workflow/config-source-snapshot-regression-20261019.log`。其中普通角色真实PG11项、服务单测7项，继续覆盖只读源调用下的目标独立写入、成功目标保留及失败目标回滚、配置日志与既有引用删除竞态。此轮与 §2.30 的81项存在重叠，不相加或改称一次99项验证。
- **边界**：该快照约束同一数据库内、单次服务调用的源配置读取，不等于跨请求的Preview→Apply版本锁定，也不是跨环境或外部资源快照。九类配置全入口/权限、全部节点与边的并发变更矩阵、提交后日志故障及源读/目标写同时占用连接时的容量仍按既定项验收；RT-5/REF-05/DBSAFE-3及I/R状态继续开放。

### 2.32 Config Sync 审计提交与目标结果隔离（2026-10-19）

- **真实缺陷**：导入原先在外层事务插入初始审计，各目标已在自身REQUIRES_NEW提交；最终日志更新失败时，补偿事务看不到外层尚未提交的初始行，更新零行也未报错；随后外层回滚，把初始审计一并删除，留下已写入目标而无同步记录。普通角色的真实PG注入瞬态40001和持续23514两种故障，均复现6项配置已提交而日志0行；首红原件及夹具修正见验证矩阵，不将未捕获的数据库异常或初始记录存在当作验收通过。
- **实现**：协调入口使用NOT_SUPPORTED，初始审计及最终统计各自在绑定审计所属租户的REQUIRES_NEW中提交，目标事务保持原有隔离。初始行必须确实插入且返回ID，随后才能开始业务；所有审计更新必须影响恰好1行。初始摘要保留批次关联ID、summary与dryRun。拿到完整目标结果后，瞬态数据库失败最多尝试两次相同审计更新，绝不重新调用导入；非瞬态或重试耗尽返回SYSTEM_ERROR，并保留未收口初始行，不伪造全失败或全成功。
- **结果未知**：初始化意外抛出而未返回完整结果时，不能推断已提交目标为0；保留RUNNING及未确认计数，将规范错误键与 `outcomeKnown=false` 合并进原详情，保留关联身份和摘要。补充错误写入也失败时保留原异常cause及suppressed诊断。原始数据库细节留在服务端，API返回系统错误；未新增数据库状态或公开字段。
- **前端承接**：配对同名分支的Config Sync日志页对RUNNING显示“结果尚未确认”，保留计划总数、隐藏未确认的成功/失败/跳过统计；终态继续展示实际计数。OpenAPI与生成类型无变化。前端组件3项、typecheck及i18n通过；这属于Mock组件/静态证据，不是浏览器连真实后端验收。
- **定向复验与回归**：修复后原故障及失败契约/HTTP系统错误共6项通过。随后 `./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,DefaultConsoleConfigSyncBundleMappingTest,DefaultConsoleConfigSyncApplicationServicePaginationTest,ConsoleConfigSyncControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，5类37/37、失败/错误/跳过均0、BUILD SUCCESS；其中真实PG24项、服务/MockMvc13项，日志 `logs/verification/workflow/config-sync-audit-regression-20261019.log`。覆盖普通角色的独立审计、目标保留、瞬态只重试日志、持续故障的持久未收口记录、元数据JSON合并、零行拒绝、系统错误映射及已有关联/原子性回归。 随后仅对未知结果详情作统一系统错误键收口，并增加非数据库内部异常场景；只跑该服务类8/8通过，日志 `logs/verification/workflow/config-sync-unresolved-errors-regression-20261019.log`。该8项与37项重叠，不累计。
- **继续开放**：进程在目标提交后、返回结果或审计完成前崩溃时的精确收据/自动对账、永久数据库故障后的审计恢复、重复HTTP导入的完整幂等边界、所有九类配置/角色与清理并发仍需完整验收。RUNNING是未确认状态，不是自动恢复已实现的证明。RT-5/REF-05/DBSAFE-3及I/R整体不关闭；SIM、strict审计、容量、部署和格式检查按原计划推进。

### 2.33 REF-07 多态 resource_tag 应用与数据库身份守护（2026-10-19）

- **实现**：`ConsoleResourceTagService.upsert` 先按 `tenant_id/resource_type/resource_code` 获取事务级 advisory lock，再查询目标存在性；缺失或跨租户资源返回稳定 `INVALID_ARGUMENT`，不会插入标签。Mapper 仅在已验证的四种类型间选择父表/自然键查询，调用者输入不会成为表名或列名。
- **数据库兜底与并发**：V314 使用同一身份锁；resource_tag 的直接 INSERT/引用键 UPDATE 会按类型核对四个父表，不存在时以 SQLSTATE `23503` 和 `fk_resource_tag_target` 拒绝。父资源新增、自然键改动及删除也取同一锁；父行物理删除或自然键改动后，只在该租户/类型/代码已无任何父版本时清理标签。WORKFLOW 多版本共享逻辑 code，因此删单一版本不清除仍有其它版本的标签。这里没有单表外键，应用与数据库触发器共同实现多态引用存在性与并发生命周期保护。
- **真实验证**：`./mvnw -o -ntp -pl batch-console-api -am "-Dtest=ConsoleResourceTagServiceTest,TenantConfigDependencyRlsIntegrationTest#shouldGuardPolymorphicResourceTagReferences+shouldSerializeResourceTagAttachmentWithParentDelete" "-Dsurefire.failIfNoSpecifiedTests=false" test`，13/13、fail/error/skip均0、BUILD SUCCESS。普通 NOSUPERUSER/NOBYPASSRLS 应用角色下验证四类目标的合法写入、异租户拒绝、直接 SQL 悬空引用拒绝及父删除清理；独立事务持有同一身份锁删除父资源时，应用写入等待后因父已删除而被拒绝。PostgreSQL 17.11 空库执行309个迁移至V314。日志：`logs/verification/workflow/resource-tag-polymorphic-regression-20261019.log`。
- **边界**：此处验证仅覆盖“目标不存在/异租户”和直接父删除并发切片；自然键改名策略与附加竞态见后续 §2.34。未验证 Workflow 多版本并发增删、所有种子/运维清理路径及高并发锁成本；不把业务路由用的 `resource_queue.resource_tag` 当成此多态引用。REF-07、REF-08、DBSAFE-3 和整体验收仍开放。

### 2.34 REF-07 父资源自然键改名生命周期回归（2026-10-19）

- **契约确认**：资源自然键改名不迁移标签到新代码；当旧 `(tenant_id, resource_type, resource_code)` 仍有父资源时保留标签，当最后一个父身份离开旧代码后清理标签。对多版本 `WORKFLOW`，按逻辑 workflow code 判断是否还有父版本，单个版本改名不得提前清除标签。
- **状态语义**：资源标签是持久资源身份的管理元数据，不代表执行可用性。`enabled=false` 的父资源仍可附加标签；`file_template_config.is_deleted=true` 保留底层身份，标签不清理且可继续管理，恢复软删除时标签仍在。其它三类父表未定义软删除列。应用查询与 V314 触发器都只将同租户类型/代码是否存在作为关系准入条件，不把 enabled 或 file-template 的可见性谓词冒充 FK 身份条件。
- **验证**：生命周期基础回归 `resource-tag-polymorphic-state-regression-20261019.log` 为4/4；六个定向用例的最终命令 `./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest#shouldGuardPolymorphicResourceTagReferences+shouldRemoveResourceTagWhenParentIdentityIsRenamed+shouldSerializeResourceTagAttachmentWithParentDelete+shouldSerializeResourceTagAttachmentWithParentRename+shouldPreserveWorkflowTagWhenVersionIsAddedBeforeConcurrentDelete+shouldNotCarryWorkflowTagWhenConcurrentVersionInsertFollowsLastDelete' '-Dsurefire.failIfNoSpecifiedTests=false' test` 为6/6、fail/error/skip均0、BUILD SUCCESS，日志 `logs/verification/workflow/resource-tag-polymorphic-reverse-concurrency-regression-20261019.log`。PostgreSQL 17.11空库执行309个迁移至V314；普通 NOSUPERUSER/NOBYPASSRLS 角色验证四类父资源改名、disabled/软删元数据语义、父删除/改名与附加并发，以及两种版本增删提交顺序：新增版本先取得锁时，旧版本删除等待并保留标签；最后旧版本删除先取得锁并提交时，新版本插入等待，旧标签先清理且新版本不继承标签。
- **写入入口盘点**：全仓 SQL/Java/XML/shell 搜索确认生产标签写入集中在 `ConsoleResourceTagService` → `ConsoleResourceTagMapper.xml`；两个 tenant wipe 脚本只做租户范围内的表清理，没有配置包/种子直写。该静态盘点不替代部署环境实际运维执行验证。
- **边界**：确认的是父自然键 UPDATE 的清理策略、disabled/软删元数据语义、两个 Workflow 版本增删提交顺序及单父身份附加/改名串行化；尚未覆盖多个版本同时新增/删除的完整交错和锁容量。两条 tenant wipe 脚本的局部动态证据见§2.35，不等价于所有运维清理入口或目标部署验收；REF-07/08、DBSAFE-3 和整体验收仍开放。

### 2.35 REF-08 tenant wipe 外键顺序动态验收（2026-10-19）

- **测试实现**：专用 `TenantWipeScriptsIntegrityIntegrationTest` 创建仅供该类使用的 Testcontainers PostgreSQL 17.11 平台库，通过真实 `psql -v ON_ERROR_STOP=1` 执行仓库中的 `scripts/db/wipe-tenants.sql` 与 `scripts/db/wipe-non-system-tenants.sql`；空库运行完整 309 个 Flyway 迁移至 V314。清理脚本与其它集成测试共用容器/数据库的风险已规避；也没有把测试结论外推到真实部署库。
- **有父子行的 fixture**：被清理租户包含 Job/Workflow 定义与 `resource_tag`，Workflow 目标及日历，引用 TriggerRequest 的 Job dependency wait、Workflow launch wait、batch-day wait，catch-up control/event，以及 PipelineInstance/Progress；验证租户 wipe 删除这些目标行及租户，显式 wipe 保留未选中租户，non-system wipe 保留 `ta` 白名单租户、定义与标签。
- **命令与结果**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantWipeScriptsIntegrityIntegrationTest#shouldExecuteExplicitTenantWipeScriptWithReferenceOrder+shouldExecuteNonSystemTenantWipeScriptAndPreserveAllowlist' '-Dsurefire.failIfNoSpecifiedTests=false' test`，最终运行 2/2、fail/error/skip 均 0、BUILD SUCCESS；日志 `logs/verification/workflow/tenant-wipe-script-integrity-regression-20261019.log`。独立 Flyway 测试配置遵循仓库平台迁移设置，关闭 PostgreSQL transactional migration lock，避免与 `CREATE INDEX CONCURRENTLY` 形成自阻塞。
- **V320 后关系补验（2026-10-19）**：将夹具扩展为实际 partitionless `job_instance→job_task` 行；新库完整迁移至 V320 后通过真实 psql 分别执行两条 wipe，证明显式清理删除 Task 后再删 Instance，白名单清理保留对应 Task 与父实例。首次扩展因静态容器被两个测试方法复用、保留租户重复插入共享 catch-up 主键而失败；收窄保留租户 fixture 为本场唯一 JobInstance/Task 后复跑通过，未改生产脚本。最终命令、失败日志与证据见[整合验证矩阵 V320 wipe 回归](workflow-daily-integration-execution-log-2026-10-14.md#2026-10-19-v320-后-jobtask-租户-wipe-顺序回归)。
- **验收边界**：这是空库迁移后的局部数据集及两条 psql 清理脚本动态证据，不覆盖每张租户表、每种运行树/归档/分区组合、并发写方、实际开发或生产目标环境、锁时长和备份恢复。其它保留期/fixture/seed/部署脚本仍按对应矩阵验收；不关闭 REF-08、DBSAFE-3 或整体 SIM/容量/部署验收。

### 2.36 REF-07 多 Workflow 版本并发删除与标签生命周期（2026-10-19）

- **验证**：新增 `TenantConfigDependencyRlsIntegrationTest#shouldKeepWorkflowTagUntilLastOfMultipleConcurrentVersionsIsDeleted`。在真实 PostgreSQL 17.11、普通 NOSUPERUSER/NOBYPASSRLS 角色下，同时删除同一逻辑 Workflow 的版本1和版本2；断言版本3仍存在时标签保留，再删除最后版本3后标签清理。
- **最终定向回归**：将该用例与六个既有 `resource_tag` 用例合并运行，命令 `./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest#shouldGuardPolymorphicResourceTagReferences+shouldRemoveResourceTagWhenParentIdentityIsRenamed+shouldSerializeResourceTagAttachmentWithParentDelete+shouldSerializeResourceTagAttachmentWithParentRename+shouldPreserveWorkflowTagWhenVersionIsAddedBeforeConcurrentDelete+shouldNotCarryWorkflowTagWhenConcurrentVersionInsertFollowsLastDelete+shouldKeepWorkflowTagUntilLastOfMultipleConcurrentVersionsIsDeleted' '-Dsurefire.failIfNoSpecifiedTests=false' test`，7/7、fail/error/skip均0、BUILD SUCCESS；日志 `logs/verification/workflow/resource-tag-multi-version-delete-regression-20261019.log`。空库迁移至 V314，编译380个后端测试源码。
- **覆盖边界**：该屏障回归补足同一 code 下多个版本并发删除、非最后版本标签保留及最后版本清理；不覆盖任意版本数和所有 INSERT/UPDATE/DELETE 排列、哈希锁冲突概率和锁容量。REF-07/08、DBSAFE-3及整体验收仍开放。

### 2.37 REF-07 多 Workflow 版本并发增删交错（2026-10-19）

- **实施与验收**：新增 `TenantConfigDependencyRlsIntegrationTest#shouldKeepWorkflowTagAcrossConcurrentVersionAddsAndDeletes`。普通 `NOSUPERUSER/NOBYPASSRLS` 角色在屏障后并发删除版本1/2并插入版本4/5；确认版本3/4/5均存在且逻辑标签仍存在。随后并发删除剩余三个版本，确认父版本归零后标签才清理。所有写操作通过独立应用角色事务执行，覆盖新增与删除竞争及多个最后版本删除的交错。
- **定向命令与结果**：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=TenantConfigDependencyRlsIntegrationTest#shouldKeepWorkflowTagAcrossConcurrentVersionAddsAndDeletes' '-Dsurefire.failIfNoSpecifiedTests=false' test`，1/1、failures/errors/skips均0、BUILD SUCCESS；Surefire报告 `batch-console-api/target/surefire-reports/io.github.pinpols.batch.console.integration.TenantConfigDependencyRlsIntegrationTest.txt`。空 PostgreSQL 17.11 库执行309个迁移至V314。Testcontainers容器在JVM退出后已清理；共享开发容器仍在运行。
- **边界**：这是受控屏障交错，不证明所有版本数、所有事务提交排列、哈希碰撞与锁容量；没有执行SIM、完整整合回归或部署验收。REF-07/08、DBSAFE-3及整体验收仍开放。

### 2.38 REF-05 通道健康状态同租户引用（2026-10-19）

- **实施**：V315 为 `file_channel_health(tenant_id,channel_code)` 增加到 `file_channel_config(tenant_id,channel_code)` 的复合外键，采用 `NOT VALID` 后显式 `VALIDATE`；父删除语义为 `ON DELETE CASCADE`。通道软删除只设置 `is_deleted`，不删除配置身份，因此保留健康快照；物理删除配置时清理派生运行状态。应用通过已解析的通道配置调用健康服务，外键兜底 tenant/channel 身份；直接SQL、错误租户和不存在通道也无法写孤儿健康行。
- **副作用语义**：检查到外部 adapter 已返回结果后，健康快照属于辅助状态。若配置硬删除与健康写并发而触发 FK 拒绝，网关记录带租户/通道上下文的告警，仍返回 adapter 的实际结果并按该结果计熔断与投递指标，避免已成功发送被误报为失败并触发重复投递；adapter 本身抛错仍按原规则释放/失败熔断许可并向上抛出。
- **定向验证**：`./mvnw -o -ntp -pl batch-worker/dispatch -am '-Dtest=DispatchChannelGatewayTest,DispatchChannelHealthServiceIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，24/24、failures/errors/skips均0、BUILD SUCCESS。测试使用隔离 PostgreSQL 17.11；更新前的证据从空库迁移至V315。针对本次 `NOT VALID`/`VALIDATE` 迁移调整，同一命令再跑24/24通过，当前分支空库迁移到 V316；Squawk 单文件检查 V315 为0 issues。真实PG直接写覆盖同租户成功、错租户和孤儿 SQLSTATE 23503、正确租户物理删除级联以及另一租户同名配置保留；健康服务集成场景验证软删除状态仍可留存；网关单测验证健康记录异常不会改写外部投递结果。Surefire报告见 `batch-worker/dispatch/target/surefire-reports/`。
- **边界**：本切片关闭的是此一关系的结构/写入/删除语义与局部回归，不覆盖全局 REF-05 配置入口、通道删除并发锁容量、部署环境迁移、SIM、strict启动审计或整个数据库关系清单；REF-05/08、DBSAFE-3及整体验收仍开放，格式检查依用户要求暂缓。
