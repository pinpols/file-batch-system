package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按租户恢复名额等待，不暂停 Kafka 分区，也不更改原业务日期。 */
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
    RlsTenantContextHolder.runWithTenant(tenantId, () -> reconcileTenantInContext(tenantId));
  }

  private void reconcileTenantInContext(String tenantId) {
    int batchSize = properties.getBatchSize();
    Instant retryCutoffBaseTime = BatchDateTimeSupport.utcNow();
    long retryDelaySeconds = properties.getRetryDelaySeconds();
    Instant claimCutoff = retryCutoffBaseTime.plusSeconds(retryDelaySeconds);
    List<WorkflowLaunchWaitClaim> waiting = waitingService.claimDue(
        tenantId,
        batchSize,
        claimCutoff);
    for (WorkflowLaunchWaitClaim entry : waiting) {
      reconcileClaimInTenant(tenantId, entry);
    }
  }

  private void reconcileClaimInTenant(String tenantId, WorkflowLaunchWaitClaim entry) {
    LaunchRequest request;
    try {
      request = waitingService.restoreRequest(tenantId, entry);
    } catch (BizException malformedPayload) {
      waitingService.reject(tenantId, entry.requestId());
      log.warn(
          "workflow launch wait payload rejected: tenantId={}, requestId={}, error={}",
          tenantId,
          entry.requestId(),
          SwallowedExceptionLogger.summary(malformedPayload));
      return;
    } catch (RuntimeException unavailableProtection) {
      log.warn("workflow launch wait decrypt deferred: tenantId={}, requestId={}, error={}",
          tenantId, entry.requestId(), SwallowedExceptionLogger.summary(unavailableProtection));
      return;
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
}
