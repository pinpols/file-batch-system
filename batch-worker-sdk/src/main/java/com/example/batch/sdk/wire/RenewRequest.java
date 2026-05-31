package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body for {@code POST /internal/tasks/{taskId}/renew}.
 *
 * <p>字段集与 {@link ClaimRequest} 一致(平台 controller 端共用 {@code TaskController.TaskClaimRequest})。 SDK
 * 侧保持独立 record 以便后续 phase 加 renew 专属字段(如 leaseExtensionSeconds)而不影响 claim。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RenewRequest(String tenantId, String workerId, String partitionInvocationId) {}
