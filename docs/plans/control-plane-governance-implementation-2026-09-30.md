# 控制面治理落地记录

> 复核日期：2026-10-01。本文记录本轮治理的代码落地、可重复验证方式和仍需外部环境提供的证据；不把 staging 或生产演练写成本地已完成。

## 1. 范围

本轮只围绕 BFS 现有的“批量运行控制面 + 文件/任务交付闭环”补治理，不新增通用配置中心、服务网格、工作流编排器、数据治理平台或独立成本平台。

| 工作项 | 本轮落地 | 当前证据边界 |
|---|---|---|
| 维护/降级多副本一致性 | V215 共享单例、version CAS、5 秒轮询、失联时写 fail-closed、维护状态 gauge；配对前端维护写保护和降级来源提示已完成 | 本地真实 HTTP/浏览器故障与恢复验证已完成；双 Console staging 收敛和告警触发仍待外部环境验证 |
| 使用率日聚合 | V216 月分区、严格 RLS、审计投影、并发累加 upsert、summary API/OpenAPI；配对前端趋势页已完成 | 后端真实 PG RLS/并发测试及本地 API/SQL 对账已完成；业务终态结果对账、生产保留策略与容量验证仍待外部证据 |
| OTel 运行证据 | 可配置证据采集脚本，记录健康、指标、Collector 和 Prometheus 快照 | 脚本可执行；真实 trace 在 Tempo/Loki 的端到端关联待用环境运行 |
| 背压和容量大盘 | Grafana capacity dashboard、Worker/队列/副本/Outbox/DLQ 告警 | 配置静态校验待跑；阈值和容量结论需真实压测 |
| Worker 滚动升级 staging 验证 | 验收脚本只检查 rollout、Pod、事件，明确不自动删除/回滚 | 需要真实 K8s staging、drain、接管和业务对账 |
| AI 成本/会话/审计治理 | V218 会话/轮次/月用量表；正文加密、可选持久化、预算预留及配对前端会话体验已完成 | 本地代码及联测已完成；真实 provider 账单校准、预算阈值和 staging/合规保留策略仍待验收 |

## 2. 关键正确性约束

### 2.1 维护状态

- `batch.console_maintenance_state` 是跨副本唯一事实源，管理员更新使用 `version` CAS。
- 副本读取失败时只允许健康、状态和维护控制白名单继续访问；普通写请求返回 503。
- `X-Maintenance-Version` 与响应体 `version` 必须同时反映当前已确认版本。
- 指标只使用低基数状态，不把租户、请求或实例号放进 Prometheus 标签。

### 2.2 使用率聚合

- `console_operation_audit` 是操作事实源，`console_usage_daily` 是派生统计，不作为审计、计费或业务正确性判据。
- 聚合表严格 RLS；写入/查询事务先设置 `app.tenant_id`，避免新表出现“策略严格但应用无法访问”的漂移。
- `ON CONFLICT` 使用数据库侧累加而不是覆盖，避免并发操作丢计数。
- 统计写失败只记录告警，不回滚真实业务和操作审计。

### 2.3 AI 成本和审计

- 现有 RPM、并发舱壁、请求超时和 provider 降级保持不变。
- `max-completion-tokens` 是 provider 请求级硬上限；`daily-request-limit` 是按租户自然日的可选请求预算。
- 日预算启用后 Redis 不可用默认拒绝，避免成本护栏静默失效；分钟限流仍遵循现有 fail-open 兼容策略。
- AI 审计清理默认关闭。开启前必须确认组织保留期、查询权限和历史预览字段处理，不允许把自动删除当成合规默认值。

## 3. 可重复验证

### 3.1 后端代码与契约

```bash
./mvnw -pl batch-console-api -am -DskipTests -DskipITs package
./mvnw -pl batch-console-api -am -DskipITs \
  -Dtest=AuditAspectTenantFallbackTest,ConsoleSystemControllerTest,ConsoleAdminMaintenanceControllerTest,MaintenanceModeFilterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 scripts/ci/check-observability-contract.py
git diff --check
```

以下迁移与集成验证已在本地真实 PostgreSQL 测试中覆盖；生产分区维护、保留清理策略仍需按部署约束另行验收：

- V215/V216/V217 顺序升级；
- 两个事务并发 upsert 后 `event_count` 等于事件数；
- 两个租户设置不同 `app.tenant_id` 时互不可见；
- 聚合写失败不影响 `console_operation_audit` 及业务事务；
- AI 清理开关关闭时不删除历史记录，开启后只删除 cutoff 之前的数据。

### 3.2 OTel 和容量证据

```bash
CONSOLE_URL=http://... \
ORCHESTRATOR_URL=http://... \
OTEL_COLLECTOR_METRICS_URL=http://.../metrics \
PROMETHEUS_URL=http://... \
EVIDENCE_DIR=artifacts/observability/staging-$(date -u +%Y%m%dT%H%M%SZ) \
bash scripts/observability/capture-runtime-evidence.sh
```

该脚本不会启动或重启服务。它只证明端点、Collector 指标和容量指标可查询；需要业务请求的 `traceId` 时，把真实请求产生的 `TRACEPARENT` 传入，并在 Tempo/Loki 中人工核对完整链路。

### 3.3 Worker 滚动升级

```bash
NAMESPACE=batch-staging \
WORKER_DEPLOYMENTS=worker-import,worker-export,worker-process,worker-dispatch,worker-atomic \
KUBE_CONTEXT=<staging-context> \
bash scripts/staging/verify-worker-rolling-upgrade.sh
```

发布动作仍按 [Worker 滚动升级 Runbook](../runbook/rolling-upgrade-workers.md) 先 drain、再 rollout；脚本不执行删除 Pod、强制下线或回滚。验收必须补充：非终态任务归零、lease 接管、Kafka lag 收敛、失败任务明确终态和业务结果对账。

## 4. 外部环境与运营验收

1. 真实 staging 的双 Console 副本切换、维护告警触发和恢复演练。
2. 使用率聚合与目标生产业务终态结果对账，确认租户、来源和版本口径，并评估生产分区维护、留存策略与容量。
3. Console → Trigger → Orchestrator → Kafka → Worker → Report 的完整 OTel trace 在 Tempo/Loki 可检索证据。
4. 真实压力下容量阈值、背压恢复时间和横向扩容结论。
5. Worker 滚动升级过程中的真实 drain/接管/回滚演练。
6. AI provider 账单对账、月预算阈值校准、并发/失败演练及消息保留期合规审批。

这些是环境或产品决策依赖，不通过增加本地 mock 结果冒充完成。

## 5. 权威实现位置

- 维护状态：`db/migration/V215__create_console_maintenance_state.sql`、`batch-console-api/.../support/maintenance/`
- 日聚合：`db/migration/V216__create_console_usage_daily.sql`、`batch-console-api/.../domain/observability/`
- AI 会话/用量/审计治理：`db/migration/V217__index_console_ai_audit_retention.sql`、`db/migration/V218__console_ai_conversations_and_cost_usage.sql`、`batch-console-api/.../domain/audit/infrastructure/ai/`
- 告警和容量：`deploy/docker/observability/prometheus-batch-rules.yml`、`grafana-dashboard-batch-capacity.json`
- 运行证据：`scripts/observability/capture-runtime-evidence.sh`、`scripts/staging/verify-worker-rolling-upgrade.sh`
