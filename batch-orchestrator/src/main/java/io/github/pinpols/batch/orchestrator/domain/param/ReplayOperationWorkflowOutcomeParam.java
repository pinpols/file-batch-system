package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 重放操作目标 Workflow 请求的一次终结或拒绝结果。 */
@Builder
public record ReplayOperationWorkflowOutcomeParam(
    String tenantId,
    String requestId,
    Long workflowRunId,
    String status,
    int succeededCount,
    int failedCount,
    int inFlightCount,
    Instant now) {}
