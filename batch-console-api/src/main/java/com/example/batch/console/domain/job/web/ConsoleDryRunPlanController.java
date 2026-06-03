package com.example.batch.console.domain.job.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
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
 *   <li>body 中的 {@code tenantId} 必经 {@link ConsoleTenantGuard#resolveTenant} 校验后强制覆盖回 body，禁止信任
 *       client 提交的 tenantId 跨租户触发演练
 *   <li>{@link OrchestratorInternalRestClient} 注入 {@code X-Internal-Secret}（已经是 console 调
 *       orchestrator internal API 的标准通道）
 * </ul>
 */
@RestController
@RequestMapping("/api/console/ops/dry-run")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN','ROLE_AUDITOR')")
public class ConsoleDryRunPlanController {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/plan")
  public CommonResponse<Map<String, Object>> plan(@RequestBody Map<String, Object> request) {
    // R6 P0-2 加固：解析当前主体的 tenantId 后强制覆盖 body 里的 tenantId；
    // 非全局角色账号若 body tenantId 与 JWT 不一致直接 FORBIDDEN，不再让"客户端任传 tenantId"成立。
    Object bodyTenant = request == null ? null : request.get("tenantId");
    String resolved = tenantGuard.resolveTenant(bodyTenant == null ? null : bodyTenant.toString());
    Map<String, Object> sanitized = new LinkedHashMap<>(request == null ? Map.of() : request);
    sanitized.put("tenantId", resolved);
    Map<String, Object> resp =
        orchestratorInternalRestClient
            .build()
            .post()
            .uri("/internal/orchestrator/dry-run/plan")
            .body(sanitized)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    // J1 bugfix 2026-06-04:orchestrator 返 CommonResponse<DryRunPlanResult> envelope;
    // 直接 success(resp) 会让 FE 见到 {success:true, data:{success:true, data:{...}}} 嵌套,
    // ADR-026 e2e integration-adr-features:18 据此误判 success=false。透传 envelope.data。
    return responseFactory.forwardOrchestrator(resp);
  }
}
