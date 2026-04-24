# file-batch-system 项目编码规范

> **维护规则**：任何影响编码约定、架构约束、版本策略、模块边界的改动，必须同步追加到本文件末尾的 **§变更记录**（按日期倒序，使用当天绝对日期）。

## 方法参数约束

**方法参数数量不能超过 6 个（含 6 个）。**

- 参数 ≥ 7：必须封装为参数对象（Command / Context / Request / Param）
- 参数 6：建议封装，Mapper 公共方法和 Service 公共接口必须封装
- 构造器（record、DTO、Response、data holder、Spring DI 注入）不受此约束
- 封装类型优先选用 `private record`（私有方法）或独立 Command/Param 类（公共接口）

## Java 代码风格

- **禁止在代码中使用全限定类名（FQN）**——必须通过 `import` 导入后使用短名。例如写 `TimeUnit.SECONDS` 而非 `java.util.concurrent.TimeUnit.SECONDS`
- 注解同理：写 `@MockitoSettings` 而非 `@org.mockito.junit.jupiter.MockitoSettings`
- 枚举字典类优先使用 Lombok 样式（见 §领域数据字典），避免手写构造器和 accessor 样板

## 分支消除规则

**按类型/状态分派的 if-chain 或 switch，≥ 3 个分支时必须消除重复，不允许以"分支数少"为由保留散漫写法。**

| 场景 | 规定写法 | 反例 |
|---|---|---|
| 按字符串/枚举类型路由到不同处理器 | `Map<String, Handler>` 路由表，构造期初始化 | `if (type.equals("A")) handleA(); else if ...` |
| switch 每个 case 调用结构相同但参数不同的操作 | 提取公共模板方法，case 内只传参 | 每 case 重复 try-catch + 日志 |
| if-chain 内联 else 分支体 > 10 行 | 提取为命名方法（`dispatchXxxNode`） | 两个 if + 一个 inline else 塞满整个方法 |
| 多个 catch/error-return 返回相同结构 | 提取 `failResult` / `errorResult` 静态工厂 | 每个 catch 块复制 3 行相同代码 |
| 同一 `if (n <= 0) { log.warn(...) }` 出现 ≥ 3 次 | 提取 `warnIfCasMiss(int, String, long)` 辅助方法 | 3 处 CAS-miss 警告各自重复写 if+warn |

**策略对象规则：**
- `SpecHandler<T,E>` 模式（见 DefaultConsoleTenantConfigInitApplicationService）：同一"查找→跳过/更新/创建"循环在多个方法中重复时，提取为带 `of()` / `upsertable()` 工厂的 handler 接口，由公共 `applySpecs()` 模板驱动。
- `CompensationHandler` 模式（见 DefaultCompensationService）：按字符串类型分派多个独立方法时，用 `Map<String, Handler>` 替代 if-chain，在构造期或静态块中一次性注册所有分支。

## API 文档同步约束

**凡修改 `batch-console-api` 控制层，必须同步更新 API 文档。**

触发条件（满足任意一条即须更新）：
- 新增、删除或重命名 Controller 方法 / 路由路径
- 修改请求参数、请求体字段或响应体字段（含新增/删除/重命名/类型变更）
- 新增或修改 Request / Response 类（含 record）

必须同步更新的文件：
- `docs/api/console-api.openapi.yaml`：补齐对应 path、schema 定义，确保无悬空 `$ref`
- `docs/api/console-api-protocol.md`：在 Changelog 表追加一行，填写日期和变更摘要

## Query Record 工厂方法规约

Query record 字段数 ≥ 5 且内部调用者只传少数字段（其余为 null）时，**必须**在 record 内定义静态工厂方法，禁止在调用处写出 null 参数。

- `ofTenant(String tenantId, PageRequest page)` — 按租户全量查
- `ofDefinition(Long definitionId, PageRequest page)` — 按定义 ID 查关联记录
- `ofDefinition(String tenantId, Long definitionId, PageRequest page)` — 双约束版本

已有工厂方法：`JobDefinitionQuery.ofTenant`、`WorkflowDefinitionQuery.ofTenant`、`WorkflowNodeQuery.ofDefinition`、`WorkflowEdgeQuery.ofDefinition`

## 领域数据字典

所有枚举值定义在 `batch-common/.../enums/`，**必须实现 `DictEnum` 接口**（提供 `code()` / `label()`），字段值使用 `.code()`。

**统一样板（Lombok）：**
```java
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum XxxType implements DictEnum {
  FOO("FOO", "..."),
  BAR("BAR", "...");

  private final String code;
  private final String label;
}
```
- `@RequiredArgsConstructor` 生成 `(code, label)` 构造器
- `@Accessors(fluent = true) + @Getter` 生成 `code()` / `label()`（非 `getCode()`）匹配 `DictEnum` 契约
- 禁止再手写构造器和 accessor

**通用工具（在 `DictEnum` 接口上）：**
- `DictEnum.fromCode(EnumClass.class, code)` — 按 code 反查，未匹配返回 `null`
- `DictEnum.codes(EnumClass.class)` — 所有 code 的不可变 `Set`
- `DictEnum.labels(EnumClass.class)` — 所有 label 的有序 `List`

各枚举若需 "空白→默认" / "未知→抛异常" / "返回 Optional" 等特殊语义，自定义 `fromCode` 方法包装 `DictEnum.fromCode` 即可（参考 `CatchUpPolicyType` / `WorkflowJoinMode` / `RunMode`）。

**对外暴露约束：**
- 暴露给前端的枚举必须登记在 `ConsoleMetaQueryService.REGISTRATIONS` 中，并同步更新 `docs/api/console-api.openapi.yaml` 的 `CommonResponseMetaEnums` schema
- 内部枚举（协议码 / 死代码 / 内部标记）加入 `ConsoleMetaEnumRegistrationTest#EXCLUDED` 白名单并注明原因
- 守护测试 `ConsoleMetaEnumRegistrationTest` 强制二选一，CI 阶段拦截遗漏

