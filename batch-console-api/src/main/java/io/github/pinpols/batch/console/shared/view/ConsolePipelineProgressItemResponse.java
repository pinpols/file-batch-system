package io.github.pinpols.batch.console.shared.view;

import java.time.Instant;
import java.util.List;

/** Orchestrator 按 pipeline 聚合的阶段进度；不再包含 workerCode 单槽身份。 */
public record ConsolePipelineProgressItemResponse(
    String stageCode,
    Long rowsProcessed,
    Long totalRowsHint,
    Instant heartbeatAt,
    List<Long> stepRunIds) {
  public ConsolePipelineProgressItemResponse(
      String stageCode, Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {
    this(stageCode, rowsProcessed, totalRowsHint, heartbeatAt, List.of());
  }
}
