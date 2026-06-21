# ADR-039 · 凭据字段 envRef 风格 —— 替代关键字扫描的根治方案

- **Status**: Accepted（P1 已落地 2026-06-21:`CredentialEnvResolver` + HttpTaskExecutor basic/bearer 接入 + fail-fast；P2 `${ENV:-default}` / P3 `${secret:...}` vault / schema `x-credential` 三层标注 / FE 改造仍待做）
- **Date**: 2026-06-02
- **Related**: ADR-035(SDK 凭据章节 §鉴权 / Kafka SASL)/ ADR-025(workflow 静态校验) / PR #40(FE `SensitiveFieldAlert`) / Issue #242(BE `SensitiveDataValidator`) / Issue #237(taskType descriptor 凭据声明) / Round-2 §4 P0
- **Supersedes(部分)**:`SensitiveFieldAlert` / `SensitiveDataValidator` 基于关键字(`token` / `password` / `secret` / `key`)的子串匹配判定逻辑
- **Plan**: 见本 ADR §实施分阶段

## 范围边界

「**凭据字段**写入时的取值格式约束 + 三层(平台 / SDK / FE)统一 schema 标注」√

- ✅ 凭据字段值**必须**写形如 `${ENV_NAME}` 或 `${secret:vault://path}` 的 envRef,运行时由部署侧 env / secret provider 解出真实值
- ✅ 在 taskType descriptor / parameters JSON Schema 加 `x-credential: true` 标注,作为单一权威源
- ✅ 平台 BE(`SensitiveDataValidator`)/ SDK(handler 参数校验)/ FE(`SensitiveFieldAlert`)三层共享同一 schema + 同一 envRef 解析规则
- ✅ envRef 解析失败(未定义 / 解析错误)走显式失败语义,不静默回落明文

「不做的部分」(留 follow-up):

- ❌ 不引入 vault 真集成(HashiCorp Vault / AWS Secrets Manager / K8s External Secrets);本 ADR 只定义 `${secret:...}` **协议占位符**,具体 provider 后续 ADR
- ❌ 不做密钥轮换 / TTL / 自动 reload —— 跟着部署侧 env 重启走
- ❌ 不做"已存在的明文凭据"批量迁移工具(需要单独 migration ADR + 灰度方案)
- ❌ 不改 ADR-035 §鉴权的 API Key / `X-Internal-Secret` 传输协议;本 ADR 只管"凭据**写入配置时**的取值风格"

## 背景

### 当前关键字扫描的根问题

PR #40 引入的 FE `SensitiveFieldAlert` 组件,以及对应 BE `SensitiveDataValidator`(#242),用关键字子串匹配判断"用户是否把明文凭据写进了任务参数 / workflow 配置 JSON":

```ts
// FE 现状(简化)
const SENSITIVE_KEYWORDS = ['token', 'password', 'secret', 'key', 'credential'];
function isSensitiveField(fieldName: string) {
  return SENSITIVE_KEYWORDS.some(k => fieldName.toLowerCase().includes(k));
}
```

```java
// BE 现状(简化)
if (SENSITIVE_KEY_PATTERN.matcher(fieldName).find()) {
    warnings.add("字段 " + fieldName + " 疑似凭据,请确认未填明文");
}
```

两个症状已踩到:

1. **假阳性大量**:
   - `csrf_token` —— 防 CSRF 表单 token,**不是**凭据,值就是要明文随表单提交
   - `idempotency_token` —— 业务幂等键,**不是**凭据,值是业务 ID
   - `partition_key` / `sort_key` —— Kafka / DB 分区键,`key` 子串误判
   - `password_min_length` / `key_count` —— 配置项,值是数字
   - 同事在 #40 评审里贴了 12 个真实假阳性截图(见 PR #40 conversation)
2. **拦截颗粒度粗 + 表达力差**:
   - 命名带 `token` 但值就是明文 UUID 的合法字段(如 `webhookCallbackToken` 业务回调标识)无法白名单豁免
   - 命名不带关键字但**真**凭据的字段(如 `apiAuth.bearer` / `dsn` / `connectionString` 嵌密码)完全漏判
   - 嵌套 JSON / 数组里的字段无法精确点位 —— 关键字扫描只能扫顶层 key