**核心字典：**
- `schedule_type`：`CRON` / `FIXED_RATE` / `MANUAL`（`ScheduleType`）
- `job_type`：`GENERAL` / `IMPORT` / `EXPORT` / `DISPATCH` / `WORKFLOW`（`JobType`）
- `retry_policy`：`NONE` / `FIXED` / `EXPONENTIAL`（`RetryPolicyType`）
- `catch_up_policy`：`NONE` / `AUTO` / `MANUAL_APPROVAL`（`CatchUpPolicyType`）
- `workflow_type`：`DAG` / `PIPELINE` / `MIXED`（`WorkflowType`）
- `workflow_node.node_type`：`START` / `END` / `TASK` / `GATEWAY` / `FILE_STEP` / `JOB`（`WorkflowNodeType`）
- `workflow_edge.edge_type`：`SUCCESS` / `FAILURE` / `CONDITION` / `ALWAYS`（`WorkflowEdgeType`）
- `file_channel.channel_type`：`SFTP` / `API` / `API_PUSH` / `EMAIL` / `NAS` / `OSS` / `LOCAL`（`FileChannelType`）
- `outbox_event.publish_status`：`NEW` / `PUBLISHING` / `PUBLISHED` / `FAILED` / `GIVE_UP`（`OutboxPublishStatus`）
- `job_instance.status`：`CREATED` / `WAITING` / `READY` / `RUNNING` / `PARTIAL_FAILED` / `SUCCESS` / `FAILED` / `CANCELLED` / `TERMINATED`（`JobInstanceStatus`）
- `workflow_run.status`：`CREATED` / `RUNNING` / `SUCCESS` / `FAILED` / `TERMINATED`（`WorkflowRunStatus`）

详见 `docs/coding-conventions.md` §18。

## 配置开关规范

全局安全旁路总开关 **`batch.security.bypass-mode`**（`BatchSecurityProperties#isBypassMode`）。开启后整条安全链（认证 / 脱敏 / 加解密 / 审批 / 渠道校验）放行，仅供本地 / 联调 / E2E。

- **默认**：IDE local=`true`（调试方便）、docker-compose=`false`（贴近生产）、prod profile 强制拒绝 `true`（@PostConstruct 守护）
- **调用**：业务代码一律 `isBypassMode()`；旧键 `testing-open` / `isTestingOpen()` 已 deprecated，下一 minor 版本移除

详见 `docs/coding-conventions.md §21`。

## 版本管理

**Maven CI-friendly 版本策略**：全项目采用 `${revision}` 占位符，版本在根 pom `<properties><revision>` 单点控制，默认 **`1.0.0`**（非 SNAPSHOT）。

- 默认构建：`mvn package` → 产物 `batch-*-1.0.0.jar`
- 覆盖版本：`mvn -Drevision=2.0.0 package`
- 根 pom 装配 `flatten-maven-plugin`，install/deploy 前自动把 `${revision}` 在 pom 里展开为实际版本号，下游消费者能正确解析
- **build script 禁用 `-Dmaven.test.skip=true`**（会同时跳过 test-jar 生成，打断 `batch-common:tests` 依赖链）；统一用 `-DskipTests`（只跳执行，保留 test-jar 产物）
- `load-tests` 是独立模块（未纳入根 reactor），版本使用字面量，与根版本手工保持同步

## 架构硬约束

- 任务分发主链：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- Orchestrator 是唯一状态主机；Worker 不能直接改写 job_instance / workflow_run / workflow_node_run
- outbox_event 必须与任务状态写入处于同一事务
- Worker 执行前必须先 CLAIM，不能绕过
- 禁止 JPA/Hibernate；持久层 MyBatis（运行态）/ Spring Data JDBC（配置态）不混用

## 时区策略

**全系统默认时区 = `batch.timezone.default-zone`（默认 `Asia/Shanghai`，UTC+8）。业务代码禁止直接调用 `ZoneId.systemDefault()`——它依赖容器 TZ / JVM `user.timezone`，不同部署环境会静默漂移。**

- **读取入口**：注入 `com.example.batch.common.config.BatchTimezoneProvider`
  - `provider.defaultZone()` — 平台默认 `ZoneId`（永远非 null）
  - `provider.resolveOrDefault(preferred)` — 优先 IANA 字符串（如 `business_calendar.timezone`），空/非法时回退到默认
- **业务覆盖优先级**：`business_calendar.timezone` > `batch_window.timezone` > `batch.timezone.default-zone`。`batch_day_instance.timezone_snapshot` 在创建时从日历抓快照，之后日历改 timezone 不影响历史数据回放。
- **容器 / JVM 协同**：`docker-compose.yml` + `.env.example` 默认 `TZ=Asia/Shanghai`；`batch-defaults.yml` 加 `spring.jackson.time-zone=${BATCH_TIMEZONE_DEFAULT_ZONE:Asia/Shanghai}` 让 Jackson 序列化 `OffsetDateTime` / `Instant` 时保持一致。
- **豁免**：`ConsoleQuerySupport.parseFlexibleInstant*`（控制台搜索框的宽松日期解析）保留 `systemDefault()`，容忍用户输入；`QuotaResetPolicy.systemZone()` 已 `@Deprecated`，仅遗留测试使用，新代码改走 provider。
- **守护**：新增 `ZoneId.systemDefault()` 用法必须在业务路径之外且加注释说明原因；否则 PR 评审拒绝。

## 字符编码

**全系统 UTF-8**：系统内部持有、传输、存储、落盘的字符串一律 UTF-8；**导入**（`PreprocessStep`）是唯一允许读非 UTF-8 源文件的边界，读入后立即转为 UTF-8 内部表示。

- **代码**：一律 `StandardCharsets.UTF_8` 或 `EncodingUtils.resolve(raw)`，**禁止** `Charset.forName("UTF-8")` / 字面量 `"UTF-8"`
- **中间件 locale**：`docker-compose.yml` 里 postgres / kafka / minio / redis 四个容器统一从 `.env` 的 `BATCH_LOCALE`（默认 `C.UTF-8`）读取 `LANG` / `LC_ALL`；postgres 另加 `POSTGRES_INITDB_ARGS=--encoding=UTF8`

详见 `docs/coding-conventions.md §20`（含 Maven / Dockerfile / Spring Boot / Export / Import 全层落地表）。

## 模块边界

模块结构固定，不可擅自增删：
`batch-common` / `batch-trigger` / `batch-orchestrator` /
`batch-worker-core` / `batch-worker-import` / `batch-worker-export` /
`batch-worker-dispatch` / `batch-console-api`

## 变更记录

> 按日期倒序；每次影响本文件任一规范的改动都必须在此追加条目。日期使用绝对日期（`YYYY-MM-DD`），条目简要描述"改了什么 + 为什么"。

