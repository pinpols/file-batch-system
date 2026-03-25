package com.example.batch.worker.dispatchs.domain;

import com.example.batch.worker.core.support.StageExecutionResult;

public record DispatchStageResult(
        DispatchStage stage,
        boolean success,
        String code,
        String message
) implements StageExecutionResult {
    public static DispatchStageResult success(DispatchStage stage) {
        return new DispatchStageResult(stage, true, "SUCCESS", "ok");
    }

    public static DispatchStageResult failure(DispatchStage stage, String code, String message) {
        return new DispatchStageResult(stage, false, code, message);
    }
}