3. **三层各扫各的不一致**:FE 关键字列表、BE 正则、SDK 暂无,三处规则漂移,新增 / 改判定要改三处。

根问题:**关键字扫描在"用字段名猜语义"**,而语义本就应由 schema 显式声明。

### 业界对照

| 来源 | 做法 |
|---|---|
| K8s | Secret 资源单独存储,Pod spec 用 `valueFrom.secretKeyRef` 引用,**不允许**在 ConfigMap / Deployment env 直填明文(策略侧 OPA / Kyverno 校验) |
| Docker Compose | `secrets:` 顶层 + 服务下 `secrets:` 挂载;env 推荐 `${VAR}` 占位符,运行时由 `.env` / shell env 解 |
| GitHub Actions | `${{ secrets.X }}` 显式引用语法 + repo / org-level secret 存储,workflow YAML 里**禁**明文(linter 警告) |
| Vault Agent / External Secrets | `vault://path` URI 或 annotation,sidecar / operator 拉取注入 |
| Spring Boot | `${ENV_VAR}` / `${vault:path}` placeholder,`PropertySource` 链解析 |

共同点:**凭据值在配置里只出现"引用",真实值从带访问控制的 secret store 解**。本系统照这条路走。

## 决策

### 一:envRef 协议

凭据字段值**必须**满足以下格式之一:

```
${ENV_NAME}                       # 引用部署侧 env 变量(必选,P1 唯一支持形式)
${ENV_NAME:-default-value}        # 带默认值(P2,可选)
${secret:provider://path}         # 引用 secret provider(P3+,占位预留)
```

**协议要点**:

1. **大括号必选**:`$ENV_NAME` 不接受(避歧义于 shell 变量插值)
2. **名称字符集**:`ENV_NAME` 匹配 `[A-Z][A-Z0-9_]*`(全大写 + 下划线,POSIX env 习惯);约束在 schema validator 与 FE 控件层两侧实施
3. **解析顺序**(部署侧):
   1. 进程 env(`System.getenv(name)`)
   2. Spring `Environment`(覆盖 application.yml `batch.credentials.*` 命名空间下的显式映射)
   3. `${secret:...}` → 走对应 secret provider(P3+ 接 vault 时实现,本 ADR 只定协议)
4. **失败行为**:envRef 解析不到对应 env / secret → **抛 `BizException.of(ResultCode.CREDENTIAL_REF_UNRESOLVED, "error.credential.env_ref_unresolved", refName)`**,**不静默回落空串 / 明文**。任务直接 fail-fast,运维在部署侧补 env 后重派
5. **日志脱敏**:envRef 解析结果**禁**进 log / trace span attribute / outbox payload;只允许日志原 envRef 字符串(`${SECRET_X}`),解析后值仅传给执行器内部 in-memory 变量
6. **存储**:DB / archive 里 `task_param` / `workflow_node_param` 等 JSON 列**只存 envRef 字符串**,不存解析后明文

### 二:SensitiveFieldSchema 标注(单一权威源)

凭据字段在 schema 层显式声明,三层共同消费:

#### 2.1 TaskType descriptor

`step_registry.parameters_schema`(JSON Schema)在凭据属性上加扩展关键字 `x-credential: true`:

```json
{
  "type": "object",
  "properties": {
    "endpoint":   { "type": "string" },
    "username":   { "type": "string", "x-credential": false },
    "password":   { "type": "string", "x-credential": true,
                    "description": "DB 密码,envRef 形如 ${DB_PASSWORD}" },
    "apiAuth": {
      "type": "object",
      "properties": {
        "bearer": { "type": "string", "x-credential": true }
      }
    },
    "csrfToken":  { "type": "string", "x-credential": false }
  }
}
```

要点:

- `x-credential` 是**字段级**而非命名级,叶子节点声明,嵌套对象 / 数组沿 JSON Pointer 递归
- 默认 `false`(不声明 = 不是凭据),避免默认凭据"误圈一片"
- `description` 强制写明 envRef 示例,FE 控件渲染时回显作为占位 placeholder

#### 2.2 SDK 侧 handler 参数声明

