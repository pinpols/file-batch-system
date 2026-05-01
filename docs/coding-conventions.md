# file-batch-system 编码规约

> 本文档总结项目中实际遵循的编码规范与设计约定，供团队成员参考。

---

## 1. 方法参数约束

| 参数数量 | 要求 |
|---------|------|
| ≤ 5     | 允许直接传参 |
| = 6     | 建议封装；Mapper 公共方法和 Service 公共接口 **必须** 封装 |
| ≥ 7     | **必须** 封装为参数对象 |

**封装类型选择：**
- 私有/内部方法 → `private record`（嵌套在使用类内部）
- 公共接口 → 独立 `Command` / `Param` / `Context` / `Request` 类

```java
// 公共接口参数对象
public record CompensationSubmitCommand(String tenantId, String jobCode, ...) {}

// 私有内部参数对象
private record BadRecordContext(ImportJobContext context, ImportStage stage, Long recordNo, ...) {}
```

**豁免场景：** 构造器（record、DTO、Response、data holder、Spring DI 注入）不受此约束。

---

## 2. 全限定类名（FQN）禁令

**禁止在代码中使用全限定类名** —— 必须通过 `import` 导入后使用短名。

```java
// ✅ 正确
import java.util.concurrent.TimeUnit;
TimeUnit.SECONDS.sleep(1);

// ❌ 错误
java.util.concurrent.TimeUnit.SECONDS.sleep(1);
```

注解同理：写 `@MockitoSettings` 而非 `@org.mockito.junit.jupiter.MockitoSettings`。

---

## 3. 依赖注入

### 3.1 唯一方式：构造器注入

项目统一使用 **Lombok `@RequiredArgsConstructor` + `final` 字段** 实现构造器注入。

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTriggerService implements TriggerService {
    private final TriggerRequestRepository triggerRequestRepository;
    private final TransactionTemplate transactionTemplate;
    // 无需手写构造器，Lombok 自动生成
}
```

### 3.2 禁止

- **禁止** `@Autowired` 字段注入
- **禁止** setter 注入
- **禁止** 在非 `@Configuration` 类中使用 `@Bean` 方法注入

### 3.3 @Configuration 类

配置类统一使用 `proxyBeanMethods = false` 以提升启动性能：

```java
@Configuration(proxyBeanMethods = false)
class ConsoleKafkaConfiguration {
    @Bean
    KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }
}
```

---

## 4. 设计模式

### 4.1 接口-默认实现（Interface-Default）

核心服务均采用 `XxxService` 接口 + `DefaultXxxService` 实现的分离模式：

```
ConsoleFileChannelApplicationService           ← 接口（应用层）
└── DefaultConsoleFileChannelApplicationService ← 实现（基础设施层）
```

实际命名示例：
- `TriggerService` → `DefaultTriggerService`
- `HeartbeatService` → `DefaultHeartbeatService`
- `WorkerRegistryService` → `DefaultWorkerRegistryService`

### 4.2 策略模式（Strategy）

通过 Registry 管理多策略实现，运行时按 key 选择：

```java
// 策略接口
public interface ExportFormatStrategy { ... }

