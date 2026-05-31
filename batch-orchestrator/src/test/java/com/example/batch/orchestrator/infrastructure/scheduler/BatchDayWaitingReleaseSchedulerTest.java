package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.service.BatchDayOperationService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchDayWaitingReleaseSchedulerTest {

  private static final String TENANT = "tenantA";
  private static final String CAL = "RECON_DAILY";
  private static final LocalDate DAY2 = LocalDate.of(2026, 5, 31);
  private static final LocalDate DAY1 = DAY2.minusDays(1);

  @Mock BatchDayWaitingLaunchMapper waitingLaunchMapper;
  @Mock BatchDayInstanceMapper batchDayInstanceMapper;
  @Mock BatchDayOperationService batchDayOperationService;
  @Mock OrchestratorGracefulShutdown gracefulShutdown;

  @InjectMocks BatchDayWaitingReleaseScheduler scheduler;

  @Test
  void shouldSkipWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.release();

    verifyNoInteractions(waitingLaunchMapper, batchDayInstanceMapper, batchDayOperationService);
  }

  @Test
  void shouldReturnEarlyWhenNoWaitingRows() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of());

    scheduler.release();

    verifyNoInteractions(batchDayInstanceMapper, batchDayOperationService);
  }

  @Test
  void shouldReleaseWhenPreviousDaySettled() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    BatchDayWaitingLaunchEntity row = waitingRow(TENANT, CAL, DAY2, "req-1");
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(row));
    BatchDayInstanceEntity previous = previousDay("SETTLED");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1))
        .thenReturn(previous);
    when(batchDayOperationService.releaseWaitingLaunchesForBatchDay(
            any(BatchDayInstanceEntity.class),
            eq(BatchDayWaitingReleaseScheduler.AUTO_RELEASE_OPERATOR)))
        .thenReturn(1);

    scheduler.release();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayOperationService)
        .releaseWaitingLaunchesForBatchDay(
            captor.capture(), eq(BatchDayWaitingReleaseScheduler.AUTO_RELEASE_OPERATOR));
    assertThat(captor.getValue().bizDate()).isEqualTo(DAY1);
    assertThat(captor.getValue().dayStatus()).isEqualTo("SETTLED");
  }

  @Test
  void shouldSkipWhenPreviousDayStillInFlight() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(waitingRow(TENANT, CAL, DAY2, "req-1")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1))
        .thenReturn(previousDay("IN_FLIGHT"));

    scheduler.release();

    verify(batchDayOperationService, never()).releaseWaitingLaunchesForBatchDay(any(), any());
  }

  @Test
  void shouldSkipWhenPreviousDayMissing() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(waitingRow(TENANT, CAL, DAY2, "req-1")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1)).thenReturn(null);

    scheduler.release();

    verify(batchDayOperationService, never()).releaseWaitingLaunchesForBatchDay(any(), any());
  }

  @Test
  void shouldDedupePreviousDayLookupsAcrossMultipleWaitingRows() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    // 同一 (tenant, calendar, bizDate) 的多条 waiting 行(不同 job)只触发一次前一日查询 + 一次 release
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(
            List.of(
                waitingRow(TENANT, CAL, DAY2, "req-1"),
                waitingRow(TENANT, CAL, DAY2, "req-2"),
                waitingRow(TENANT, CAL, DAY2, "req-3")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1))
        .thenReturn(previousDay("SETTLED"));
    when(batchDayOperationService.releaseWaitingLaunchesForBatchDay(any(), any())).thenReturn(3);

    scheduler.release();

    verify(batchDayInstanceMapper, times(1)).selectByTenantCalendarBizDate(TENANT, CAL, DAY1);
    verify(batchDayOperationService, times(1))
        .releaseWaitingLaunchesForBatchDay(
            any(), eq(BatchDayWaitingReleaseScheduler.AUTO_RELEASE_OPERATOR));
  }

  @Test
  void shouldIsolateExceptionPerPreviousDay() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    BatchDayWaitingLaunchEntity rowA = waitingRow("tenantA", CAL, DAY2, "req-a");
    BatchDayWaitingLaunchEntity rowB = waitingRow("tenantB", CAL, DAY2, "req-b");
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(rowA, rowB));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("tenantA", CAL, DAY1))
        .thenReturn(previousDayFor("tenantA", "SETTLED"));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("tenantB", CAL, DAY1))
        .thenReturn(previousDayFor("tenantB", "SKIPPED"));
    // tenantA 抛错,tenantB 正常释放
    when(batchDayOperationService.releaseWaitingLaunchesForBatchDay(
            any(BatchDayInstanceEntity.class), any()))
        .thenAnswer(
            inv -> {
              BatchDayInstanceEntity arg = inv.getArgument(0);
              if ("tenantA".equals(arg.tenantId())) {
                throw new RuntimeException("simulated db error");
              }
              return 1;
            });

    scheduler.release();

    verify(batchDayOperationService, times(2))
        .releaseWaitingLaunchesForBatchDay(
            any(), eq(BatchDayWaitingReleaseScheduler.AUTO_RELEASE_OPERATOR));
  }

  @Test
  void shouldTreatSkippedAndManualReleasedAsReleasable() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(
            List.of(
                waitingRow("tenantA", CAL, DAY2, "req-a"),
                waitingRow("tenantB", CAL, DAY2, "req-b")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("tenantA", CAL, DAY1))
        .thenReturn(previousDayFor("tenantA", "SKIPPED"));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("tenantB", CAL, DAY1))
        .thenReturn(previousDayFor("tenantB", "MANUAL_RELEASED"));
    when(batchDayOperationService.releaseWaitingLaunchesForBatchDay(any(), any())).thenReturn(1);

    scheduler.release();

    verify(batchDayOperationService, times(2)).releaseWaitingLaunchesForBatchDay(any(), any());
  }

  @Test
  void shouldNotTreatFailedAsReleasable() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(waitingRow(TENANT, CAL, DAY2, "req-1")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1))
        .thenReturn(previousDay("FAILED"));

    scheduler.release();

    verify(batchDayOperationService, never()).releaseWaitingLaunchesForBatchDay(any(), any());
  }

  private static BatchDayWaitingLaunchEntity waitingRow(
      String tenantId, String calendarCode, LocalDate bizDate, String requestId) {
    return BatchDayWaitingLaunchEntity.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .jobCode("JOB_X")
        .bizDate(bizDate)
        .requestId(requestId)
        .waitStatus("WAITING")
        .waitReason("PREVIOUS_BATCH_DAY_NOT_CLOSED")
        .build();
  }

  private static BatchDayInstanceEntity previousDay(String dayStatus) {
    return previousDayFor(TENANT, dayStatus);
  }

  private static BatchDayInstanceEntity previousDayFor(String tenantId, String dayStatus) {
    return BatchDayInstanceEntity.builder()
        .tenantId(tenantId)
        .calendarCode(CAL)
        .bizDate(DAY1)
        .dayStatus(dayStatus)
        .frozen(false)
        .build();
  }
}
