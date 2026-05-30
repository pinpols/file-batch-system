package com.example.batch.console.domain.ops.web.response;

import java.time.Instant;

public record ConsoleSseEventResponse(
    String stream, String eventType, String cursor, Object data, Instant emittedAt) {}
