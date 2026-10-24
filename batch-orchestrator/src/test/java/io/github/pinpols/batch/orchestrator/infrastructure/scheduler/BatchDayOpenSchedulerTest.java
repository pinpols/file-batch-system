package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.domain.param.BatchDayInstanceAdvanceOpenCursorParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.BatchDayScanProperties;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DisasterDayOverrideEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.metrics.BatchDayCatchupMetrics;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayTimePolicyResolver;
import io.github.pinpols.batch.orchestrator.service.CutoffScheduleResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("批次日自动开启调度的到期判定,覆盖截止时刻,上游日历结算依赖与灾难日覆盖动作的处理分支")
class BatchDayOpenSchedulerTest {

  private BusinessCalendarMapper businessCalendarMapper;
  private BatchDayInstanceMapper batchDayInstanceMapper;
  private BatchDayCatchupControlMapper catchupControlMapper;
  private BatchDayPlanCalendarMapper planCalendarMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private CalendarDependencyMapper calendarDependencyMapper;
  private CalendarDependencyWaitMapper calendarDependencyWaitMapper;
  private FileGroupExpectationService fileGroupExpectationService;
  private ExternalEventExpectationService externalEventExpectationService;
  private AlertEventService alertEventService;
  private BatchDayCatchupMetrics catchupMetrics;
  private DisasterDayOverrideMapper disasterDayOverrideMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private BatchDayOpenScheduler scheduler;

  @BeforeEach
  void setUp() {
    businessCalendarMapper = mock(BusinessCalendarMapper.class);
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    catchupControlMapper = mock(BatchDayCatchupControlMapper.class);
    AtomicReference<LocalDate> openCursor = new AtomicReference<>();
    doAnswer(invocation -> {
          openCursor.compareAndSet(null, invocation.getArgument(2));
          return 1;
        })
        .when(batchDayInstanceMapper)
        .initializeOpenCursor(any(), any(), any());
    when(batchDayInstanceMapper.selectOpenCursorForUpdate(any(), any()))
        .thenAnswer(invocation -> openCursor.get());
    when(batchDayInstanceMapper.advanceOpenCursor(org.mockito.ArgumentMatchers.any(BatchDayInstanceAdvanceOpenCursorParam.class)))
        .thenAnswer(invocation -> {
          BatchDayInstanceAdvanceOpenCursorParam parameters = invocation.getArgument(0);
          return openCursor.compareAndSet(parameters.expectedBizDate(), parameters.nextBizDate())
              ? 1 : 0;
        });
    planCalendarMapper = mock(BatchDayPlanCalendarMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    calendarDependencyMapper = mock(CalendarDependencyMapper.class);
    calendarDependencyWaitMapper = mock(CalendarDependencyWaitMapper.class);
    fileGroupExpectationService = mock(FileGroupExpectationService.class);
    externalEventExpectationService = mock(ExternalEventExpectationService.class);
    alertEventService = mock(AlertEventService.class);
    catchupMetrics = mock(BatchDayCatchupMetrics.class);
    disasterDayOverrideMapper = mock(DisasterDayOverrideMapper.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    scheduler = new BatchDayOpenScheduler(
        businessCalendarMapper,
        batchDayInstanceMapper,
        catchupControlMapper,
        planCalendarMapper,
        jobExecutionLogMapper,
        gracefulShutdown,
        timezoneProvider,
        new BatchDayTimePolicyResolver(timezoneProvider, new CutoffScheduleResolver()),
        new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
        calendarDependencyMapper,
        calendarDependencyWaitMapper,
        fileGroupExpectationService,
        externalEventExpectationService,
        alertEventService,
        catchupMetrics,
        disasterDayOverrideMapper,
        triggerRequestMapper,
        noopTransactionManager(), new BatchDayScanProperties());
  }

  private static PlatformTransactionManager noopTransactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {
        // no-op: scheduler tests use transaction callbacks only as boundaries.
      }

      @Override
      public void rollback(TransactionStatus status) {
        // no-op: scheduler tests use transaction callbacks only as boundaries.
      }
    };
  }

