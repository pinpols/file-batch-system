# Kafka Topic Plan

## Topic Naming

- `batch.task.dispatch.import`
- `batch.task.dispatch.export`
- `batch.task.dispatch.dispatch`
- `batch.task.result`
- `batch.task.retry`
- `batch.task.dead-letter`
- `batch.outbox.event`
- `batch.worker.heartbeat`

## Key Design

- Dispatch topics: `tenantId:jobCode:instanceNo:partitionId`
- Result topics: `tenantId:jobCode:instanceNo:taskId`
- Retry topics: `tenantId:jobCode:instanceNo:partitionId:attemptNo`
- Dead-letter topics: `tenantId:jobCode:instanceNo:partitionId:taskId`
- Outbox topics: `tenantId:eventName:aggregateType:aggregateId`
- Heartbeat topics: `tenantId:workerCode:workerGroup:workerId`

## Event Body

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

## Retry and Dead Letter

- Retry messages carry `attemptNo`, `maxAttempts`, `nextRetryAt`, and `retryReason`.
- Dead-letter messages carry `attemptNo`, `deadReason`, and `deadAt`.
- Retry exhaustion moves the message to dead-letter, not back to normal dispatch.

## Idempotency Key

- Trigger: `tenantId + jobCode + bizDate + requestId`
- Instance: `tenantId + jobCode + instanceNo`
- Partition: `tenantId + jobCode + instanceNo + partitionId`
- Worker task: `tenantId + jobCode + instanceNo + taskId`
- Dispatch: `fileId + targetSystem + dispatchVersion`
- Outbox: `tenantId + eventName + aggregateType + aggregateId`
