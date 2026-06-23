package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerTenantRequest(String tenantId) {}
