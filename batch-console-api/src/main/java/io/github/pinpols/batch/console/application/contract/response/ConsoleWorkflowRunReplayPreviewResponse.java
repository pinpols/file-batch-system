package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleWorkflowReplayCapabilityAssessment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 整 Workflow 重放的公开预览；密钥参数和内部快照摘要保留在服务端。 */
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
