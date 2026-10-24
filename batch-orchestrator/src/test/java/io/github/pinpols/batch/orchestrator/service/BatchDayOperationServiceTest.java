package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayOperationAuditEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.view.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayOperationAuditMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("批量日操作服务:验证冻结,跳过,手工释放的状态流转与操作审计记录")
class BatchDayOperationServiceTest {

  private BatchDayInstanceMapper batchDayInstanceMapper;
  private BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private BatchDayOperationAuditMapper batchDayOperationAuditMapper;
  private JobInstanceMapper jobInstanceMapper;
  private JobDependencyWaitingLaunchMapper jobDependencyWaitingLaunchMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private LaunchService launchService;
  private BatchDayOperationService service;

  @BeforeEach
  void setUp() {
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    waitingLaunchMapper = mock(BatchDayWaitingLaunchMapper.class);
    expectedWorkOccurrenceMapper = mock(ExpectedWorkOccurrenceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    batchDayOperationAuditMapper = mock(BatchDayOperationAuditMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobDependencyWaitingLaunchMapper = mock(JobDependencyWaitingLaunchMapper.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    launchService = mock(LaunchService.class);
    service = new BatchDayOperationService(
        batchDayInstanceMapper,
        waitingLaunchMapper,
        expectedWorkOccurrenceMapper,
        jobExecutionLogMapper,
        batchDayOperationAuditMapper,
        jobInstanceMapper,
        jobDependencyWaitingLaunchMapper,
        triggerRequestMapper,
        launchService,
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
  }

  @Test
  @DisplayName("冻结未结账的批量日时保持打开状态并置为冻结,同时记录操作日志与审计明细")
  void shouldFreezeOpenBatchDay() {
    BatchDayInstanceEntity current = batchDay("OPEN");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(current);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    BatchDayOperationService.BatchDayOperationResult result =
        service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.FREEZE)
            .operatorId("ops")
            .reason("manual hold")
            .build());

    assertThat(result.batchDay().dayStatus()).isEqualTo("OPEN");
    assertThat(result.batchDay().frozen()).isTrue();
    assertThat(result.releasedLaunchCount()).isZero();
    verify(jobExecutionLogMapper).insert(any());
    ArgumentCaptor<BatchDayOperationAuditEntity> auditCaptor =
        ArgumentCaptor.forClass(BatchDayOperationAuditEntity.class);
    verify(batchDayOperationAuditMapper).insert(auditCaptor.capture());
    BatchDayOperationAuditEntity recorded = auditCaptor.getValue();
    assertThat(recorded.operationType()).isEqualTo("BATCH_DAY_FREEZE");
    assertThat(recorded.fromStatus()).isEqualTo("OPEN");
    assertThat(recorded.toStatus()).isEqualTo("OPEN");
    assertThat(recorded.fromFrozen()).isFalse();
    assertThat(recorded.toFrozen()).isTrue();
    assertThat(recorded.operatorId()).isEqualTo("ops");
    assertThat(recorded.reasonCode()).isEqualTo("manual hold");
  }

  @Test
  @DisplayName("跳过未进入终态的批量日时状态置为已跳过并补齐结账时间")
  void shouldSkipNonTerminalBatchDay() {
    BatchDayInstanceEntity current = batchDay("CUTOFF");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(current);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    service.operate(BatchDayOperateCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .action(BatchDayOperationService.BatchDayOperation.SKIP)
        .operatorId("ops")
        .reason("no input")
        .build());

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("SKIPPED");
    assertThat(captor.getValue().settledAt()).isNotNull();
  }

  @Test
  @DisplayName("对已结账的批量日执行冻结时抛出业务异常且不更新状态")
  void shouldRejectFreezeOnTerminalBatchDay() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("SETTLED"));

    assertThatThrownBy(() -> service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.FREEZE)
            .operatorId("ops")
            .reason("hold")
            .build()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("重开批量日缺少操作理由时在写入前拒绝")
  void shouldRejectReopenWithoutReasonBeforeCreatingNewObligations() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("SETTLED"));

