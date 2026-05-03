package com.example.batch.orchestrator.controller.request;

/** ADR-016: per-task outcome in batch renew response (order matches request {@code items}). */
public record TaskLeaseRenewResultPayload(Long taskId, boolean renewed) {}
