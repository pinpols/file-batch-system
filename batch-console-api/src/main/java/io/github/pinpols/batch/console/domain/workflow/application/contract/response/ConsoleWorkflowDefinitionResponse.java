package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import java.time.Instant;
import lombok.Builder;

@Builder
public record ConsoleWorkflowDefinitionResponse(
    Long id,
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    String scheduleType,
    String scheduleExpr,
    String timezone,
    String triggerMode,
    String calendarCode,
    String dependsOnJobCode,
    Integer version,
    Boolean enabled,
    Boolean scheduleEnabled,
    String description,
    Instant createdAt,
    Instant updatedAt,
    Long singleJobDefinitionId) {}
