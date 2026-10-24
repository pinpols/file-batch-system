package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 日期区间重放的冻结影响范围与一次性提交凭证。 */
public record ReplayOperationPreviewResponse(
    String tenantId,
    UUID operationKey,
    String operationType,
    String calendarCode,
    LocalDate fromDate,
    LocalDate throughDate,
    String scope,
    int totalCandidateCount,
    String previewToken,
    Instant expiresAt,
    List<DayPreview> days) {

  public record DayPreview(
      LocalDate bizDate,
      String disposition,
      int candidateCount,
      List<BatchDayReplayPreviewResponse.PreviewEntry> entries,
      List<BatchDayReplayPreviewResponse.ResultVersionImpact> resultVersionImpacts,
      List<BatchDayReplayPreviewResponse.AssetPartitionImpact> assetPartitionImpacts,
      List<BatchDayReplayPreviewResponse.DispatchImpact> dispatchImpacts,
      List<String> warnings) {}
}
