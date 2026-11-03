# 架构设计审查：状态所有权与模块边界

日期：2026-11-03。基线：后端仓库 `feature/tenant-coverage-packages`，HEAD `1bf95fe33`，工作区干净。方法：`project-engineering-review`（架构评估）+ `batch-scheduling-engineering`（领域维度）；只读静态审查，未修改代码、配置或默认值，未编译、测试、部署或连接任何环境。

## 范围与定位

[2026-11-02 架构适配性评估](architecture-suitability-assessment-2026-11-02.md)已给出整体方向判断（WorkflowRun 统一模型、DB 事实源 + 事务 Outbox + claim/report + 专用 Worker 方向合理），并把“按状态所有权收敛控制面，先梳理状态写入者与事务”列为第 3 项候选。本次审查承接这一项，不重复整体结论：

- **纳入**：模块依赖方向、平台库表的写入者矩阵、数据库账号与授权边界、Orchestrator 内部分层与职责密度、热路径与后台治理的资源隔离、现有架构守卫的覆盖面。
- **排除**：事件可靠性/容量/扩缩容/观测（11-02 已覆盖）、Console 限界上下文内部（已有严格 ArchTest）、前端、SDK 协议、逐文件 Bug 扫描。
- 结论等级：**有当前证据**（本基线源码/配置可定位）、**待验证**（需真实 PG 或目标环境）、**建议**。静态证据不代表运行时已发生故障。

## 总体结论

模块级依赖方向是干净的：运行时只依赖 `batch-common`（Worker 另依赖 `batch-worker-core`）；`console-api → batch-worker-atomic`、`orchestrator → batch-worker-sdk` 均为 `test` scope（`batch-console-api/pom.xml:240`、`batch-orchestrator/pom.xml:29`）。Trigger 只写自己的表，Console 运行态写入仅限其自有的 outbox/操作表。

主要问题不在“模块怎么划”，而在 **“谁可以写哪张表”只靠约定维持**：所有平台模块共用一个平台库账号，没有表级写入者守卫，同时有 6 张运行态表存在 Orchestrator 与 Worker 双写，其中 `pipeline_instance` 的 Worker 终态写入没有状态或 invocation 谓词。Orchestrator 内部则出现两套 service 包、贫血 domain 和承载业务规则的 infrastructure 调度器，可维护性随规模（约 7.5 万行）下降。

## 平台库表写入者矩阵（静态）

按 `src/main` 下 Mapper XML / Java 中 `insert/update/delete` 语句所在文件数统计，仅反映存在写路径，不区分列与条件。

| 表 | Orchestrator | Worker | Console | Trigger | 文档声明的所有者 |
|---|---|---|---|---|---|
| `job_instance` / `job_partition` / `workflow_run` / `workflow_node_run` / `outbox_event` | 写 | — | — | — | Orchestrator（[模块通信](../architecture/runtime-module-communication.md) §读图要点） |
| `job_task` | 3 | **2** | — | — | Orchestrator（同上） |
| `pipeline_instance` | 3 | **1** | — | — | 未声明 |
| `pipeline_step_run` | 4 | **1** | — | — | 未声明 |
| `file_record` / `file_dispatch_record` | 2 / 3 | **1 / 1** | — | — | “文件类平台表”，未声明唯一写入者 |
| `workflow_node`（定义） | 测试数据清理 | — | 写 | — | Console |
| `trigger_outbox_event` / `trigger_request` | 清理/归档 | — | — | 写 | Trigger |

## 系统问题台账

### ARCH-20261103-01 / P1 / 有当前证据：平台库共享单一账号，写入所有权无数据库层约束

