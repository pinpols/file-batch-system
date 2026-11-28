package io.github.pinpols.batch.orchestrator.controller.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** ADR-046 P2 切片 2.1:批量认领请求 —— 一次 HTTP 往返认领 K 个独立 partition 对应的 task。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskClaimBatchRequest(List<TaskClaimItemPayload> items) {}
