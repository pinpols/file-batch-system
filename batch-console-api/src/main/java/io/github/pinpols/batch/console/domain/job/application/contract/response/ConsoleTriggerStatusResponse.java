package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;

/** 触发器注册状态的固定传输契约，租户过滤直接使用 tenantId。 */
public record ConsoleTriggerStatusResponse(
    String tenantId,
    String jobCode,
    String scheduleType,
    String scheduleExpression,
    String timezone,
    String triggerMode,
    String status,
    Instant previousFireTime,
    Instant nextFireTime) {}
