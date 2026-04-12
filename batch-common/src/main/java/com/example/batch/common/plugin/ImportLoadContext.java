package com.example.batch.common.plugin;

import java.util.Map;

/**
 * LOAD-stage context passed to {@link ImportLoadPlugin}. Platform fills this; plugins must not
 * assume fixed entity types.
 */
public record ImportLoadContext(
    String tenantId,
    String jobCode,
    String traceId,
    String workerId,
    String sourceFileName,
    String batchNo,
    String bizType,
    String templateCode,
    Map<String, Object> templateConfig) {}
