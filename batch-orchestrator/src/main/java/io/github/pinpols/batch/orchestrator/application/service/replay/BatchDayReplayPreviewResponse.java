package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 批次日重放影响预览：解析候选并签发短时凭证，不创建 session 或触发审批。 */
public record BatchDayReplayPreviewResponse(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    String executionMode,
    String candidateSource,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String previewToken,
    Instant expiresAt,
    int totalCount,
    List<PreviewEntry> entries,
    List<ResultVersionImpact> resultVersionImpacts,
    List<AssetPartitionImpact> assetPartitionImpacts,
    List<DispatchImpact> dispatchImpacts,
    List<String> warnings) {

  public record PreviewEntry(
      String jobCode,
      Long sourceInstanceId,
      Long resultVersionId,
      String action,
      String businessKey) {}

  public record ResultVersionImpact(
      String businessKey,
      Long sourceInstanceId,
      Long resultVersionId,
      String action,
      String resultPolicy) {}

  public record AssetPartitionImpact(
      String businessKey,
      String assetCode,
      String partitionKey,
      Long currentResultVersionId,
      String freshnessStatus) {}

  public record DispatchImpact(
      Long sourceInstanceId,
      long recordCount,
      long sentCount,
      long failedCount,
      long pendingReceiptCount) {}
}
