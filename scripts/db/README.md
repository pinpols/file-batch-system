# 数据库脚本目录说明

## 与根 db 目录的分工

- 根 `db/migration/`：版本化平台结构迁移，由 Flyway 按版本执行。
- 本目录：数据库操作入口及配套 SQL，包括巡检/预检、备份恢复、清理、业务库隔离与测试种子。脚本不会因位于 db 目录而自动具备生产执行授权。
- 本目录不整体并入根 db：迁移历史与人工操作生命周期不同，当前构建资源、容器挂载、测试和运行手册也消费本目录路径。现阶段保持路径稳定，通过下表和配套索引明确分类。

| 任务 | 入口与材料 | 执行边界 |
|---|---|---|
| 结构、索引、分区与容量巡检 | [inspect-schema-governance.sh](inspect-schema-governance.sh)、[控制面预检](check-postgres-control-plane-readiness.sh) | 只读采样；不自动执行 DDL 或清理 |
| 备份、恢复与灾备演练 | [backup](backup/README.md) | 备份与破坏性恢复分别确认目标，不以本地演练替代生产灾备证据 |
| 到期实例、工作流、Outbox 与死信清理 | [使用场景](sql-script-usage-scenarios.md)及本页 SQL 索引 | 依照保留、审计、租户及外键约束执行，不能将清理当作通用修复 |
| 业务库初始化与 RLS | [business](business/README.md) | 与平台 Flyway 分开；目标库、角色与租户隔离显式指定 |
| 配置缓存失效队列角色授权 | [config-cache-invalidation-role.sql](config-cache-invalidation-role.sql) | 平台库 DBA 显式指定已有可信 Console 主库角色；只调整该角色的 V372 队列权限和专属策略 |
| 分区迁移演练 | [partition-migration](partition-migration/README.md) | 属于人工演练资产，不直接放入正式 migration 路径执行 |
| 测试与压测种子 | [test-seed](test-seed/README.md)、[load-test-seed](load-test-seed/README.md) | 仅隔离测试目标；共享 SIM 场景输入在根 test-data |

SQL 文件维持现有路径。若后续单独整理本目录的平铺 SQL，需同时验证读取器、相对 include、构建资源、容器挂载及测试；不把目录整理与迁移脚本内容改写混成一次操作。

这里收纳非 Flyway 的数据库维护脚本：业务库样例、测试种子、压测种子、清理、备份恢复和分区迁移演练。

## 目录分工

- [business/](./business/)：业务库 `batch_business.biz` 的示例建模、RLS 和分区脚本。
- [test-seed/](./test-seed/)：平台库与业务库的系统测试种子。
- [load-test-seed/](./load-test-seed/)：压测场景初始化数据。
- [backup/](./backup/)：PostgreSQL 备份、恢复和 DR drill 脚本。
- [cleanup/](./cleanup/)：测试、压测和临时数据清理 SQL。
- [partition-migration/](./partition-migration/)：大表分区迁移演练 SQL。

平台正式结构变更只维护在 [db/migration/](../../db/migration/)；本目录脚本用于环境准备、演练和一次性维护。

租户重置脚本 [wipe-tenants.sql](wipe-tenants.sql) 和 [wipe-non-system-tenants.sql](wipe-non-system-tenants.sql) 面向已执行 V373 的平台库，会删除被清理目标租户的配置批操作回执，并删除被清理审计租户的操作及其级联回执。其他审计租户的操作和目标列表保留；其中缺失回执的目标只能按结果未知核对，不能判定为失败或完成。执行账号须具备两表的跨租户删除权限并能处理其强制行级隔离策略；普通租户角色可能看不到全部待清理记录。执行前仍须核对目标库、租户清单和备份。

### 租户清理与触发观察记录的确认和恢复

这三个入口默认只预览，不删除。先停止相关写方，核对数据库和具备跨租读取权限的运维角色，执行并验证可恢复备份；预览时输出候选租户，以及删除审计操作级联影响保留租户的回执、保留审计操作因目标清理而变为 UNKNOWN 的回执。跨租户读取不足会漏算影响，因此两个租户清理脚本要求超级用户或 BYPASSRLS 运维角色；只在已确认的目标库使用，不自动授予权限。保留租户若引用待删父实例则拒绝，先核对并由业务处置。非系统清理仅处理预览时仍在租户表中的目标，不自动清理无主孤儿；预览与删除是两次执行，删除前重新计算并锁定租户表，目标集合有变化将拒绝。

