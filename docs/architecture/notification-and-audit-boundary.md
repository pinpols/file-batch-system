# 通知与审计职责边界

> 状态：当前权威边界（2026-08-02）
>
> 本文统一说明 BFS 的通知、告警、运行日志和业务审计分别由谁负责、数据存在哪里、哪些能力已经生效，以及哪些配置只是预留。详细的表职责仍见 [`governance-tables-boundary.md`](./governance-tables-boundary.md)，日志采集细节仍见 [`../design/logging-architecture.md`](../design/logging-architecture.md)。

## 1. 结论

BFS 采用两条相互独立的横切链路：

1. **通知链路**：技术告警由 Prometheus/Alertmanager 负责；业务事件通知由 BFS 的订阅、渠道和投递记录负责。
2. **审计链路**：技术运行日志由 SLF4J/OTel/Loki 负责；业务操作和交付证据由 PostgreSQL 审计表负责。

两条链路不能互相替代：日志不是业务审计，Alertmanager 告警不是租户事件订阅，数据库审计表也不是全文日志系统。

## 2. 通知与告警

### 2.1 技术告警

| 内容 | 权威组件 | 说明 |
|---|---|---|
| 指标采集 | Micrometer + Prometheus | 采集 outbox、Kafka lag、worker、SLA、PG、Redis、MinIO 等指标 |
| 告警规则 | Prometheus rules | 判断阈值、持续时间和告警级别 |
| 分组、抑制、静默、路由 | Alertmanager 静态模板 | 当前生产可用的技术告警路由 |
| 告警查看 | Grafana / Alertmanager | 观测、确认和排障入口 |
| 告警交付 | Webhook、邮件及现有渠道适配 | 投递状态和重试由 BFS 记录 |

### 2.2 业务事件通知

| 数据/组件 | 责任 |
|---|---|
| `subscription_rule` | 租户订阅哪些业务事件、严重度和 job 范围 |
| `notification_channel` | 邮件、钉钉、企业微信、Webhook 等业务投递渠道 |
| `notification_delivery_log` | 业务通知投递结果、重试和最终失败 |
| `NotificationDispatchService` | 按订阅规则生成并投递业务事件通知 |
| Webhook relay | 持久化投递、退避重试、GIVE_UP 和指标 |

### 2.3 `alert_routing_config` 的真实状态

`alert_routing_config` 是平台告警动态路由的**预留配置模型**，目前有 CRUD 和数据表，但没有运行时 `AlertRoutingResolver` 消费者。因此：

- 它不会改变 Prometheus/Alertmanager 当前路由。
- 它不能作为“告警已分组、去重、静默”的运行时证据。
- 需要修改技术告警路由时，应修改 Alertmanager 模板并按观测栈 runbook 验证。
- 动态路由迁移暂缓到上线后出现真实告警流量，再基于实际分组、去重和静默需求评估。

## 3. 审计与日志

### 3.1 技术运行日志

| 内容 | 权威组件 | 不承担的责任 |
|---|---|---|
| 应用日志 | SLF4J + Logback | 不作为业务状态真相 |
| 链路字段 | MDC、traceId、requestId、tenantId、jobInstanceId | 不替代业务实体关联 |
| 日志聚合 | OTel Collector、Loki、Grafana | 不提供业务操作审批语义 |
| 基础设施指标 | Prometheus、Exporter | 不记录每一次业务变更的完整前后值 |

应用日志不写 PostgreSQL，避免日志量拖垮业务库；也不把 BFS 扩展成 Loki/OpenSearch/SIEM 产品。

### 3.2 业务操作与交付审计

| 审计对象 | 主要事实表/记录 | 典型内容 |
|---|---|---|
| 作业执行 | `job_execution_log`、workflow/node/task 运行记录 | 状态推进、attempt、失败原因、终态 |
| 文件操作 | `file_audit_log`、`file_dispatch_record` | 上传、下载、分发、回执、checksum |
| 配置变更 | `config_change_log`、config release/approval | 发布、灰度、回滚、操作者和版本 |
| 运行时操作 | approval command、operation audit | 重跑、重放、取消、补偿、人工确认 |
| 通知交付 | `notification_delivery_log`、`webhook_delivery_log` | 渠道、投递、重试、最终失败 |
| 消息交付 | `outbox_event`、`event_delivery_log`、DLQ | 发布状态、重试、隔离、重放 |
| AI 使用 | `console_ai_audit_log` | 请求、响应摘要、token/cost、操作者 |
| 取证与归档 | archive 表、forensic bundle | 按租户、业务日和运行实例留存证据 |

业务审计必须满足：租户隔离、操作者可追溯、关联 trace/request/task、状态变更可解释、归档策略可执行。审计表只保存结构化事实和摘要，不承载大段应用日志。

## 4. 复用与自研边界

| 能力 | 复用成熟方案 | BFS 必须保留 |
|---|---|---|
| 技术告警 | Prometheus、Alertmanager、Grafana | BFS 业务指标、告警规则和 runbook |
| 日志与 Trace | OTel、Loki、Tempo、Grafana | 任务/批次/租户关联字段 |
| 安全分析 | 对接 SIEM/OpenSearch 等外部系统 | 业务操作审计和取证包 |
| 业务通知 | 邮件/Webhook/钉钉等客户端或渠道 SDK | 租户订阅、审批通知、投递状态和幂等 |
| 审计存储 | PostgreSQL、归档存储 | BFS 领域审计模型和生命周期 |

禁止事项：

- 不把 `alert_routing_config` 当作已生效的 Alertmanager 动态路由。
- 不用应用日志代替重跑、审批、取消和配置变更审计。
- 不为告警分组、全文日志或 SIEM 再造一套平台。
- 不把业务通知与技术告警强行合并成同一张订阅表。
- 不为了“完整审计”记录所有请求体、文件内容或敏感凭据。

## 5. 运维判定顺序

| 问题 | 首先查看 |
|---|---|
| Kafka lag、outbox 积压、worker 离线 | Prometheus/Grafana/Alertmanager 和对应 runbook |
| 租户为什么没收到业务通知 | `subscription_rule`、`notification_channel`、`notification_delivery_log` |
| 谁重跑/取消/审批了任务 | operation audit、approval command、job execution log |
| 配置为什么生效或回滚 | config release、config change log、操作者和版本 |
| 任务为什么失败或重复 | task/attempt/outbox/DLQ 记录 + traceId |
| 需要完整取证 | 结构化业务审计 + archive/forensic bundle；日志只作辅助证据 |

## 6. 相关文档优先级

1. 本文：通知与审计的统一职责边界。
2. [`governance-tables-boundary.md`](./governance-tables-boundary.md)：通知治理表的字段、所有权和不合并理由。
3. [`../design/logging-architecture.md`](../design/logging-architecture.md)：日志、MDC、OTel、Loki 和审计表清单的实现细节。
4. [`../runbook/observability-stack.md`](../runbook/observability-stack.md)：观测栈启动、指标、告警和排障操作。
5. [`../plans/bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md`](../plans/bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md)：动态告警路由、配置中心和产品边界的后续裁定。
