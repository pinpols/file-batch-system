# REST / Command API Toggle 语义治理待办

## 结论

当前 `toggle` 接口不是上线阻断问题。

本专题来自 Console API RESTful 全量审查，审查报告见
[`../review/console-api-restful-review-2026-09-26.md`](../review/console-api-restful-review-2026-09-26.md)。

复核结果显示，现有 `POST .../toggle` 接口都要求调用方显式传入 `enabled` 目标值，实际语义是“设置启停状态”，不是无参反转。因此它们已经具备幂等基础，不存在“同一请求重试导致状态来回翻转”的活 bug。

需要治理的是契约命名和接口风格：路径名 `toggle` 容易让调用方误解为非幂等翻转操作，也和已经较成熟的 `PATCH .../enabled` 风格不一致。

## 当前范围

后端 OpenAPI 当前命中 6 个候选接口：

| 接口 | 当前语义 | 治理建议 |
|---|---|---|
| `POST /api/console/queues/{id}/toggle?enabled=` | 设置队列启停 | 新增显式状态接口，旧接口保留兼容 |
| `POST /api/console/batch-windows/{id}/toggle?enabled=` | 设置批量窗口启停 | 新增显式状态接口，旧接口保留兼容 |
| `POST /api/console/calendars/{id}/toggle?enabled=` | 设置日历启停 | 新增显式状态接口，旧接口保留兼容 |
| `POST /api/console/quota-policies/{id}/toggle?enabled=` | 设置配额策略启停 | 新增显式状态接口，旧接口保留兼容 |
| `POST /api/console/alert-routings/{id}/toggle?enabled=` | 设置告警路由启停 | 新增显式状态接口，旧接口保留兼容 |
| `POST /api/console/pipeline-definitions/{id}/toggle?enabled=` | 设置 Pipeline 定义启停 | 新增显式状态接口，旧接口保留兼容 |

已存在的正向样板：

| 接口 | 说明 |
|---|---|
| `PATCH /api/console/asset-freshness-policies/{id}/enabled` | 使用 request body `{ "enabled": true/false }` 明确设置目标状态 |
| `PATCH /api/console/file-channels/{id}` | 使用 body 显式更新 `enabled` |
| `PATCH /api/console/file-templates/{id}` | 使用 body 显式更新 `enabled` |
| `PATCH /api/console/job-definitions/{id}` | 使用 body 显式更新 `enabled` |

## 不纳入本次治理

以下接口虽然也是动作接口，但属于业务命令，不应强行改成资源更新：

| 类型 | 示例 | 理由 |
|---|---|---|
| 触发类 | `POST /api/console/jobs/trigger`、`POST /api/console/jobs/rerun` | 产生新运行意图或重跑命令，不是资源字段更新 |
| 审批类 | `POST /api/console/approvals/{approvalNo}/approve`、`reject` | 带审计、权限和状态机副作用 |
| 调度控制 | `POST /api/console/scheduler/pause-all`、`resume-all` | 运维命令，非单资源字段 |
| 注册类 | `POST /api/console/ops/triggers/{jobCode}/register`、`unregister` | 管理外部调度注册状态，语义不是简单 enabled |
| 计划 / 演练 | `POST /api/console/ops/dry-run/plan` | 计算命令，可能包含复杂 body，GET 不合适 |

## 建议方案

### Phase 1：兼容新增

新增显式状态接口，保留旧接口：

```text
PATCH /api/console/queues/{id}/enabled
PATCH /api/console/batch-windows/{id}/enabled
PATCH /api/console/calendars/{id}/enabled
PATCH /api/console/quota-policies/{id}/enabled
PATCH /api/console/alert-routings/{id}/enabled
PATCH /api/console/pipeline-definitions/{id}/enabled
```

请求体统一：

```json
{
  "tenantId": "ta",
  "enabled": true
}
```

约束：

- 新旧接口必须调用同一个 application service 方法，避免双实现漂移。
- 旧 `toggle` 接口标记 deprecated，但暂不删除。
- OpenAPI 给旧接口补充说明：该接口实际为显式设置 `enabled`，不是无参翻转。

### Phase 2：前端切换

前端 API 方法改为调用新接口：

| 前端位置 | 当前调用 |
|---|---|
| `src/api/governance.ts` | `toggleQueue` / `toggleBatchWindow` / `toggleCalendar` / `toggleQuotaPolicy` / `toggleAlertRouting` |
| `src/api/system.ts` | `togglePipelineDefinition` |

页面层可以继续叫 `toggleXxx`，因为 UI 行为是开关切换；API 层注释要说明它是“设置目标 enabled 状态”。

### Phase 3：旧接口退役

满足以下条件后再删除旧接口：

- 前端主分支和 E2E 已全部切新接口。
- OpenAPI SDK / 生成类型不再依赖旧路径。
- 至少一个小版本周期内没有旧接口访问日志。
- PR 模板或 API review checklist 已覆盖“启停状态必须显式传目标状态”。

## 影响面

| 层 | 预计文件 |
|---|---|
| 后端 controller | `ConsoleResourceQueueController`、`ConsoleBatchWindowController`、`ConsoleCalendarController`、`ConsoleQuotaPolicyController`、`ConsoleAlertRoutingController`、`ConsolePipelineDefinitionController` |
| 后端 service / mapper | 理论上复用现有 `toggle(id, tenantId, enabled)` / `toggleEnabled`，无需新增持久层逻辑 |
| OpenAPI | `docs/api/console-api.openapi.yaml` + 协议说明 |
| 前端 API | `src/api/governance.ts`、`src/api/system.ts` |
| 前端页面 | `QueueConfig.vue`、`QuotaPanel.vue`、`AlertRoutingPanel.vue`、Pipeline 定义列表 |
| 测试 | controller 单测、前端 API 单测、相关 E2E 选择性更新 |

预计改动量：15 到 25 个文件。主要是契约和调用路径调整，不涉及核心调度链路。

## 优先级

| 维度 | 判断 |
|---|---|
| 上线阻断 | 否 |
| 数据一致性风险 | 低，当前已经显式传 `enabled` |
| 用户可见风险 | 低 |
| 工程一致性收益 | 中 |
| 建议优先级 | P2 |

建议在安全、容量、配置治理、CI 门禁和主链路稳定性之后实施。

## 验收标准

- 新接口和旧接口对同一资源设置相同 `enabled` 值时结果一致。
- 重复调用新接口不会改变目标状态以外的字段。
- 旧接口仍兼容当前前端和外部调用方。
- OpenAPI 生成类型更新，前端 `gen:api:check` 通过。
- 前端启停开关页面回归通过。
