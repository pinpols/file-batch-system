package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.contract.request.system.UpdateMaintenanceRequest;
import io.github.pinpols.batch.console.application.contract.response.MaintenanceStatusResponse;
import io.github.pinpols.batch.console.application.observability.ConsoleMaintenanceApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护模式管理员热更新端点。
 *
 * <p>与启动期 {@code batch.console.maintenance.*} 配置互补:配置只决定启动时的初值,运行时由 admin 通过这里实时切换,**不需要重启**。
 *
 * <p>典型 SOP:灰度上线前 admin 调一次 PUT enabled=true + message + etaAt → filter 立即拦截非 admin 流量; 上线完成后再调 PUT
 * enabled=false → 立刻恢复。
 *
 * <p>权限:仅 ROLE_ADMIN。维护期 filter 白名单放行本路径,避免"维护期间改不了维护配置"死锁。
 */
@RestController
@RequestMapping("/api/console/admin/system")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
public class ConsoleAdminMaintenanceController {

  private final ConsoleMaintenanceApplicationService maintenanceService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/maintenance")
  public CommonResponse<MaintenanceStatusResponse> get() {
    return responseFactory.success(maintenanceService.status());
  }

  @PutMapping("/maintenance")
  @AuditAction(
      action = "system.maintenance.update",
      aggregateType = "system",
      aggregateId = "'maintenance'")
  public CommonResponse<MaintenanceStatusResponse> update(
      @Valid @RequestBody UpdateMaintenanceRequest request) {
    return responseFactory.success(maintenanceService.update(request));
  }
}
