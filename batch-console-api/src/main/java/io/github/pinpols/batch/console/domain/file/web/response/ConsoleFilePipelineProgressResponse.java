package io.github.pinpols.batch.console.domain.file.web.response;

import java.util.List;

public record ConsoleFilePipelineProgressResponse(
    Long pipelineInstanceId,
    Long fileId,
    String fileName,
    List<ConsoleFilePipelineStepProgressResponse> steps) {}
