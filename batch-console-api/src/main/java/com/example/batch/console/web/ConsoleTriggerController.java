package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ops.ConsoleTriggerProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 触发器运维（Ops-only）：register / unregister / pause / resume 属于 <b>救急修复入口</b>， 仅用于 DB 与 Quartz JobStore
 * 漂移时的强制收敛，不用作日常业务状态切换。
 *
 * <p>日常禁用 job 请走 {@code POST /api/console/job-definitions/{id}/toggle-enabled}—— DB 是权威源，trigger 侧的
 * {@code TriggerReconciler} 会在 30s 内自动把 Quartz 收敛到 DB 状态。 在此接口上直接注销一个 {@code enabled=true} 的
 * job，会被下一次对账扫描<b>悄悄重建</b>。
 *
 * <p>路径 {@code /api/console/ops/triggers}，语义上归类到 Ops 菜单；前端按钮保留但应显式提示风险。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops/triggers")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleTriggerController {

  private final ConsoleTriggerProxyService triggerProxyService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<List<Object>> list() {
    return responseFactory.success(triggerProxyService.triggerList());
  }

  @PostMapping("/{jobCode}/register")
  public CommonResponse<Map<String, String>> register(
      @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        triggerProxyService.triggerAction(tenantId, jobCode, "register"));
  }

  @PostMapping("/{jobCode}/unregister")
  public CommonResponse<Map<String, String>> unregister(
      @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        triggerProxyService.triggerAction(tenantId, jobCode, "unregister"));
  }

  @PostMapping("/{jobCode}/pause")
  public CommonResponse<Map<String, String>> pause(
      @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(triggerProxyService.triggerAction(tenantId, jobCode, "pause"));
  }

  @PostMapping("/{jobCode}/resume")
  public CommonResponse<Map<String, String>> resume(
      @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(triggerProxyService.triggerAction(tenantId, jobCode, "resume"));
  }
}
