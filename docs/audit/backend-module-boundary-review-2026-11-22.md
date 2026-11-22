# 后端模块与功能职责边界审查（2026-11-22）

基线为后端 `main` 的 `37a817471`（#1455），配对前端契约抽查基线为 `main` 的 `6cd8aa7fa`。本轮核对 10 个平台生产模块（3 个控制面、5 类 Worker、2 个共享模块）、五语言 SDK、测试支撑、数据库运行角色及 Console 契约。以 [项目结构](../architecture/project-structure.md)、[运行模块通信](../architecture/runtime-module-communication.md) 和 [系统边界](../architecture/system-scope-boundary.md) 为参照，从 POM、运行入口、Mapper、角色授予和配置门禁核对实际所有权。前端只核对相关 API 消费者。

## 发现

### P2 · 保存 Workflow 图可以覆盖已完成的定时暂停（BOUNDARY-20261122-01）

Console 的画布保存先读定义，将读取到的 `schedule_enabled` 放入全量更新，更新时只比较定义 `version`。Console 定时开关和 Trigger 运维暂停均能单独写开关而不推进该版本。交错顺序为画布读取 `7/true`，暂停落库 `7/false`，画布保存仍通过比较并写回 `8/true`。隔离 PostgreSQL 17.11 分别执行了两条暂停 Mapper 和同一条保存 Mapper，观察到两种入口都能覆盖暂停，第二租户数据保持原值。Quartz 对账会从持久开关读取启用态，因而处于暂停的后续定时触发有被恢复的风险。本轮未实测重新注册或真实首个 fire。[保存服务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/DefaultWorkflowDefinitionService.java) · [Console 更新 Mapper](../../batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml) · [Trigger 启停 Mapper](../../batch-trigger/src/main/resources/mapper/TriggerDefinitionMapper.xml) · [Trigger 对账](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerReconciler.java)

整改候选为将画布更新从定时开关写路径剥离，保存和版本快照按保存后的实际开关取值。保留显式启停入口，并明确配置包导入何时可改开关。验收用真实 PostgreSQL 交错暂停与保存、恢复与保存，并回读设置、快照和 Quartz 结果。确认新定时火点受暂停约束，已产生的补触发义务按既有策略恢复。

### P2 · 文件状态的旧 Worker 写入能覆盖归档（BOUNDARY-20261122-02）

治理侧从读取到的状态发起条件更新，Worker Core 则在 Java 先读状态并校验，SQL 按文件 ID 直接写。两侧验证的时间不同。用真实 PostgreSQL 执行 Worker 读取 `RECEIVED`、治理更新 `ARCHIVED`、Worker 写 `PARSING` 的 Mapper 序列，确认最终为 `PARSING`，尽管状态机只允许归档记录走向删除。该路径可使文件状态与治理审计不一致。探针覆盖持久化层，完整归档入口还有活跃 Pipeline 和派发检查，整链触发时序仍待验证。[Worker 校验和写入](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java) · [Worker Mapper](../../batch-worker/core/src/main/resources/mapper/PlatformFileRuntimeMapper.xml) · [治理条件更新](../../batch-orchestrator/src/main/resources/mapper/FileGovernanceMapper.xml) · [文件状态机](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/FileStateMachine.java)

整改候选为在 Worker 更新中使用读取到的前置状态，并让调用者处理条件更新未生效。先追 Import 阶段、Dispatch 回执与失败收口，确定同态幂等、真实冲突和上下文失效各自的返回语义，再决定是否需要接入既有执行代际。验收以并发下的状态、审计、文件是否交付和重派结果为准，覆盖管理员归档与 Worker 迟到写入。

### P3 · Trigger 对 Workflow 定义表的授权超出当前写职责（BOUNDARY-20261122-03）

Trigger 的当前写语句只更新 `schedule_enabled` 和 `updated_at`。运行账号授权脚本给 `workflow_definition` 整表 `UPDATE`，使调度表达式、归属和定义版本等列也落入 Trigger 写权限。Trigger 还用 `FOR SHARE` 锁定定时接纳和外部事件候选，需要保留兼容的行锁权限。用隔离 PostgreSQL 复刻该授权，`has_column_privilege` 确认当前可更新 `schedule_expr`。换为两列授权后，示例角色无法更新表达式，`FOR SHARE` 和暂停更新仍可执行。真实环境角色、继承和完整授权脚本未运行。[Trigger 授权脚本](../../scripts/db/platform-control-plane-roles.sql) · [Trigger 定义写入](../../batch-trigger/src/main/resources/mapper/TriggerDefinitionMapper.xml) · [触发接纳行锁](../../batch-trigger/src/main/resources/mapper/TriggerRequestMapper.xml)

