# 前端 issue 移交清单（2026-05-17）

> 后端日志分析得出的"看似 server 错但实际是 client 没正确校验/提示/隐藏"的清单。每条都有：FE 影响 / 复现 / 推荐修法 / 对应后端代码已修部分。

## 1. 表单必填字段未拦截就提交（**最高频，共 11 处 validation 异常**）

### 现象
用户点"创建"按钮，前端没拦截 `null` / 空字符串 / 不合规字段，直接 POST 到后端，后端 `@NotBlank` / `@Size` / `@Min` 返 400。

### 涉及表单 + 字段

| Controller | 字段 | 后端约束 |
|---|---|---|
| `POST /api/console/queues` | `queueCode` | `@NotBlank @Size(1-128)` |
| `POST /api/console/queues` | `queueName` | `@Size(1-256)` |
| `POST /api/console/quota-policies` | `policyCode` | `@NotBlank @Size(1-128)` |
| `POST /api/console/quota-policies` | `maxRunningJobsPerTenant` | `@Min(0)` |
| `POST /api/console/quota-policies` | `maxPartitionsPerTenant` | `@Min(0)` |
| `POST /api/console/quota-policies` | `maxQpsPerTenant` | `@Min(0)` |
| `POST /api/console/quota-policies` | `fairShareWeight` | `@Min(1)` |
| `POST /api/console/alert-routings` | `routeCode` | `@NotBlank @Size(1-128)` |
| `POST /api/console/alert-routings` | `team` | `@Size(1-64)` |
| `POST /api/console/alert-routings` | `receiver` | `@Size(1-256)` |
| `POST /api/console/configs/{tenantId}/releases` | `configName` | `@NotBlank` |
| `POST /api/console/configs/{tenantId}/releases` | `configKey` | `@NotBlank` |

### FE 修法
- Element Plus `ElForm + rules` 表单级 + 字段级校验，提交前 `formRef.validate()`，不通过禁用按钮 + 高亮红字
- 字符长度 / 整数下限直接给最大长度 + min 属性
- 后端字段 + 限制以 `docs/api/console-api.openapi.yaml` 为权威，前端 codegen 后做 schema 校验也行

## 2. 枚举字段用 `<el-input>` 收任意字符串（**真 500 bug**）

### 现象
用户输入 `jobType="SHELL"`、`pipelineType="ssssasas"`（任意字符串），后端 `@NotBlank` 通过，DB CHECK 约束撞死 → 500 "data integrity violation"。

后端**已加** `@Pattern` 前置（commit `baed3416`），现在返 400。但前端还该改成下拉。

### 涉及表单

| 字段 | 合法值 |
|---|---|
| `JobDefinitionCreateRequest.jobType` | GENERAL / IMPORT / EXPORT / PROCESS / DISPATCH / WORKFLOW |
| `JobDefinitionCreateRequest.scheduleType` | CRON / FIXED_RATE / MANUAL |
| `JobDefinitionCreateRequest.triggerMode` | SCHEDULED / API / MANUAL（参考 enum）|
| `JobDefinitionCreateRequest.retryPolicy` | NONE / FIXED / EXPONENTIAL |
| `JobDefinitionCreateRequest.catchUpPolicy` | NONE / AUTO / MANUAL_APPROVAL |
| `PipelineDefinitionSaveRequest.pipelineType` | IMPORT / EXPORT / PROCESS / DISPATCH |
| `WorkflowDefinition` workflow_type | DAG / PIPELINE / MIXED |
| `WorkflowNode` node_type | START / END / TASK / GATEWAY / FILE_STEP / JOB |
| `WorkflowEdge` edge_type | SUCCESS / FAILURE / CONDITION / ALWAYS |
| `FileChannel` channel_type | SFTP / API / API_PUSH / EMAIL / NAS / OSS / LOCAL |

### FE 修法
- 用 `<el-select>` + 后端字典 API `GET /api/console/meta/options?category=job_type` 等取选项（已暴露见 `ConsoleMetaQueryService.REGISTRATIONS`）
- 别 hardcode 枚举值（后端加 PROCESS / WORKFLOW 时前端不用改）
- 强制必填 `:required` 属性

