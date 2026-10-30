package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;
import lombok.Builder;

/** 文件流水线步骤查询的固定列投影。 */
@Builder
public record FilePipelineStepRunQueryView(
    Long id,
    Long pipelineInstanceId,
    String stepCode,
    String stageCode,
    Integer runSeq,
    String stepStatus,
    Object inputSummary,
    Object outputSummary,
    String errorCode,
    String errorMessage,
    String errorKey,
    Object errorArgs,
    Integer retryCount,
    Long durationMs,
    Instant startedAt,
    Instant finishedAt) {}
