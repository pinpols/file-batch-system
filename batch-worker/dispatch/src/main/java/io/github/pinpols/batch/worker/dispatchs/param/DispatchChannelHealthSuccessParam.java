package io.github.pinpols.batch.worker.dispatchs.param;

import java.sql.Timestamp;
import lombok.Builder;

/** 成功结果写入参数；健康状态由 Mapper 的成功入口固定。 */
@Builder
public record DispatchChannelHealthSuccessParam(
    String tenantId,
    String channelCode,
    String channelType,
    Timestamp now,
    Timestamp nextProbeAt,
    String probeMessage,
    String probeEvidence) {}
