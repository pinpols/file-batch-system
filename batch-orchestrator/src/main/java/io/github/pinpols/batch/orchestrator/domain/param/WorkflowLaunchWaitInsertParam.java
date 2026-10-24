package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.LocalDate;

public record WorkflowLaunchWaitInsertParam(
    String tenantId,
    String requestId,
    Long workflowDefinitionId,
    LocalDate bizDate,
    boolean dryRun,
    String launchPayload) {}
