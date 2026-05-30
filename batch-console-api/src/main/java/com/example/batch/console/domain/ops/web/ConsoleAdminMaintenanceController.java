package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import com.example.batch.console.web.request.system.UpdateMaintenanceRequest;
import com.example.batch.console.web.response.MaintenanceStatusResponse;
import jakarta.validation.Valid;
import java.util.List;
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
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleAdminMaintenanceController {

  private final MaintenanceStateHolder stateHolder;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/maintenance")
  public CommonResponse<MaintenanceStatusResponse> get() {
    return responseFactory.success(toResponse(stateHolder.current()));
  }

  @PutMapping("/maintenance")
  @AuditAction(
      action = "system.maintenance.update",
      aggregateType = "system",
      aggregateId = "'maintenance'")
  public CommonResponse<MaintenanceStatusResponse> update(
      @Valid @RequestBody UpdateMaintenanceRequest request) {
    List<String> affected =
        request.getAffectedServices() == null ? List.of() : request.getAffectedServices();
    MaintenanceState next =
        new MaintenanceState(
            request.isEnabled(),
            request.isReadOnly(),
            request.getMessage(),
            request.getEtaAt(),
            affected);
    MaintenanceState applied = stateHolder.update(next);
    return responseFactory.success(toResponse(applied));
  }

  private static MaintenanceStatusResponse toResponse(MaintenanceState state) {
    return new MaintenanceStatusResponse(
        state.enabled(),
        state.readOnly(),
        state.message(),
        state.etaAt() != null ? state.etaAt().toString() : null,
        state.affectedServices());
  }
}
