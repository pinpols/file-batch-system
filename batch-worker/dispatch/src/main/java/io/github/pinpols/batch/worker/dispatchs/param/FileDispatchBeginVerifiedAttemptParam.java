package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 一次性授权重发参数；绑定当前租户、作业及 Pipeline 身份。 */
@Builder
public record FileDispatchBeginVerifiedAttemptParam(
    String tenantId,
    Long fileId,
    String channelCode,
    Long jobInstanceId,
    Long pipelineInstanceId,
    String unknownOutcomeCode,
    String dispatchStatus,
    String receiptStatus) {}
