# 控制面平台库受限角色本地演练

日期：2026-11-05。基线：`main` `e0271d4c2`，分支 `feat/control-plane-db-roles`（控制面授权生成、Flyway owner 账号与演练 harness）。环境：本机 Docker/Testcontainers（PostgreSQL 17、Kafka、Redis、对象存储），一次性容器，未连接共享或目标环境。

## 目的与范围

[架构设计审查](../audit/architecture-design-review-2026-11-03.md)的 ARCH-20261103-01 后半：Console/Trigger/Orchestrator 共用平台库 owner 账号。本次由各自 Mapper XML 与内联 SQL 生成运行时角色授权（`scripts/db/platform-control-plane-roles.sql`），并让三个模块的真实集成测试以这些角色运行，找出生成器漏掉的访问和受限角色下才暴露的问题。

不在范围：目标环境切换、ARCH-04 容量、E2E 多模块共享上下文（共享帧无法按模块归属）。

## 方法

- `-Dbatch.drill.platform-control-plane-role=true` 启用 `PlatformWorkerRoleDrill` 的控制面模式：Flyway 迁移后创建 `drill_cp_console`、`drill_cp_trigger`、`drill_cp_orchestrator`（NOLOGIN、非超级用户、NOBYPASSRLS），执行控制面授权脚本，Console 角色再执行 `config-cache-invalidation-role.sql`。
- 每条语句按调用栈归属：首个 `io.github.pinpols.batch.{console,trigger,orchestrator}` 帧决定 `SET ROLE` 到对应角色；Flyway、测试代码保持 owner；只有共享帧时，单模块应用按该模块执行。
- Trigger 测试 profile 的 `spring.quartz.jdbc.schema` 会在迁移后删除并重建 Quartz 表，harness 在 Quartz 初始化后重新授权。
- 权限拒绝（42501，包括 RLS `WITH CHECK` 拒绝）记录到 `target/platform-worker-role-drill/control-plane.tsv`。
- 本机 `batch_user` 为 SUPERUSER + BYPASSRLS，owner 执行时 RLS 不生效；受限角色下 RLS 按 `app.tenant_id` 实际校验。

## 结果

| 模块 | 用例 | 受限角色执行语句数 | 拒绝（修正后） | 测试结果（演练 / 不演练） |
|---|---|---|---|---|
| Trigger | 模块全部测试 397 项 | 31 401 | 1（测试直调 Mapper，未设租户上下文） | 394 通过 / 3 失败；失败用例均为测试自身机制 |
| Console | 模块全部测试 2 944 项 | 85 005 | 2（`job_dependency` RLS，见发现一） | 2 899 通过 / 43 失败；同 9 个类不演练时 137 项全部通过 |
| Orchestrator | 模块全部测试 2 447 项；失败的 7 个类修正后复跑 183 项 | 25 101（复跑） | 0 | 整模块 2 435 通过 / 12 失败；复跑 175 通过 / 8 失败；同 7 个类不演练时 183 项全部通过 |

首轮演练暴露的生成器缺口均已修正并重跑验证：

- `<sql>` 复用片段与 `<include>` 未解析、`<choose>` 选择的表只取第一个（终态通知归档冷热表）；
- MyBatis `#{x, jdbcType=…}` 内逗号被当作赋值分隔，产生不存在的列 `jdbctype`；
- 触发器函数以调用者身份执行，未授予函数体访问的表（`trigger_request` 准入守卫读 `batch_day_instance`）；
- `FOR UPDATE OF <别名>` 按最后一个 `FROM` 推断，锁错表（`workflow_terminal_notification_receipt`）；
- “仅锁”权限假定存在 `updated_at`，改为缺列时退回主键首列；
- Orchestrator 启动期 `ArchiveSchemaDriftCheck` 经 `information_schema` 比对冷热表列，受限角色只看到有权限的列，会误报漂移并阻断启动；改为按 `ARCHIVED_TABLES` 为每对冷热表授予只读权限（复跑后两项漂移测试通过）。

修正后 Worker 授权清单只有“仅锁”条目格式变化，实际权限不变。`TerminalNotificationArchiveIntegrationTest` 在修正后的授权下演练 11 项全部通过、无拒绝。

### 测试失败分类

| 类别 | 用例 | 结论 |
|---|---|---|
| 测试自建 NOBYPASSRLS 角色并自行 `SET ROLE`/`RESET ROLE`，被演练的逐语句切换覆盖 | Trigger `TriggerServiceIntegrationTest`（2）；Console `BatchDayCatchUpOperationSchema`、`ConfigCacheInvalidationOutbox`、`ConsoleUsageDaily`、`TenantConfigDependencyRls`；Orchestrator 中带 `NonBypassRlsRole` 的用例与 `JobNodeDispatchIntegrationTest.capacityWait_shouldEnforceRealTenantRowSecurity` | 演练产物 |
| 测试直接调用 Mapper，未走生产代码设置租户上下文的路径 | Trigger `ScheduledOccurrenceMapperIntegrationTest.shouldRecordDistinctActualFiresIdempotently`（生产 `ScheduledOccurrenceRecorder` 先 `setTenantContext`） | 演练产物 |
| 只读副本使用独立数据库，没有演练角色 | Console `ReadReplicaHappyPathIntegrationTest` | 演练产物 |
| 测试直接调用 Mapper 读写 RLS 表，未走设置租户上下文的生产路径 | Orchestrator `BatchDayMetricsMapperIntegrationTest.shouldCountOpenExpectedWorkForExactCalendarAndBizDate` | 演练产物 |
| `job_dependency` RLS 拒绝 | Console `ConsoleJobDefinitionMutation`、`ConsoleTenantConfigPackageExcelUpload`（2）、`ConsoleTenantCoveragePackages`（2） | **真实问题**，见发现一 |
| 跨租户清理依赖绕过 RLS | Orchestrator `AdminTestDataCleanupControllerIntegrationTest`（3） | **真实问题**，见发现二 |

