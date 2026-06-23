package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.dto.TaskHeartbeatDetailsResponse;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleTaskHeartbeatService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FE 2-C：console「任务详情」页读 task 最新心跳进度 / checkpoint(SDK Phase 4 / ORCH-P4-1)。
 *
 * <p>权限:租户管理员看本租户;平台管理员可查任意租户({@link ConsoleTenantGuard#resolveTenant} 解析 + mapper 租户作用域)。 只读,
 * console-api 走读写分离只读路径,不回写 job_task(状态主机是 orchestrator)。
 */
@RestController
@RequestMapping("/api/console/tasks")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleTaskController {

  private final ConsoleTaskHeartbeatService heartbeatService;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  /**
   * GET /api/console/tasks/{taskId}/heartbeat-details?tenantId=xxx — 某 task 最新心跳进度;不存在(或不属于该租户)返
   * 404。
   */
  @GetMapping("/{taskId}/heartbeat-details")
  public CommonResponse<TaskHeartbeatDetailsResponse> heartbeatDetails(
      @PathVariable("taskId") Long taskId,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(heartbeatService.getHeartbeatDetails(resolved, taskId));
  }
}