### 2026-04-24
- **shutdown 期 Redis 调用收敛**（重启日志复查发现）：orchestrator graceful shutdown 时 Lettuce 先关，OutboxPollScheduler 继续 tick 抢 ShedLock → `setIfAbsent` 抛 `java.lang.IllegalStateException: LettuceConnectionFactory has been STOPPED` → 冒泡到 OutboxPollScheduler `catch (Throwable t)` 打 `ERROR Outbox 轮询异常（非数据库类）`。两处补丁：
  - `RedisShedLockProvider.lock/unlock` 的 catch 追加 `IllegalStateException`（与 `DataAccessException` 并列），统一 `rootReason()` 抽取消息格式，关闭期 return Optional.empty 视为未拿到锁。
  - `OutboxPollScheduler.pollAndReschedule` 在 `executeWithLock` 之前加前置 `gracefulShutdown.isDraining()` 短路（原先的 check 在 `executeAdvance` 里，发生在拿锁之后）；这样 shutdown 期间根本不会调用 Redis，也不会产生 WARN。
- **运行日志长期噪声 + 真错误一锅端**（跟进 2026-04-22 "收敛运行日志长期噪声" 批次；这轮抓的是日志里剩下的 ERROR/WARN 噪声大头）：
  - **`LaunchBatchDayService.upsertBatchDayInstance` 加 `DuplicateKeyException` 重试**：03:00 等整点时多个触发同时落 `default-tenant/default_calendar/<bizDate>` 的 batch_day_instance，两个线程 `findFirstByTenantIdAndCalendarCodeAndBizDate` 都返回 null，都走 INSERT 分支，第二个撞 `uk_batch_day_instance` → 500 → trigger 当 SYSTEM_ERROR 重试。外层 retry 循环现在同时抓 `OptimisticLockingFailureException`（update 分支 CAS 失败）和 `DuplicateKeyException`（INSERT 分支并发撞键），`last` 容器类型升到 `DataAccessException` 兼容两者；重试后 SELECT 已能看到对方的记录，走 update 分支收敛。
  - **`DefaultTriggerService.handleHttpClientError` 区分 422/409/404**：
    - 422 Unprocessable = 业务拒绝（典型：`current execution time is outside batch window`、tenant closed、validation 失败）→ REJECTED + WARN + 不抛异常，避免 Quartz 打 `Job threw an unhandled Exception` ERROR 并进 misfire retry；下次 cron fire 仍是同样结果，重试无意义。
    - 409 Conflict = 瞬时并发冲突（乐观锁 / 唯一键 / 幂等撞键）→ FORWARD_FAILED（trigger-retry-scheduler 自动下次 tick 再试），不抛。
    - 404 / 其他 4xx 行为不变。
  - **`AbstractApiExceptionHandler` 新增 2 个 handler**：`OptimisticLockingFailureException` / `DuplicateKeyException` 统一映射为 409 CONFLICT + WARN（而不是落到通用 `Exception` handler 打 ERROR + 500）。所有模块（trigger/orchestrator/console）的 ApiExceptionHandler 一次受益，因为它们都继承这个基类。
  - **`RedisShedLockProvider.lock` 捕获 `DataAccessException`**：Redis 瞬时故障（`QueryTimeoutException` / 连接拒绝 / 节点切主）时 `return Optional.empty()`（视为没拿到锁），下 tick 自然重试，不再冒泡到 Spring scheduler 打 ERROR。`unlock` 同理吞掉（key TTL 自动释放）。
  - **`DefaultStateMachine` 自回边 NOOP 降 DEBUG**：`case "START", "CLAIM", ..., "RUNNING" -> "RUNNING"` 把 RUNNING 加进合法事件；TERMINATE/CANCEL 补 TERMINATED/CANCELLED；WAITING/CREATED/PENDING/NOOP 合并为 noop；default 分支判断 `event.equalsIgnoreCase(fromState)` → 幂等重复事件 DEBUG，其他真未知事件（如 `SUCESS` 拼错）保留 WARN。
  - **`RemoteFilesystemDispatchSupport` NAS symlink WARN 首次抑制**：加 `ConcurrentHashMap<String, Boolean> NAS_SYMLINK_WARNED`，同一 configured path 只报一次；macOS 本地 `/tmp → /private/tmp` 这种恒定 symlink 不再每次 dispatch 都刷一条。
- **脏数据排查：孤儿 job_definition 通用清理**：最初发现 `default-tenant/gen_data_cleanup` 的 `worker_group=GENERAL` 在系统里没有任何 `ONLINE/DRAINING` worker 匹配（只有 IMPORT/EXPORT/DISPATCH 三组），导致 `WaitingPartitionDispatchScheduler` 每 10s 选一次 → `DefaultWorkerSelector` WARN `no_online_workers_in_group`（累计 1073 条）。进一步调研发现同类孤儿还有 `gen_archive_purge` / `gen_index_rebuild`，以及 6 条长期 CREATED 卡住的 job_instance（launch 中断残留）。
  - `scripts/db/cleanup-orphan-general-job.sql` 重写为**通用脚本**（不再硬编码 `gen_data_cleanup`）：PART A 只读诊断（4 个 SELECT 列出将被改的范围），PART B 写事务（4 条 UPDATE 级联 CANCEL partition → instance → disable definition → 附带清 stale CREATED），PART C 事后验证。判定逻辑：`enabled=true AND worker_group <> '' AND worker_group NOT IN (online worker_groups)`；**WORKFLOW 类型的 `worker_group=''` 合法**（workflow 本身不挂 worker，节点才分），脚本已排除。
  - 脚本幂等：`UPDATE ... WHERE status IN (active states)` 只改当前活跃行，重复跑无副作用。
  - 执行记录（2026-04-24 14:01）：B-1 CANCEL 2 partition / B-2 CANCEL 3 instance / B-3 禁用 3 definition / B-4 CANCEL 5 stale CREATED；事后 orchestrator `waiting dispatch tick` INFO 立即静默（下沉到 `log.debug("no WAITING partitions this tick")` 分支）。
  - 脚本不入 Flyway，属运维一次性动作；将来新发现的孤儿组合直接再跑同脚本即可。

