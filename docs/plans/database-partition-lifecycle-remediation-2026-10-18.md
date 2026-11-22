# 数据库表分区与分区扩展专项审查及整改方案

> 审查日期：2026-10-18。状态：**结构与生命周期审查完成；PART-01 叶子隔离已有局部实现和动态证据，整体整改未闭环**。属于系统问题整改；与[引用完整性方案](./database-reference-integrity-remediation-2026-10-18.md)、[索引专项](./database-index-review-remediation-2026-10-18.md)交叉但分别推进。纳入[总方案](./workflow-daily-integration-plan-2026-10-14.md)，唯一当前状态见 [TODO Master](todo-master.md) 的 `ALIGN-DB-PARTITION`。

> 后续 P0 验证（2026-10-18）：RLS/普通角色守护 29 项与 Process 分区类 4 项通过，覆盖叶子漏策略守卫、同一 HASH 叶子的两租户读取、实际 Mapper 新叶子创建/策略同事务回滚，以及普通角色的跨租户 INSERT 拒绝、UPDATE/DELETE 不可见、合法更新和空上下文拒绝。尚未逐目录重跑真实安装/strict/rollback 脚本、全部业务分区及多物理库联合矩阵，也没有关闭在途清理；详见[执行证据日志](workflow-daily-integration-execution-log-2026-10-14.md)文末。下文目录与源码行号保留初始审查时点，不能当作修复后再次实测的当前结构。

> 最新 PART-01 切片（2026-10-18）：已在独立 PostgreSQL 上执行真实安装/strict/rollback 脚本，遍历权威业务 DDL 的租户父表与叶子；后段故障证明三份脚本现在整事务回滚，安装拒绝既有 SUPERUSER/BYPASSRLS writer，闭世界检查拒绝未知 PERMISSIVE 或 OR-true 策略。先复跑7个原失败用例，再跑相关6类37项，均通过。非空 CRUD 数据只覆盖 customer/staging，不能扩大为所有业务表的数据验收。灾备调用方不再吞掉 RLS 重建失败，3项 mock 组件用例通过；未执行真实恢复。多物理库、在途清理、维护权限与目标环境仍开放，最新证据见验证矩阵文末“RLS 脚本原子性与恢复失败传播”。

## 1. 结论

当前分区基础结构已经建立，现存子索引挂接完整；**持续扩展、普通角色隔离、过期清理及多物理库覆盖尚未形成完整闭环**。这些问题涉及实现和运维入口调整，不能只追加验证项。

优先处理普通业务角色直接访问无 RLS 子分区的权限缺口，以及 PROCESS 按日期整分区淘汰没有检查在途/恢复任务的问题。随后补平台持续维护入口、修扩分区脚本事务语义、完善多分片与默认分区处置；查询裁剪、统计和索引放大随专项一起验收。

本轮没有执行 DDL/DML、启停服务、创建/删除分区、迁移 DEFAULT 数据、读取业务行、运行测试或负载压测。没有将数据库分区调整扩展为 SDK 协议、作业分片算法或新分库框架改造。

### 1.1 三种“分区/分片”分别管理

| 对象 | 当前含义 | 本次边界 |
|---|---|---|
| PostgreSQL 表分区 | RANGE 日期/月/日、HASH tenant；父表与物理子表 | 本专项全量审查：扩建、清理、权限、索引、锁、统计与恢复 |
| 作业执行分区 `job_partition` / task | 作业工作量拆分、领取、租约、重试和恢复 | 不等于数据库子表；数据库淘汰必须保护仍需读取数据的执行/恢复实例，不因本专题擅改分片规则 |
| 业务数据库 placement/shard | tenant 路由到默认库、pool shard 或 silo | 维护需遍历唯一物理库；一个路由 DataSource 的默认连接不等于覆盖全部库 |

## 2. 实际结构与全量覆盖

同本次引用/索引审查的 PostgreSQL 17.11 / V291 本机隔离环境。结构快照 **08:54:38 +08:00**；索引快照 **09:06:34–35**；权限/存储参数快照 **09:08:36**。两轮结构按表和索引定义匹配；容器外部重建导致 OID/统计窗口变化，本报告不把它们合并为持续运行证据。

