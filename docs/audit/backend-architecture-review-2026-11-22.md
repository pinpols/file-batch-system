# 全后端架构审核

基线为 `51c82f49b09bcfb5823aaf53e6976c171d500144`，与本轮核对时的远端 `main` 一致。审核覆盖服务端全部 10 个平台模块、五种语言 SDK、数据库迁移和两个测试支撑模块。新增 **1 项 P1、3 项 P2**。其中 3 项有本地反例，另 1 项由应用读写路径与生产 Helm 配置共同确定。与[首轮的 4 项](backend-architecture-review-2026-11-21.md)合计 **3 项 P1、5 项 P2**。这组数字是两轮架构审核发现数，仓库其他专项待办仍以 [TODO Master](../plans/todo-master.md) 为准。

## 本轮发现

### P1 · 导出重派可将旧文件登记为零行成功（ARCH-20261122-01）

开启 GENERATE 断点且使用确定性文件路径时，第一次执行可在某页写入并记录位点后中断。重派时，DELIMITED 会再次读首页探测列。如果模板和插件都未声明列且首页已空，格式类立即返回 0，跳过打开文件、按位点截断和按游标续读。上层用旧文件的实际字节数写完成标记，并把零行和旧文件路径交给后续阶段。可选列布局和空页均为插件接口支持的返回。FIXED_WIDTH 有同一提前返回。[分隔符格式，第 42–47 行](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/DelimitedExportFormat.java) · [定长格式，第 39–44 行](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/FixedWidthExportFormat.java) · [完成位点，第 243–250 行](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java) · [读取产物，第 88–119 行](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java)

**复现。** 复用项目 GenerateStepCheckpointTest 的插件和内存位点夹具，运行真实 GenerateStep、CSV、定长策略和临时文件。第一次首页含 OLD_ROW，下一页抛错。第二次首页为空。结果为阶段成功、位点 completed、上下文 recordCount=0、CSV 文件大小 11 字节、定长文件大小 34 字节，两个文件都仍含 OLD_ROW。探针止于生成阶段。[已有夹具](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/stage/GenerateStepCheckpointTest.java)

**关闭条件。** 新生成的零行文件须与模板约定相符。重派时按已保存位点正确续读，或在无法还原列布局时明确失败并保留可恢复状态。覆盖 CSV、定长、首次为空、带残文件重派，以及重新登记时记录数和实际交付内容的一致性。

### P2 · SDK 过期执行者能够清除新执行者的成功结果（ARCH-20261122-02）

启用带正 TTL 的内存幂等存储后，处理器 A 超时，B 可重新取得同一个任务键。B 写入成功结果后，A 的失败路径仅凭键释放记录，会删掉 B 的结果。同键再次投递时，业务处理器可在 B 的结果有效期内重复执行。使用具有外部副作用的处理器时，可能重复提交。[TypeScript 失败释放，第 75 行；内存删除，第 136 行](../../sdk/typescript/src/client/idempotency.ts) · [Go 包装器与存储，第 155–170、209–245 行](../../sdk/go/client/resilience.go) · [Rust 包装器与存储，第 169–185、241–277 行](../../sdk/rust/src/client/resilience.rs)

**复现。** 在真实 TypeScript 包装器和存储中固定时钟。A 在 t=1000 取得 10 ms 租约并暂停，B 在 1011 成功并写缓存。固定在 1011，让 A 返回失败，读取缓存变为空。第三次调用实际进入业务处理器，总进入次数为 3。Go、Rust 的对应结论来自源码；本轮未将该反例外推给 Java、Python 或租户自建存储。

**关闭条件。** 每次取得执行权有可区分的凭据，新结果只能被本轮持有者更新或释放。跨语言契约验证旧失败不能删新成功、旧成功不能覆盖新结果，以及正常失败后仍可立即重试。变更存储接口时要覆盖已有适配器兼容路径。

### P2 · 告警升级发件箱可将刚重新领取的通知置回失败（ARCH-20261122-03）

