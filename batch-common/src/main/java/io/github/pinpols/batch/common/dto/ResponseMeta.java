package io.github.pinpols.batch.common.dto;

import java.time.Instant;

public record ResponseMeta(String requestId, String traceId, Instant timestamp) {}