  @Test
  @DisplayName("当前时刻已过当日开启截止点后,按租户时区打开当日批次日实例并写入执行日志")
  void shouldOpenCurrentBizDateAfterCutoff() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z"); // 08:30 Asia/Shanghai
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    BatchDayInstanceEntity opened = captor.getValue();
    assertThat(opened.dayStatus()).isEqualTo("OPEN");
    assertThat(opened.dryRun()).isFalse();
    assertThat(opened.bizDate()).isEqualTo(LocalDate.of(2026, Month.MAY, 5));
    assertThat(opened.timezoneSnapshot()).isEqualTo("Asia/Shanghai");
    assertThat(opened.cutoffAt()).isEqualTo(Instant.parse("2026-05-05T22:00:00Z"));
    assertThat(opened.slaDeadlineAt()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    verify(jobExecutionLogMapper).insert(any());
    verify(fileGroupExpectationService)
        .materializeForOpenedBusinessDay(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5), "Asia/Shanghai");
    verify(externalEventExpectationService)
        .materializeForOpenedBusinessDay(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5), "Asia/Shanghai");
  }

  @Test
  @DisplayName("有限 catch-up 扫描报告提交后的原始日期积压")
  void shouldReportRemainingRawDateBacklogAfterBoundedScan() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate latest = LocalDate.of(2026, Month.MAY, 1);
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectLatestBizDateAtOrBefore(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(latest);
    when(calendarDependencyMapper.selectEnabledByDownstream("t1", "CAL")).thenReturn(List.of());
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    BatchDayScanProperties properties = new BatchDayScanProperties();
    properties.setCatchupDaysPerScan(1);
    ReflectionTestUtils.setField(scheduler, "batchDayScanProperties", properties);

    scheduler.openDueBatchDays(now);

    verify(catchupMetrics).beginScan();
    verify(catchupMetrics).recordCalendarLag(3);
    verify(catchupMetrics).completeScan();
  }

  @Test
  @DisplayName("暂停 catch-up 时保留积压指标且不初始化或推进日期游标")
  void shouldKeepBacklogVisibleWithoutScanningWhileCatchupIsPaused() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate cursor = LocalDate.of(2026, Month.MAY, 1);
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(catchupControlMapper.selectForUpdate("t1", "CAL"))
        .thenReturn(new BatchDayCatchupControlEntity(
            "t1", "CAL", true, now.minusSeconds(60), "operator", "maintenance", now));
    when(batchDayInstanceMapper.selectOpenCursorDate("t1", "CAL")).thenReturn(cursor);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    verify(catchupControlMapper).setTenantContext("t1");
    verify(catchupControlMapper).selectForUpdate("t1", "CAL");
    verify(catchupMetrics).recordCalendarLag(4);
    verify(batchDayInstanceMapper).selectOpenCursorDate("t1", "CAL");
    verify(batchDayInstanceMapper, never()).initializeOpenCursor(any(), any(), any());
    verify(batchDayInstanceMapper, never()).advanceOpenCursor(org.mockito.ArgumentMatchers.any(BatchDayInstanceAdvanceOpenCursorParam.class));
    ArgumentCaptor<BatchDayInstanceEntity> opened =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(opened.capture());
    assertThat(opened.getValue().bizDate()).isEqualTo(LocalDate.of(2026, Month.MAY, 5));
  }

  @Test
  @DisplayName("当前时刻未到当日开启截止点时,打开前一业务日实例并沿用前一日的截止时刻")
  void shouldOpenPreviousBizDateBeforeCutoff() {
    Instant now = Instant.parse("2026-05-04T21:30:00Z"); // 05:30 Asia/Shanghai
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    assertThat(captor.getValue().bizDate()).isEqualTo(LocalDate.of(2026, Month.MAY, 4));
    assertThat(captor.getValue().cutoffAt()).isEqualTo(Instant.parse("2026-05-04T22:00:00Z"));
  }

  @Test
  @DisplayName("SKIP 节假日写入可审计终态,避免批量日永久停留在空的 OPEN 状态")
  void shouldPersistSkippedHolidayAsTerminalBatchDay() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate bizDate = LocalDate.of(2026, Month.MAY, 5);
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(planCalendarMapper.selectEffectiveDayType("t1", "CAL", bizDate)).thenReturn("HOLIDAY");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", bizDate))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate("t1", "CAL", bizDate, now))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    BatchDayInstanceEntity skipped = captor.getValue();
    assertThat(skipped.bizDate()).isEqualTo(bizDate);
    assertThat(skipped.dayStatus()).isEqualTo("SKIPPED");
    assertThat(skipped.settledAt()).isEqualTo(now);
    assertThat(skipped.operationReason()).isEqualTo("CALENDAR_HOLIDAY_SKIP");
    verify(jobExecutionLogMapper).insert(any());
  }

  @Test
  @DisplayName("NEXT_WORKDAY 节假日滚动到首个工作日并只打开滚动后的业务日")
  void shouldRollHolidayToNextWorkday() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate holiday = LocalDate.of(2026, Month.MAY, 5);
    LocalDate nextWorkday = holiday.plusDays(1);
    BusinessCalendarEntity calendar = calendar("NEXT_WORKDAY");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar));
    when(planCalendarMapper.selectEffectiveDayType("t1", "CAL", holiday)).thenReturn("HOLIDAY");
    when(planCalendarMapper.selectEffectiveDayType("t1", "CAL", nextWorkday)).thenReturn("WORKDAY");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", nextWorkday))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate("t1", "CAL", nextWorkday, now))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    assertThat(captor.getValue().bizDate()).isEqualTo(nextWorkday);
    assertThat(captor.getValue().dayStatus()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("PREV_WORKDAY 节假日滚动到最近的前一工作日")
  void shouldRollHolidayToPreviousWorkday() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate holiday = LocalDate.of(2026, Month.MAY, 5);
    LocalDate previousWorkday = holiday.minusDays(1);
    when(businessCalendarMapper.selectByEnabled(true))
        .thenReturn(List.of(calendar("PREV_WORKDAY")));
    when(planCalendarMapper.selectEffectiveDayType("t1", "CAL", holiday)).thenReturn("HOLIDAY");
    when(planCalendarMapper.selectEffectiveDayType("t1", "CAL", previousWorkday))
        .thenReturn("WORKDAY");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", holiday))
        .thenReturn(null);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", previousWorkday))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate("t1", "CAL", holiday, now))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate("t1", "CAL", previousWorkday, now))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    assertThat(captor.getValue().bizDate()).isEqualTo(previousWorkday);
  }

  @Test
  @DisplayName("WORKDAY_OVERRIDE 优先于假日滚动规则")
  void shouldKeepWorkdayOverrideOnConfiguredDate() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate bizDate = LocalDate.of(2026, Month.MAY, 5);
    when(businessCalendarMapper.selectByEnabled(true))
        .thenReturn(List.of(calendar("NEXT_WORKDAY")));
    when(planCalendarMapper.selectEffectiveDayType("t1", "CAL", bizDate))
        .thenReturn("WORKDAY_OVERRIDE");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", bizDate))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate("t1", "CAL", bizDate, now))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    assertThat(captor.getValue().bizDate()).isEqualTo(bizDate);
    assertThat(captor.getValue().dayStatus()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("上游日历未结算时推迟打开当日批次日,不写入任何实例")
  void shouldDeferOpenWhenUpstreamCalendarNotSettled() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(null);
    when(calendarDependencyMapper.selectEnabledByDownstream("t1", "CAL"))
        .thenReturn(List.of(CalendarDependencyEntity.builder()
            .id(1L)
            .tenantId("t1")
            .upstreamCode("CAL_CN")
            .downstreamCode("CAL")
            .rule("WAIT_SETTLED")
            .enabled(true)
            .build()));
    BatchDayInstanceEntity upstreamRunning = BatchDayInstanceEntity.builder()
        .id(99L)
        .tenantId("t1")
        .calendarCode("CAL_CN")
        .bizDate(LocalDate.of(2026, Month.MAY, 5))
        .dayStatus("IN_FLIGHT")
        .build();
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL_CN", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(upstreamRunning);

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper, never()).insert(any());
    ArgumentCaptor<CalendarDependencyWaitEntity> waitCaptor =
        ArgumentCaptor.forClass(CalendarDependencyWaitEntity.class);
    verify(calendarDependencyWaitMapper).upsertWaiting(waitCaptor.capture());
    assertThat(waitCaptor.getValue().bizDate()).isEqualTo(LocalDate.of(2026, Month.MAY, 5));
    assertThat(waitCaptor.getValue().blockedReason()).contains("CAL_CN");
    assertThat(waitCaptor.getValue().dependencySnapshot())
        .contains("CAL_CN")
        .contains("WAIT_SETTLED");
  }

  @Test
  @DisplayName("依赖等待超过阈值后仅认领并发送一次告警,但仍保留自动恢复")
  void shouldEmitTimeoutAlertOnceForLongCalendarDependencyWait() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate bizDate = LocalDate.of(2026, Month.MAY, 5);
    CalendarDependencyWaitEntity oldWait = CalendarDependencyWaitEntity.builder()
        .id(7L)
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(bizDate)
        .waitStatus(CalendarDependencyWaitEntity.STATUS_WAITING)
        .blockedReason("blocked")
        .firstWaitingAt(now.minusSeconds(86_401))
        .lastCheckedAt(now.minusSeconds(86_400))
        .attemptCount(10)
        .cutoffAt(now)
        .slaDeadlineAt(now)
        .timezoneSnapshot("Asia/Shanghai")
        .dstPolicySnapshot("snapshot")
        .build();
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", bizDate))
        .thenReturn(null);
    when(calendarDependencyMapper.selectEnabledByDownstream("t1", "CAL"))
        .thenReturn(List.of(CalendarDependencyEntity.builder()
            .upstreamCode("CAL_CN")
            .downstreamCode("CAL")
            .rule("WAIT_SETTLED")
            .enabled(true)
            .build()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL_CN", bizDate))
        .thenReturn(BatchDayInstanceEntity.builder()
            .tenantId("t1")
            .calendarCode("CAL_CN")
            .bizDate(bizDate)
            .dayStatus("IN_FLIGHT")
            .build());
    when(calendarDependencyWaitMapper.selectByTenantCalendarBizDate("t1", "CAL", bizDate))
        .thenReturn(oldWait);
    when(calendarDependencyWaitMapper.claimTimeoutAlert("t1", 7L, now)).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<AlertEmitRequest> alertCaptor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(alertCaptor.capture());
    assertThat(alertCaptor.getValue().alertType())
        .isEqualTo("BATCH_CALENDAR_DEPENDENCY_WAIT_TIMEOUT");
    assertThat(alertCaptor.getValue().resourceKey()).contains("CAL").contains(bizDate.toString());
    verify(batchDayInstanceMapper, never()).insert(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("等待恢复沿用原业务日期与时间快照，人工放行单独保留操作审计")
  void shouldPreserveFrozenDayTimingWhenDependencyWaitResumes(boolean manualRelease) {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    LocalDate currentBizDate = LocalDate.of(2026, Month.MAY, 5);
    LocalDate waitingBizDate = LocalDate.of(2026, Month.MAY, 3);
    Instant frozenCutoff = Instant.parse("2026-05-04T10:00:00Z");
    Instant frozenDeadline = Instant.parse("2026-05-04T12:00:00Z");
    CalendarDependencyWaitEntity.CalendarDependencyWaitEntityBuilder waitBuilder =
        CalendarDependencyWaitEntity.builder()
            .id(71L)
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(waitingBizDate)
            .waitStatus(CalendarDependencyWaitEntity.STATUS_WAITING)
            .cutoffAt(frozenCutoff)
            .slaDeadlineAt(frozenDeadline)
            .timezoneSnapshot("America/New_York")
            .dstPolicySnapshot("frozen-dst-policy");
    if (manualRelease) {
      waitBuilder.manualAction(CalendarDependencyWaitEntity.MANUAL_ACTION_RELEASE);
      waitBuilder.manualOperatorId("operator-7");
      waitBuilder.manualReason("upstream verified");
    }
    CalendarDependencyWaitEntity waiting = waitBuilder.build();
    BatchDayInstanceEntity currentDay = BatchDayInstanceEntity.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(currentBizDate)
        .dayStatus("OPEN")
        .build();
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", currentBizDate))
        .thenReturn(currentDay);
    when(calendarDependencyWaitMapper.selectWaitingForUpdate("t1", "CAL", now, 100))
        .thenReturn(List.of(waiting));
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    ArgumentCaptor<BatchDayInstanceEntity> openedCaptor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(openedCaptor.capture());
    BatchDayInstanceEntity opened = openedCaptor.getValue();
    assertThat(opened.bizDate()).isEqualTo(waitingBizDate);
    assertThat(opened.openAt()).isEqualTo(now);
    assertThat(opened.cutoffAt()).isEqualTo(frozenCutoff);
    assertThat(opened.slaDeadlineAt()).isEqualTo(frozenDeadline);
    assertThat(opened.timezoneSnapshot()).isEqualTo("America/New_York");
    assertThat(opened.dstPolicySnapshot()).isEqualTo("frozen-dst-policy");
    if (manualRelease) {
      assertThat(opened.operatedBy()).isEqualTo("operator-7");
      assertThat(opened.operatedAt()).isEqualTo(now);
      assertThat(opened.operationReason())
          .isEqualTo("MANUAL_CALENDAR_DEPENDENCY_RELEASE:upstream verified");
      verify(calendarDependencyMapper, never()).selectEnabledByDownstream("t1", "CAL");
    } else {
      assertThat(opened.operatedBy()).isNull();
      assertThat(opened.operatedAt()).isNull();
      assertThat(opened.operationReason()).isNull();
      verify(calendarDependencyMapper).selectEnabledByDownstream("t1", "CAL");
    }
    verify(fileGroupExpectationService)
        .materializeForOpenedBusinessDay("t1", "CAL", waitingBizDate, "America/New_York");
    verify(externalEventExpectationService)
        .materializeForOpenedBusinessDay("t1", "CAL", waitingBizDate, "America/New_York");
    verify(calendarDependencyWaitMapper)
        .updateStatus("t1", 71L, CalendarDependencyWaitEntity.STATUS_OPENED, now);
  }

  @Test
  @DisplayName("上游日历已结算时正常打开当日批次日实例")
  void shouldOpenWhenUpstreamCalendarSettled() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(null);
    when(calendarDependencyMapper.selectEnabledByDownstream("t1", "CAL"))
        .thenReturn(List.of(CalendarDependencyEntity.builder()
            .id(1L)
            .tenantId("t1")
            .upstreamCode("CAL_CN")
            .downstreamCode("CAL")
            .rule("WAIT_SETTLED")
            .enabled(true)
            .build()));
    BatchDayInstanceEntity upstreamSettled = BatchDayInstanceEntity.builder()
        .id(99L)
        .tenantId("t1")
        .calendarCode("CAL_CN")
        .bizDate(LocalDate.of(2026, Month.MAY, 5))
        .dayStatus("SETTLED")
        .build();
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL_CN", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(upstreamSettled);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper).insert(any());
  }

  @Test
  @DisplayName("灾难日覆盖动作为跳过时,写入已跳过状态的实例并记录审批人与操作原因")
  void shouldWriteSkippedDayWhenDisasterOverrideSkip() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5), now))
        .thenReturn(DisasterDayOverrideEntity.builder()
            .id(1L)
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 5))
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
  @DisplayName("灾难日覆盖动作为顺延至下一业务日时,推迟打开且不写入实例")
  void shouldDeferOpenWhenDisasterOverrideDefer() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar()));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(null);
    when(disasterDayOverrideMapper.selectActiveByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5), now))
        .thenReturn(DisasterDayOverrideEntity.builder()
            .id(2L)
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 5))
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
  @DisplayName("目标业务日的批次日实例已存在时跳过开启,且不写入执行日志")
  void shouldSkipExistingBatchDay() {
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    BusinessCalendarEntity calendar = calendar();
    when(businessCalendarMapper.selectByEnabled(true)).thenReturn(List.of(calendar));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5)))
        .thenReturn(existing());

    scheduler.openDueBatchDays(now);

    verify(batchDayInstanceMapper, never()).insert(any());
    verify(jobExecutionLogMapper, never()).insert(any());
  }

  private BusinessCalendarEntity calendar() {
    return calendar("SKIP");
  }

  private BusinessCalendarEntity calendar(String holidayRollRule) {
    return new BusinessCalendarEntity(
        1L,
        "t1",
        "CAL",
        "Calendar",
        "Asia/Shanghai",
        holidayRollRule,
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
        LocalDate.of(2026, Month.MAY, 5),
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
