package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers slot waits per tenant without pausing a Kafka partition or changing the original date. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowLaunchWaitReconciler {
  private final TenantDirectoryMapper tenantDirectoryMapper;
  private final WorkflowLaunchWaitService waitingService;
  private final WorkflowRunLaunchService launchService;
  private final WorkflowLaunchWaitProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.workflow.launch-wait.poll-interval-millis:5000}")
  @SchedulerLock(name = "workflow_launch_wait_reconcile", lockAtMostFor = "PT5M")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    for (String tenantId : tenantDirectoryMapper.selectRunnableTenantIds()) {
      try {
        reconcileTenant(tenantId);
      } catch (RuntimeException failure) {
        log.warn(
            "workflow launch wait scan failed: tenantId={}, error={}",
            tenantId,
            SwallowedExceptionLogger.summary(failure));
      }
    }
  }

  public void reconcileTenant(String tenantId) {
    RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      List<WorkflowLaunchWaitClaim> waiting = waitingService.claimDue(
          tenantId,
          properties.getBatchSize(),
          BatchDateTimeSupport.utcNow().plusSeconds(properties.getRetryDelaySeconds()));
      for (WorkflowLaunchWaitClaim entry : waiting) {
        LaunchRequest request;
        try {
          request = JsonUtils.fromJson(entry.launchPayload(), LaunchRequest.class);
          if (!tenantId.equals(request.tenantId())
              || !entry.requestId().equals(request.requestId())) {
            throw new IllegalArgumentException("persisted launch identity mismatch");
          }
        } catch (RuntimeException malformedPayload) {
          waitingService.reject(tenantId, entry.requestId());
          log.warn(
              "workflow launch wait payload rejected: tenantId={}, requestId={}, error={}",
              tenantId,
              entry.requestId(),
              SwallowedExceptionLogger.summary(malformedPayload));
          continue;
        }
        try {
          launchService.launch(request);
        } catch (BizException rejected) {
          waitingService.reject(tenantId, entry.requestId());
          log.warn(
              "workflow launch wait rejected: tenantId={}, requestId={}, error={}",
              tenantId,
              entry.requestId(),
              SwallowedExceptionLogger.summary(rejected));
        } catch (RuntimeException retryable) {
          log.warn(
              "workflow launch wait retry deferred: tenantId={}, requestId={}, error={}",
              tenantId,
              entry.requestId(),
              SwallowedExceptionLogger.summary(retryable));
        }
      }
    });
  }
}
