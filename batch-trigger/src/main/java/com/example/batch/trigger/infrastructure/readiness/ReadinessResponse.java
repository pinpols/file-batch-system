package com.example.batch.trigger.infrastructure.readiness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * orchestrator {@code /internal/readiness/job} 响应体(ADR-043)。
 *
 * @param ready 上游是否就绪
 * @param reason 未就绪原因码(就绪时为 null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadinessResponse(boolean ready, String reason) {}
