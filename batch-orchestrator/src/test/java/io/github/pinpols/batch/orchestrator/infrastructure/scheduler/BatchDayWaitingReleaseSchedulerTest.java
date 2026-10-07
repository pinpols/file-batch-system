package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayOperationService;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("批量日等待释放调度器;验证停机排水短路,待释放记录筛选,前一业务日结算判定,按业务日去重与异常隔离")
class BatchDayWaitingReleaseSchedulerTest {

  private static final String TENANT = "tenantA";
  private static final String CAL = "RECON_DAILY";
  private static final LocalDate DAY2 = LocalDate.of(2026, Month.MAY, 31);
  private static final LocalDate DAY1 = DAY2.minusDays(1);

  @Mock
  BatchDayWaitingLaunchMapper waitingLaunchMapper;

  @Mock
  BatchDayInstanceMapper batchDayInstanceMapper;

  @Mock
  BatchDayOperationService batchDayOperationService;

  @Mock
  OrchestratorGracefulShutdown gracefulShutdown;

  @InjectMocks
  BatchDayWaitingReleaseScheduler scheduler;

  @Test
  @DisplayName("调度器处于停机排水状态时直接返回,不访问任何数据依赖")
  void shouldSkipWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.release();

    verifyNoInteractions(waitingLaunchMapper, batchDayInstanceMapper, batchDayOperationService);
  }

  @Test
  @DisplayName("没有等待释放记录时提前返回,不再查询业务日也不执行释放")
  void shouldReturnEarlyWhenNoWaitingRows() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of());

    scheduler.release();

    verifyNoInteractions(batchDayInstanceMapper, batchDayOperationService);
  }

  @Test
  @DisplayName("前一业务日已结算时按该日释放等待记录,释放上下文取前一日的业务日期与状态")
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
  @DisplayName("前一业务日仍在进行中时不做释放,留到后续调度周期再判定")
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
  @DisplayName("前一业务日记录缺失时不释放,避免前置条件不明时误放行")
  void shouldSkipWhenPreviousDayMissing() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(waitingRow(TENANT, CAL, DAY2, "req-1")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1))
        .thenReturn(null);

    scheduler.release();

    verify(batchDayOperationService, never()).releaseWaitingLaunchesForBatchDay(any(), any());
  }

  @Test
  @DisplayName("同一租户与业务日的多条等待记录只触发一次前一日查询和一次释放")
  void shouldDedupePreviousDayLookupsAcrossMultipleWaitingRows() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    // 同一 (tenant, calendar, bizDate) 的多条 waiting 行(不同 job)只触发一次前一日查询 + 一次 release
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(
            waitingRow(TENANT, CAL, DAY2, "req-1"),
            waitingRow(TENANT, CAL, DAY2, "req-2"),
            waitingRow(TENANT, CAL, DAY2, "req-3")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(TENANT, CAL, DAY1))
        .thenReturn(previousDay("SETTLED"));
    when(batchDayOperationService.releaseWaitingLaunchesForBatchDay(any(), any()))
        .thenReturn(3);

    scheduler.release();

    verify(batchDayInstanceMapper, times(1)).selectByTenantCalendarBizDate(TENANT, CAL, DAY1);
    verify(batchDayOperationService, times(1))
        .releaseWaitingLaunchesForBatchDay(
            any(), eq(BatchDayWaitingReleaseScheduler.AUTO_RELEASE_OPERATOR));
  }

  @Test
  @DisplayName("某个租户释放失败时其它租户继续处理,异常按业务日隔离")
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
        .thenAnswer(inv -> {
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
  @DisplayName("前一业务日为已跳过或人工放行时同样满足释放条件")
  void shouldTreatSkippedAndManualReleasedAsReleasable() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(waitingLaunchMapper.selectWaiting(
            null, BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT))
        .thenReturn(List.of(
            waitingRow("tenantA", CAL, DAY2, "req-a"), waitingRow("tenantB", CAL, DAY2, "req-b")));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("tenantA", CAL, DAY1))
        .thenReturn(previousDayFor("tenantA", "SKIPPED"));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("tenantB", CAL, DAY1))
        .thenReturn(previousDayFor("tenantB", "MANUAL_RELEASED"));
    when(batchDayOperationService.releaseWaitingLaunchesForBatchDay(any(), any()))
        .thenReturn(1);

    scheduler.release();

    verify(batchDayOperationService, times(2)).releaseWaitingLaunchesForBatchDay(any(), any());
  }

  @Test
  @DisplayName("前一业务日为失败状态时不释放等待记录")
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
