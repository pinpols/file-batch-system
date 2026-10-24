package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public whole Workflow replay preview; secret parameters and internal snapshot hashes stay server-side. */
public record ConsoleWorkflowRunReplayPreviewResponse(
    String tenantId,
    Long sourceWorkflowRunId,
    Long workflowDefinitionId,
    Integer workflowVersion,
    LocalDate bizDate,
    String calendarCode,
    Instant dataIntervalStart,
    Instant dataIntervalEnd,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    UUID operationKey,
    String previewToken,
    Instant expiresAt,
    List<NodePreview> nodes,
    List<EdgePreview> edges) {

  public record NodePreview(
      String nodeCode,
      String nodeType,
      Boolean enabled,
      String jobCode,
      Long jobDefinitionId,
      Integer jobDefinitionVersion,
      String workerGroup,
      String pluginId,
      ConsoleWorkflowReplayCapabilityAssessment capability,
      List<String> blockers) {}

  public record EdgePreview(
      String fromNodeCode, String toNodeCode, String edgeType, Boolean enabled) {}
}
