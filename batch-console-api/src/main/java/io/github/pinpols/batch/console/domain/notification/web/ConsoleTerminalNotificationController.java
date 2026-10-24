package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.TerminalDeliveryRetryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.TerminalDeliveryRecoveryResponse;
import io.github.pinpols.batch.console.domain.notification.service.TerminalNotificationRecoveryService;
import io.github.pinpols.batch.console.domain.notification.service.TerminalNotificationRecoveryService.Source;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/notifications/terminal-deliveries")
@RequiredArgsConstructor
public class ConsoleTerminalNotificationController {
  private final TerminalNotificationRecoveryService recovery;
  private final ConsoleResponseFactory responses;
  private final ConsoleRequestMetadataResolver metadata;

  @GetMapping
  @PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
  public CommonResponse<List<TerminalDeliveryRecoveryResponse>> list(
      @RequestParam @NotBlank String tenantId,
      @RequestParam(defaultValue = "100") @Positive @Max(500) int limit) {
    return responses.success(recovery.list(tenantId, limit));
  }

  @PostMapping("/{source}/{id}/retry")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public CommonResponse<Void> retry(@RequestParam @NotBlank String tenantId,
      @PathVariable Source source, @PathVariable @Positive Long id,
      @Valid @RequestBody TerminalDeliveryRetryRequest request) {
    recovery.retry(tenantId, source, id, request, metadata.current());
    return responses.success(null);
  }
}
