package io.github.pinpols.batch.orchestrator.application.service.batchday;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
    when(controlMapper.updatePauseState("t1", "CAL", true, now, "ops-7", "maintenance", now))
        .thenReturn(1);
    when(controlMapper.selectCurrent("t1", "CAL")).thenReturn(state(true));

    BatchDayCatchupControlEntity result =
        service.change(new BatchDayCatchupControlService.PauseCommand(
            "t1", "CAL", "PAUSE", "ops-7", "maintenance"));

    assertThat(result.paused()).isTrue();
    verify(controlMapper).insertIfMissing("t1", "CAL");
    verify(controlMapper).insertEvent("t1", "CAL", "PAUSE", "ops-7", "maintenance", now);
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
        .updatePauseState(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(controlMapper, never())
        .insertEvent(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
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
