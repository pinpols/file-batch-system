# Worker 平台库受限角色本地演练

日期：2026-11-04。基线：`main` `e5d2d140e`（#1309 已合并的 Worker 授权清单与 `scripts/db/platform-worker-roles.sql`），演练 harness 在分支 `fix/worker-restricted-role-drill`。环境：本机 Docker/Testcontainers（PostgreSQL 17.11、Kafka、对象存储），一次性容器，未连接共享或目标环境。

## 目的与范围

[架构设计审查](../audit/architecture-design-review-2026-11-03.md)的 ARCH-20261103-01 在 #1309 落地了 Worker 最小授权，但证据只覆盖授权脚本本身（`PlatformWorkerRolesSqlIntegrationTest`）。目标环境切换前最大的风险是生成器漏掉某条写路径，切换后 Worker 报 `permission denied`。本次让五类 Worker 以生成的受限角色跑真实流水线，观察是否有被拒绝的语句。

不在范围：目标环境切换、Console/Trigger/Orchestrator 分账号（ARCH-01 后半）、ARCH-04 容量。

## 方法

`batch-test-support` 新增 opt-in 演练（`PlatformWorkerRoleDrill`），以 `-Dbatch.drill.platform-worker-role=<worker>` 启用，只作用于 `AbstractIntegrationTest` 的 Spring 上下文：

1. 平台库连接仍以 owner 登录。Spring 的 Flyway 迁移完成后，在容器内用 psql 执行**已提交的** `platform-worker-roles.sql`，给 `drill_worker_<worker>`（NOLOGIN、非超级用户、无 BYPASSRLS）授权。
2. 每条 JDBC 语句执行前按调用栈选角色：Worker 包发起的语句 `SET ROLE` 到演练角色；测试 fixture、断言、Flyway（含回调）和 Orchestrator/Trigger/Console 发起的语句保持 owner。只有 `batch-common` 等共享帧时，独立 Worker 应用按 Worker 处理，E2E 共享上下文无法归属则保持 owner 并计入 `unattributed`。
3. 这样触发器、RLS、行锁和序列都按受限角色校验，而 fixture 写 Orchestrator 所有的表不会混入结论。权限拒绝（42501）、授权前执行的 Worker 语句和每个 JVM 的执行计数写入 `target/platform-worker-role-drill/<worker>.tsv`。

E2E 中 Orchestrator 与 Worker 共用一个上下文和平台库数据源，因此 Orchestrator 发起的语句不在受限范围内；本次结论只针对 Worker。

## 结果

| Worker | 用例 | 结果 | 受限角色执行语句数 | 拒绝 | 无法归属 |
|---|---|---|---|---|---|
| Import | `ImportPipelineE2eIT`、`ImportFailurePipelineE2eIT` | 11 + 2 通过 | 680 + 92 | 0 | 0 |
| Export | `ExportPipelineE2eIT`、`ExportFailurePipelineE2eIT` | 4 通过 | 22 + 134 | 0 | 0 |
| Process | `ProcessPipelineE2eIT`、`ProcessFailurePipelineE2eIT` | 8 通过 | 39 + 417 | 0 | 0 |
| Dispatch | `DispatchPipelineE2eIT`、`DispatchFailurePipelineE2eIT` | 16 通过 | 12 + 486 | 0 | 0 |
| Atomic | `AtomicTaskPipelineE2eIT`、`AtomicMixedScenarioE2eIT` | 12 通过，1 错误 | 77 + 13 | 4 | 0 |
| Process（模块级，仅应用装配） | `BatchWorkerProcess*IntegrationTest`、`ProcessStagingCleanupYamlIntegrationTest` | 7 通过 | — | 0 | — |

同一 Worker 的两个计数来自不同的测试 JVM。模块级首轮暴露了一处分类错误：Flyway 回调 `SecretPayloadFlywayCallback` 被当成 Worker 语句；已改为调用栈中出现 Flyway 即按迁移 owner 处理，E2E 各轮未再出现授权前语句。

