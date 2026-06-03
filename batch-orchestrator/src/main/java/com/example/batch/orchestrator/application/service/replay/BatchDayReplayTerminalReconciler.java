package com.example.batch.orchestrator.application.service.replay;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import com.example.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import com.example.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-020 §决策 §实施分阶段 Stage 5 — job_instance 终态钩子里的 replay 回填器。
 *
 * <p>调用方（{@code DefaultTaskOutcomeService}）在 isTerminalJobInstanceStatus 分支里检查 {@code
 * jobInstance.replaySessionId}，非空时调本 reconciler：
 *
 * <ul>
 *   <li>按 (sessionId, tenant, jobCode) 反查 entry；
 *   <li>更新 entry.status：SUCCESS / PARTIAL_FAILED → SUCCEEDED；FAILED / CANCELLED / TERMINATED →
 *       FAILED；
 *   <li>回填 entry.rerun_instance_id / finished_at；
 *   <li>recompute session 计数（succeeded/failed/in_flight）；
 *   <li>所有 entry 进入终态 → session 推到 SUCCEEDED（无失败）/ PARTIAL_FAILED（有失败）。
 * </ul>
 *
 * <p>独立 REQUIRES_NEW 事务：避免 outer task outcome 链路被 replay 副作用污染（同时也保证 outer 失败 rollback 不影响 replay
 * 计数推进）。
 */
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
  private static final String SESSION_PARTIAL_FAILED = "PARTIAL_FAILED";

  private final BatchDayReplaySessionMapper sessionMapper;
  private final BatchDayReplayEntryMapper entryMapper;
  private final BatchDateTimeSupport dateTimeSupport;

  /** 入口：tenant + replaySessionId + jobCode 定位 entry，按 instance terminal 状态推进。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reconcileOnTerminal(
      String tenantId,
      Long replaySessionId,
      String jobCode,
      Long jobInstanceId,
      String instanceStatus) {
    if (!Texts.hasText(tenantId)
        || replaySessionId == null
        || !Texts.hasText(jobCode)
        || !Texts.hasText(instanceStatus)) {
      return;
    }
    BatchDayReplaySessionEntity session = sessionMapper.selectById(tenantId, replaySessionId);
    if (session == null) {
      log.warn(
          "replay terminal reconcile: session not found; tenantId={}, sessionId={}, jobCode={}",
          tenantId,
          replaySessionId,
          jobCode);
      return;
    }
    BatchDayReplayEntryEntity entry = findEntry(replaySessionId, tenantId, jobCode, jobInstanceId);
    if (entry == null) {
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
    String entryStatus = mapInstanceTerminalToEntryStatus(instanceStatus);
    entryMapper.updateStatus(entry.id(), entryStatus, jobInstanceId, null, null, null, now, now);
    advanceSessionCounts(session, now);
  }

  /**
   * 三级查找(P0-2 修复优先级):
   *
   * <ol>
   *   <li>rerun_instance_id == jobInstanceId 精确反查(本 reconciler 第一次执行就会回填
   *       rerun_instance_id,后续重入/重试天然命中);
   *   <li>(sessionId, sourceInstanceId == jobInstanceId 的父 source) 精确反查 —— 兜底覆盖单次首报场景,只要
   *       jobInstanceId 是 rerun 实例本身,会与 entry.rerun_instance_id 匹配走分支 1;若上游传的是 source 实例 id(legacy
   *       路径),走分支 2;
   *   <li>退化到 (sessionId, tenantId, jobCode) 线性扫第一条 —— 仅在 source/rerun 都对不上时使用,保留向后兼容。
   * </ol>
   *
   * <p>注:当前 schema {@code uk_replay_entry_session_job} 阻止同 jobCode 多 entry,所以分支 3 实际不会
   * 误命中重复行;若未来放宽该约束(允许同 jobCode 多 source instance 区分),分支 1/2 仍能精确定位。
   */
  private BatchDayReplayEntryEntity findEntry(
      Long sessionId, String tenantId, String jobCode, Long jobInstanceId) {
    // 1) rerun 命中(重入场景)
    if (jobInstanceId != null) {
      BatchDayReplayEntryEntity byRerun =
          entryMapper.selectByRerunInstanceId(tenantId, jobInstanceId);
      if (byRerun != null && sessionId.equals(byRerun.sessionId())) {
        return byRerun;
      }
      // 2) source 命中(首报且上游误传 sourceInstanceId 而非 rerunInstanceId)
      BatchDayReplayEntryEntity bySource =
          entryMapper.selectBySessionAndSourceInstanceId(sessionId, tenantId, jobInstanceId);
      if (bySource != null) {
        return bySource;
      }
    }
    // 3) jobCode 兜底
    List<BatchDayReplayEntryEntity> all = entryMapper.selectBySessionId(sessionId);
    if (all == null) {
      return null;
    }
    for (BatchDayReplayEntryEntity entry : all) {
      if (entry == null) {
        continue;
      }
      if (tenantId.equals(entry.tenantId()) && jobCode.equals(entry.jobCode())) {
        return entry;
      }
    }
    return null;
  }

  /**
   * SUCCESS / PARTIAL_FAILED → entry SUCCEEDED；FAILED / CANCELLED / TERMINATED → entry
   * FAILED；其余意外状态保留 RUNNING（极少见，下次再来）。
   */
  private String mapInstanceTerminalToEntryStatus(String instanceStatus) {
    if (JobInstanceStatus.SUCCESS.code().equals(instanceStatus)
        || JobInstanceStatus.PARTIAL_FAILED.code().equals(instanceStatus)) {
      return ENTRY_SUCCEEDED;
    }
    if (JobInstanceStatus.FAILED.code().equals(instanceStatus)
        || JobInstanceStatus.CANCELLED.code().equals(instanceStatus)
        || JobInstanceStatus.TERMINATED.code().equals(instanceStatus)) {
      return ENTRY_FAILED;
    }
    return ENTRY_RUNNING;
  }

  /** 重读各状态计数；全部终态 → session 推到 SUCCEEDED / PARTIAL_FAILED；否则更新计数继续 RUNNING。 */
  private void advanceSessionCounts(BatchDayReplaySessionEntity session, Instant now) {
    long succeeded = entryMapper.countBySessionAndStatus(session.id(), ENTRY_SUCCEEDED);
    long failed = entryMapper.countBySessionAndStatus(session.id(), ENTRY_FAILED);
    long pending = entryMapper.countBySessionAndStatus(session.id(), ENTRY_PENDING);
    long running = entryMapper.countBySessionAndStatus(session.id(), ENTRY_RUNNING);
    long inFlight = pending + running;
    sessionMapper.updateCounts(
        session.tenantId(),
        session.id(),
        (int) succeeded,
        (int) failed,
        (int) inFlight,
        session.totalCount(),
        now);
    if (inFlight == 0L && SESSION_RUNNING.equals(session.status())) {
      String terminalStatus = failed > 0L ? SESSION_PARTIAL_FAILED : SESSION_SUCCEEDED;
      int updated =
          sessionMapper.updateStatus(
              session.tenantId(),
              session.id(),
              terminalStatus,
              List.of(SESSION_RUNNING),
              null,
              now,
              null,
              now);
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
  }
}
