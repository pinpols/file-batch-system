package io.github.pinpols.batch.console.domain.file.application.contract.response;

public record ConsoleFilePipelineStepProgressResponse(
    Long stepId,
    Long pipelineInstanceId,
    String stepCode,
    String stageCode,
    Long rowsProcessed,
    Long totalRowsHint,
    Long lastHeartbeatAt) {}
