package com.example.batch.orchestrator.domain.pipeline;

public record StepOrderModel(
        String pipelineCode,
        String stepCode,
        Integer stepOrder
) {
}
