package io.github.pinpols.batch.console.application.contract.response.ops;

import java.time.Instant;

/** Workflow 调度注册状态的固定传输契约，租户过滤直接使用 tenantId。 */
public record ConsoleTriggerStatusResponse(
    String tenantId,
    String workflowCode,
    String scheduleType,
    String scheduleExpression,
    String timezone,
    String triggerMode,
    String status,
    Instant previousFireTime,
    Instant nextFireTime) {}
