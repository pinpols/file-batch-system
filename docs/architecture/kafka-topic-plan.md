# Kafka Topic 设计规范

## 主题命名

- `batch.task.dispatch.import`
- `batch.task.dispatch.export`
- `batch.task.dispatch.dispatch`
- `batch.task.result`
- `batch.task.retry`
- `batch.task.dead-letter`
- `batch.outbox.event`
- `batch.worker.heartbeat`

## Key 设计

- Dispatch topics: `tenantId:jobCode:instanceNo:jobPartitionId`
- Result topics: `tenantId:jobCode:instanceNo:taskId`
- Retry topics: `tenantId:jobCode:instanceNo:partitionId:attemptNo`
- Dead-letter topics: `tenantId:jobCode:instanceNo:partitionId:taskId`
- Outbox topics: `tenantId:eventName:aggregateType:aggregateId`
- Heartbeat topics: `tenantId:workerCode:workerGroup:workerId`

## 事件体字段

- `schemaVersion`
- `messageType`
- `tenantId`
- `jobCode`
- `instanceNo`
- `partitionId`
- `taskId`
- `requestId`
- `traceId`
- `idempotencyKey`
- `businessKey`
- `producer`
- `eventName`
- `topic`
- `key`
- `eventTime`
- `payload`
- `ext`

## 重试与死信

- Retry messages carry `attemptNo`, `maxAttempts`, `nextRetryAt`, and `retryReason`.
- Dead-letter messages carry `attemptNo`, `deadReason`, and `deadAt`.
- Retry exhaustion moves the message to dead-letter, not back to normal dispatch.

## 消息体结构（JSON 示例）

本项目当前采用 **JSON + `schemaVersion`** 的轻量方案（不依赖 Schema Registry）。

### Envelope（所有 topic 通用外层）

- `schemaVersion`: integer，消息体版本（向后兼容：仅新增可选字段；不删除/不改类型）
- `messageType`: string，消息类型（建议枚举：`TASK_DISPATCH` / `TASK_RESULT` / `TASK_RETRY` / `TASK_DEAD_LETTER` / `OUTBOX_EVENT` / `WORKER_HEARTBEAT`）
- `eventName`: string，事件名（可与 `messageType` 不同，用于细分）
- `eventTime`: ISO-8601 string（UTC），如 `2026-03-25T12:34:56Z`
- `tenantId`, `traceId`, `requestId`: string，用于多租户与链路追踪
- `topic`, `key`: string，生产者写入时的目标 topic 与最终 key（便于审计/回放）
- `idempotencyKey`: string，幂等键（见下方 Idempotency Key 章节）
- `producer`: object，生产者信息（服务名、实例等）
- `payload`: object，**内联 JSON 对象**（不是 JSON string）
- `ext`: object，可选扩展字段（灰度/调试/兼容）

示例（Envelope 形态）：

```json
{
  "schemaVersion": 1,
  "messageType": "TASK_RETRY",
  "eventName": "JOB_PARTITION_RETRY_SCHEDULED",
  "eventTime": "2026-03-25T12:34:56Z",
  "tenantId": "t1",
  "traceId": "trace-001",
  "requestId": "req-001",
  "topic": "batch.task.retry",
  "key": "t1:E2E_IMPORT_123:inst-001:4101:2",
  "idempotencyKey": "t1:JOB_PARTITION:4101:2",
  "producer": {
    "service": "batch-orchestrator",
    "instanceId": "orchestrator-1"
  },
  "payload": {},
  "ext": {}
}
```

### Retry Topic（`batch.task.retry`）消息体示例

用途：用于“重试调度/回放”链路的统一消息体规范（可用于回放、审计、下游治理）。

