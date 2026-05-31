# 自定义 taskType 作业配置设计

**状态**:基线设计(部分未实施) · 2026-05-31
**关联**:[worker-deployment-models](./worker-deployment-models.md) · [sdk-industry-benchmark](./sdk-industry-benchmark.md) · [ADR-035](../adr/ADR-035-tenant-self-hosted-sdk.md) · [深度评估](../review/batch-worker-sdk-deep-review-2026-05-31.md)

> 本文回答:**租户写了一个 SDK handler 之后,运营 / 业务方怎么在 console 上用它?参数怎么填?默认值怎么设?敏感凭据怎么处理?**
>
> 这是 SDK 体系第 4 块文档,补齐自定义 taskType 从代码到运营的完整链路。

---

## 1. 配置分 5 层,不要混

| 层 | 谁配 | 在哪 | 例子 |
|---|---|---|---|
| **A. Worker 进程级** | 租户运维 | 租户 K8s ConfigMap / Secret / env | `baseUrl` / `apiKey` / `kafkaBootstrap` / 业务 DB 密码 |
| **B. taskType 定义级** | 租户(代码或一次性) | 平台 `custom_task_type_registry` 表 | schema / 默认参数 / 默认重试 / 默认超时 |
| **C. workflow_node 引用级** | 运营 / 业务方 | 平台 `workflow_node.parameters` 字段(JSONB) | 这一次使用的具体参数(`filePath` / `batchSize`) |
| **D. 触发器级** | 运营 | 平台 `trigger_definition` | cron 表达式 / 触发条件 |
| **E. 派单运行时级** | 平台自动 | `TaskDispatchMessage` 字段 | `bizDate` / `attemptNo` / 模板变量替换结果 |

**派单时合并**:`B.defaults` + `C.overrides` + `E.runtime substitution` → 一份完整 `parameters` 进 `TaskDispatchMessage`。SDK handler 拿到的是**已合并的最终值**。

---

## 2. 当前实现状态

### ✅ 已有

- **A 层**:`BatchPlatformClientConfig` 支持 builder 构建,租户从 env / K8s Secret 注入
- **C 层**:`workflow_node.parameters` 是 JSONB 字段,console 编辑器能编辑
- **D 层**:`trigger_definition` 完整支持 cron / event 等
- **E 层**:`TaskDispatchMessage.parameters` 接收,handler 通过 `ctx.parameters()` 读
- **`register()`**:上报 `capabilityTags = handlers.keySet()`,平台知道 SDK 能跑哪些 taskType

### ❌ 真缺(B 层是大洞)

- ❌ `custom_task_type_registry` 表 **不存在**
- ❌ console "我的 taskType 注册" 页 **不存在**
- ❌ 参数 schema 定义机制 **不存在**
- ❌ workflow_node 编辑器**没有 schema 提示** —— 运营拖一个 `tenant_xyz_import` 节点,parameters 是空 JSON 框,只能瞎填
- ❌ SDK register 时**没上报 schema**(协议没字段接收)
- ❌ 默认参数 / 默认重试策略 **没法在 taskType 级声明**

**直接后果**:租户运营 / PM 不知道某个 taskType 需要传什么参数,只能问 SDK 开发者翻代码。写错时,task 派下去 SDK 抛 `ClassCastException` —— **配置错位在运行时才暴露**。

---

## 3. 自定义 taskType 注册(B 层)设计

### 3.1 新表 `custom_task_type_registry`

```sql
CREATE TABLE custom_task_type_registry (
  tenant_id          VARCHAR(64) NOT NULL,
  task_type_code     VARCHAR(128) NOT NULL,
  display_name       VARCHAR(255),
  description        TEXT,
  owner_email        VARCHAR(128),
  parameters_schema  JSONB,           -- JSON Schema(draft-2020-12)
  parameters_default JSONB,           -- 默认值 map
  outputs_schema     JSONB,           -- 输出 schema(供下游 GATEWAY 用)
  default_retry      JSONB,           -- {max_attempts, backoff, initial_delay}
  default_timeout_ms BIGINT,
  status             VARCHAR(16),     -- DRAFT / ACTIVE / DEPRECATED
  source             VARCHAR(16),     -- CONSOLE / SDK_DECLARED
  sdk_build_id       VARCHAR(64),     -- SDK 声明时上报,便于版本追溯
  created_by         VARCHAR(64),
  created_at         TIMESTAMPTZ,
  updated_at         TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, task_type_code)
);

CREATE INDEX idx_ctt_tenant_status ON custom_task_type_registry (tenant_id, status);
```

