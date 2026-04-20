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
