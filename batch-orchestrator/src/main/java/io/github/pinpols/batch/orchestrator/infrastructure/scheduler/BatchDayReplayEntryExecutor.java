package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.enums.BatchDayReplayExecutionMode;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CompensationCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplayEntryStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 单条补批 entry 的独立事务执行器，确保一条失败不会回滚同批其他 entry。 */
@Slf4j
@Component
@RequiredArgsConstructor
class BatchDayReplayEntryExecutor {

  private record PlanSnapshot(Integer jobDefinitionVersion, Map<String, Object> defaultParams) {}

  private final BatchDayReplayEntryMapper entryMapper;
  private final CompensationCommandMapper compensationCommandMapper;
  private final CompensationService compensationService;
  private final BatchDateTimeSupport dateTimeSupport;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void dispatch(BatchDayReplaySessionEntity session, BatchDayReplayEntryEntity entry) {
    Instant now = dateTimeSupport.nowInstant();
    if (entryMapper.claimPending(entry.id(), session.tenantId(), session.id(), now) == 0) {
      return;
    }
    PlanSnapshot planSnapshot = entry.planSnapshot() == null
        ? null
        : JsonUtils.fromJson(entry.planSnapshot(), PlanSnapshot.class);
    boolean dryRun = BatchDayReplayExecutionMode.DRY_RUN.code().equals(session.executionMode());
    String batchNoOverride = dryRun || EmptyChecks.isNull(entry.sourceInstanceId())
        ? "dry-run-session-" + session.id() + "-entry-" + entry.id()
        : null;
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId(session.tenantId())
        .compensationType(entry.sourceInstanceId() == null ? "BATCH" : "JOB")
        .targetId(entry.sourceInstanceId())
        .jobCode(entry.jobCode())
        .bizDate(session.bizDate())
        .batchNo(batchNoOverride)
        .reason("BATCH_DAY_REPLAY:" + session.reason())
        .operatorId(session.requestedBy())
        .resultPolicy(session.resultPolicy())
        .configVersionPolicy(
            planSnapshot == null
                ? session.configVersionPolicy()
                : ConfigVersionPolicy.USE_SPECIFIED_VERSION.code())
        .configVersion(
            planSnapshot == null ? session.configVersion() : planSnapshot.jobDefinitionVersion())
        .strategy("SCHEDULE_PLAN".equals(session.candidateSource()) ? "SCHEDULE_PLAN" : null)
        .replaySessionId(session.id())
        .replayEntryId(entry.id())
        .launchParams(planSnapshot == null ? null : planSnapshot.defaultParams())
        .dryRun(dryRun)
        .traceId(session.traceId())
        .build();
    String commandNo = compensationService.submit(command);
    CompensationCommandEntity compensation =
        compensationCommandMapper.selectByCommandNo(session.tenantId(), commandNo);
    if (compensation != null && compensation.getRelatedJobInstanceId() != null) {
      UpdateBatchDayReplayEntryStatusParam statusUpdate =
          UpdateBatchDayReplayEntryStatusParam.running(
              entry.id(),
              compensation.getRelatedJobInstanceId(),
              now,
              dateTimeSupport.nowInstant());
      entryMapper.updateStatus(statusUpdate);
    }
  }

  /** 提交事务回滚后，以新事务记录条目失败，避免 rollback-only 吞掉失败状态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void markFailed(
      BatchDayReplaySessionEntity session, BatchDayReplayEntryEntity entry, Exception failure) {
    Instant now = dateTimeSupport.nowInstant();
    UpdateBatchDayReplayEntryStatusParam statusUpdate =
        UpdateBatchDayReplayEntryStatusParam.failedOnSubmission(
            entry.id(), truncate(failure.getMessage(), 1024), now);
    entryMapper.updateStatus(statusUpdate);
    Long sessionId = session.id();
    Long entryId = entry.id();
    String jobCode = entry.jobCode();
    String causeSummary = SwallowedExceptionLogger.summary(failure);
    log.warn(
        "batch_day_replay compensation submit failed: sessionId={}, entryId={}, jobCode={}, msg={}",
        sessionId,
        entryId,
        jobCode,
        causeSummary);
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return null;
    }
    return text.length() <= max ? text : text.substring(0, max);
  }
}
