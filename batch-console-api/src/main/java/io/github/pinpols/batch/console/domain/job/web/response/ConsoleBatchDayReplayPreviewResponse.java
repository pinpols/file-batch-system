package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.LocalDate;
import java.util.List;

/** ADR-020 批次日重放影响预览响应（console 透传 orchestrator {@code BatchDayReplayPreviewResponse} 的 JSON 投影）。 */
public record ConsoleBatchDayReplayPreviewResponse(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    Integer totalCount,
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
