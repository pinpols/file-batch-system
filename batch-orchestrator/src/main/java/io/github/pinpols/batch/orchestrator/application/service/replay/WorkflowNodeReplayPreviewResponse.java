package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 冻结的来源身份、策略、副作用警示与一次性准入凭证。 */
public record WorkflowNodeReplayPreviewResponse(
    String tenantId,
    UUID operationKey,
    Long sourceWorkflowRunId,
    Long sourceWorkflowNodeRunId,
    String sourceNodeCode,
    Integer sourceNodeRunSeq,
    Long sourceJobInstanceId,
    String jobCode,
    LocalDate bizDate,
    Integer sourceConfigVersion,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String previewToken,
    Instant expiresAt,
    List<String> warnings) {}
