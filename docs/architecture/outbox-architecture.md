# Outbox 子系统总览 — 主表与副表关系

> **结论先行**：outbox 子系统投递主线由 1 张主表（`outbox_event`）+ 2 张副表（`event_outbox_retry` / `event_delivery_log`）+ 1 张兄弟表（`trigger_outbox_event`，**复用 outbox 模式但字段名独立**：`request_id` 代替 `event_key`、`payload` 代替 `payload_json`）组成，另有全局去重台账维持跨时间分区的唯一身份。本文档**只讲表与表之间的边界、归属和 FK 关系**；状态机、调度、退避、归档等运行时细节看 [ADR-002 §当前状态](adr/ADR-002-transactional-outbox.md)。

---

## 1. 投递表与去重台账

V182 的 `outbox_event_dedup_key(tenant_id,event_key)` 是全局身份裁决者，Mapper 在同事务先 `ON CONFLICT DO NOTHING RETURNING` 认领键，再仅为认领成功者写分区事件。事件表带时间分区键的局部唯一约束不能独自替代跨分区幂等。`job_instance_dedup_key` 采用相同边界处理作业身份，见[分区幂等](../design/partition-idempotency-decision.md)。台账保留/归档按业务重投窗口治理，不能为清理空间先删键后留可重投消息。

### 投递关系图

```mermaid
erDiagram
  outbox_event ||--o{ event_outbox_retry : "tenant_id + id + created_at (FK)"
  outbox_event ||--o{ event_delivery_log : "tenant_id + id + created_at (FK)"

  outbox_event {
    bigint id PK
    string tenant_id
    timestamp created_at "分区键"
    string aggregate_type
    bigint aggregate_id
    string event_type
    string event_key "全局唯一由去重台账裁决"
    jsonb payload_json
    string publish_status "5态: NEW/PUBLISHING/PUBLISHED/FAILED/GIVE_UP"
    int publish_attempt
    timestamp next_publish_at
  }

  event_outbox_retry {
    bigint id PK
    string tenant_id FK
    bigint outbox_event_id FK
    timestamp outbox_event_created_at FK "父分区键"
    string event_key
    int retry_attempt
    string retry_status
    string retry_reason
    timestamp next_retry_at
  }

  event_delivery_log {
    bigint id PK
    string tenant_id FK
    bigint outbox_event_id FK
    timestamp outbox_event_created_at FK "父分区键"
    string topic
    int partition
    long offset
    int latency_ms
  }

  trigger_outbox_event {
    bigint id PK
    string tenant_id
    string request_id UK "tenant_id+request_id 防双写"
    string topic "默认 batch.trigger.launch.v1"
    jsonb payload
    string publish_status "5态 同 outbox_event"
    int publish_attempt
    string last_error
    string trace_id
    timestamp next_publish_at
    timestamp published_at
  }
```

---

## 2. 各表职责

| 表 | 角色 | 写入时机 | 读取时机 | 归档 |
|---|---|---|---|---|
| `outbox_event` | **主表** — 业务事件待发队列 | 业务事务里 `TaskDispatchOutboxService.writeDispatchEvent()` 同事务写 | `OutboxPollScheduler` 周期 `selectPending()` | PUBLISHED / GIVE_UP cutoff 后归 `archive.outbox_event_archive` |
| `event_outbox_retry` | **副表** — 失败重试元数据，每次失败一行 | `DefaultScheduleForwarder.advance()` 第三阶段由 `OutboxChildRecordService` 写入 | console-api 排障查询、metrics gauge | 随主表归档；父事件已冷归档时，迟到审计写入冷 retry 表 |
| `event_delivery_log` | **副表** — 投递审计日志，每次 send 一行（成功 / 失败都记） | Kafka send 完成后由 `OutboxChildRecordService` 写入 | 审计、链路追踪、回放 | 随主表归档；父事件已冷归档时，迟到回调写入冷 delivery 表 |
| `trigger_outbox_event` | **兄弟表** — trigger → orchestrator 异步 launch 链路专用 | trigger 模块 `TriggerOutboxRelay` 写入 | orchestrator 模块 `TriggerLaunchConsumer` 消费 | 独立归档路径 |

---

## 3. 主表 → 副表的复合外键关系

### 3.1 `outbox_event_id` 是关键

V172 将 `outbox_event` 改为按 `created_at` 分区后，旧子表缺少分区键，原生 FK 被移除。V306 为两张热子表新增 `outbox_event_created_at`，并建立以下 `NO ACTION` 复合 FK：

```text
(tenant_id, outbox_event_id, outbox_event_created_at)
  -> outbox_event(tenant_id, id, created_at)
```

父端唯一键包含分区键，外键同时保证租户归属和具体分区父行存在。应用 Mapper 以同一三列身份定位父行并 `FOR KEY SHARE`；数据库外键负责直接 SQL 和并发删除的最终拒绝。应用前置校验、外键字段及错误处理必须保持这套身份一致。

