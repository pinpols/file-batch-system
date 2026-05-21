# file-batch-system 编码规约

> 本文档总结项目中实际遵循的编码规范与设计约定，供团队成员参考。

---

## 1. 方法参数约束


| 参数数量 | 要求                                       |
| ---- | ---------------------------------------- |
| ≤ 5  | 允许直接传参                                   |
| = 6  | 建议封装；Mapper 公共方法和 Service 公共接口 **必须** 封装 |
| ≥ 7  | **必须** 封装为参数对象                           |


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

### 1.1 调用方约束（inline build 提取）

方法签名压缩到参数对象后,调用现场如果写成 `f(XxxParam.builder().a()...build())` 一长串 fluent 链,
参数臃肿就从签名搬到调用处了。判定线按链长度 `.xxx()` 调用数:

| chain 长度 | 处理 |
|---|---|
| ≤ 3 | 单行 inline,**禁提取**(业界 builder 标准写法) |
| 4-6 | 单行或 2 行 fluent,**禁提取** |
| 7-9 | 看场景:在短工厂方法(`static foo() { return X.builder()...build(); }`,整体 ≤ 8 行) → inline;在长函数中间 → 可考虑提取 |
| ≥ 10 | **必须提取**为局部变量再 return / 传参 |

**正确写法(chain ≥ 10)**：

```java
// ✅ 调用方提取局部变量,return / 传参语义一眼可读
LaunchRequest launchRequest =
    LaunchRequest.builder()
        .tenantId(request.tenantId())
        .jobCode(request.jobCode())
        ...11 个字段...
        .build();
return launchRequest;
```

**禁用写法**：

```java
// ❌ return 语句被 builder 链淹没,在 if 分支里嵌套时尤其难读
return LaunchRequest.builder()
    .tenantId(request.tenantId())
    .jobCode(request.jobCode())
    ...11 个字段...
    .build();
```

**合理 inline 场景(短链不该提取)**：

```java
// SDK args(MinIO/AWS),链 ≤ 5,单行即读完 — 不提取
minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(name).build());

// 短工厂方法,工厂本身就是"避免调用方写长链"的封装 — 内部 inline 是惯例
public static DryRunFinding pass(String code, String scope, String message) {
  return DryRunFinding.builder()
      .code(code).severity(Severity.PASS).scope(scope).message(message)
      .build();
}
```

**豁免**：
- 声明式注册类(`ConsoleMenuRegistry` / `*SchemaRegistry`):inline new 是声明式数据结构的标准写法
- test fixture:test-as-spec 模式,inline 让测试可读性更高

**守护**：`PositionalArgsConventionTest` 拦白名单(`GUARDED_TYPES`,当前 51 个类型)的方法实参 inline build;
return 位置因每个项目语义不同(短工厂方法合理 inline),不全局守护,靠 review 按上述判定线手工把关。

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


| 枚举值                       | HTTP 状态码 | 含义     |
| ------------------------- | -------- | ------ |
| `SUCCESS`                 | 200      | 成功     |
| `INVALID_ARGUMENT`        | 400      | 参数非法   |
| `VALIDATION_ERROR`        | 400      | 参数校验失败 |
| `MISSING_IDEMPOTENCY_KEY` | 400      | 缺少幂等键  |
| `UNAUTHORIZED`            | 401      | 未授权    |
| `FORBIDDEN`               | 403      | 禁止访问   |
| `NOT_FOUND`               | 404      | 资源不存在  |
| `CONFLICT`                | 409      | 资源冲突   |
| `STATE_CONFLICT`          | 409      | 状态冲突   |
| `RATE_LIMITED`            | 429      | 请求过于频繁 |
| `BUSINESS_ERROR`          | 422      | 通用业务错误 |
| `NOT_IMPLEMENTED`         | 501      | 未实现    |
| `SYSTEM_ERROR`            | 500      | 系统错误   |


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


| 操作         | HTTP 方法        | 适用场景            |
| ---------- | -------------- | --------------- |
| 物理删除       | `DELETE /{id}` | 叶子节点，无其他表 FK 引用 |
| 软删除（禁用/启用） | `PATCH /{id}`  | 有运行时 FK 引用的配置实体 |
| 批量软删除/启用   | `PATCH /batch` | 同上，批量版本         |


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


| 操作              | XML 标签     |
| --------------- | ---------- |
| 物理删除            | `<delete>` |
| 软删除（修改 enabled） | `<update>` |


---

## 8. 数据传输对象


| 用途          | 类型                | 命名                                     |
| ----------- | ----------------- | -------------------------------------- |
| API 响应体     | `record`          | `XxxDto` / `XxxResponse`               |
| API 请求体     | `class` + `@Data` | `XxxRequest`                           |
| 查询参数        | `class` + `@Data` | `XxxQueryRequest`                      |
| 内部命令        | `record`          | `XxxCommand`                           |
| 参数聚合        | `record`          | `XxxParam` / `XxxContext`              |
| DB 投影 / 列表行 | `record`          | `XxxView`（MyBatis `resultType` / 查询投影） |


请求类使用 `@Data`（Lombok）以支持 Spring MVC 参数绑定；响应/命令类使用 `record` 以保证不可变性。

### 8.1 Query Record 工厂方法规约

Query record 字段数 ≥ 5 时，**内部调用者常常只传 1–3 个字段、其余传 null**。此时必须在 record 内定义静态工厂方法，禁止在调用处写出 null 参数。

