# file-batch-system 项目编码规范

> **维护规则**：任何影响**本文件已有规范条款**（编码约定、架构约束、版本策略、模块边界、领域字典等）的改动，必须同步追加到 [`docs/changelog.md`](docs/changelog.md)（按日期倒序，使用当天绝对日期）。
>
> Feature 完成、bug 修复、运维操作等项目演进信息，以 git commit + PR 描述 + 对应模块文档（`docs/architecture/*.md`、`docs/runbook/*.md`、`docs/analysis/*.md`）为权威，**不要**写到本文件，避免文件膨胀。

## 方法参数约束

**方法参数数量不能超过 6 个（含 6 个）。**

- 参数 ≥ 7：必须封装为参数对象（Command / Context / Request / Param）
- 参数 6：建议封装，Mapper 公共方法和 Service 公共接口必须封装
- 构造器（record、DTO、Response、data holder、Spring DI 注入）不受此约束
- 封装类型优先选用 `private record`（私有方法）或独立 Command/Param 类（公共接口）

### 调用方约束（方法实参里 inline `new` 长构造）

封装为 Param/Command 类后，调用方禁止把臃肿"搬到 `new XxxParam(...)`"。具体规则按 Param 类构造参数数 `argc`：

| 类构造参数数 `argc` | 调用方动作 |
|---|---|
| **`argc > 6`** | 该类**必须加 `@Builder`** + 调用方**先建引用变量再传**：`Type t = Type.builder()....build(); f(t);` —— null/false/0 字段不显式 set，靠 Lombok 默认值。**禁止** `f(Type.builder()....build())` 任何 inline build 在方法实参位置 |
| **`argc ≤ 6`** | 不约束（业界无依据：Effective Java / Google Style / Oracle Conventions 都未禁止 `f(new Foo(a,b,c,d,e,f))`） |

**允许 `Type.builder()....build()` 直接出现的位置**仅 2 个：
1. `Type t = ....;` 赋值右侧
2. `return ....;` 单一 return（不嵌套）

**豁免**：
- 声明式注册类（`*Registry` / `*SchemaRegistry` / Spring `@Configuration` 列表 / `List.of(new Foo(...))` 等）—— inline new 在声明式数据结构里是业界鼓励的可读写法，不算反例
- **Spring Data JDBC / JPA `@Modifying @Query` 接口方法多 `@Param`** —— 框架契约（`:paramName` 命名参数解析依赖位置 `@Param`，bean property 引用未原生支持），保留多 `@Param` 形式即可。MyBatis mapper 可封装（原生支持 `#{p.field}`），但不强制

### 加 `@Builder` 时空参构造的注解兜底

`@Builder` 在普通 class 上会生成 `@AllArgsConstructor`，**消除隐式空参** → 反射路径（Jackson / MyBatis / Spring `@ModelAttribute`）会 break。**禁止降级**到"仅提取引用"，**用注解兜底**：

| 类形态 | 注解组合 |
|---|---|
| record | `@Builder` 单注解（record 本无空参，无影响） |
| class 已有 `@Data` / 显式 `public Foo() {}` | `@Builder` 单注解（已有空参不冲突） |
| class 仅有隐式空参（无任何构造器） | `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor` 三连 |
| class 已有自定义带参构造 | `@Builder` + `@Tolerate` 显式声明空参 |

**铁律**：加 `@Builder` 不允许破坏 class 的反射构造路径。

### 字段顺序与 entity 红线

- 加 `@Builder` 时**禁止重排 record 字段**（保护 mybatis xml `#{q.xxx}`、保护 canonical constructor 调用方）
- **Spring Data JDBC entity / `@Entity` / `@Table` 持久化类一律不加 `@Builder`**（侵入持久化路径）
- **禁止重命名任何字段**

