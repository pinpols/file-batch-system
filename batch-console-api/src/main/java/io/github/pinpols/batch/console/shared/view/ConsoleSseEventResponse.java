package io.github.pinpols.batch.console.shared.view;

import java.time.Instant;

public record ConsoleSseEventResponse(
    String stream, String eventType, String cursor, Object data, Instant emittedAt) {}