遵循 CLAUDE.md 多租隔离:`tenant_id` 在 PK 中。

### 3.2 注册方式两条路

#### 方式 1:console 手动注册(运营友好)

console 新增页 "我的 taskType",租户 PM 填表(或 import YAML):

```yaml
taskType: tenant_xyz_import
display_name: "XYZ 业务数据导入"
description: "每日从 SFTP 拉取订单数据导入业务库"
owner_email: data-team@xyz.com

parameters_schema:                # JSON Schema(draft-2020-12)
  type: object
  required: [filePath]
  properties:
    filePath:  { type: string, title: "源文件路径(支持 ${bizDate})" }
    batchSize: { type: integer, default: 1000, minimum: 1, maximum: 100000 }
    validate:  { type: boolean, default: true, title: "是否校验" }

parameters_default:
  batchSize: 1000
  validate: true

default_retry:
  max_attempts: 3
  backoff: exponential
  initial_delay_ms: 30000

default_timeout_ms: 1800000        # 30 min

outputs_schema:
  type: object
  properties:
    rows:       { type: integer, title: "导入行数" }
    errorCount: { type: integer, title: "错误行数" }
```

#### 方式 2:SDK 代码声明(代码即配置,推荐)

SDK handler 实现可选接口 `descriptor()` 暴露 schema:

```java
class MyImportHandler implements SdkTaskHandler {
    @Override public String taskType() { return "tenant_xyz_import"; }

    @Override public SdkTaskTypeDescriptor descriptor() {
        return SdkTaskTypeDescriptor.builder()
            .displayName("XYZ 业务数据导入")
            .ownerEmail("data-team@xyz.com")
            .parametersSchema("""
                {"type":"object","required":["filePath"],
                 "properties":{
                   "filePath":{"type":"string"},
                   "batchSize":{"type":"integer","default":1000,"minimum":1,"maximum":100000},
                   "validate":{"type":"boolean","default":true}}}
                """)
            .defaultRetry(RetryPolicy.exponential(3, Duration.ofSeconds(30)))
            .defaultTimeout(Duration.ofMinutes(30))
            .build();
    }

    @Override public SdkTaskResult execute(SdkTaskContext ctx) { ... }
}
```

SDK `register()` body 增加 `taskTypes[].descriptor` 段,平台 upsert 到 `custom_task_type_registry`(`source=SDK_DECLARED`)。

**好处**:schema 跟 handler 代码同仓库同 PR 演进,代码升级不会忘改 schema;repo 是单一权威源。

**冲突处理**:同 `taskType_code` 同时存在 console 配置 + SDK 声明 → **SDK 声明优先**,但 console 上提示"由 SDK 声明,如需修改请改代码"。

### 3.3 SDK 端新增 API 草案

```java
public interface SdkTaskHandler {
    String taskType();
    SdkTaskResult execute(SdkTaskContext ctx);

    /** 可选 —— 返回 null 平台用 console 已注册的或 fallback。 */
    default SdkTaskTypeDescriptor descriptor() { return null; }
}

@Value @Builder
public class SdkTaskTypeDescriptor {
    String displayName;
    String description;
    String ownerEmail;
    String parametersSchema;       // JSON Schema 字符串
    String outputsSchema;
    RetryPolicy defaultRetry;
    Duration defaultTimeout;
}
```

---

## 4. 参数合并规则(派单时,orchestrator 侧)

```
effective_parameters = 
    custom_task_type_registry.parameters_default     (B 层兜底)
  + workflow_node.parameters                          (C 层覆盖)
  + 模板变量替换 ${bizDate} ${trigger.fireTime} ...  (E 层注入)
```