详见 `docs/analysis/positional-args-cleanup-plan.md` v2 治理方案。

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
- `job_type`：`GENERAL` / `IMPORT` / `EXPORT` / `PROCESS` / `DISPATCH` / `WORKFLOW`（`JobType`）
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
- **调用**：业务代码一律 `isBypassMode()`；旧键 `testing-open` / `isTestingOpen()` 已于 2026-05-01 物理删除（commit `753e6393`），不再接受任何兼容写法

**ADR-010 trigger 异步链路（已固化，无开关）**：trigger fire → 同事务写 `trigger_outbox_event` → `TriggerOutboxRelay` 周期发到 Kafka topic `batch.trigger.launch.v1` → orchestrator `TriggerLaunchConsumer` 消费触发 launch。原同步 HTTP 桥（`HttpOrchestratorTriggerAdapter`）已于 2026-05-02 删除，`batch.trigger.async-launch.enabled` 开关同步下线。

详见 `docs/coding-conventions.md §21` + `docs/runbook/feature-switches.md`。

## i18n 错误码规范

**业务异常一律走 i18n key**：`throw BizException.of(ResultCode.XXX, "error.<scope>.<reason>", args...)`，不要再用旧 literal 构造器 `new BizException(code, message)`（仅 Guard 等工具类签名豁免）。

- **key 命名**：`error.<scope>.<reason>`，全小写 + snake_case 分隔符 `_`，禁用驼峰/连字符。`<scope>` 单数业务域（`tenant`/`job`/`workflow`/`process`/`import`/`export`/`dispatch`/`worker` ...），`<reason>` 简短失败原因（`not_found`/`already_exists`/`invalid_format`/`state_conflict` ...）
- **占位符**：`{0}` / `{1}` / `{2}` 与 `args` 顺序一一对应；MessageFormat 单引号 `'` 要双写 `''`
- **双语强制**：每个 key 必须同时在 `messages.properties`（en）+ `messages_zh_CN.properties`（zh）落地，1:1 对齐（缺 key 触发 fallback）
- **持久化层**：业务实体写错误时实现 `LocalizedErrorCarrier` 接口，11 张表的 `error_key` + `error_args` 列由 `BizExceptionUtils.toLocalizedError` 自动填充；console 读路径过 `LocalizedErrorRenderer` 按当前 Locale 重渲染历史失败记录

详见 `docs/design/i18n.md`。

## Workflow 节点参数 DSL 规范（ADR-009）

`workflow_node.node_params` JSONB 中的 value 支持引用上游节点产出，由 `WorkflowParamResolver` 在派发前解析：

- **支持语法**（受限 JSONPath 子集）：
  - `$.nodes.<nodeCode>.output.<key>` — 引用同 workflow_run 内某节点 output 字段（嵌套用 `.` 下钻）
  - `$.workflowRun.<key>` — 引用 workflow_run 级共享字段（`bizDate` / `traceId`）
- **不支持**：通配符 `*`、过滤 `[?]`、函数 `length()`、表达式 `$ + 1`
- **Worker 暴露的 output key**（按业务领域）：
  - IMPORT: `fileId` / `recordCount` / `parsedCount` / `validatedCount` / `skippedCount` / `bizDate`
  - EXPORT: `fileId` / `objectName` / `recordCount` / `fileSizeBytes` / `checksumValue` / `bizDate`
  - PROCESS: `processedCount` / `stagedCount` / `publishedCount` / `batchKey` / `highWaterMarkOut`
  - DISPATCH: `fileId` / `receiptCode` / `receiptStatus` / `externalRequestId` / `channelCode`
- **Fail-mode**：未知 nodeCode / 路径语法非法 → `BizException(error.workflow.param_ref_invalid)` 拒绝节点启动；output 字段缺失 → null fallback 让业务侧兜底

详见 `docs/architecture/workflow-dependency-guide.md §10` + ADR-009。

## 版本管理

**SemVer 2.0.0 + Maven CI-friendly `${revision}`** 单点控制，9 模块共版（不抄 Spring Cloud Release Train / CalVer，单 repo 不需要 BOM 协调）。

