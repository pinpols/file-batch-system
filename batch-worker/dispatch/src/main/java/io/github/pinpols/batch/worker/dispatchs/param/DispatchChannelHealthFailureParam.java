package io.github.pinpols.batch.worker.dispatchs.param;

import java.sql.Timestamp;
import lombok.Builder;

/** 失败结果写入参数；首次退避与失败阈值由调用方计算，状态由 Mapper 固定。 */
@Builder
public record DispatchChannelHealthFailureParam(
    String tenantId,
    String channelCode,
    String channelType,
    Timestamp now,
    Timestamp firstFailureBackoffAt,
    int failureThreshold,
    String probeMessage,
    String probeEvidence) {}
