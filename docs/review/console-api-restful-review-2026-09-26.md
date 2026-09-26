# Console API RESTful 风格审查

## 结论

Console API 不需要按“纯 RESTful 教科书”大改。

当前接口整体是成熟 BFF / 控制台 API 风格：资源 CRUD 使用 REST，查询投影集中在 `/queries/**`，复杂业务动作使用 Command API。对批量调度、审批、运维、dry-run、重放和导入导出来说，这种混合风格比强行把所有动作改成资源字段更新更清晰。

本轮唯一建议进入当前待办的是 6 个 `POST .../toggle?enabled=` 接口的命名治理。它们当前已显式传入 `enabled`，不是活 bug；但路径名 `toggle` 容易误导调用方，以后应兼容迁移到显式状态接口。

## 审查范围

审查对象：

- `docs/api/console-api.openapi.yaml`
- 前端 `batch-console/src/api/**` 调用方式
- 后端 `batch-console-api` 控制层与下游 BFF 边界

OpenAPI 当前统计：

| 项 | 数量 |
|---|---:|
| Path | 331 |
| Operation | 380 |
| `GET` | 200 |
| `POST` | 134 |
| `PUT` | 26 |
| `PATCH` | 6 |
| `DELETE` | 14 |

## 风格分类

### 资源 CRUD：基本合理

这类接口符合常规 REST 语义：

| 类型 | 示例 | 结论 |
|---|---|---|
| 查询资源 | `GET /api/console/job-definitions/{id}` | 合理 |
| 创建资源 | `POST /api/console/job-definitions` | 合理 |
| 整体更新 | `PUT /api/console/workflow-definitions/{id}` | 合理 |
| 局部更新 | `PATCH /api/console/job-definitions/{id}` | 合理 |
| 删除资源 | `DELETE /api/console/tags`、`DELETE /api/console/files/{fileId}` | 合理 |

已存在的较好样板：

- `PATCH /api/console/asset-freshness-policies/{id}/enabled`
- `PATCH /api/console/file-channels/{id}`
- `PATCH /api/console/file-templates/{id}`
- `PATCH /api/console/job-definitions/{id}`

这些接口显式表达“把某字段设置成目标值”，比无参翻转更适合重试和审计。

### 查询投影：不是纯 REST，但合理

`/api/console/queries/**` 是 CQRS / read-model 风格：

| 示例 | 说明 |
|---|---|
| `GET /api/console/queries/instances` | 作业实例查询投影 |
| `GET /api/console/queries/job-definitions` | 作业定义列表投影 |
| `GET /api/console/queries/workflow-runs` | Workflow 运行查询 |
| `GET /api/console/queries/pipeline-progress` | 进度查询投影 |

这类接口不必强行改成资源根路径。Console API 需要承载分页、筛选、聚合、读副本、跨表投影和 UI 专用字段，`/queries/**` 能明确告诉调用方“这是查询投影，不是领域写模型”。

保留建议：

- 继续使用 `GET`。
- 查询参数要保持幂等、可审计、可分页。
- 不在 `/queries/**` 下放写操作。

### Command API：保留，不强改

以下动作接口是业务命令，不适合强行改成资源 CRUD：

| 类型 | 示例 | 保留理由 |
|---|---|---|
| 触发 / 重跑 | `POST /api/console/jobs/trigger`、`POST /api/console/jobs/rerun` | 会创建运行意图或发起状态机命令 |
| 审批 | `POST /api/console/approvals/{approvalNo}/approve`、`reject` | 带权限、审计和终态约束 |
| 调度运维 | `POST /api/console/scheduler/pause-all`、`resume-all` | 运维命令，不是单资源字段 |
| Worker 运维 | `POST /api/console/workers/{workerCode}/drain`、`takeover`、`force-offline` | 有副作用和时序语义 |
| 重放 / 补偿 | `POST /api/console/jobs/dead-letters/replay`、`tasks/replay`、`partitions/replay` | 不是简单资源替换 |
| dry-run | `POST /api/console/ops/dry-run/plan` | 复杂请求体 + 计算结果，GET 不合适 |
| cache / outbox 运维 | `POST /api/console/ops/cache/evict-*`、`outbox/republish` | 明确命令语义 |

