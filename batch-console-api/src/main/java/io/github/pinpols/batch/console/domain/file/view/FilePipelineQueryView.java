package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;

/** 文件流水线查询使用的固定列投影。 */
public record FilePipelineQueryView(
    Long id,
    String tenantId,
    String jobCode,
    String pipelineType,
    Long fileId,
    Long relatedJobInstanceId,
    String currentStage,
    String lastSuccessStage,
    String runStatus,
    String traceId,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {}
