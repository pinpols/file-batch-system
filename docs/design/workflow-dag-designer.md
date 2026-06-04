# Workflow DAG Designer — BE Spike 契约

> 2026-06-04 落地。配合 FE PR #364 画布编辑器,本文档收 BE 侧契约与边界。完整 FE 交互 / 画布交互见 PR #364 描述。

## 范围边界(scope)

**BE Spike 阶段做的**(本 PR):

- 全量替换端点:画布 Save 一次性提交 (definition + nodes + edges),BE 同事务清空旧节点/边 + 重写 + version 自增
- 单人编辑锁:Redis SETNX 5min TTL,显式 acquire / release / renew(避免多人同时编辑覆盖)
- 下拉数据源补齐:`job-definitions/codes` + `pipeline-definitions/codes`,只返回 (code, name) 二元组

**不做**(明确范围边界):

- DAG 拓扑校验语义(START/END 唯一、无环、可达性等)— 画布 / FE 侧已校验,BE 只接收已校验数据 + 基础 `@ValidResourceCode` 非空 / 字符集校验
- 版本对比 diff(后续 Polish 阶段)
- 真正的协同编辑(光标共享 / CRDT 等)— Spike 只确保不互覆盖
- 锁的持久化 — 进程崩溃 / Redis 重启锁丢失即重新申请,不做 fallback

## 端点

URL 前缀沿用现有 `/api/console/workflow-definitions`(不另立 `/api/console/workflow/definitions`)。

| Method | Path | 用途 | 失败码 |
|---|---|---|---|
| PUT | `/{id}/full` | 画布 Save 全量替换 | `CONFLICT`(锁不归属/未持锁/version 冲突)、`INVALID_ARGUMENT`(workflowCode 试改)、`NOT_FOUND` |
| PUT | `/{id}/lock` | 申请编辑锁(5min) | `CONFLICT`(别人持锁,响应含 lockedBy) |
| DELETE | `/{id}/lock` | 释放锁(必须持锁人) | `FORBIDDEN`(非持锁人);锁过期幂等 204 |
| PUT | `/{id}/lock/renew` | 续期 5min | `CONFLICT`(过期)、`FORBIDDEN`(非持锁人) |
| GET | `/queries/job-definitions/codes` | JOB 节点下拉 | — |
| GET | `/queries/pipeline-definitions/codes` | FILE_STEP 节点下拉 | — |

详细 schema 见 `docs/api/console-api.openapi.yaml`(本 PR 同步落库)。

## 锁实现

- **Key**:`wf-design-lock:{tenantId}:{definitionId}`
- **Value**:JSON `{"lockedBy": "<console-username>", "expiresAt": "<iso8601-utc>"}`
- **TTL**:5min(常量 `WorkflowDesignLockService.LOCK_TTL`),续期重置
- **存储**:Redis SETNX(`StringRedisTemplate.opsForValue().setIfAbsent`),命中 → 返回 holder;失败 → 读现 value 抛 CONFLICT 含 lockedBy
- **不走 ShedLock**:后者是 scheduled job 进程级互斥语义,用户级 UI 编辑锁不复用以免语义混杂

## RBAC

- `full` / `lock` 三端点:`ROLE_ADMIN | ROLE_TENANT_ADMIN`(高权写路径,显式收紧)
- `codes` 两端点:沿用 `ConsoleQueryController` 类级 `ROLE_ADMIN | ROLE_AUDITOR | ROLE_TENANT_ADMIN | ROLE_TENANT_USER`(只读元数据)
- 租户作用域:全部走 `ConsoleTenantGuard.resolveTenant`,跨租户访问 FORBIDDEN

## i18n

新增错误码:

- `error.workflow_design_lock.{held_by_other, required, not_owner, expired, serialize_failed}`
- `error.workflow_full_update.{code_immutable, version_conflict}`

en / zh_CN 1:1 落 `messages.properties` + `messages_zh_CN.properties`。

## 测试

- `WorkflowDesignLockServiceTest`(6 case):acquire 成功 / 别人持锁 CONFLICT / 持锁人续期 / 持锁人释放 / 非持锁人 FORBIDDEN / 过期 renew CONFLICT
- `ConsoleWorkflowFullUpdateControllerTest`(3 case):成功 / 锁不归属 CONFLICT / 嵌套 @Valid 失败 400
