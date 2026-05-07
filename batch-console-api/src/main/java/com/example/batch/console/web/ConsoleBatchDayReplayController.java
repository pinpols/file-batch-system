package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
public class ConsoleBatchDayReplayController {

  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final RestClient.Builder restClientBuilder;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/sessions")
  public CommonResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> command) {
    return responseFactory.success(
        proxyClient()
            .post()
            .uri("/internal/orchestrator/batch-day-replay/sessions")
            .body(command)
            .retrieve()
            .body(unwrapToMap()));
  }

  @PostMapping("/sessions/{sessionId}/approve")
  public CommonResponse<Map<String, Object>> approve(
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
  public CommonResponse<Map<String, Object>> cancel(
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

  @GetMapping("/sessions/{sessionId}/entries")
  public CommonResponse<List<Map<String, Object>>> entries(
      @PathVariable("sessionId") Long sessionId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false, defaultValue = "500") int limit) {
    StringBuilder uri =
        new StringBuilder("/internal/orchestrator/batch-day-replay/sessions/")
            .append(sessionId)
            .append("/entries?limit=")
            .append(limit);
    if (status != null && !status.isBlank()) {
      uri.append("&status=").append(status);
    }
    Map<String, Object> resp =
        proxyClient().get().uri(uri.toString()).retrieve().body(unwrapToMap());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data =
        resp == null ? List.of() : (List<Map<String, Object>>) resp.getOrDefault("data", List.of());
    return responseFactory.success(data);
  }

  private RestClient proxyClient() {
    return restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
  }

  @SuppressWarnings("unused")
  private static ParameterizedTypeReference<Map<String, Object>> unwrapToMap() {
    return new ParameterizedTypeReference<>() {};
  }
}
