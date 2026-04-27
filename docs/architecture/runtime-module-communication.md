# 运行时模块通信拓扑

这份文档拆成两层：

- 总览图：看模块、协议、对端和主要落点
- 细化图：看 `orchestrator` 收到不同 HTTP 请求后，实际会写哪些核心表

说明：

- 图上的表表示“对端服务最终读写的核心表”，不代表发起方一定直连这些表。
- `batch-worker-core` 是 `dispatch/import/export` 三类 worker 共享的基础库，不是独立部署模块，所以图里只画具体 worker 进程。
- 图里保留高信号表，不把所有辅助表、全部状态字段和每个 mapper 细节都展开。

## 总览图

```mermaid
flowchart LR
  C["batch-console-api"]
  T["batch-trigger"]
  O["batch-orchestrator"]
  K["Kafka topics<br/>batch.task.dispatch.*"]
  WD["batch-worker-dispatch"]
  WI["batch-worker-import"]
  WE["batch-worker-export"]
  WP["batch-worker-process"]
  M["MinIO bucket<br/>batch-dev"]

  P["batch_platform<br/>trigger_request<br/>job_definition<br/>job_instance<br/>job_partition<br/>job_task<br/>workflow_run<br/>workflow_node_run<br/>worker_registry<br/>retry_schedule<br/>dead_letter_task<br/>outbox_event<br/>file_record<br/>file_channel_config<br/>file_dispatch_record<br/>file_audit_log<br/>..."]

  B["batch_business<br/>biz.customer_account<br/>biz.settlement_batch<br/>biz.settlement_detail"]

  Q["Quartz tables<br/>quartz.QRTZ_*"]

  C -->|"HTTP -> trigger<br/>launch / compensate / rerun"| T
  C -->|"HTTP -> orchestrator<br/>dead-letter replay"| O
  C -->|"JDBC query -> batch_platform<br/>job_definition / job_instance / file_record / file_audit_log / worker_registry"| P

  T -->|"JDBC -> batch_platform<br/>trigger_request"| P
  T -->|"JDBC -> Quartz<br/>quartz.QRTZ_*"| Q
  T -->|"HTTP -> orchestrator<br/>launch"| O

  O -->|"JDBC -> batch_platform<br/>launch / task-state / retry / replay / worker-lifecycle"| P
  O -->|"Kafka -> batch.task.dispatch.*<br/>dispatch publish"| K

  K -->|"Kafka consume"| WD
  K -->|"Kafka consume"| WI
  K -->|"Kafka consume"| WE
  K -->|"Kafka consume"| WP

  WD -->|"HTTP -> orchestrator<br/>register / heartbeat / status / claim / renew / report"| O
  WI -->|"HTTP -> orchestrator<br/>register / heartbeat / status / claim / renew / report"| O
  WE -->|"HTTP -> orchestrator<br/>register / heartbeat / status / claim / renew / report"| O
  WP -->|"HTTP -> orchestrator<br/>register / heartbeat / status / claim / renew / report"| O

  WD -->|"JDBC -> batch_platform<br/>file_record / file_channel_config / file_dispatch_record"| P
  WI -->|"JDBC -> batch_business<br/>customer_account"| B
  WE -->|"JDBC -> batch_business<br/>settlement_batch / settlement_detail"| B
  WE -->|"S3 API -> MinIO<br/>export object write"| M
  WP -->|"JDBC -> batch_platform<br/>process_staging (WAP write/audit/publish)"| P
  WP -->|"JDBC -> batch_business<br/>SQL transform source/target tables"| B
```

## Orchestrator 写表细化图

```mermaid
flowchart LR
  T["batch-trigger"]
  W["workers<br/>dispatch / import / export"]
  C["batch-console-api"]
  O["batch-orchestrator"]
  P["batch_platform"]

  T -->|"HTTP /internal/orchestrator/launch"| O
  W -->|"HTTP /internal/workers/*<br/>register / heartbeat / status"| O
  W -->|"HTTP /internal/tasks/*<br/>claim / renew"| O
  W -->|"HTTP /internal/tasks/*<br/>report"| O
  C -->|"HTTP /internal/dead-letters/:id/replay"| O

  O -->|"launch path -><br/>read/update trigger_request +<br/>insert/update job_instance +<br/>insert/update workflow_run +<br/>insert workflow_node_run +<br/>insert job_task +<br/>insert outbox_event"| P

  O -->|"worker lifecycle path -><br/>upsert worker_registry"| P

  O -->|"claim / renew path -><br/>update job_task +<br/>update job_partition"| P

  O -->|"report path -><br/>update job_task +<br/>update job_partition +<br/>update job_instance +<br/>update workflow_run +<br/>insert/update workflow_node_run"| P

  O -->|"retry / dead-letter / replay path -><br/>insert retry_schedule or dead_letter_task +<br/>update dead_letter_task replay status +<br/>reset job_partition + reset job_task +<br/>insert outbox_event"| P
```

### 内部 Task HTTP 协议要点（联调用）
- `POST /internal/tasks/{taskId}/claim`：body 为 `TaskClaimRequest { tenantId, workerId }`；冲突/不存在任务时返回 `404/409`
- `POST /internal/tasks/{taskId}/renew`：body 为 `TaskClaimRequest { tenantId, workerId }`；续租失败返回 `409`
- `POST /internal/tasks/{taskId}/report`：body 为 `TaskExecutionReportDto`
  - `traceId` 用于串起 worker→orchestrator 的状态推进与审计日志（controller 层接收 body traceId 并回写到 trace 相关 log 字段）
  - `success=false` 且缺失 `errorCode/errorMessage` 时，服务端会落库兜底可观测错误信息（`UNKNOWN`）

## 读图要点

- `console-api` 既查库，也通过 HTTP 把触发和高危动作交给 `trigger` 或 `orchestrator`。
- `trigger` 自己维护 `batch.trigger_request` 和 Quartz 调度表，然后通过 HTTP 调 `orchestrator` 发起正式调度。
- `orchestrator` 是调度状态的收口点，负责维护 `job_instance`、`job_partition`、`job_task`、`workflow_run`、`workflow_node_run`、`worker_registry`、`retry_schedule`、`dead_letter_task`、`outbox_event` 等核心表。
- worker 不是直接从库里扫任务，而是先消费 Kafka，再通过 HTTP 回 `orchestrator` 做 `claim`、`heartbeat`、`renew`、`report`。
- worker 仍然会直连数据库，但直连的是执行阶段需要的业务表或文件类平台表，不直接接管调度状态主表。
- `batch-worker-export` 还会额外访问 MinIO，把导出产物写到对象存储。

## 对应代码

- worker 注册、心跳、状态更新：`batch-worker-core` 的 `HttpWorkerRegistryClient`
- worker 任务认领、续租、回报：`batch-worker-core` 的 `HttpTaskExecutionClient`
- trigger 调 orchestrator：`batch-trigger` 的 `HttpOrchestratorTriggerAdapter`
- launch 初始化运行态：`batch-orchestrator` 的 `DefaultLaunchService`
- task report / claim / renew：`batch-orchestrator` 的 `DefaultTaskExecutionService`
- retry / dead-letter / replay：`batch-orchestrator` 的 `DefaultRetryGovernanceService`
- orchestrator 出 Kafka：`TaskDispatchOutboxService` + `KafkaOutboxPublisher`
- export 写对象存储：`batch-worker-export` 的 `MinioExportStorage`
- console 查询平台表：`batch-console-api/src/main/resources/mapper/*.xml`
