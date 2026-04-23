# Console API Protocol

This document is the human-readable contract for the console frontend.
When the API surface changes, update this file and [console-api.openapi.yaml](./console-api.openapi.yaml) together.

## Changelog

| 日期       | 变更摘要                                                                                                                                      |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 2026-04-23 | 新增 `GET /api/console/queries/partitions`：按作业实例分页查询 `job_partition` 列表，支持 `partitionStatus` 过滤，按 `partition_no ASC` 排序；响应 `ConsoleJobPartitionResponse`（13 字段：`id/tenantId/jobInstanceId/partitionNo/partitionKey/partitionStatus/workerGroup/workerCode/retryCount/businessKey/leaseExpireAt/startedAt/finishedAt`）。前端 PartitionView 改为服务端分页，替换已废弃的本地聚合调用 |
| 2026-04-20 | 触发器运维 5 条路径由 `/api/console/triggers` 迁到 `/api/console/ops/triggers`（list/{jobCode}/register/unregister/pause/resume），语义归 Ops 救急入口；日常禁用 job 请走 `toggleEnabled`，`TriggerReconciler` 以 30s 周期把 Quartz 收敛到 DB（`ROLE_ADMIN` 权限不变，限流路径前缀同步调整） |
| 2026-04-20 | `/api/console/meta/enums` 的 `triggerType` 字典新增 `RERUN`（重跑触发）；与 V62 迁移中扩展的 `ck_trigger_request_type` / `ck_job_instance_trigger_type` CHECK 约束对齐；OpenAPI `MetaEnumItem` 由后端反射下发，schema 无需改 |
| 2026-04-20 | 删除 7 个单表 Excel 维护接口的 upload/preview/previewWorkbook/apply（job-definitions / workflows / business-calendars / quota-policies / batch-windows / file-channels / pipeline-definitions，共 28 条路由，均已由 tenant-package 合并导入取代）。保留各自 `/export` 和 `/template` 导出；同步删除 49 个仅被这些端点引用的 schema（含 Common/Preview/Apply wrapper 和 per-entity request body） |
| 2026-04-20 | 补录 `POST /api/console/auth/stream/ticket` 到 OpenAPI（2026-04-18 新增端点，Changelog 已记但 yaml 漏写，CI path 一致性校验失败）；新增 `ConsoleSseTicketResponse` schema（`{ticket: string}`）和 `CommonResponseConsoleSseTicketResponse` 包装器 |
| 2026-04-18 | `GET /api/console/auth/me` 响应体 `ConsoleAuthProfileResponse` 补齐 `menus` 字段（后端早已下发，OpenAPI 漏写导致前端 codegen 不可见，退化为硬编码）；新增 `ConsoleMenuGroup` / `ConsoleMenuItem` schema，字段：`key`/`title`/`icon`/`minRole`（VIEWER/OPERATOR/ADMIN）、`children`、`path`；前端应从 `menus` 渲染侧边栏，废弃本地硬编码 navigation |
| 2026-04-18 | 新增 `POST /api/console/auth/stream/ticket` SSE 一次性 ticket 鉴权端点；SSE 连接支持 `?ticket=` 参数（替代已移除的 `?token=`）；outbox-deliveries/outbox-retries stream 补充业务事件发布；`/meta/enums` 新增 8 个字典：triggerStatus/deliveryStatus/notificationChannelType/tenantStatus/logType/workflowDefinitionStatus/tenantConfigInitAction/triggerResourceType；OpenAPI 补 9 个 enum schema |
| 2026-04-17 | 抽取 `DictEnum` 接口（`code()` / `label()`），batch-common 下全部 60 个公共枚举统一实现；`ConsoleMetaQueryService.EnumReg` 精简为 `(key, enumClass)` 两字段，`GET /api/console/meta/enums` 新增 3 个字典 key：`fileDispatchStatus` / `fileReceiptStatus` / `pipelineRunStatus`（原为裸枚举，补齐 code/label 后对外暴露）；守护测试 EXCLUDED 白名单从 6 收窄到 3（保留 `ResultCode` / `WorkflowNodeCode` / `JobStatus`），新增 DictEnum 实现的强制断言 |
| 2026-04-17 | `GET /api/console/meta/enums` 新增 17 个字典 key：`priorityLevel` / `aiPromptDecision` / `checksumType` / `workflowJoinMode` / `fileDispatchRunStatus` / `compensationStatus` / `retryScheduleStatus` / `encryptType` / `compressType` / `errorSinkType` / `priorityBand` / `stepInstanceStatus` / `runMode` / `skipAction` / `workflowNodeRunStatus` / `deadLetterReplayStatus` / `skipThresholdMode`；同步补齐 `CommonResponseMetaEnums` schema 定义；新增 `ConsoleMetaEnumRegistrationTest` 守护测试，强制新增枚举二选一：注册或加入显式 EXCLUDED 白名单 |
| 2026-04-16 | 6 个 Excel 控制器（file-templates/file-channels/alert-routings/batch-windows/quota-policies/resource-queues）的 upload/apply 请求响应统一为共享类型 `ExcelUploadResponse`/`ExcelApplyResponse`/`ExcelApplyRequest`/`ExcelRowIssue`，旧 per-entity schema 改为 `$ref` 别名 |
| 2026-04-16 | `ExcelApplyResponse` 新增 `skippedRows` 字段；`ExcelPreviewResponse`（6 个 per-entity 变体）新增 `previewWorkbookUrl` 字段 |
| 2026-04-16 | 新增 `POST /api/console/config/alert-routings/excel/quick-import`：一键导入（upload + validate + apply 合并），无错误自动 apply（`applied=true`），有错误返回 preview + workbook URL（`applied=false`）；支持 `skipInvalid` 参数跳过无效行 |
| 2026-04-11 | list 查询 `enabled` 参数默认值改为 `true`：job-definitions / workflow-definitions / file-channels / file-templates 四类列表接口（含 `/queries/` 前缀版本），不传 `enabled` 时只返回已启用记录，需查禁用记录需显式传 `enabled=false` |
| 2026-04-11 | 软删除改 `PATCH`：`POST /{id}/toggle?enabled=` 及 `POST /batch-toggle` 统一改为 `PATCH /{id}`（body: `EnabledPatchRequest`）和 `PATCH /batch`（body: `BatchEnabledPatchRequest`），适用于 job-definitions / workflow-definitions / file-channels / file-templates |
| 2026-04-11 | `POST /api/console/files/delete` 改为 `DELETE /api/console/files/{fileId}`，请求体拆为 path/query 参数，统一物理删除走 `DELETE` 方法 |
| 2026-04-11 | 移除 `DELETE job-definitions/{id}`、`workflow-definitions/{id}`、`file-channels/{id}`、`file-templates/{id}`；软删除统一走 `PATCH /{id}`，`DELETE` 仅保留于物理删除场景 |
| 2026-04-11 | 删除 `DELETE /api/console/users/{id}`：账号不可物理删除，停用走 disable，彻底下线走租户 suspend |
| 2026-04-11 | 删除 `POST /api/console/users`（独立创建账号）：每个租户仅一个运营账号，由建租户接口统一创建，后续通过 `PUT /api/console/users/{id}` 调整权限 |
| 2026-04-12 | 新增 `GET /api/console/config/tenant-package/excel/export`：导出当前租户全量配置包为 8-Sheet xlsx（job_definition / file_channel / alert_routing / pipeline / pipeline_step / workflow_definition / workflow_node / workflow_edge），文件可直接回灌至合包导入接口 |
| 2026-04-12 | 10 个独立 Excel 导入 Controller 的 upload / preview / previewWorkbook / apply 端点标注 `deprecated`；export / template 端点不受影响；推荐改用 `/api/console/config/tenant-package/excel` 系列接口 |
| 2026-04-12 | `GET /api/console/meta/enums` 新增三个 key：`operationType`（文件审计操作类型，10 个值）、`operationResult`（SUCCESS / FAILED）、`fileStatus`（文件状态，11 个值）|
| 2026-04-12 | 新增 `GET /api/console/meta/biz-types?tenantId=`：按租户动态返回 `file_record` 中已有的 distinct `biz_type` 值，用于文件列表业务类型下拉筛选 |
| 2026-04-12 | 新增 `GET /api/console/config/tenant-package/excel/template`、`POST /upload`、`GET /preview/{token}`、`GET /preview/{token}/workbook`、`POST /apply/{token}`：8-Sheet 租户配置包 Excel 导入（job_definition / file_channel / alert_routing / pipeline / workflow，单事务，含跨 Sheet 依赖校验） |
| 2026-04-12 | `POST /api/console/tenants/batch`：`initConfigFrom` 不传时默认使用 `default` 模板租户（原逻辑：不传则跳过配置初始化） |
| 2026-04-12 | `POST /api/console/tenants/batch` 新增可选字段 `initConfigFrom`（源租户 ID）和 `initMode`（默认 `SKIP_EXISTING`）：非空时建完租户后自动复制源租户全部配置，响应体从 `List<ConsoleTenantResponse>` 改为 `{tenants, configInit}`（`configInit` 在未传 `initConfigFrom` 时为 null） |
| 2026-04-12 | 补齐 OpenAPI 缺失接口 `GET /api/console/config/file-templates/excel/preview/{uploadToken}/workbook`；修正 18 处 CRUD 创建/更新/复制响应体错误类型（`CommonResponseLong`/`CommonResponseString` → `CommonResponseObject`）；协议正文同步 PATCH 改造、移除已删除的 user create/delete 和 POST files/delete 接口描述 |
| 2026-04-11 | 新增 `POST /api/console/tenants/batch` 批量建租户端点，共享密码（≥12位）+ 用户名前缀（默认 `op-`），单事务 |
| 2026-04-11 | `POST /api/console/tenants` 新增必填字段 `username`/`password`，建租户时同步创建 `ROLE_TENANT_USER` 操作账号 |
| 2026-04-12 | `GET /api/console/meta/enums` 新增 6 个字典 key：`taskStatus` / `partitionStatus` / `workflowRunStatus` / `approvalType` / `outboxPublishStatus` / `aiPromptCategory`；OpenAPI 响应 schema 由 `CommonResponseObject` 改为精确的 `CommonResponseMetaEnums`，补全所有 34 个 key 的 schema 定义 |
| 2026-04-11 | 补齐 5 个新域（BatchWindow/BusinessCalendar/PipelineDefinition/TenantQuotaPolicy/ResourceQueue）Excel Upload/Preview/Apply 全套 schema（共 47 个），消除所有悬空 $ref；添加 Changelog 标识 |

