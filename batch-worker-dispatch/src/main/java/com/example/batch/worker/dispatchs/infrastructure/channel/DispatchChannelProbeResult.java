package com.example.batch.worker.dispatchs.infrastructure.channel;

public record DispatchChannelProbeResult(
        boolean success,
        String message,
        String evidenceRef
) {
}