**生命周期约束**：
- 归档器先以 `FOR UPDATE SKIP LOCKED` 锁定候选父行，再在同一事务复制子行和父行，随后先删除两个热子表、最后删除父行；数据库 `NO ACTION` FK 会阻止不完整归档
- 父行归档期间开始的应用子写会等待父行锁。父行提交到冷表后，`OutboxChildRecordService` 按同一三列身份将迟到 delivery/retry 写到对应冷子表；冷父子不存在时失败并报告完整性错误
- `cleanup-outbox-events.sql` 按同一三列身份和保留窗口先删除 `event_delivery_log`、`event_outbox_retry`，再删除父事件；清理不是归档
- 热子表不能引用缺失或异租户热父行；冷表的迟到记录必须对应已冷归档父行

**启动守卫**：`ArchiveSchemaDriftCheck` 校验冷表结构；热态父子关系由 PostgreSQL 复合 FK 持续保证。

### 3.2 retry vs delivery_log 的区别

容易混的两张副表，按这条线分：

| | `event_outbox_retry` | `event_delivery_log` |
|---|---|---|
| 写入触发 | **失败** 才写（markFailed 时） | **每次 send** 都写（不管成败） |
| 用途 | 排障："这条事件为什么没投出去" | 审计：&nbsp;"这条事件实际投了几次、什么时间、到哪个 topic / partition / offset" |
| 字段重点 | `retry_attempt` / `retry_status` / `retry_reason` / `next_retry_at` | `topic` / `partition` / `offset` / `latency_ms` |
| 行数与主表关系 | 每次失败 +1（GIVE_UP 时累计 5 行） | 每次 send +1（成功 1 行 / 失败 + 重试 5 行 = 最多 5 行） |

**简记**：
- **retry = 失败原因表**（why did it fail, when next）
- **delivery_log = 投递事实表**（when, where, how fast）

---

## 4. `trigger_outbox_event` 为什么独立成表

历史决策（V80, ADR-010）：trigger → orchestrator 异步 launch 链路与业务事件 outbox 完全隔离：

| 维度 | `outbox_event` | `trigger_outbox_event` |
|---|---|---|
| 写入方 | orchestrator（业务事务）| trigger 模块（fire 事务）|
| 消费方 | worker（dispatch / retry / reclaim 等多种 event）| orchestrator `TriggerLaunchConsumer` 单一消费者 |
| Kafka topic | 多（`BatchTopicResolver` 按 eventType 解析）| 固定 `batch.trigger.launch.v1` |
| 归档策略 | 与业务事件归档一致 | 独立 cutoff |
| 表 schema | `event_key` / `payload_json` / `aggregate_*` | `request_id` / `payload`（无 aggregate 列，复用模式但字段名独立）|

**为什么不复用同一张表**：trigger 链路的 SLA / 流量 / 故障模式与业务事件不同；混在一张表 polling 时 trigger 事件可能被业务事件的积压拖累。物理隔离 = 故障域隔离。

---

## 5. 归档同步性

`OutboxArchiveService` 一次归档动作覆盖三张热表：

```text
archive.outbox_event_archive          ← 主表行
archive.event_outbox_retry_archive    ← 关联 retry 行（按完整复合身份）
archive.event_delivery_log_archive    ← 关联 delivery_log 行（按完整复合身份）
```

三步在同一事务里完成，失败回滚。`ArchiveColdStorageIntegrationTest` 守护这一约束。

---

## 6. 何时该看哪张表

| 排障场景 | 先看哪张 |
|---|---|
| "这条事件为什么 30 分钟还没发出去" | `outbox_event.publish_status` + `next_publish_at`，再看 `event_outbox_retry` 历史失败原因 |
| "这条事件实际有没有发到 Kafka" | `event_delivery_log`（按 `outbox_event_id` 查）|
| "为什么 worker 收到 2 条相同事件" | 看 `event_delivery_log` 是否真发了 2 次（可能是 markPublishing CAS 失败后又重投）|
| "GIVE_UP 的事件能不能重发" | console-api `/internal/outbox/republish` 接口（FAILED / GIVE_UP → NEW），背后改的是 `outbox_event` |
| "trigger 触发为什么没到 orchestrator" | 看 `trigger_outbox_event`，**不是** `outbox_event` |

---

## 7. 相关文档

- [ADR-002 transactional-outbox](adr/ADR-002-transactional-outbox.md) — 决策追溯 + 当前状态（状态机、调度、退避、归档全细节）
- [ADR-010 trigger 异步解耦](adr/ADR-010-trigger-async-decoupling.md) — `trigger_outbox_event` 独立表的决策
- [docs/runbook/feature-switches.md](../runbook/feature-switches.md) — outbox 相关开关
- [docs/agent-baseline.md §架构硬约束](../agent-baseline.md) — outbox_event 同事务约束、console-api 不能直接 UPDATE/DELETE 等