## Common Headers

- `X-Request-Id`: optional, generated by server if absent
- `X-Trace-Id`: optional, generated by server if absent
- `X-Tenant-Id`: optional, can be used by gateway or upstream
- `X-Operator-Id`: optional in local mode, should be set by gateway in production
- `Idempotency-Key`: required for all write APIs
- `Authorization: Bearer <jwt>`: preferred authentication header for the console frontend
- Security headers are emitted by the backend on every console response:
  - `Content-Security-Policy`
  - `X-Frame-Options`
  - `X-Content-Type-Options`
  - `Referrer-Policy`
  - `Permissions-Policy`
  - `Strict-Transport-Security`

## Common Response Body

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {},
  "meta": {
    "requestId": "req-20260321T100000Z-ab12cd34",
    "traceId": "1a2b3c4d5e6f7890",
    "timestamp": "2026-03-21T10:00:00Z"
  }
}
```

## Standard Data Models

### CommonResponse<T>

- `code`: application-level status code
- `message`: human-readable status message
- `data`: typed payload, list, or documented primitive
- `meta`: request tracing metadata

### PageRequest

- `pageNo`: 1-based page number
- `pageSize`: number of items per page
- New paginated endpoints should use `PageRequest` instead of ad hoc limit fields when practical.

### PageResponse<T>

- `total`: total number of items
- `pageNo`: current page number
- `pageSize`: requested page size
- `items`: page items

### Common Header Mapping

- `X-Request-Id` and `X-Trace-Id` are propagated into response metadata.
- `X-Tenant-Id` is the tenant context input used by gateway or upstream.
- `X-Operator-Id` is the operator identity header for write actions and audit trails.
- `Idempotency-Key` is mandatory for write actions and must remain stable for retryable submissions.
- `Authorization: Bearer <jwt>` is the preferred console login/session credential.

## API Contract Rules

### Authentication And Authorization

- Console endpoints are protected by Spring Security.
- JWT bearer auth is the preferred login/session mechanism.
- `POST /api/console/auth/login` validates one of the seeded console accounts stored in the platform database and issues a JWT.
- Single-account login is single-session by default. A fresh login invalidates older JWTs for the same username and tenant.
- Legacy `X-Console-Token` header auth is retained for compatibility and migration.
- `POST /api/console/auth/token` exchanges an authenticated console session for a JWT access token.
- `GET /api/console/auth/me` returns the current authenticated principal, including `menus` — the role-filtered sidebar tree produced by `ConsoleMenuRegistry`. Frontends should render navigation from this field rather than hard-coding menu items.
- `ROLE_ADMIN` can perform all write actions.
- `ROLE_AUDITOR` is read-only for operational views and queries.
- `ROLE_CONFIG_ADMIN` can access config and worker operations, but not all write actions.
- `ROLE_TENANT_USER` can view job/file/workflow status, trigger jobs, and download exports, but cannot modify configurations or perform ops actions.
- AI endpoints require both role access and prompt authorization checks.

### Role Permission Matrix

| Operation | ADMIN | AUDITOR | CONFIG_ADMIN | TENANT_USER |
|-----------|:-----:|:-------:|:------------:|:-----------:|
| Dashboard / Query / Meta / Realtime SSE | ✅ | ✅ | ✅ | ✅ |
| Job/File/Workflow status view | ✅ | ✅ | ✅ | ✅ |
| Scheduler snapshot view | ✅ | ✅ | ✅ | ✅ |
| Report Excel export | ✅ | ✅ | ✅ | ✅ |
| File pipeline observability | ✅ | ✅ | ✅ | ✅ |
| Job definition / workflow definition detail view | ✅ | ✅ | ✅ | ✅ |
| **Trigger job** | ✅ | ❌ | ❌ | ✅ |
| Config view (template / channel / Excel export) | ✅ | ✅ | ✅ | ❌ |
| Config modify (create / update / Excel import) | ✅ | ❌ | ✅ | ❌ |
| Config delete / reset | ✅ | ❌ | ❌ | ❌ |
| Ops actions (compensate / rerun / approve / dead-letter) | ✅ | ❌ | ❌ | ❌ |
| Notification channel / rule CRUD | ✅ | ❌ | ✅ | ✅ |
| Notification delivery log view | ✅ | ✅ | ✅ | ✅ |
| Config approval (submit / approve / reject) | ✅ | ❌ | ❌ | ❌ |
| Config approval detail view | ✅ | ✅ | ✅ | ❌ |
| Config sync (export / preview / import) | ✅ | ❌ | ❌ | ❌ |
| Config sync log view | ✅ | ❌ | ❌ | ❌ |
| Worker management (drain / restore) | ✅ | ❌ | ✅ | ❌ |
| Scheduler actions (pause-all / resume-all) | ✅ | ❌ | ❌ | ❌ |
| Alert management | ✅ | ❌ | ✅ | ❌ |
| **Tenant management (CRUD)** | ✅ | ❌ | ❌ | ❌ |
| **Tenant list / detail view** | ✅ | ❌ | ✅ | ❌ |
| **User account management (CRUD)** | ✅ | ❌ | ❌ | ❌ |

### Tenant Rules

- Login (`POST /api/console/auth/login`) does not require `tenantId`; the backend resolves tenant from the globally unique username.
- All other requests must carry an explicit `tenantId` either in the request body, query string, or upstream header mapping.
- Server-side tenant resolution is authoritative.
- Tenant mismatch must fail fast with `FORBIDDEN` or `STATE_CONFLICT` depending on the route semantics.
- Frontend must not treat tenant values as trusted client state.

### Idempotency Rules

- All write APIs require `Idempotency-Key`.
- The key must be stable for the same business action and unique across distinct actions.
- Duplicate write requests must be safe to retry.
- If idempotency handling is missing, the request should fail with `MISSING_IDEMPOTENCY_KEY`.

### Request And Response Rules

- Use `GET` for read-only endpoints.
- Use `POST` for commands, approvals, operations, and state transitions.
- Response bodies should use the common envelope:
  - `code`
  - `message`
  - `data`
  - `meta`
- `data` must be a typed DTO, list, or a documented primitive. Avoid anonymous `Map<String, Object>` in new endpoints.
- Approval lists, config release lists, secret version lists, change logs, and batch approval results use dedicated response DTOs instead of raw entities or generic maps.
- `meta` carries request tracing information and server timestamp.
- Treat all response strings as plain text. The frontend must not inject untrusted content through `innerHTML` or equivalent HTML sinks.
- New endpoints should prefer explicit DTOs over raw entities and should reuse `PageRequest` / `PageResponse` when pagination is required.
- Approval status values currently exposed by the backend are `PENDING`, `APPROVED`, `REJECTED`, and `EXECUTED`; the frontend should not assume `CLOSED` or `CANCELLED` are terminal approval states.

### Query And Pagination Rules

- Query endpoints should be filterable by `tenantId` and domain-specific identifiers.
- For list endpoints, prefer bounded result sets and explicit limit defaults.
- New list APIs should define pagination or at least `limit` semantics in both protocol and OpenAPI.
- Sorting and filtering should be explicit in request DTOs; do not rely on implicit database ordering.

### Error Semantics

- Validation failures use `VALIDATION_ERROR` or `INVALID_ARGUMENT`.
- Missing authentication maps to `UNAUTHORIZED`.
- Access violations map to `FORBIDDEN`.
- Missing resources map to `NOT_FOUND`.
- Concurrent or state mismatch cases map to `CONFLICT` or `STATE_CONFLICT`.
- Unimplemented or temporarily unavailable capabilities must not silently return success.

### Compatibility Rules

- Backward-compatible changes:
  - adding nullable fields
  - adding new endpoints
  - adding new enum values when documented
- Breaking changes:
  - removing fields
  - renaming fields
  - changing field semantics
  - changing idempotency or tenant behavior
- Breaking changes must update this document, `console-api.openapi.yaml`, and the frontend contract together.
- Prefer additive evolution over mutation of existing payloads.

### Text Safety Rules

- High-risk user-visible text fields are normalized before persistence or forwarding. These fields include reasons, titles, descriptions, prompts, and audit previews.
- Rich text is not a supported input format for the console API.
- Query and audit responses may contain escaped text. The frontend should render them as text nodes and not assume HTML markup.
- If a field needs to carry structured data, it must be encoded as JSON and validated as JSON, not as free-form HTML.

### Excel Config Maintenance Rules

- Excel maintenance is a controlled extension of the console, not a general-purpose file upload feature.
- All Excel-backed config flows must follow `upload -> preview -> apply -> export` through a dedicated adapter layer.
- Upload requests must use `multipart/form-data` with a single form field named `file`.
- `upload` returns an `uploadToken` and the token is the only handle used by `preview` and `apply`.
- `preview` must be a read-only check; it may validate and summarize rows, but it must not persist anything.
- `apply` must take the `uploadToken` in the path and the idempotency key header in the request headers.
- `export` remains a raw `.xlsx` download, not a JSON response.
- Each importable object also provides `GET /template` for downloading a blank template (for first-time import with no existing data).
- `file template config`, `file channel config`, `workflow definition / node / edge`, `job definition`, and `alert routing / notification policy` are the first-class editable domains.
- Excel templates must be user-facing edit forms, not raw database dumps.
- The main sheet should use frozen headers, required-field highlighting, enum dropdowns where practical, sample values, and automatic column sizing.
- A workbook should include a concise instruction sheet and, where useful, a dictionary sheet for enum values and a validation sheet for preview errors.
- Preview must report row numbers, column names, and validation reasons so the user can correct the sheet without guessing.
- `workflow definition / node / edge` and the safe subset of `job definition` are exportable and optionally importable only under strict validation.
- `config release`, `config change log`, `secret version`, `audit log`, `scheduler snapshot/history`, `worker registry`, and `outbox retry/delivery` are export-only records.
- Secret material, tokens, passwords, approval records, and other runtime facts must never be accepted as Excel import sources.
- Import payloads must be validated against a whitelist of editable columns before any write is applied.
- Excel processing logic must stay inside a dedicated adapter layer; controllers must only handle HTTP boundaries.

### Frontend Security Rules

- Use text binding for all server-provided strings.
- Do not render API strings via `innerHTML`, `v-html`, `dangerouslySetInnerHTML`, or similar APIs unless the content has been explicitly sanitized and approved.
- Do not trust role claims for security decisions on the client. They are only for routing and menu visibility.
- Treat `401` as login/session failure and `403` as authorization failure. Do not infer authorization by inspecting response body text.

## URL Design Conventions

### HTTP Method Assignment

| Method | Semantics | Typical usage |
|--------|-----------|---------------|
| `GET` | Read, never mutates state | Query, detail, list, export |
| `POST` | Create a resource **or** trigger a command/action | Create, trigger, approve, cancel, toggle |
| `PUT` | Idempotent full replacement of an existing resource | Update resource definition (replaces the whole record) |
| `DELETE` | Remove a resource | Hard delete |

`PATCH` is used exclusively for **enable/disable** operations:
- `PATCH /{id}` — single resource toggle, body: `EnabledPatchRequest { tenantId, enabled }`
- `PATCH /batch` — batch toggle (up to 200 ids), body: `BatchEnabledPatchRequest`

Currently applies to: `job-definitions`, `workflow-definitions`, `file-channels`, `file-templates`.  
All other partial updates are handled by a dedicated `PUT` endpoint or an action endpoint (see below).

### Resource URL Naming

1. **All resource paths use plural kebab-case.**  
   `GET /api/console/job-definitions`, `GET /api/console/queries/instances`, `GET /api/console/tenants/quota`

2. **Do not use singular for a resource collection**, even if the context is "my tenant" or "meta info".  
   Correct: `/api/console/queries/…`, `/api/console/tenants/…`  
   Wrong: `/api/console/queries/…`, `/api/console/tenant/…`

3. **Exception — namespace prefixes are singular by convention.**  
   `/api/console/meta/enums` — "meta" here is a namespace qualifier, not a collection.  
   `/api/console/auth/login` — "auth" is a namespace, not a list of auth objects.

### Action (Command) Endpoints

For state-change operations that are not a simple CRUD (toggle, cancel, approve, drain, rerun, replay, etc.) use the **action suffix pattern**:

```
POST /api/console/{resources}/{id}/{action}
```

Examples:
- `POST /api/console/job-definitions/{id}/toggle`
- `POST /api/console/workers/{workerCode}/drain`
- `POST /api/console/approvals/{approvalNo}/approve`

Rationale: these commands are not idempotent replacements of a resource (which would be `PUT`). They represent an intent/event. GitHub API, Stripe API and similar industry APIs follow the same convention.

### Batch Action Endpoints

Batch operations on the same resource use the **`/batch-{action}`** path pattern (hyphenated, same level as the resource root):

```
POST /api/console/{resources}/batch-{action}
```

Examples:
- `POST /api/console/approvals/batch-approve`
- `POST /api/console/approvals/batch-reject`
- `POST /api/console/job-definitions/batch-toggle`

**Do not** use a path-separator style (`/batch/approve`). All batch endpoints must use the hyphenated form.

### Summary Table

| Pattern | Example | Correct |
|---------|---------|---------|
| Resource collection | `/api/console/job-definitions` | ✅ plural kebab-case |
| Resource detail | `/api/console/job-definitions/{id}` | ✅ |
| Create resource | `POST /api/console/job-definitions` | ✅ |
| Update resource | `PUT /api/console/job-definitions/{id}` | ✅ full replacement |
| Delete resource | `DELETE /api/console/job-definitions/{id}` | ✅ |
| Action on resource | `POST /api/console/job-definitions/{id}/toggle` | ✅ verb suffix |
| Batch action | `POST /api/console/approvals/batch-approve` | ✅ hyphenated |
| Query namespace | `/api/console/queries/instances` | ✅ plural |
| Meta namespace | `/api/console/meta/enums` | ✅ singular namespace exception |
| ❌ Singular collection | `/api/console/queries/instances` | ❌ |
| ❌ Verb path-sep batch | `POST /api/console/approvals/batch-approve` | ❌ |

---

## Current Route Catalog

### Ops

- `POST /api/console/auth/login`
- `POST /api/console/auth/token`
- `GET /api/console/auth/me`
- `GET /api/console/ops/summary`
- `GET /api/console/ops/summary/events`

Default seeded accounts:

| Username | Password | Roles | Description |
|----------|----------|-------|-------------|
| `admin` | `admin123` | `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_CONFIG_ADMIN` | Super admin |
| `auditor` | `auditor123` | `ROLE_AUDITOR` | Read-only auditor |
| `config-admin` | `config123` | `ROLE_CONFIG_ADMIN` | Configuration manager |
| `tenant-user` | `tenant123` | `ROLE_TENANT_USER` | Tenant business user |

Username is globally unique. Login requires only `username` + `password`; tenant is resolved from the account record automatically.

Minimal login page:

- `GET /console-login.html`

`POST /api/console/auth/login` accepts:

- `username` required
- `password` required
- Username is globally unique; tenant is resolved from the account record automatically, no need to pass `tenantId`.
- Repository does not ship plaintext default passwords; only password hashes are stored in the platform database.

Session rule:

- The same seeded account keeps only one active JWT per tenant.
- A newer `POST /api/console/auth/login` or `POST /api/console/auth/token` replaces the previous JWT for that account.

`GET /api/console/ops/summary` is the first-screen operational snapshot. The server requires **`tenantId` as a query parameter** (not only `X-Tenant-Id`). The response is a typed summary payload inside `CommonResponse` and should be treated as the control plane entry for the console home page. It includes:

- pending approvals
- open alerts and critical alerts
- running and failed jobs
- SLA breach count
- worker online, draining, and offline/decommissioned distribution
- outbox retry backlog and delivery failures

`GET /api/console/ops/summary/events` is the first-screen realtime stream. It emits `ops-summary-updated` payloads with the full `ConsoleOpsSummaryResponse` snapshot after key write actions succeed. The frontend should use it to invalidate or replace the cached summary on the home page.

When the shared realtime channel receives a `summaryRefresh=true` envelope for `ops-summary`, the console consumer reloads the latest summary from the database and emits `ops-summary-updated` instead of forwarding the trigger event verbatim. In other words, the trigger signal is internal, and `ops-summary-updated` is the client-facing data update event.

Query parameters:

- `heartbeatMillis` is optional and controls the SSE keepalive interval.
- `initialSnapshot` is optional and defaults to `true`. When set to `false`, the stream only listens for later updates and does not emit an immediate snapshot after subscription.

Frontend integration rule:

1. Load `GET /api/console/ops/summary?tenantId=...` first and render the initial homepage state from that response.
2. Open `GET /api/console/ops/summary/events?tenantId=...` only after the first snapshot has been rendered.
3. On `ops-summary-updated`, replace the cached summary with the event payload directly when possible.
4. If the UI uses a query/cache layer, `setQueryData` or an equivalent cache replacement is preferred over an unconditional refetch, because the event already carries the full snapshot.
5. Use `heartbeat` only as a keepalive signal. Do not treat it as a data update.
6. If the SSE connection closes or errors out, reconnect automatically and fall back to a summary refetch if the client cannot guarantee event continuity.

Deployment note:

- The console SSE layer uses Redis Pub/Sub as the shared event source. Each `console-api` instance subscribes to the same channel, so every instance sees the same realtime events without sticky session.
- The local SSE hub is still in-process, but it is fed from Redis rather than from a single instance's write path.
- The realtime payload includes `originInstanceId` so the instance that already handled the local write path can ignore its own broadcast echo.
- `BATCH_CONSOLE_INSTANCE_ID` should be set per replica when possible so origin filtering remains stable across restarts.

### Job Definitions

- `GET /api/console/queries/job-definitions`
- `POST /api/console/job-definitions`
- `GET /api/console/job-definitions/{id}`
- `PUT /api/console/job-definitions/{id}`
- `PATCH /api/console/job-definitions/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- `PATCH /api/console/job-definitions/batch` — batch enable/disable up to 200; body: `BatchEnabledPatchRequest`
- `POST /api/console/job-definitions/{id}/copy`
- `POST /api/console/job-definitions/{id}/clone` — clone with field overrides via `JobDefinitionCopyRequest` body (jobName, workerGroup, queueCode, scheduleExpr, retryPolicy, etc.)
- All write operations require `ROLE_ADMIN`. Read operations allow `ROLE_AUDITOR`, `ROLE_CONFIG_ADMIN`, and `ROLE_TENANT_USER`.
- `copy` uses query params `tenantId` (required) and `newJobCode` (required); the cloned definition is created with `enabled=false`.
- `clone` accepts a JSON body with overridable fields; unset fields inherit from the source definition.