```sh
# 指定目标；action 省略即预览。确认值必须与预览打印的排序结果完全一致。
psql -X -v ON_ERROR_STOP=1 -v tenant_ids=ta,tb,tc -f scripts/db/wipe-tenants.sql
psql -X -v ON_ERROR_STOP=1 -v tenant_ids=ta,tb,tc -v action=delete \
  -v confirm_database=目标数据库 -v confirm_targets=ta,tb,tc -v confirm_backup=verified \
  -f scripts/db/wipe-tenants.sql
# 非系统目标以预览打印的逗号分隔列表为准；无目标时为 <none>。
psql -X -v ON_ERROR_STOP=1 -f scripts/db/wipe-non-system-tenants.sql
psql -X -v ON_ERROR_STOP=1 -v action=delete -v confirm_database=目标数据库 \
  -v confirm_targets=预览目标列表 -v confirm_backup=verified -f scripts/db/wipe-non-system-tenants.sql
# 触发观察清理同样先预览，删除时重复确认租户和截止时刻。
psql -X -v ON_ERROR_STOP=1 -v tenant_id=租户 -v cutoff=截止时间 -v batch_size=1000 \
  -f scripts/db/cleanup-scheduled-occurrence-fire-retention.sql
psql -X -v ON_ERROR_STOP=1 -v tenant_id=租户 -v cutoff=截止时间 -v batch_size=1000 \
  -v action=delete -v confirm_database=目标数据库 -v confirm_tenant=租户 \
  -v confirm_cutoff=截止时间 -v confirm_batch_size=1000 -v confirm_backup=verified \
  -f scripts/db/cleanup-scheduled-occurrence-fire-retention.sql
```

`confirm_backup=verified` 是操作员的显式确认，并不能证明备份可用；删除已提交后不能用事务回滚，需停止写方并按 [备份恢复流程](backup/README.md) 在隔离环境验证后恢复。恢复范围须同时包含配置批操作及其回执，否则无法凭当前配置还原原结果。预览不锁定未来写入，必须在相同参数下重新预览并停止写方后执行；有未知结果时先人工核对，不能用新键重新发起整个批次。

## 常用入口

1. 本地联调先看 [docs/runbook/local-development.md](../../docs/runbook/local-development.md)。
2. 业务库示例建模走 [business/README.md](./business/README.md)。
3. 系统测试数据装载走 [test-seed/README.md](./test-seed/README.md)。
4. 压测种子数据走 [load-test-seed/README.md](./load-test-seed/README.md)。
5. 备份恢复演练走 [backup/README.md](./backup/README.md)。
6. 分区迁移演练走 [partition-migration/README.md](./partition-migration/README.md)。
7. 外置 PostgreSQL 上线前运行 `check-postgres-control-plane-readiness.sh`；生产设置
   `PG_READINESS_STRICT=1`，完整参数见 [deploy/ha/README.md](../../deploy/ha/README.md)。

预检 SQL 独立保存在 `postgres-control-plane-readiness.sql`，便于 DBA 先审 SQL 再执行。
`PG_EXPECTED_APP_CONNECTIONS` 应填写所有应用副本 Hikari 上限总和，`PG_CONNECTION_RESERVE`
用于预留运维、迁移和复制连接。

## 运行记录与原请求的保留期清理

`cleanup-success-instances.sql` 和 `cleanup-historical-failures.sql` 是直接清理入口，不会复制冷表。V292 后，热位点通过租户复合 FK 绑定 Pipeline；这两个脚本发现带位点或冷热Workflow关联的候选会在任何写入前以 SQLSTATE 55000 拒绝，psql 在 `ON_ERROR_STOP` 下非零退出。应由 Orchestrator 的运行树归档服务先锁定父/位点、复制并核对冷记录，再清热数据，不要跳过 FK 或手工先删位点来绕开拒绝。

两入口通过psql `-f scripts/db/cleanup-success-instances.sql`或`-f scripts/db/cleanup-historical-failures.sql`执行，连接参数须显式确认目标库；不要用stdin重定向导致相对include解析到错误目录。共用的[cleanup-trigger-request-retention.sql](./cleanup-trigger-request-retention.sql)仅供入口在同一事务中包含，不能独立执行。

- 成功窗口默认30日（`success_retention_days`），失败窗口默认1小时（`retention_interval`）；同时检查请求创建/最后修改和等待最后修改时间。窗口只是最低年龄，保留关联存在时继续留存。
- 单批原请求默认1000、参数`trigger_request_cleanup_batch_size`允许1..10000。未清完可按原入口重复推进；该限制不代表其它实例清理语句已分批。
- 活跃/近期等待、FORWARD_FAILED、冷热运行、Trigger outbox及计划/预期/misfire审计关联均排除；先锁原请求，再删到期终态等待，最后删无等待父。V294的NO ACTION外键拒绝绕过此顺序的父删除。
- SQL或参数失败使整个事务回滚并非零退出。执行前须停止对应写方并导出应保留证据；弱引用新增写入的全入口并发协议、全量wipe与生产规模查询代价仍待验收。

显式租户 wipe 与隔离 fixture 清理属于数据销毁，按各自原有目标范围先清位点再清父；需要保留恢复或取证数据时应先导出。相关边界见[高风险操作治理](../../docs/runbook/high-risk-operations.md)。

## 参考文档

