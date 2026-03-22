package com.example.batch.orchestrator.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerTenantRequest(String tenantId) {
}