### Workflow Definitions

- `GET /api/console/queries/workflow-definitions`
- `POST /api/console/workflow-definitions`
- `GET /api/console/workflow-definitions/{id}`
- `PUT /api/console/workflow-definitions/{id}`
- `PATCH /api/console/workflow-definitions/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- `POST /api/console/workflow-definitions/{id}/validate`
- `GET /api/console/workflow-definitions/events`
- Create and update are transactional: definition, nodes, and edges are persisted or replaced atomically.
- `validate` runs Kahn topological sort and checks for cycles, START/END node presence, and reachability. Returns a validation result payload, not a simple boolean.

### Compatibility Aliases

- `GET /api/console/queries/pipeline-definitions`
- `GET /api/console/queries/pipeline-definitions/{id}`
- `GET /api/console/file-pipeline-observability`
- `GET /api/console/file-pipeline-observability/{id}`
- These are compatibility aliases for older callers. They return the same file pipeline list/detail payloads as `/api/console/queries/file-pipelines` and `/api/console/queries/file-pipelines/{id}`.
- Delete cascades to nodes and edges.
- `GET /api/console/workflow-definitions/events` subscribes to the workflow-definition realtime stream. It emits change signals for create, update, toggle, and delete operations using event types such as `workflow-definition-created`, `workflow-definition-updated`, `workflow-definition-toggled`, and `workflow-definition-deleted`.

### Pipeline Definitions

- `GET /api/console/pipeline-definitions`
- `POST /api/console/pipeline-definitions`
- `GET /api/console/pipeline-definitions/{id}`
- `PUT /api/console/pipeline-definitions/{id}`
- `POST /api/console/pipeline-definitions/{id}/toggle`
- `GET /api/console/pipeline-definitions/events`
- Create and update are transactional: definition and step list are persisted or replaced atomically.
- Detail response (`PipelineDefinitionDetailResponse`) includes the ordered step list.
- `GET /api/console/pipeline-definitions/events` is the domain-level realtime entry for pipeline editing screens. It subscribes to the same event hub used by the other realtime console streams, but keeps the route close to the pipeline-definition UX.

### Workers

- `POST /api/console/workers/{workerCode}/drain`
- `POST /api/console/workers/{workerCode}/force-offline`
- `POST /api/console/workers/{workerCode}/takeover`
- `GET /api/console/workers/{workerCode}/claimed-tasks`
- `GET /api/console/workers/events`
- `GET /api/console/workers/events` subscribes to worker registry realtime changes. It emits `worker-updated` signals after drain, force-offline, or takeover actions succeed.
- Worker list query is served by `GET /api/console/queries/workers`.

### Alerts

- `POST /api/console/alerts/{alertId}/ack`
- `POST /api/console/alerts/{alertId}/silence`
- `POST /api/console/alerts/{alertId}/close`
- `GET /api/console/alerts/events`
- `GET /api/console/alerts/events` subscribes to alert governance realtime changes. It emits `alert-updated` signals after ack, silence, or close actions succeed.
- Alert list query is served by `GET /api/console/queries/alerts`.

### Job Instances and Workflow Runs

- `GET /api/console/stream/job-instances/events`
- `GET /api/console/workflow-runs/events`
- `POST /api/console/jobs/trigger`
- `POST /api/console/jobs/compensations`
- `POST /api/console/jobs/compensate`
- `POST /api/console/jobs/rerun`
- `POST /api/console/jobs/dead-letters/replay`
- `POST /api/console/jobs/tasks/replay`
- `POST /api/console/jobs/partitions/replay`
- `POST /api/console/jobs/catch-up/approve`
- `POST /api/console/jobs/batch-days/{bizDate}/catchup`
- `GET /api/console/stream/job-instances/events` exposes the shared realtime hub for job-instance run-state pages. `GET /api/console/workflow-runs/events` is the domain-specific shortcut for the workflow-run screen.
- `job-instances` emits `job-instance-updated` signals and `workflow-runs` emits `workflow-run-updated` signals after job and workflow-run write actions succeed.

### Outbox

- `GET /api/console/stream/outbox-retries/events`
- `GET /api/console/stream/outbox-deliveries/events`
- `GET /api/console/stream/outbox-retries/events` subscribes to outbox retry activity and emits `outbox-retry-updated` signals.
- `GET /api/console/stream/outbox-deliveries/events` subscribes to outbox delivery activity and emits `outbox-delivery-updated` signals.

### Instances

- `POST /api/console/instances/{id}/cancel`
- `POST /api/console/instances/{id}/terminate`
- `POST /api/console/instances/partitions/{id}/cancel`
- `POST /api/console/instances/partitions/{id}/retry`
- `cancel` on an instance is allowed only for `CREATED`, `WAITING`, or `READY` states.
- `terminate` on an instance is allowed only for `RUNNING` state.
- `cancel` on a partition follows the same allowed states as instance cancel.
- `retry` on a partition is allowed only for `FAILED` state; `retryCount` is incremented.
- All operations use optimistic locking via `version`.

### Triggers

- `GET /api/console/ops/triggers`
- `POST /api/console/ops/triggers/{jobCode}/register`
- `POST /api/console/ops/triggers/{jobCode}/unregister`
- `POST /api/console/ops/triggers/{jobCode}/pause`
- `POST /api/console/ops/triggers/{jobCode}/resume`
- `register` loads the job definition from DB and registers it into Quartz; safe to call again to update an existing trigger.
- `unregister` removes the Quartz job entry; does not affect the job definition record.
- Trigger list response includes `status`, `previousFireTime`, and `nextFireTime` per job.

### Meta

- `GET /api/console/meta/enums`
- `GET /api/console/meta/queues`
- `GET /api/console/meta/calendars`
- `GET /api/console/meta/windows`
- `GET /api/console/meta/worker-groups`
- `enums` returns all platform enum dictionaries. Each key maps to an ordered `[{code, label}]` list:

  | Key | 说明 |
  |---|---|
  | `triggerType` | 触发类型 |
  | `scheduleType` | 调度类型（CRON / FIXED_RATE / MANUAL）|
  | `triggerMode` | 触发模式（SCHEDULED / API / MANUAL / EVENT / MIXED）|
  | `catchUpPolicy` | 补跑策略 |
  | `jobType` | 作业类型 |
  | `shardStrategy` | 分片策略 |
  | `retryPolicy` | 重试策略 |
  | `taskStatus` | 任务状态 |
  | `partitionStatus` | 分区状态 |
  | `instanceStatus` | 作业实例状态 |
  | `workflowType` | 工作流类型 |
  | `workflowNodeType` | 工作流节点类型 |
  | `edgeType` | 工作流边类型 |
  | `workflowRunStatus` | 工作流运行状态 |
  | `pipelineType` | 流水线类型 |
  | `channelType` | 文件通道类型 |
  | `authType` | 通道认证类型 |
  | `receiptPolicy` | 回执策略 |
  | `fileTemplateType` | 文件模板类型 |
  | `fileTemplateFormat` | 文件格式 |
  | `endStrategy` | 批量窗口结束策略 |
  | `outOfWindowAction` | 窗口外动作 |
  | `holidayStrategy` | 节假日顺延规则 |
  | `dayType` | 日历日类型 |
  | `queueType` | 资源队列类型 |
  | `priorityPolicy` | 优先级策略 |
  | `severity` | 告警级别 |
  | `alertStatus` | 告警状态（OPEN / ACKED / SUPPRESSED / CLOSED）|
  | `approvalStatus` | 审批状态 |
  | `approvalType` | 审批类型（CATCH_UP / COMPENSATION / DLQ_REPLAY / DOWNLOAD）|
  | `configStatus` | 配置发布状态 |
  | `workerStatus` | Worker 注册状态 |
  | `outboxPublishStatus` | Outbox 投递状态 |
  | `aiPromptCategory` | AI Prompt 分类 |
- `queues`, `calendars`, and `windows` return simplified lists (`code` + `name`) for use as dropdown options; all require `tenantId` query param.
- `worker-groups` returns deduplicated group codes from active worker registrations.
- All meta endpoints allow `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_CONFIG_ADMIN`, and `ROLE_TENANT_USER`.

### Queues

- `GET /api/console/queues`
- `POST /api/console/queues`
- `PUT /api/console/queues/{id}`
- `POST /api/console/queues/{id}/toggle`
- All write operations require `ROLE_ADMIN`.
- `queue_code` uniqueness is enforced on create.

### Batch Windows

- `GET /api/console/batch-windows`
- `POST /api/console/batch-windows`
- `PUT /api/console/batch-windows/{id}`
- `POST /api/console/batch-windows/{id}/toggle`
- All write operations require `ROLE_ADMIN`.
- `window_code` uniqueness is enforced on create.
- Window definition includes start/end time, cross-day policy, and out-of-window action.

### Calendars

- `GET /api/console/calendars`
- `POST /api/console/calendars`
- `PUT /api/console/calendars/{id}`
- `POST /api/console/calendars/{id}/toggle`
- `GET /api/console/calendars/{id}/holidays`
- `POST /api/console/calendars/{id}/holidays`
- `PUT /api/console/calendars/{id}/holidays/{holidayId}`
- `DELETE /api/console/calendars/{id}/holidays/{holidayId}`
- `calendar_code` uniqueness is enforced on create.
- Holiday create supports batch import (multiple entries in one request body).
- Holiday operations validate calendar tenant ownership before write.

### Scheduler

- `GET /api/console/scheduler/status`
- `POST /api/console/scheduler/pause-all`
- `POST /api/console/scheduler/resume-all`
- `GET /api/console/scheduler/snapshot`
- `GET /api/console/scheduler/snapshot/history`
- `status` returns one of `STARTED`, `PAUSED`, `STANDBY`, or `SHUTDOWN`.
- `pause-all` / `resume-all` apply globally to all Quartz triggers; use with caution in production.
- Scheduler snapshot responses keep the stable display slices `policies / queues / workers`; the frontend should treat those lists as the primary render contract.

### Quota Policies

- `GET /api/console/quota-policies`
- `POST /api/console/quota-policies`
- `PUT /api/console/quota-policies/{id}`
- `POST /api/console/quota-policies/{id}/toggle`
- All write operations require `ROLE_ADMIN`.
- `policy_code` uniqueness is enforced on create.
- Policy definition includes concurrent cap, QPS, fair-share configuration, burst limit, and sliding window hours.

### Workflow Runs

- `POST /api/console/workflow-runs/{id}/cancel`
- `POST /api/console/workflow-runs/{id}/terminate`
- `POST /api/console/workflow-runs/{id}/skip-node`
- `cancel` is allowed for `CREATED` or `RUNNING` states → transitions to `TERMINATED`.
- `terminate` is allowed for `RUNNING` state → transitions to `TERMINATED`.
- `skip-node` requires `nodeCode` query param and is allowed only for `FAILED` nodes → transitions to `SKIPPED`.

### Dashboard

- `GET /api/console/dashboard/job-stats`
- `GET /api/console/dashboard/trigger-stats`
- `GET /api/console/dashboard/worker-load`
- `GET /api/console/dashboard/alert-trend`
- `GET /api/console/dashboard/sla-compliance`
- `GET /api/console/dashboard/sla-report` — per-job SLA breakdown: avg/max duration, success/failure/breach counts
- `GET /api/console/dashboard/execution-progress` — execution progress by `jobCode` + `bizDate`, returns instance partition completion percentage
- `GET /api/console/dashboard/tenant-usage` — tenant resource usage: job/workflow/channel/template definition counts + recent instance/file counts (`days` defaults to `30`)
- All endpoints require `tenantId` query param. `days` defaults to `7` where applicable (except `tenant-usage` which defaults to `30`).
- `job-stats`: instance status distribution + daily execution trend.
- `trigger-stats`: trigger type distribution + daily trend.
- `worker-load`: worker status/group distribution + active partition breakdown.
- `alert-trend`: alert severity distribution + daily trend.
- `sla-compliance`: violation/on-time counts + average duration + daily trend.
- Allow `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_CONFIG_ADMIN`, and `ROLE_TENANT_USER`.

### File Channels

- `GET /api/console/file-channels`
- `POST /api/console/file-channels`
- `GET /api/console/file-channels/{id}`
- `PUT /api/console/file-channels/{id}`
- `PATCH /api/console/file-channels/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- Read requires `ROLE_ADMIN`, `ROLE_CONFIG_ADMIN`, or `ROLE_AUDITOR`. Write requires `ROLE_CONFIG_ADMIN` or above. Delete requires `ROLE_ADMIN`.