### 2026-04-23
- **新增 `GET /api/console/queries/partitions`**：按作业实例分页查询 `job_partition` 列表。前端 `PartitionView.vue` 此前打的 `instanceApi.partitions(instanceId)` 后端没有对应路由，只能本地裁切；补上后走服务端分页，避免大实例（分区数 > 1000）拉全量。
  - 新建 `JobPartitionQueryRequest`（extends `PageQueryRequest`，字段 `tenantId / jobInstanceId / partitionStatus`）+ `ConsoleJobPartitionResponse`（13 字段，含 `partitionNo / partitionKey / partitionStatus / workerGroup / workerCode / retryCount / businessKey / leaseExpireAt / startedAt / finishedAt`）。
  - 新建 console 侧只读 `JobPartitionMapper` + XML（MyBatis）独立于 orchestrator 的同名可写 mapper；`selectByQuery` 走 `<include refid="filters"/>` 消除 count/list 的 where 重复。
  - `ConsoleJobQueryMappers` / `ConsoleJobQueryService` / `ConsoleQueryApplicationService` / `DefaultConsoleQueryApplicationService` / `ConsoleQueryController` 接线。
  - OpenAPI 加 `/api/console/queries/partitions` path + `ConsoleJobPartitionResponse` schema + `CommonResponseJobPartitionList` wrapper；`console-api-protocol.md` 补 Changelog、endpoint 清单、过滤参数表。
  - 备注：「partition 粒度」与既有「step 粒度」（`/job-step-instances`）并存，前者 = `job_partition`，后者 = `job_step_instance`。

### 2026-04-22
- **清理 `docs/test-data/deprecated-single-sheet-imports/`**：10 份单 sheet Excel 样本（`01..10-*-full-coverage-test.xlsx`，绑定孤立租户 `test-full-coverage`）已全量删除。3 个对应控制器已 `@Deprecated`（FileTemplate / ResourceQueue / AlertRouting），其余 7 个虽未标注但流程上被整合式 Excel 和 tenant-init JSON 取代，样本无人装载。同步修 README：明确 `file_template_config` 不进整合式 Excel 是系统设计（建租户时从 `default` 克隆 + 页面单条维护），不是"gap"；批量初始化走 `tenant-init` 的 `FileTemplateConfigUpsertParam`。
- **前端整合式 Excel 按租户分工补齐枚举覆盖**（在 default-tenant 之外）：
  - **ta（零售）**：新 `TA_IMPORT_ORDER` IMPORT 6-stage 流水线（`RECEIVE→PREPROCESS→PARSE→VALIDATE→LOAD→FEEDBACK` 全链）+ 既有 `TA_EXPORT_REPORT` 补 5-stage EXPORT 流水线（`PREPARE→GENERATE→STORE→REGISTER→COMPLETE`）+ `ta_local_archive` LOCAL channel。
  - **tb（金融）**：新 `TB_DISPATCH_SETTLE` DISPATCH 6-stage 流水线（`PREPARE→DISPATCH→ACK→RETRY→COMPENSATE→COMPLETE` 全链）+ `tb_api_ingest` API channel（区别于已有 API_PUSH）。
  - **tc（风控）**：两个新 GATEWAY workflow —— `TC_WF_GATEWAY_ALL`（`joinMode=ALL`，3 branch）和 `TC_WF_GATEWAY_N_OF`（`joinMode=N_OF, joinThreshold=2`，带 FAILURE / CONDITION 边的 fallback 子路径）覆盖 `WorkflowJoinMode` 全部三值 + `workflow_edge.edge_type` 全部四值。
  - 追加脚本 `scripts/local/append-tenant-coverage.py` 幂等处理（按主键查重跳过），重复跑不会产生重复行；源于 v4 审计发现"ta/tb/tc excel 场景长尾缺口"（FEEDBACK / STORE / REGISTER / DISPATCH 全链 / API / LOCAL / ALL / N_OF / FAILURE / CONDITION）。
- **前端整合式 Excel 补齐 default-tenant 样本**：v4 硬化批次在 `multi-tenant-seed.sql` 给 `default-tenant` 新增了 4 条本地化 dispatch channel_config + 3 条探针 workflow（`wf_probe_pipeline` / `wf_probe_gateway` / `wf_probe_mixed`）+ 对应 job_definition，但 `test-full-coverage-import-suite/` 只有 ta/tb/tc 的 `*-tenant-config-package-test.xlsx`，前端 `/api/console/config/tenant-package/excel/*` 入口无法一键重放这批 seed。
  - 新增 `docs/test-data/test-full-coverage-import-suite/default-tenant-config-package-test.xlsx`（用生成脚本 `scripts/local/gen-default-tenant-excel.py` 产出，整合式 8 sheet 结构，按需修改脚本重生）。
  - 更新同目录 `README.md`：声明四个租户样本的用途、生成脚本指针、以及已知 gap——整合式 Excel 暂不含 `file_template_config` sheet，因此 tb/tc 本轮新增的 `IMP-TXN-FIXED` / `IMP-TXN-XML` / `IMP-TRANSACTION-CSV.jdbcMappedImport` / `IMP-RISK-SCORE-JSON.jdbcMappedImport` / `EXP-RISK-ALERT-JSON.sqlTemplateExport` 目前仅落地 seed SQL，等后续整合式 Excel 增加该 sheet 后再同步。
- **`capability_tags` 数据质量审计调度器**（审计发现的唯一"脏数据妥协"兜底）：`DefaultWorkerSelector.capabilityTagsContain` 为防畸形 JSON 拖垮 selector，catch 后返回 false（WARN 一条即过）——数据源头可能长期无人发现。新增 `WorkerCapabilityTagsAuditScheduler` 默认每 5 min（`batch.worker.audit.capability-tags-scan-interval-millis`）扫描 ONLINE/DRAINING worker：
  - DB 侧 `WorkerRegistryMapper.selectInvalidCapabilityTags` 用 `jsonb_typeof(capability_tags) <> 'array'` + 含非字符串元素过滤，O(activeWorkers) 扫表。
  - App 侧用 `JsonNode` 而非 `String[].class` 做二次严格校验——Jackson 默认会把 `[1,2]` 这种数值元素静默强转成字符串（这恰恰是要审计的脏数据），必须按元素 `isTextual()` 判定才能暴露。
  - 命中时 WARN 日志（采样前 10 条，`capability-tags-log-sample-limit` 可覆盖）+ `batch.worker.capability_tags.invalid.count` gauge，Grafana 可设"> 0 持续 2 周期"告警。
  - ShedLock(`worker_capability_tags_audit`, PT2M)；`OrchestratorGracefulShutdown.isDraining()` 时跳过。
  - 新增 `InvalidCapabilityTagsRecord` DTO（tenant/code/raw）+ `WorkerCapabilityTagsAuditSchedulerTest` 7 case（draining/empty/对象/标量/含数值数组/合法数组/mixed/null-blank）。
  - 顺手修 `DefaultWorkflowNodeDispatchServiceIdempotencyTest` 对 `DefaultWorkflowNodeDispatchService` 构造器的过期调用（缺 `NamedParameterJdbcTemplate` 入参，早先补 upstream partition output 查询时遗漏）。
