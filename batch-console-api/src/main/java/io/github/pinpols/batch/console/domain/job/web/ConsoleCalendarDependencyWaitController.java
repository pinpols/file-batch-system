package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleCalendarDependencyWaitResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console read and audited action requests for cross-calendar waits. */
@RestController
@Validated
@RequestMapping("/api/console/calendar-dependency-waits")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
@Idempotent
public class ConsoleCalendarDependencyWaitController {

  private final ConsoleOrchestratorPort orchestrator;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver metadataResolver;

  @GetMapping
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
  public CommonResponse<List<ConsoleCalendarDependencyWaitResponse>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "calendarCode", required = false) String calendarCode,
      @RequestParam(value = "waitStatus", defaultValue = "WAITING") String waitStatus,
      @RequestParam(value = "limit", defaultValue = "100") int limit) {
    return responseFactory.success(
        orchestrator.listCalendarDependencyWaits(tenantId, calendarCode, waitStatus, limit));
  }

  @PostMapping("/manual-action")
  public CommonResponse<ConsoleCalendarDependencyWaitResponse> requestManualAction(
      @Valid @RequestBody ManualActionRequest request) {
    ConsoleCalendarDependencyWaitResponse response =
        orchestrator.requestCalendarDependencyWaitAction(
            request.tenantId(),
            request.calendarCode(),
            request.bizDate(),
            request.action(),
            metadataResolver.current().operatorId(),
            request.reason());
    return responseFactory.success(response);
  }

  public record ManualActionRequest(
      @NotBlank @Size(max = 64) String tenantId,
      @NotBlank @Size(max = 128) String calendarCode,
      @NotNull LocalDate bizDate,
      @NotBlank @Pattern(regexp = "^(RELEASE|SKIP)$") String action,
      @NotBlank @Size(max = 1024) String reason) {}
}
