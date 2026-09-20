package io.github.pinpols.batch.console.domain.file.application.contract.response;

import java.util.List;

public record ConsoleFilePipelineProgressResponse(
    Long pipelineInstanceId,
    Long fileId,
    String fileName,
    List<ConsoleFilePipelineStepProgressResponse> steps) {}
