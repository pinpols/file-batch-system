package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
@Builder
public record WorkflowDefinitionDetailResponse(
    Long id,
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Long singleJobDefinitionId,
    String scheduleType,
    String scheduleExpr,
    String timezone,
    String triggerMode,
    String calendarCode,
    String dependsOnJobCode,
    Integer version,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt,
    List<ConsoleWorkflowNodeResponse> nodes,
    List<ConsoleWorkflowEdgeResponse> edges) {}