ADR-035 / ADR-036 模板里 SDK 自描述 handler 参数,对应字段同样加 `@Credential` 注解:

```java
public record SqlHandlerParams(
    String dsn,
    @Credential String password,
    String csrfToken  // 非凭据,不加注解
) {}
```

SDK 自描述上报到平台时输出等价 JSON Schema(带 `x-credential: true`),走与平台 taskType descriptor 完全一致的下游消费路径。

### 三:FE `SensitiveFieldAlert` 改造

去掉关键字扫描,改读 schema 标注:

```ts
// 新逻辑(简化)
function isSensitiveField(jsonPointer: string, schema: JsonSchema): boolean {
  const node = resolveByPointer(schema, jsonPointer);
  return node?.['x-credential'] === true;
}

function validateValue(value: string): 'ok' | 'plaintext-warn' {
  if (/^\$\{[A-Z][A-Z0-9_]*\}$/.test(value)) return 'ok';
  if (/^\$\{secret:[^}]+\}$/.test(value))    return 'ok';
  return 'plaintext-warn';
}
```

UI 行为:

- 控件根据 `x-credential` 渲染**专用 envRef 输入框**(灰底 + `${...}` 占位 placeholder + 一键插入下拉:列出部署侧白名单 env 名 —— 来自 `batch.credentials.env-whitelist` 配置)
- 输入非 envRef 形式 → 行内 error + 禁止保存,而非现在的"模糊 warning"
- 控件**永不**在 DOM 里渲染解析后明文(避免 DevTools 看密码)

### 四:BE `SensitiveDataValidator` 改造

- 入口同 #242:`workflow_definition` / `task_param` JSON 落库前
- 删除关键字 / 正则集合,改:遍历 schema 找出所有 `x-credential: true` 节点 → 按 JSON Pointer 取值 → 校验 envRef 格式
- 校验结果:
  - 节点不存在 / 值为 null:依 schema `required` 决定 (不在本 ADR 范围)
  - 值是合法 envRef:✅ 通过
  - 值是明文(任意非 envRef 字符串):❌ 拒,抛 `BizException.of(ResultCode.CREDENTIAL_PLAINTEXT_REJECTED, "error.credential.plaintext_rejected", jsonPointer)`
- 非 `x-credential` 节点:不再触发任何凭据检查 —— 假阳性彻底消除

## 实施分阶段

| Phase | 内容 | 交付物 | Issue |
|---|---|---|---|
| **P1** | 协议 + ADR 接受 + `batch-common` 加 `EnvRefSyntax` 工具类(format 正则 / parser stub) | 本 ADR Accepted + `EnvRefSyntax.java` + 单元测 | 本 PR + 后续 |
| **P2** | schema 标注落地:`step_registry.parameters_schema` 现有 ~12 个 taskType 补 `x-credential`;SDK 加 `@Credential` 注解 + 自描述生成 | DB seed migration + 文档更新 + SDK 注解处理器 | TBD |
| **P3** | BE / SDK / FE 三层联动改造:`SensitiveDataValidator` 重写 / SDK handler 参数校验加 envRef 解析钩子 / FE `SensitiveFieldAlert` 改 schema 读取 | 三处代码 + 集成测覆盖 假阳性消除 + 明文拒绝 | TBD |
| **P4** | 删除关键字扫描:移除 FE `SENSITIVE_KEYWORDS` 常量、BE `SENSITIVE_KEY_PATTERN`、SDK 同义遗留 | 代码删除 PR + grep 守护测试(禁出现关键字列表) | TBD |
| **P5(可选)** | `${secret:...}` provider 接入:首接 Spring `ConfigDataLoader` + HashiCorp Vault | 单独 ADR + 实现 | follow-up |

P1 完成 = 本 ADR 与协议规范固化;P2-P4 是平台侧改造主体;P5 是真 vault 集成,留到具备真需求(目前 env 已够用)再做。

## 后果

### 正面

