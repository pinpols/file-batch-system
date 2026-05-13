package com.example.batch.orchestrator.application.service.sensor;

import java.time.Duration;
import java.util.Map;

/**
 * ADR-028 sensor 探测上下文。由 {@code SensorPollScheduler} 在 S3 阶段填充并传给 {@link SensorPolicy#probe}。
 *
 * @param tenantId 当前 workflow_node_run 的租户
 * @param workflowNodeRunId 当前 WAIT 节点运行 id（用于幂等 / 日志 trace）
 * @param sensorSpec node_params.sensor_spec JSONB 反序列化结果
 * @param workflowRunVars workflow_run 级共享字段（bizDate / traceId 等），用于 sensor_spec 占位符替换
 * @param timeRemaining = timeout_seconds - elapsed，&lt;= 0 时 scheduler 先于 probe 标 TIMEOUT
 */
public record SensorContext(
    String tenantId,
    Long workflowNodeRunId,
    Map<String, Object> sensorSpec,
    Map<String, Object> workflowRunVars,
    Duration timeRemaining) {}
