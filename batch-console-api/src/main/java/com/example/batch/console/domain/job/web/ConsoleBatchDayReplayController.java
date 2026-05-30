package com.example.batch.console.domain.job.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.Idempotent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

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
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN')")
public class ConsoleBatchDayReplayController {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  // P1-5/P1-6 (ADR audit): submit body 的 tenantId 经 guard 解析后强制覆盖回 body，
  // 防止跨租户提交；同时通过 @Idempotent 拦截重复请求。
  @PostMapping("/sessions")
  @Idempotent
  public CommonResponse<Map<String, Object>> submit(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestBody Map<String, Object> command) {
    Object bodyTenant = command == null ? null : command.get("tenantId");
    String resolved = tenantGuard.resolveTenant(bodyTenant == null ? null : bodyTenant.toString());
    Map<String, Object> sanitized = new LinkedHashMap<>(command == null ? Map.of() : command);
    sanitized.put("tenantId", resolved);
    return responseFactory.success(
        proxyClient()
            .post()
            .uri("/internal/orchestrator/batch-day-replay/sessions")
            .body(sanitized)
            .retrieve()
            .body(unwrapToMap()));
  }

  @PostMapping("/sessions/{sessionId}/approve")
  @Idempotent
  public CommonResponse<Map<String, Object>> approve(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("approver") String approver) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .post()
            .uri(
                "/internal/orchestrator/batch-day-replay/sessions/{id}/approve"
                    + "?tenantId={tenantId}&approver={approver}",
                sessionId,
                resolved,
                approver)
            .retrieve()
            .body(unwrapToMap()));
  }

  @PostMapping("/sessions/{sessionId}/cancel")
  @Idempotent
  public CommonResponse<Map<String, Object>> cancel(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .post()
            .uri(
                "/internal/orchestrator/batch-day-replay/sessions/{id}/cancel?tenantId={tenantId}",
                sessionId,
                resolved)
            .retrieve()
            .body(unwrapToMap()));
  }

  @GetMapping("/sessions/{sessionId}")
  public CommonResponse<Map<String, Object>> detail(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .get()
            .uri(
                "/internal/orchestrator/batch-day-replay/sessions/{id}?tenantId={tenantId}",
                sessionId,
                resolved)
            .retrieve()
            .body(unwrapToMap()));
  }

  // P1-5: entries 必须先解析 tenantId 走 guard 守护，避免跨租户拉取条目；改用 URI template
  // 而非字符串拼接，让 RestClient 做转义。
  @GetMapping("/sessions/{sessionId}/entries")
  public CommonResponse<List<Map<String, Object>>> entries(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "500") int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String uri =
        status == null || status.isBlank()
            ? "/internal/orchestrator/batch-day-replay/sessions/{id}/entries?tenantId={tenantId}&limit={limit}"
            : "/internal/orchestrator/batch-day-replay/sessions/{id}/entries?tenantId={tenantId}&limit={limit}&status={status}";
    Map<String, Object> resp =
        status == null || status.isBlank()
            ? proxyClient()
                .get()
                .uri(uri, sessionId, resolved, limit)
                .retrieve()
                .body(unwrapToMap())
            : proxyClient()
                .get()
                .uri(uri, sessionId, resolved, limit, status)
                .retrieve()
                .body(unwrapToMap());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data =
        resp == null ? List.of() : (List<Map<String, Object>>) resp.getOrDefault("data", List.of());
    return responseFactory.success(data);
  }

  private RestClient proxyClient() {
    return orchestratorInternalRestClient.build();
  }

  private static ParameterizedTypeReference<Map<String, Object>> unwrapToMap() {
    return new ParameterizedTypeReference<>() {};
  }
}
