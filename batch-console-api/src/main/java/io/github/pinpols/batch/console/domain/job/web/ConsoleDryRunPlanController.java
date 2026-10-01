package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.DryRunPlanRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleDryRunPlanResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-026 演练计划 console 转发：{@code POST /api/console/ops/dry-run/plan} → orchestrator {@code
 * /internal/orchestrator/dry-run/plan}。
 *
 * <p>UI 端按 L1 / L2 / L3 三档发起演练计划查询，不触发 launch。
 *
 * <p>R6 audit 2026-05-15 安全收紧：
 *
 * <ul>
 *   <li>类级 {@code @PreAuthorize} 要求 ADMIN/CONFIG_ADMIN/AUDITOR —— 不允许 TENANT_USER 触发演练
 *   <li>body 中的 {@code tenantId} 由 {@link ConsoleOrchestratorPort} 统一校验后强制覆盖回 body，禁止信任
 *       client 提交的 tenantId 跨租户触发演练
 *   <li>Orchestrator 内部鉴权和 HTTP 调用由 {@link ConsoleOrchestratorPort} 的基础设施实现统一处理
 * </ul>
 */
@RestController
@RequestMapping("/api/console/ops/dry-run")
@RequiredArgsConstructor
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
public class ConsoleDryRunPlanController {

  private final ConsoleOrchestratorPort orchestratorProxy;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/plan")
  public CommonResponse<ConsoleDryRunPlanResponse> plan(
      @Valid @RequestBody DryRunPlanRequest request) {
    // R6 P0-2 加固：解析当前主体的 tenantId 后强制覆盖 body 里的 tenantId；
    // 非全局角色账号若 body tenantId 与 JWT 不一致直接 FORBIDDEN，不再让"客户端任传 tenantId"成立。
    CommonResponse<ConsoleDryRunPlanResponse> resp = orchestratorProxy.dryRunPlan(request);
    // J1 bugfix 2026-06-04:orchestrator 返 CommonResponse<DryRunPlanResult> envelope;
    // 直接 success(resp) 会让 FE 见到 {success:true, data:{success:true, data:{...}}} 嵌套,
    // ADR-026 e2e integration-adr-features:18 据此误判 success=false。透传 envelope.data。
    return responseFactory.forwardOrchestrator(resp);
  }
}
