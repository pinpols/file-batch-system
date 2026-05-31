/**
 * SDK 自有 wire DTO records — 跟 batch-orchestrator 的 {@code /internal/*} 控制器 body schema 一一对齐。
 *
 * <p>这些 record 是 SDK 调用 orchestrator HTTP 端点时**发出**的 body 形态。SDK 不直接 import 平台侧 DTO(避免反向耦合 +
 * 跨发布周期版本漂移),而是在 {@code SdkWireContractTest} 里通过 Jackson 反序列化校验"SDK 写出的 JSON 平台能完整解出"。
 *
 * <p>对应关系:
 *
 * <ul>
 *   <li>{@link com.example.batch.sdk.wire.RegisterRequest} ↔ {@code POST
 *       /internal/workers/register} body = {@code WorkerHeartbeatDto}
 *   <li>{@link com.example.batch.sdk.wire.HeartbeatRequest} ↔ {@code POST
 *       /internal/workers/{workerCode}/heartbeat} body = {@code WorkerHeartbeatDto}
 *   <li>{@link com.example.batch.sdk.wire.ClaimRequest} ↔ {@code POST
 *       /internal/tasks/{taskId}/claim} body = {@code TaskController.TaskClaimRequest}
 *   <li>{@link com.example.batch.sdk.wire.ReportRequest} ↔ {@code POST
 *       /internal/tasks/{taskId}/report} body = {@code TaskExecutionReportDto}
 *   <li>{@link com.example.batch.sdk.wire.RenewRequest} ↔ {@code POST
 *       /internal/tasks/{taskId}/renew} body = {@code TaskController.TaskClaimRequest}
 * </ul>
 *
 * <p>schemaVersion 协议:本 phase(SDK 路线图 Phase 0)只在 {@link
 * com.example.batch.sdk.dispatcher.TaskDispatchMessage}(Kafka in-bound)读 schemaVersion,wire DTO
 * 自身暂不带版本字段,后续 phase 若需要演进协议再加。
 *
 * <p>详见 {@code docs/plans/sdk-roadmap-2026-h2.md} §2 Phase 0 + {@code
 * docs/api/orchestrator-internal.openapi.yaml}。
 */
package com.example.batch.sdk.wire;