**实施位置**:orchestrator `TaskDispatchService` 派单前合并;拼好后塞进 `TaskDispatchMessage.parameters`。**SDK 端拿到的是已合并的最终值**,handler 不需要自己再 merge。

### 4.1 模板变量(E 层注入)

支持的占位符:

| 变量 | 含义 | 来源 |
|---|---|---|
| `${bizDate}` | 业务日 YYYY-MM-DD | 平台日历 |
| `${prevBizDate}` | 上一业务日 | 平台日历 |
| `${nextBizDate}` | 下一业务日 | 平台日历 |
| `${trigger.fireTime}` | 触发器 fire 时间 ISO 8601 | trigger 模块 |
| `${trigger.code}` | 触发器代码 | trigger 模块 |
| `${workflow.runId}` | 当前 workflow run id | orchestrator |
| `${prev.outputs.X}` | 上游节点 outputs 的 X 字段 | 上游 SdkTaskResult.outputs |

替换在 orchestrator 派单时一次完成,SDK 端不感知模板。

### 4.2 effective_parameters 持久化做审计快照

`task` 表加 `effective_parameters JSONB` 字段,记录派单时合并完的快照。理由:

- **审计**:运维查"这次 task 为什么用 batchSize=500"时,看的是当时的实际值,而不是事后改过的 default
- **重派复现**:重派时直接用快照,不再重新 merge,保证两次完全一致
- **跨日一致性**:配合 §5.1 的 `bizDate` 推送

---

## 5. 敏感凭据走 env / Secret,**不走 console parameters**

### 5.1 走 / 不走 划分

| 类型 | 走 console parameters? | 替代 |
|---|---|---|
| 业务标识(文件路径、分类、日期范围) | ✅ | — |
| 业务参数(batchSize、超时阈值、开关) | ✅ | — |
| **DB 密码 / OAuth secret / API key** | ❌ | K8s Secret + env var |
| **加密密钥 / 证书** | ❌ | K8s Secret + payload codec |
| 租户内服务地址 | ⚠️ 看情况 | 推荐 env var,避免 console 误改 |

### 5.2 为什么不能走 console parameters

- console 配的会**明文存 DB**(运维查 SQL 看到)
- 派单时**明文进 Kafka**(`kafka-console-consumer` 看得到)
- 业务方填写时 console 表单**明文显示**(截屏 / 录屏泄漏)

### 5.3 SDK 端推荐模式

```java
class MyImportHandler implements SdkTaskHandler {
    private final String dbPassword;
    private final HikariDataSource ds;

    public MyImportHandler() {
        // 启动时一次性从 env 读 —— 凭据不进 ctx.parameters
        this.dbPassword = System.getenv("TENANT_DB_PASSWORD");
        this.ds = buildDataSource(dbPassword);
    }

    public SdkTaskResult execute(SdkTaskContext ctx) {
        // 业务参数从 ctx 拿
        String filePath = (String) ctx.parameters().get("filePath");
        int batchSize   = (int)    ctx.parameters().getOrDefault("batchSize", 1000);
        // 执行业务
    }
}
```

**这条要在 SDK README 写死成硬规约**,避免租户犯错。

---

## 6. 配置变更的生效模型

| 变更 | 生效方式 | 影响范围 |
|---|---|---|
| `workflow_node.parameters` | 下一次派单立刻生效 | 之后的 task |
| `custom_task_type_registry.parameters_default` | 新建节点用新 default;**已有 workflow_node 不受影响** | 编辑器侧 |
| `custom_task_type_registry.parameters_schema` | 编辑器立刻按新 schema 校验;**已派 task 不重新校验** | 编辑器侧 |
| Worker 进程 env vars | 进程重启 | 该副本 |
| Handler 代码 | 进程重启(SDK 不支持热加载) | 该副本 |
| **taskType 大版本不兼容升级** | 走 taskType code 灰度:`tenant_xyz_import_v2` 新建,workflow 切流,旧的下线 | 全局 |

### 6.1 快照 vs 实时引用

`workflow_node.parameters` 是**实时引用 + 派单时合并**(每次 fire 都重新算 effective)。

