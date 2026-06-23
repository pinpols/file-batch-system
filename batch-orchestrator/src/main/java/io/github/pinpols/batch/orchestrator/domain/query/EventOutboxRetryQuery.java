package io.github.pinpols.batch.orchestrator.domain.query;

public record EventOutboxRetryQuery(String tenantId, String retryStatus, String eventKey) {}