**结论**：Import、Export、Process、Dispatch 在成功与失败两条流水线上没有任何被拒绝的语句，未发现生成器遗漏的写路径，无需修改授权清单。

## 发现：Atomic SQL/存储过程任务默认落到平台库

- **现象**：`AtomicMixedScenarioE2eIT` 的 SQL 任务写 `batch.e2e_mixed_sql_calls`、存储过程任务调用的 `batch.e2e_mixed_proc()` 写 `batch.e2e_mixed_proc_calls`，在受限角色下被拒绝（`SqlTaskExecutor#runStatements`、`StoredProcTaskExecutor#execCallableStatement`），用例失败。这两张表是测试为用户任务准备的目标表，不是平台状态表。
- **根因**：任务参数不带 `dataSourceBean` 且未配置执行器级数据源时，`AtomicConnectionManager.resolveDataSourceBeanName` 返回 `null`，`resolveDataSource` 回落到上下文默认 `DataSource`（`SqlTaskExecutor.java:86`）。Atomic 进程的默认数据源就是平台库。`allowed-data-source-beans` 白名单只校验显式传入且不同于默认值的 bean，不约束这个回落。
- **影响**：
  - 当前（共享 `batch_user`）：启用 SQL/存储过程执行器后，用户任务默认可以用平台库凭据读写任意平台表，绕过 Orchestrator 的状态保护。生产 overlay 默认关闭这些执行器并要求白名单，是现有缓解。
  - 切换到 Atomic 受限角色后：这类任务会因权限不足失败，同时仍可读写 Atomic 角色被授予的平台表（如 Worker 报告发件箱）。
- **需要裁定**：是否禁止 SQL/存储过程任务回落到平台库，例如在启用执行器时要求显式配置非平台数据源，由 `AtomicExecutorProductionGuard` 在启动时拒绝。这会改变现有任务的兼容性，未擅自修改。在裁定前，Atomic 的目标环境账号切换应先核对现网 SQL/存储过程任务是否依赖默认数据源。
- **裁定与处置（2026-11-04）**：禁止回落。`AtomicExecutorProductionGuard` 在 SQL/存储过程执行器启用时，要求 `data-source-bean-name` 显式指向业务库，且默认值与 `allowed-data-source-beans` 都不得含平台库 bean `dataSource`；生产类 profile 启动失败，dev/local 只告警（本地与 E2E 仍按平台库运行）。验证：`AtomicExecutorProductionGuardTest` 19 项通过，其中新增 4 项先红后绿。生产 Helm 默认关闭这两类执行器，Compose 使用 local profile，现有部署不受影响；Atomic 尚无内置业务库 bean，生产启用前需部署方提供，目标环境未验证。

## 执行的命令

```bash
./mvnw -ntp -pl batch-e2e-tests -Dbatch.drill.platform-worker-role=<worker> \
  -Dtest='<用例>' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true test
./mvnw -ntp -pl batch-worker/process -Dbatch.drill.platform-worker-role=process \
  -Dtest='BatchWorkerProcess*IntegrationTest,ProcessSpiEquivalenceIntegrationTest,ProcessStagingCleanupYamlIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true test
./mvnw -q -ntp -pl batch-test-support spotless:check pmd:check
```

前置：`./mvnw -pl batch-e2e-tests -am install -DskipTests`（上游模块均已安装；`batch-e2e-tests` 自身无制品，install 阶段报错不影响上游）。

## 未覆盖

- 模块级 Mapper 集成测试自建 DataSource，不经过 Spring 上下文，未纳入演练；它们的 SQL 由生成器静态推导覆盖。
- 只覆盖上述 E2E 场景触达的 Worker 路径：重放、分区 reclaim、补偿开关、业务分片路由切换、ShedLock JDBC 提供者等未逐一触发。E2E 中 ShedLock 使用测试配置，计入 Orchestrator 或无法归属。
- 未验证目标环境的账号、Secret 与 Helm 分账号渲染后的真实运行；未运行 CI。
