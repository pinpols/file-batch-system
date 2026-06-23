package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import java.time.Instant;

/** 分发渠道健康状态快照，记录探测时间、连续失败次数及下次探测时间等信息。 */
public record DispatchChannelHealthSnapshot(
    String tenantId,
    String channelCode,
    String channelType,
    String healthStatus,
    int consecutiveFailures,
    Instant lastProbeAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Instant nextProbeAt,
    String probeMessage,
    String probeEvidence) {}