- 版本号格式：`MAJOR.MINOR.PATCH[-PRERELEASE]`（详见 [`docs/runbook/releasing.md`](docs/runbook/releasing.md)）
  - `MAJOR` 不向后兼容；`MINOR` 向后兼容新功能；`PATCH` bug fix
  - `-SNAPSHOT` 开发分支（main 分支默认形态）；`-RC.N` release candidate
- 当前状态：`v1.0.0` 已 release（commit `525e60f0`），main 分支默认 `<revision>1.1.0-SNAPSHOT</revision>` 累积下一版功能
- 默认构建：`mvn package` → 产物 `batch-*-${revision}.jar`
- 覆盖版本：`mvn -Drevision=1.1.0 package`（release 时用）
- 根 pom 装配 `flatten-maven-plugin`，install/deploy 前自动把 `${revision}` 在 pom 里展开为实际版本号，下游消费者能正确解析
- Git tag 规范：`v<version>`，annotated tag (`git tag -a v1.1.0 -m "..."`)；描述性 tag（如 `stable-*`）可与版本 tag 并存
- **build script 禁用 `-Dmaven.test.skip=true`**（会同时跳过 test-jar 生成，打断 `batch-common:tests` 依赖链）；统一用 `-DskipTests`（只跳执行，保留 test-jar 产物）
- `load-tests` 是独立模块（未纳入根 reactor），版本使用字面量，与根版本手工保持同步

完整 release flow（含 hotfix / RC / patch）见 [`docs/runbook/releasing.md`](docs/runbook/releasing.md)。

## 架构硬约束

- 任务分发主链：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- Orchestrator 是唯一状态主机；Worker 不能直接改写 job_instance / workflow_run / workflow_node_run
- **Console-api 也不能直接 UPDATE/DELETE outbox_event**（runtime 状态表外延）；运维操作（cleanup / republish）必须经 `ConsoleOrchestratorProxyService` 转发到 orchestrator `/internal/outbox/*` 接口执行
- outbox_event 必须与任务状态写入处于同一事务
- Worker 执行前必须先 CLAIM，不能绕过
- 禁止 JPA/Hibernate；**全业务模块**持久层**统一 MyBatis**（运行态与配置态均 Mapper）+ `JdbcTemplate` 基础设施；**禁止** Spring Data JDBC（见 ADR-001）
  - **`batch-console-api`** / **`batch-orchestrator`** / **`batch-trigger`** / **`batch-worker-*`** 均不引入 `spring-boot-starter-data-jdbc`
  - 表行映射类型放在 `domain/entity`，统一 **`*Entity` 后缀**（`record` 或 `@Data` class）；**禁止**用 `*Record` 后缀区分技术栈；**同一表、同一写路径**禁止 Mapper 与 Spring Data `Repository` 双主入口
- **读写分离仅 console-api 启用**；主链路（trigger / orchestrator / worker）严禁引入。原因：状态机依赖 read-after-write 强一致性（`INSERT job_instance` → 立即 `SELECT` 验证、worker `CLAIM` 后立即读自己的 lease），PG 异步流复制秒级延迟会引入 race condition；且这些模块写为主，读路径分离也无收益。详见 `docs/runbook/read-replica.md` §六。
- **模块不得覆盖 batch-common AutoConfiguration 的基础设施 bean**（`taskScheduler` / `lockProvider` / 等）。要定制行为就提供扩展点 bean 让 AutoConfiguration 通过 `ObjectProvider` 注入（例：`SchedulerErrorHandlerConfiguration` 只暴露 `ErrorHandler` bean，不重新定义 `taskScheduler`）。重复 `@Bean` 同名定义会触发 `BeanDefinitionOverrideException` 启动失败。

## 时区策略

