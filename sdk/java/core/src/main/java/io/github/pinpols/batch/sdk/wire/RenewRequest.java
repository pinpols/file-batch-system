package io.github.pinpols.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SDK wire DTO — {@code POST /internal/tasks/{taskId}/renew} 请求体。
 *
 * <p>字段集跟 {@link ClaimRequest} 一致(平台 {@code TaskController} 复用 {@code TaskClaimRequest})。SDK 侧拆独立
 * record 是为了语义明确,且未来若 renew 需要带 lease epoch / heartbeat details 时可独立扩展(Phase 4 计划)。
 *
 * <p>Phase 0 ({@code docs/plans/sdk-roadmap-2026-h2.md} §2)— SDK 协议演进基础。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RenewRequest(String tenantId, String workerId, String partitionInvocationId) {}
