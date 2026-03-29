package com.example.batch.orchestrator.domain.pipeline;

public record StepOrderModel(
        String jobCode,
        String stepCode,
        Integer stepOrder
) {
    public String getJobCode() {
        return jobCode;
    }

    public String getPipelineCode() {
        return jobCode;
    }

    public String jobCode() {
        return jobCode;
    }

    public String pipelineCode() {
        return jobCode;
    }
}