**全系统默认时区 = `batch.timezone.default-zone`（默认 `Asia/Shanghai`，UTC+8）。业务代码禁止直接调用 `ZoneId.systemDefault()`——它依赖容器 TZ / JVM `user.timezone`，不同部署环境会静默漂移。**

- **读取入口**：注入 `com.example.batch.common.config.BatchTimezoneProvider`
  - `provider.defaultZone()` — 平台默认 `ZoneId`（永远非 null）
  - `provider.resolveOrDefault(preferred)` — 优先 IANA 字符串（如 `business_calendar.timezone`），空/非法时回退到默认
- **业务覆盖优先级**：`business_calendar.timezone` > `batch_window.timezone` > `batch.timezone.default-zone`。`batch_day_instance.timezone_snapshot` 在创建时从日历抓快照，之后日历改 timezone 不影响历史数据回放。
- **容器 / JVM 协同**：`docker-compose.yml` + `.env.example` 以 **`BATCH_TIMEZONE_DEFAULT_ZONE`** 为唯一时区 env 源，容器 `TZ`/`PGTZ` 均从该变量派生，避免业务时区和运行时默认时区漂移；`batch-defaults.yml` 的 `spring.jackson.time-zone` 与 `batch.timezone.default-zone` 同源；本地 `start-all.sh`/`restart.sh` 会 source 同一份 env 并导出 `TZ`（等于 `BATCH_TIMEZONE_DEFAULT_ZONE`）及 `LANG`/`LC_ALL`（等于 `BATCH_LOCALE`）。
- **控制台宽松日期解析**：`ConsoleQuerySupport.parseFlexibleInstant*` 的 naive 字符串须显式传入 `ZoneId`；控制台查询服务传入 `BatchTimezoneProvider.defaultZone()`，与平台默认区一致（禁止在该路径使用 `ZoneId.systemDefault()`）。
- **守护**：新增 `ZoneId.systemDefault()` 用法必须在业务路径之外且加注释说明原因；否则 PR 评审拒绝。

## 字符编码

**全系统 UTF-8**：系统内部持有、传输、存储、落盘的字符串一律 UTF-8；**导入**（`PreprocessStep`）是唯一允许读非 UTF-8 源文件的边界，读入后立即转为 UTF-8 内部表示。

- **Spring 基线**：`server.servlet.encoding` / `spring.messages.encoding` 在 `batch-defaults.yml` 固定 UTF-8，与以下 locale env 对齐即可，无需再配单独「charset」环境变量。
- **代码**：一律 `StandardCharsets.UTF_8` 或 `EncodingUtils.resolve(raw)`，**禁止** `Charset.forName("UTF-8")` / 字面量 `"UTF-8"`
- **中间件 / 应用容器 locale**：`docker-compose.yml`、`docker-compose.app.yml`、Helm ConfigMap 统一从 `.env`/values 的 **`BATCH_LOCALE`**（默认 `C.UTF-8`）派生 `LANG` / `LC_ALL`；postgres 另加 `POSTGRES_INITDB_ARGS=--encoding=UTF8`

详见 `docs/coding-conventions.md §20`（含 Maven / Dockerfile / Spring Boot / Export / Import 全层落地表）。

## 多租隔离

**所有业务表必须携带 `tenant_id` 列；所有 UNIQUE/PRIMARY 约束必须包含 `tenant_id`（直接含或通过 FK 间接保证）。**

- **直接含**（首选）：`UNIQUE (tenant_id, code)` —— SELECT 路径 PG planner 直接走 (tenant_id, ...) 复合索引
- **间接含**（次选）：`UNIQUE (parent_definition_id, code)`，前提是 parent_definition_id 已带 tenant_id 约束 —— 不推荐，未来加 partition / 分库时要先补列
- **禁止**：`UNIQUE (definition_id, code)` 而 definition 表带 tenant_id 但本表不带 —— 跨表 JOIN 才能租户过滤，违反"主表自闭"原则
- **合法豁免（4 张系统表）**：
  - `batch_runtime_default_parameter` — 模块级全局参数
  - `step_registry` — 应用启动 bean 白名单上报
  - `shedlock` — ShedLock 官方表
  - `biz_table_schema` — 目标库 schema 元数据（建议后续也加 tenant_id）