// 注册表
@Component
public class ExportFormatStrategyRegistry {
    private final Map<String, ExportFormatStrategy> strategies;
    // Spring 自动注入所有实现
}
```

### 4.3 模板方法（Template Method）

Worker 执行链使用 Pipeline Step 模板：

```java
public record PipelineStepDefinitionParam(...) {}  // 步骤定义
public record StepExecutionRequest(...)  {}         // 步骤执行请求
public record StageExecutionContext(...) {}         // 阶段上下文
```

### 4.4 门面模式（Facade）

Worker 运行时通过 `WorkerRuntimeFacade` 统一对外暴露能力，屏蔽内部细节。

---

## 5. 异常体系

### 5.1 异常层次

```
RuntimeException
├── BizException      ← 业务异常（用户可感知，如参数校验失败、资源冲突）
└── SystemException   ← 系统异常（内部错误、第三方故障）
```

两者均携带 `ResultCode` 枚举：

```java
public class BizException extends RuntimeException {
    private final ResultCode code;
    public BizException(ResultCode code, String message) { ... }
}
```

### 5.2 ResultCode 枚举

| 枚举值 | HTTP 状态码 | 含义 |
|--------|------------|------|
| `SUCCESS` | 200 | 成功 |
| `INVALID_ARGUMENT` | 400 | 参数非法 |
| `VALIDATION_ERROR` | 400 | 参数校验失败 |
| `MISSING_IDEMPOTENCY_KEY` | 400 | 缺少幂等键 |
| `UNAUTHORIZED` | 401 | 未授权 |
| `FORBIDDEN` | 403 | 禁止访问 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `CONFLICT` | 409 | 资源冲突 |
| `STATE_CONFLICT` | 409 | 状态冲突 |
| `RATE_LIMITED` | 429 | 请求过于频繁 |
| `BUSINESS_ERROR` | 422 | 通用业务错误 |
| `NOT_IMPLEMENTED` | 501 | 未实现 |
| `SYSTEM_ERROR` | 500 | 系统错误 |

### 5.3 全局异常处理

每个服务模块有独立的 `@RestControllerAdvice` 异常处理器：

- `ConsoleApiExceptionHandler`（console-api）
- `OrchestratorApiExceptionHandler`（orchestrator）
- `TriggerApiExceptionHandler`（trigger）

处理链：`BizException` → `SystemException` → `MethodArgumentNotValidException` → `ConstraintViolationException` → ... → `Exception`（兜底）

---

## 6. API 响应规范

### 6.1 统一响应包装

所有 API 返回 `CommonResponse<T>`：

```java
public record CommonResponse<T>(
    ResultCode code,
    String message,
    T data,
    ResponseMeta meta
) {
    public static <T> CommonResponse<T> success(T data) { ... }
    public static <T> CommonResponse<T> failure(ResultCode code, String message) { ... }
}
```

### 6.2 Controller 写法

```java
@GetMapping("/jobs")
public CommonResponse<List<JobDefinitionDto>> listJobs(...) {
    return CommonResponse.success(service.list(...));
}
```

---

## 7. REST 删除规范

> 完整设计说明见 [docs/design/delete-strategy.md](../design/delete-strategy.md)。

### 7.1 两类删除与 HTTP 方法

| 操作 | HTTP 方法 | 适用场景 |
|------|----------|---------|
| 物理删除 | `DELETE /{id}` | 叶子节点，无其他表 FK 引用 |
| 软删除（禁用/启用） | `PATCH /{id}` | 有运行时 FK 引用的配置实体 |
| 批量软删除/启用 | `PATCH /batch` | 同上，批量版本 |

**核心规则：`DELETE` 方法只用于物理删除，绝不用于软删除。**

### 7.2 判断是否需要软删除

新增删除功能时先检查 DDL：如果有其他表通过 `REFERENCES` 外键引用该表主键，**必须使用软删除**，否则硬删除会导致历史运行记录孤儿化。

```
有运行时 FK 引用（如 job_instance → job_definition）？
├── 是 → 软删除（PATCH enabled=false）
└── 否 → 物理删除（DELETE）
```

### 7.3 软删除的 Request 复用

软删除统一复用以下两个请求类，禁止为各实体单独创建 toggle 请求类：

```java
EnabledPatchRequest         // 单条：tenantId + enabled
BatchEnabledPatchRequest    // 批量：tenantId + enabled + ids（max 200）
```

### 7.4 列表查询默认过滤

软删除实体的 `XxxQueryRequest` 中，`enabled` 字段**默认值必须为 `true`**：

```java
private Boolean enabled = true;  // 不传时只返回启用记录
```

前端传 `?enabled=false` 可查看禁用记录（运维场景）。

### 7.5 物理删除 Controller 参数

物理删除不使用请求体，参数通过 path（id）和 query（tenantId、可选 reason）传递：

```java
@DeleteMapping("/{id}")
public CommonResponse<...> delete(
        @RequestHeader(...) String idempotencyKey,
        @PathVariable Long id,
        @RequestParam("tenantId") String tenantId,
        @RequestParam(required = false) String reason) { ... }
```

### 7.6 Mapper XML 标签约定

| 操作 | XML 标签 |
|------|---------|
| 物理删除 | `<delete>` |
| 软删除（修改 enabled） | `<update>` |

---

## 8. 数据传输对象

| 用途 | 类型 | 命名 |
|------|------|------|
| API 响应体 | `record` | `XxxDto` / `XxxResponse` |
| API 请求体 | `class` + `@Data` | `XxxRequest` |
| 查询参数 | `class` + `@Data` | `XxxQueryRequest` |
| 内部命令 | `record` | `XxxCommand` |
| 参数聚合 | `record` | `XxxParam` / `XxxContext` |
| DB 投影 | `interface` | `XxxView`（Spring Data JDBC 投影接口） |

请求类使用 `@Data`（Lombok）以支持 Spring MVC 参数绑定；响应/命令类使用 `record` 以保证不可变性。

### 8.1 Query Record 工厂方法规约

Query record 字段数 ≥ 5 时，**内部调用者常常只传 1–3 个字段、其余传 null**。此时必须在 record 内定义静态工厂方法，禁止在调用处写出 null 参数。

**命名约定：**

| 工厂方法签名 | 语义 |
|-------------|------|
| `ofTenant(String tenantId, PageRequest page)` | 按租户查全量 |
| `ofDefinition(Long definitionId, PageRequest page)` | 按定义 ID 查关联记录 |
| `ofDefinition(String tenantId, Long definitionId, PageRequest page)` | 租户 + 定义 ID 双约束 |

**实现示例（WorkflowNodeQuery）：**

```java
public record WorkflowNodeQuery(
        String tenantId,
        Long workflowDefinitionId,
        String workflowCode,
        String nodeCode,
        String nodeType,
        Boolean enabled,
        PageRequest pageRequest) {

    public static WorkflowNodeQuery ofDefinition(Long workflowDefinitionId, PageRequest pageRequest) {
        return new WorkflowNodeQuery(null, workflowDefinitionId, null, null, null, null, pageRequest);
    }

    public static WorkflowNodeQuery ofDefinition(String tenantId, Long workflowDefinitionId, PageRequest pageRequest) {
        return new WorkflowNodeQuery(tenantId, workflowDefinitionId, null, null, null, null, pageRequest);
    }
}
```

**调用侧对比：**

```java
// ❌ 禁止：null 参数暴露在调用处，可读性差且易出错
nodeMapper.selectByQuery(new WorkflowNodeQuery(tenantId, def.getId(), null, null, null, null, pageReq));