- **v4 P0/P1/P3 批量闭环**（仅 P2 验证型场景保留）：
  - **P3-1 calendar WARN**：`DefaultTriggerService.resolveCalendar` 加 `CodeNormalizer.toConfigFormOrNull` 归一 `strict-calendar` → `strict_calendar`，消除每 30min 刷的「calendar definition not found」WARN。
  - **P3-3 P3-4 数据清理**：新增 `scripts/db/cleanup-historical-failures.sql` 按保留窗口级联清理 `job_instance`/`job_partition`/`job_step_instance`/`job_task`/`pipeline_instance`/`pipeline_step_run`/`file_dispatch_record`/`workflow_run`/`workflow_node_run`/`dead_letter_task`/`trigger_request`/`outbox_event` 一条龙。本轮跑完：146 FAILED + 4 CANCELLED 清零；146 DL 剩 3 条活跃。
  - **P1-4 EXPORT SQL 占位符白名单**：`SqlTemplateExportSecurityProperties.allowedExtraParams` 默认加入 `bizDate`，不再因「常见业务日期过滤」被拒。
  - **P1-5 DISPATCH 硬错不可重试**：`DefaultRetryGovernanceService` 加 `NON_RETRYABLE_ERROR_CODES` 集合（`DISPATCH_PREPARE_FILE_MISSING` / `DISPATCH_PREPARE_FILE_NOT_FOUND` / `DISPATCH_PREPARE_CHANNEL_NOT_FOUND` / `DISPATCH_PREPARE_INVALID` / `DISPATCH_PREPARE_PARSE_FAILED` / `EXPORT_GENERATE_NO_PAYLOAD` / `STEP_NOT_FOUND`），这类一次性硬错直接进死信，不再 EXPONENTIAL 重试 3 次浪费指数 backoff 与 DL 空间。
  - **P1-3 EXPORT 包装层校验 `id` 列**：`SqlTemplateExportSpec.parse` 在加载模板时用词界正则预检 `cursorColumn`（默认 `id`）是否在 SQL 出现，不在就直接抛 `IllegalArgumentException` 明示「either add to SELECT, or set sqlTemplateExport.cursorColumn」，避免运行期 PostgreSQL `bad SQL grammar` 难调试。
  - **P0-3 空壳 workflow 清理**：删掉 24 条 0-node 的 `workflow_definition`（跨 4 租户 × 6 workflow_code：`wf_archive_flow / wf_compliance_check / wf_data_migration / wf_full_pipeline / wf_onboarding / wf_settle_dispatch`），同时把 default-tenant 3 条指向这些空壳的 `job_definition` 禁用。
  - **P1-1 Workflow 节点 payload 串联**：`DefaultWorkflowNodeDispatchService.buildTaskPayload` 新增两层合并：
    - `mergeNodeParams`：把当前 `workflow_node.node_params`（用户在设计器配的 `templateCode` / `channelCode` 等静态字段）合并进下游 task payload。
    - `mergeUpstreamPartitionOutputs`：扫描同一 job_instance 下已 SUCCESS 的兄弟分区 `output_summary`，按保守白名单（`fileId / fileCode / batchNo / recordCount / bizDate`）抽取后塞进 payload；SETTLE 生成的文件自动流向 DISPATCH 节点。
    验证：触发 `wf_eod_process` 后观察 SETTLE 分区的 `task_payload`，5 个 node_params 字段（`step / batchNo / bizType / fileCode / templateCode`）正确注入，与 `sourcePayload` 字段共存。
    跟进：经查 `WaitingPartitionDispatchScheduler` 本身没 bug。早先误判「不 release」的根因是 worker 进程在长时间运行中挂掉、心跳超时被 `WorkerHeartbeatTimeoutScheduler` 打 OFFLINE，selector 自然 `candidates=0 / no_online_workers_in_group` 静默重试。重启 workers 后 WAITING partition 立即释放。顺手给该 scheduler 加了 `waiting dispatch tick` 和 `skip partitionId=... reason=...` 两级 INFO 日志，今后此类卡死一目了然。
- **Workflow→worker step executor 协议错位修复**（上段副发现的根因落地）：`STEP_NOT_FOUND: DISPATCH_PREPARE` 不是 worker 把 payload.steps 当执行链解读的问题，而是 worker 拿 `request.jobCode()`（= workflow 自己的 `wf_eod_process`）去查 `pipeline_definition` → 命中的是跨 worker 的复合 pipeline（混着 EXPORT_* 和 DISPATCH_* 两类 impl_code）→ EXPORT worker 在 DISPATCH_PREPARE 上自然报错。三处修法一起上：
  - `AbstractPipelineStepExecutionAdapter.resolveJobCode` 优先读 task payload JSON 的 `targetJobCode`（orchestrator 派发 workflow TASK 节点时已经写入），确保 worker 加载本域独立 pipeline（`exp_settlement_daily` / `disp_sftp_bank` 这种纯 EXPORT / DISPATCH 的 pipeline，而不是 `wf_eod_process` 的复合 pipeline）。
  - `DefaultWorkflowNodeDispatchService.mergeUpstreamPartitionOutputs` 加两级兜底查 fileId：先按 `jobInstance.traceId` 查 `file_record.trace_id`（本轮产出的文件），没有时再按 `batchNo → file_record.source_ref` 查（按业务键幂等复用的文件，如 `settlement-2026-04-22`）；抽到后注入下游 payload。DISPATCH 节点从此能看到 SETTLE 上游生成的 fileId。
  - `WaitingPartitionDispatchScheduler.buildRequest` 优先读 `partition.input_snapshot` 里 sub-job 专用的 `queueCode / windowCode`，否则才回退到 `jobInstance` 的 workflow 级值。避免 DISPATCH partition 按 `workflow_queue.resource_tag=workflow` 去找 DISPATCH worker → capability_tags=[delivery] 永远不匹配的死循环。
  - 端到端验证：wf_eod_process（instance 219）的 SETTLE SUCCESS → DISPATCH partition 的 `task_payload` 含 `fileId=470 / channelCode=sftp_bank / targetJobCode=disp_sftp_bank` → DISPATCH worker 加载正确 pipeline 跑到 DISPATCH_SEND 阶段。最后的 `sftp_host missing` 是 channel config 种子数据硬伤，与代码无关。