共 **10 张分区父表、107 张子表**。在全部 **176 张逻辑表** 中，其余 **166 张当前未采用声明式表分区**；本轮没有足够生产规模依据要求它们一律分区。普通表的索引、清理和冷热生命周期见两个交叉专题。

| 数据库 / 父表 | 键与实际子表 | 有界范围（左闭右开） | 父索引 → 子索引 | 扩展/保留判断 |
|---|---|---|---:|---|
| 平台 `batch.job_instance` | RANGE(biz_date)，36 月 + DEFAULT | 2024-11-01 → 2027-11-01 | 19 → 703 | V173 初始窗口，未找到持续生产维护入口；保持原业务日，不能改为当前物理日 |
| 平台 `batch.outbox_event` | RANGE(created_at)，36 月 + DEFAULT | 2024-11-01 00:00+08 → 2027-11-01 00:00+08 | 6 → 222 | V172 初始窗口；未来建区须固定时区；未投递/重试/归档引用限制淘汰 |
| 平台 `batch.console_usage_daily` | RANGE(stat_date)，16 月 + DEFAULT | 2026-09-01 → 2028-01-01 | 3 → 51 | V216 初始窗口；03 脚本也未覆盖本表，需同一维护清单纳管 |
| 业务 `batch.process_staging` | RANGE(staged_at)，仅 DEFAULT | 无有界日分区 | 5 → 5 | Cleaner 有自动扩展/淘汰实现；local profile 显式关闭，不能据当前仅 DEFAULT 判生产调度失败 |
| 业务 `biz.transaction` | RANGE(txn_date)，仅 DEFAULT | 无有界日期分区 | 4 → 4 | 示例业务建表只建 DEFAULT；长期使用时没有日期裁剪/按期淘汰结构，需明确业务库维护责任 |
| 业务 `biz.risk_score` | RANGE(score_date)，仅 DEFAULT | 无有界日期分区 | 4 → 4 | 同上，范围粒度与留存由业务数据生命周期决定 |
| 业务 `biz.risk_alert` | RANGE(alert_date)，仅 DEFAULT | 无有界日期分区 | 4 → 4 | 同上，告警审计/处理状态不能单由日期决定删除 |
| 业务 `biz.customer_account` | HASH(tenant_id)，4 子表 | modulus 4 / remainder 0–3 | 4 → 16 | 桶齐全，不需按月扩展；扩桶须重分布设计 |
| 业务 `biz.settlement_batch` | HASH(tenant_id)，4 子表 | modulus 4 / remainder 0–3 | 4 → 16 | 桶齐全；与 detail 的同租户外键及数据恢复同时保护 |
| 业务 `biz.settlement_detail` | HASH(tenant_id)，4 子表 | modulus 4 / remainder 0–3 | 5 → 20 | 桶齐全；4→8 不可通过简单追加相同模数的第 5 个桶实现 |
| 合计 | **10 父表 / 107 子表** | DEFAULT 共 7 个 | **58 → 1,045** | 本轮逐个核对，不只按建表脚本推断 |

[分区实物清单 CSV](../audit/database-partition-catalog-2026-10-18.csv) 共 **117 行**（10 父 + 107 子），包含真实分区键/边界、索引挂接、RLS/FORCE、owner、ACL、writer角色属性及其采集时间、reloptions、统计时间与行数估计；估计行数不是精确数据计数。全部 1,045 个子索引挂接到正确父索引，每个子表覆盖相应父索引集合；未见 invalid/not-ready/not-live。RANGE 有界窗口逐月连续，HASH 四个余数完整。初始范围尚覆盖当前月份，不应写成今天已经无分区可写。

业务 `biz.*` 来自示例/验收业务建表脚本，并不代表所有外部业务库都必须采用同样分区。平台兼容 `batch.process_staging` 是普通表，与业务库同名分区表须按数据库身份区分，不能向平台表误执行分区维护。

## 3. 已确认问题与静态风险

### PART-01：P1，叶子直接访问绕过父表租户策略

