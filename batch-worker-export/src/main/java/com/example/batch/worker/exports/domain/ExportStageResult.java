package com.example.batch.worker.exports.domain;

import com.example.batch.worker.core.support.StageExecutionResult;

/**
 * 导出 stage 执行结果，包含阶段标识、是否成功、结果码和消息。
 */
public record ExportStageResult(
        ExportStage stage,
        boolean success,
        String code,
        String message
) implements StageExecutionResult {
    /**
     * 构造指定阶段的成功结果。
     *
     * @param stage 执行阶段
     * @return 成功的 ExportStageResult
     */
    public static ExportStageResult success(ExportStage stage) {
        return new ExportStageResult(stage, true, "SUCCESS", "ok");
    }

    /**
     * 构造指定阶段的失败结果。
     *
     * @param stage   执行阶段
     * @param code    失败码
     * @param message 失败原因
     * @return 失败的 ExportStageResult
     */
    public static ExportStageResult failure(ExportStage stage, String code, String message) {
        return new ExportStageResult(stage, false, code, message);
    }
}
