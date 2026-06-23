package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ADR-016: single row in {@code POST /internal/tasks/leases/renew-batch} request body. Fields align
 * with {@code POST /internal/tasks/{taskId}/renew} claim body (ADR-014 invocation id).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskLeaseRenewItemPayload(
    String tenantId, Long taskId, String workerId, String partitionInvocationId) {}
