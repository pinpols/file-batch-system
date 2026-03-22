package com.example.batch.worker.dispatchs.infrastructure.channel;

import java.time.Instant;

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
        String probeEvidence
) {
}
