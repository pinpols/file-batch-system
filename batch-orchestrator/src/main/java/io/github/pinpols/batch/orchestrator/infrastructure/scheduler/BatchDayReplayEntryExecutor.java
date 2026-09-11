package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import java.time.Instant;
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

  private static final String ENTRY_RUNNING = "RUNNING";
  private static final String ENTRY_FAILED = "FAILED";

  private final BatchDayReplayEntryMapper entryMapper;
  private final CompensationService compensationService;
  private final BatchDateTimeSupport dateTimeSupport;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void dispatch(BatchDayReplaySessionEntity session, BatchDayReplayEntryEntity entry) {
    Instant now = dateTimeSupport.nowInstant();
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId(session.tenantId())
        .compensationType("JOB")
        .targetId(entry.sourceInstanceId())
        .jobCode(entry.jobCode())
        .bizDate(session.bizDate())
        .reason("BATCH_DAY_REPLAY:" + session.reason())
        .operatorId(session.requestedBy())
        .resultPolicy(session.resultPolicy())
        .configVersionPolicy(session.configVersionPolicy())
        .configVersion(session.configVersion())
        .replaySessionId(session.id())
        .traceId(session.traceId())
        .build();
    try {
      compensationService.submit(command);
      entryMapper.updateStatus(entry.id(), ENTRY_RUNNING, null, null, null, now, null, now);
    } catch (RuntimeException submitFailure) {
      entryMapper.updateStatus(
          entry.id(),
          ENTRY_FAILED,
          null,
          null,
          truncate(submitFailure.getMessage(), 1024),
          now,
          now,
          now);
      log.warn(
          "batch_day_replay compensation submit failed: sessionId={}, entryId={}, jobCode={}, msg={}",
          session.id(),
          entry.id(),
          entry.jobCode(),
          submitFailure.getMessage());
    }
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return null;
    }
    return text.length() <= max ? text : text.substring(0, max);
  }
}