- **P2 场景真实数据验证**（除压测）：逐条走通 —— drain enable/disable（orchestrator+trigger 双边，trigger 同步切 Quartz STANDBY/STARTED）、worker drain 生命周期（ONLINE→DRAINING→DECOMMISSIONED）、file archive/redispatch（file 470 `GENERATED→ARCHIVED`）、compensation 独立（`cmp-...afe1e065` JOB type SUCCESS）、LOCAL dispatch 真文件落盘、OSS dispatch 真上传、日历 holiday 插入验证。未覆盖：FIXED_WIDTH/XML（无种子模板）、GATEWAY 节点 + PIPELINE/MIXED 类型 workflow + join 模式（ALL/ANY/ANY_N）（无种子实例）、API/API_PUSH/EMAIL/NAS/SFTP 最后一公里（endpoint 占位或 channel config 种子问题）。
- **P2 未通过项种子补齐**（沉到 `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql` 末尾）：
  - default-tenant 4 条 dispatch channel config 重写：`sftp_bank`→`localhost:12222`（docker SFTP 映射）、`email_ops`→`localhost:1025`（MailHog-ready）、`nas_archive`→`/tmp/batch/nas-probe`、`oss_backup`→`http://localhost:19000`（本地 MinIO），全部对齐 `ChannelConfigMerge` 白名单的 key（sftp_host/smtp_host/oss_bucket/nas_remote_directory 等）。验证：SFTP 真上传到 `/home/ta/inbound/settlement-2026-04-22`；NAS 真写到 `/tmp/batch/nas-probe/settlement-{bizDate}.csv`；LOCAL + OSS 之前已绿。
  - tb 两条文件格式模板：`IMP-TXN-FIXED`（FIXED_WIDTH，record_length=70，6 定宽字段；2 行真实数据落 `biz.transaction`）+ `IMP-TXN-XML`（XML，`parseHints.xmlRecordElement=txn`；2 行真实数据落 `biz.transaction`）。XML 模板必须把 `xmlRecordElement` 放在 `query_param_schema.parseHints` 下（被 `ParseSupport.parseHints` 取），顶层或 `xml_record_element` 作为后备路径；这条踩坑经验也记在种子注释里。
  - 两条探针 workflow：`wf_probe_pipeline`（workflow_type=PIPELINE，START→TASK→END）+ `wf_probe_gateway`（DAG，含 GATEWAY fork、并行两 TASK 分支、`joinMode=ANY` 的 MERGE gateway）。验证：instance 239 真走完 START→FORK(GATEWAY→SUCCESS)→BRANCH_A + BRANCH_B 并行派发（`failed_partition_count=2` 证明两条分支都真实执行），PIPELINE 类型 workflow 派发链路同样走通。MIXED 类型未加种子（code 路径支持，样板需另给）。
- **`ParseSupport.writeParsedRecord` 去硬编码 `CustomerImportPayload`（P1-2 修完）**：`preserveLogicalRow=false` 分支把 row 强转 `CustomerImportPayload` 的代码删除，改为原样 NDJSON 输出。主链路 I/O 形态不变（LoadStep/ValidateStep 本来就按 Map 走），但后续非 customer schema 的 IMPORT 模板即便忘配 `jdbc_mapped_import` 也不会被默默吞字段。`CustomerImportPayload` 类和 LoadStep/DataQuality 的 legacy 重载保留，与硬编码问题无关、等后续统一下线 legacy 路径时再清。同场修掉 `ImportIngressScannerTest` / `GenerateStepTest` 两个旧版构造器调用。

### 2026-04-21
- **Worker `capability_tags` 心跳上报闭环（P0-2 修完）**：让 V4-BUG-2 的 selector 代码修复真正生效。
  - `WorkerConfiguration` 接口加 `default List<String> capabilityTags()`（默认空列表，向后兼容）
  - 3 个 `@ConfigurationProperties` record（`ImportWorkerConfiguration` / `ExportWorkerConfiguration` / `DispatchWorkerConfiguration`）加 `List<String> capabilityTags` 字段 + `@Override` 把 null 归一成 `List.of()`
  - `WorkerRegistration` domain 加 `List<String> capabilityTags`；`AbstractWorkerLoop.ensureStarted` 把 `cfg.capabilityTags()` 塞进 registration；`HttpWorkerRegistryClient.toHeartbeatDto` 用这个值替代原 `null`
  - 3 个 worker 的 `application-local.yml` 声明具体 tag（import=`[ingest]` / export=`[report, workflow]` / dispatch=`[delivery]`）
  - 踩坑提示：只改 `batch-worker-core` 源码后，直接 `mvn package` 下游 worker 模块会用本地 m2 缓存的旧 jar 打包。必须先 `mvn -pl batch-worker-core install -DskipTests`，否则下游 jar 里没有新的 setter 调用。
  - 恢复 `default-tenant` 2 条 queue 的 `resource_tag`（`export_queue=report` / `workflow_queue=workflow`），之前 V4-P0-1 临时清空的状态回滚为正常。
- **多租户业务链路验证批次 v4**：详见 `docs/analysis/fix-report-v4.md` / `docs/analysis/hardening-backlog-v4.md`。修了 2 条代码 bug：
  - `TriggerSecurityConfiguration.InternalSecretFilter.setAuthenticated` 用 `AnonymousAuthenticationToken` 被 Spring Security `.authenticated()` 拒绝（trustResolver 判 anonymous）→ 换 `UsernamePasswordAuthenticationToken.authenticated(...)`。原因：`/api/triggers/management/*` 全线 403 / bypass-mode 失效。
  - `DefaultWorkerSelector.matchesResourceTag` 只比对 `worker.resource_tag` 单值，忽略 `capability_tags` JSONB 数组 → 扩展为"单值等于 OR 数组命中"，同时畸形 JSON 降级为 WARN 不抛。新增 `DefaultWorkerSelectorTest` 6 case。