### File Templates

- `GET /api/console/file-templates`
- `POST /api/console/file-templates`
- `GET /api/console/file-templates/{id}`
- `PUT /api/console/file-templates/{id}`
- `PATCH /api/console/file-templates/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- Same permission rules as File Channels.

### Jobs

- `POST /api/console/jobs/trigger` — supports `dryRun: true` in request body for sandbox validation (validates tenant, jobCode, bizDate, triggerType, enabled status without executing)
- `POST /api/console/jobs/batch-trigger` — batch trigger up to 50 jobs in one request; accepts `List<TriggerRequest>`, requires `Idempotency-Key`
- `POST /api/console/jobs/compensations`
- `POST /api/console/jobs/compensate`
- `POST /api/console/jobs/rerun`
- `POST /api/console/jobs/dead-letters/replay`
- `POST /api/console/jobs/tasks/replay`
- `POST /api/console/jobs/partitions/replay`
- `POST /api/console/jobs/catch-up/approve`
- `POST /api/console/jobs/batch-days/{bizDate}/catchup`

### Approvals

- `POST /api/console/approvals/{approvalNo}/approve`
- `POST /api/console/approvals/{approvalNo}/reject`
- `POST /api/console/approvals/batch-approve`
- `POST /api/console/approvals/batch-reject`
- Approval query views should use `ConsoleApprovalCommandResponse` and `ConsoleBatchApprovalResultResponse`, not raw entities.

### Config

- `GET /api/console/config/releases`
- `POST /api/console/config/releases`
- `GET /api/console/config/releases/{releaseId}`
- `POST /api/console/config/releases/{releaseId}/publish`
- `POST /api/console/config/releases/{releaseId}/gray`
- `POST /api/console/config/releases/{releaseId}/rollback`
- `GET /api/console/config/secrets`
- `GET /api/console/config/secrets/{secretVersionId}`
- `POST /api/console/config/secrets/rotate`
- `GET /api/console/config/dependencies` — query config item dependencies; params: `tenantId`, `configType` (QUEUE / CALENDAR / WINDOW / WORKER_GROUP), `configCode`
- `GET /api/console/config/releases/diff` — diff two release versions; params: `tenantId`, `releaseIdA`, `releaseIdB`
- `GET /api/console/config/change-logs`
- `GET /api/console/config/file-templates/excel/template`
- `GET /api/console/config/file-templates/excel/export`
- `POST /api/console/config/file-templates/excel/upload`
- `GET /api/console/config/file-templates/excel/preview/{uploadToken}`
- `GET /api/console/config/file-templates/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/file-templates/excel/apply/{uploadToken}`
- `GET /api/console/config/file-channels/excel/template`
- `GET /api/console/config/file-channels/excel/export`
- `POST /api/console/config/file-channels/excel/upload`
- `GET /api/console/config/file-channels/excel/preview/{uploadToken}`
- `GET /api/console/config/file-channels/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/file-channels/excel/apply/{uploadToken}`
- `GET /api/console/config/workflows/excel/template`
- `GET /api/console/config/workflows/excel/export`
- `POST /api/console/config/workflows/excel/upload`
- `GET /api/console/config/workflows/excel/preview/{uploadToken}`
- `GET /api/console/config/workflows/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/workflows/excel/apply/{uploadToken}`
- `GET /api/console/config/job-definitions/excel/template`
- `GET /api/console/config/job-definitions/excel/export`
- `POST /api/console/config/job-definitions/excel/upload`
- `GET /api/console/config/job-definitions/excel/preview/{uploadToken}`
- `GET /api/console/config/job-definitions/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/job-definitions/excel/apply/{uploadToken}`
- `GET /api/console/config/alert-routings/excel/template`
- `GET /api/console/config/alert-routings/excel/export`
- `POST /api/console/config/alert-routings/excel/upload`
- `GET /api/console/config/alert-routings/excel/preview/{uploadToken}`
- `GET /api/console/config/alert-routings/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/alert-routings/excel/apply/{uploadToken}`
- `GET /api/console/config/batch-windows/excel/template`
- `GET /api/console/config/batch-windows/excel/export`
- `POST /api/console/config/batch-windows/excel/upload`
- `GET /api/console/config/batch-windows/excel/preview/{uploadToken}`
- `GET /api/console/config/batch-windows/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/batch-windows/excel/apply/{uploadToken}`
- `GET /api/console/config/business-calendars/excel/template`
- `GET /api/console/config/business-calendars/excel/export`
- `POST /api/console/config/business-calendars/excel/upload`
- `GET /api/console/config/business-calendars/excel/preview/{uploadToken}`
- `GET /api/console/config/business-calendars/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/business-calendars/excel/apply/{uploadToken}`
- `GET /api/console/config/pipeline-definitions/excel/template`
- `GET /api/console/config/pipeline-definitions/excel/export`
- `POST /api/console/config/pipeline-definitions/excel/upload`
- `GET /api/console/config/pipeline-definitions/excel/preview/{uploadToken}`
- `GET /api/console/config/pipeline-definitions/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/pipeline-definitions/excel/apply/{uploadToken}`
- `GET /api/console/config/resource-queues/excel/template`
- `GET /api/console/config/resource-queues/excel/export`
- `POST /api/console/config/resource-queues/excel/upload`
- `GET /api/console/config/resource-queues/excel/preview/{uploadToken}`
- `GET /api/console/config/resource-queues/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/resource-queues/excel/apply/{uploadToken}`
- `GET /api/console/config/quota-policies/excel/template`
- `GET /api/console/config/quota-policies/excel/export`
- `POST /api/console/config/quota-policies/excel/upload`
- `GET /api/console/config/quota-policies/excel/preview/{uploadToken}`
- `GET /api/console/config/quota-policies/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/quota-policies/excel/apply/{uploadToken}`
- Config list views should use typed response DTOs for releases, secrets, and change logs.
- Excel maintenance currently covers 10 editable config domains: `file template`, `file channel`, `workflow`, `job definition`, `alert routing`, `batch window`, `business calendar`, `pipeline definition`, `resource queue`, and `tenant quota policy`.
- Each editable Excel domain follows the same HTTP shape: `template -> export -> upload -> preview -> preview workbook -> apply`.
- `GET /preview/{uploadToken}/workbook` downloads a corrected workbook family that includes populated `VALIDATION` rows and cell comments pointing at the failing cells.
- The preview workbook is intentionally re-importable: comments and extra sheets must not break a subsequent `upload`.
- Excel maintenance for `file template config` and `file channel config` follows the dedicated adapter flow with single-sheet maintenance semantics.
- Excel maintenance for `resource queue`, `tenant quota policy`, `batch window`, and `alert routing` follows the same single-sheet maintenance flow.
- Excel maintenance for `workflow definition / node / edge`, `business calendar / holiday`, and `pipeline definition / step definition` follows the same dedicated adapter flow but keeps multiple sheets aligned by shared keys.
- Excel maintenance for the safe subset of `job definition` follows the same dedicated adapter flow, but only allows white-listed mutable columns and update-only apply semantics.
- Export workbooks for editable Excel flows must stay as recoverable templates: data sheet first, then README / DICT / VALIDATION sheets, so users can edit and re-upload the same file family.
- Preview workbook generation is read-only: it reflects validation findings from the current upload session and does not write configuration data.
- `GET /api/console/reports/excel/config-releases`
- `GET /api/console/reports/excel/secrets`
- `GET /api/console/reports/excel/change-logs`
- `GET /api/console/reports/excel/audits`
- `GET /api/console/reports/excel/scheduler-snapshot`
- `GET /api/console/reports/excel/scheduler-history`
- `GET /api/console/reports/excel/workers`
- `GET /api/console/reports/excel/outbox-retries`
- `GET /api/console/reports/excel/outbox-deliveries`
- Report Excel exports are export-only snapshots or logs. They do not accept upload / preview / apply flows.

### Tenant Management

- `GET /api/console/tenants` — list tenants (keyword, status filter, paginated)
- `GET /api/console/tenants/{tenantId}` — tenant detail
- `POST /api/console/tenants` — create tenant
- `PUT /api/console/tenants/{tenantId}` — update tenant name / description
- `POST /api/console/tenants/{tenantId}/suspend` — suspend tenant
- `POST /api/console/tenants/{tenantId}/activate` — reactivate tenant
- Tenant is the platform-level isolation unit. Must exist in `batch.tenant` before any config can be pushed.
- `tenantId` format: `^[a-z0-9][a-z0-9\-]*[a-z0-9]$` (lowercase, alphanumeric, hyphens).
- `status` values: `ACTIVE`, `SUSPENDED`.
- `GET` list and detail require `ROLE_ADMIN` or `ROLE_CONFIG_ADMIN`. All write operations require `ROLE_ADMIN`.
- Response fields: `id`, `tenantId`, `tenantName`, `status`, `description`, `createdBy`, `createdAt`, `updatedAt`.

### User Account Management

- `GET /api/console/users` — list accounts (tenantId filter, keyword search by username/displayName, paginated)
- `GET /api/console/users/{id}` — account detail
- `PUT /api/console/users/{id}` — update displayName and authoritiesCsv
- `POST /api/console/users/{id}/reset-password` — reset password (Argon2id hash stored, raw password never persisted)
- `POST /api/console/users/{id}/enable` — enable account
- `POST /api/console/users/{id}/disable` — disable account
- All operations require `ROLE_ADMIN`.
- `username` is globally unique (case-insensitive). Format: alphanumeric + `.` `_` `-`, min 2 chars.
- `authoritiesCsv` is a comma-separated list of role names (e.g. `ROLE_CONFIG_ADMIN,ROLE_AUDITOR`); default is `ROLE_USER`.
- Password minimum 8 characters; only the Argon2id hash is stored, raw password is discarded after hashing.
- Response fields: `id`, `tenantId`, `username`, `displayName`, `authoritiesCsv`, `enabled`, `createdAt`, `updatedAt`. `passwordHash` is never exposed.
- Create request fields: `tenantId` (required), `username` (required), `displayName`, `password` (required, min 8), `authoritiesCsv`.
- The seeded default accounts (see Ops section) are `admin`, `auditor`, `config-admin`, `tenant-user` under `default-tenant`.

### Tenant Config Init

- `POST /api/console/config/tenant-init`
- Batch-initializes or updates configuration for multiple tenants in one request.
- `targetTenantIds` is resolved from `batch.tenant WHERE status = 'ACTIVE'` when omitted (broadcast to all active tenants).
- Supports two modes: `SKIP_EXISTING` (default, create missing only) and `UPSERT` (create or update).
- Supports `dryRun` mode: when `true`, performs read and validation only without executing writes.
- Covers all 10 config types: job definitions, workflow definitions, pipeline definitions, file channels, file templates, resource queues, batch windows, business calendars, quota policies, alert routings.
- Response includes `batchOperationId` for audit correlation, per-tenant results with per-item details (code, action, errorMessage) for each of the 10 config types.
- Requires `ROLE_ADMIN`.
- Requires `Idempotency-Key` header.

### Tenant Config Copy

- `POST /api/console/config/tenant-copy`
- Reads configuration from a source tenant and pushes it to one or more target tenants.
- Request body: `sourceTenantId`, `targetTenantIds` (max 50), optional `configTypes` (subset of `JOB_DEFINITION`, `WORKFLOW_DEFINITION`, `PIPELINE_DEFINITION`, `FILE_CHANNEL`, `FILE_TEMPLATE`, `RESOURCE_QUEUE`, `BATCH_WINDOW`, `BUSINESS_CALENDAR`, `QUOTA_POLICY`, `ALERT_ROUTING`; empty means all 10), `mode` (default `SKIP_EXISTING`), `dryRun`.
- Internally reads source tenant's configs and delegates to the tenant-init logic.
- Response format is identical to `tenant-init`.
- Requires `ROLE_ADMIN`.
- Requires `Idempotency-Key` header.

### Workers

- `POST /api/console/workers/{workerCode}/drain`
- `POST /api/console/workers/{workerCode}/takeover`
- `POST /api/console/workers/{workerCode}/force-offline`
- `GET /api/console/workers/{workerCode}/claimed-tasks`
- `POST /api/console/workers/{workerCode}/warmup`
- `takeover` is the explicit manual handoff path: it requeues in-flight tasks and marks the worker decommissioned immediately.
- `force-offline` keeps the stronger ops semantics and should be treated as an emergency offlining command.

### Files

- `POST /api/console/files/archive`
- `DELETE /api/console/files/{fileId}` — delete a file record
- `POST /api/console/files/redispatch`
- `POST /api/console/files/presign-download`
- `POST /api/console/files/arrival-groups/action`
- `POST /api/console/files/presign-upload`
- `POST /api/console/files/{fileId}/confirm-arrival`
- `GET /api/console/files/{fileId}/download`
- `GET /api/console/files/{fileId}/errors/export` — export file error records as CSV (param: `tenantId`, optional `errorStage`)
- File operation endpoints use `ConsoleFileOperationResponse`. `POST /api/console/files/presign-download` uses `ConsolePresignDownloadResponse`, where `approvalNo` and `downloadUrl` are mutually exclusive and one side may be `null` depending on whether the request goes through approval submission or direct presign execution.
- Download success responses are raw file bytes with `Content-Disposition: attachment`; validation or state errors still return the normal JSON error envelope via the global exception handler.

### Alerts

- `GET /api/console/queries/alerts`
- `POST /api/console/alerts/{alertId}/ack`
- `POST /api/console/alerts/{alertId}/silence`
- `POST /api/console/alerts/{alertId}/close`
- `ack` is the UI-facing confirm action. It maps to backend alert status `ACKED`.
- `silence` maps to backend alert status `SUPPRESSED`.
- `close` maps to backend alert status `CLOSED`.

### Scheduler

- `GET /api/console/scheduler/snapshot`
- `GET /api/console/scheduler/snapshot/history`
- Scheduler snapshot responses keep the stable display slices `policies / queues / workers`; the frontend should treat those lists as the primary render contract.

### AI

- `POST /api/console/ai/chat`

### System Parameters

- `GET /api/console/system-parameters` — list all parameters for tenant
- `GET /api/console/system-parameters/value` — get single parameter value by `key`
- `PUT /api/console/system-parameters` — upsert parameter (body: `key`, `value`, `description`)
- `DELETE /api/console/system-parameters` — delete parameter by `key`
- All endpoints require `ROLE_ADMIN`.
- Parameters are cached in Redis (`sys-param:{tenantId}:{key}`, TTL 30min); writes invalidate cache immediately.

### Webhooks

- `GET /api/console/webhooks` — list webhook subscriptions for tenant
- `GET /api/console/webhooks/{id}` — subscription detail
- `POST /api/console/webhooks` — create subscription (body: `name`, `callbackUrl`, `eventTypes[]`, `secret`, `enabled`)
- `PUT /api/console/webhooks/{id}` — update subscription
- `DELETE /api/console/webhooks/{id}` — delete subscription
- `GET /api/console/webhooks/delivery-logs` — query delivery log history (`subscriptionId` optional, `limit` default 20)
- Webhook delivery uses HMAC-SHA256 signature in `X-Webhook-Signature` header; payload is JSON with `eventType`, `tenantId`, `payload`, `timestamp`.
- Delivery retries up to 3 times with exponential backoff (2s, 4s, 8s).
- Permissions: `ROLE_ADMIN`, `ROLE_CONFIG_ADMIN`, `ROLE_TENANT_USER`.

### Resource Tags

- `GET /api/console/tags` — list tags for a specific resource (`resourceType`, `resourceCode` required)
- `GET /api/console/tags/search` — search resources by tag (`tagKey` required, `tagValue` optional)
- `GET /api/console/tags/keys` — list all distinct tag keys for tenant
- `POST /api/console/tags` — upsert tag (body: `resourceType`, `resourceCode`, `tagKey`, `tagValue`)
- `DELETE /api/console/tags` — delete single tag (`resourceType`, `resourceCode`, `tagKey` required)
- `DELETE /api/console/tags/all` — delete all tags for a resource
- Supported `resourceType` values: `JOB`, `WORKFLOW`, `FILE_CHANNEL`, `FILE_TEMPLATE`.
- Permissions: `ROLE_ADMIN`, `ROLE_CONFIG_ADMIN`.

### API Keys

- `GET /api/console/api-keys` — list all API keys for tenant (key hash only, no raw key)
- `GET /api/console/api-keys/{id}` — API key detail
- `POST /api/console/api-keys` — create API key (body: `keyName`, `scopes`, `expiresAt`); returns raw key **once only**
- `DELETE /api/console/api-keys/{id}` — revoke API key (requires `ROLE_ADMIN`)
- Raw key is a `bk_`-prefixed Base64 token; only the SHA-256 hash and 8-char prefix are stored.
- List/detail/create: `ROLE_ADMIN`, `ROLE_TENANT_USER`. Revoke: `ROLE_ADMIN` only.

### Kafka Lag

- `GET /api/console/ops/kafka-lag` — query Kafka consumer group lag for batch-related topics

### Governance

- `GET /api/console/ops/governance` — list circuit breaker / rate limit parameters
- `POST /api/console/ops/governance` — update governance parameter
- `POST /api/console/ops/governance/reset` — reset to default

### Outbox Ops

- `GET /api/console/ops/outbox/stats` — outbox event statistics (pending / failed / delivered counts)
- `POST /api/console/ops/outbox/cleanup` — clean up delivered/expired outbox events
- `POST /api/console/ops/outbox/republish` — republish failed outbox events

### Archive Policies

- `GET /api/console/ops/archive-policies` — list archive/cleanup policies
- `PUT /api/console/ops/archive-policies` — upsert archive/cleanup policy

### Cluster Diagnostic

- `GET /api/console/ops/cluster-diagnostic` — full cluster health check
- `GET /api/console/ops/cluster-diagnostic/shedlock` — ShedLock lease status
- `GET /api/console/ops/cluster-diagnostic/workers` — worker consistency
- `GET /api/console/ops/cluster-diagnostic/outbox` — outbox health

### Tenant Self-Service

- `GET /api/console/tenants/quota` — tenant quota policies
- `GET /api/console/tenants/usage` — tenant usage metrics
- `POST /api/console/tenants/quota/request` — request quota change (stored as system parameter; returns request key)

Request body:

```json
{
  "field": "maxConcurrentJobs",
  "requestedValue": 200,
  "reason": "业务增长，需要提升并发额度"
}
```

Notes:

- `field` should match quota policy code returned by `GET /api/console/tenants/quota` (e.g. `items[].policyCode`)
- `requestedValue` must be a positive integer

### Self-Service Jobs

- `POST /api/console/self-service/jobs/rerun-request` — submit rerun via approval
- `POST /api/console/self-service/jobs/compensation-request` — submit compensation via approval

### Event Catalog

- `GET /api/console/event-catalog/event-types` — subscribable event types
- `GET /api/console/event-catalog/topics` — Kafka topic directory

### Queries

- `GET /api/console/queries/audits`
- `GET /api/console/queries/execution-logs`
- `GET /api/console/queries/alerts`
- `GET /api/console/queries/approvals`
- `GET /api/console/queries/files`
- `GET /api/console/queries/job-definitions`
- `GET /api/console/queries/outbox-retries`
- `GET /api/console/queries/outbox-deliveries`
- `GET /api/console/queries/file-pipelines`
- `GET /api/console/queries/file-pipeline-steps`
- `GET /api/console/queries/file-dispatches`
- `GET /api/console/queries/channel-receipts`
- `GET /api/console/queries/file-channels`
- `GET /api/console/queries/file-arrival-groups`
- `GET /api/console/queries/file-errors`
- `GET /api/console/queries/file-templates`
- `GET /api/console/queries/instances` — supports `sortBy=duration` and `minDurationSeconds` for slow task diagnosis
- `GET /api/console/queries/instances/{id}`
- `GET /api/console/queries/instances/batch-status` — batch query instance status by `instanceNos[]`
- `GET /api/console/queries/job-step-instances`
- `GET /api/console/queries/job-step-instances/{id}`
- `GET /api/console/queries/partitions` — 按作业实例分页查询 `job_partition`（分区粒度；`job-step-instances` 是步骤粒度）
- `GET /api/console/queries/workflow-definitions`
- `GET /api/console/queries/workflow-nodes`
- `GET /api/console/queries/workflow-edges`
- `GET /api/console/queries/workflow-runs`
- `GET /api/console/queries/workflow-runs/{id}`
- `GET /api/console/queries/workflow-node-runs`
- `GET /api/console/queries/workflow-node-runs/{id}`
- `GET /api/console/queries/workflow-topology`
- `GET /api/console/queries/ai-audits`
- `GET /api/console/queries/dead-letters`
- `GET /api/console/queries/retries`
- `GET /api/console/queries/catch-up-approvals`
- `GET /api/console/queries/batch-days`
- `GET /api/console/queries/batch-days/{bizDate}/window`
- `GET /api/console/queries/workers`
- `GET /api/console/queries/file-channels/{channelCode}`
- `GET /api/console/queries/file-templates/{templateCode}`
- `GET /api/console/queries/files/{id}`
- `GET /api/console/queries/file-pipelines/{id}`
- Query endpoints must return typed list DTOs or documented view objects. Avoid raw entity lists and anonymous maps in new query APIs.
- `execution-logs` is a UI alias for `audits` and uses the same response shape.
- `channel-receipts` is a receipt-focused alias of `file-dispatches`; it uses the same request fields and response DTO, but gives the frontend a stable semantic entrypoint for receipt tracking.
- `workflow-topology` returns `ConsoleWorkflowTopologyResponse` with `workflowDefinition`, `nodes`, `edges`, `workflowRuns`, and `nodeRuns`; the frontend should use those five fields directly instead of reconstructing a generic object map.
- All paginated query endpoints accept `tenantId`, `pageNo`, and `pageSize`. In addition, the following endpoints support server-side filtering:

| Endpoint | Filter Parameters | Match |
|----------|-------------------|-------|
| `/query/audits` | `operationType`, `operationResult` (exact); `operatorId`, `traceId` (partial); `fileId` (exact); `startTime`/`endTime` (range) | mixed |
| `/query/alerts` | `severity`, `status`, `alertType` (exact) | exact |
| `/query/files` | `fileStatus`, `bizType` (exact); `fileName` (partial); `traceId`, `fileId` (exact); `fromTime`/`toTime` (range) | mixed |
| `/query/instances` | `jobCode` (partial); `instanceStatus`, `instanceNo`, `bizDate` (exact); `traceId` (partial); `startDate`/`endDate` (range); `sortBy` (`id`/`duration`); `minDurationSeconds` (threshold filter) | mixed |
| `/query/job-definitions` | `jobCode`, `jobName`, `workerGroup`, `queueCode` (partial); `jobType`, `scheduleType`, `enabled` (exact) | mixed |
| `/query/job-step-instances` | `jobInstanceId`, `jobPartitionId`, `stepCode`, `stepStatus` (exact) | exact |
| `/query/partitions` | `jobInstanceId`, `partitionStatus` (exact) | exact |
| `/query/workflow-definitions` | `workflowCode` (partial) | partial |
| `/query/workflow-nodes` | `workflowDefinitionId` (exact); `workflowCode`, `nodeCode` (exact); `nodeType`, `enabled` (exact) | exact |
| `/query/workflow-edges` | `workflowDefinitionId` (exact); `workflowCode`, `fromNodeCode`, `toNodeCode`, `edgeType`, `enabled` (exact) | exact |
| `/query/workflow-runs` | `workflowDefinitionId`, `relatedJobInstanceId`, `runStatus`, `currentNodeCode`, `traceId` (exact) | exact |
| `/query/workflow-node-runs` | `workflowRunId`, `nodeCode`, `nodeStatus` (exact) | exact |
| `/query/outbox-retries` | `retryStatus`, `eventKey` (exact) | exact |
| `/query/outbox-deliveries` | `deliveryStatus`, `eventType`, `eventKey` (exact) | exact |

### Streaming

- Streaming endpoints use `text/event-stream` and return raw SSE frames instead of the `CommonResponse` JSON envelope.
- Browser `EventSource` clients may authenticate with `Authorization: Bearer <jwt>` or `?token=<jwt>` when custom headers are unavailable.
- The realtime stream currently emits `ready`, `heartbeat`, and domain event names such as `pipeline-definition-created`, `pipeline-definition-updated`, and `pipeline-definition-toggled`.

### Notification Subscription Management

- `GET /api/console/notifications/channels` — list notification channels
- `GET /api/console/notifications/channels/{channelCode}` — get channel detail
- `POST /api/console/notifications/channels` — create notification channel (EMAIL / DINGTALK / WECOM / WEBHOOK / SMS)
- `PUT /api/console/notifications/channels/{channelCode}` — update channel
- `DELETE /api/console/notifications/channels/{channelCode}` — delete channel
- `POST /api/console/notifications/channels/{channelCode}/test` — send test notification
- `GET /api/console/notifications/rules` — list subscription rules
- `GET /api/console/notifications/rules/{ruleId}` — get rule detail
- `POST /api/console/notifications/rules` — create subscription rule (links channel + event types + filters)
- `PUT /api/console/notifications/rules/{ruleId}` — update rule
- `DELETE /api/console/notifications/rules/{ruleId}` — delete rule
- `GET /api/console/notifications/delivery-logs` — list delivery logs (param: `tenantId`, `limit`)
- Channel CRUD and rule CRUD require `ROLE_ADMIN`, `ROLE_CONFIG_ADMIN`, or `ROLE_TENANT_USER`.
- Delivery log view additionally allows `ROLE_AUDITOR`.
- Channel delete requires `ROLE_ADMIN` or `ROLE_TENANT_USER`.
- Database tables: `notification_channel`, `subscription_rule`, `notification_delivery_log` (V49 migration).

### Config Approval Flow

- `POST /api/console/config/releases/{releaseId}/submit-approval` — submit release for approval (changes status to PENDING_APPROVAL)
- `GET /api/console/config/releases/{releaseId}/approval` — get approval detail for a release
- `POST /api/console/config/approvals/{approvalId}/approve` — approve (changes release to PUBLISHED)
- `POST /api/console/config/approvals/{approvalId}/reject` — reject (changes release back to DRAFT)
- Submit, approve, and reject require `ROLE_ADMIN`.
- Approval detail view allows `ROLE_ADMIN`, `ROLE_AUDITOR`, and `ROLE_CONFIG_ADMIN`.
- State machine: `DRAFT → PENDING_APPROVAL → PUBLISHED` (approve) or `DRAFT → PENDING_APPROVAL → DRAFT` (reject).
- Database table: `config_approval` (V49 migration). Enum `ConfigLifecycleStatus.PENDING_APPROVAL` added.

### Cross-Environment Config Sync

- `POST /api/console/config/sync/export` — export config bundle from source tenant/environment
- `POST /api/console/config/sync/preview` — preview import impact without executing
- `POST /api/console/config/sync/import` — import config bundle into target tenants
- `GET /api/console/config/sync/logs` — list sync operation logs (param: `tenantId`, `limit`)
- All endpoints require `ROLE_ADMIN`.
- Export returns a `ConfigSyncBundlePayload` containing job definitions, workflow definitions, pipeline definitions, file channels, and file templates.
- Import delegates to `ConsoleTenantConfigInitApplicationService.batchInit()` and records sync log with RUNNING → SUCCESS / PARTIAL_FAILED / FAILED status.
- Database table: `config_sync_log` (V49 migration).

### Alert Routing / Notification Policy Status

- Alert routing Excel maintenance is available via the standard upload → preview → apply flow.
- Notification subscription management is now fully implemented (see above).

## Trigger API

- `POST /api/console/jobs/trigger`

Request headers:

```http
Idempotency-Key: tenant-a:job-daily-settlement:2026-03-21:req-001
X-Request-Id: req-001
X-Trace-Id: trace-001
X-Operator-Id: admin
```

Request body:

```json
{
  "tenantId": "tenant-a",
  "jobCode": "daily-settlement",
  "bizDate": "2026-03-21",
  "triggerType": "MANUAL",
  "payload": "{\"source\":\"console\"}",
  "dryRun": false
}
```

When `dryRun` is `true`, the server validates tenant, jobCode existence, enabled status, bizDate format, and triggerType without creating an actual trigger request. `Idempotency-Key` header is not required for dry-run calls.

## Compensate API

- `POST /api/console/jobs/compensate`

Request body:

```json
{
  "tenantId": "tenant-a",
  "jobCode": "daily-settlement",
  "bizDate": "2026-03-21",
  "targetInstanceNo": "INST-20260321-0001",
  "reason": "manual compensate"
}
```

## Rerun API

- `POST /api/console/jobs/rerun`

Request body:

```json
{
  "tenantId": "tenant-a",
  "jobCode": "daily-settlement",
  "bizDate": "2026-03-21",
  "targetInstanceNo": "INST-20260321-0001",
  "reason": "rerun failed partitions"
}
```

### Governance

- `GET /api/console/ops/governance` — list circuit breaker / rate limit parameters (with defaults)
- `POST /api/console/ops/governance` — update governance parameter
- `POST /api/console/ops/governance/reset` — reset governance parameter to default

### Tenant Self-Service

- `GET /api/console/tenants/quota` — query tenant quota policies
- `GET /api/console/tenants/usage` — query tenant usage metrics
- `POST /api/console/tenants/quota/request` — request quota change (stored as system parameter; returns request key)

### File Upload & Arrival Confirmation

- `POST /api/console/files/presign-upload` — get pre-signed upload URL (params: `tenantId`, `channelCode`, `fileName`)
- `POST /api/console/files/{fileId}/confirm-arrival` — confirm file arrival (param: `tenantId`)

### Archive & Cleanup Policies

- `GET /api/console/ops/archive-policies` — list archive/cleanup policies
- `PUT /api/console/ops/archive-policies` — upsert archive/cleanup policy

### Cluster Diagnostic

- `GET /api/console/ops/cluster-diagnostic` — full cluster health diagnostic
- `GET /api/console/ops/cluster-diagnostic/shedlock` — ShedLock lease status
- `GET /api/console/ops/cluster-diagnostic/workers` — worker registry consistency
- `GET /api/console/ops/cluster-diagnostic/outbox` — outbox health

### Self-Service Rerun / Compensation

- `POST /api/console/self-service/jobs/rerun-request` — submit rerun request (creates approval workflow)
- `POST /api/console/self-service/jobs/compensation-request` — submit compensation request (creates approval workflow)

### Event Catalog

- `GET /api/console/event-catalog/event-types` — list subscribable event types
- `GET /api/console/event-catalog/topics` — list Kafka topics

### API Versioning

All console APIs support versioned paths via URL prefix:

- `/api/v1/console/**` → rewritten to `/api/console/**`
- `X-API-Version` response header indicates current version (`1`)
- `Accept-Version` request header is recognized for future negotiation

### Worker Warmup

- `POST /api/console/workers/{workerCode}/warmup` — trigger worker warmup (param: `tenantId`)

### Frontend Telemetry

- `POST /api/console/telemetry/events` — receive frontend telemetry events in batch
- Request body structure:

```json
{
  "app": "batch-console",
  "userId": "admin",
  "sessionId": "sess-abc123",
  "events": [
    {
      "type": "error",
      "name": "TypeError: Cannot read property 'id' of undefined",
      "ts": "2026-04-10T12:00:00.000Z",
      "page": "/jobs",
      "props": { "stack": "at JobList.vue:42", "componentName": "JobList" }
    }
  ]
}
```

- `type`: event category — `route` / `click` / `api` / `error`
- `name`: event name or description
- `ts`: ISO 8601 timestamp string
- `props`: arbitrary key-value object, backend serializes to JSON for logging
- Outer `app` / `userId` / `sessionId` provide session context without repeating per event
- Max 50 events per batch
- Backend logs each event via slf4j with MDC fields (`frontendApp`, `frontendUserId`, `frontendEventType`, `frontendPage`), then Promtail picks up into Loki
- `error` type events are logged at ERROR level; all others at INFO level
- Requires JWT authentication (any authenticated console user)
- Frontend should batch non-critical events (click, route) and report errors immediately
- See `docs/design/logging-architecture.md` for full logging pipeline design

## File Download API

- `GET /api/console/files/{fileId}/download`
- Query params: `tenantId` (required), `approvalId` (optional).
- Response is **raw file bytes** (`Content-Disposition: attachment`, MIME from metadata or `application/octet-stream`), **not** the `CommonResponse` JSON envelope.
- When the file’s template enforces download approval, `approvalId` must reference an **APPROVED** (or **EXECUTED**) approval; omit only when testing-open or policy does not require approval.

## Error Code Baseline

- `INVALID_ARGUMENT`
- `VALIDATION_ERROR`
- `MISSING_IDEMPOTENCY_KEY`
- `NOT_FOUND`
- `CONFLICT`
- `STATE_CONFLICT`
- `UNAUTHORIZED`
- `FORBIDDEN`
- `BUSINESS_ERROR`
- `NOT_IMPLEMENTED`
- `SYSTEM_ERROR`