- **证据**：Console、Trigger、Orchestrator 及 Import/Export/Process/Dispatch Worker 的平台数据源均读取 `BATCH_PLATFORM_DB_USERNAME`，Helm 只渲染一份（`helm/batch-platform/templates/configmap.yaml:157`，默认 `batch_user`，`helm/batch-platform/values.yaml:28`）。只有 ATOMIC 强制独立 Secret（`helm/batch-platform/templates/worker-atomic.yaml:12`），但该账号应具备的表级授权在仓库内没有定义。迁移中没有平台角色授权；现有授权脚本只按单张表零散补齐（`scripts/db/scheduled-occurrence-fire-roles.sql`、`scripts/db/config-cache-invalidation-role.sql`）。
- **触发与影响**：解析外部文件、调用外部渠道的平台 Worker 一旦出现实现错误、依赖漏洞或被攻陷，就能直接写 `job_instance`、`outbox_event`、租户配置和授权相关表，从而绕过 Orchestrator 的 CAS、终态保护和 Outbox 原子性；[安全模型](../architecture/security-model.md)对自托管 Worker 声明“不获得平台库全局写权限”，平台 Worker 则没有同等约束。这同时意味着下文 ARCH-02/03 的越界写入在数据库层不会被拦截。
- **建议**：以上面的写入者矩阵为输入，形成“模块 × 表 × 操作”授权清单，按 Worker → Console → Trigger 的顺序拆分角色；Helm 支持分模块凭据，默认值维持现状以保证兼容。复用已有 grant/revoke 脚本的写法，不新建框架。
- **验收**：在真实 PG 中用各模块角色执行越界写（例如 Import 角色 `update batch.job_instance`）得到 `permission denied`，各模块的正常路径集成测试通过；Helm 渲染测试覆盖分账号与回退两种配置。

### ARCH-20261103-02 / P2 / 有当前证据（运行影响待验证）：`pipeline_instance` 双写，Worker 终态写缺状态与 invocation 谓词

- **证据**：每个 `related_job_instance_id` 只有一行 `pipeline_instance`，重派时 upsert 复用并重置状态（`batch-worker/core/src/main/resources/mapper/PlatformFileRuntimeMapper.xml:188`）。Worker 的 `updatePipelineStage`、`markPipelineSuccess` 和 `markPipelineFailed` 只按 `where id = ?` 更新，既没有 `run_status` 谓词，也没有 invocation fence，且忽略返回值（同文件 `:214`–`:240`；`PlatformPipelineRunRepository.java:72`）。这些语句被登记为 tenant 谓词豁免（`WorkerCoreMapperXmlTenantGuardArchTest.java:20`）。Orchestrator 会把超时的 RUNNING 实例扫成 FAILED（`batch-orchestrator/src/main/resources/mapper/FileGovernanceMapper.xml:554`）。
- **触发路径**：① Worker 执行超过 `staleRunningFailSeconds` 后被扫成 FAILED，Worker 随后完成并写 SUCCESS，终态被覆盖；② lease 过期后被新 invocation 接管，同一行已被 upsert 重置为 RUNNING，旧 invocation 迟到的 `markPipelineFailed/Success` 会覆盖新执行的阶段和状态。
- **影响**：`pipeline_instance` 定位为观测，`job_task` 的状态仍由 Orchestrator 经 REPORT 收敛，因此当前没有证据表明作业结果会出错。受影响的是阶段进度展示、stale 统计和告警口径。ADR-038 阶段续跑依赖 `pipeline_step_run` 的 SUCCESS 记录；旧 invocation 是否可能写入误导跳过判断的记录，需要专项确认。多分区共享行的问题已有降级守卫（`AbstractStageExecutor.java:62`），不在此列。
- **建议**：交由 `code-review-and-gates` Bug 专项，用真实 PG 复现路径 ①②。确认后，Worker 写入加上 `run_status` 非终态谓词，并携带 invocation/attempt fence（可参考 `ProcessInvocationFenceMapper.xml` 现有的 `current_invocation_id` 写法），返回 0 时记录 stale 写入，不抛错。
- **验收**：两条竞态路径各有一条先失败后通过的真实 PG 测试；Mapper 契约测试断言终态集合与 `PipelineRunStatus` 一致。

### ARCH-20261103-03 / P3 / 有当前证据：Worker 写 Orchestrator 所有的 `job_task` 辅助列，与文档口径不一致

- **证据**：Process 写 `job_task.process_staging_generation_id/process_staging_batch_key`（`batch-worker/process/src/main/resources/mapper/ProcessInvocationFenceMapper.xml:57`），Dispatch 写 `job_task.external_outcome_resolution_consumed_at`（`batch-worker/dispatch/src/main/resources/mapper/FileDispatchMapper.xml:228`）。两者都有 RUNNING/invocation/授权 CTE 谓词，不改 `task_status`，实现本身是谨慎的。
- **问题**：[模块通信](../architecture/runtime-module-communication.md)把 `job_task` 列为 Orchestrator 核心表，并写明 Worker “不直接接管调度状态主表”，但没有登记这两类例外；也没有守卫阻止今后在 Worker 中写 `task_status`。
- **建议**：二选一由维护者裁定。A：在文档中登记“Worker 可写的 `job_task` 列白名单”，并配一个 Mapper XML 守卫，在 Worker 模块中只允许这些列。B：把这些列迁到 Worker 自有的伴生表。推荐 A，改动小，也不涉及迁移。
- **验收**：守卫能拦截在 Worker Mapper 中 `update batch.job_task set task_status` 的反例，并放行现有两处。