**命名约定：**


| 工厂方法签名                                                               | 语义             |
| -------------------------------------------------------------------- | -------------- |
| `ofTenant(String tenantId, PageRequest page)`                        | 按租户查全量         |
| `ofDefinition(Long definitionId, PageRequest page)`                  | 按定义 ID 查关联记录   |
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

### 8.2 Domain 子包与代码风格（按职责）

各业务模块的 `com.example.batch.<module>.domain`（及_worker 模块的 `.../domain` 单包）承载**领域侧数据结构**，与 `controller` / `service` / `mapper` 分层正交。以下按**子目录**约定形态、命名与 Refactor 方向；若与上文总表冲突，以本节 **domain 落位** 为准。

#### 8.2.1 总原则


| 原则                     | 说明                                                                                                     |
| ---------------------- | ------------------------------------------------------------------------------------------------------ |
| **包即词汇表**              | `entity` / `query` / `command`一眼可读；不要把 API 的 `*Request` / `*Dto` 塞进 `domain`（除非明确是跨层复用的查询契约）。          |
| **禁止 `*Record` 表映射后缀** | 表行一律 `*Entity`；Java `record` 关键字仍可用于 **非表** 的不可变 DTO。                                                  |
| **单向依赖**               | `command` / `query` → 可依赖 `common`；**避免** `entity` 依赖 `command`；子域模型（`pipeline`）尽量只吃 JDK + `common`。   |
| **与 §1 参数规约衔接**        | 对外 `Command` 若构造字段很多，按 CLAUDE.md：`argc > 6` 须 `@Builder` 且调用方先赋变量再传入；`domain.param` 中 Mapper 单次写入参数同理。 |


#### 8.2.2 `domain/entity` — 表行映射（MyBatis 主类型）


| 项          | 约定                                                                                                              |
| ---------- | --------------------------------------------------------------------------------------------------------------- |
| **命名**     | `XxxEntity`，与表含义对应，不缩写业务词。                                                                                      |
| **形态**     | **默认** `class` + `@Data`，运行态大行、多 null 更新、`version` 乐观锁时更合适。                                                     |
| **Lombok** | 需 MyBatis 无参构造时：`@NoArgsConstructor` + `@AllArgsConstructor`；若加 `@Builder`，三连兜底（与 CLAUDE.md 「Builder 与反射路径」一致）。 |
| **可变边界**   | 仅在编排内核、状态推进路径上允许对 Entity 做「读完—改字段—写回」；不要在 Controller 层持有可变 Entity 横穿多层。                                         |
| **可选：不可变** | 少数只读/窄表可用 `record` + XML `resultMap`/`<constructor>`；新代码无必要时优先与大多数字段对齐用 `@Data` class。                          |
| **接口混入**   | 实现 `Stateful`、`LocalizedErrorCarrier` 等横切接口时保持类在 `entity` 内，不为此单独开包。                                            |


**治理**：`domain` 根下零散的 `*Entity`（如历史遗留）应**迁入** `domain/entity`，保持「表映射只此一包」。

#### 8.2.3 `domain/query` — 列表/分页查询条件


| 项        | 约定                                                                           |
| -------- | ---------------------------------------------------------------------------- |
| **命名**   | `XxxQuery`（不叫 `XxxQueryParam` 以免与 `param` 混淆）。                               |
| **形态**   | `**record` 优先**：不可变、线程安全、适合拼条件。                                              |
| **工厂方法** | 字段数 ≥ 5 且常见调用只带少数维度时，**必须**提供 `ofTenant` / `ofDefinition` 等静态工厂（见 **§8.1**）。 |
| **分页**   | 统一携带 `PageRequest`（或模块内等价的分页值对象），命名字段建议 `pageRequest`。                       |


#### 8.2.4 `domain/command` — 用例级输入（编排 / 触发 / 控制台动作）


| 项      | 约定                                                                                                                                          |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **命名** | `XxxCommand`，表达一次业务动作（提交补偿、治理任务等）。                                                                                                          |
| **形态** | `**record` 优先**；字段多、调用方需按需缺省时用 `**@Builder` + record**（Java 16+ record 可用 `@Builder` on record 按项目统一风格，与现有 `CompensationSubmitCommand` 一致）。 |
| **边界** | 从 Controller / Listener 入参组装为 Command，在 ApplicationService 入口消费；**不**直接下传到 Mapper。                                                          |


#### 8.2.5 `domain/param` — 单次持久化/领域操作参数束


| 项                | 约定                                                                                                                       |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **命名**           | `XxxParam`（如 `FinishTaskParam`、`XxxUpsertParam`）。                                                                        |
| **形态**           | `**class` + `@Getter` + `private final` + `@Builder`** 为主，便于实现 `LocalizedErrorCarrier` 等接口、且与 MyBatis `@Param` 属性映射习惯一致。 |
| **与 Command 区别** | **Command** = 用例边界；**Param** = **单次** `update`/`insert`/领域方法参数聚合，字段更贴近 SQL 列或存储过程语义。                                     |
| **不可变**          | 字段 `final`，无 setter；禁止在 Mapper 调用返回后再改 Param 实例。                                                                         |


#### 8.2.6 `domain/view` — 只读查询投影（Console 等）


