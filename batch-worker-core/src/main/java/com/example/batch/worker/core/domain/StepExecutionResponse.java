package com.example.batch.worker.core.domain;

public record StepExecutionResponse(
        boolean success,
        String code,
        String message
) {
    public static StepExecutionResponse successResponse() {
        return new StepExecutionResponse(true, "SUCCESS", "ok");
    }
}
