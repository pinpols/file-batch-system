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
- **调用**：业务代码一律 `isBypassMode()`；旧键 `testing-open` / `isTestingOpen()` 已 deprecated，下一 minor 版本移除

**ADR-010 trigger 异步解耦总开关 `batch.trigger.async-launch.enabled`**（**默认 `true`**，2026-04-30 起切换）：trigger fire → 同事务写 `trigger_outbox_event` → `TriggerOutboxRelay` 周期发到 Kafka topic `batch.trigger.launch.v1` → orchestrator `TriggerLaunchConsumer` 消费触发 launch。回退到原同步 HTTP 路径(`HttpOrchestratorTriggerAdapter`，已标 `@Deprecated forRemoval=true`)：显式 `BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=false`。**两边开关必须一致**(trigger 不发但 orchestrator 起 listener 浪费连接；trigger 发了但 orchestrator 不接更危险)。灰度切换 / 回滚 / 24h 对账步骤见 `docs/runbook/trigger-async-launch-rollout.md`。

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

**Maven CI-friendly 版本策略**：全项目采用 `${revision}` 占位符，版本在根 pom `<properties><revision>` 单点控制，默认 **`1.0.0`**（非 SNAPSHOT）。

- 默认构建：`mvn package` → 产物 `batch-*-1.0.0.jar`
- 覆盖版本：`mvn -Drevision=2.0.0 package`
- 根 pom 装配 `flatten-maven-plugin`，install/deploy 前自动把 `${revision}` 在 pom 里展开为实际版本号，下游消费者能正确解析
- **build script 禁用 `-Dmaven.test.skip=true`**（会同时跳过 test-jar 生成，打断 `batch-common:tests` 依赖链）；统一用 `-DskipTests`（只跳执行，保留 test-jar 产物）
- `load-tests` 是独立模块（未纳入根 reactor），版本使用字面量，与根版本手工保持同步

## 架构硬约束

- 任务分发主链：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- Orchestrator 是唯一状态主机；Worker 不能直接改写 job_instance / workflow_run / workflow_node_run
- **Console-api 也不能直接 UPDATE/DELETE outbox_event**（runtime 状态表外延）；运维操作（cleanup / republish）必须经 `ConsoleOrchestratorProxyService` 转发到 orchestrator `/internal/outbox/*` 接口执行
- outbox_event 必须与任务状态写入处于同一事务
- Worker 执行前必须先 CLAIM，不能绕过
- 禁止 JPA/Hibernate；持久层 MyBatis（运行态）/ Spring Data JDBC（配置态）不混用
  - **Console-api 豁免**：console-api 因 read-write 混合（既读 runtime 状态表又写配置表）+ 复杂分页/聚合查询，配置表写入也使用 MyBatis（如 `secret_version` / `workflow_node` / `tenant_quota_policy` / `file_channel_config` / `pipeline_step_definition` / `alert_routing_config` / `calendar_holiday` / `config_change_log`）。orchestrator 内仍严格按运行态 MyBatis / 配置态 Spring Data JDBC 分层
- **读写分离仅 console-api 启用**；主链路（trigger / orchestrator / worker）严禁引入。原因：状态机依赖 read-after-write 强一致性（`INSERT job_instance` → 立即 `SELECT` 验证、worker `CLAIM` 后立即读自己的 lease），PG 异步流复制秒级延迟会引入 race condition；且这些模块写为主，读路径分离也无收益。详见 `docs/runbook/read-replica.md` §六。
- **模块不得覆盖 batch-common AutoConfiguration 的基础设施 bean**（`taskScheduler` / `lockProvider` / 等）。要定制行为就提供扩展点 bean 让 AutoConfiguration 通过 `ObjectProvider` 注入（例：`SchedulerErrorHandlerConfiguration` 只暴露 `ErrorHandler` bean，不重新定义 `taskScheduler`）。重复 `@Bean` 同名定义会触发 `BeanDefinitionOverrideException` 启动失败。

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
`batch-worker-process` / `batch-worker-dispatch` / `batch-console-api`
