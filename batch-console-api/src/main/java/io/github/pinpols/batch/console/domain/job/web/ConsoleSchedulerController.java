package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleSchedulerCommandResponse;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleTriggerProxyService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/scheduler")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleSchedulerController {

  private final ConsoleTriggerProxyService triggerProxyService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/status")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<ConsoleSchedulerCommandResponse> status() {
    return responseFactory.success(
        ConsoleSchedulerCommandResponse.from(triggerProxyService.schedulerStatus()));
  }

  @PostMapping("/pause-all")
  @AuditAction(action = "scheduler.pauseAll", aggregateType = "scheduler")
  public CommonResponse<ConsoleSchedulerCommandResponse> pauseAll() {
    return responseFactory.success(
        ConsoleSchedulerCommandResponse.from(triggerProxyService.schedulerPauseAll()));
  }

  @PostMapping("/resume-all")
  @AuditAction(action = "scheduler.resumeAll", aggregateType = "scheduler")
  public CommonResponse<ConsoleSchedulerCommandResponse> resumeAll() {
    return responseFactory.success(
        ConsoleSchedulerCommandResponse.from(triggerProxyService.schedulerResumeAll()));
  }
}
