package com.example.batch.console.infrastructure.realtime;

import java.time.Instant;

/**
 * Redis Pub/Sub 中存放的控制台实时事件包。
 */
public record ConsoleRealtimeStreamEnvelope(
        String originInstanceId,
        String tenantId,
        String stream,
        String eventType,
        String cursor,
        boolean summaryRefresh,
        String dataJson,
        Instant emittedAt
) {
}
