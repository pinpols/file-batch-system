package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.ConsoleSchedulerCommandResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleTriggerProxyService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/scheduler")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
@Idempotent
public class ConsoleSchedulerController {

  private final ConsoleTriggerProxyService triggerProxyService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/status")
  @PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
  public CommonResponse<ConsoleSchedulerCommandResponse> status() {
    return responseFactory.success(triggerProxyService.schedulerStatus());
  }

  @PostMapping("/pause-all")
  @AuditAction(action = "scheduler.pauseAll", aggregateType = "scheduler")
  public CommonResponse<ConsoleSchedulerCommandResponse> pauseAll() {
    return responseFactory.success(triggerProxyService.schedulerPauseAll());
  }

  @PostMapping("/resume-all")
  @AuditAction(action = "scheduler.resumeAll", aggregateType = "scheduler")
  public CommonResponse<ConsoleSchedulerCommandResponse> resumeAll() {
    return responseFactory.success(triggerProxyService.schedulerResumeAll());
  }
}