要"快照"语义(this run 用的就是当时的值),靠 `task.effective_parameters` 字段(§4.2)。

### 6.2 不兼容升级走灰度

```
v1.0:  taskType=tenant_xyz_import,schema 含 filePath
v2.0:  filePath 改成 filePaths(数组)→ 不兼容
```

❌ 错误做法:直接改 v1.0 的 schema,workflow 编辑器立刻按新 schema 校验,**已部署的 SDK 还是 v1.0 代码,跑挂**。

✅ 正确做法:注册新 `tenant_xyz_import_v2`,workflow 在 console 切流(全部 / 部分节点指向新 code),v1 保留 30 天观察期再 DEPRECATED → 下线。

---

## 7. console 工作流编辑器 UX 期望

有了 schema 后,编辑器能做到:

- 拖 `tenant_xyz_import` 节点 → **自动渲染表单**(filePath / batchSize / validate 各字段)
- **必填校验 / 类型校验 / 范围校验**(基于 JSON Schema)
- **模板变量补全**(输入 `${` 弹出 `bizDate / trigger.fireTime / ...`)
- 显示 `display_name` / `description` / `owner_email` / 上次成功率(若集成监控)
- 显示"由 SDK v1.5.0 声明"(从 `sdk_build_id` 读)

这是把 SDK 自定义 taskType **从"开发者私有"变成"运营可用"**的关键。

---

## 8. 跟业界对比

| 系统 | taskType 配置怎么做 |
|---|---|
| **Temporal** | 没有 console 配置,workflow code 即定义(代码是真理) |
| **Conductor** | `TaskDef` 注册到 server,带 inputKeys / outputKeys / retry / timeout(最接近我们) |
| **Zeebe** | BPMN 里定义 service task + variables,没有独立 taskType registry |
| **AWS SFn** | Activity ARN + ASL definition(state machine 里描述 input) |
| **本系统设计** | `custom_task_type_registry` 表 + SDK 代码声明 descriptor + console 编辑器读 schema |

Conductor 的 `TaskDef` 模型是最对标的,我们参考它。

---

## 9. 实施优先级

| 优先级 | 项 | 工作量 |
|---|---|---|
| 🔴 P0 | `custom_task_type_registry` 表 + Flyway migration + archive 镜像 | 0.5d |
| 🔴 P0 | SDK `register()` 上报 `taskTypes[].descriptor`(含 schema / defaults / retry / timeout)+ `SdkTaskTypeDescriptor` API | 1d |
| 🔴 P0 | orchestrator `TaskDispatchService` 派单合并 `defaults + node.parameters + 模板替换` | 1d |
| 🟡 P1 | `task.effective_parameters` 字段写入做审计快照 | 0.5d |
| 🟡 P1 | console "我的 taskType" 列表页(读 registry,展示) | 1d |
| 🟡 P1 | console 工作流编辑器按 schema 渲染节点表单 + 模板补全 | 2-3d |
| 🟡 P1 | SDK README 加"敏感凭据走 env,不走 parameters"硬规约 | 0.2d |
| 🟢 P2 | taskType 版本化 + 灰度切流(console 支持节点改 taskType code) | 2d |
| 🟢 P2 | 模板变量库扩展(`${prev.outputs.X}` / `${workflow.runId}` 等)| 1d |

---

## 10. 维护

- 表结构变更 → Flyway migration + archive 镜像(CLAUDE.md 红线)
- 模板变量库扩展 → 同步本文档 §4.1 表
- 新增"特殊参数语义"(类似敏感凭据约束)→ 同步 §5
- console UX 期望变化 → 同步 §7

---

**参考**

- [worker-deployment-models](./worker-deployment-models.md)
- [sdk-industry-benchmark](./sdk-industry-benchmark.md)
- [batch-worker-sdk 深度评估](../review/batch-worker-sdk-deep-review-2026-05-31.md)
- [ADR-035 租户自托管 SDK](../adr/ADR-035-tenant-self-hosted-sdk.md)
- [Conductor TaskDef](https://conductor-oss.org/docs/documentation/configuration/taskdef)
