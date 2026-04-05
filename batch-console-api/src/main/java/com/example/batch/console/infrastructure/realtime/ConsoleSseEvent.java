package com.example.batch.console.infrastructure.realtime;

import java.time.Instant;

/**
 * 控制台 SSE 推送事件。
 *
 * <p>实时总线最终发送给浏览器的载荷。</p>
 */
public record ConsoleSseEvent
(
        String tenantId,
        String stream,
        String eventType,
        String cursor,
        Object data,
        Instant emittedAt
) { }
