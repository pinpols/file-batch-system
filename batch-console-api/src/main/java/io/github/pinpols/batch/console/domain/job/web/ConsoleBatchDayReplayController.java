package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayReplaySubmitRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplayEntryResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplayPreviewResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplaySessionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-020 批次日重放 console 转发 API：5 个端点 submit / approve / cancel / detail / entries（progress）。
 *
 * <p>路径 {@code /api/console/ops/batch-day-replay}，所有调用通过 RestClient 转发到 orchestrator {@code
 * /internal/orchestrator/batch-day-replay/...}。
 */
@RestController
@RequestMapping("/api/console/ops/batch-day-replay")
@RequiredArgsConstructor
// P0-1: 批次日重放是高危跨实例运维操作，整类要求 ADMIN/CONFIG_ADMIN（GET 详情/进度也限定，避免泄漏跨租户元数据）
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
public class ConsoleBatchDayReplayController {

  private final ConsoleOrchestratorPort orchestratorProxy;
  private final ConsoleResponseFactory responseFactory;

  // P1-5/P1-6 (ADR audit): submit body 的 tenantId 经 guard 解析后强制覆盖回 body，
  // 防止跨租户提交；同时通过 @Idempotent 拦截重复请求。
  @PostMapping("/sessions")
  @Idempotent
  @AuditAction(
      action = "batchDayReplay.submit",
      aggregateType = "batch_day_replay_session",
      targetTenantParam = "#command.tenantId")
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> submit(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody BatchDayReplaySubmitRequest command) {
    CommonResponse<ConsoleBatchDayReplaySessionResponse> resp =
        orchestratorProxy.batchDayReplaySubmit(command);
    return responseFactory.forwardOrchestrator(resp);
  }

  @PostMapping("/sessions/preview")
  public CommonResponse<ConsoleBatchDayReplayPreviewResponse> preview(
      @Valid @RequestBody BatchDayReplaySubmitRequest command) {
    CommonResponse<ConsoleBatchDayReplayPreviewResponse> resp =
        orchestratorProxy.batchDayReplayPreview(command);
    return responseFactory.forwardOrchestrator(resp);
  }

  @GetMapping("/sessions")
  public CommonResponse<List<ConsoleBatchDayReplaySessionResponse>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    CommonResponse<List<ConsoleBatchDayReplaySessionResponse>> resp =
        orchestratorProxy.batchDayReplayList(tenantId, status, limit);
    return responseFactory.forwardOrchestrator(resp);
  }

  @PostMapping("/sessions/{sessionId}/approve")
  @Idempotent
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  @AuditAction(
      action = "batchDayReplay.approve",
      aggregateType = "batch_day_replay_session",
      aggregateId = "#sessionId",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> approve(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("approver") String approver) {
    CommonResponse<ConsoleBatchDayReplaySessionResponse> resp =
        orchestratorProxy.batchDayReplayApprove(sessionId, tenantId, approver);
    return responseFactory.forwardOrchestrator(resp);
  }

  @PostMapping("/sessions/{sessionId}/cancel")
  @Idempotent
  @AuditAction(
      action = "batchDayReplay.cancel",
      aggregateType = "batch_day_replay_session",
      aggregateId = "#sessionId",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> cancel(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    CommonResponse<ConsoleBatchDayReplaySessionResponse> resp =
        orchestratorProxy.batchDayReplayCancel(sessionId, tenantId);
    return responseFactory.forwardOrchestrator(resp);
  }

  @GetMapping("/sessions/{sessionId}")
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> detail(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    CommonResponse<ConsoleBatchDayReplaySessionResponse> resp =
        orchestratorProxy.batchDayReplayDetail(sessionId, tenantId);
    return responseFactory.forwardOrchestrator(resp);
  }

  // P1-5: entries 必须先解析 tenantId 走 guard 守护，避免跨租户拉取条目；改用 URI template
  // 而非字符串拼接，让 RestClient 做转义。
  @GetMapping("/sessions/{sessionId}/entries")
  public CommonResponse<List<ConsoleBatchDayReplayEntryResponse>> entries(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "500") int limit) {
    CommonResponse<List<ConsoleBatchDayReplayEntryResponse>> resp =
        orchestratorProxy.batchDayReplayEntries(sessionId, tenantId, status, limit);
    return responseFactory.forwardOrchestrator(resp);
  }
}
