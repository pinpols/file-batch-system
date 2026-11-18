package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorAsyncConfiguration;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按租户恢复名额等待，不暂停 Kafka 分区，也不更改原业务日期。
 *
 * <p>同一轮内不同 requestId 并发启动：每条等待都要跑一次完整 launch 事务，串行循环会让单租户恢复吞吐
 * 退化到单线程水平。租约（{@code claimLeaseSeconds}）保证同一 requestId 不会被重复认领。
 */
@Slf4j
@Component
public class WorkflowLaunchWaitReconciler {
  private final TenantDirectoryMapper tenantDirectoryMapper;
  private final WorkflowLaunchWaitService waitingService;
  private final WorkflowRunLaunchService launchService;
  private final WorkflowLaunchWaitProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final Executor resumeExecutor;

  public WorkflowLaunchWaitReconciler(
      TenantDirectoryMapper tenantDirectoryMapper,
      WorkflowLaunchWaitService waitingService,
      WorkflowRunLaunchService launchService,
      WorkflowLaunchWaitProperties properties,
      OrchestratorGracefulShutdown gracefulShutdown,
      @Qualifier(OrchestratorAsyncConfiguration.LAUNCH_WAIT_RESUME_EXECUTOR)
          Executor resumeExecutor) {
    this.tenantDirectoryMapper = tenantDirectoryMapper;
    this.waitingService = waitingService;
    this.launchService = launchService;
    this.properties = properties;
    this.gracefulShutdown = gracefulShutdown;
    this.resumeExecutor = resumeExecutor;
  }

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
    reconcileTenant(tenantId, properties.getBatchSize());
  }

  /** 终态提交后立即补槽：只处理较小批次，把剩余等待留给定时扫描，避免阻塞完成线程。 */
  public void resumeTenant(String tenantId) {
    reconcileTenant(tenantId, properties.getResumeBatchSize());
  }

  public void reconcileTenant(String tenantId, int limit) {
    RlsTenantContextHolder.runWithTenant(tenantId, () -> reconcileTenantInContext(tenantId, limit));
  }

  private void reconcileTenantInContext(String tenantId, int limit) {
    Instant retryCutoffBaseTime = BatchDateTimeSupport.utcNow();
    long retryDelaySeconds = properties.getRetryDelaySeconds();
    Instant claimLease = retryCutoffBaseTime.plusSeconds(properties.getClaimLeaseSeconds());
    List<WorkflowLaunchWaitClaim> waiting = waitingService.claimDue(tenantId, limit, claimLease);
    if (EmptyChecks.isEmpty(waiting)) {
      return;
    }
    CompletableFuture<?>[] launches = new CompletableFuture<?>[waiting.size()];
    for (int index = 0; index < waiting.size(); index++) {
      WorkflowLaunchWaitClaim entry = waiting.get(index);
      launches[index] = CompletableFuture.runAsync(
          () ->
              // Worker 线程没有调用方的租户上下文，RLS 必须逐条绑定。
              RlsTenantContextHolder.runWithTenant(
                  tenantId, () -> reconcileClaimInTenant(tenantId, entry, retryDelaySeconds)),
          resumeExecutor);
    }
    // 等本轮全部结束再返回：租约边界与“一轮补满”的既有语义都依赖这个同步点。
    CompletableFuture.allOf(launches).join();
  }

  private void reconcileClaimInTenant(
      String tenantId, WorkflowLaunchWaitClaim entry, long retryDelaySeconds) {
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
      releaseForRetry(tenantId, entry.requestId(), retryDelaySeconds);
      log.warn(
          "workflow launch wait decrypt deferred: tenantId={}, requestId={}, error={}",
          tenantId,
          entry.requestId(),
          SwallowedExceptionLogger.summary(unavailableProtection));
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
      releaseForRetry(tenantId, entry.requestId(), retryDelaySeconds);
      log.warn(
          "workflow launch wait retry deferred: tenantId={}, requestId={}, error={}",
          tenantId,
          entry.requestId(),
          SwallowedExceptionLogger.summary(retryable));
    }
  }

  private void releaseForRetry(String tenantId, String requestId, long retryDelaySeconds) {
    waitingService.release(
        tenantId, requestId, BatchDateTimeSupport.utcNow().plusSeconds(retryDelaySeconds));
  }
}