- **worker-import `business` 数据源 URL 加 `?stringtype=unspecified`**（`application-local.yml`）：`GenericJdbcMappedImportLoadPlugin.setObject(String)` 绑定到 NUMERIC/DATE 列时 Postgres 不隐式 cast，导致 tb TRANSACTION / tc RISK_SCORE 批 LOAD 失败。加参数后服务端按列类型自动转换。
- **DB 改动种子化（Flyway 不承接这类；详见 hardening-backlog-v4 的 V4-P0-1）**：
  - ✅ `biz.transaction` / `biz.risk_score` / `biz.risk_alert` 三张业务表 DDL 已落 `scripts/db/business/create_biz_tables.sql`（含索引 + CHECK）
  - ✅ `tb/IMP-TRANSACTION-CSV`、`tc/IMP-RISK-SCORE-JSON`、`tc/EXP-RISK-ALERT-JSON` 的 `jdbcMappedImport` / `sqlTemplateExport` / `default_query_sql` 已落 `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql`（UPDATE 块在原 INSERT 下方）
  - ❓ `default-tenant/exp_settlement_csv_v1` 源头暂未定位（live DB created_by='system'，可能来自 Console 上传 / 租户初始化服务），留到 P1-1 批次
  - ❌ `default-tenant` `export_queue` / `workflow_queue` `resource_tag` 清空、2 条 `job_definition.enabled=false`（TA_DISPATCH_ORDER / gen_reconcile）不入种子 — 属运维临时动作 / 待 P0-2 根治

### 2026-04-20
- **脏数据入口治理**：新增 `CodeNormalizer` 工具（batch-common/utils），定义两类归一规则：
  - **分组码**（`worker_group` / `tenant_id`）→ UPPER + `^[A-Z][A-Z0-9_]*$` 校验
  - **配置码**（`window_code` / `calendar_code` / `queue_code`）→ LOWER + `-` 替换为 `_` + `^[a-z0-9][a-z0-9_]*$` 校验
  入口侧在 7 个写路径统一调用 `toUpperOrNull` / `toConfigFormOrNull`（宽松版，供 Excel 预览使用）：
  `DefaultConsoleJobDefinitionExcelApplicationService` / `DefaultConsoleWorkflowExcelApplicationService` /
  `DefaultConsoleTenantConfigPackageExcelApplicationService` / `DefaultConsoleJobDefinitionApplicationService` /
  `DefaultConsoleTenantConfigInitApplicationService` / `AbstractWorkerLoop.ensureStarted`（worker 注册源头）/
  `DefaultWorkerSelector.select`（读路径兜底）。
  V64 migration (`V64__normalize_code_conventions.sql`) 一次性归一 5 张表（worker_registry / job_definition /
  job_instance / job_partition / resource_queue / workflow_node / batch_window / business_calendar）的历史存量。
  修的 bug：`IMPORT` vs `import`、`always_open` vs `always-open` 并存导致 ResourceScheduler worker 匹配等值
  比较失配，WAITING 分片永久卡住。
- **`job_instance` / `job_partition` quota check 去除 WAITING 分母**：`countActiveByTenant` / `countActiveAll` /
  `countActiveByFairShareGroup` / `countActiveByTenantAndQueueCode` / `job_partition.countActive*`
  6 个 SQL 把 WAITING 也算作 "active"，而 WAITING 正是被 quota 裁决的候选集——分母含分子 → 死锁
  （WAITING 超过配额就永久卡）。改为仅统计 READY/RUNNING（job_partition 多一个 RETRYING）。
  非 quota 路径（SLA 扫描、UI 活跃计数快照）保留原语义。
- **Worker 心跳超时降级**：新增 `WorkerHeartbeatTimeoutScheduler`（batch-orchestrator），
  30s 周期 + ShedLock(PT2M)；超时阈值 `batch.worker.drain.heartbeat-timeout-seconds` 默认 90s。
  把 `status IN ('ONLINE','DRAINING') AND heartbeat_at < cutoff` 的 worker 批量降为 OFFLINE；
  不动 DECOMMISSIONED。修的 bug：系统之前没有心跳超时清理，worker 进程 crash 后 DB 里 status 仍为
  ONLINE，WorkerSelector 选中它 release partition → partition 永远不会被 claim。
- **触发器控制面收敛：`job_definition.enabled` 作为权威，Quartz 异步对账**（修 "job 被 toggle=false 但 Quartz 还 fire → orchestrator 404 刷日志" 的长期 bug）：
  - 新增 `TriggerReconciler`（batch-trigger/infrastructure/scheduler）：`@Scheduled(fixedDelay=30s)` + `ShedLock PT5M` + `@EventListener(ApplicationReadyEvent)` 首轮立扫；比较 `job_definition` 权威集合与 Quartz `GroupMatcher.jobGroupEquals(JOB_GROUP)` 列表，DB-有-Quartz-无→`registerByJobCode`，DB-无-Quartz-有→`unregisterByJobCode`；`TriggerGracefulShutdown.isDraining()` 时跳过。
  - 删除 `TriggerRegistrationStartup`（`ApplicationRunner` 启动注册）+ 配套 IT `TriggerRegistrationStartupIntegrationTest`，由 reconciler 的启动期首轮覆盖同等语义。
  - `TriggerSchedulerFacade.JOB_GROUP` 可见性从 package-private 提升为 `public`（reconciler 跨包引用）。
  - **控制面职责重划**：console `ConsoleTriggerController` 的 5 条路由 `/api/console/triggers/**` → `/api/console/ops/triggers/**`（list/{jobCode}/register/unregister/pause/resume），定位为 **Ops 救急入口**（DB 与 Quartz 漂移时的强制修复），日常业务走 `toggleEnabled`；控制器 javadoc + OpenAPI tag 加警告："在此注销 enabled=true 的 job 会被下一次 reconcile 重建"。`ConsoleRateLimitFilter.TRIGGER_PATH_PREFIX` 同步更新。
  - 对账周期配置项 `batch.trigger.reconcile-interval-millis`（默认 30000）。
  - 守护：`TriggerReconcilerTest` 6 个 case 覆盖 DB-only / Quartz-only / 对齐 / 禁用 / draining / 畸形 JobKey。
