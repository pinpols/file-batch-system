package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/** 完整来源图及逐节点证据；明确排除密钥参数。 */
@Builder
public record WorkflowRunReplayPreviewResponse(
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
      WorkflowReplayCapabilityService.Assessment capability,
      List<String> blockers) {}

  public record EdgePreview(
      String fromNodeCode, String toNodeCode, String edgeType, Boolean enabled) {}
}
