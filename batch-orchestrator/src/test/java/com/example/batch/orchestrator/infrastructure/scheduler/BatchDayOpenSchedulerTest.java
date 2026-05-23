package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.CalendarDependencyEntity;
import com.example.batch.orchestrator.domain.entity.DisasterDayOverrideEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BusinessCalendarMapper;
import com.example.batch.orchestrator.mapper.CalendarDependencyMapper;
import com.example.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.service.BatchDayTimePolicyResolver;
import com.example.batch.orchestrator.service.CutoffScheduleResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class BatchDayOpenSchedulerTest {

  private BusinessCalendarMapper businessCalendarMapper;
  private BatchDayInstanceMapper batchDayInstanceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private CalendarDependencyMapper calendarDependencyMapper;
  private DisasterDayOverrideMapper disasterDayOverrideMapper;
  private BatchDayOpenScheduler scheduler;

  @BeforeEach
  void setUp() {
    businessCalendarMapper = mock(BusinessCalendarMapper.class);
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    calendarDependencyMapper = mock(CalendarDependencyMapper.class);
    disasterDayOverrideMapper = mock(DisasterDayOverrideMapper.class);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    scheduler =
        new BatchDayOpenScheduler(
            businessCalendarMapper,
            batchDayInstanceMapper,
            jobExecutionLogMapper,
            gracefulShutdown,
            timezoneProvider,
            new BatchDayTimePolicyResolver(timezoneProvider, new CutoffScheduleResolver()),
            new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
            calendarDependencyMapper,
            disasterDayOverrideMapper,
            noopTransactionManager());
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

  @Test
  void shouldOpenCurrentBizDateAfterCutoff() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z"); // 08:30 Asia/Shanghai
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5)))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    BatchDayInstanceEntity opened = captor.getValue();
    assertThat(opened.dayStatus()).isEqualTo("OPEN");
    assertThat(opened.bizDate()).isEqualTo(LocalDate.of(2026, 5, 5));
    assertThat(opened.timezoneSnapshot()).isEqualTo("Asia/Shanghai");
    assertThat(opened.cutoffAt()).isEqualTo(Instant.parse("2026-05-05T22:00:00Z"));
    assertThat(opened.slaDeadlineAt()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    verify(jobExecutionLogMapper).insert(any());
  }

  @Test
  void shouldOpenPreviousBizDateBeforeCutoff() {
    Instant now = Instant.parse("2026-05-04T21:30:00Z"); // 05:30 Asia/Shanghai
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    assertThat(captor.getValue().bizDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(captor.getValue().cutoffAt()).isEqualTo(Instant.parse("2026-05-04T22:00:00Z"));
  }

  @Test
  void shouldDeferOpenWhenUpstreamCalendarNotSettled() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5)))
        .thenReturn(null);
    when(calendarDependencyMapper.selectEnabledByDownstream("t1", "CAL"))
        .thenReturn(
            List.of(
                CalendarDependencyEntity.builder()
                    .id(1L)
                    .tenantId("t1")
                    .upstreamCode("CAL_CN")
                    .downstreamCode("CAL")
                    .rule("WAIT_SETTLED")
                    .enabled(true)
                    .build()));
    BatchDayInstanceEntity upstreamRunning =
        BatchDayInstanceEntity.builder()
            .id(99L)
            .tenantId("t1")
            .calendarCode("CAL_CN")
            .bizDate(LocalDate.of(2026, 5, 5))
            .dayStatus("IN_FLIGHT")
            .build();
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL_CN", LocalDate.of(2026, 5, 5)))
        .thenReturn(upstreamRunning);

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper, never()).insert(any());
  }

  @Test
  void shouldOpenWhenUpstreamCalendarSettled() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5)))
        .thenReturn(null);
    when(calendarDependencyMapper.selectEnabledByDownstream("t1", "CAL"))
        .thenReturn(
            List.of(
                CalendarDependencyEntity.builder()
                    .id(1L)
                    .tenantId("t1")
                    .upstreamCode("CAL_CN")
                    .downstreamCode("CAL")
                    .rule("WAIT_SETTLED")
                    .enabled(true)
                    .build()));
    BatchDayInstanceEntity upstreamSettled =
        BatchDayInstanceEntity.builder()
            .id(99L)
            .tenantId("t1")
            .calendarCode("CAL_CN")
            .bizDate(LocalDate.of(2026, 5, 5))
            .dayStatus("SETTLED")
            .build();
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL_CN", LocalDate.of(2026, 5, 5)))
        .thenReturn(upstreamSettled);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper).insert(any());
  }

  @Test
  void shouldWriteSkippedDayWhenDisasterOverrideSkip() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5)))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5), now))
        .thenReturn(
            DisasterDayOverrideEntity.builder()
                .id(1L)
                .tenantId("t1")
                .calendarCode("CAL")
                .bizDate(LocalDate.of(2026, 5, 5))
                .action("SKIP")
                .reason("typhoon shutdown")
                .approvedBy("ops-1")
                .approvedAt(now)
                .effectiveAt(now)
                .ttlUntil(now.plusSeconds(86400))
                .build());
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("SKIPPED");
    assertThat(captor.getValue().operationReason()).contains("DISASTER_DAY_SKIP");
    assertThat(captor.getValue().operatedBy()).isEqualTo("ops-1");
  }

  @Test
  void shouldDeferOpenWhenDisasterOverrideDefer() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5)))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5), now))
        .thenReturn(
            DisasterDayOverrideEntity.builder()
                .id(2L)
                .tenantId("t1")
                .calendarCode("CAL")
                .bizDate(LocalDate.of(2026, 5, 5))
                .action("DEFER_TO_NEXT_BIZDAY")
                .reason("system maintenance")
                .approvedBy("ops-2")
                .approvedAt(now)
                .effectiveAt(now)
                .ttlUntil(now.plusSeconds(86400))
                .build());

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper, never()).insert(any());
  }

  @Test
  void shouldSkipExistingBatchDay() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    BusinessCalendarEntity calendar = calendar();
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5)))
        .thenReturn(existing());

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper, never()).insert(any());
    verify(jobExecutionLogMapper, never()).insert(any());
  }

  private BusinessCalendarEntity calendar() {
    return new BusinessCalendarEntity(
        1L,
        "t1",
        "CAL",
        "Calendar",
        "Asia/Shanghai",
        "SKIP",
        "AUTO",
        30,
        LocalTime.of(6, 0),
        60,
        120,
        true);
  }

  private BatchDayInstanceEntity existing() {
    Instant at = Instant.parse("2026-05-05T00:00:00Z");
    return new BatchDayInstanceEntity(
        1L,
        "t1",
        "CAL",
        LocalDate.of(2026, 5, 5),
        "OPEN",
        at,
        at,
        null,
        null,
        0,
        0,
        "Asia/Shanghai",
        0L,
        at,
        at);
  }
}
