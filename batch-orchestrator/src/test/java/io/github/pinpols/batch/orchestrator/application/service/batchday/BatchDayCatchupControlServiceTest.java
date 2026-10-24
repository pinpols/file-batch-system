package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.orchestrator.domain.param.BatchDayCatchupEventParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayCatchupPauseStateParam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("批量日追平暂停的幂等与审计")
class BatchDayCatchupControlServiceTest {

  private BatchDayCatchupControlMapper controlMapper;
  private BusinessCalendarMapper calendarMapper;
  private BatchDayCatchupControlService service;
  private final Instant now = Instant.parse("2026-05-05T00:30:00Z");

  @BeforeEach
  void setUp() {
    controlMapper = mock(BatchDayCatchupControlMapper.class);
    calendarMapper = mock(BusinessCalendarMapper.class);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    service = new BatchDayCatchupControlService(
        controlMapper,
        calendarMapper,
        noopTransactionManager(),
        new BatchDateTimeSupport(Clock.fixed(now, ZoneId.of("UTC")), timezoneProvider));
    when(calendarMapper.selectFirstByTenantAndCodeAndEnabled("t1", "CAL", true))
        .thenReturn(BusinessCalendarEntity.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .enabled(true)
            .build());
  }

  @DisplayName("暂停与操作者理由审计同事务记录")
  @Test
  void shouldRecordPauseWithOperatorAndReasonAtomically() {
    when(controlMapper.selectForUpdate("t1", "CAL")).thenReturn(state(false), state(true));
    when(controlMapper.updatePauseState(BatchDayCatchupPauseStateParam.builder().tenantId("t1").calendarCode("CAL").paused(true).pausedAt(now).pausedBy("ops-7").pauseReason("maintenance").updatedAt(now).build()))
        .thenReturn(1);
    when(controlMapper.selectCurrent("t1", "CAL")).thenReturn(state(true));

    BatchDayCatchupControlEntity result =
        service.change(new BatchDayCatchupControlService.PauseCommand(
            "t1", "CAL", "PAUSE", "ops-7", "maintenance"));

    assertThat(result.paused()).isTrue();
    verify(controlMapper).insertIfMissing("t1", "CAL");
    verify(controlMapper).insertEvent(BatchDayCatchupEventParam.builder().tenantId("t1").calendarCode("CAL").action("PAUSE").operatorId("ops-7").reason("maintenance").occurredAt(now).build());
  }

  @DisplayName("恢复保留原暂停证据，并用归一化后的当前操作信息记录审计")
  @Test
  void shouldPreservePauseEvidenceWhenResumingWithNormalizedCommand() {
    Instant pausedAt = now.minusSeconds(3600);
    BatchDayCatchupControlEntity paused = new BatchDayCatchupControlEntity(
        "t1", "CAL", true, pausedAt, "original-operator", "original-reason", pausedAt);
    BatchDayCatchupControlEntity resumed = new BatchDayCatchupControlEntity(
        "t1", "CAL", false, pausedAt, "original-operator", "original-reason", now);
    when(controlMapper.selectForUpdate("t1", "CAL")).thenReturn(paused);
    when(controlMapper.updatePauseState(BatchDayCatchupPauseStateParam.builder().tenantId("t1").calendarCode("CAL").paused(false).pausedAt(pausedAt).pausedBy("original-operator").pauseReason("original-reason").updatedAt(now).build()))
        .thenReturn(1);
    when(controlMapper.selectCurrent("t1", "CAL")).thenReturn(resumed);
    BatchDayCatchupControlService.PauseCommand command =
        new BatchDayCatchupControlService.PauseCommand(
            " t1 ", " CAL ", " resume ", " ops-8 ", " recovered ");

    BatchDayCatchupControlEntity result = service.change(command);

    assertThat(result).isEqualTo(resumed);
    verify(controlMapper).setTenantContext("t1");
    verify(controlMapper).updatePauseState(BatchDayCatchupPauseStateParam.builder().tenantId("t1").calendarCode("CAL").paused(false).pausedAt(pausedAt).pausedBy("original-operator").pauseReason("original-reason").updatedAt(now).build());
    verify(controlMapper).insertEvent(BatchDayCatchupEventParam.builder().tenantId("t1").calendarCode("CAL").action("RESUME").operatorId("ops-8").reason("recovered").occurredAt(now).build());
  }

  @DisplayName("重复暂停保持幂等且不重复审计")
  @Test
  void shouldMakeRepeatedPauseIdempotentWithoutAnotherAuditEvent() {
    when(controlMapper.selectForUpdate("t1", "CAL"))
        .thenReturn(
            new BatchDayCatchupControlEntity("t1", "CAL", true, now, "ops-7", "maintenance", now));

    BatchDayCatchupControlEntity result =
        service.change(new BatchDayCatchupControlService.PauseCommand(
            "t1", "CAL", "PAUSE", "ops-8", "duplicate request"));

    assertThat(result.paused()).isTrue();
    verify(controlMapper, never())
        .updatePauseState(ArgumentMatchers.any(BatchDayCatchupPauseStateParam.class));
    verify(controlMapper, never())
        .insertEvent(ArgumentMatchers.any(BatchDayCatchupEventParam.class));
  }

  @DisplayName("预检后日历并发删除时将外键拒绝映射为冲突且不写审计")
  @Test
  void shouldMapCalendarReferenceRaceToConflict() {
    doThrow(new DataIntegrityViolationException(
            "violates fk_batch_day_catchup_control_business_calendar",
            new SQLException("calendar reference removed concurrently", "23503")))
        .when(controlMapper)
        .insertIfMissing("t1", "CAL");

    assertThatThrownBy(() -> service.change(new BatchDayCatchupControlService.PauseCommand(
            "t1", "CAL", "PAUSE", "ops-7", "maintenance")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(
            exception -> ((ResponseStatusException) exception).getStatusCode().value())
        .isEqualTo(409);
    verify(controlMapper, never())
        .insertEvent(ArgumentMatchers.any(BatchDayCatchupEventParam.class));
  }

  private BatchDayCatchupControlEntity state(boolean paused) {
    return new BatchDayCatchupControlEntity(
        "t1",
        "CAL",
        paused,
        paused ? now : null,
        paused ? "ops-7" : null,
        paused ? "maintenance" : null,
        now);
  }

  private static PlatformTransactionManager noopTransactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