// ✅ 正确：工厂方法隐藏无关字段
nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, def.getId(), pageReq));
```

**已有工厂方法的 Query 类：**`JobDefinitionQuery.ofTenant`、`WorkflowDefinitionQuery.ofTenant`、`WorkflowNodeQuery.ofDefinition`、`WorkflowEdgeQuery.ofDefinition`

---

## 9. 持久层规范

### 9.1 技术选型

| 场景 | 技术 | 说明 |
|------|------|------|
| 配置态（Console） | Spring Data JDBC | Repository 接口 + `@Query` |
| 运行态（Orchestrator/Worker） | MyBatis | XML Mapper |
| **禁止** | JPA / Hibernate | 整个项目禁用 |

### 9.2 Spring Data JDBC 约定

- 实体类必须标注 `@Id` + `@Table("schema.table_name")`
- 查询投影使用 `interface`（getter 风格：`getXxx()`）
- Repository 继承 `Repository<Entity, Long>` 或 `CrudRepository`

### 9.3 MyBatis 约定

- Mapper XML 放在 `classpath:mapper/*.xml`
- type-aliases-package 指向 domain 包

---

## 10. 事务管理

### 10.1 声明式事务

```java
@Transactional
public void submitApproval(...) {
    // 状态写入 + outbox_event 在同一事务内
}
```

### 10.2 编程式事务

复杂场景使用 `TransactionTemplate`：

```java
@RequiredArgsConstructor
public class DefaultTriggerService {
    private final TransactionTemplate transactionTemplate;

    public void process() {
        transactionTemplate.executeWithoutResult(status -> {
            // 事务逻辑
        });
    }
}
```

### 10.3 硬约束

- `outbox_event` 必须与任务状态写入处于同一事务
- Orchestrator 是唯一状态主机；Worker 不能直接改写 `job_instance` / `workflow_run` / `workflow_node_run`

---

## 11. 参数校验

### 11.1 Jakarta Validation（JSR-380）

Controller 层使用 `@Valid` / `@Validated` 触发校验：

```java
@PostMapping("/jobs")
public CommonResponse<?> createJob(@Valid @RequestBody CreateJobRequest request) { ... }
```

Request 类上使用标准注解：

```java
@Data
public class CreateJobRequest {
    @NotBlank(message = "jobCode 不能为空")
    private String jobCode;

    @Size(max = 200, message = "描述不能超过 200 字")
    private String description;
}
```

### 11.2 路径/查询参数校验

Controller 类标注 `@Validated`，方法参数使用约束注解：

```java
@RestController
@Validated
public class ConsoleJobController {
    @GetMapping("/jobs/{jobCode}")
    public CommonResponse<?> getJob(@PathVariable @NotBlank String jobCode) { ... }
}
```

---

## 12. 日志规范

### 12.1 日志框架

统一使用 **SLF4J + Lombok `@Slf4j`**：

```java
@Slf4j
public class DefaultTriggerService {
    public void process() {
        log.info("trigger processed: jobCode={}, tenantId={}", jobCode, tenantId);
    }
}
```

### 12.2 MDC 结构化日志

关键上下文通过 MDC 传递（tenantId、traceId 等），供日志平台检索。

### 12.3 日志级别约定

| 级别 | 场景 |
|------|------|
| `ERROR` | 系统异常、不可恢复错误 |
| `WARN` | 业务异常、可恢复的告警 |
| `INFO` | 关键业务事件、状态变更 |
| `DEBUG` | 调试信息（生产环境关闭） |

---

## 13. 配置管理

### 13.1 @ConfigurationProperties

外部化配置使用类型安全绑定：

```java
@ConfigurationProperties(prefix = "batch.worker.lease")
public record WorkerLeaseProperties(Duration renewInterval, Duration timeout) {}
```

启动类使用 `@ConfigurationPropertiesScan` 自动扫描。

### 13.2 配置嵌套

```java
@ConfigurationProperties(prefix = "batch.console")
public record ConsoleProperties(
    String instanceId,
    SecurityProperties security,
    AiProperties ai
) {
    public record SecurityProperties(boolean enabled, String sharedSecret, ...) {}
    public record AiProperties(boolean enabled, String model, ...) {}
}
```

### 13.3 配置归属决策（yml / DB / 环境变量 / 配置中心）

每个新增的可调参数，落地前先回答一个问题：**改这个值，业务/运维期望什么时候生效？**

**决策树**：

1. 运行时改 + 立即生效（不重启）→ **DB 表 + Redis cache**
2. 改完接受滚动重启 → **`@ConfigurationProperties` + yml**
3. 跨环境差异化（dev/test/prod 取值不同）→ **环境变量 + yml 占位符** `${VAR:default}`
4. 秘钥/凭证（不能进 git）→ **环境变量 / k8s Secret / 部署平台注入**
5. 多机房/多 region 下发不同配置 + 频繁审计 → 等场景真出现再评估配置中心，**当前阶段不引入**

**三层归属边界**：

| 配置去处 | 适合 | 不适合 | 现存例子 |
|---|---|---|---|
| `@ConfigurationProperties` + yml | 启动期注入的静态参数：超时、池大小、开关、阈值、重试次数、表达式格式约束 | 频繁变更的业务规则、租户级差异化、秘钥 | `BatchSecurityProperties` / `ReadReplicaProperties` / `MqRoutingProperties`（共 ~62 个） |
| PostgreSQL 表 + Redis cache | 动态业务规则、多租户 / 多 job 维度差异化、需要审计版本、Console UI 可改 | 启动期就需要的基础设施配置（DB url、Kafka bootstrap）、秘钥 | `tenant_config` / `default_params` / `tenant_quota_policy` / `business_calendar` / `pipeline_step_definition` |
| 环境变量（`${VAR:default}`） | 跨环境差异化：URL、端口、profile、秘钥、规模参数（pool size） | 业务逻辑规则、多租户参数 | `BATCH_CONSOLE_READ_REPLICA_ENABLED` / `BATCH_SECURITY_BYPASS_MODE` / DB 密码 |

**反模式（PR 评审拒绝）**：

- ❌ 把租户级差异化参数塞 yml（如 `tenant.acme.quota=1000`）→ 应进 `tenant_quota_policy` 表
- ❌ 秘钥写死在 yml 且无环境变量占位符 → 必须 `${VAR:fallback-only-for-local}`
- ❌ 基础设施 URL 写死在 yml 不留 env 占位符 → 跨环境部署受限
- ❌ 给 yml 字段加 `@RefreshScope` 期望热更新但下游不监听失效 → 多实例配置漂移；动态需求一律走 §13.3 标准链路
- ❌ 业务方临时提"改这个参数立即生效"，直接把 yml 字段 promote 成"运行时可改"→ 必须先评估是否该建表；**yml 字段一旦发布就只能滚动重启改**

**动态配置标准链路**（任何"运行时改 + 立即生效"需求按此串）：

1. DB 表（权威源）
2. MyBatis / Spring Data JDBC 仓储读取
3. 应用内 cache（自建 Redis cache 或 `@Cacheable`）
4. Console UI / Ops 端点改 DB → 调 `/api/console/ops/cache/evict-*` 触发 Redis pub/sub
5. 多实例订阅 channel，本地 cache 失效后下次读重新加载

参考实现：`ConsoleConfigCacheController` / `OrchestratorConfigCacheService`。

**为什么当前不引入配置中心**：

| 配置中心能力 | 当前替代 |
|---|---|
| 静态参数运行时改 + 推送 | 静态参数本来就低频改，滚动重启可接受 |
| 多环境集中管理 | Spring Profile + `.env.*` 三件套 |
| 配置版本/审计 | git（yml 在仓库；DB 配置变更走 Console 审计） |
| 灰度/分实例下发 | 当前 worker 同质化，无此需求 |
| 秘钥集中管理 | k8s Secret / 部署平台环境变量注入 |
| 动态业务规则 | DB + Redis cache（已落地） |

**触发时机**（满足任意 2 项再立项评估，否则不引入）：

- 部署 region / 集群数 ≥ 3
- 业务方提"线上无重启修改 yml 参数"需求 ≥ 月度
- 合规要求配置变更可审计 + 任意版本回滚
- 微服务模块数翻倍（≥ 16 个）

**当前阶段不要做的"预埋"**：禁止为了"以后好迁"在 yml 字段上加 `@RefreshScope` / Nacos 注解 / Apollo 客户端依赖。真要迁时按 `prefix` 一一映射到外部 namespace，工作量 ≈ 1-2 周；预埋只会徒增复杂度。

---

## 14. 测试规范

### 14.1 技术栈

- JUnit 5 + Mockito + AssertJ
- `@MockitoSettings(strictness = LENIENT)` 按需使用
- Spring Boot Test（集成测试）

### 14.2 命名约定

```java
class ConsoleQueryControllerTest {
    @Test
    void listJobs_shouldReturnPagedResult() { ... }

    @Test
    void createJob_whenCodeExists_shouldReturn409() { ... }
}
```

格式：`方法名_条件_预期结果`（省略 `should` 也可）。

### 14.3 断言风格

统一使用 AssertJ 流式断言：

```java
assertThat(result.code()).isEqualTo(ResultCode.SUCCESS);
assertThat(list).hasSize(3).extracting("jobCode").contains("JOB_A");
```

---

## 15. 命名规范

### 15.1 类命名

| 角色 | 命名模式 | 示例 |
|------|---------|------|
| 接口 | `XxxService` | `TriggerService` |
| 默认实现 | `DefaultXxxService` | `DefaultTriggerService` |
| Controller | `XxxController` | `ConsoleJobController` |
| Repository | `XxxRepository` | `ConsoleDashboardQueryRepository` |
| 配置类 | `XxxConfiguration` | `ConsoleKafkaConfiguration` |
| Properties | `XxxProperties` | `WorkerLeaseProperties` |
| 异常处理器 | `XxxApiExceptionHandler` | `ConsoleApiExceptionHandler` |

### 15.2 方法命名

| 操作 | 动词 | 示例 |
|------|------|------|
| 查询单个 | `get` / `find` | `getJob()`, `findByCode()` |
| 查询列表 | `list` / `query` | `listJobs()`, `queryInstances()` |
| 创建 | `create` | `createJobDefinition()` |
| 更新 | `update` | `updateJobStatus()` |
| 删除 | `delete` / `remove` | `deleteJob()` |
| 统计 | `count` | `countJobDefinitions()` |
| 校验 | `validate` / `check` | `validateTenant()` |

---

## 16. 架构硬约束

1. **任务分发主链**：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
2. **Orchestrator 是唯一状态主机**；Worker 不能直接改写 `job_instance` / `workflow_run` / `workflow_node_run`
3. **outbox_event 必须与任务状态写入处于同一事务**
4. **Worker 执行前必须先 CLAIM**，不能绕过
5. **禁止 JPA/Hibernate**；持久层 MyBatis（运行态）/ Spring Data JDBC（配置态）不混用

---

## 17. 模块边界

模块结构固定，**不可擅自增删**：

```
batch-common            ← 公共基础（异常、枚举、DTO、工具类）
batch-trigger           ← 触发器服务
batch-orchestrator      ← 编排调度服务（唯一状态主机）
batch-worker-core       ← Worker 公共核心
batch-worker-import     ← 文件导入 Worker
batch-worker-export     ← 文件导出 Worker
batch-worker-dispatch   ← 文件分发 Worker
batch-console-api       ← 控制台 BFF（面向前端）
```

---

## 18. 领域数据字典

所有枚举值定义在 `batch-common/.../enums/` 下，**字段值必须使用枚举 `.code()`，禁止硬编码字符串**。

### 18.1 配置域（Console / 定义态）

| 字段 | 枚举类 | 允许值 |
|------|-------|--------|
| `job_definition.job_type` | `JobType` | `GENERAL` / `IMPORT` / `EXPORT` / `DISPATCH` / `WORKFLOW` |
| `job_definition.schedule_type` | — | `CRON` / `FIXED_RATE` / `MANUAL` / `EVENT` / `ONE_TIME` |
| `job_definition.retry_policy` | `RetryPolicyType` | `NONE` / `FIXED` / `EXPONENTIAL` |
| `job_definition.catch_up_policy` | `CatchUpPolicyType` | `NONE` / `AUTO` / `MANUAL_APPROVAL` |
| `workflow_definition.workflow_type` | `WorkflowType` | `DAG` / `PIPELINE` / `MIXED` |
| `workflow_node.node_type` | `WorkflowNodeType` | `START` / `END` / `TASK` / `GATEWAY` / `FILE_STEP` / `JOB` |
| `workflow_edge.edge_type` | `WorkflowEdgeType` | `SUCCESS` / `FAILURE` / `CONDITION` / `ALWAYS` |
| `file_channel_config.channel_type` | `FileChannelType` | `SFTP` / `API` / `API_PUSH` / `EMAIL` / `NAS` / `OSS` / `LOCAL` |
| `file_channel_config.auth_type` | `FileChannelAuthType` | `NONE` / `PASSWORD` / `KEY_PAIR` / `TOKEN` / `OAUTH2` / `CUSTOM` |

### 18.2 运行域（Orchestrator / 运行态）

| 字段 | 枚举类 | 允许值 |
|------|-------|--------|
| `outbox_event.publish_status` | `OutboxPublishStatus` | `NEW` / `PUBLISHING` / `PUBLISHED` / `FAILED` / `GIVE_UP` |
| `trigger_request.trigger_type` | `TriggerType` | `API` / `MANUAL` / `EVENT` / `CATCH_UP` / `SCHEDULED` |
| `job_instance.status` | `JobInstanceStatus` | `CREATED` / `WAITING` / `READY` / `RUNNING` / `PARTIAL_FAILED` / `SUCCESS` / `FAILED` / `CANCELLED` / `TERMINATED` |
| `workflow_run.status` | `WorkflowRunStatus` | `CREATED` / `RUNNING` / `SUCCESS` / `FAILED` / `TERMINATED` |
| `file_record.status` | `FileStatus` | `RECEIVED` / `PARSING` / `PARSED` / `VALIDATED` / `LOADED` / `GENERATED` / `DISPATCHING` / `DISPATCHED` / `ARCHIVED` / `FAILED` / `DELETED` |

---

## 19. 注释规范

### 19.1 原则

**只注释 WHY，不注释 WHAT。** 代码本身说明做什么；注释说明为什么这样做——隐藏约束、反直觉决策、特定 bug 的绕过方案、业务语义。

| 应写注释 | 不应写注释 |
|---------|-----------|
| 算法选择原因（如为何用 AES/GCM） | 重复方法名含义（`// 获取用户` 在 `getUser()` 上） |
| 状态机转换的业务含义 | 解释自解释变量名 |
| 看似多余但有原因的代码（防御性写法、幂等保护） | 每个参数的 `@param` 描述（参数名已表达） |
| 跨事务、跨服务的协议约定 | 枚举/接口成员的字面描述 |

### 19.2 类级 Javadoc

复杂 Service / Executor / Handler 类必须有类级 Javadoc，说明：
- 该类在链路中的角色与职责边界
- 状态机或关键流程（必要时附简要流程说明）
- 与相邻类的协议约定（如事务边界、并发安全假设）

接口、简单 DTO / record、Spring 配置类、枚举不需要 Javadoc。

### 19.3 方法级注释

以下场景需要在方法内写内联注释或方法级 Javadoc：
- 方法体内存在非显而易见的分支决策（如 half-open 探针放行、bizDate=null 跳过语义）
- 有顺序约束的多步操作（如"先 deactivate 再 insert"保证单活版本）
- 魔法常量或限制值的来源（如 `MAX_PROBE_CHANNEL_BATCH = 1000` 防 DB 扫描）
- 与线格式、协议字节序相关的编解码逻辑

辅助方法（名称已自解释的 `toXxx` / `resolveXxx` / `buildXxx` / `parseXxx`）不需要注释。

### 19.4 当前覆盖基线（2026-04-18）

对各模块核心逻辑类（Service / Executor / Handler 实现）的逻辑方法（≥3 行方法体）扫描结果：

| 模块 | 核心类 | 逻辑方法数 | 有注释 | 覆盖率 |
|------|--------|-----------|--------|--------|
| batch-common | `BatchObjectCryptoService` | 9 | 6 | 67% |
| batch-trigger | `DefaultTriggerService` | 13 | 9 | 69% |
| batch-trigger | `DefaultLaunchAdapterService` | 3 | 3 | 100% |
| batch-orchestrator | `DefaultTaskOutcomeService` | 35 | 14 | 40% |
| batch-orchestrator | `DefaultTaskExecutionService` | 1 | 1 | 100%（全 delegate） |
| batch-worker-core | `DefaultWorkerLifecycleManager` | 3 | 2 | 67% |
| batch-worker-import | `DefaultImportStageExecutor` | 12 | 6 | 50% |
| batch-worker-export | `DefaultExportStageExecutor` | 13 | 6 | 46% |
| batch-worker-dispatch | `DefaultDispatchStageExecutor` | 13 | 7 | 54% |
| batch-console-api | `DefaultConsoleJobApplicationService` | 0 | 0 | N/A（全 facade） |
| batch-console-api | `DefaultConsoleConfigApplicationService` | 22 | 10 | 45% |
| **全局** | | **124** | **64** | **52%** |

> 未覆盖的 48% 基本为命名自解释的辅助方法（`resolveXxx` / `toXxx` / `buildXxx` 等），
> 不应为追求覆盖率而添加无效注释。**52% 是当前合理的目标终态。**

## 20. 字符编码

**契约：全系统 UTF-8**。系统内部持有、传输、存储、落盘的字符串一律 UTF-8；仅"读取外部推过来的非 UTF-8 源文件"这一个导入边界允许 GBK / ISO-8859-1 等，读入时立即转为 UTF-8 内部表示。

### 20.1 落地层次

| 层 | 具体约束 | 代码位置 |
|---|---|---|
| 项目源码 / 构建 | 根 pom `project.build.sourceEncoding=UTF-8` + `maven-compiler-plugin` 显式 `<encoding>UTF-8</encoding>` | `pom.xml` |
| 运行时容器 locale | `ENV LANG=C.UTF-8 LC_ALL=C.UTF-8`（Java 25 默认 `file.encoding=UTF-8` via JEP 400，不必再传 `-Dfile.encoding`） | `docker/Dockerfile.app` |
| HTTP / i18n | `server.servlet.encoding.charset=UTF-8` + `force=true`；`spring.messages.encoding=UTF-8` | `batch-common/.../batch-defaults.yml` |
| 导出（系统→外部） | 硬编码 `StandardCharsets.UTF_8`，`file_template_config.target_charset` 仅接受 `UTF-8`，其他拒绝 | `batch-worker-export/.../format/*ExportFormat.java`、`MinioExportStorage`、`RegisterStep` |
| 导入（外部→系统） | `PreprocessStep.resolveCharset()` 按 `payload.targetCharset → template.charset → UTF-8` 三级降级，解析后全流转均为 UTF-8 | `batch-worker-import/.../PreprocessStep.java`、`ImportPreprocessPipeline`、`ParseStep` |
| 中间件容器 locale | `docker-compose.yml` 的 `postgres` / `kafka` / `minio` / `redis` 均从 `.env` 的 `BATCH_LOCALE`（默认 `C.UTF-8`）读取 `LANG` / `LC_ALL`；`postgres` 额外 `POSTGRES_INITDB_ARGS=--encoding=UTF8`。Test profile（`sftp` / `mockserver`）同样继承 | `docker-compose.yml`、`docker-compose.test.yml` |

### 20.2 Java 代码风格

- **禁止** `Charset.forName("UTF-8")` / 字符串字面量 `"UTF-8"`（字符串易拼错、无法静态校验）
- 需要 `Charset` 对象 → `StandardCharsets.UTF_8`
- 需要字符集名（写入 `file_record.charset` 等字段） → `EncodingUtils.UTF_8`
- 需要归一外部输入 → `EncodingUtils.normalize(raw)` / `EncodingUtils.resolve(raw)`
- 导出路径强制断言 → `EncodingUtils.requireUtf8(raw)`
- **禁止** 业务代码自行调用 `Charset.forName(...)`——别名差异（`utf8` / `UTF8` / `utf-8`）和非法值交给 `EncodingUtils` 处理

`EncodingUtils`：`batch-common/src/main/java/com/example/batch/common/utils/EncodingUtils.java`

## 21. 配置开关规范

### 21.1 `batch.security.bypass-mode`（全链路安全旁路总开关）

配置键 `batch.security.bypass-mode`（Java 字段 `BatchSecurityProperties.bypassMode`，方法 `isBypassMode()`）。开启后**整条安全链旁路**：认证 / 脱敏 / 加解密 / 审批 / 渠道校验全部放宽。**仅供本地 / 联调 / E2E 使用**。

**默认值分级**（高优先级覆盖低优先级）：

| 层 | 默认 | 场景 |
|---|---|---|
| Java 字段 `bypassMode` | `false` | 兜底最安全，防未知 profile 意外放开 |
| `application-local.yml`（6 个模块） | `true` | IDE 本地跑默认旁路，开发者调试不受安全链拖累 |
| `docker-compose.app.yml` env | `${BATCH_SECURITY_BYPASS_MODE:-false}` | docker-compose 起容器默认不旁路，环境形态贴近生产；需要旁路时显式设 env 覆盖 |
| `.env.local` / `.env.test` / `.env.example` | `false` | 跟 compose 对齐 |
| `.env.prod` | `false` | 生产显式关 |
| Helm `values.yaml`（生产） | `"false"` | 默认安全 |
| Helm `examples/values-local-k8s.yaml` | `"true"` | 本地 K8s 演示 |
| prod profile @PostConstruct | **强制拒绝 `true`** | `BatchSecurityProperties.validateSecuritySettings()` 启动 fail-fast |

**调用规范**：业务代码一律 `batchSecurityProperties.isBypassMode()`。旧 `isTestingOpen()` / `setTestingOpen()` / 旧键 `batch.security.testing-open` 已于 2026-05-01 物理删除（`@Deprecated since=2026-04-19, forRemoval=true` 周期到期）。

---

## 22. 代码模式实战（已落地的消除重复 / 封装策略）

本章记录项目中**实际落地**的消除重复分支、封装策略的具体模式，供新代码参照实现。前面 §1-§21 是规约，本章是规约触发后**怎么改**的样板。

### 22.1 路由表模式（Map<String, Handler>）

**问题：** 按字符串类型分派不同处理器时，if-chain 随业务增长无限膨胀，且新增类型需要修改主干方法。

**规定写法：** 构造期（或静态块）建立一次性路由表，`execute()` 方法只负责查表和调用。

**参照实现：** `DefaultCompensationService`

```java
private final Map<String, CompensationHandler> handlersByType = Map.of(
        "JOB",       this::rerunJob,
        "STEP",      this::rerunStep,
        "PARTITION", this::retryPartition,
        "FILE",      (cmd, cmdNo, traceId, entity) -> reprocessFile(cmd, traceId, entity),
        "BATCH",     this::rerunBatch,
        "DLQ",       this::replayDeadLetter
);

private Map<String, Object> execute(...) {
    CompensationHandler handler = handlersByType.get(compensationType);
    if (handler == null) throw new BizException(...);
    return handler.handle(command, commandNo, traceId, entity);
}
```

**适用条件：** ≥ 3 个分支、分支间逻辑独立、类型值为字符串或可 toString 的枚举。

### 22.2 策略 + 模板方法模式（SpecHandler<T,E>）

**问题：** 同一"查找 → 跳过/更新/创建"三路循环在 N 个方法中逐字重复，每增加一种配置类型就复制一套。

**规定写法：** 定义 `SpecHandler<T,E>` 接口和公共 `applySpecs()` 模板，每种类型只提供一个 handler lambda，消除循环体重复。

**参照实现：** `DefaultConsoleTenantConfigInitApplicationService`

```java
// 公共模板（只写一次）
private <T, E> ItemStats applySpecs(List<T> specs, ApplyContext ctx, SpecHandler<T, E> handler) { ... }

// 7 个 upsertable 类型：insert/update 走同一 upsert 路径
private ItemStats applyFileChannels(List<FileChannelSpec> specs, ApplyContext ctx) {
    return applySpecs(specs, ctx, SpecHandler.upsertable(
            "channel", FileChannelSpec::getChannelCode,
            (tid, s) -> Optional.ofNullable(fileChannelConfigMapper.selectByUniqueKey(tid, s.getChannelCode())),
            (c, s) -> upsertFileChannel(c.tenantId(), s, c.operator())));
}

// 3 个特殊类型：insert/update 行为不同
private ItemStats applyJobDefinitions(List<JobDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(specs, ctx, SpecHandler.of(
            "jobDef", JobDefinitionSpec::getJobCode,
            (tid, s) -> Optional.ofNullable(jobDefinitionMapper.selectByUniqueKey(tid, s.getJobCode())),
            (c, s) -> insertJobDefinition(c.tenantId(), s, c.operator()),
            (c, s, existing) -> updateJobDefinition(existing, s, c.operator())));
}
```

**两个工厂：**
- `SpecHandler.upsertable(...)` — insert 和 update 走同一操作（7 种简单类型）
- `SpecHandler.of(...)` — insert 和 update 行为不同（作业定义、工作流定义、流水线定义）

### 22.3 上下文对象替代多参数（ApplyContext / Command record）

**问题：** 同一批参数在 N 个方法间逐个传递，方法签名随需求增长超过 6 个参数上限。

**规定写法：** 将不变量封装为 `private record`（私有场景）或独立 Command/Param 类（公共接口）。

**参照实现：** `DefaultConsoleTenantConfigInitApplicationService.ApplyContext`

```java
private record ApplyContext(String tenantId, InitMode mode, String operator, boolean dryRun) {}

// 调用方只传 ctx，不再传 4 个独立参数
private ItemStats applyFileChannels(List<FileChannelSpec> specs, ApplyContext ctx) { ... }
```

### 22.4 命名方法路由（inline else 超过 10 行时提取）

**问题：** `dispatchNode` 方法的 if-gateway / if-job / else(TASK) 结构中，else 分支体约 85 行，导致主干方法既是路由器又是实现，难以阅读。

**规定写法：** 每个分支体 > 10 行时提取为独立命名方法，主干方法变成纯路由器。

**参照实现：** `DefaultWorkflowNodeDispatchService.dispatchNode`

```java
// 重构前：两个 if + 一个 85 行 inline else
// 重构后：三路对称路由
if (isGatewayNode(workflowNode.getNodeType())) {
    return dispatchGatewayNode(jobInstance, workflowRun, node, sourcePayload);
}
if (isJobNode(workflowNode.getNodeType())) {
    return dispatchJobNode(jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
}
return dispatchTaskNode(jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
```

### 22.5 共享前提逻辑消除（resolveLeftOperand 委托 resolveLiteralOperand）

**问题：** `resolveLeftOperand` 与 `resolveLiteralOperand` 前 6 个判断分支逐字重复，只有最后一行（`readPath` 还是 `UNRESOLVED`）不同。

**规定写法：** 让共享逻辑收敛到一处，差异以委托方式表达。

**参照实现：** `WorkflowConditionEvaluator`

```java
// 重构前：两个 ~20 行几乎相同的方法
// 重构后：resolveLeftOperand 委托给 resolveLiteralOperand，只处理差异路径
private Object resolveLeftOperand(String token, Map<String, Object> payload) {
    Object literal = resolveLiteralOperand(token, payload);
    if (literal != UNRESOLVED) {
        return literal;
    }
    return readPath(payload, stripOuterParentheses(token.trim()));
}
```

### 22.6 错误结果工厂（failResult / errorResult）

**问题：** 多个 try-catch 块的 catch 部分构造完全相同的失败结果对象，每处复制 3 行。

**规定写法：** 提取 `private static XxxResult failResult(Command command, Exception ex)` 静态工厂。

**参照实现：** `RemoteFilesystemDispatchSupport`

```java
// 重构前：dispatchNas 和 dispatchOss 各自 catch 中重复 3 行
// 重构后：共享工厂
private static DispatchResult failResult(DispatchCommand command, Exception ex) {
    String externalRequestId = resolveExternalRequestId(command);
    String receiptCode = resolveReceiptCode(command, externalRequestId);
    return new DispatchResult(false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
}
```

### 22.7 CAS-miss 警告辅助方法（warnIfCasMiss）

**问题：** `if (updated <= 0) { log.warn("... CAS miss ...") }` 在同一类中出现 3 次，每次只有上下文字符串不同。

**规定写法：** 提取 `private void warnIfCasMiss(int updated, String context, long id)` 辅助方法。

**参照实现：** `DefaultTaskOutcomeService`

```java
private void warnIfCasMiss(int updated, String context, long partitionId) {
    if (updated <= 0) {
        log.warn("{} CAS miss - concurrent update likely already advanced: partitionId={}", context, partitionId);
    }
}

// 调用方
warnIfCasMiss(partitionUpdated, "partition markStatus(SUCCESS)", partition.getId());
warnIfCasMiss(retryUpdated,    "partition markRetrying",         partition.getId());
warnIfCasMiss(failUpdated,     "partition markStatus(FAILED)",   partition.getId());
```

### 22.8 识别需要重构的信号

| 信号 | 应用的模式 |
|---|---|
| `if (type == A) handleA(); else if (type == B) handleB()...` ≥ 3 分支 | Map 路由表（§22.1）|
| 同一"查找→跳过/更新/创建"循环出现 N 次，只有 mapper 调用不同 | SpecHandler + applySpecs（§22.2）|
| 一个方法传参 ≥ 5 个，且这些参数一起被传给下游 N 个方法 | ApplyContext/Command record（§22.3）|
| 主干方法有 if-A + if-B + inline-else，else 体 > 10 行 | 命名方法路由（§22.4）|
| 两个方法前 N 行相同，最后 1-2 行不同 | 委托共享逻辑（§22.5）|
| 多个 catch 块构造相同结构的失败对象 | failResult 工厂（§22.6）|
| `if (n <= 0) { log.warn(...) }` 出现 ≥ 3 次 | warnIfXxx 辅助方法（§22.7）|
