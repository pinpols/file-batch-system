package com.example.batch.console.domain.file.web.response;

public record ConsoleFilePipelineStepProgressResponse(
    Long stepId,
    Long pipelineInstanceId,
    String stepCode,
    String stageCode,
    Long rowsProcessed,
    Long totalRowsHint,
    Long lastHeartbeatAt) {}
