package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body for {@code POST /internal/tasks/{taskId}/claim}.
 *
 * <p>字段集对齐 {@code TaskController.TaskClaimRequest}(平台侧 inner record)。
 *
 * <p>{@code workerId} 当前等同 worker 注册时用的 {@code workerCode}(ADR-035 §9)。
 *
 * <p>{@code partitionInvocationId} 可选(ADR-014):非 null 时平台校验跟 dispatch 时分配的 invocation id 一致,
 * mismatch 则 reject claim。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimRequest(String tenantId, String workerId, String partitionInvocationId) {}
