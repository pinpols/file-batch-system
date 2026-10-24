package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ADR-049 date-range preview impact. */
public record ConsoleReplayOperationPreviewResponse(
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
      List<ConsoleBatchDayReplayPreviewResponse.PreviewEntry> entries,
      List<ConsoleBatchDayReplayPreviewResponse.ResultVersionImpact> resultVersionImpacts,
      List<ConsoleBatchDayReplayPreviewResponse.AssetPartitionImpact> assetPartitionImpacts,
      List<ConsoleBatchDayReplayPreviewResponse.DispatchImpact> dispatchImpacts,
      List<String> warnings) {}
}
