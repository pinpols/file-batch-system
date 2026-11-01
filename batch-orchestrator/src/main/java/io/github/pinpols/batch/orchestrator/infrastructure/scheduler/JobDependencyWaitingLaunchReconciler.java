package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.config.JobDependencyWaitProperties;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在进程重启或遗漏上游完成通知后恢复延迟启动。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobDependencyWaitingLaunchReconciler {

  private static final int TENANT_BATCH_SIZE = 100;

  private final TenantDirectoryMapper tenantDirectoryMapper;
  private final JobDependencyWaitingLaunchService waitingLaunchService;
  private final ObjectProvider<LaunchService> launchServiceProvider;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AlertEventService alertEventService;
  private final BatchDateTimeSupport dateTimeSupport;
  private final ObjectProvider<WorkflowNodeDispatchService> workflowDispatchProvider;

  private final JobDependencyWaitProperties dependencyWaitProperties;

  @Scheduled(fixedDelayString = "${batch.job-dependency-wait.reconcile-interval-millis:30000}")
  @SchedulerLock(
      name = "job_dependency_wait_reconcile",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT5S")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<String> tenantIds = tenantDirectoryMapper.selectRunnableTenantIds();
    if (EmptyChecks.isEmpty(tenantIds)) {
      return;
    }
    for (String tenantId : tenantIds) {
      try {
        reconcileTenant(tenantId);
      } catch (Exception failure) {
        log.warn(
            "job dependency wait reconcile failed for tenant={}: {}",
            tenantId,
            SwallowedExceptionLogger.summary(failure));
      }
    }
  }

  public void reconcileTenant(String tenantId) {
    RlsTenantContextHolder.runWithTenant(tenantId, () -> reconcileTenantInContext(tenantId));
  }

  private void reconcileTenantInContext(String tenantId) {
    List<JobDependencyWaitingLaunchEntity> waiting =
        waitingLaunchService.selectWaiting(tenantId, TENANT_BATCH_SIZE);
    for (JobDependencyWaitingLaunchEntity entry :
        EmptyChecks.isNull(waiting) ? List.<JobDependencyWaitingLaunchEntity>of() : waiting) {
      try {
        reconcileOne(tenantId, entry);
      } catch (RuntimeException failure) {
        log.warn(
            "job dependency wait entry failed: tenantId={}, requestId={}, error={}",
            tenantId,
            entry.requestId(),
            SwallowedExceptionLogger.summary(failure));
      }
    }
    for (String requestId :
        waitingLaunchService.selectRejectedForWorkflowProgression(tenantId, TENANT_BATCH_SIZE)) {
      try {
        workflowDispatchProvider.getObject().rejectDeferredChildRequest(tenantId, requestId);
      } catch (RuntimeException failure) {
        log.warn(
            "deferred Job rejection progression failed: tenantId={}, requestId={}, error={}",
            tenantId,
            requestId,
            SwallowedExceptionLogger.summary(failure));
      }
    }
  }

  void reconcileOne(String tenantId, JobDependencyWaitingLaunchEntity entry) {
    TriggerRequestEntity triggerRequest =
        waitingLaunchService.selectOriginalRequest(tenantId, entry.requestId());
    if (EmptyChecks.isNull(triggerRequest)) {
      waitingLaunchService.markWaitCancelled(tenantId, entry.requestId());
      return;
    }
    String requestStatus = triggerRequest.getRequestStatus();
    boolean launchRequestWasNotAccepted = TriggerRequestStatus.REJECTED.code().equals(requestStatus)
        || TriggerRequestStatus.DUPLICATE.code().equals(requestStatus)
        || TriggerRequestStatus.GIVE_UP.code().equals(requestStatus);
    if (launchRequestWasNotAccepted) {
      waitingLaunchService.markWaitCancelled(tenantId, entry.requestId());
      return;
    }
    if (TriggerRequestStatus.LAUNCHED.code().equals(requestStatus)) {
      waitingLaunchService.markWaitReleased(tenantId, entry.requestId());
      return;
    }

    Optional<LaunchRequest> restored =
        waitingLaunchService.restoreWaitRequest(entry, triggerRequest);
    if (!restored.isPresent()) {
      waitingLaunchService.rejectInvalidWait(entry);
      log.error(
          "invalid persisted dependency-wait payload; recovery refused tenant={} requestId={}",
          tenantId,
          entry.requestId());
      return;
    }

    String notReadyReason = waitingLaunchService.findNotReadyReason(
        tenantId, entry.jobCode(), entry.bizDate(), entry.dependencySnapshot());
    if (EmptyChecks.isNotNull(notReadyReason)) {
      waitingLaunchService.recordWaitCheck(tenantId, entry.requestId(), notReadyReason);
      maybeEmitTimeoutAlert(tenantId, entry, triggerRequest, notReadyReason);
      return;
    }

    try {
      LaunchRequest launchRequest = restored.get();
      LaunchResponse response = launchServiceProvider.getObject().launch(launchRequest);
      if (EmptyChecks.isNotNull(response) && EmptyChecks.isNotNull(response.instanceNo())) {
        waitingLaunchService.markWaitReleased(tenantId, entry.requestId());
      } else {
        waitingLaunchService.recordWaitCheck(
            tenantId, entry.requestId(), "LAUNCH_BLOCKED_BY_OTHER_GATE");
      }
    } catch (Exception failure) {
      String reason = "LAUNCH_RETRY:" + SwallowedExceptionLogger.summary(failure);
      String boundedReason = reason.substring(0, Math.min(reason.length(), 500));
      waitingLaunchService.recordWaitCheck(tenantId, entry.requestId(), boundedReason);
      log.warn(
          "deferred job launch retry failed: tenantId={}, requestId={}, error={}",
          tenantId,
          entry.requestId(),
          SwallowedExceptionLogger.summary(failure));
    }
  }

  private void maybeEmitTimeoutAlert(
      String tenantId,
      JobDependencyWaitingLaunchEntity entry,
      TriggerRequestEntity triggerRequest,
      String notReadyReason) {
    boolean dependencyWaitNeedsNoTimeoutAlert =
        dependencyWaitProperties.getTimeoutAlertSeconds() <= 0
            || EmptyChecks.isNull(entry.createdAt())
            || EmptyChecks.isNotNull(entry.timeoutAlertedAt());
    if (dependencyWaitNeedsNoTimeoutAlert) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    Instant timeoutAlertAt =
        entry.createdAt().plusSeconds(dependencyWaitProperties.getTimeoutAlertSeconds());
    if (now.isBefore(timeoutAlertAt)) {
      return;
    }
    if (waitingLaunchService.claimWaitTimeoutAlert(tenantId, entry.requestId(), now) != 1) {
      return;
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("jobCode", entry.jobCode());
    detail.put("bizDate", entry.bizDate().toString());
    detail.put("dependencySnapshot", entry.dependencySnapshot());
    detail.put("lastNotReadyReason", notReadyReason);
    detail.put("createdAt", entry.createdAt().toString());
    detail.put("timeoutSeconds", dependencyWaitProperties.getTimeoutAlertSeconds());
    detail.put("requestId", entry.requestId());
    if (EmptyChecks.isNotNull(triggerRequest.getParentWorkflowRunId())) {
      detail.put("workflowRunId", triggerRequest.getParentWorkflowRunId());
    }
    if (!EmptyChecks.isBlank(triggerRequest.getParentNodeCode())) {
      detail.put("nodeCode", triggerRequest.getParentNodeCode());
    }
    AlertEmitCommand alertRequest = AlertEmitCommand.builder()
        .tenantId(tenantId)
        .serviceName("batch-orchestrator")
        .alertType("BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT")
        .severity("ERROR")
        .title("job dependency wait exceeded timeout")
        .detailJson(JsonUtils.toJson(detail))
        .resourceKey(
            tenantId + ":" + entry.jobCode() + ":" + entry.bizDate() + ":" + entry.requestId())
        .traceId(triggerRequest.getTraceId())
        .build();
    alertEventService.emit(alertRequest);
  }
}