## 发现一：作业依赖同步触发器在无租户上下文时被 RLS 拒绝

- **现象**：Console 创建或修改作业定义的 `depends_on_job_code`、以及租户配置包应用作业依赖时返回 500。`batch.job_definition` 未启用 RLS，但其触发器 `trg_sync_legacy_job_dependency`（`batch.sync_legacy_job_dependency()`，非 SECURITY DEFINER）向强制 RLS 的 `batch.job_dependency` 写行；这些写路径没有在事务中设置 `app.tenant_id`，`WITH CHECK` 拒绝。
- **为什么之前没暴露**：本机和测试的平台库账号是 SUPERUSER + BYPASSRLS，RLS 被绕过。
- **影响**：凡平台库账号不能绕过 RLS 的部署都会失败，包括本次让 Console 改用受限运行时角色。它是 Console 切换受限角色的前置阻塞项；Trigger/Orchestrator 不受影响。
- **不止写入**：同一 Mapper 的列表、详情、依赖环路校验（`selectAllDependencies`、`lockDependencyGraph`）与配置包预览校验也读取 `job_dependency`；无租户上下文时普通角色读到空结果，环路校验会把已有依赖当作不存在。
- **处置（2026-11-06）**：Console 改为集中绑定。`ConsoleTenantGuard.resolveTenant` 在解析出租户后经 `TenantDatabaseContext` 向当前平台库事务写入 `app.tenant_id`（不在事务内或事务属于其他数据源时不做任何事）；作业定义详情与配置包 `preview` / `patchRow` / `downloadPreviewWorkbook` 补事务，使绑定对其读取生效。配置包 `apply` 经 `loadSession` 走租户守卫，已在事务内绑定。平台库账号仍可绕过 RLS 时行为不变。
- **验证**：新增 `ConsoleJobDependencyTenantContextIntegrationTest`，让 Console 全部平台库连接切换为 NOBYPASSRLS 普通角色，经 HTTP 覆盖创建、修改、详情、列表依赖筛选、复制与环路拒绝；修复前两项用例均因 `job_dependency` RLS 拒绝失败（500），修复后通过。演练模式复跑首轮失败的配置包上传、覆盖包与作业定义写路径 58 项全部通过、0 拒绝。

## 发现二：测试数据清理接口依赖绕过 RLS

- **现象**：Orchestrator 管理端测试数据清理（`AdminTestDataCleanupController`）删除 `trigger_request` 时违反 `workflow_launch_wait` 的外键，返回 500。
- **原因**：清理按租户前缀或精确租户跨租户删除，没有设置 `app.tenant_id`；受限角色下对强制 RLS 表的 `DELETE` 被策略静默过滤为 0 行，下游引用行未删除，随后删除父行触发外键。owner 绕过 RLS 时不出现。
- **结论（2026-11-06）**：不作为切换阻塞项。该接口按租户前缀或租户列表一次删除多个租户，单租户 `app.tenant_id` 的 RLS 策略本就无法覆盖；而 `AdminTestDataCleanupService.rejectProductionProfile` 已在 prod/staging/uat/preprod 及未识别 profile 下拒绝调用，只在 dev/local/test 以 owner 账号运行。受限运行时角色面向生产类环境，因此保留为 owner 执行的开发测试工具，不改代码。

## Orchestrator 补充

整模块演练以 `There was a timeout in the fork` 结束，fork 被 surefire 终止，退出钩子未写汇总行；拒绝行在发生时即时写入，本轮没有产生任何拒绝行。单类复跑可正常生成汇总（例如 `OutboxReferenceIntegrityIntegrationTest` 216 条受限语句、0 拒绝）。失败用例的演练与不演练对照见“执行的命令”。

## 执行的命令

```bash
./mvnw -o -pl batch-test-support install -DskipTests
./mvnw -o -pl <batch-trigger|batch-console-api|batch-orchestrator> test -Djacoco.skip=true \
  -Dbatch.drill.platform-control-plane-role=true
# 失败类不演练对照
./mvnw -o -pl batch-console-api test -Djacoco.skip=true -Dtest='<失败类>' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl batch-orchestrator test -Djacoco.skip=true -Dtest='<失败类>' -Dsurefire.failIfNoSpecifiedTests=false
```

## 未覆盖

- E2E 多模块共享上下文未演练；模块级测试未覆盖的运行路径只由生成器静态推导。
- 测试自建角色的 RLS 用例在演练下不具判定力，其结论以不演练运行为准。
- 未验证目标环境账号、Secret 与 Helm 分账号的真实运行，未运行 CI。
