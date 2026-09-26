# 批量平台能力演进待办（2026-09-26）

> 结论：当前系统不缺新的中间件清单，缺的是把已有 PostgreSQL、Kafka、Redis、Worker、Orchestrator、Helm、OTel 和 CI 治理能力闭环成可运营平台。优先级按“上线可排障、容量可验证、扩展不破主链”排序。

## 1. 当前事实

| 能力 | 当前状态 | 证据 |
|---|---|---|
| OpenTelemetry / 可观测栈 | 已有拓扑、生产开关、Logback bridge、Prometheus / Loki / Tempo / Jaeger / Collector 文档 | [`../runbook/observability-stack.md`](../runbook/observability-stack.md) |
| K8s / KEDA | Helm 已有 Orchestrator backlog KEDA 与五类 Worker Kafka lag KEDA 模板，默认关闭 | [`../../helm/batch-platform/values.yaml`](../../helm/batch-platform/values.yaml) |
| Worker Runtime / SPI | `BatchTaskExecutor`、Registry、Import / Export / Process / Dispatch / Atomic executor 已落地 | [`../design/task-spi-design.md`](../design/task-spi-design.md) |
| 配置治理 | STATIC / DYNAMIC_DB / SECRET / RESTART_REQUIRED、checksum 滚动、禁止 `@RefreshScope` 已有约束 | [`../runbook/config-governance.md`](../runbook/config-governance.md) |
| 事件驱动到达 | Import event-arrival v1 已实现，默认关闭，轮询兜底仍保留 | [`../runbook/event-driven-arrival.md`](../runbook/event-driven-arrival.md) |
| 增量 / CDC | FULL / INCREMENTAL 已有水位回路；CDC 仍是占位，不是已实现流式平台 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §7.8 |
| GitOps | 有 workflow / Helm / runbook 骨架，尚未接 ops repo 与真实 Argo CD 环境 | [`../runbook/ci-cd-followup-2026-05-22.md`](../runbook/ci-cd-followup-2026-05-22.md) |
| AI Ops | Console AI 助手已有计划与边界，默认关闭；只读诊断 / 草稿建议，不接主链 | [`../plans/ai-integration-plan-2026-07.md`](../plans/ai-integration-plan-2026-07.md) |

## 2. P0：上线前必须收口

| ID | 待办 | 验收口径 | 备注 |
|---|---|---|---|
| **PLAT-OTEL-1** | 做一次完整业务链路 OTel 验收：Console/API → Trigger → Orchestrator → Kafka → Worker → Report | 同一 `traceId` / `jobInstanceId` 能在 Tempo 查 trace、Loki 查日志、Prometheus 查关键指标；租户字段必须来自认证身份 | 不是只跑静态 `check-observability-contract.py` |
| **PLAT-OTEL-2** | 定义批量主链 span / MDC 字段最小集 | 至少覆盖 `traceId`、`tenantId`、`jobInstanceId`、`workflowRunId`、`partitionId`、`taskId`、`batchDay`、`workerId`、`attempt`、`topic` | 高基数字段不进 Prometheus label |
| **PLAT-BP-1** | 背压与容量大盘收口 | Dashboard 同屏展示 admission、claim/report 延迟、Outbox backlog、Kafka lag、Hikari、PG 锁等待、Worker lease circuit、终态残留 | 容量结论必须能定位“控制面 / 执行面 / Kafka / PG”瓶颈 |
| **PLAT-WR-1** | Worker Runtime / SPI 行为一致性复核 | 五类 Worker 均能说明 claim、lease renew、取消、优雅停机、report outbox、progress、metrics、trace 的统一边界 | 已有 SPI 不能破坏现有 pipeline 主链 |

## 3. P1：值得做，但以 staging / ops 接入为验收边界