- [docs/testing/README.md](../../docs/testing/README.md)
- [sql-script-usage-scenarios.md](./sql-script-usage-scenarios.md)

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [analyze-hot-tables.sql](analyze-hot-tables.sql) | 强制 ANALYZE 热表 / JSONB 列统计信息，让 PG planner 的 selectivity 估算保持稳定。 |
| [check-postgres-control-plane-readiness.sh](check-postgres-control-plane-readiness.sh) | 外置 PostgreSQL 控制面容量预检。SQL 只读，阈值与退出策略由本脚本负责。 |
| [cleanup/purge-permanent-deadletters.sql](cleanup/purge-permanent-deadletters.sql) | DANGER: 永久删除筛选命中的 DLQ 记录且不可恢复。执行前完成备份并复核租户和时间范围。 |
| [cleanup-dead-letter-task.sql](cleanup-dead-letter-task.sql) | 清理 dead_letter_task 死信表的历史记录,避免无限累积成事故黑洞。 |
| [cleanup-historical-failures.sql](cleanup-historical-failures.sql) | 清理累积的历史失败数据（FAILED / CANCELLED / TERMINATED job_instance 及其级联）， |
| [cleanup-job-execution-log.sql](cleanup-job-execution-log.sql) | 清理 job_execution_log 执行日志表,避免无限累积压死 INSERT 写入与 console 查询。 |
| [cleanup-orphan-general-job.sql](cleanup-orphan-general-job.sql) | 清理两类"永远跑不动"的异常数据： |
| [cleanup-outbox-events.sql](cleanup-outbox-events.sql) | 清理 outbox_event 及其 delivery/retry 子行的历史记录，避免无限膨胀拖慢 OutboxPollScheduler 的 SELECT。 |
| [cleanup-scheduled-occurrence-fire-retention.sql](cleanup-scheduled-occurrence-fire-retention.sql) | 按指定租户、截止时刻和批次日状态预览或清理实际触发观察记录。 |
| [cleanup-success-instances.sql](cleanup-success-instances.sql) | 直接清理历史成功的 job_instance 及其级联数据（本脚本不复制冷表）。和 cleanup-historical-failures.sql 互补： |
| [cleanup-trigger-outbox-events.sql](cleanup-trigger-outbox-events.sql) | 清理 trigger_outbox_event 表的历史事件,避免无限膨胀拖慢 TriggerOutboxRelay 扫描。 |
| [cleanup-workflow-runs.sql](cleanup-workflow-runs.sql) | 归档 workflow_run + workflow_node_run。和 cleanup-success-instances.sql 互补： |
| [config-cache-invalidation-role.sql](config-cache-invalidation-role.sql) | 为显式指定的可信 Console 主库角色授予或撤销 V372 缓存失效队列权限和专属 RLS 策略。 |
| [scheduled-occurrence-fire-roles.sql](scheduled-occurrence-fire-roles.sql) | 为指定的 Trigger 与 Console 主库角色授予或撤销实际触发观察记录表权限。 |
| [inspect/schema-capacity.sql](inspect/schema-capacity.sql) | 只读检查数据库结构容量，支持 Docker 容器和宿主机上的 psql 环境。 |
| [inspect/select-data-quality-evidence.sql](inspect/select-data-quality-evidence.sql) | 按精确租户/Job实例只读回查热表与归档表DQ执行证据；用于归档后诊断与恢复核对，不执行数据恢复写入。 |
| [inspect/schema-governance.sql](inspect/schema-governance.sql) | 只读数据库结构治理画像。 |
| [inspect/select-highest-flyway-success-version.sql](inspect/select-highest-flyway-success-version.sql) | 供 [inspect-schema-governance.sh](inspect-schema-governance.sh) 使用的配套 SQL。 |
| [inspect-schema-governance.sh](inspect-schema-governance.sh) | 数据库结构治理画像入口。只读查询，不执行 DDL/DML。 |
| [postgres-control-plane-readiness.sql](postgres-control-plane-readiness.sql) | 供 [check-postgres-control-plane-readiness.sh](check-postgres-control-plane-readiness.sh) 使用的配套 SQL。 |
| [preflight-job-instance-trigger-source.sql](preflight-job-instance-trigger-source.sql) | V136/V137 上线前预检:确认 job_instance 触发来源约束不会在 VALIDATE 阶段失败。 |
| [quartz-replacement-preflight-scan.sql](quartz-replacement-preflight-scan.sql) | Quartz → 时间轮替换 Pre-flight 扫描脚本 |
| [reset_platform_flyway_history.sh](reset_platform_flyway_history.sh) | 重置明确指定的本地平台数据库 Flyway 历史记录。 |
| [reset_platform_flyway_history.sql](reset_platform_flyway_history.sql) | DANGER: 清空平台库 Flyway 历史记录会破坏迁移状态。执行前备份库并确认只有一个应用执行 migration。 |
| [wipe-non-system-tenants.sql](wipe-non-system-tenants.sql) | DANGER: 批量删除所有非系统租户及关联数据。执行前必须核对数据库、备份和保留租户名单。 |
| [wipe-tenants.sql](wipe-tenants.sql) | DANGER: 批量删除租户及关联数据。执行前必须核对数据库、备份和目标租户清单。 |
