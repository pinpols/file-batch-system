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
