package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.time.LocalDate;
import java.util.List;

/** 批次日重放影响预览：只读解析候选 entry,不创建 session,不触发审批。 */
public record BatchDayReplayPreviewResponse(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
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