| 项      | 约定                                                                 |
| ------ | ------------------------------------------------------------------ |
| **命名** | `XxxView`，按业务场景分子包（如 `view/dashboard`、`view/cluster`、`view/meta`）。 |
| **形态** | `**record` 优先**；MyBatis `resultType`/Constructor 映射简单。             |
| **边界** | 不写回业务表；不含业务方法，仅承载展示/报表列。                                           |


#### 8.2.7 Orchestrator 专有子域（可长期保留在 `domain` 下）


| 子包                 | 用途                     | 形态建议                                                                                                |
| ------------------ | ---------------------- | --------------------------------------------------------------------------------------------------- |
| `**pipeline`**     | Pipeline 定义、步骤注册、执行上下文 | **定义/配置加载结果**可用 `@Data class`（字段需陆续填充）；**窄元组**（如步骤键、阶段结果）用 `record`。接口 + 实现（`PipelineExecutor`）可同包。 |
| `**scheduler`**    | 资源调度决策、配额策略            | 决策对象多为「多字段、逐步填空」：`@Data class` 更常见；策略枚举/纯函数放 `common` 若跨模块复用。                                       |
| `**statemachine**` | 状态迁移表、状态机描述            | **迁移边、小事件**：`record`；**有行为的机**可用 `class`。                                                           |
| `**value`**        | DB 语义包装（如 JSONB 原始串）   | `**final class**` + 静态 `of` + 正确 `equals`/`hashCode`，避免与 `String` 混用语义。                             |


#### 8.2.8 Worker 模块 `.../domain`（非多子包时）


| 项       | 约定                                                      |
| ------- | ------------------------------------------------------- |
| **命名**  | 阶段/步骤结果：`XxxStageResult`、`XxxStepContext` 等，后缀表达生命周期阶段。 |
| **形态**  | `**record` 优先**（Worker 热路径短生命周期对象）。                     |
| **膨胀时** | 再行拆 `domain/step` / `domain/model` 子包，避免单文件堆积。          |


#### 8.2.9 自检清单（PR / 重构）

- 新业务表映射类是否落在 `domain/entity` 且以 `Entity` 结尾？
- `domain/query` 宽表条件是否已有工厂方法，避免调用处一长串 `null`？
- Application **Command** 与 Mapper **Param** 是否未混在同一类型里？
- `domain/view` 是否保持无写模型逻辑、无对 `entity` 的回写依赖？
- 新增 `*Record` **类名**仅用于非持久化语义时，是否避免与「表行」误命名冲突（表行请用 `Entity`）？

---

## 9. 持久层规范

### 9.1 技术选型


| 场景                      | 技术                                     | 说明                                                                            |
| ----------------------- | -------------------------------------- | ----------------------------------------------------------------------------- |
| 业务表 CRUD / CAS / 复杂 SQL | **MyBatis**                            | `mapper/*.java` + `resources/mapper/*.xml`                                    |
| 锁表、极薄支撑查询               | `JdbcTemplate`                         | 非默认业务持久化手段                                                                    |
| **禁止**                  | JPA / Hibernate / **Spring Data JDBC** | 不得 `spring-boot-starter-data-jdbc`、`@EnableJdbcRepositories`、`CrudRepository` |


### 9.2 实体与命名

- 表行映射类型放在 `domain/entity`，类名 `***Entity` 后缀**（与 CLAUDE.md / ADR-001 一致）。
- 可为不可变 `**record`**（配合 `resultMap` + `<constructor>`）或 `**@Data` class**（可变运行态行）；**禁止**再用 `*Record` 后缀区分「配置态」。
- **同一表、同一写路径**只能有一个主入口：禁止 **Mapper 写 + 自建 Repository 写** 或历史意义上的 **Repository + Mapper 双写**。

### 9.3 MyBatis 约定

- Mapper XML 放在 `classpath:mapper/*.xml`（或模块约定路径）
- `type-aliases-package` 指向 domain 包

### 9.4 公共 SQL 片段(`CommonFragments.xml`)

`batch-common/.../mapper/CommonFragments.xml` 提供跨表复用片段,避免每个 mapper 重复同样 5 行。

| 片段 ID | 用途 | 参数要求 |
|---|---|---|
| `offsetPageClause` | offset 分页 limit/offset bind | 顶层有 `pageRequest`(PageRequest) |
| `tenantPredicate` | 租户隔离 `AND tenant_id = #{tenantId}` | 顶层有 `tenantId` |
| `activePredicate` | 软删除过滤 `AND is_deleted = false` | 表含 `is_deleted` 列(opt-in) |
| `orderByUpdatedDesc` | `ORDER BY updated_at DESC, id DESC` | 表含 `updated_at` 列 |
| `orderByCreatedDesc` | `ORDER BY created_at DESC, id DESC` | 表含 `created_at` 列 |

用法:
```xml
<select id="...">
  SELECT ... FROM xxx WHERE 1=1
  <include refid="com.example.batch.common.mapper.CommonFragments.tenantPredicate"/>
  <include refid="com.example.batch.common.mapper.CommonFragments.activePredicate"/>
  <include refid="com.example.batch.common.mapper.CommonFragments.orderByUpdatedDesc"/>
  <include refid="com.example.batch.common.mapper.CommonFragments.offsetPageClause"/>
</select>
```

### 9.5 审计字段自动填充(`AuditFieldsInterceptor`)

`batch-common/.../persistence/mybatis/AuditFieldsInterceptor` 拦截 `Executor.update`,反射写 entity 的 `createdAt` / `updatedAt` / `createdBy` / `updatedBy` / `tenantId`:

