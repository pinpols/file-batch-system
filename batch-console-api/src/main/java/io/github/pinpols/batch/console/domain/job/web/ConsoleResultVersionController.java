package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleResultVersionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

  private final ConsoleOrchestratorPort orchestratorProxy;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<List<ConsoleResultVersionResponse>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("businessKey") String businessKey,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    CommonResponse<List<ConsoleResultVersionResponse>> resp =
        orchestratorProxy.resultVersions(tenantId, businessKey, limit);
    return responseFactory.forwardOrchestrator(resp);
  }

  @GetMapping("/effective")
  public CommonResponse<ConsoleResultVersionResponse> effective(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("businessKey") String businessKey) {
    CommonResponse<ConsoleResultVersionResponse> resp =
        orchestratorProxy.effectiveResultVersion(tenantId, businessKey);
    return responseFactory.forwardOrchestrator(resp);
  }

  @GetMapping("/{id}")
  public CommonResponse<ConsoleResultVersionResponse> detail(
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    CommonResponse<ConsoleResultVersionResponse> resp =
        orchestratorProxy.resultVersion(id, tenantId);
    return responseFactory.forwardOrchestrator(resp);
  }

  // P0-1: promote/reject 是高危结果版本变更，要求管理员/配置管理员权限；P1-6：强制幂等键
  @PostMapping("/{id}/promote")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  @Idempotent
  public CommonResponse<ConsoleResultVersionResponse> promote(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    CommonResponse<ConsoleResultVersionResponse> resp =
        orchestratorProxy.promoteResultVersion(id, tenantId);
    return responseFactory.forwardOrchestrator(resp);
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  @Idempotent
  public CommonResponse<ConsoleResultVersionResponse> reject(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    CommonResponse<ConsoleResultVersionResponse> resp =
        orchestratorProxy.rejectResultVersion(id, tenantId);
    return responseFactory.forwardOrchestrator(resp);
  }
}
