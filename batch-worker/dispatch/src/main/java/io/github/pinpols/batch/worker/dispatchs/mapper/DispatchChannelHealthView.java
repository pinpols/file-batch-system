package io.github.pinpols.batch.worker.dispatchs.mapper;

import java.time.Instant;

/** 渠道健康状态的固定业务列投影。 */
public record DispatchChannelHealthView(
    String tenantId,
    String channelCode,
    String channelType,
    String healthStatus,
    Integer consecutiveFailures,
    Instant lastProbeAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Instant nextProbeAt,
    String probeMessage,
    String probeEvidence) {}
