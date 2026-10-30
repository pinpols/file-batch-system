package io.github.pinpols.batch.worker.dispatchs.param;

import java.sql.Timestamp;
import lombok.Builder;

/** 完整健康快照写入参数；可空探针时间与证据沿用原数据库字段语义。 */
@Builder
public record DispatchChannelHealthUpsertParam(
    String tenantId,
    String channelCode,
    String channelType,
    String healthStatus,
    int consecutiveFailures,
    Timestamp lastProbeAt,
    Timestamp lastSuccessAt,
    Timestamp lastFailureAt,
    Timestamp nextProbeAt,
    String probeMessage,
    String probeEvidence) {}
