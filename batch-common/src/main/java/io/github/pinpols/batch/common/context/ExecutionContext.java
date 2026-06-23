package io.github.pinpols.batch.common.context;

import java.time.Instant;
import java.util.Map;

public record ExecutionContext(
    String tenantId,
    String traceId,
    String operatorId,
    String jobCode,
    String instanceNo,
    Instant startedAt,
    Map<String, Object> attributes) {}
