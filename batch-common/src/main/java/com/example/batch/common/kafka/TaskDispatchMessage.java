package com.example.batch.common.kafka;

import java.time.Instant;

public record TaskDispatchMessage(
    String schemaVersion,
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    Long taskId,
    String instanceNo,
    String jobCode,
    String taskType,
    Integer taskSeq,
    String workerType,
    String selectedWorkerId,
    String priorityBand,
    String businessKey,
    String payload,
    String traceId,
    String idempotencyKey,
    Instant dispatchAt,
    /**
     * 增量执行模式(ExecutionMode.INCREMENTAL)的水位起点。orchestrator 派发时从上次成功实例的 high_water_mark_out
     * 读出;FULL/CDC 模式下保持 null。worker 在 INCREMENTAL 业务逻辑里 读它拼 SQL,例 `WHERE update_time >
     * :highWaterMarkIn`。
     *
     * <p>v1 schema 中加该字段对旧 worker 透明:Jackson 反序列化时未知/缺失字段被忽略, 旧 worker 不读取就当不存在;新 worker 读到 null
     * 时按"从头跑"处理。
     */
    String highWaterMarkIn) {}