    assertThatThrownBy(() -> service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.REOPEN)
            .operatorId("ops")
            .build()))
        .isInstanceOf(BizException.class);

    verify(expectedWorkOccurrenceMapper, never())
        .insertNextGenerationForCancelledByCalendarBizDate(anyString(), anyString(), any());
    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("手工释放失败的批量日时补发下一业务日的等待作业并标记为已释放")
  void shouldReleaseWaitingLaunchesForNextBizDate() {
    BatchDayInstanceEntity current = batchDay("FAILED");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(current);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);
    when(waitingLaunchMapper.selectWaitingByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 5), 200))
        .thenReturn(List.of(waitingLaunch()));
    when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-1", "trace-1"));

    BatchDayOperationService.BatchDayOperationResult result =
        service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.RELEASE)
            .operatorId("ops")
            .reason("manual release")
            .build());

    assertThat(result.batchDay().dayStatus()).isEqualTo("MANUAL_RELEASED");
    assertThat(result.releasedLaunchCount()).isEqualTo(1);
    verify(launchService).launch(any(LaunchRequest.class));
    verify(waitingLaunchMapper).markReleased("t1", "req-1", "ops");
  }

  @Test
  @DisplayName("显式确认空批量日时要求 CUTOFF、零作业实例和必填原因并写入审计")
  void shouldSettleExplicitlyConfirmedEmptyBatchDay() {
    BatchDayInstanceEntity current = batchDay("CUTOFF");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(current);
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(0L));
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    BatchDayOperationService.BatchDayOperationResult result =
        service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
            .operatorId("ops")
            .reason("upstream confirmed no expected batch")
            .build());

    assertThat(result.batchDay().dayStatus()).isEqualTo("SETTLED");
    assertThat(result.batchDay().settledAt()).isNotNull();
    assertThat(result.releasedLaunchCount()).isZero();
    ArgumentCaptor<BatchDayOperationAuditEntity> auditCaptor =
        ArgumentCaptor.forClass(BatchDayOperationAuditEntity.class);
    verify(batchDayOperationAuditMapper).insert(auditCaptor.capture());
    assertThat(auditCaptor.getValue().operationType()).isEqualTo("BATCH_DAY_SETTLE_EMPTY");
    assertThat(auditCaptor.getValue().reasonCode()).isEqualTo("EMPTY_DAY_CONFIRMED");
    assertThat(auditCaptor.getValue().comment()).isEqualTo("upstream confirmed no expected batch");
  }

  @Test
  @DisplayName("空批量日确认拒绝已有作业实例且不推进状态")
  void shouldRejectEmptySettlementWhenJobInstancesExist() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(1L));

    assertThatThrownBy(() -> service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
            .operatorId("ops")
            .reason("no batch expected")
            .build()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("空日结算拒绝跨日历依赖等待,避免后续恢复在已结算日启动作业")
  void shouldRejectEmptySettlementWhenCalendarDependencyLaunchIsWaiting() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(0L));
    when(waitingLaunchMapper.selectWaitingByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4), 1))
        .thenReturn(List.of(waitingLaunch()));

    assertThatThrownBy(() -> service.operate(emptySettleCommand()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("空日结算拒绝作业依赖等待,避免后续恢复在已结算日启动作业")
  void shouldRejectEmptySettlementWhenJobDependencyLaunchIsWaiting() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(0L));
    when(jobDependencyWaitingLaunchMapper.countWaitingForCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(1);

    assertThatThrownBy(() -> service.operate(emptySettleCommand()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("空日结算拒绝仍可能创建实例的异步触发请求")
  void shouldRejectEmptySettlementWhenTriggerRequestIsUnlaunched() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(0L));
    when(triggerRequestMapper.countUnlaunchedForCalendarBizDate(
            anyString(), anyString(), any(), any()))
        .thenReturn(1);

    assertThatThrownBy(() -> service.operate(emptySettleCommand()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
    verify(batchDayOperationAuditMapper, never()).insert(any());
  }

  @Test
  @DisplayName("空日结算拒绝仍处于计划或等待状态的 Quartz occurrence")
  void shouldRejectEmptySettlementWhenScheduledOccurrenceIsBlocking() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(0L));
    when(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(1);

    assertThatThrownBy(() -> service.operate(emptySettleCommand()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("空日结算在调度物化覆盖未到 cutoff 时失败关闭")
  void shouldRejectEmptySettlementWhenScheduleCoverageIsIncomplete() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(metrics(0L));
    when(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            "t1", "CAL", Instant.parse("2026-05-04T00:00:00Z")))
        .thenReturn(1);

    assertThatThrownBy(() -> service.operate(emptySettleCommand()))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
    verify(batchDayOperationAuditMapper, never()).insert(any());
  }

  @Test
  @DisplayName("空批量日确认拒绝未到 cutoff 的日期和空原因")
  void shouldRequireCutoffAndReasonForEmptySettlement() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("OPEN"));

    assertThatThrownBy(() -> service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
            .operatorId("ops")
            .reason("confirmed no expected jobs")
            .build()))
        .isInstanceOf(BizException.class);

    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(batchDay("CUTOFF"));
    assertThatThrownBy(() -> service.operate(BatchDayOperateCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
            .operatorId("ops")
            .reason("  ")
            .build()))
        .isInstanceOf(BizException.class);

    verify(jobInstanceMapper, never()).selectBatchDayMetrics(anyString(), anyString(), any());
    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  private BatchDayInstanceMetrics metrics(long totalCount) {
    BatchDayInstanceMetrics metrics = new BatchDayInstanceMetrics();
    metrics.setTotalCount(totalCount);
    metrics.setActiveCount(0L);
    metrics.setSuccessCount(0L);
    metrics.setFailedCount(0L);
    return metrics;
  }

  private BatchDayOperateCommand emptySettleCommand() {
    return BatchDayOperateCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
        .operatorId("ops")
        .reason("no expected jobs")
        .build();
  }

  private BatchDayInstanceEntity batchDay(String status) {
    Instant at = Instant.parse("2026-05-04T00:00:00Z");
    return new BatchDayInstanceEntity(
        1L,
        "t1",
        "CAL",
        LocalDate.of(2026, Month.MAY, 4),
        status,
        at,
        at,
        null,
        null,
        0,
        0,
        "Asia/Shanghai",
        false,
        null,
        null,
        null,
        0L,
        at,
        at);
  }

  private BatchDayWaitingLaunchEntity waitingLaunch() {
    Instant at = Instant.parse("2026-05-05T00:00:00Z");
    Map<String, Object> payload = Map.of(
        "tenantId", "t1",
        "jobCode", "JOB",
        "bizDate", "2026-05-05",
        "triggerType", "SCHEDULED",
        "requestId", "req-1",
        "traceId", "trace-1",
        "params", Map.of("k", "v"));
    return new BatchDayWaitingLaunchEntity(
        1L,
        "t1",
        "CAL",
        "JOB",
        LocalDate.of(2026, Month.MAY, 5),
        "req-1",
        "trace-1",
        "SCHEDULED",
        "PREVIOUS_BATCH_DAY_NOT_CLOSED",
        JsonUtils.toJson(payload),
        "WAITING",
        null,
        null,
        at,
        at);
  }
}