- **INSERT**:字段为 null 才填(用户显式赋值优先)
- **UPDATE**:`updatedAt` / `updatedBy` 强制刷新最新,`createdAt` / `createdBy` 不动
- 当前值从 MDC 读(`OPERATOR_ID` / `TENANT_ID`),Console-api 由 `ConsoleRequestContextFilter` 灌入;worker / trigger 后台路径 MDC 空时审计字段保留 null
- 关闭走配置:`batch.mybatis.audit.enabled=false`

新表设计**不需要**在 Mapper XML 里显式 `#{createdAt}` / `#{updatedAt}`,interceptor 自动注入。

### 9.6 软删除约定(opt-in)

现有表全部物理删除。新表需要软删除时,按以下 4 步:

1. Flyway migration 加 `is_deleted boolean NOT NULL DEFAULT false`
2. SELECT / UPDATE 谓词 `<include refid="...activePredicate"/>`
3. 删除路径改成 `UPDATE ... SET is_deleted = true WHERE ...`(不要 DELETE)
4. 在 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 同步加列(如果该表入归档)

**不做全表迁移** —— 收益(可恢复 / 软回滚)< 成本(全表 schema 改 + 谓词补漏风险)。按需 opt-in。

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

## 11.4 分布式锁的两种用法

**注解式 `@DistributedLock`(batch-common/lock):业务方法级,acquire-execute-release**

- 适合:防止并发重复执行的业务方法(RERUN / 资源占用 / 防双 submit)
- SpEL key 解析方法参数,失败降级方法签名兜底
- 抢锁失败按 `throwOnFailure` 决定抛 `DistributedLockAcquireException` 或静默跳过
- 配置开关:`batch.lock.distributed.enabled`

**手写 `LockingTaskExecutor.executeWithLock`:scheduler poll 循环,custom error handling**

- 现有 3 处(OutboxPollScheduler / WebhookDeliveryRelay / TriggerOutboxRelay)**保留手写**:
  - 锁外 `AtomicBoolean running` CAS 防重叠(锁内只是单实例并发,锁外是单实例自身轮询重叠)
  - 三层 catch:`DataAccessException → WARN`(瞬时,自动退避) / `OOM → rethrow` / `Throwable → ERROR`
  - `finally` 重置 running flag,确保下轮能继续
- 这些是 infrastructure 调度器,注解化会丢失语义。**不要**为了「统一」强迁

## 11.5 Micrometer 指标命名规范

**命名格式**:`batch.<module>.<area>.<metric>.<unit>`(参见 `batch-common/.../observability/BatchMetricsNames`)

- `<module>` = `trigger / orchestrator / worker / console`
- `<area>` = 业务域(job / outbox / quartz / wheel / dispatch / process / audit)
- `<metric>` = 计量项(total / duration / failure / lag / count)
- `<unit>` = Timer / Histogram **必须**带 seconds / ms / bytes;Counter / Gauge 可省

**tag 命名**:全部 snake_case;优先用 `BatchMetricsNames.TAG_*` 标准 tag 常量(tenant_id / job_type / status / error_code / module / worker_type)。

**禁止高基数 tag**:不要把 jobInstanceId / requestId / traceId 当 tag 打(会爆 cardinality)。这些走 MDC 日志关联,不走 metrics。

**null/空值处理**:tag value 空 / null 必须 fallback 到字符串 `"unknown"`(`JobLifecycleMetrics#safe` 示范),保持 Grafana 维度可枚举。

## 11.6 业务指标集中常量(`BatchMetricsNames`)

跨模块共享的指标名(job 生命周期 / outbox 共用 / claim 链路)集中常量,避免散落字面量漂移。模块私有指标在各自 Metrics 类自管(如 `WheelMetrics` / `ProcessMetrics`)。

`JobLifecycleMetrics`(orchestrator):job_instance 走到终态时调 `recordCompletion(tenantId, jobType, status, duration)` 记录端到端时长 + 完成计数;失败路径额外 `recordFailure(tenantId, jobType, errorCode)`。Histogram percentile 自动启用。

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


| 级别      | 场景           |
| ------- | ------------ |
| `ERROR` | 系统异常、不可恢复错误  |
| `WARN`  | 业务异常、可恢复的告警  |
| `INFO`  | 关键业务事件、状态变更  |
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
2. 改完接受滚动重启 → `**@ConfigurationProperties` + yml**
3. 跨环境差异化（dev/test/prod 取值不同）→ **环境变量 + yml 占位符** `${VAR:default}`
4. 秘钥/凭证（不能进 git）→ **环境变量 / k8s Secret / 部署平台注入**
5. 多机房/多 region 下发不同配置 + 频繁审计 → 等场景真出现再评估配置中心，**当前阶段不引入**

**三层归属边界**：


| 配置去处                             | 适合                                            | 不适合                                      | 现存例子                                                                                                          |
| -------------------------------- | --------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `@ConfigurationProperties` + yml | 启动期注入的静态参数：超时、池大小、开关、阈值、重试次数、表达式格式约束          | 频繁变更的业务规则、租户级差异化、秘钥                      | `BatchSecurityProperties` / `ReadReplicaProperties` / `MqRoutingProperties`（共 ~62 个）                          |
| PostgreSQL 表 + Redis cache       | 动态业务规则、多租户 / 多 job 维度差异化、需要审计版本、Console UI 可改 | 启动期就需要的基础设施配置（DB url、Kafka bootstrap）、秘钥 | `tenant_config` / `default_params` / `tenant_quota_policy` / `business_calendar` / `pipeline_step_definition` |
| 环境变量（`${VAR:default}`）           | 跨环境差异化：URL、端口、profile、秘钥、规模参数（pool size）      | 业务逻辑规则、多租户参数                             | `BATCH_CONSOLE_READ_REPLICA_ENABLED` / `BATCH_SECURITY_BYPASS_MODE` / DB 密码                                   |