- **守护**：`ArchiveSchemaDriftCheck` 已覆盖 archive 对齐；新增多租约束应补 `TenantIsolationIntegrationTest` 断言。

详见 [PG Schema 审计 2026-05-03 §P0（archive）](docs/archive/analysis/pg-schema-audit-2026-05-03.md)、V82-V85 落地。

## archive 冷表对齐

**热表 `batch.*` 与归档表 `archive.*_archive` 必须 1:1 字段镜像。**

- **拦截机制**：`ArchiveSchemaDriftCheck` 启动期 `@EventListener(ApplicationReadyEvent.class)` 双向 diff 已注册的归档对照表，差异即 `IllegalStateException` fail-fast；启动失败比静默归档差异更安全
- **演进规则**：任何 `ALTER TABLE batch.* ADD COLUMN` 必须**同 PR** 补齐 `ALTER TABLE archive.*_archive` 的 migration（参考 V77 → V79 三轮 i18n 列同步）；新增主表带归档时同步把表名加入 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES`
- **覆盖范围（2026-05-07 现状）**：17 张（V108 加 result_version；V110 加 batch_day_replay_session + batch_day_replay_entry）；V116 forensic_export_log / V118 data_quality_rule / V118 data_quality_check 暂未入 ARCHIVED_TABLES（独立运维域，待启用归档时注册）；定义表（job_definition / workflow_definition 等）不入归档，无需对齐
- **未来演进**：考虑 PG 15+ `MERGE INTO` 或 declarative partition + `DETACH PARTITION` 替代物理双表，彻底消除人工 sync

详见 `batch-orchestrator/src/main/java/.../infrastructure/archive/ArchiveSchemaDriftCheck.java`。

## 异步事件路由政策

**三张异步表各司其职，禁止相互复用，禁止新增第 4 张同义表。**

| 表 | 用途 | 写入方 | 消费 topic |
|---|---|---|---|
| `outbox_event` | 通用业务事件（订单 / 配置变更 / 通知） | orchestrator + business domain service | `batch.event.*` |
| `event_outbox_retry` | `outbox_event` 投递失败的退避重试队列 | OutboxPollScheduler 内部转移 | 同上（重投） |
| `trigger_outbox_event` | trigger fire → orchestrator launch 的调度事件 | batch-trigger（与 trigger_request 同事务） | `batch.trigger.launch.v1` |

- **不变量**：`(tenant_id, request_id)` 在 trigger_outbox_event 唯一，业务幂等兜底
- **状态机**：两张 outbox 都用 `OutboxPublishStatus` enum {NEW, PUBLISHING, PUBLISHED, FAILED, GIVE_UP}；event_outbox_retry 用独立 retry_status enum
- **新事件场景判定**：先问"是不是 trigger fire？" 是 → 走 trigger_outbox_event；否 → 走 outbox_event。**禁止**新事件类型自创第 4 张表

详见 `docs/architecture/event-routing-policy.md`。

## Pipeline vs Workflow vs Job 边界

**三套体系职责切分清楚，禁止混用、禁止 UNION 跨表查询。**

| 体系 | 定义表 | 运行表 | 适用场景 |
|---|---|---|---|
| **Pipeline** | `pipeline_definition` `pipeline_step_definition` | `pipeline_instance` `pipeline_step_run` | 文件处理流水线（IMPORT/EXPORT/DISPATCH 固定 9 stages，内置不可扩） |
| **Workflow** | `workflow_definition` `workflow_node` `workflow_edge` | `workflow_run` `workflow_node_run` | 用户 DAG 编排（任意 Job 组合 / GATEWAY 分支 / 补偿 / 审批） |
| **Job** | `job_definition` | `job_instance` `job_partition` `job_task` | 单个执行单元（GENERAL / IMPORT / EXPORT / DISPATCH / WORKFLOW） |

- **业务约束**：
  - ✗ 禁止 `SELECT FROM pipeline_instance UNION SELECT FROM workflow_run`
  - ✓ pipeline_instance 只读（worker 内部记录，运维不介入）
  - ✓ workflow_run 支持人工干预（审批 / 重跑 / 补偿）
- **新增编排选哪边**：固定 9 阶段文件处理 → pipeline；用户自定义图 / 多 Job 组合 → workflow；单一 Job 执行 → job
- **跨域引用**：workflow_node.related_pipeline_code 指向 pipeline_definition.job_code（FILE_STEP 节点专用），不要反向

详见 `docs/design/pipeline-vs-workflow-definition.md`。

## 模块边界

模块结构固定，不可擅自增删：
`batch-common` / `batch-trigger` / `batch-orchestrator` /
`batch-worker-core` / `batch-worker-import` / `batch-worker-export` /
`batch-worker-process` / `batch-worker-dispatch` / `batch-console-api`

## ADR 实施范围纪律（防越界）

**系统定位写死**：批量运行控制面 + 文件 / 任务交付闭环。**不扩张**为企业数据治理 / 容器资源编排 / 合规审计平台。详见 [`docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md)（已 fold 进本节）。

