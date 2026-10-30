package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 步骤终态写入参数；保留输出 JSON、错误三元组和原条件更新语义。 */
@Builder(toBuilder = true)
public record PlatformStepRunFinishParam(
    Long stepRunId,
    String status,
    String outputSummaryJson,
    String errorCode,
    String errorMessage,
    String errorKey,
    String errorArgs) {}
