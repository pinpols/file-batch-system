package com.example.batch.worker.core.domain;

import java.util.Map;

public record PipelineStepTemplate(
        String stepCode,
        String stepName,
        String stageCode,
        Integer stepOrder,
        String implCode,
        Map<String, Object> stepParams,
        Integer timeoutSeconds,
        String retryPolicy,
        Integer retryMaxCount,
        boolean enabled
) {
}