**反模式（PR 评审拒绝）**：

- ❌ 把租户级差异化参数塞 yml（如 `tenant.acme.quota=1000`）→ 应进 `tenant_quota_policy` 表
- ❌ 秘钥写死在 yml 且无环境变量占位符 → 必须 `${VAR:fallback-only-for-local}`
- ❌ 基础设施 URL 写死在 yml 不留 env 占位符 → 跨环境部署受限
- ❌ 给 yml 字段加 `@RefreshScope` 期望热更新但下游不监听失效 → 多实例配置漂移；动态需求一律走 §13.3 标准链路
- ❌ 业务方临时提"改这个参数立即生效"，直接把 yml 字段 promote 成"运行时可改"→ 必须先评估是否该建表；**yml 字段一旦发布就只能滚动重启改**

**动态配置标准链路**（任何"运行时改 + 立即生效"需求按此串）：

1. DB 表（权威源）
2. MyBatis Mapper 读取
3. 应用内 cache（自建 Redis cache 或 `@Cacheable`）
4. Console UI / Ops 端点改 DB → 调 `/api/console/ops/cache/evict-`* 触发 Redis pub/sub
5. 多实例订阅 channel，本地 cache 失效后下次读重新加载

参考实现：`ConsoleConfigCacheController` / `OrchestratorConfigCacheService`。

**为什么当前不引入配置中心**：


| 配置中心能力        | 当前替代                             |
| ------------- | -------------------------------- |
| 静态参数运行时改 + 推送 | 静态参数本来就低频改，滚动重启可接受               |
| 多环境集中管理       | Spring Profile + `.env.`* 三件套    |
| 配置版本/审计       | git（yml 在仓库；DB 配置变更走 Console 审计） |
| 灰度/分实例下发      | 当前 worker 同质化，无此需求               |
| 秘钥集中管理        | k8s Secret / 部署平台环境变量注入          |
| 动态业务规则        | DB + Redis cache（已落地）            |


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
- Spring Boot Test（集成测试）
- 集成测试统一继承 `AbstractIntegrationTest`（`batch-common/src/test/.../testing/`），复用 Testcontainers PG / Kafka / Redis / MinIO；**禁** 各自 `@Testcontainers @SpringBootTest`

### 14.2 Mock 初始化（强约束）

✅ **新单测一律声明式**：

```java
@ExtendWith(MockitoExtension.class)
class XxxServiceTest {
    @Mock private FooMapper fooMapper;
    @Mock private BarPublisher barPublisher;
    @InjectMocks private XxxService service;

    @Test
    void shouldXxx_whenYyy() { ... }
}
```

❌ **禁** 以下两种写法（旧代码改动时顺带迁）：

```java
// 命令式 openMocks
@BeforeEach
void setUp() { MockitoAnnotations.openMocks(this); }

// 字段直 mock
private FooMapper fooMapper = Mockito.mock(FooMapper.class);
```

构造器有循环引用（`@Lazy self`）的，`@InjectMocks` 后在 `@BeforeEach` 补 `ReflectionTestUtils.setField(service, "self", service);` 一行。

### 14.3 Mock strictness

默认 strict（`MockitoExtension` 自带），**禁** `@MockitoSettings(strictness = Strictness.LENIENT)` 当模板拷贝带入。

只有「跨方法共享 stub 且部分方法不触达全部 stub」时才允许，**必须**在注解上方加中文注释说明原因，例如：

```java
// LENIENT 原因:setUp 预置了 STEP_NOT_FOUND/PIPELINE_STEP_MISSING 等场景共享 stub,
// backoffSeconds 静态用例不触达全部 stub,strict 模式会误报。
@MockitoSettings(strictness = Strictness.LENIENT)
```

### 14.4 命名约定

```java
class ConsoleQueryControllerTest {
    @Test
    void listJobs_shouldReturnPagedResult() { ... }

    @Test
    void createJob_whenCodeExists_shouldReturn409() { ... }
}
```

格式：`shouldXxx_whenYyy`（首选）或 `方法名_条件_预期结果`（省略 `should` 也可，老代码 ~48% 是此风格）。**禁** `testXxx` / `test1` / `xxx_test` / 全 snake_case 无语义结构。

### 14.5 `@DisplayName`

业务复杂或方法名表达不清时**强烈推荐**中文 `@DisplayName`（参考 `SoftDeleteRecoveryIntegrationTest`）；简单字段校验可省。**不要**只在少数模块用，整模块统一风格。

### 14.6 断言风格

统一使用 AssertJ 流式断言：

```java
assertThat(result.code()).isEqualTo(ResultCode.SUCCESS);
assertThat(list).hasSize(3).extracting("jobCode").contains("JOB_A");
assertThatThrownBy(() -> service.doSth()).isInstanceOf(BizException.class);
```

❌ **禁** JUnit Jupiter `Assertions.assertEquals / assertTrue / assertThrows`（`security-scan` 模块的 legacy 文件除外，下次改动时一并迁移）。

