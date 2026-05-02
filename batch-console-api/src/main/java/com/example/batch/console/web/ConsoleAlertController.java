package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.monitor.ConsoleAlertApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.ops.AlertActionRequest;
import com.example.batch.console.web.response.ops.ConsoleAlertActionResponse;
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
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleAlertController {

  private final ConsoleAlertApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 确认告警。 */
  @PostMapping("/{alertId}/ack")
  public CommonResponse<ConsoleAlertActionResponse> ack(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable Long alertId,
      @Valid @RequestBody AlertActionRequest request) {
    return responseFactory.success(applicationService.ack(alertId, request, idempotencyKey));
  }

  /** 静默告警。 */
  @PostMapping("/{alertId}/silence")
  public CommonResponse<ConsoleAlertActionResponse> silence(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable Long alertId,
      @Valid @RequestBody AlertActionRequest request) {
    return responseFactory.success(applicationService.silence(alertId, request, idempotencyKey));
  }

  /** 关闭告警。 */
  @PostMapping("/{alertId}/close")
  public CommonResponse<ConsoleAlertActionResponse> close(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable Long alertId,
      @Valid @RequestBody AlertActionRequest request) {
    return responseFactory.success(applicationService.close(alertId, request, idempotencyKey));
  }
}