**事实已确认**：实际业务库 16 个叶子全部 `RLS=false / FORCE=false`，而普通非 superuser、非 BYPASSRLS 的 `batch_business_writer` 显式拥有 `arwd`（SELECT/INSERT/UPDATE/DELETE）。相关父表均开启 RLS/FORCE。

[rls-phase-a.sql](../../scripts/db/business/rls-phase-a.sql) 第 63–81 行授予 schema 使用权和 ALL TABLES/default privileges；第 111–122 行以“分区子表继承父表 RLS”为由排除 `relispartition=true`。[RlsClosedWorldChecker](../../batch-common/src/main/java/io/github/pinpols/batch/common/rls/RlsClosedWorldChecker.java) 第 60–65 行也排除叶子。

通过父表访问会应用父策略；直接命名子表访问则只受子表自身策略约束。因而当前角色可直接访问没有租户过滤的叶子；若包含其他租户行，会失去该数据库隔离边界。**这是实际 ACL/RLS 与数据库语义确认的配置缺口，尚未验证应用 API 可达利用路径，也未发现或宣称已发生数据泄漏**。[PostgreSQL 17 继承访问与行安全规则](https://www.postgresql.org/docs/17/ddl-inherit.html)

整改要求：

- 应用仅访问逻辑父表时，收敛 ALL TABLES/default grants，去除叶子直接及继承得到的应用访问权限；核验有效权限，不能只看显式 ACL。
- 必须直接访问叶子的入口，逐叶配置正确的 ENABLE/FORCE RLS、USING/WITH CHECK 和事务 tenant context。新建/挂接必须一起完成授权与隔离，不能暴露“先可访问、后补策略”的窗口。
- 闭世界检查遍历叶子并检查“角色有效访问能力 + 隔离规则”，校正“自动继承 RLS”的注释与运维口径。对象默认权限仅作用于设置它的创建者后续创建对象，必须按实际建表角色验证。
- 使用非 owner、非 BYPASSRLS 应用角色验证父表/叶子、已有/新建分区、tenant 缺失/切换及两租户 SELECT/INSERT/UPDATE/DELETE 拒绝路径。不要用提升 Worker 为 owner/superuser/BYPASSRLS 来解决维护权限。

平台 `job_instance/outbox_event` 在快照中父子 RLS 均未开启；其平台授权/应用过滤体系需按 R20 既有范围核对，不能仅凭这一点推导与业务库相同的可达越权结论。

### PART-02：P1 静态风险，PROCESS 整分区淘汰未保护在途与恢复数据

[原实现快照](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingOrphanCleaner.java)按 UTC 当前日预建分区，并依据 `retentionDays` 和分区名后缀 DROP；Mapper 另有只按 `staged_at` 删除行的 TTL SQL。它们不读取真实边界或运行恢复身份，不能证明安全。当前实现已删除按年龄直接删行和整分区 DROP 的 Mapper 入口；UTC 分区维护只负责预建。

原行级清理虽使用 `tableoid + ctid` 和 `FOR UPDATE SKIP LOCKED` 避免跨分区行定位错误，但**不保护跨事务仍需恢复的批次，也不能替代整表 DROP 的业务准入**。该按年龄路径已移除；仍须验证新 generation 协议覆盖全部恢复旅程，才可启用回收。

整改必须定义 staging 的生产者、消费完成事实、最晚恢复期限和可删除证明；按真实分区边界筛选，清理前重新核对活动批次，失败或无法证明安全则保留并告警。整分区 DROP 与行 TTL 分别保护，清理大小、超时、互斥及恢复方案不能只靠名称正则。验证长任务跨日、暂停超过保留期、计算后发布前崩溃、迟到重试、并发写入/清理，证明活动任务保留、完成批次最终收敛。

**当前实施切片（2026-10-18）**：PROCESS staging 位于业务库，任务当前 generation 和实例 fence 位于平台库。现在只枚举超期 OPEN/SEALED generation；清理事务先用 tenant+JobInstance advisory fence 串行化 Worker 阶段、reclaim 和 generation 轮换，再确认候选 UUID 已不是 task 当前代。当前代、缺失 task/instance/current generation 和未知身份均失败关闭。业务库事务锁 ledger，OPEN 代转 SEALED，设置租户与 generation RLS GUC，并通过 `tableoid + ctid`、精确 tenant/generation/batch key 和 `FOR UPDATE SKIP LOCKED` 有界删除；部分删除后 SEALED ledger 保留，下一轮可幂等续删。创建分区只负责预建，整分区 DROP Mapper API 已移除。启动审计按租户上下文枚举 generation，并只统计平台 fence 确认过期的代次，扫描截断时显式标记。两个独立 PostgreSQL 容器上的真实清理围栏故障注入已验证“业务提交、平台事务回滚后可重试”“业务事务中途失败整笔回滚后可重试”及“首块提交、后续块失败后 SEALED 可续清”（详见验证矩阵，清理类3/3）；另有 Worker fence PostgreSQL 集成4/4证明新 invocation 重跑 COMPUTE 会轮换 generation、同 invocation 的 VALIDATE checkpoint 复用当前 generation。Orchestrator `JobRetryFlowIntegrationTest` 以真实 PostgreSQL 覆盖自动重试调度→到期重投递→重新 CLAIM，确认 retry 后 invocation ID 改变（类3/3）；之后新增真实 PROCESS 自动 retry E2E 1/1，以及 Workflow/JobInstance 保持活动时的 PROCESS 手工 retry E2E 1/1，均确认新 invocation 轮换 generation；手工 retry E2E 还覆盖活动兄弟分区期间 JobInstance/Workflow pause/resume，并确认 generation 未改变。双库 PostgreSQL archive-fixture 用例证明 cleanup fence 能从冷表读取 task 当前 generation 并保留当前代（定向1/1，整个相关集成类4/4）；生产 `SuccessInstanceArchiveService` 测试另确认非空 generation UUID 与 batch key 原样复制到 `archive.job_task_archive`（1/1）。Worker 先行和清理器先行两种 VALIDATE checkpoint-stage 锁交错均已由双 PostgreSQL 集成验证（所属类6/6）。上述切片现由下文完整生产 archive E2E 串联 Workflow/JobInstance 归档、冷表 generation 读取和旧代清理；仍不覆盖进程强杀、网络断连/提交确认丢失和多物理库。`batch.worker.process.staging-cleanup.deletion-enabled` 仍默认 `false`；当前工作不授权开启。最新代不会回收；跨多物理业务库、完整暂停/归档状态恢复、长 SQL 锁时延、其余跨库故障注入、容量和 SIM 验收仍开放。安全依据是旧代已被持久化的新代取代，不依赖父任务表面终态；这项依据仍须通过各类恢复旅程验证。

**协议设计约束（禁止按表面任务终态直接放开清理）**：当前 batch key 为 `process-<taskId>`，故意跨任务重试保持稳定，让 COMPUTE/VALIDATE 崩溃后的重派和阶段续跑继续找到同一份 staging；直接改用每次 lease/invocation 的 ID 会破坏合法 checkpoint 恢复，真实 PostgreSQL 阶段续跑测试已确认这一语义。稳定 key 又使重试共享 staging 身份，因此 REPORT fence 单独不足以约束已进入业务库的 SQL；现有阶段 fence 只解决“lease reclaim 与一个正在运行的 PROCESS 阶段不能并行越过 claim 所有权检查”，不会阻止合法重试复用 staging，也未给清理器提供可恢复的批次终结证明。完成清理必须同时解决这些问题：为 staging 引入平台持久化的 generation/续跑身份；所有 framework staging 写入、验证、发布及清理均以该身份执行；只有允许沿用阶段产物的续跑才能复用 generation，重新 COMPUTE 的 attempt 必须获新 generation；业务库以事务级写入封口/tombstone 阻止已回收 generation 的迟到写入，并在同一业务库事务内删除其 staging。

围栏必须证明确实覆盖所有会改变执行所有权的路径。当前 Worker stage、`PartitionReclaimUnit` 与 generation 清理均按同一 tenant/JobInstance advisory lock 串行化；reclaim 保留 task `FOR UPDATE NOWAIT` 作为旁路防御。generation 轮换必须持有此锁，故清理时读到的“当前代不同”证明合法 checkpoint 只能读取新代，而非被清理的旧代。VALIDATE 阶段与清理器两个锁竞争顺序已有局部真实 PostgreSQL 证据；完整 pause/resume 与 checkpoint 重启恢复、生产归档全旅程、同实例多分区风暴及长 SQL 对 reclaim 延迟的量化仍待验证。若发现任何恢复路径会重新指向旧代，必须先修正协议并保持删除开关关闭。跨数据库不能假装为单一原子事务：业务封口与分块删除必须可幂等重试，需注入平台锁释放、业务事务回滚/提交中断并证明恢复收敛。多物理业务库覆盖按 PART-05 单独解决；单库通过不能授权多库启用。

**组合恢复验证补充（2026-10-18）**：新增真实 PROCESS Worker E2E 覆盖 SQLSTATE 40001 COMMIT 失败、自动 retry 调度与生产重投递、重新 CLAIM、新 invocation 触发新 staging generation、旧代不混入新代发布；另覆盖父 Workflow/JobInstance 仍 RUNNING 时调用生产手工 retry API、活动兄弟分区延迟期间 JobInstance/Workflow pause-resume 与新 generation 身份保持；两用例各 1/1 通过。双 PostgreSQL archive-fixture 测试证明生产 fence Mapper 从冷表读取当前 generation 并阻止清理；生产 archive service 的运行树集成测试另验证 generation UUID 与 batch key 冷复制。Worker 先行和清理器先行两个 checkpoint-stage 锁竞争顺序均由双 PostgreSQL 集成验证，所属类6/6通过。随后新增完整生产链路 E2E `ProcessPipelineE2eIT#archivedProcessTask_cleanupUsesColdCurrentGeneration`：真实 PROCESS 成功运行后，先用生产 Workflow archive service 归档 Workflow，再用生产 JobInstance archive service 归档运行树，最后用生产跨库 cleanup fence 从冷表读取当前 generation，保护当前代并只回收过期旧代。命令 `./mvnw -o -ntp -pl batch-e2e-tests -am '-Dtest=ProcessPipelineE2eIT#archivedProcessTask_cleanupUsesColdCurrentGeneration' '-Dsurefire.failIfNoSpecifiedTests=false' test`，1/1、0 failures/errors/skips、BUILD SUCCESS；16个 reactor 模块通过，空库应用308个迁移至V313。该E2E发现并由V313修复V71冷表快照遗留的类型CHECK漂移；`ArchiveColdStorageIntegrationTest` 比较热/冷 job_task 与 pipeline_instance 约束定义，13/13通过。完整归档旅程已获得此单库切片证据，但进程强杀/提交确认丢失、多物理业务库、并发业务写入与清理的双向锁竞态、非 owner/BYPASSRLS 应用凭据及长 SQL 锁时延/容量仍未覆盖。PART-02 保持开放，自动删除开关保持关闭。详细命令和断言见[整合验证矩阵](workflow-daily-integration-execution-log-2026-10-14.md)。

### PART-03：P2，平台三表只有初始窗口，缺少可确认的持续维护入口

[V172](../../db/migration/V172__outbox_event_monthly_partition.sql)、[V173](../../db/migration/V173__job_instance_monthly_partition.sql) 按执行月生成 `-23..12`；[V216](../../db/migration/V216__create_console_usage_daily.sql) 生成 `-1..14` 月。初次 Flyway 成功不提供持续扩展。

[现行运维手册](../runbook/pg-table-partitioning.md) 已说明仓库未找到生产自动创建调度器/部署 cron；`partition-migration/03` 归类演练，不能把文件头 cron 示例当成已部署能力。该脚本还遗漏 console_usage_daily。

实施前在现有维护组件/运维入口中明确一个责任方、执行身份、全部父表清单、预建窗口与失败告警；避免应用与 cron 两套不协调维护。初始化窗口与后续窗口采用同一日期/时区契约；按实际 bounds 判缺口，而非只 `IF NOT EXISTS`。不要为此引入未经决策的新扩展或通用调度框架。

### PART-04：P2，演练扩建脚本的容错说明与事务结果不符

[03-add-future-partitions.sql](../../scripts/db/partition-migration/03-add-future-partitions.sql) 第 57–67 行内块 catch 可继续尝试其他月份，但第 71–74 行最终未捕获的 RAISE 会回滚**整个 DO 本轮成功的 DDL**。它实现“继续尝试并汇总错误”，没有实现“保留成功分区”；原有已提交分区不受影响。第 13–16、56 行的容错注释容易误导。[PostgreSQL 17 异常块回滚语义](https://www.postgresql.org/docs/17/plpgsql-control-structures.html#PLPGSQL-ERROR-TRAPPING)

若要求成功项持久化，应使用每个分区独立可提交单元，外层汇总失败并返回非零；若要求整批原子，保留回滚并修正契约。同步增加窗口参数范围、父表白名单、时区、锁/语句超时、现有对象定义核对。Java/脚本负责流程和错误汇总，SQL 保留清晰 DDL；本轮不执行或修改该脚本。

### PART-05：P2，多物理业务库维护可能只覆盖默认库

PROCESS Mapper 通过 `processBusinessDataSource` 路由；[BusinessRoutingDataSource](../../batch-common/src/main/java/io/github/pinpols/batch/common/tenant/routing/BusinessRoutingDataSource.java) 从 tenant holder 取路由键。Cleaner 定时入口没有 tenant/placement 遍历；[HashAndSiloPlacementResolver](../../batch-common/src/main/java/io/github/pinpols/batch/common/tenant/routing/HashAndSiloPlacementResolver.java) 空 tenant 返回默认库，[DbTablePlacementResolver](../../batch-common/src/main/java/io/github/pinpols/batch/common/tenant/routing/DbTablePlacementResolver.java) 空 tenant 走 fallback。

因此启用多库路由后不能将一次清理当作所有 shard/silo 已维护。定时锁名称固定 `process_staging_orphan_cleanup`，并发控制也没有表达物理库维度；实际是否共享锁库须结合部署核实。本轮未验证多库运行。

整改按实际物理连接/placement 去重枚举，逐库记录创建/清理结果、可达性、权限和失败重试，互斥键包含物理库身份；仅为维护传一个代表 tenant 不能证明全部库覆盖。禁止跨库假事务或把数据库路由扩容等同于表分区扩建。

### PART-06：P2，业务日期分区仅 DEFAULT，维护职责尚需明确

[create_biz_tables.sql](../../scripts/db/business/create_biz_tables.sql) 对 transaction/risk_score/risk_alert 只创建默认子表。可正常路由写入，但没有日期子区的裁剪/按期淘汰收益；数据长期累积时不能报告“已经完成按日/月分区治理”。

示例/验收用途可保留 DEFAULT，需注明其能力边界；如进入长期部署，按业务日期、增长量、保留与恢复要求选日/月粒度并登记维护方。外部业务表由业务方负责时，通过部署契约核对，不让平台 Worker 自行扩建任意用户表。HASH 三表当前完整，无需自动增加余数；扩桶/迁库另作有数据重分布与回退的变更方案。

### PART-07：P2，DEFAULT 补区、历史回补与时区必须成为有界流程

存在 DEFAULT 时并非只要有数据就一律无法建区：**只有落入目标新边界的已有行才造成约束冲突**；扫描 DEFAULT 本身也可能产生锁和耗时。当前脚本头部“DEFAULT 一旦有数据后再加常规分区会失败”过于绝对。合法历史补跑也可能进入默认区，不能无条件告警为数据错误。

平台 outbox 的现有边界为 +08，脚本使用 DATE 隐式转换；PROCESS 显式 UTC。应分别固定各表的时间语义，不能在不同会话时区继续造相邻边界，也不能将 biz_date 改为系统当前日期以避开缺分区。建立只读缺口/DEFAULT 日期分布检查、经审核的数据迁移与并发写入控制、行数/指纹/唯一及引用对账和回退；无用户授权不自动 DETACH、DELETE 或重排历史业务日。新建正常分区不把旧 DEFAULT 行自动搬过去。[PostgreSQL 17 分区维护](https://www.postgresql.org/docs/17/ddl-partitioning.html#DDL-PARTITIONING-DECLARATIVE-MAINTENANCE)

### PART-08：P2，DDL 锁、失败残留与最小权限需要配套实现

CREATE/ATTACH/DETACH/DROP 的锁成本及默认区扫描需要单独评估。Cleaner 的 ShedLock 最长 5 分钟，而 Mapper DDL 没有显式 lock_timeout/statement_timeout；不能依赖租约到期自动终止阻塞 SQL。按数据库/父表互斥，限定锁等待与每轮工作量，确保数据库操作超时早于互斥租约失效；失败可重入且有状态证据。

`CREATE TABLE IF NOT EXISTS` 只跳过同名关系，**不证明它属于正确父表、边界或索引/权限一致**。重试前后核目录定义，错误对象应失败关闭。[PostgreSQL 17 CREATE TABLE](https://www.postgresql.org/docs/17/sql-createtable.html)

维护使用限定权限的维护角色/入口，应用写角色不为 DDL 被授予 owner 或 BYPASSRLS。分区索引上线遵循父/子构建与挂接协议，不能假定父表直接支持普通表的并发索引创建方式。动态行为、锁超时和失败中断尚未验证。

### PART-09：P2，新增日分区遗漏存储参数与父表统计维护

业务默认 staging 显式设置 autovacuum scale/threshold；建表脚本注释称 Cleaner 对日分区也设置参数，但实际 `createDailyPartition` 仅 CREATE，未执行相同设置。新叶子不会从默认兄弟表获得 reloptions，需统一创建后参数校验；不应盲目将 staging 的激进设置套给所有历史表。

物理叶子的 autovacuum 与分区父表统计分别维护：分区父表不由 autovacuum 自动 ANALYZE，需按数据变化安排适当的父表 ANALYZE。验证新/旧分区统计、回收进度、膨胀、freeze age；空库统计无法说明生产健康。[PostgreSQL 17 日常统计与 VACUUM](https://www.postgresql.org/docs/17/routine-vacuuming.html)

### PART-10：P2，日期列转换已证实影响裁剪；索引数量随分区放大

Console JobInstance Mapper 对 `biz_date` 转 varchar。只读计划的同日期对比已证明规划涉及 **37 → 1** 个分区，原件和限制见[索引专项 §1.2](./database-index-review-remediation-2026-10-18.md#12-已取得的只读计划证据)。优先改参数侧日期类型，再测完整业务查询；不能据此宣称实际加速 37 倍。

job_instance 每新增一个分区会复制 19 个父索引；现有 703 子索引是正确派生，不能当作重复垃圾直接删除。父索引收敛须先完成 IDX-06 的查询/约束依赖与写放大测量；新建分区验收索引挂接和有效性，不能只验表存在。

### PART-11：P2，淘汰须与归档、引用、幂等及恢复联合裁定

日期过期不等于可删除：Job 的在途/等待/重放、Outbox 的未投递/重试/消费回执、usage 的审计保留、业务结算与风险历史都有各自保留契约。先明确热归档完成事实、子引用处理、迟到回调与可重放期限，再允许淘汰；不能用 DROP CASCADE 清除引用阻碍。

保留跨分区去重账本、结果版本与重放身份，不能随分区过期同步清空而重新接纳旧副作用。Job/Outbox 的复合唯一键/引用键问题按 REF-02/04/06 处理；PostgreSQL 17 支持分区表 FK，不是统一去 FK 的理由。恢复后核分区结构、业务日、引用/去重账本、sequence、路由、权限和外部副作用，再恢复调度；备份存在不代表已恢复验收。

### PART-12：P2，维护结果指标与值班闭环不足

Cleaner 每次 CREATE IF NOT EXISTS 未抛异常都执行 `created++`，包括已有分区；当前计数日志不是“实际新增数”。失败主要记 warning，没有逐库/父表的维护状态、覆盖到哪一天、DEFAULT 增长或最后成功时间证明。

新增有界指标/结果记录：窗口上界与缺口、DEFAULT 规模及增长（区分合法历史）、成功/失败/未处理项、锁等待、实际创建/淘汰数、最后成功时间和告警责任人。监控采集本身需限制开销；不得把全表 COUNT 机械放进高频健康检查。部署检查覆盖启用开关、local 与目标环境差异、时区、权限、预建/保留参数及故障后重入。

## 4. 分阶段整改与验收清单

| 阶段 | 内容与责任 | 关闭证据 |
|---|---|---|
| A，隔离与数据安全 | PART-01：业务库 RLS/角色、建表授权、闭世界检查；PART-02/11：PROCESS 与归档负责人裁定在途/恢复保留 | 普通应用角色两租户父/叶子负例；跨日长任务/重试数据保留、完成后清理；整改代码与真实 PG 证据。P1 不以文档或 Mock 关闭 |
| B，扩展与可恢复执行 | PART-03/04/05/07/08：维护入口、逐库执行、已裁定的提交/回滚契约与失败汇总、DEFAULT 与时区、权限和锁 | 新月/新日、跨年、时区边界、窗口漏建/历史补跑、同名错误对象、DEFAULT 冲突、按选定契约验证单项失败后成功项持久化或整批回滚、并发多实例、锁超时与重入、多 shard+silo |
| C，容量与维护质量 | PART-06/09/10/12：业务表责任、参数/统计、裁剪、索引成本、指标告警 | 代表规模父/子计划、读取/写入/WAL/体积、维护锁耗时；真实告警与失败定位；示例 DEFAULT 能力边界明确 |
| D，发布与恢复 | 与 REF/IDX、I4/I6 和 R20/R33/R36 联合核对 | 部署参数/开关/角色清单；清理/分区变更回退及备份恢复；归档/重放/结日/告警业务结果一致；目标环境证据 |

每项登记模块负责人、实现切片、失败场景、命令/环境、结果及遗留风险。所有动态验收目前均未在本轮执行；已有其他任务的结果按各自 SHA/环境单独引用，不重复算成本专题完成。

## 5. 证据、可复现性与当前检查

- [分区 CSV](../audit/database-partition-catalog-2026-10-18.csv)：10 父 / 107 子，定义、ACL、RLS、options 与索引挂接；[索引 CSV](../audit/database-index-catalog-2026-10-18.csv)：全部 1,634 目录对象；[全表结构 CSV](../audit/database-reference-catalog-2026-10-18.csv)：全部 283 物理关系。
- [日期 EXPLAIN JSON](../audit/database-job-date-pruning-explain-2026-10-18.json)：09:13:53 新进程只读计划；没有实际执行时间/BUFFERS，不能与 09:06 的索引统计合并推算收益。
- 本轮静态核对：全部分区集合、月范围连续性、HASH 余数、父/子索引集合、有效标志和业务叶子权限；独立复核确认 RLS 访问规则及外层 DO 回滚判断。
- 运行 SQL 的角色为 catalog 采集角色，不是以 superuser 结果证明普通角色隔离。未执行跨租户业务行读取或写入负例。
- 只读计划首次尝试因容器不存在而失败；容器再次出现后复核版本并成功取得计划。未启动/删除容器；每份证据按自身时间与实例标识使用。
- 后续实施同步更新 `pg-table-partitioning.md`、业务 RLS runbook、业务建表/分区演练说明、配置入口和验收矩阵；本次仅增加待整改提示与可发现链接，不将方案写作已实现能力。

## 证据文件摘要

| 文件 | SHA256 |
|---|---|
| `database-index-catalog-2026-10-18.csv` | `b1a5193cef09b1bdd74464f70e1663d94298f719997e07474c3015fa1e4aa806` |
| `database-partition-catalog-2026-10-18.csv` | `8a72355e98642a80fcdc1335b10d3cfdf3d20555fb890e5d02da3b479ec7ff9f` |
| `database-job-date-pruning-explain-2026-10-18.json` | `c1c889fe6c2bea1c59547c7543cdfa5c60089ed46d6ecd94eb2c97b7d57fa6da` |

## 本轮文档检查

- `python3 scripts/ci/check-docs-structure.py`：通过（退出码 0）。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：通过（退出码 0）。
- 本轮文档范围的 `git diff --check`：通过（退出码 0）。
- 176表/589索引覆盖、117分区关系/1,045子索引挂接及目录有效状态已静态核对；没有运行实现测试或性能验收。