- **新增 §时区策略 + 全局时区 provider**：`BatchTimezoneProperties` (`batch.timezone.default-zone`，默认 `Asia/Shanghai`) + `BatchTimezoneProvider` bean；业务路径上的 `ZoneId.systemDefault()` 统一替换为 `provider.defaultZone()` / `provider.resolveOrDefault(tz)`，9 处调用点完成迁移（`LaunchBatchDayService` / `LaunchParamResolver` / `CalendarBizDateResolver` / `DefaultLaunchAdapterService` / `DefaultResourceScheduler` / `BatchDayCutoffScheduler`×2 / `QuotaRuntimeStateService`）。`QuotaResetPolicy.systemZone()` 打 `@Deprecated`；`ConsoleQuerySupport` 宽松日期解析作为明确豁免保留。`batch-defaults.yml` 补 `spring.jackson.time-zone`。
- **V62 迁移：重跑语义 + 批次日并发 + 时区快照**（对齐 `docs/analysis/deep-issue-analysis-v3.md` 的五点设计灰色地带）：
  - `job_instance` 新增 `run_attempt INT NOT NULL DEFAULT 1` + `ck_run_attempt >= 1`；唯一键由 `(tenant_id, dedup_key)` 改为 `(tenant_id, dedup_key, run_attempt)`，同一 (job, biz_date) 可持有多次 attempts。
  - `TriggerType` 新增 `RERUN`（重跑触发）；`job_instance` / `trigger_request` 的 `ck_*_trigger_type` CHECK 同步扩展；console `/meta/enums.triggerType` 由反射自动下发不用改 schema。
  - `batch_day_instance` 加 `version BIGINT NOT NULL DEFAULT 0`（Spring Data JDBC `@Version` 乐观锁）+ `timezone_snapshot VARCHAR(64) NOT NULL DEFAULT 'UTC'`（创建时从日历 timezone 抓快照，后续回放不受日历改 tz 影响）。
- **`DefaultLaunchService.launch` 承认 RERUN 语义**：triggerType=RERUN 时不走"existing-instance=duplicate"短路；新建实例时 run_attempt = MAX+1，parent_instance_id 指回上次 attempt；RERUN 并发撞 23505 直接抛，不再误判为幂等重复。
- **`BatchDaySettleScheduler` 状态机竞态收口**：`settle()` 去掉 @Transactional，改为循环内 `selfProvider.getObject().settleOne(candidate, now)`（`REQUIRES_NEW`），单条 CAS 冲突只跳过该条，其他候选不受影响；捕获 `OptimisticLockingFailureException` 日志后下 tick 重扫。
- **`LaunchBatchDayService.routeLateArrivalIfNeeded` 原子化**：late-rejected 路径从"先改内存 → 再 UPDATE DB"改为 `updateTriggerType(..., expectedTriggerType='EVENT')` CAS；ROW=0（并发已改走）直接返回原 request，不再覆盖内存。`TriggerRequestMapper.updateTriggerType` 签名加 `expectedTriggerType` 参数，XML WHERE 带 `and trigger_type = #{expectedTriggerType}`。

### 2026-04-19
- **新增 §字符编码 + §配置开关规范**：两节详细落地清单移到 `docs/coding-conventions.md §20 / §21`，CLAUDE.md 仅保留核心规则指针。
- **`testing-open` → `bypass-mode`**：`BatchSecurityProperties` 字段/方法重命名，16 处业务 + 9 处测试全部迁移；保留 `setTestingOpen` setter + `@DeprecatedConfigurationProperty` 兼容旧键一个版本。默认值矩阵：IDE local=`true` / docker-compose=`false` / prod=`false` + @PostConstruct 守护。
- **全栈 UTF-8 契约**：新建 `EncodingUtils`（10 处 `Charset.forName(...)` 迁移），`Dockerfile.app` + `batch-defaults.yml` + 根 pom 补 UTF-8 配置；`docker-compose.yml` 四个中间件统一 `BATCH_LOCALE=C.UTF-8`。导出硬编码 UTF-8，导入保留 `file_template_config.charset` 透传。
- **§领域数据字典 `schedule_type` 与枚举对齐**：删除文档中多出的 `EVENT` / `ONE_TIME`（实际枚举仅 3 值）；起因是 tc Excel 按旧文档填 `EVENT` 被 validator 判 invalid 导致整包 apply 回退。

### 2026-04-18
- **版本管理改 Maven CI-friendly `${revision}`**：根 pom 统一入口（默认 `1.0.0`，非 SNAPSHOT），11 个子模块改用 `${revision}`；根 pom 加 `flatten-maven-plugin` 保证 install/deploy 后下游能解析；`build-apps.sh`、`docker/Dockerfile.app` 由 `-Dmaven.test.skip=true` 改为 `-DskipTests`（前者会阻断 `batch-common:tests` 依赖链）；`scripts/ci/security-scan.sh` 硬编码 jar 路径改为 glob 匹配。文档示例（`docs/design/*.md` / `docs/runbook/security-scan.md` / `security-scan/README.md`）同步。
- 新增 **§版本管理** 和 **§变更记录** 两节；§Java 代码风格 提示 Lombok 枚举样式。

### 2026-04-17
- **`CodedEnum` 接口重命名为 `DictEnum`**：与项目术语"字典"对齐；76 个文件的 `implements` / `import` / 调用处同步，跨 60 个枚举 + 10 个 Excel 应用层 + 守护测试 + 协议文档。
- **60 个枚举统一 Lombok 样式**：`@RequiredArgsConstructor` + `@Accessors(fluent = true)` + `@Getter`，删除所有手写构造器和 `code()` / `label()` accessor；每个枚举从 20+ 行样板降至 ~10 行。
- **抽取 `DictEnum.fromCode` / `codes` / `labels` 静态工具**：消除 35 个枚举各自的 `public static Set<String> codes()` 副本、5 个枚举各自的 `fromCode` 循环体；5 个有特殊语义的 `fromCode`（`CatchUpPolicyType` / `WorkflowJoinMode` / `ShardStrategy` / `RunMode` / `FileStatus`）改为薄包装。§领域数据字典 更新 Lombok 样板说明 + 通用工具说明。
- **`ConsoleMetaQueryService.buildEnums` 新增 20 个枚举 key**：`priorityLevel` / `aiPromptDecision` / `checksumType` / `workflowJoinMode` / `fileDispatchRunStatus` / `fileDispatchStatus` / `fileReceiptStatus` / `pipelineRunStatus` / `compensationStatus` / `retryScheduleStatus` / `encryptType` / `compressType` / `errorSinkType` / `priorityBand` / `stepInstanceStatus` / `runMode` / `skipAction` / `workflowNodeRunStatus` / `deadLetterReplayStatus` / `skipThresholdMode`；同步 OpenAPI `CommonResponseMetaEnums` schema。
- **新增守护测试 `ConsoleMetaEnumRegistrationTest`**：扫描 `com.example.batch.common.enums` 包，强制每个公共枚举二选一 —— 要么在 `REGISTRATIONS` 里注册暴露给前端，要么加入 `EXCLUDED` 白名单并注明原因；同时断言所有公共枚举必须实现 `DictEnum`。
- `ConsoleMetaQueryService.EnumReg` 精简为 `(key, enumClass)` 两字段；委托 `DictEnum::code`、`DictEnum::label`。