Console 备用通知路径由 `batch.alert.escalation.notify.enabled=true` 启用，默认关闭。扫描器先选超时行，再按租户、ID 和 PUBLISHING 复位。扫描后若暂停超过调度锁期限，另一实例可复位并领取同一行。前一实例随后执行旧复位时，SQL 未复核超时条件或领取凭据。新发布者的成功回写会更新 0 行，已发布的升级通知可被重试。与首轮 Orchestrator 派发事件涉及不同的表、发布器和启用条件。[开关和锁，第 68–92 行；回收与发布，第 260–271、335 行](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertEscalationNotifier.java) · [选取、回写、复位，第 56、75、106 行](../../batch-console-api/src/main/resources/mapper/AlertEscalationNotificationOutboxMapper.xml)

**复现。** 从当前 Mapper XML 提取原始语句，在隔离 PostgreSQL 17.11 以最小表和一条过期记录依次执行。A 查到过期项；B 复位 1 行并重新领取 1 行；A 的旧复位更新 1 行，B 的成功回写更新 0 行，最终为 FAILED。此顺序验证了 SQL，未向 SSE 或 webhook 发送消息。

**关闭条件。** 复位和发布结果均须验证本轮领取；重新验证原超时条件或观测版本以防误回收。旧复位、旧成功、旧失败和旧放弃对新一轮都应更新 0 行。Console 的配置失效发件箱已有按 claim_token 回写的对照。[按 token 回写，第 28、34 行](../../batch-console-api/src/main/resources/mapper/ConfigCacheInvalidationOutboxMapper.xml)

### P2 · 默认双副本部署无法保证已完成的取证包可下载（ARCH-20261122-04）

Console 管理员生成和下载是两次独立请求。Orchestrator 将 ZIP 写本机，跨实例共享的完成记录只保存本地路径和摘要，下载时在当前实例检查该文件。生产 values 配置两个 Orchestrator，模板将每个 Pod 的 /tmp 挂为各自的 emptyDir。下载落到另一副本会触发 export_file_missing，生成副本被重建后原卷也会消失。这是现有本地存储设计在随仓生产拓扑中的限制，ADR-022 已记载 v0.1 采用本地文件。使用真正的共享目录可改变该结论。[下载，第 97–111 行；完成记录，第 130–148 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/forensic/ForensicExportService.java) · [默认目录，第 14–16 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/forensic/ForensicExportProperties.java) · [生产副本数，第 137 行](../../helm/values-prod.yaml) · [临时卷，第 112–116 行](../../helm/batch-platform/templates/orchestrator.yaml) · [ADR-022](../architecture/adr/ADR-022-forensic-audit-bundle.md)

**证据与关闭条件。** 这是代码和部署配置核对，未在双 Pod 集群实测。按已承诺的下载保留期选择可供所有副本访问的介质，或明确约束支持拓扑。用 A 生成、B 下载、重建 A 后再次下载，核对同一导出 ID、SHA-256 和租户边界。此项无需以实现多年对象锁作为前提。

## 源码与职责覆盖

从该提交的 Git 跟踪文件枚举。清单逐个读取平台和 Java SDK 所有 src/main/java、运行资源、四种非 Java SDK 的实现及 db/migration SQL。对 Java 提取包名和去除字符串、注释后的 if / switch 词法计数，对 Mapper 解析 XML 顶层 SQL 操作，对迁移提取显式 DDL 类型。共 **3,882 个文件**，包含 3,130 个 Java、121 个其他语言实现、195 个 Mapper XML（1,383 条映射语句）、387 个迁移、49 个其他资源。Java 总数含 23 个共享测试支撑和 5 个 SDK testkit 源文件。测试目录、示例、压测、扫描与发布工具不计入该分母。源码清单是逐文件结构审查；下表说明进入调用链或实际执行的深度。[逐文件清单](backend-source-inventory-2026-11-22.csv)

