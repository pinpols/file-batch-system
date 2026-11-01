package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.notification.service.NotificationRoutePreviewService;
import io.github.pinpols.batch.console.domain.notification.service.NotificationRoutePreviewService.Preview;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/notifications")
@RequiredArgsConstructor
public class ConsoleNotificationRoutePreviewController {
  private final NotificationRoutePreviewService service;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/route-preview")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public CommonResponse<Preview> preview(
      @RequestParam @NotBlank String tenantId,
      @RequestParam
          @NotBlank
          @Size(max = 128)
          @Pattern(
              regexp =
                  "(?i)JOB_SUCCESS|JOB_FAILED|WORKFLOW_SUCCESS|WORKFLOW_FAILED|ALERT_ESCALATED")
          String eventType,
      @RequestParam(required = false) @Size(max = 128) String severity,
      @RequestParam(required = false) @Size(max = 128) String jobCode) {
    return responseFactory.success(service.preview(tenantId, eventType, severity, jobCode));
  }
}
