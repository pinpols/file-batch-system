package io.github.pinpols.batch.orchestrator.application.service.replay;

import lombok.Builder;
import java.util.UUID;

/** ADR-049 整 Workflow 重放身份与显式选定的策略。 */
@Builder(toBuilder = true)
public record WorkflowRunReplayCommand(
    String tenantId,
    Long workflowRunId,
    String reason,
    String requestedBy,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String traceId,
    UUID operationKey,
    String previewToken) {}
