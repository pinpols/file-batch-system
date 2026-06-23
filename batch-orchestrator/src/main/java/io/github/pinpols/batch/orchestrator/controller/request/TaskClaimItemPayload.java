package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ADR-046 P2 切片 2.1:{@code POST /internal/tasks/claim-batch} 请求体中的单行。 字段与单条 {@code POST
 * /internal/tasks/{taskId}/claim}({@code TaskClaimRequest})对齐 + taskId。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskClaimItemPayload(
    String tenantId, Long taskId, String workerId, String partitionInvocationId) {}