❌ **禁** 反模式 `assertThat(x.equals(y)).isTrue()` —— 直接 `assertThat(x).isEqualTo(y)`。

### 14.7 集成测试 fixture

`*MutationIntegrationTest` 类的 `WebTestClient` 构造与请求体构造由公共基类 / Fixture 提供：

- `AbstractMutationIntegrationTest`（继承 `AbstractIntegrationTest`）统一 `WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(60s).build()`
- 请求体优先使用 Request DTO + `ObjectMapper.writeValueAsString()`；不要在每个测试类各自维护 `private String body(...)` 字符串拼接

---

## 15. 命名规范

### 15.1 类命名


| 角色                                      | 命名模式                     | 示例                           |
| --------------------------------------- | ------------------------ | ---------------------------- |
| 接口                                      | `XxxService`             | `TriggerService`             |
| 默认实现                                    | `DefaultXxxService`      | `DefaultTriggerService`      |
| Controller                              | `XxxController`          | `ConsoleJobController`       |
| MyBatis Mapper                          | `XxxMapper`              | `JobInstanceMapper`          |
| 自研仓储（**非** Spring Data `Repository` 接口） | `XxxRepository`          | `FileGovernanceRepository`   |
| 配置类                                     | `XxxConfiguration`       | `ConsoleKafkaConfiguration`  |
| Properties                              | `XxxProperties`          | `WorkerLeaseProperties`      |
| 异常处理器                                   | `XxxApiExceptionHandler` | `ConsoleApiExceptionHandler` |


### 15.2 方法命名


| 操作   | 动词                   | 示例                               |
| ---- | -------------------- | -------------------------------- |
| 查询单个 | `get` / `find`       | `getJob()`, `findByCode()`       |
| 查询列表 | `list` / `query`     | `listJobs()`, `queryInstances()` |
| 创建   | `create`             | `createJobDefinition()`          |
| 更新   | `update`             | `updateJobStatus()`              |
| 删除   | `delete` / `remove`  | `deleteJob()`                    |
| 统计   | `count`              | `countJobDefinitions()`          |
| 校验   | `validate` / `check` | `validateTenant()`               |


---

## 16. 架构硬约束

1. **任务分发主链**：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
2. **Orchestrator 是唯一状态主机**；Worker 不能直接改写 `job_instance` / `workflow_run` / `workflow_node_run`
3. **outbox_event 必须与任务状态写入处于同一事务**
4. **Worker 执行前必须先 CLAIM**，不能绕过
5. **禁止 JPA/Hibernate** 与 **Spring Data JDBC**；持久层 **统一 MyBatis**（同一写路径禁止双入口，见 §9）

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


| 字段                                  | 枚举类                   | 允许值                                                              |
| ----------------------------------- | --------------------- | ---------------------------------------------------------------- |
| `job_definition.job_type`           | `JobType`             | `GENERAL` / `IMPORT` / `EXPORT` / `DISPATCH` / `WORKFLOW`        |
| `job_definition.schedule_type`      | —                     | `CRON` / `FIXED_RATE` / `MANUAL` / `EVENT` / `ONE_TIME`          |
| `job_definition.retry_policy`       | `RetryPolicyType`     | `NONE` / `FIXED` / `EXPONENTIAL`                                 |
| `job_definition.catch_up_policy`    | `CatchUpPolicyType`   | `NONE` / `AUTO` / `MANUAL_APPROVAL`                              |
| `workflow_definition.workflow_type` | `WorkflowType`        | `DAG` / `PIPELINE` / `MIXED`                                     |
| `workflow_node.node_type`           | `WorkflowNodeType`    | `START` / `END` / `TASK` / `GATEWAY` / `FILE_STEP` / `JOB`       |
| `workflow_edge.edge_type`           | `WorkflowEdgeType`    | `SUCCESS` / `FAILURE` / `CONDITION` / `ALWAYS`                   |
| `file_channel_config.channel_type`  | `FileChannelType`     | `SFTP` / `API` / `API_PUSH` / `EMAIL` / `NAS` / `OSS` / `LOCAL`  |
| `file_channel_config.auth_type`     | `FileChannelAuthType` | `NONE` / `PASSWORD` / `KEY_PAIR` / `TOKEN` / `OAUTH2` / `CUSTOM` |


### 18.2 运行域（Orchestrator / 运行态）


| 字段                             | 枚举类                   | 允许值                                                                                                                                         |
| ------------------------------ | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `outbox_event.publish_status`  | `OutboxPublishStatus` | `NEW` / `PUBLISHING` / `PUBLISHED` / `FAILED` / `GIVE_UP`                                                                                   |
| `trigger_request.trigger_type` | `TriggerType`         | `API` / `MANUAL` / `EVENT` / `CATCH_UP` / `SCHEDULED`                                                                                       |
| `job_instance.status`          | `JobInstanceStatus`   | `CREATED` / `WAITING` / `READY` / `RUNNING` / `PARTIAL_FAILED` / `SUCCESS` / `FAILED` / `CANCELLED` / `TERMINATED`                          |
| `workflow_run.status`          | `WorkflowRunStatus`   | `CREATED` / `RUNNING` / `SUCCESS` / `FAILED` / `TERMINATED`                                                                                 |
| `file_record.status`           | `FileStatus`          | `RECEIVED` / `PARSING` / `PARSED` / `VALIDATED` / `LOADED` / `GENERATED` / `DISPATCHING` / `DISPATCHED` / `ARCHIVED` / `FAILED` / `DELETED` |


