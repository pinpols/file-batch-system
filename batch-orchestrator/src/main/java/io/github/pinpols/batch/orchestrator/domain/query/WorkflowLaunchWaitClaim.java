package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.LocalDate;

/** 已领取的持久化 Workflow 启动意图，此时根运行尚未准入。 */
public record WorkflowLaunchWaitClaim(
    String tenantId,
    String requestId,
    Long workflowDefinitionId,
    LocalDate bizDate,
    Boolean dryRun,
    String launchPayload) {}
