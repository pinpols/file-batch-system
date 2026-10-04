package io.github.pinpols.batch.console.shared.view;

import java.time.Instant;

/** Orchestrator 按 pipeline 聚合的阶段进度；不再包含 workerCode 单槽身份。 */
public record ConsolePipelineProgressItemResponse(
    String stageCode, Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {}