| 模块 | Java / 其他语言实现 | Mapper / 映射语句 | 审核到的职责边界 |
|---|---:|---:|---|
| batch-common | 368 | 4 / 9 | 共享协议、状态码、插件 SPI、存储与基础组件。核对导出插件允许未声明布局及空页、共享库依赖方向 |
| batch-trigger | 135 | 9 / 89 | 定时及补触发的时区与发生时刻、请求身份、TriggerRequestPersistence 事务内持久化请求和投递意图、发件箱领取凭据 |
| batch-orchestrator | 793 | 77 / 631 | launch、容量/排队、DAG、认领、回报、重试、终态、资产和取证，详见下文与首轮反例 |
| batch-console-api | 1,258 | 93 / 531 | 10 个业务域的入口、跨服务调用与自有表，含管理操作转发、通知发件箱和下载 |
| Worker core | 143 | 6 / 55 | 消费、领取、执行前租约确认、取消、结果回报和 REPORT 发件箱 |
| Worker import | 78 | 1 / 26 | 入站扫描范围和租约、续扫位点、解析、校验、LOAD 插件和重派边界 |
| Worker export | 58 | 0 | 格式策略、分页写入、fsync 与位点、上传及注册，新增 01 |
| Worker process | 54 | 2 / 19 | staging generation、Compute 与 Commit、业务库提交与平台栅栏的归属 |
| Worker dispatch | 94 | 3 / 23 | Deliver、Ack、Retry 与 Compensate；外部结果人工裁定消费标记 |
| Worker atomic | 30 | 0 | SPI 执行入口与 SQL、存储过程、HTTP、Shell、Spark 各执行器的外部副作用边界 |
| Java SDK core / Spring / testkit | 88 / 3 / 5 | 0 | 平台协议、Kafka 消费决定、生命周期自动装配、测试工具，依赖独立于平台服务 |
| Go SDK | 17 | 0 | client、protocol 和独立 Kafka 子模块；幂等、按分区暂停、提交和消费代次 |
| Python SDK | 68 | 0 | handler / store、签名与 Kafka 客户端适配，定向执行幂等和签名用例 |
| TypeScript SDK | 17 | 0 | handler / store、Kafka offset 策略与协议；真实执行新增 02 |
| Rust SDK | 19 | 0 | 锁内存储、生命周期和 Kafka 适配，执行库单测 |
| test-support / e2e-tests | 23 / 0 | 0 | 共享夹具与环境属性来源；E2E 模块的组件扫描和 Mapper 装配治理。E2E 模块源码在测试目录 |
| db/migration | 387 个 SQL | — | 迁移对象、RLS 静态覆盖、核心表写入权与 V386 槽位。数据库反例限报告列明的最小结构 |

**服务间依赖与数据库所有权。** 根 reactor 直接非测试依赖中，三个控制面服务都依赖 common，各类 Worker 依赖 core 和 common；Java SDK 的 Spring 和 testkit 只依赖 SDK core 这一仓内组件。对 195 个 Mapper 中显式 DML 表名核对，job_instance、job_partition、workflow_run 的写语句位于 Orchestrator。job_task 的另外两个写者有具体列边界：Process 轮换 staging generation，Dispatch 消费外部结果裁定。把它们一概归为任务生命周期越界会误报。这一核对不包含动态拼表和外置插件。[Process，第 57 行](../../batch-worker/process/src/main/resources/mapper/ProcessInvocationFenceMapper.xml) · [Dispatch，第 228 行](../../batch-worker/dispatch/src/main/resources/mapper/FileDispatchMapper.xml)

**业务域覆盖。** Console 的 audit、config、file、governance、job、notification、observability、ops、rbac、workflow 全部进入结构清单。逐域核对的入口包括附件对象与审计记录、配置批量恢复、文件查询端口、归档策略、业务日重放代理、告警水位线与发件箱、Dashboard 查询缓存、维护操作代理、API key 存储、工作流触发回执。440 条 Console OpenAPI 路由的对齐只证明路径和方法覆盖。未对每条路由做多角色浏览器验收。

Orchestrator 的 application/service 共 17 个域。asset、readiness、version、lineage 通过资产分区和结果版本衔接；dataquality 执行检查，dq 维护规则；batchday、calendar、capacity、sensor 负责业务日、日历依赖、容量画像和信号；task、workflow、replay 分别负责执行推进、DAG 与受控重放；dryrun、failure、governance、forensic 涵盖演练、故障分类、治理操作和证据。另查 application/engine 的准入与发布、scheduler 恢复及 mapper。核对了 ResultVersionWriter 要求参与外层事务、ReadinessService 读取资产分区、重放服务使用 operation 与 preview token，以及取证产物跨副本可见性。[版本写入](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java) · [就绪判定](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessService.java) · [重放操作](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowNodeReplayOperationService.java)

