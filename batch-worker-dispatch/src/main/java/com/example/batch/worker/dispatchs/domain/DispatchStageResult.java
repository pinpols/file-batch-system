package com.example.batch.worker.dispatchs.domain;

import com.example.batch.worker.core.support.StageExecutionResult;

/**
 * 分发阶段执行结果，包含阶段标识、成功标志及错误码/消息。
 */
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
