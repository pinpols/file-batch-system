package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.util.UUID;

/** Preview and admission contract for one exact failed Workflow JOB node. */
@SuppressWarnings("PMD.ExcessiveParameterList")
public record WorkflowNodeReplayCommand(
    String tenantId,
    Long workflowRunId,
    Long workflowNodeRunId,
    String reason,
    String requestedBy,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String traceId,
    UUID operationKey,
    String previewToken) {}
