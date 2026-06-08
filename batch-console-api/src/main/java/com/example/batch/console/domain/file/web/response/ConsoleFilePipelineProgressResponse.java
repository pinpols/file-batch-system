package com.example.batch.console.domain.file.web.response;

import java.util.List;

public record ConsoleFilePipelineProgressResponse(
    Long pipelineInstanceId, List<ConsoleFilePipelineStepProgressResponse> steps) {}
