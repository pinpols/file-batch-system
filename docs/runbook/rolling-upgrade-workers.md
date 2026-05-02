# Worker 滚动升级 Runbook

## 目标

在不停掉整个平台的前提下，逐个或分批替换 Worker 进程（新版本镜像/配置），并通过 **排空（drain）** 让已认领任务被安全移交或重试，避免硬杀导致任务长期卡在错误 Worker 上。

## 编排侧行为（概要）

- **Drain**：将 `worker_registry` 中对应 Worker 标为 `DRAINING`，并记录 `drain_started_at`、`drain_deadline_at`（截止时间 = 开始时间 + 超时）。
- **超时接管**：Orchestrator 定时任务按 `batch.worker.drain.check-interval-millis` 扫描；若仍处于 `DRAINING` 且已超过 `drain_deadline_at`，则对该 Worker 仍占用的活跃任务执行与「强制下线」相同的 **接管（重试/重新入队）**，然后将 Worker 标为 `DECOMMISSIONED`。
- **强制下线**：不等待自然排空，立即对仍分配给该 Worker 的活跃任务做接管，然后下线。

环境变量（Orchestrator，默认值见 `application.yml`）：

- `BATCH_WORKER_DRAIN_TIMEOUT_SECONDS`：未在请求体指定时的默认排空超时（秒）。
- `BATCH_WORKER_DRAIN_CHECK_INTERVAL_MILLIS`：超时扫描间隔（毫秒）。
- `BATCH_WORKER_DRAIN_ENABLED`：是否启用超时调度器。

## 控制台 API（面向运维）

需具备 `ROLE_ADMIN` 或 `ROLE_CONFIG_ADMIN`。请求需带租户上下文；`tenantId` 与当前登录租户不一致时会拒绝。

| 操作 | 方法 | 路径 |
|------|------|------|
| 发起排空 | `POST` | `/api/console/workers/{workerCode}/drain` |
| 强制下线 | `POST` | `/api/console/workers/{workerCode}/force-offline` |
| 查询剩余认领任务 | `GET` | `/api/console/workers/{workerCode}/claimed-tasks?tenantId=...` |

**Drain 请求体**（示例）：`tenantId` 必填；`timeoutSeconds` 可选，覆盖默认排空时长。

**说明**：控制台将请求转发到 Orchestrator 内部接口 `/internal/workers/...`；生产应通过网络策略仅允许 Console 访问 Orchestrator。

## 推荐滚动步骤（单 Worker 实例）

1. **确认实例 identity**：从运维侧确认要替换的 Pod/进程对应的 `workerCode`（与注册表一致）。
2. **发起 drain**：对目标 `workerCode` 调用 `POST .../drain`，设置合适的 `timeoutSeconds`（应大于该 Worker 上典型长任务时长）。
3. **观察**：轮询 `GET .../claimed-tasks?tenantId=...`；列表收敛为空表示该 Worker 在编排侧已无 RUNNING/READY/CREATED 且仍指向该 Worker 的任务（以实际查询语义为准）。
4. **部署新版本**：在 K8s 中滚动该 Deployment/StatefulSet 的对应副本，或替换进程；新进程以新 `workerCode` 或同 code 重新注册（依你们部署约定）。
5. **若 drain 过久**：评估业务后使用 `POST .../force-offline` 触发立即接管并下线，再替换实例。

对多副本 Worker：**逐副本**重复上述流程，或按池分批，保证任意时刻集群仍有足够容量处理负载。

### DB 迁移前置条件（2026-04-30 起强制）

新 worker 拉起前必须确保 **orchestrator 已对接以下 Flyway 迁移**，否则 schema mismatch 会导致 worker 启动 / 上报失败：

| Migration | 表 / 列变化 | 影响范围 | 不应用的故障 |
|---|---|---|---|
| **V72** | `batch.workflow_node_run` 加 `output JSONB` 列(ADR-009) | orchestrator `WorkflowNodeRunMapper.updateStatus` 写 `#{output}::jsonb` | 列缺失时 `column "output" does not exist`,所有 workflow 节点 finish 报错 |
| **V77** | `job_task` / `workflow_node_run` / `event_delivery_log` 加 `error_key VARCHAR(128)` + `error_args JSONB`(i18n Phase F) | orchestrator i18n 三元组持久化路径 | 列缺失时 worker 上报 errorKey 时落库失败 |
| **V78** | 8 表加 `error_key`/`error_args`(i18n Phase 2):`pipeline_step_run` / `job_step_instance` / `compensation_command` / `compensation_checkpoint` / `file_dispatch_record` / `file_error_record` / `retry_schedule`(`last_error_key`/`last_error_args`)/`notification_delivery_log` | orchestrator + console-api 读路径过 `LocalizedErrorRenderer` | 同 V77 |
| **V79** | archive.* 冷表同步加 `error_key`/`error_args`(与 batch.* 对齐) | `ArchiveSchemaDriftCheck` 启动自检 | drift 不匹配时 orchestrator **启动失败**,因此必须先 V79 后 orchestrator 重启 |
| **V80** | `batch.trigger_outbox_event` 表(ADR-010 trigger 异步，固化) | trigger outbox 写入路径（唯一路径，无开关） | 表缺失时 trigger INSERT 失败 |

**滚动顺序**(强烈建议):

```
1. 先在 staging apply V72/V77/V78/V79/V80 → 验证 schema drift check 通过
2. orchestrator 重启,确认 ArchiveSchemaDriftCheck PASS
3. 确认所有 worker 镜像版本对齐(都已含 i18n + ADR-009 outputs 字段)
4. 按本节"推荐滚动步骤"逐副本替换 worker
5. 滚动期间避免新旧 worker 混合超过 30 分钟(JsonIgnoreProperties 兜底未识别字段,但语义上仍建议短窗口)
```

**滚动期间兼容性矩阵**:

- **新 orchestrator + 旧 worker**:✅ 安全。worker 不上报 outputs/errorKey,orchestrator 落库 NULL,DSL 引用按 null fallback。
- **旧 orchestrator + 新 worker**:⚠️ worker 上报的 outputs/errorKey 字段被 orchestrator 忽略(`@JsonIgnoreProperties(ignoreUnknown = true)` 保护),但 V72/V77/V78/V79 列若未 apply 则 worker → orchestrator 上报后 mapper 写库会失败。**所以 V72/V77/V78/V79 必须先于 worker 升级**。

## Worker 进程侧（建议）

本仓库在 Orchestrator/Console 侧提供了排空状态与 API；Worker 进程若需在 `DRAINING` 时 **停止拉取新任务**，应在心跳或配置拉取结果中识别 `DRAINING` 并停止 claim（若尚未实现，请在发布说明中注明当前行为）。

## 验收核对

- [ ] 能对指定 `workerCode` 发起 drain 并查到 `claimed-tasks`。
- [ ] 超时后 Orchestrator 能完成接管并将 Worker 标为下线（查 `worker_registry` 或控制台）。
- [ ] `force-offline` 能在紧急情况下立即移交任务并下线。
