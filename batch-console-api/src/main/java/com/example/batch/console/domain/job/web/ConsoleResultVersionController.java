package com.example.batch.console.domain.job.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * ADR-017 Stage 6 — result_version console 转发 API。{@code /api/console/result-versions}
 *
 * <p>5 个端点：list / effective / detail / promote / reject。 console UI 通过本路径访问 orchestrator 的 {@code
 * /internal/orchestrator/result-versions/...} 内部端点； 业务规则（partial unique index / promote 顺序）由
 * orchestrator service 保证，console 只做鉴权 + 透传。
 */
@RestController
@RequestMapping("/api/console/result-versions")
@RequiredArgsConstructor
public class ConsoleResultVersionController {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<List<Map<String, Object>>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("businessKey") String businessKey,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> resp =
        proxyClient()
            .get()
            .uri(
                "/internal/orchestrator/result-versions?tenantId={tenantId}"
                    + "&businessKey={businessKey}&limit={limit}",
                resolved,
                businessKey,
                limit)
            .retrieve()
            .body(unwrapToMap());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data =
        resp == null ? List.of() : (List<Map<String, Object>>) resp.getOrDefault("data", List.of());
    return responseFactory.success(data);
  }

  @GetMapping("/effective")
  public CommonResponse<Map<String, Object>> effective(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("businessKey") String businessKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .get()
            .uri(
                "/internal/orchestrator/result-versions/effective?tenantId={tenantId}"
                    + "&businessKey={businessKey}",
                resolved,
                businessKey)
            .retrieve()
            .body(unwrapToMap()));
  }

  @GetMapping("/{id}")
  public CommonResponse<Map<String, Object>> detail(
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .get()
            .uri("/internal/orchestrator/result-versions/{id}?tenantId={tenantId}", id, resolved)
            .retrieve()
            .body(unwrapToMap()));
  }

  // P0-1: promote/reject 是高危结果版本变更，要求管理员/配置管理员权限；P1-6：强制幂等键
  @PostMapping("/{id}/promote")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN')")
  @Idempotent
  public CommonResponse<Map<String, Object>> promote(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .post()
            .uri(
                "/internal/orchestrator/result-versions/{id}/promote?tenantId={tenantId}",
                id,
                resolved)
            .retrieve()
            .body(unwrapToMap()));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN')")
  @Idempotent
  public CommonResponse<Map<String, Object>> reject(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(
        proxyClient()
            .post()
            .uri(
                "/internal/orchestrator/result-versions/{id}/reject?tenantId={tenantId}",
                id,
                resolved)
            .retrieve()
            .body(unwrapToMap()));
  }

  private RestClient proxyClient() {
    return orchestratorInternalRestClient.build();
  }

  private static ParameterizedTypeReference<Map<String, Object>> unwrapToMap() {
    return new ParameterizedTypeReference<>() {};
  }
}
