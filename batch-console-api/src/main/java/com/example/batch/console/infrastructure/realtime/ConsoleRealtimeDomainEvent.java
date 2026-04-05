package com.example.batch.console.infrastructure.realtime;

import java.time.Instant;

/**
 * 控制台侧的实时领域事件。
 *
 * <p>service 层只发布这个事件，统一 listener 再把它转成 SSE 或摘要刷新。</p>
 */
public record ConsoleRealtimeDomainEvent
(
        String tenantId,
        String stream,
        String eventType,
        String cursor,
        Object data,
        boolean summaryRefresh,
        Instant emittedAt
) { }
