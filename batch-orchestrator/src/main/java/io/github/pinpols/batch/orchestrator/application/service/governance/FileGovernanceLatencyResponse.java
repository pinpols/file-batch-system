package io.github.pinpols.batch.orchestrator.application.service.governance;

import java.util.List;
import java.util.Map;

/** 文件治理延迟指标的 HTTP 响应契约。 */
public record FileGovernanceLatencyResponse(
    String tenantId,
    long arrivalDelayViolations,
    long maxArrivalDelaySeconds,
    long processingDelayViolations,
    long maxProcessingDelaySeconds,
    List<Map<String, Object>> arrivalDelaySamples,
    List<Map<String, Object>> processingDelaySamples) {}
