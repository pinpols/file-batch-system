package com.example.batch.console.domain.job.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.domain.ops.application.ConsoleTriggerProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import java.util.Map;
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
  public CommonResponse<Map<String, String>> status() {
    return responseFactory.success(triggerProxyService.schedulerStatus());
  }

  @PostMapping("/pause-all")
  @AuditAction(action = "scheduler.pauseAll", aggregateType = "scheduler")
  public CommonResponse<Map<String, String>> pauseAll() {
    return responseFactory.success(triggerProxyService.schedulerPauseAll());
  }

  @PostMapping("/resume-all")
  @AuditAction(action = "scheduler.resumeAll", aggregateType = "scheduler")
  public CommonResponse<Map<String, String>> resumeAll() {
    return responseFactory.success(triggerProxyService.schedulerResumeAll());
  }
}
