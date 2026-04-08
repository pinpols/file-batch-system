package com.example.batch.worker.dispatchs.infrastructure.channel;

/**
 * 分发渠道探测结果，包含成功标志、消息及证据引用。
 */
public record DispatchChannelProbeResult(
        boolean success,
        String message,
        String evidenceRef
) {
}
