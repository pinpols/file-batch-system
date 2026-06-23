package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertActionResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import io.github.pinpols.batch.console.web.request.ops.AlertActionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控制台告警治理 REST：确认、静默、关闭。 */
@RestController
@Validated
@RequestMapping("/api/console/alerts")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleAlertController {

  private final ConsoleAlertApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 确认告警。 */
  @PostMapping("/{alertId}/ack")
  @AuditAction(action = "alert.ack", aggregateType = "alert", aggregateId = "#alertId")
  public CommonResponse<ConsoleAlertActionResponse> ack(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable Long alertId,
      @Valid @RequestBody AlertActionRequest request) {
    return responseFactory.success(applicationService.ack(alertId, request, idempotencyKey));
  }

  /** 静默告警。 */
  @PostMapping("/{alertId}/silence")
  @AuditAction(action = "alert.silence", aggregateType = "alert", aggregateId = "#alertId")
  public CommonResponse<ConsoleAlertActionResponse> silence(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable Long alertId,
      @Valid @RequestBody AlertActionRequest request) {
    return responseFactory.success(applicationService.silence(alertId, request, idempotencyKey));
  }

  /** 关闭告警。 */
  @PostMapping("/{alertId}/close")
  @AuditAction(action = "alert.close", aggregateType = "alert", aggregateId = "#alertId")
  public CommonResponse<ConsoleAlertActionResponse> close(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable Long alertId,
      @Valid @RequestBody AlertActionRequest request) {
    return responseFactory.success(applicationService.close(alertId, request, idempotencyKey));
  }
}
