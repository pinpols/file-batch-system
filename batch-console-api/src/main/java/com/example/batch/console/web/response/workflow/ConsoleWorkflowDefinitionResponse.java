package com.example.batch.console.web.response.workflow;

import java.time.Instant;

public record ConsoleWorkflowDefinitionResponse(
    Long id,
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
