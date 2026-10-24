package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;

/** 文件流水线步骤持久化进度查询投影。 */
public record FilePipelineProgressStepView(
    Long stepId,
    Long pipelineInstanceId,
    String stepCode,
    String stageCode,
    String stepStatus,
    Long rowsProcessed,
    Long totalRowsHint,
    Instant lastHeartbeatAt) {}