1. **假阳性归零**:`csrf_token` / `idempotency_token` / `partition_key` 这些字段在 schema 里 `x-credential: false` 或默认不声明,验证器根本不查它,不再有假警告
2. **表达力提升**:嵌套字段(`apiAuth.bearer` / `dsn` 嵌入密码)能精确点位;非命名带关键字的真凭据也能被覆盖
3. **三层一致**:FE / BE / SDK 共享同一 schema 单一权威源,改判定只改 schema 不改代码
4. **运维可控**:凭据值集中在部署侧 env,符合 12-factor 实践;配置 JSON 入库 / archive / forensic bundle 全是 envRef 字符串,可放心备份 / 共享 / 进 log
5. **forensic 友好**:ADR-022 取证包里 task_param JSON 永远是 envRef,不会泄露真凭据,合规视角天然脱敏

### 负面 / 成本

1. **schema 维护负担**:新 taskType 必须在 schema 标注 `x-credential`,漏标 = 漏检 —— 用 PR 模板 + reviewer checklist 兜底,加 schema lint 守护
2. **存量任务参数迁移**:历史 `task_param` 里可能已经塞了明文凭据(虽然主要发生在开发环境)。**P3 上线策略**:
   - 不批量改历史数据(留旧任务"明文已存在"的事实)
   - 上线后**只对新 / 改任务**强制 envRef
   - 旧任务再次编辑时触发 envRef 校验
   - 提供一次性扫描脚本(只读)出审计报表,不自动改库
3. **FE 控件改造工作量**:envRef 输入框 + env 下拉白名单是新组件,~2 sprint
4. **envRef 解析失败 fail-fast 可能误伤上线**:部署侧 env 漏配 → 任务直接挂。**缓解**:在 dry-run(ADR-026)阶段提前解析,启动期 sanity check 列出本租户所有引用的 env,缺失则启动 fail-fast

### 中性

- 不影响 ADR-035 的 API Key / `X-Internal-Secret` 传输 —— 那是平台 ↔ SDK 通道,凭据存储是 `batch.api_key` 表(已加密列),本 ADR 管的是**用户配置 JSON**这条路径
- 不影响 Kafka SASL / DB 连接池凭据 —— 那些在 application.yml,本来就走 Spring `${}` 解析

## 关联与替代方案

### 替代方案对比

| 方案 | 取舍 |
|---|---|
| 维持现状关键字扫描 | ❌ 假阳性已是 PR #40 评审主诉,根问题不解 |
| 关键字 + 字段名白名单(允许 `csrf_token` 等加白) | ❌ 黑白名单永远跑不过新业务命名,维护负担 |
| 在 BE 层加密所有 `task_param` JSON | ❌ 改写存储 + key 管理 + forensic 难;且不解 FE "用户写没写明文" 的判定问题 |
| 强制凭据走单独表 `task_credential` | ❌ 改 ER + 改 schema + 改 SDK 协议,远超 #40 修复范围;且与 K8s `secretKeyRef` 业界路径不一致 |
| **本 ADR envRef** | ✅ 协议 + schema 标注的纯增量,无 ER 变更,业界对齐 |

### 关联文档

- [ADR-035 §鉴权 / Kafka SASL 凭据](./ADR-035-tenant-self-hosted-worker-sdk.md):平台 ↔ SDK 通道凭据,本 ADR 与之**并列不冲突**
- [ADR-025 workflow 静态校验](./ADR-025-workflow-static-validator.md):本 ADR P3 的 `SensitiveDataValidator` 接入 ADR-025 的 validator 链
- [ADR-022 forensic bundle](./ADR-022-forensic-audit-bundle.md):取证包内 `task_param` JSON 走 envRef 后天然脱敏,本 ADR 顺带帮 ADR-022 解决"取证包脱敏"长期心病
- PR #40 / Issue #242 / Issue #237:本 ADR 的直接触发源
- Round-2 §4 P0:列入"凭据处理统一化"的 P0 项

## 评估提问(reviewer checklist)

实施 PR 评审时请答:

1. 本 PR 新增 / 改的 taskType,`parameters_schema` 里凭据字段是否都标 `x-credential: true`?
2. SDK handler 参数,凭据字段是否都加 `@Credential` 注解?
3. 是否引入新的 "用字段名猜语义" 的代码(命名 match / keyword scan)?有 → reject
4. envRef 解析后值是否进 log / trace / outbox?有 → reject
5. 是否在 DB 列 / archive 列里存了解析后明文?有 → reject
