package com.example.batch.console.web.response;

import java.time.Instant;
import java.util.List;

public record PipelineDefinitionDetailResponse(
        Long id,
        String tenantId,
        String jobCode,
        String pipelineName,
        String pipelineType,
        String bizType,
        String workerGroup,
        Integer version,
        Boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt,
        List<StepResponse> steps
) {
    public record StepResponse(
            Long id,
            Long pipelineDefinitionId,
            String stepCode,
            String stepName,
            String stageCode,
            Integer stepOrder,
            String implCode,
            String stepParams,
            Integer timeoutSeconds,
            String retryPolicy,
            Integer retryMaxCount,
            Boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
