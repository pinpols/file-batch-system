package com.example.batch.console.web.response;

import java.time.Instant;
import java.util.List;

public record WorkflowDefinitionDetailResponse(
        Long id,
        String tenantId,
        String workflowCode,
        String workflowName,
        String workflowType,
        Integer version,
        Boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt,
        List<ConsoleWorkflowNodeResponse> nodes,
        List<ConsoleWorkflowEdgeResponse> edges) {}
