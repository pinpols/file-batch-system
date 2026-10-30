package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 新分发记录参数；初始状态和外部身份由仓库显式提供。 */
@Builder
public record FileDispatchInsertParam(
    String tenantId,
    Long fileId,
    Long pipelineInstanceId,
    String channelCode,
    String dispatchTarget,
    String dispatchStatus,
    String receiptCode,
    String receiptStatus,
    String externalRequestId) {}
