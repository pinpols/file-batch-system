package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleJobDependencyWaitResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 等待上游结果的作业只读运维视图，并按租户隔离。 */
@RestController
@Validated
@RequestMapping("/api/console/job-dependency-waits")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN)
@RequiredArgsConstructor
public class ConsoleJobDependencyWaitController {

  private final ConsoleOrchestratorPort orchestrator;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<List<ConsoleJobDependencyWaitResponse>> list(
      @RequestParam String tenantId,
      @RequestParam(required = false) String waitStatus,
      @RequestParam(required = false) String jobCode,
      @RequestParam(defaultValue = "100") int limit) {
    return responseFactory.success(
        orchestrator.listJobDependencyWaits(tenantId, waitStatus, jobCode, limit));
  }
}