### ARCH-20261103-04 / P3 / 建议（容量待验证）：claim/report 热路径与后台治理共用一个连接池

- **证据**：Orchestrator 只有一个平台 Hikari 池（默认 50，`batch-orchestrator/src/main/resources/application.yml:45`），注释写明 Outbox 轮询、状态写入、ShedLock 和查询共用。BatchDay 开日/结算、文件治理、归档、补偿、重放扫描都在同一进程中运行，没有 role 开关；仓库内唯一可选的 `@ConditionalOnProperty` 属于 sensor/quota/lineage。
- **影响**：大批量开日、归档或重放扫描占满连接时，Worker 的 claim/renew/report 会排队，可能引发 lease 过期和重派放大。这是风险推断，没有压测证据。
- **建议**：先在现有容量验证中加入“后台扫描峰值 + claim/report 吞吐”组合场景，观察 Hikari pending 和 renew 延迟。有证据后，优先为后台治理配置独立的小池或信号量；不建议先拆进程（与 11-02 第 3 项一致）。

## 代码规约与可维护性台账

| ID | 位置 | 问题 | 建议 / 关闭条件 |
|---|---|---|---|
| CONV-20261103-01 | `orchestrator/service/`（21 个文件，约 5.6k 行）与 `orchestrator/application/service/`（约 34k 行） | 两套 service 包并存。`DefaultLaunchService`、`BatchDay*Service`、`DefaultWorkerRegistryService` 仍在旧包，BatchDay 逻辑分散在 `service/`、`application/service/batchday/` 和 `infrastructure/scheduler/` 三处 | 随相关功能改动逐步迁入 `application/service/<ctx>`，不做一次性搬迁；补一条 ArchTest 禁止新文件进入旧包 |
| CONV-20261103-02 | `infrastructure/scheduler/BatchDayOpenScheduler.java`（758 行，直接注入 10 个 Mapper 和 `PlatformTransactionManager`）；`infrastructure/scheduler` 合计约 5.7k 行 | 业务日开日规则写在基础设施调度器中，调度入口与业务规则耦合，难以脱离调度器测试 | 调度器只保留触发、加锁和分批，业务步骤下沉到 `application/service/batchday`；以现有测试保护行为 |
| CONV-20261103-03 | `orchestrator/domain`：202 个文件、约 5.6k 行（平均 28 行，以 record/enum 为主） | domain 较薄，规则集中在 900–1350 行的应用服务中（`DefaultCompensationService`、`BatchDayReplayService` 等） | 不建议为“充血”而重构；只在拆分大服务时，把纯规则（状态迁移判定、窗口计算）提取为可单测的 domain 对象 |
| CONV-20261103-04 | `config/WorkflowSensorDataSourceConfiguration.java:55` | 主平台数据源 `orchestratorPlatformDataSource` 定义在名为 WorkflowSensor 的配置类中 | 改名或拆分，属于纯命名问题 |

## 治理控制缺口

| 控制 | 现状 | 缺口 |
|---|---|---|
| 模块依赖方向 | Maven 依赖与 scope 正确；Console 有严格的限界上下文 ArchTest | 无 |
| 层次边界 | Orchestrator 有 `ApplicationBoundaryArchTest` 等 8 个 ArchTest | 不阻止新文件进入旧 `service/` 包（CONV-01） |
| 表写入所有权 | 只有文档声明 | **既没有自动守卫，也没有数据库授权**（ARCH-01/03）；各 Worker 的 `MapperXmlTenantGuardArchTest` 只检查 tenant 谓词，不检查表的归属 |
| 终态保护 | 已有 AGENTS 约定和 JobInstance 状态 Mapper 测试 | `pipeline_instance` 的写入没有终态谓词约定，也不在测试范围内（ARCH-02） |

## 交叉映射

| 系统问题 | 关联规约 | 关联治理缺口 |
|---|---|---|
| ARCH-01 | — | 表写入所有权缺少数据库层控制 |
| ARCH-02 | — | 终态保护未覆盖 pipeline；tenant 豁免清单无人复核 |
| ARCH-03 | — | 表写入所有权缺少自动守卫 |
| ARCH-04 | CONV-02（调度器承载业务，难以单独限流） | — |

