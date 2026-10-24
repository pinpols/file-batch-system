package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitingLaunchSummary;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已持久化作业依赖等待记录的租户隔离只读运维视图。 */
@RestController
@RequestMapping("/internal/job-dependency-waits")
@RequiredArgsConstructor
public class JobDependencyWaitingLaunchController {

  private final JobDependencyWaitingLaunchService waitingLaunchService;

  @GetMapping
  public List<JobDependencyWaitingLaunchSummary> list(
      @RequestParam String tenantId,
      @RequestParam(required = false) String waitStatus,
      @RequestParam(required = false) String jobCode,
      @RequestParam(defaultValue = "100") int limit) {
    return waitingLaunchService.listRecent(tenantId, waitStatus, jobCode, limit);
  }
}
