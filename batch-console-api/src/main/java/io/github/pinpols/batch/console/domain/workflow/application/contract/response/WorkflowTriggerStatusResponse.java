package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;

/** 原触发请求的持久化事实；存在请求不等于业务运行已成功。 */
public record WorkflowTriggerStatusResponse(
    String tenantId,
    Long workflowDefinitionId,
    LocalDate bizDate,
    String requestId,
    String traceId,
    String requestStatus,
    Instant updatedAt) {}