---

## 19. 注释规范

### 19.1 原则

**只注释 WHY，不注释 WHAT。** 代码本身说明做什么；注释说明为什么这样做——隐藏约束、反直觉决策、特定 bug 的绕过方案、业务语义。


| 应写注释                    | 不应写注释                              |
| ----------------------- | ---------------------------------- |
| 算法选择原因（如为何用 AES/GCM）    | 重复方法名含义（`// 获取用户` 在 `getUser()` 上） |
| 状态机转换的业务含义              | 解释自解释变量名                           |
| 看似多余但有原因的代码（防御性写法、幂等保护） | 每个参数的 `@param` 描述（参数名已表达）          |
| 跨事务、跨服务的协议约定            | 枚举/接口成员的字面描述                       |


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


| 模块                    | 核心类                                      | 逻辑方法数   | 有注释    | 覆盖率              |
| --------------------- | ---------------------------------------- | ------- | ------ | ---------------- |
| batch-common          | `BatchObjectCryptoService`               | 9       | 6      | 67%              |
| batch-trigger         | `DefaultTriggerService`                  | 13      | 9      | 69%              |
| batch-trigger         | `DefaultLaunchAdapterService`            | 3       | 3      | 100%             |
| batch-orchestrator    | `DefaultTaskOutcomeService`              | 35      | 14     | 40%              |
| batch-orchestrator    | `DefaultTaskExecutionService`            | 1       | 1      | 100%（全 delegate） |
| batch-worker-core     | `DefaultWorkerLifecycleManager`          | 3       | 2      | 67%              |
| batch-worker-import   | `DefaultImportStageExecutor`             | 12      | 6      | 50%              |
| batch-worker-export   | `DefaultExportStageExecutor`             | 13      | 6      | 46%              |
| batch-worker-dispatch | `DefaultDispatchStageExecutor`           | 13      | 7      | 54%              |
| batch-console-api     | `DefaultConsoleJobApplicationService`    | 0       | 0      | N/A（全 facade）    |
| batch-console-api     | `DefaultConsoleConfigApplicationService` | 22      | 10     | 45%              |
| **全局**                |                                          | **124** | **64** | **52%**          |


> 未覆盖的 48% 基本为命名自解释的辅助方法（`resolveXxx` / `toXxx` / `buildXxx` 等），
> 不应为追求覆盖率而添加无效注释。**52% 是当前合理的目标终态。**

## 20. 日期时间、时区与字符编码

### 20.1 日期时间与时区

**契约：技术时间用 `Instant`，业务/批量日期用 `LocalDate`，所有时区转换必须显式。** 新代码优先依赖 `BatchDateTimeSupport`，不要直接调用 JVM 默认时区或静态 now 方法。

统一入口：

- `BatchDateTimeSupport`：`batch-common/src/main/java/com/example/batch/common/time/BatchDateTimeSupport.java`
- `BatchTimezoneProvider`：`batch-common/src/main/java/com/example/batch/common/config/BatchTimezoneProvider.java`

类型选择规则：

| 场景 | 推荐类型 | 获取方式 |
| --- | --- | --- |
| 事件发生时间 | `Instant` | `BatchDateTimeSupport.nowInstant()` |
| 创建/更新时间 | `Instant` | `BatchDateTimeSupport.nowInstant()` |
| 锁/租约/超时 | `Instant` | `BatchDateTimeSupport.instantAfter(...)` / `instantAfterSeconds(...)` |
| Webhook/JWT/消息时间 | `Instant` | `BatchDateTimeSupport.nowInstant()` |
| 批量日 | `LocalDate` | `BatchDateTimeSupport.currentBatchDate()`；调度触发链路优先使用 `CalendarBizDateResolver` / 后续 `BusinessDateService` |
| 业务日期 | `LocalDate` | `BatchDateTimeSupport.currentBusinessDate()`；需要节假日/顺延时走 `BusinessCalendarService` |
| Cron 触发计算 | `ZonedDateTime` / `Instant` | 使用作业 `timezone` 或日历时区解析出的 `scheduleZoneId`，结果落库和消息传输转 `Instant` |
| 展示给用户 | `ZonedDateTime` / `String` | `BatchDateTimeSupport.toZonedDateTime(...)` / `formatForDisplay(...)`，时区来自用户、租户或平台默认 |
| 文件名可读时间 | `String` | `BatchDateTimeSupport.formatForFileTimestamp(...)` / `currentFileTimestamp()` |

代码规则：

- **禁止** 业务代码直接使用 `Instant.now()`、`LocalDate.now()`、`LocalDateTime.now()`、`ZonedDateTime.now()`、`OffsetDateTime.now()`。
- **禁止** 业务代码直接使用 `ZoneId.systemDefault()`；平台默认时区只能从 `BatchTimezoneProvider.defaultZone()` 或 `BatchDateTimeSupport.defaultBusinessZone()` 取得。
- 数据库、消息、审计、锁、租约、超时、JWT、Webhook、Outbox 等技术时间字段统一使用 `Instant`。
- `LocalDateTime` 只能表示“外部输入的无时区本地时间”，转为技术时间前必须调用 `BatchDateTimeSupport.interpretLocalDateTimeInDefaultZone(...)` 或显式传入 `ZoneId`。
- `LocalDate` 只承载业务日期或批量日，不承载时间点语义；不要把 `LocalDate` 当成 UTC 零点持久化。
- Cron / fixed-rate 计算必须使用作业配置时区或业务日历时区，缺省时走平台默认业务时区；计算出的 fire time、data interval 起止统一用 `Instant` 传递。
- 用户展示和文件名格式化必须先明确时区，再格式化；禁止依赖 Jackson、JVM default timezone 或操作系统 locale 的隐式格式。