**设计模式、集合和分支。** 3,130 个 Java 文件的词法统计为 10,054 个 if、119 个 switch。复核了分支集中的业务实现。DAG 用 ArrayDeque 和 visited 集合推进遍历、用按节点码的 Map 匹配前驱，ALL / ANY / N_OF 的有限分支直接表示准入条件。导出格式通过策略注册表扩展，但分页和位点收尾由共用层维护，新增 01 正是一个绕过共用层的提前返回。ImportIngressScanner 的四个阶段 if 会读取上一阶段返回的新位点，允许一轮顺序推进，将其机械改为 else-if 或单次 switch 会改变行为。分支数只用于定位复核对象。[既有设计模式整改记录](branching-data-structure-pattern-review-2026-11-12.md) · [DAG，第 295、312 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) · [扫描阶段，第 173–187 行](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) · [格式分派](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/ExportFormatStrategyRegistry.java)

## 验证记录

- 在独立工作树执行 16 个仓库静态门禁，均退出 0。命令为 `python3 scripts/ci/check-<名称>.py`。模块和数据边界为 application-governance、dependency-boundaries、direct-client-boundaries、five-worker-parity、biz-table-tenant-rls、sql-config-boundaries、schema-governance-assets；配置与观测为 config-governance、config-defaults-sync（加 `--check`）、helm-env-sync、observability-contract、test-config-sources；契约为 java-contract-governance、console-openapi-paths、sdk-config-env-parity、sdk-runtime-alignment。
- `bash scripts/ci/run-java-governance-tests.sh` 构建成功，完整性检查找到 11 个模块的 44 个架构/约定测试类的 Surefire 报告。
- Java 业务和契约定向复跑命令为 `./mvnw -B test -Dtest=ForensicExportServiceTest,ReadinessServiceTest,ResultVersionWriterTest,DataQualityCheckExecutorTest,WorkflowConditionEvaluatorTest,AssetPartitionServiceTest,AlertEscalationNotifierTest,CommitStepTest,ImportIngressScannerPaginationTest,GenerateStepCheckpointTest,SqlTaskExecutorTest,SdkPlatformContractTest,JsonFixtureContractTest,DefaultTaskExecutionWrapperTest,AckDispatchStepTest -Dsurefire.failIfNoSpecifiedTests=false`。15 个指定类都有报告。合计 226 项，213 通过，0 失败，13 跳过。13 项来自 JsonFixtureContractTest 的纯决策层假设，涉及 HTTP 或 Kafka 副作用；本轮没有把它们计为运行通过。
- Go 主模块执行 `go test ./...`。另在 sdk/go/kafka 指定按分区暂停、提交重试、代次和 offset 的 7 个函数执行 `go test -run 'Test(WithholdAllowsOtherPartition|PollRetainsDeliveryWhilePaused|CommitFailureRetriesCurrentPartition|ExpiredGenerationCannotCommit|MatchesDirectDispatch_WorkerTypeAgnostic|Committable|Consumer_WithholdSetsCeilingAndDropsPending)$' -count=1 ./...`。TypeScript 执行 `node --test tests/contract.test.ts tests/retry-idempotency.test.ts`，37 项通过；Python 执行 `pytest -q tests/idempotent tests/test_signing_conformance.py`，37 项通过；Rust 执行 `cargo test --locked --offline --lib`，82 项通过。
- 新增问题的探针分别使用 JDK 与项目既有 fixture、真实 TypeScript 包装器、PostgreSQL 17.11 原始 Mapper 语句。首轮同基线的 JVM 和 PostgreSQL 结果沿用原报告。完整 Flyway 从零升级、真实 Kafka 重平衡、外部对象存储、双 Pod 下载、全量业务回归、吞吐与灾备演练不在本轮实测结果内。[容量与部署验收计划](../plans/system-design-capacity-validation-plan-2026-11-09.md)