## 3. 数字字段无 max 限制，超 int 范围发到后端（**真客户端 bug**）

### 现象
`POST /api/...` body 含 `999999999999999`（15 位数字）→ Jackson 反序列化失败 → 400 `Numeric value out of range of int (-2147483648..2147483647)`。出现 8 次。

### FE 修法
- `<el-input-number :max="2147483647" :min="0">`
- 或表单层加 `Number.isSafeInteger` 校验

## 4. 用户角色不够却看到/点击高危按钮（**UX + 安全**）

### 现象
`console access denied: POST /api/console/queues` × 8 次 → 当前登录角色（TENANT_USER / AUDITOR）没创建 queue 权限，但 UI 没隐藏"创建"按钮。

### FE 修法
- 按 `roles` 隐藏 / disable 按钮：
  - `ROLE_ADMIN` / `ROLE_CONFIG_ADMIN` → 显示"创建"按钮
  - `ROLE_AUDITOR` / `ROLE_USER` → 隐藏或灰
- 权限矩阵看 `docs/api/console-api-protocol.md § Role Permission Matrix`
- 推荐做法：单一 `usePermission()` composable，按当前 user roles 返 `canCreate(resource)` / `canDelete(resource)`，所有按钮统一调

## 5. 日期字段格式不一致（**轻度 bug**）

### 现象
后端收到 `Text '2026-05-17' could not be parsed at index 10` — 前端发 `'2026-05-17'`（10 个字符），后端期望 ISO datetime `'2026-05-17T00:00:00Z'` 或 epoch。

### FE 修法
- 用 `dayjs(date).toISOString()` 而非 `dayjs(date).format('YYYY-MM-DD')`
- 或检查 OpenAPI schema 期望类型（`date` vs `date-time`），用对应格式
- 后端实际已经能宽松解析（`parseFlexibleInstant`），但留 INFO log。不阻塞但建议规范化

## 6. 业务 NOT_FOUND 路径用户感知不到（**UX**）

### 现象
`console biz exception: code=NOT_FOUND message=error.job.definition_not_found` × 2

### FE 修法
- 业务异常 `code=NOT_FOUND` 时 ElMessage 弹"该任务定义不存在或已删除"
- 当前可能弹的是 "未知错误" 或啥都不弹

## 7. 凭证错误的回显（**安全/UX**）

### 现象
`code=UNAUTHORIZED message=error.auth.invalid_credentials` × 2 — 登录密码错

### FE 修法
- 登录页根据 `code=UNAUTHORIZED` 区分：
  - `error.auth.invalid_credentials` → "账号或密码错误"
  - `error.auth.account_locked` → "账号已被锁定，联系管理员"
  - `error.auth.session_expired` → "会话过期，请重新登录"

## 8. 幂等 key 提示（**轻度 UX**）

### 现象
`idempotency Redis GET unavailable — fail-closed: key=...` × 4 — Redis 超时时后端 fail-closed 拒绝该请求

### FE 修法
- 收到 `code=SERVICE_UNAVAILABLE message=error.idempotency.redis_unavailable` 时 ElMessage 弹"系统忙，请稍后重试"
- 重试时**重新生成新的 idempotency-key**（不是用同一个 retry）

---

## 优先级建议

| 优先级 | issue | 工作量 |
|---|---|---|
| **P0** | #2 枚举字段下拉化（防 500 数据脏） | 1 天 |
| **P0** | #4 按角色隐藏/灰按钮 | 1-2 天 |
| **P1** | #1 表单必填 + 长度 + Min 校验全表 | 2 天 |
| **P1** | #3 数字字段加 max 防溢出 | 半天 |
| **P2** | #5 日期统一 ISO | 半天 |
| **P2** | #6/#7 业务异常 / 登录错回显 | 半天 |
| **P2** | #8 幂等错回显 | 半天 |

## 关联文档

- `docs/api/console-api.openapi.yaml` — 字段约束权威
- `docs/api/console-api-protocol.md` — 角色权限矩阵
- 后端已修部分：commit `baed3416`（jobType / scheduleType / pipelineType `@Pattern` 前置）
- 完整日志样本：本仓库 `logs/app/console.log`（dev 实际触发的 4xx 全在）