这类接口的重点不是“像不像 REST”，而是：

- 是否需要幂等键。
- 是否有权限和租户校验。
- 是否有审计日志。
- 是否能明确返回命令受理结果。
- 是否避免 GET 触发副作用。

### 导入导出 / 下载：当前可接受

| 接口 | 结论 |
|---|---|
| `GET /api/console/jobs/bundle/export` | 查询并导出 bundle，GET 可接受 |
| `POST /api/console/jobs/bundle/import` | 上传/导入有副作用，POST 合理 |
| `POST /api/console/config/sync/export` | 请求体复杂，生成导出包，POST 可接受 |
| `POST /api/console/config/sync/preview` | 复杂 body 的预览计算，POST 可接受 |
| `GET /api/console/files/{fileId}/download` | 下载资源，GET 合理 |
| `GET /api/console/files/{fileId}/errors/export` | 导出错误记录，GET 可接受 |

导出类接口如果未来需要长任务化，可以演进为：

```text
POST /exports
GET  /exports/{id}
GET  /exports/{id}/download
```

当前同步导出体量受控时，不是必须改。

## 建议修改项

### P2：`toggle` 命名与显式状态接口收敛

候选接口：

| 当前接口 | 当前事实 | 建议 |
|---|---|---|
| `POST /api/console/queues/{id}/toggle?enabled=` | 已显式传目标状态 | 新增 `PATCH /api/console/queues/{id}/enabled` |
| `POST /api/console/batch-windows/{id}/toggle?enabled=` | 已显式传目标状态 | 新增 `PATCH /api/console/batch-windows/{id}/enabled` |
| `POST /api/console/calendars/{id}/toggle?enabled=` | 已显式传目标状态 | 新增 `PATCH /api/console/calendars/{id}/enabled` |
| `POST /api/console/quota-policies/{id}/toggle?enabled=` | 已显式传目标状态 | 新增 `PATCH /api/console/quota-policies/{id}/enabled` |
| `POST /api/console/alert-routings/{id}/toggle?enabled=` | 已显式传目标状态 | 新增 `PATCH /api/console/alert-routings/{id}/enabled` |
| `POST /api/console/pipeline-definitions/{id}/toggle?enabled=` | 已显式传目标状态 | 新增 `PATCH /api/console/pipeline-definitions/{id}/enabled` |

详细待办见：

- [`../backlog/rest-command-api-toggle-governance-2026-09-26.md`](../backlog/rest-command-api-toggle-governance-2026-09-26.md)

### 不建议改的项

| 项 | 不改原因 |
|---|---|
| `/queries/**` | 读模型投影明确，符合 Console BFF/CQRS 边界 |
| `/ops/**` | 运维命令集合，强改成资源树收益低 |
| `approve/reject/pause/resume/rerun/replay` | 业务命令而非资源字段替换 |
| `dry-run/plan` | 复杂计算请求，POST 比 GET 更合适 |
| `bundle/import/export` | 导入导出语义清楚，当前同步模式下可接受 |

## 风险判断

| 风险 | 当前判断 |
|---|---|
| 浏览器绕过 Console API 直连 Orchestrator / Trigger | 未发现；前端运行态请求走 `/api/console/**` |
| GET 执行写副作用 | 未发现需要立即整改的主路径 |
| 无参 toggle 导致重试翻转 | 未发现；6 个 toggle 都带 `enabled` 目标值 |
| 动作接口用 POST | 合理，是 Command API，不是 REST 缺陷 |
| OpenAPI 与前端调用大面积不一致 | 本轮未发现；正式修改接口时仍需跑 OpenAPI / 前端生成类型校验 |

## 后续验收建议

如果实施 P2 toggle 收敛，验收至少包括：

- 后端新增接口与旧接口调用同一 service，不新增持久层分叉逻辑。
- 旧接口标记 deprecated 并保持兼容。
- OpenAPI 同步更新。
- 前端 API 调用切新接口。
- `npm run gen:api:check` 通过。
- 对应页面开关操作回归通过。
