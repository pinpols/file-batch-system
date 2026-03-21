package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import java.util.Map;

public record DispatchCommand(
        String tenantId,
        String traceId,
        Map<String, Object> fileRecord,
        Map<String, Object> channelConfig,
        DispatchPayload payload
) {
}
