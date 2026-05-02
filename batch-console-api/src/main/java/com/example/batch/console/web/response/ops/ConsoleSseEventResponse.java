package com.example.batch.console.web.response.ops;

import java.time.Instant;

public record ConsoleSseEventResponse(
    String stream, String eventType, String cursor, Object data, Instant emittedAt) {}