| ID | 待办 | 验收口径 | 备注 |
|---|---|---|---|
| **PLAT-KEDA-1** | KEDA staging 验证 | `batch.outbox.sharding-mode=dynamic` 下启用 Orchestrator backlog KEDA；扩缩容期间无重复 outbox 分片、无残留 `RUNNING` | static sharding 禁止直接 autoscale |
| **PLAT-KEDA-2** | 五类 Worker Kafka lag KEDA 验证 | Import / Export / Process / Dispatch / Atomic 分别按 lag 扩缩；缩容时 drain 正常，Kafka lag 能回落 | 需要真实 K8s + KEDA operator |
| **PLAT-GITOPS-1** | GitOps staging 接入 | 镜像构建、ops repo promotion PR、Argo CD sync、Helm values、staging smoke 全链路跑通 | 当前只有骨架，不能宣称已具备生产 GitOps |
| **PLAT-SECRET-1** | 生产 Secret 后端选型与接入 | DB / Kafka / Redis / 对象存储 / Console JWT / internal secret 均由 K8s Secret 或 Vault 类后端注入；普通 DB 动态配置不混入 Secret | 不引入普通配置中心替代现有配置治理 |
| **PLAT-WR-2** | Worker 第三方插件示例与 capability 展示 | 提供一个不依赖 `batch-worker-core` 的外部 `BatchTaskExecutor` 示例；Console 能展示已注册 taskType / capability | 先示例和治理，不急着无限扩内置 task type |

## 4. P2：业务触发后再推进

| ID | 待办 | 触发条件 | 边界 |
|---|---|---|---|
| **PLAT-CDC-1** | CDC / Streaming 方案设计 | 确认有持续流式同步、准实时入湖或下游 Kafka 事件处理需求 | 先设计 Debezium / Flink / Hudi 与批量平台的边界，不直接把 CDC 接进 Worker 主链 |
| **PLAT-AI-1** | AI Ops 根因诊断增强 | OTel / 日志 / 指标链路稳定后，AI 可读只读诊断上下文 | AI 只能给 RCA / 建议 / 草稿，不直接重试、改状态、写库 |
| **PLAT-IDP-1** | Worker 脚手架 / 内部开发平台 | Worker SPI、Helm、CI、镜像模板、SDK 契约稳定后 | 当前不做生成器，避免生成物和真实约定漂移 |

## 5. 暂时不做

| 事项 | 原因 |
|---|---|
| Service Mesh | 当前主要瓶颈不是服务间流量治理，而是批量状态、背压、观测和容量验收 |
| 更换 MQ | Kafka 是当前主链承重墙；抽象可以保留，但近期不以换 MQ 为目标 |
| 分布式数据库 / Citus | PostgreSQL 仍是合理核心；先做索引、分区、归档、连接池和容量验收 |
| Nacos / Apollo / Spring Cloud Config | 现有配置治理明确禁止预埋热刷新；达到多 region、频繁无重启变更、合规回滚或服务数量翻倍后再评估 |
| 主链 AI 自执行 | AI 不接 orchestrator / worker / trigger 主链，不直接改任务状态 |

## 6. 推荐执行顺序

1. `PLAT-OTEL-1` + `PLAT-OTEL-2`：先让一次失败任务能被完整解释。
2. `PLAT-BP-1`：把容量瓶颈从猜测变成指标证据。
3. `PLAT-WR-1`：把五类 Worker 的 runtime 行为统一成平台契约。
4. `PLAT-KEDA-1` + `PLAT-KEDA-2`：在 staging 验证弹性执行。
5. `PLAT-GITOPS-1` + `PLAT-SECRET-1`：补生产交付与密钥注入闭环。
6. 业务触发后再做 `PLAT-CDC-1`、`PLAT-AI-1`、`PLAT-IDP-1`。

## 7. 验证命令

静态与文档侧至少执行：

```bash
python3 scripts/ci/check-docs-structure.py
python3 scripts/ci/check-doc-timestamp-policy.py
python3 scripts/ci/check-code-doc-references.py
```

运行侧验收必须在目标环境补证据，本地静态检查不能替代 OTel、KEDA、GitOps、Secret 和容量压测的真实结果。