批量日补充约定：

- 批量日由业务日历的 `cutoff_time`、时区、节假日规则共同决定；不是简单的自然日。
- 日切后应先打开或确认新批量日实例，再允许新批量日作业进入运行链路。
- 是否要求第二天等待前一天完成，必须作为显式批量日门禁策略表达；不能依赖调度时间或自然日隐式推断。

### 20.2 字符编码

**契约：全系统 UTF-8**。系统内部持有、传输、存储、落盘的字符串一律 UTF-8；仅"读取外部推过来的非 UTF-8 源文件"这一个导入边界允许 GBK / ISO-8859-1 等，读入时立即转为 UTF-8 内部表示。

### 20.3 落地层次


| 层            | 具体约束                                                                                                                                                                                                                      | 代码位置                                                                                    |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| 项目源码 / 构建    | 根 pom `project.build.sourceEncoding=UTF-8` + `maven-compiler-plugin` 显式 `<encoding>UTF-8</encoding>`                                                                                                                      | `pom.xml`                                                                               |
| 运行时容器 locale | `BATCH_LOCALE=C.UTF-8`，并由部署模板 / 镜像派生 `LANG=C.UTF-8 LC_ALL=C.UTF-8`（Java 25 默认 `file.encoding=UTF-8` via JEP 400，不必再传 `-Dfile.encoding`）                                                                                     | `docker/Dockerfile.app`、`docker/compose/app.yml`、Helm ConfigMap                         |
| HTTP / i18n  | `server.servlet.encoding.charset=UTF-8` + `force=true`；`spring.messages.encoding=UTF-8`                                                                                                                                   | `batch-common/.../batch-defaults.yml`                                                   |
| 导出（系统→外部）    | 硬编码 `StandardCharsets.UTF_8`，`file_template_config.target_charset` 仅接受 `UTF-8`，其他拒绝                                                                                                                                       | `batch-worker-export/.../format/*ExportFormat.java`、`MinioExportStorage`、`RegisterStep` |
| 导入（外部→系统）    | `PreprocessStep.resolveCharset()` 按 `payload.targetCharset → template.charset → UTF-8` 三级降级，解析后全流转均为 UTF-8                                                                                                                | `batch-worker-import/.../PreprocessStep.java`、`ImportPreprocessPipeline`、`ParseStep`    |
| 中间件容器 locale | `docker-compose.yml` 的 `postgres` / `kafka` / `minio` / `redis` 均从 `.env` 的 `BATCH_LOCALE`（默认 `C.UTF-8`）派生 `LANG` / `LC_ALL`；`postgres` 额外 `POSTGRES_INITDB_ARGS=--encoding=UTF8`。Test profile（`sftp` / `mockserver`）同样继承 | `docker-compose.yml`、`docker/compose/test.yml`                                          |


### 20.4 Java 代码风格

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


| 层                                           | 默认                                     | 场景                                                                |
| ------------------------------------------- | -------------------------------------- | ----------------------------------------------------------------- |
| Java 字段 `bypassMode`                        | `false`                                | 兜底最安全，防未知 profile 意外放开                                            |
| `application-local.yml`（6 个模块）              | `true`                                 | IDE 本地跑默认旁路，开发者调试不受安全链拖累                                          |
| `docker/compose/app.yml` env                | `${BATCH_SECURITY_BYPASS_MODE:-false}` | docker-compose 起容器默认不旁路，环境形态贴近生产；需要旁路时显式设 env 覆盖                  |
| `.env.local` / `.env.test` / `.env.example` | `false`                                | 跟 compose 对齐                                                      |
| `.env.prod`                                 | `false`                                | 生产显式关                                                             |
| Helm `values.yaml`（生产）                      | `"false"`                              | 默认安全                                                              |
| Helm `examples/values-local-k8s.yaml`       | `"true"`                               | 本地 K8s 演示                                                         |
| prod profile @PostConstruct                 | **强制拒绝 `true`**                        | `BatchSecurityProperties.validateSecuritySettings()` 启动 fail-fast |


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


| 信号                                                                  | 应用的模式                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| `if (type == A) handleA(); else if (type == B) handleB()...` ≥ 3 分支 | Map 路由表（§22.1）                     |
| 同一"查找→跳过/更新/创建"循环出现 N 次，只有 mapper 调用不同                              | SpecHandler + applySpecs（§22.2）    |
| 一个方法传参 ≥ 5 个，且这些参数一起被传给下游 N 个方法                                     | ApplyContext/Command record（§22.3） |
| 主干方法有 if-A + if-B + inline-else，else 体 > 10 行                       | 命名方法路由（§22.4）                      |
| 两个方法前 N 行相同，最后 1-2 行不同                                              | 委托共享逻辑（§22.5）                      |
| 多个 catch 块构造相同结构的失败对象                                               | failResult 工厂（§22.6）               |
| `if (n <= 0) { log.warn(...) }` 出现 ≥ 3 次                            | warnIfXxx 辅助方法（§22.7）              |
