package com.example.batch.worker.core.support;

/**
 * 单次阶段执行的不可变上下文快照。
 *
 * <p>写入 MDC，使阶段执行期间产生的每条日志自动包含关联字段（tenantId / jobInstanceId / taskId / stage / workerId）。
 */
public record StageExecutionContext(
    String tenantId, String jobInstanceId, String taskId, String stage, String workerId) {}
