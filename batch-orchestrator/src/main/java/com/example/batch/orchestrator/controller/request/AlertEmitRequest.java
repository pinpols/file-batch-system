package com.example.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertEmitRequest(
    String tenantId,
    String serviceName,
    String alertType,
    String severity,
    String title,
    String detailJson,
    String resourceKey,
    String traceId) {}
