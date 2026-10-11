package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.request.config.AlertRoutingSaveRequest;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertRoutingApplicationService;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertRoutingQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertRoutingResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.shared.command.EnabledPatchRequest;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/alert-routings")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
@RequiredArgsConstructor
@Idempotent
public class ConsoleAlertRoutingController {

  private final ConsoleAlertRoutingApplicationService alertRoutingApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<PageResponse<ConsoleAlertRoutingResponse>> list(
      @Valid @ModelAttribute AlertRoutingQueryRequest request) {
    PageResponse<ConsoleAlertRoutingResponse> page = alertRoutingApplicationService.list(request);
    return responseFactory.success(page);
  }

  @PostMapping
  @AuditAction(
      action = "alertRouting.create",
      aggregateType = "alert_routing",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<ConsoleAlertRoutingResponse> create(
      @Valid @RequestBody AlertRoutingSaveRequest request) {
    return responseFactory.success(alertRoutingApplicationService.create(request));
  }

  @PutMapping("/{id}")
  @AuditAction(
      action = "alertRouting.update",
      aggregateType = "alert_routing",
      aggregateId = "#id",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<ConsoleAlertRoutingResponse> update(
      @PathVariable Long id, @Valid @RequestBody AlertRoutingSaveRequest request) {
    return responseFactory.success(alertRoutingApplicationService.update(id, request));
  }

  @PatchMapping("/{id}/enabled")
  @AuditAction(
      action = "alertRouting.toggle",
      aggregateType = "alert_routing",
      aggregateId = "#id",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<Void> setEnabled(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    alertRoutingApplicationService.toggle(id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }
}
