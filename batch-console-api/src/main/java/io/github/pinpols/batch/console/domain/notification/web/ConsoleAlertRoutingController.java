package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertRoutingApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import io.github.pinpols.batch.console.web.request.config.AlertRoutingSaveRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/alert-routings")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleAlertRoutingController {

  private final ConsoleAlertRoutingApplicationService alertRoutingApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "routeCode", required = false) String routeCode,
      @RequestParam(value = "team", required = false) String team,
      @RequestParam(value = "severity", required = false) String severity,
      @RequestParam(value = "enabled", required = false) Boolean enabled,
      @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return responseFactory.success(
        alertRoutingApplicationService.list(
            tenantId, routeCode, team, severity, enabled, pageNo, pageSize));
  }

  @PostMapping
  @AuditAction(
      action = "alertRouting.create",
      aggregateType = "alert_routing",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<Map<String, Object>> create(
      @Valid @RequestBody AlertRoutingSaveRequest request) {
    return responseFactory.success(alertRoutingApplicationService.create(request));
  }

  @PutMapping("/{id}")
  @AuditAction(
      action = "alertRouting.update",
      aggregateType = "alert_routing",
      aggregateId = "#id",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<Map<String, Object>> update(
      @PathVariable Long id, @Valid @RequestBody AlertRoutingSaveRequest request) {
    return responseFactory.success(alertRoutingApplicationService.update(id, request));
  }

  @PostMapping("/{id}/toggle")
  @AuditAction(
      action = "alertRouting.toggle",
      aggregateType = "alert_routing",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<Void> toggle(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("enabled") Boolean enabled) {
    alertRoutingApplicationService.toggle(id, tenantId, enabled);
    return responseFactory.success(null);
  }
}