```json
{
  "schemaVersion": 1,
  "messageType": "TASK_RETRY",
  "eventName": "JOB_PARTITION_RETRY_SCHEDULED",
  "eventTime": "2026-03-25T12:34:56Z",
  "tenantId": "t1",
  "traceId": "trace-001",
  "requestId": "req-001",
  "topic": "batch.task.retry",
  "key": "t1:JOB_CODE:inst-001:4101:2",
  "idempotencyKey": "t1:JOB_PARTITION:4101:2",
  "producer": { "service": "batch-orchestrator", "instanceId": "orchestrator-1" },
  "payload": {
    "jobCode": "JOB_CODE",
    "instanceNo": "inst-001",
    "jobInstanceId": 4001,
    "partitionId": 4101,
    "taskId": 4201,
    "attemptNo": 2,
    "maxAttempts": 3,
    "retryPolicy": "FIXED",
    "nextRetryAt": "2026-03-25T12:35:56Z",
    "retryReason": {
      "errorCode": "IMPORT_VALIDATE_REQUIRED",
      "errorMessage": "Required fields missing",
      "source": "TASK_REPORT"
    },
    "dispatch": {
      "dispatchTopic": "batch.task.dispatch.import",
      "dispatchKey": "t1:JOB_CODE:inst-001:4101"
    }
  },
  "ext": {}
}
```

字段约束建议：
- `attemptNo` 从 1 开始递增；当 `attemptNo > maxAttempts` 时进入死信（见下节）
- `nextRetryAt` 用于审计/观测；实际调度以 DB 中 `retry_schedule.next_retry_at` 为准
- `dispatch.dispatchTopic/dispatchKey` 表示“重试成功后重新进入哪条 dispatch topic”

### Dead-letter Topic（`batch.task.dead-letter`）消息体示例

用途：重试耗尽、不可重试错误、或治理层拦截等“不可继续自动推进”的统一记录格式。

```json
{
  "schemaVersion": 1,
  "messageType": "TASK_DEAD_LETTER",
  "eventName": "JOB_PARTITION_DEAD_LETTERED",
  "eventTime": "2026-03-25T13:01:10Z",
  "tenantId": "t1",
  "traceId": "trace-001",
  "requestId": "req-001",
  "topic": "batch.task.dead-letter",
  "key": "t1:JOB_CODE:inst-001:4101:4201",
  "idempotencyKey": "t1:DEAD_LETTER:JOB_PARTITION:4101",
  "producer": { "service": "batch-orchestrator", "instanceId": "orchestrator-1" },
  "payload": {
    "jobCode": "JOB_CODE",
    "instanceNo": "inst-001",
    "jobInstanceId": 4001,
    "partitionId": 4101,
    "taskId": 4201,
    "attemptNo": 4,
    "maxAttempts": 3,
    "deadAt": "2026-03-25T13:01:10Z",
    "deadReason": {
      "reasonType": "RETRY_EXHAUSTED",
      "errorCode": "IMPORT_VALIDATE_REQUIRED",
      "errorMessage": "Required fields missing",
      "lastRetryAt": "2026-03-25T12:59:10Z"
    },
    "operator": {
      "operatorType": "SYSTEM",
      "operatorId": "batch-orchestrator"
    },
    "references": {
      "deadLetterTaskId": 9001,
      "retryScheduleId": 8001
    }
  },
  "ext": {}
}
```

### Serialization & Compatibility

- `payload` **必须为内联 object**（不要用 JSON string），便于日志检索与结构化消费。
- 版本兼容：`schemaVersion` 只做 **向后兼容**（新增可选字段）；类型变化需升版本并在消费端同时支持 N/N+1。

## 幂等键

- Trigger: `tenantId + jobCode + bizDate + requestId`
- Instance: `tenantId + jobCode + instanceNo`
- Partition: `tenantId + jobCode + instanceNo + partitionId`
- Worker task: `tenantId + jobCode + instanceNo + taskId`
- Dispatch: `fileId + targetSystem + dispatchVersion`
- Outbox: `tenantId + eventName + aggregateType + aggregateId`

## 待补充

以下内容尚未形成正式文档，需在上线前补全：

- **消息体 JSON schema**：已补充 retry / dead-letter 的完整 JSON 示例；后续可按同一 Envelope 规范补齐 dispatch/result/outbox/heartbeat 的 payload 细分字段
- **事件体序列化格式**：已明确 `payload` 为 inline object + `schemaVersion` 轻量版本兼容策略
- **Schema Registry 策略**（可选）：是否引入 Avro/Protobuf schema 注册，或维持 JSON + `schemaVersion` 字段的轻量方案