整改候选为让定义表授予反映字段职责，覆盖存量整表授权的撤销和重新授权。使用实际拆分账号跑定时接纳、外部事件接纳和启停，再断言表达式与版本字段无更新权限。

## 职责矩阵

| 模块 | 主责及实际交接点 | 核对证据 |
|---|---|---|
| Console API | 管理定义、租户、权限、审批、通知和运维查询。实际执行命令通过内部 HTTP 交给 Trigger 或 Orchestrator，运行表可作租户内查询。告警人工处理、通知回执与 Orchestrator 自动解除共用业务告警记录。 | [控制面分层](../architecture/project-structure.md#console-分层职责结论2026-09-28) · [定义开关入口](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/web/ConsoleWorkflowDefinitionController.java) · [告警状态更新](../../batch-console-api/src/main/resources/mapper/AlertEventMapper.xml) |
| Trigger | 管 Quartz、触发接纳、发生记录、misfire 和补触发。触发请求与发布意图同事务，异步交给 Orchestrator。为回执和连贯性投影读取关联的 Job/Workflow 运行信息。 | [接纳与转发](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerRequestPersistence.java) · [外部事件运行回执](../../batch-trigger/src/main/resources/mapper/ExternalEventDeliveryMapper.xml) |
| Orchestrator | 管业务日、容量准入、Workflow 节点、Job 与分片、CLAIM/REPORT、租约、持久化重试、补偿重放、派发 Outbox 和治理恢复。对 `trigger_request` 回写接纳/运行结果，也创建补偿、节点、业务日补触发等内部请求。 | [启动消费](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/trigger/TriggerLaunchConsumer.java) · [任务处理入口](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskControllerApplicationService.java) · [请求修复及回写](../../batch-orchestrator/src/main/resources/mapper/TriggerRequestMapper.xml) |
| Worker Core | 消费、认领、续租、取消、回报及 REPORT Outbox。维护文件登记、Pipeline 观察状态及运行代际。Orchestrator 维护任务终态并处理 Pipeline 超时收口与归档。 | [HTTP 客户端](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java) · [运行代际](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PipelineRunFence.java) |
| Import | 入站扫描和认领、解析、校验、加载及位点。配置/业务日归控制面，文件执行写入归 Worker。 | [入站扫描](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) |
| Export | 查询分页、文件生成、恢复和持久化产物，Orchestrator 根据回报推进任务与 Workflow。 | [生成](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java) · [存储登记](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java) |
| Process | 暂存代际、计算、校验和业务库提交。以租户、任务和调用代际谓词轮换 `job_task` 中专用暂存列。 | [业务提交](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/CommitStep.java) · [代际围栏](../../batch-worker/process/src/main/resources/mapper/ProcessInvocationFenceMapper.xml) |
| Dispatch | 交付、回执、补偿及渠道适配。消费 Orchestrator 已批准的外部结果裁定时，只回写专用消费标记。 | [交付](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java) · [派发 Mapper](../../batch-worker/dispatch/src/main/resources/mapper/FileDispatchMapper.xml) |
| Atomic | 运行受控原子执行器，处理业务侧副作用与结果不明，调度层据其结果继续裁定。 | [HTTP 原子执行](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java) |
| Common | 公共协议、模型、配置、安全和基础能力。直接 Maven 依赖图未发现生产模块反向依赖，Console 自有 DTO 留在 Console。 | [Common POM](../../batch-common/pom.xml) · [项目依赖守卫](../../scripts/ci/check-dependency-boundaries.py) |
| Java、Go、Python、TypeScript、Rust SDK | 自托管 handler、HTTP/Kafka 接入、租户侧去重和消费代际，平台负责任务状态。Java Spring 负责自动装配，testkit 提供 SDK 测试工具。 | [SDK 边界与矩阵](../../sdk/README.md) · [自托管 ADR](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) |
| 测试支撑及部署 | Test Support 提供共享夹具，E2E 聚合服务验证，生产编译依赖与测试装配分开。Flyway/脚本维护结构与授权，Helm/Compose 传递运行配置。 | [测试支撑](../../batch-test-support/pom.xml) · [E2E POM](../../batch-e2e-tests/pom.xml) · [部署角色](../../scripts/db/platform-control-plane-roles.sql) |

直接 Maven 生产依赖方向为控制面 → Common、五类 Worker → Core/Common、Java SDK Spring 与 testkit → Java SDK Core。195 个生产 Mapper XML 共解析 1,385 条映射。以显式 DML 定位读写交接，核心运行表 `workflow_run`、`job_instance`、`job_partition` 的已见写入均在 Orchestrator，`job_task` 另外有上表两项受限列写入。核对同时区分了测试数据清理授权、告警自动与人工写入、文件审计追加、Pipeline 超时收口。动态 SQL、外置插件和目标环境实际账号有效权限另需证据。Console OpenAPI 路由对齐覆盖 440 条路径与方法。配对前端确认 Workflow 图编辑、定时启停与运行暂停是各自的 API 操作，本轮未跑浏览器。


### 配置与运行部署

启动配置由 Common 提供共享默认值，各应用在启动时绑定自身配置。Helm 与 Compose 负责注入和环境覆盖，配置治理检查验证了 148 项配置属性的登记、启动期绑定限制和暴露的环境变量。业务动态配置由 Console 入库并记录缓存失效意图，提交后触发失效，Orchestrator 负责消费和清理实例内缓存。本轮核对发布、订阅与配置入口，未重跑跨进程缓存收敛、混版和目标环境密钥轮换。[配置治理](../runbook/config-governance.md) · [共享默认值](../../batch-common/src/main/resources/batch-defaults.yml) · [Console 失效服务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/config/ConsoleConfigCacheInvalidationService.java) · [Orchestrator 订阅](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java)

## 整改（2026-11-22）

三项发现已在 `codex/backend-module-boundary-review` 工作树完成代码整改。验证范围为本机单元与真实 PostgreSQL 用例；完整应用交错、三租户 E2E、拆分账号实跑和目标环境切换仍待执行。

| 编号 | 整改 | 代码证据 | 本地验证 |
|---|---|---|---|
| BOUNDARY-20261122-01 | 画布保存不再写 `schedule_enabled`；定义全量更新与配置包 upsert 冲突更新都只在该参数显式给出时写开关；保存后重读定义，版本快照取持久化后的版本与开关。配置包导入只有显式提供开关列时才改，batch init 省略时按当前值合并。 | [服务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/DefaultWorkflowDefinitionService.java) · [Mapper](../../batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml) · [配置包支持](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantWorkflowDefinitionApplySupport.java) · [Excel 应用](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java) | 单测 81 项：Mapper XML 6、版本历史 7、batch init 45、Excel 监控策略 15、DAG 校验 8；真实 PostgreSQL 契约 10 项，覆盖全量更新与 upsert 冲突时保留开关 |
| BOUNDARY-20261122-02 | Worker 状态写入增加前置状态条件；未命中时重读，同态幂等，其余抛 `STATE_CONFLICT`；pipeline 失败补偿钩子对状态冲突只记录告警，不覆盖新状态。 | [仓储](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java) · [Mapper](../../batch-worker/core/src/main/resources/mapper/PlatformFileRuntimeMapper.xml) · [失败钩子](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java) | `PlatformFileRecordRepositoryIntegrationTest` 新增 3 项：合法推进落库、读 `RECEIVED` 后归档的迟到写被拒且保持 `ARCHIVED`、并发同目标按幂等处理；失败补偿的冲突告警由 `AbstractPipelineStepExecutionAdapterDefinitionTest` 覆盖 |
| BOUNDARY-20261122-03 | 生成器为 Trigger 的 `workflow_definition` 增加列级作用域，重新生成清单与脚本：`SELECT` + `UPDATE (schedule_enabled, updated_at)`；重复执行先撤销整表授权。 | [生成器](../../scripts/db/generate-platform-worker-grants.py) · [清单](../../scripts/db/platform-control-plane-grants.json) · [脚本](../../scripts/db/platform-control-plane-roles.sql) | `python3 scripts/db/generate-platform-worker-grants.py` 通过（产物一致）；列级授权对 `FOR SHARE` 与暂停写入的兼容性沿用审查阶段的隔离库探针 |

整改后的写入边界：定时开关只由 Console 定时启停和 Trigger 运维暂停维护，画布保存与配置包省略列不再携带该值；Worker 文件状态写入必须命中读取到的前置状态，否则交由调用方按冲突处理；Trigger 对定义表的 UPDATE 只覆盖开关和更新时间。核对见 [项目结构](../architecture/project-structure.md) 与 [运行模块通信](../architecture/runtime-module-communication.md)。

### 整改验证

- 编译：`./mvnw -ntp -pl batch-console-api,batch-worker/core -am -DskipTests test-compile` 通过。
- 单元：`./mvnw -ntp -pl batch-console-api -am -Dtest=WorkflowDefinitionMapperXmlTest,WorkflowDefinitionVersionTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,TenantConfigPackageExcelMonitoringPolicyApplyTest -Dsurefire.failIfNoSpecifiedTests=false test`，81 项通过、0 失败。
- 集成：同一入口执行 `WorkflowDefinitionWriteContractIntegrationTest` 与 `ConsoleWorkflowDefinitionMutationIntegrationTest`，23 项通过（真实 PostgreSQL）；Excel 配置包上传 `ConsoleTenantConfigPackageExcelUploadIntegrationTest` 38 项通过（真实 PostgreSQL 与工作簿导入链路）。
- Worker：`./mvnw -ntp -pl batch-worker/core -am -Dtest=PlatformFileRecordRepositoryIntegrationTest,PlatformFileRuntimeMapperStageSkipIntegrationTest,WorkerCoreMapperXmlTenantGuardArchTest,AbstractPipelineStepExecutionAdapterDefinitionTest -Dsurefire.failIfNoSpecifiedTests=false test`，42 项通过（文件登记与新增 CAS 用例 13、运行态 Mapper 20、Mapper 租户守卫 2、执行适配器失败补偿 7）。
- 静态：`check-test-conventions.py`、`check-java-structured-string-concat.py`、`check-empty-checks.py`、`check-script-governance.py`、`check-docs-structure.py`、`check-code-doc-references.py`、`check-doc-timestamp-policy.py` 与平台库授权生成器自检均通过。
- 未执行：完整 Worker 与治理侧交错的应用级用例、三租户 E2E、拆分账号实跑、目标环境 Quartz 重注册与首个 fire。不得将上述本机结果当作目标环境验收。

## 验证和归档

- 以 `git ls-files` 枚举生产 Mapper，使用 Python XML 解析顶层映射与显式 DML。读取各 Maven 模块直接依赖和 scope。日志、集成和目标环境属于后续验证层。
- 使用本机现有 `postgres:17.11` 镜像启动两个无网络、无持久化的临时实例。第一个实例在最小表结构执行当前 Mapper XML 展开 SQL，复现 01 的两条暂停入口和 02 的一次迟到写入。第二个实例校验 03 的表级和列级授权差异、行锁及暂停写入。探针仅证明对应 SQL/授权行为。
- 执行 `python3 scripts/ci/check-<名称>.py`，其中名称为 `application-governance`、`dependency-boundaries`、`direct-client-boundaries`、`five-worker-parity`、`biz-table-tenant-rls`、`sql-config-boundaries`、`config-governance`、`helm-env-sync`、`test-config-sources`、`java-contract-governance`、`console-openapi-paths`、`sdk-runtime-alignment`。另执行 `python3 scripts/ci/check-config-defaults-sync.py --check` 与 `python3 scripts/ci/check-infrastructure-abstraction-boundaries.py --base 37a817471`，共 14 项均通过。最后一项只检查基线后的代码差异，本轮无 Java 差异。
- `bash scripts/ci/run-java-governance-tests.sh` 通过。执行完整性核对覆盖 11 个模块的 44 个 Java 架构与约定测试类，94 个用例，0 失败、0 错误、0 跳过。完整应用竞态、业务 E2E、真实 Kafka、对象存储、线上数据库角色与部署切换待单独验证。
- 同步 [项目结构](../architecture/project-structure.md) 和 [运行模块通信](../architecture/runtime-module-communication.md) 的职责描述。新事项登记于 [TODO Master](../plans/todo-master.md)，既有 [架构整改](../plans/backend-architecture-remediation-2026-11-22.md) 继续维护原发现与验收状态。