## 优先级与实施顺序（建议，未授权实施）

1. **ARCH-02** 进入 Bug 专项复现，范围最小，结论直接决定是否需要修复。
2. **ARCH-03 方案 A**：补列白名单和守卫，小改动，也为 ARCH-01 提供可执行的所有权清单。
3. **ARCH-01**：先完成授权清单和 Worker 角色，再处理 Console/Trigger。涉及部署配置和真实 PG 验证，需要确认目标环境。
4. **ARCH-04**：并入现有容量验证计划，有证据后再决定是否改池。
5. CONV-01..04 随相关功能改动推进，不单独排期。

## 整改进度（2026-11-03）

分支 `fix/arch-20261103-state-ownership`：ARCH-02 见 `9823a4aa1`，ARCH-03/CONV-01 见 `6591ae43f`；尚未推送或开 PR。

| ID | 状态 | 变更 | 验证 |
|---|---|---|---|
| ARCH-02 | **已确认并修复** | Worker 对 `pipeline_instance` 的阶段、补偿和终态写入，统一加上非终态谓词和 `started_at` 运行代际围栏。`insertPipelineInstance` 改为 `insert … returning id, started_at`（`<select affectData>`，遵守 generated key 只回传 `id` 的门禁），`PipelineRunFence` 贯穿 adapter、stage executor 和补偿 hook；返回 0 时记 warn，不抛错。不需要迁移。`pipeline_step_run` 已有 `step_status in (RUNNING, RETRYING)` 谓词，并且每次 attempt 新开一行，不在本次修改范围内 | 真实 PG：`PlatformFileRuntimeMapperStageSkipIntegrationTest` 新增路径①（stale sweep 后迟到成功）、路径②（重派后旧 invocation 迟到写入）以及按 `PipelineRunStatus` 全量枚举的前置态契约用例。修复前 3 条失败，修复后 19/19 通过 |
| ARCH-03 | **已按方案 A 修复** | 新增 `BaseWorkerJobTaskWriteGuardArchTest`（batch-test-support），六个 Worker 模块各有一个 `*JobTaskWriteGuardArchTest`；列白名单登记在[模块通信](../architecture/runtime-module-communication.md#读图要点) | `WorkerJobTaskWriteGuardConventionTest` 覆盖拦截 `set task_status`、CTE/`<select>` 包装、动态标签、insert/delete，并放行现有 Process/Dispatch 写法；六个模块的守卫均通过 |
| CONV-01 | **守卫已加** | `LegacyServicePackageFreezeArchTest` 冻结旧 `orchestrator.service` 包内现有的 21 个类，存量迁移随功能推进 | Orchestrator `*ArchTest` 16/16 通过 |
| ARCH-01 / ARCH-04 / CONV-02..04 | 未处理 | ARCH-01 涉及 Helm 分账号和真实 PG 授权，需要先确认目标环境；ARCH-04 需要容量证据 | — |

实际执行的命令（本机 Docker/Testcontainers）：

- `./mvnw -pl batch-test-support,batch-worker/{core,import,export,process,dispatch,atomic} -am install`：BUILD SUCCESS，0 失败（按 reactor 顺序：common 725、test-support 81、core 426、import 401、export 246、process 193、dispatch 263、atomic 264）。
- `./mvnw -pl batch-orchestrator -Dtest='*ArchTest' test`：16/16 通过。未运行 Orchestrator 全量测试，因为它的生产代码没有改动。
- `./mvnw -pl batch-worker/core,batch-test-support,batch-orchestrator spotless:check pmd:check`、`check-test-config-sources.py`、`check-java-readability.py`：通过。
- 未执行：CI Full Gate、staging 竞态复现、Console/前端验证（契约未变）。

## 未覆盖与未验证

- 未连接任何数据库，无法确认目标环境实际使用的账号与授权；ARCH-01 只说明仓库默认值和模板现状。
- 写入者矩阵来自文本搜索，可能漏掉动态 SQL 或 JDBC 模板写法；也没有统计读路径。
- ARCH-02 的竞态窗口没有复现；ARCH-04 没有容量数据。
- 未审查 Console 内部、SDK、前端，也未覆盖 11-02 已评估的事件可靠性与扩缩容维度。
