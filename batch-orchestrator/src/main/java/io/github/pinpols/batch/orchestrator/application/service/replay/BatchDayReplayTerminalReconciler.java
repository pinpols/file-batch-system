package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.BatchDayReplayExecutionMode;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplayEntryFailRejectedLaunchesParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplaySessionUpdateCountsParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplayEntryStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplaySessionStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在终态 REPORT 的同一事务中协调指定的补跑执行。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDayReplayTerminalReconciler {

  private static final String ENTRY_SUCCEEDED = "SUCCEEDED";
  private static final String ENTRY_FAILED = "FAILED";
  private static final String ENTRY_PENDING = "PENDING";
  private static final String ENTRY_RUNNING = "RUNNING";
  private static final String SESSION_RUNNING = "RUNNING";
  private static final String SESSION_SUCCEEDED = "SUCCEEDED";
  private static final String SESSION_PARTIAL_FAILED = JobInstanceStatus.PARTIAL_FAILED.code();

  private final BatchDayReplaySessionMapper sessionMapper;
  private final BatchDayReplayEntryMapper entryMapper;
  private final ReplayOperationMapper replayOperationMapper;
  private final BatchDateTimeSupport dateTimeSupport;

  /** 入口：tenant + replaySessionId + jobCode 定位 entry，按当前执行 Job 的 terminal 状态推进。 */
  @Transactional(propagation = Propagation.REQUIRED)
  public void reconcileOnTerminal(
      String tenantId,
      Long replaySessionId,
      String jobCode,
      Long jobInstanceId,
      String instanceStatus) {
    boolean terminalReplayIdentityIsIncomplete = !Texts.hasText(tenantId)
        || EmptyChecks.isNull(replaySessionId)
        || EmptyChecks.isNull(jobInstanceId)
        || !Texts.hasText(jobCode)
        || !Texts.hasText(instanceStatus);
    if (terminalReplayIdentityIsIncomplete) {
      return;
    }
    replayOperationMapper.setTenantContext(tenantId);
    BatchDayReplaySessionEntity session = loadForUpdateWithOperationLock(tenantId, replaySessionId);
    if (EmptyChecks.isNull(session)) {
      log.warn(
          "replay terminal reconcile: session not found; tenantId={}, sessionId={}, jobCode={}",
          tenantId,
          replaySessionId,
          jobCode);
      return;
    }
    BatchDayReplayEntryEntity entry =
        entryMapper.selectByCurrentExecution(tenantId, replaySessionId, jobCode, jobInstanceId);
    if (EmptyChecks.isNull(entry)) {
      log.warn(
          "replay terminal reconcile: entry not found; sessionId={}, tenantId={}, jobCode={},"
              + " jobInstanceId={}",
          replaySessionId,
          tenantId,
          jobCode,
          jobInstanceId);
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    String entryStatus = mapInstanceTerminalToEntryStatus(session, instanceStatus);
    if (EmptyChecks.isNull(entryStatus)) return;
    UpdateBatchDayReplayEntryStatusParam statusUpdate =
        UpdateBatchDayReplayEntryStatusParam.completedRerun(
            entry.id(), entryStatus, jobInstanceId, now, now);
    entryMapper.updateStatus(statusUpdate);
    advanceSessionCounts(session, now);
  }

  /** 绑定异步创建的子作业，或结束已永久拒绝的准入记录。 */
  @Transactional
  public void reconcileAdmissions(String tenantId, Long sessionId) {
    replayOperationMapper.setTenantContext(tenantId);
    BatchDayReplaySessionEntity session = loadForUpdateWithOperationLock(tenantId, sessionId);
    if (EmptyChecks.isNull(session)) return;
    Instant now = dateTimeSupport.nowInstant();
    entryMapper.bindCurrentExecutions(tenantId, sessionId, now);
    BatchDayReplayEntryFailRejectedLaunchesParam batchDayReplayEntryFailRejectedLaunchesParam =
        BatchDayReplayEntryFailRejectedLaunchesParam.builder()
            .tenantId(tenantId)
            .sessionId(sessionId)
            .now(now)
            .failedStatus(ENTRY_FAILED)
            .rejectedStatuses(
                List.of(TriggerRequestStatus.REJECTED.code(), TriggerRequestStatus.GIVE_UP.code()))
            .build();
    entryMapper.failRejectedLaunches(batchDayReplayEntryFailRejectedLaunchesParam);
    // 提交前可能尚未创建启动请求；该条补跑记录仍需计入。
    advanceSessionCounts(session, now);
  }

  /**
   * SUCCESS / PARTIAL_FAILED → entry SUCCEEDED；FAILED / CANCELLED / TERMINATED → entry
   * FAILED；其余状态或模式不匹配不写入。
   */
  private String mapInstanceTerminalToEntryStatus(
      BatchDayReplaySessionEntity session, String instanceStatus) {
    boolean dryRun = BatchDayReplayExecutionMode.DRY_RUN.code().equals(session.executionMode());
    if (dryRun && JobInstanceStatus.SUCCESS_DRY_RUN.code().equals(instanceStatus)) {
      return ENTRY_SUCCEEDED;
    }
    if (dryRun && JobInstanceStatus.FAILED_DRY_RUN.code().equals(instanceStatus)) {
      return ENTRY_FAILED;
    }
    boolean isSuccessfulRealReplayExecution = !dryRun
        && (JobInstanceStatus.SUCCESS.code().equals(instanceStatus)
            || JobInstanceStatus.PARTIAL_FAILED.code().equals(instanceStatus));
    if (isSuccessfulRealReplayExecution) {
      return ENTRY_SUCCEEDED;
    }
    boolean isNonDryRunFailureTerminalStatus = !dryRun
        && (JobInstanceStatus.FAILED.code().equals(instanceStatus)
            || JobInstanceStatus.CANCELLED.code().equals(instanceStatus)
            || JobInstanceStatus.TERMINATED.code().equals(instanceStatus));
    if (isNonDryRunFailureTerminalStatus) {
      return ENTRY_FAILED;
    }
    log.error(
        "replay terminal mode mismatch: sessionId={}, executionMode={}, instanceStatus={}",
        session.id(),
        session.executionMode(),
        instanceStatus);
    return null;
  }

  /**
   * 重读各状态计数；全部终态时同步 session 终态。
   *
   * <p>实例允许在受控 retry/compensation 后从失败收敛为成功，因此 entry 也可能从 FAILED 改为 SUCCEEDED。
   * 此时 session 可能已经是 PARTIAL_FAILED，必须随最新 entry 真相重新收敛，避免状态与计数永久矛盾。
   */
  private void advanceSessionCounts(BatchDayReplaySessionEntity session, Instant now) {
    long succeeded = entryMapper.countBySessionAndStatus(session.id(), ENTRY_SUCCEEDED);
    long failed = entryMapper.countBySessionAndStatus(session.id(), ENTRY_FAILED);
    long pending = entryMapper.countBySessionAndStatus(session.id(), ENTRY_PENDING);
    long running = entryMapper.countBySessionAndStatus(session.id(), ENTRY_RUNNING);
    long inFlight = pending + running;
    BatchDayReplaySessionUpdateCountsParam batchDayReplaySessionUpdateCountsParam =
        BatchDayReplaySessionUpdateCountsParam.builder()
            .tenantId(session.tenantId())
            .id(session.id())
            .succeededCount((int) succeeded)
            .failedCount((int) failed)
            .inFlightCount((int) inFlight)
            .totalCount(session.totalCount())
            .now(now)
            .build();
    sessionMapper.updateCounts(batchDayReplaySessionUpdateCountsParam);
    boolean replaySessionCanComplete = inFlight == 0L
        && (SESSION_RUNNING.equals(session.status())
            || SESSION_PARTIAL_FAILED.equals(session.status()));
    if (replaySessionCanComplete) {
      String terminalStatus = failed > 0L ? SESSION_PARTIAL_FAILED : SESSION_SUCCEEDED;
      UpdateBatchDayReplaySessionStatusParam statusUpdate =
          UpdateBatchDayReplaySessionStatusParam.completed(
              session.tenantId(),
              session.id(),
              terminalStatus,
              List.of(SESSION_RUNNING, SESSION_PARTIAL_FAILED),
              now);
      int updated = sessionMapper.updateStatus(statusUpdate);
      if (updated > 0) {
        log.info(
            "replay session completed: tenantId={}, sessionId={}, terminalStatus={},"
                + " succeeded={}, failed={}",
            session.tenantId(),
            session.id(),
            terminalStatus,
            succeeded,
            failed);
      }
    }
    if (EmptyChecks.isNotNull(session.replayOperationId())) {
      replayOperationMapper.refreshFromChildSessions(
          session.tenantId(), session.replayOperationId(), now);
    }
  }

  private BatchDayReplaySessionEntity loadForUpdateWithOperationLock(
      String tenantId, Long sessionId) {
    BatchDayReplaySessionEntity candidate = sessionMapper.selectById(tenantId, sessionId);
    if (EmptyChecks.isNull(candidate)) return null;
    if (EmptyChecks.isNotNull(candidate.replayOperationId())
        && EmptyChecks.isNull(replayOperationMapper.lockForProgressRefresh(
            tenantId, candidate.replayOperationId()))) {
      throw new IllegalStateException(
          "replay operation not found while reconciling child session " + sessionId);
    }
    BatchDayReplaySessionEntity locked = sessionMapper.selectForUpdate(tenantId, sessionId);
    if (EmptyChecks.isNull(locked)) return null;
    if (!Objects.equals(candidate.replayOperationId(), locked.replayOperationId())) {
      throw new IllegalStateException(
          "replay operation changed while reconciling child session " + sessionId);
    }
    return locked;
  }
}