### 三阶段优先级

| 阶段 | ADR | 触发条件 |
|---|---|---|
| **P0 第 1 阶段必做** | ADR-012 失败分类 / ADR-023 多日历联动 / ADR-025 Workflow 静态校验 | 已就绪可直接做 |
| **P1 第 2 阶段应做但收敛边界** | ADR-021 数据对账 / ADR-022 Forensic / ADR-026 dry-run | 各自实施触发条件出现即开工，但严守 ❌ 红线 |
| **P2 第 3 阶段暂缓** | ADR-024 冷热分层 / ADR-027 资源亲和 | 触发条件未到不开工（archive 行数 / 多机房 / 异构硬件 / 合规隔离 / worker_group ≥ 8 等） |

### 4 个最高越界风险 ADR 的判定提问（实施 / PR 评审必看）

| ADR | 判定提问 | 一句话越界红线 |
|---|---|---|
| **ADR-021** 数据对账 | 「修业务数据」vs 「裁定业务对错」 | 后者拒收：主数据 / 财务核算 / 数据治理 / 数据血缘 |
| **ADR-022** Forensic | 「按 bizDate 圈定批次取证包」vs 「实时合规审计流」 | 后者拒收：SIEM / Splunk 接入 / 合规调查工作流 |
| **ADR-026** dry-run | 「看配置 / 看会不会跑 / 看 SQL 能跑」vs 「看业务结果对」 | 后者拒收：FULL_SIMULATION / 事务回滚 / 真发 Kafka 不消费 / mixed mode / 复用 bypass-mode |
| **ADR-027** 资源亲和 | 「挑 worker」vs 「挑机器」 | 后者拒收：自研 K8s Scheduler / PodAffinity / TopologySpreadConstraints / 节点拓扑调度 / multi-objective optimization |

### PR 评审硬规则

- 上述 4 个 ADR 的实施 PR 必须在描述里**答出判定提问** + 引用对应 ADR 文档"❌ 不做"清单
- 评审者发现越界（即使代码功能正确）必须 reject 并要求拆为后续 ADR
- 第 3 阶段（ADR-024/027）启动必须先行触发条件证据（监控数据 / 业务诉求工单），否则不开工
- 各 ADR 文档顶部"范围边界（Scope Discipline）"小节是单一权威源，与本节冲突以 ADR 文档为准

详见各 ADR 文档 + [`docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md) §5 一句话越界风险表（原档已归档，本节为权威）。
