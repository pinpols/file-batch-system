package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;

/** pipeline 进度脏数据扫描结果。 */
public record PipelineProgressDirtyView(
    String tenantId, Long pipelineInstanceId, Long jobInstanceId, Instant updatedAt) {}
