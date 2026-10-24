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
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers deferred launches after restart or a missed upstream-completion notification. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobDependencyWaitingLaunchReconciler {

  private static final int TENANT_BATCH_SIZE = 100;

  private final TenantDirectoryMapper tenantDirectoryMapper;
  private final JobDependencyWaitingLaunchMapper waitingMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final JobDependencyWaitingLaunchService waitingLaunchService;
  private final ObjectProvider<LaunchService> launchServiceProvider;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AlertEventService alertEventService;
  private final BatchDateTimeSupport dateTimeSupport;

  @Value("${batch.job-dependency-wait.timeout-alert-seconds:86400}")
  private long timeoutAlertSeconds = 86_400L;

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
    if (EmptyChecks.isNull(tenantIds) || EmptyChecks.isEmpty(tenantIds)) {
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
        waitingMapper.selectWaiting(tenantId, TENANT_BATCH_SIZE);
    if (EmptyChecks.isNull(waiting) || EmptyChecks.isEmpty(waiting)) {
      return;
    }
    for (JobDependencyWaitingLaunchEntity entry : waiting) {
      reconcileOne(tenantId, entry);
    }
  }

  void reconcileOne(String tenantId, JobDependencyWaitingLaunchEntity entry) {
    TriggerRequestEntity triggerRequest =
        triggerRequestMapper.selectByTenantAndRequestId(tenantId, entry.requestId());
    if (EmptyChecks.isNull(triggerRequest)) {
      waitingMapper.markCancelled(tenantId, entry.requestId());
      return;
    }
    String requestStatus = triggerRequest.getRequestStatus();
    if (TriggerRequestStatus.REJECTED.code().equals(requestStatus)
        || TriggerRequestStatus.DUPLICATE.code().equals(requestStatus)
        || TriggerRequestStatus.GIVE_UP.code().equals(requestStatus)) {
      waitingMapper.markCancelled(tenantId, entry.requestId());
      return;
    }
    if (TriggerRequestStatus.LAUNCHED.code().equals(requestStatus)) {
      waitingMapper.markReleased(tenantId, entry.requestId());
      return;
    }

    String notReadyReason = waitingLaunchService.findNotReadyReason(
        tenantId, entry.jobCode(), entry.bizDate(), entry.dependencySnapshot());
    if (EmptyChecks.isNotNull(notReadyReason)) {
      waitingMapper.recordCheck(tenantId, entry.requestId(), notReadyReason);
      maybeEmitTimeoutAlert(tenantId, entry, notReadyReason);
      return;
    }

    try {
      LaunchRequest launchRequest = JsonUtils.fromJson(entry.launchPayload(), LaunchRequest.class);
      if (EmptyChecks.isNull(launchRequest)
          || !tenantId.equals(launchRequest.tenantId())
          || !entry.requestId().equals(launchRequest.requestId())
          || !entry.bizDate().equals(launchRequest.bizDate())) {
        waitingMapper.markCancelled(tenantId, entry.requestId());
        log.error(
            "invalid persisted dependency-wait payload; cancelled tenant={} requestId={}",
            tenantId,
            entry.requestId());
        return;
      }
      LaunchResponse response = launchServiceProvider.getObject().launch(launchRequest);
      if (EmptyChecks.isNotNull(response) && EmptyChecks.isNotNull(response.instanceNo())) {
        waitingMapper.markReleased(tenantId, entry.requestId());
      } else {
        waitingMapper.recordCheck(tenantId, entry.requestId(), "LAUNCH_BLOCKED_BY_OTHER_GATE");
      }
    } catch (Exception failure) {
      String reason = "LAUNCH_RETRY:" + SwallowedExceptionLogger.summary(failure);
      String boundedReason = reason.substring(0, Math.min(reason.length(), 500));
      waitingMapper.recordCheck(tenantId, entry.requestId(), boundedReason);
      log.warn(
          "deferred job launch retry failed: tenantId={}, requestId={}, error={}",
          tenantId,
          entry.requestId(),
          SwallowedExceptionLogger.summary(failure));
    }
  }

  private void maybeEmitTimeoutAlert(
      String tenantId, JobDependencyWaitingLaunchEntity entry, String notReadyReason) {
    if (timeoutAlertSeconds <= 0
        || EmptyChecks.isNull(entry.createdAt())
        || EmptyChecks.isNotNull(entry.timeoutAlertedAt())) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    if (now.isBefore(entry.createdAt().plusSeconds(timeoutAlertSeconds))) {
      return;
    }
    if (waitingMapper.claimTimeoutAlert(tenantId, entry.requestId(), now) != 1) {
      return;
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("jobCode", entry.jobCode());
    detail.put("bizDate", entry.bizDate().toString());
    detail.put("dependencySnapshot", entry.dependencySnapshot());
    detail.put("lastNotReadyReason", notReadyReason);
    detail.put("createdAt", entry.createdAt().toString());
    detail.put("timeoutSeconds", timeoutAlertSeconds);
    detail.put("requestId", entry.requestId());
    AlertEmitRequest alertRequest = AlertEmitRequest.builder()
        .tenantId(tenantId)
        .serviceName("batch-orchestrator")
        .alertType("BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT")
        .severity("ERROR")
        .title("job dependency wait exceeded timeout")
        .detailJson(JsonUtils.toJson(detail))
        .resourceKey(
            tenantId + ":" + entry.jobCode() + ":" + entry.bizDate() + ":" + entry.requestId())
        .build();
    alertEventService.emit(alertRequest);
  }
}
