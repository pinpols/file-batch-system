# 异步事件路由政策

> **status**: 固化（2026-05-03）  
> **scope**: 三张异步事件表的职责边界、写入路径、消费 topic、新事件类型选型规则

## 1. 三表概览

| 表 | 用途 | 状态字段 | 写入方 | 关联 topic |
|---|---|---|---|---|
| `batch.outbox_event` | 通用业务事件（订单 / 配置变更 / 通知 / 审批） | `publish_status` ∈ `OutboxPublishStatus` | `orchestrator` + business domain service | `batch.event.*`（多 topic） |
| `batch.event_outbox_retry` | `outbox_event` 投递失败的退避重试队列 | `retry_status` ∈ {WAITING, RUNNING, SUCCESS, FAILED, EXHAUSTED, CANCELLED} | `OutboxPollScheduler`（内部转移） | 同上（重投到原 topic） |
| `batch.trigger_outbox_event`（V80） | trigger fire → orchestrator launch 的调度事件 | `publish_status` ∈ `OutboxPublishStatus` | `batch-trigger`（与 trigger_request 同事务） | `batch.trigger.launch.v1`（固定单 topic） |

## 2. 设计原则

### 2.1 为什么要拆三张表

历史上"一张大 outbox 包打天下"的方案曾被讨论，但最终拆分原因：

1. **事件量级差异**：trigger fire 是高频事件（生产 95%+ 是定时器触发，每秒可能数千），与业务事件（每秒几十）混在一张表会让 orchestrator 端 outbox 扫描压力陡增。
2. **消费方差异**：trigger_outbox 只有一个消费方（orchestrator TriggerLaunchConsumer），而 outbox_event 有多个 topic 多个消费方。混在一起的路由元数据会让 schema 变臃肿。
3. **关联表差异**：trigger_outbox_event 与 `trigger_request` 同事务（一对一），outbox_event 与状态机表（job_instance / workflow_run）同事务（一对多）。事务边界拆开避免互相影响。
4. **重试策略差异**：trigger 失败靠退避重发即可（trigger_request 自带 dedup_key 回退），不需要单独的 retry 队列；业务事件失败要走 retry → DLQ → 人工 replay 全套流程。

### 2.2 不变量

- **trigger_outbox_event**:
  - `(tenant_id, request_id)` UNIQUE（V83 改成 CONSTRAINT，原 V80 是 UNIQUE INDEX）
  - 必须与 `trigger_request` 同事务写入
  - publish_status 状态机：NEW → PUBLISHING → PUBLISHED；失败走 NEW → PUBLISHING → FAILED → ...（带退避）→ GIVE_UP

- **outbox_event**:
  - 必须与业务状态变更同事务（`outbox_event 必须与任务状态写入处于同一事务` —— CLAUDE.md §架构硬约束）
  - publish_status 同上
  - retry 路径走 event_outbox_retry，主表 outbox_event 不直接做退避

- **event_outbox_retry**:
  - 仅由 `OutboxPollScheduler` 写入（投递失败时从 outbox_event 派生）
  - 业务代码**禁止**直接 INSERT
  - retry_status 终态 EXHAUSTED 后转入 `dead_letter_task`，由 console-api 触发人工 replay

## 3. 写入流程

### 3.1 trigger_outbox_event（ADR-010 异步链路）

```
trigger fire
  ↓
DefaultTriggerService.persistAndForward(...) [@Transactional]
  ├─ INSERT trigger_request (status=ACCEPTED)
  └─ INSERT trigger_outbox_event (status=NEW, payload=LaunchEnvelope JSON)
  ↓
TriggerOutboxRelay (200ms 周期, ShedLock 互斥)
  ↓
KafkaTriggerEventPublisher.publish(topic="batch.trigger.launch.v1")
  ↓
orchestrator TriggerLaunchConsumer.consume(envelope)
  ↓
LaunchApplicationService.launch(launchRequest) → INSERT job_instance + outbox_event(同事务)
```

**幂等回退**：
- `(tenant_id, request_id)` UNIQUE（trigger_outbox_event 重发拒绝）
- `uk_job_instance_tenant_dedup`（orchestrator 端回退）
- consumer 收到 409 CONFLICT 视为 ack 成功

### 3.2 outbox_event（通用业务事件）

```
business domain service.someOperation() [@Transactional]
  ├─ UPDATE business state（如 INSERT job_instance）
  └─ INSERT outbox_event (status=NEW, eventType=..., payload=...)
  ↓
OutboxPollScheduler (200ms 周期, ShedLock + sharding)
  ↓
RelayKafkaPublisher.publish(topic 由 eventType 路由)
  ├─ 成功 → UPDATE outbox_event SET status=PUBLISHED
  └─ 失败 → INSERT event_outbox_retry (status=WAITING, retry_at=now+backoff)
  ↓ (retry path)
OutboxRetryScheduler 扫 event_outbox_retry
  ├─ 成功 → 删除 retry 行
  └─ retry_count 耗尽 → INSERT dead_letter_task；event_outbox_retry status=EXHAUSTED
```

## 4. 新事件类型选型决策树

```
发送新的异步事件
  ↓
是不是 trigger fire（定时 / 手动 / API）→ orchestrator launch？
  │
  ├─ 是 → 用 trigger_outbox_event（已有 LaunchEnvelope schema 回退，新增 trigger_type 即可）
  │
  └─ 否 → 用 outbox_event
           │
           是不是已有 eventType 可复用？
             │
             ├─ 是 → 直接复用（修 eventType handler 即可）
             │
             └─ 否 → 新增 eventType + 新 Kafka topic（按 BatchTopics 命名约定）
```

**禁止**：
- 新事件不允许新建第 4 张 outbox 同义表（如 "config_change_outbox" / "audit_outbox"）—— 走 outbox_event + 新 eventType
- 业务代码不允许直接 INSERT event_outbox_retry（retry 是基础设施职责，由 scheduler 内部转移）
- trigger_outbox_event 不允许加业务字段（如 priority / job_type 等业务路由）—— 路由信息塞在 LaunchEnvelope.launchRequest 里

## 5. 监控与运维

| 指标 | 表 | 告警阈值 |
|---|---|---|
| `batch.outbox.publish.duration` | outbox_event | p99 > 5s |
| `batch.outbox.publish.failed.total` | outbox_event | rate > 0.1/s |
| `batch.outbox.giveup.total` | outbox_event / trigger_outbox_event | > 0（任何 GIVE_UP 都告警） |
| `batch.trigger.launch.consumed.total` | trigger_outbox_event 消费侧 | rate < 预期定时器触发数 |
| `batch.trigger.launch.deduped.total` | TriggerLaunchConsumer | rate 突增 = 上游重投异常 |

详见 `docker/observability/prometheus-batch-rules.yml`。

## 6. 历史演进

| 时间 | 事件 |
|---|---|
| V21 | 创建 `outbox_event` + `event_outbox_retry`（ADR-002 transactional outbox 落地） |
| V80（2026-04-30） | 创建 `trigger_outbox_event`（ADR-010 trigger 异步解耦，独立表避免与业务 outbox 抢 scheduler 资源） |
| V83（2026-05-03） | trigger_outbox_event UNIQUE INDEX → CONSTRAINT，对齐 SQL 标准 |
| 2026-05-02 | ADR-010 同步 HTTP 桥（HttpOrchestratorTriggerAdapter）删除，trigger_outbox_event 成为唯一路径 |

详见 `docs/architecture/adr/ADR-002-transactional-outbox.md` + `docs/architecture/adr/ADR-010-trigger-async-decoupling.md`。
