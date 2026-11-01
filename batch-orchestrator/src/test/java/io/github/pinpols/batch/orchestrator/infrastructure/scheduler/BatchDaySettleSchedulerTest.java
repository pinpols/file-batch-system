package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.view.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@DisplayName("批量日结算定时任务: 候选批量日筛选,活跃实例推进,全部成功或失败时的终态收敛,以及失败日补跑与人工审批分支")
class BatchDaySettleSchedulerTest {

  private BatchDayInstanceMapper batchDayInstanceMapper;
  private BatchDayWaitingLaunchMapper batchDayWaitingLaunchMapper;
  private JobDependencyWaitingLaunchMapper jobDependencyWaitingLaunchMapper;
  private CalendarDependencyWaitMapper calendarDependencyWaitMapper;
  private ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper;
  private JobInstanceMapper jobInstanceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private WorkflowDefinitionMapper workflowDefinitionMapper;
  private OrchestratorConfigCacheService configCacheService;
  private LaunchApplicationService launchApplicationService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private BatchDaySettleScheduler scheduler;

  @BeforeEach
  void setUp() {
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    batchDayWaitingLaunchMapper = mock(BatchDayWaitingLaunchMapper.class);
    jobDependencyWaitingLaunchMapper = mock(JobDependencyWaitingLaunchMapper.class);
    calendarDependencyWaitMapper = mock(CalendarDependencyWaitMapper.class);
    expectedWorkOccurrenceMapper = mock(ExpectedWorkOccurrenceMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    when(triggerRequestMapper.insertBatchDayCatchUpIfAbsent(any())).thenReturn(1);
    workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
    configCacheService = mock(OrchestratorConfigCacheService.class);
    launchApplicationService = mock(LaunchApplicationService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    scheduler = new BatchDaySettleScheduler(
        batchDayInstanceMapper,
        batchDayWaitingLaunchMapper,
        jobDependencyWaitingLaunchMapper,
        calendarDependencyWaitMapper,
        expectedWorkOccurrenceMapper,
        mock(AlertEventService.class),
        jobInstanceMapper,
        jobExecutionLogMapper,
        triggerRequestMapper,
        workflowDefinitionMapper,
        configCacheService,
        launchApplicationService,
        gracefulShutdown,
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())),
        transactionManager);
    when(batchDayWaitingLaunchMapper.selectWaitingByCalendarBizDate(
            anyString(), anyString(), any(), eq(1)))
        .thenReturn(List.of());
    when(calendarDependencyWaitMapper.countWaitingByTenantCalendarBizDate(
            anyString(), anyString(), any()))
        .thenReturn(0);
    when(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            anyString(), anyString(), any()))
        .thenReturn(0);
  }

  @Test
  @DisplayName("CUTOFF 空日只有无预期作业时自动结算")
  void shouldAutoSettleCutoffDayWhenNoExpectedWorkExists() {
    BatchDayInstanceEntity cutoff = candidate("CUTOFF");
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(cutoff));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", cutoff.bizDate()))
        .thenReturn(cutoff);
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", cutoff.bizDate()))
        .thenReturn(metrics(0L, 0L, 0L, 0L));
    when(batchDayInstanceMapper.countUncoveredScheduledDefinitions(anyString(), anyString(), any()))
        .thenReturn(0);
    when(batchDayInstanceMapper.countUntrackedTriggerDefinitions("t1", "CAL")).thenReturn(0);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("SETTLED");
    assertThat(captor.getValue().settledAt()).isNotNull();
    ArgumentCaptor<JobExecutionLogEntity> audit =
        ArgumentCaptor.forClass(JobExecutionLogEntity.class);
    verify(jobExecutionLogMapper).insert(audit.capture());
    assertThat(audit.getValue().getExtraJson()).contains("AUTO_EMPTY_NO_EXPECTED_WORK");
  }

  @Test
  @DisplayName("未完成的显式文件/事件预期项阻止 CUTOFF 空日自动结算")
  void shouldKeepCutoffDayOpenWhenExpectedWorkIsOutstanding() {
    BatchDayInstanceEntity cutoff = candidate("CUTOFF");
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(cutoff));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", cutoff.bizDate()))
        .thenReturn(metrics(0L, 0L, 0L, 0L));
    when(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate("t1", "CAL", cutoff.bizDate()))
        .thenReturn(1);

    scheduler.settle();

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("事件作业无独立预期清单时空日保持 CUTOFF")
  void shouldKeepCutoffDayWhenEventWorkHasNoExpectationLedger() {
    BatchDayInstanceEntity cutoff = candidate("CUTOFF");
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(cutoff));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", cutoff.bizDate()))
        .thenReturn(metrics(0L, 0L, 0L, 0L));
    when(batchDayInstanceMapper.countUntrackedTriggerDefinitions("t1", "CAL")).thenReturn(1);

    scheduler.settle();

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("已有作业生命周期的 IN_FLIGHT 空日不走自动空日结算")
  void shouldNotAutoSettleEmptyInFlightDay() {
    BatchDayInstanceEntity inFlight = candidate("IN_FLIGHT");
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(inFlight));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", inFlight.bizDate()))
        .thenReturn(metrics(0L, 0L, 0L, 0L));

    scheduler.settle();

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
    verify(batchDayInstanceMapper, never())
        .countUntrackedTriggerDefinitions(anyString(), anyString());
  }

  @Test
  @DisplayName("没有待结算的批量日时候不查询作业实例指标")
  void shouldDoNothingWhenNoCandidates() {
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of());

    scheduler.settle();

    verify(jobInstanceMapper, never()).selectBatchDayMetrics(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("存在活跃实例时候批量日推进为进行中,且不写结算时间")
  void shouldPromoteToInFlightWhenActiveInstancesExist() {
    BatchDayInstanceEntity candidate = candidate("CUTOFF");
    BatchDayInstanceMetrics metrics = metrics(3L, 2L, 1L, 0L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(candidate);
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate()))
        .thenReturn(metrics);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("IN_FLIGHT");
    assertThat(captor.getValue().settledAt()).isNull();
  }

  @Test
  @DisplayName("实例全部成功时候批量日先从进行中转为结算中再落定为结算完成")
  void shouldSettleWhenAllInstancesSucceeded() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(4L, 0L, 4L, 0L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate()))
        .thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(candidate, settling);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper, times(2)).updateWithCas(captor.capture());
    assertThat(captor.getAllValues().get(0).dayStatus()).isEqualTo("SETTLING");
    assertThat(captor.getAllValues().get(1).dayStatus()).isEqualTo("SETTLED");
    assertThat(captor.getAllValues().get(1).settledAt()).isNotNull();
  }

  @Test
  @DisplayName("实例全部终态且存在失败时候批量日落定为失败并写结算时间")
  void shouldFailWhenAllInstancesTerminalAndAnyFailed() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics(eq("t1"), eq("CAL"), any())).thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(candidate, settling);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper, times(2)).updateWithCas(captor.capture());
    assertThat(captor.getAllValues().get(0).dayStatus()).isEqualTo("SETTLING");
    assertThat(captor.getAllValues().get(1).dayStatus()).isEqualTo("FAILED");
    assertThat(captor.getAllValues().get(1).settledAt()).isNotNull();
  }

  @Test
  @DisplayName("失败批量日配置为自动补跑时候写入触发请求并发起补跑执行")
  void shouldLaunchAutoCatchUpWhenBatchDayFailed() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 101L);
    BusinessCalendarEntity calendar = calendar("AUTO");

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate()))
        .thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(candidate, settling);
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar);
    when(configCacheService.findEnabledJobDefinition("t1", "FAILED_JOB"))
        .thenReturn(JobDefinitionView.builder()
            .id(501L)
            .tenantId("t1")
            .jobCode("FAILED_JOB")
            .build());
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 501L, true))
        .thenReturn(new WorkflowDefinitionView(
            801L, "t1", "WF_FAILED_JOB", "Failed job", "SINGLE_JOB", 1, true, 501L, null));
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(null);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenReturn(new LaunchResponse("inst-001", "trace-001"));

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> batchCaptor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper, times(2)).updateWithCas(batchCaptor.capture());
    assertThat(batchCaptor.getAllValues().get(0).dayStatus()).isEqualTo("SETTLING");
    assertThat(batchCaptor.getAllValues().get(1).dayStatus()).isEqualTo("FAILED");
    ArgumentCaptor<TriggerRequestEntity> triggerCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insertBatchDayCatchUpIfAbsent(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getWorkflowDefinitionId()).isEqualTo(801L);
    assertThat(triggerCaptor.getValue().getJobCode()).isNull();
    ArgumentCaptor<LaunchRequest> launchCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchApplicationService).launchFromTrustedQueue(launchCaptor.capture());
    assertThat(launchCaptor.getValue().triggerType()).isEqualTo(TriggerType.CATCH_UP);
    assertThat(launchCaptor.getValue().workflowDefinitionId()).isEqualTo(801L);
    assertThat(launchCaptor.getValue().jobCode()).isNull();
  }

  @Test
  @DisplayName("进程错过失败日提交后的补跑回调时仍会重扫并派发")
  void shouldRecoverCatchUpForAlreadyFailedDay() {
    BatchDayInstanceEntity failedDay = candidate("FAILED");
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 103L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of());
    when(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(100)).thenReturn(List.of(failedDay));
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", failedDay.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar("AUTO"));
    when(configCacheService.findEnabledJobDefinition("t1", "FAILED_JOB"))
        .thenReturn(JobDefinitionView.builder()
            .id(501L)
            .tenantId("t1")
            .jobCode("FAILED_JOB")
            .build());
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 501L, true))
        .thenReturn(new WorkflowDefinitionView(
            801L, "t1", "WF_FAILED_JOB", "Failed job", "SINGLE_JOB", 1, true, 501L, null));
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(null);
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenReturn(new LaunchResponse("inst-002", "trace-002"));

    Instant originalStart = Instant.parse("2026-03-27T00:00:00Z");
    failedJob.setDataIntervalStart(originalStart);
    failedJob.setDataIntervalEnd(originalStart.plusSeconds(86400));
    scheduler.settle();

    verify(batchDayInstanceMapper).selectFailedDaysNeedingCatchUp(100);
    ArgumentCaptor<TriggerRequestEntity> accepted =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insertBatchDayCatchUpIfAbsent(accepted.capture());
    LaunchRequest frozen =
        JsonUtils.fromJsonStrict(accepted.getValue().getLaunchPayload(), LaunchRequest.class);
    assertThat(frozen.dataIntervalStart()).isEqualTo(originalStart);
    assertThat(frozen.dataIntervalEnd()).isEqualTo(originalStart.plusSeconds(86400));
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(accepted.getValue());
    failedJob.setDataIntervalStart(originalStart.plusSeconds(86400));
    scheduler.settle();
    ArgumentCaptor<LaunchRequest> launched = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchApplicationService, times(2)).launchFromTrustedQueue(launched.capture());
    assertThat(launched.getAllValues()).containsExactly(frozen, frozen);
    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("重扫已接纳补跑时当前受管 Workflow 变化不能改写原请求")
  void shouldPreserveAcceptedCatchUpWhenCurrentWorkflowChanges(boolean currentWorkflowExists) {
    BatchDayInstanceEntity failedDay = candidate("FAILED");
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 103L);
    LaunchRequest frozen = LaunchRequest.builder()
        .tenantId("t1")
        .requestId("accepted-catchup")
        .workflowDefinitionId(801L)
        .bizDate(failedDay.bizDate())
        .triggerType(TriggerType.CATCH_UP)
        .build();
    TriggerRequestEntity accepted = new TriggerRequestEntity();
    accepted.setTenantId("t1");
    accepted.setRequestId(frozen.requestId());
    accepted.setWorkflowDefinitionId(frozen.workflowDefinitionId());
    accepted.setBizDate(frozen.bizDate());
    accepted.setRequestStatus("ACCEPTED");
    accepted.setLaunchPayload(JsonUtils.toJson(frozen));
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of());
    when(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(100)).thenReturn(List.of(failedDay));
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", failedDay.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar("AUTO"));
    when(configCacheService.findEnabledJobDefinition("t1", "FAILED_JOB"))
        .thenReturn(JobDefinitionView.builder()
            .id(501L)
            .tenantId("t1")
            .jobCode("FAILED_JOB")
            .build());
    WorkflowDefinitionView current = currentWorkflowExists
        ? new WorkflowDefinitionView(802L, "t1", "WF_NEW", "new", "SINGLE_JOB", 1, true, 501L, null)
        : null;
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 501L, true))
        .thenReturn(current);
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(accepted);
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenReturn(new LaunchResponse("inst", "trace"));

    scheduler.settle();

    verify(triggerRequestMapper, never())
        .updateAcceptance(anyString(), anyString(), eq("REJECTED"), any());
    verify(launchApplicationService).launchFromTrustedQueue(frozen);
  }

  @Test
  @DisplayName("检查关联期间其他执行器已接纳补跑时保留新请求")
  void shouldPreserveCatchUpAcceptedDuringMissingWorkflowCheck() {
    BatchDayInstanceEntity failedDay = candidate("FAILED");
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 103L);
    TriggerRequestEntity accepted = new TriggerRequestEntity();
    accepted.setTenantId("t1");
    accepted.setRequestId("concurrent-catchup");
    accepted.setRequestStatus("ACCEPTED");
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of());
    when(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(100)).thenReturn(List.of(failedDay));
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", failedDay.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar("AUTO"));
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(null, accepted);

    scheduler.settle();

    verify(triggerRequestMapper, never())
        .updateAcceptance(anyString(), anyString(), eq("REJECTED"), any());
    verify(triggerRequestMapper, never()).insertBatchDayCatchUpIfAbsent(any());
    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"GIVE_UP", "DUPLICATE"})
  @DisplayName("同一失败日因其他任务重扫时，放弃或重复请求不能自动重投")
  void shouldNotLaunchTerminalCatchUpWhenDayIsScannedForOtherJobs(String status) {
    BatchDayInstanceEntity failedDay = candidate("FAILED");
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 103L);
    TriggerRequestEntity terminal = new TriggerRequestEntity();
    terminal.setTenantId("t1");
    terminal.setRequestId("terminal-catchup");
    terminal.setWorkflowDefinitionId(801L);
    terminal.setBizDate(failedDay.bizDate());
    terminal.setRequestStatus(status);
    LaunchRequest frozen = LaunchRequest.builder()
        .tenantId("t1")
        .requestId("terminal-catchup")
        .workflowDefinitionId(801L)
        .bizDate(failedDay.bizDate())
        .triggerType(TriggerType.CATCH_UP)
        .build();
    terminal.setLaunchPayload(JsonUtils.toJson(frozen));
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of());
    when(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(100)).thenReturn(List.of(failedDay));
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", failedDay.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar("AUTO"));
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(terminal);

    scheduler.settle();

    verify(triggerRequestMapper, never())
        .updateAcceptance(anyString(), anyString(), anyString(), any());
    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
  }

  @Test
  @DisplayName("失败批量日需要人工审批时候只登记补跑请求而不发起执行")
  void shouldCreatePendingCatchUpWhenApprovalRequired() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 102L);
    BusinessCalendarEntity calendar = calendar("MANUAL_APPROVAL");

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate()))
        .thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(candidate, settling);
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar);
    when(configCacheService.findEnabledJobDefinition("t1", "FAILED_JOB"))
        .thenReturn(JobDefinitionView.builder()
            .id(501L)
            .tenantId("t1")
            .jobCode("FAILED_JOB")
            .build());
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 501L, true))
        .thenReturn(new WorkflowDefinitionView(
            801L, "t1", "WF_FAILED_JOB", "Failed job", "SINGLE_JOB", 1, true, 501L, null));
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(null);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    verify(batchDayInstanceMapper, atLeastOnce()).updateWithCas(any());
    ArgumentCaptor<TriggerRequestEntity> triggerCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insertBatchDayCatchUpIfAbsent(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getWorkflowDefinitionId()).isEqualTo(801L);
    assertThat(triggerCaptor.getValue().getJobCode()).isNull();
    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
  }

  @Test
  @DisplayName("单作业 Workflow 关联缺失时拒绝补跑而不回退到独立 Job Launch")
  void shouldRejectCatchUpWhenManagedSingleJobWorkflowIsMissing() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 103L);
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate()))
        .thenReturn(metrics(2L, 0L, 1L, 1L));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(candidate, settling);
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar("AUTO"));
    when(configCacheService.findEnabledJobDefinition("t1", "FAILED_JOB"))
        .thenReturn(JobDefinitionView.builder()
            .id(501L)
            .tenantId("t1")
            .jobCode("FAILED_JOB")
            .build());
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 501L, true))
        .thenReturn(null);
    when(triggerRequestMapper.selectCatchUpRootByDedupKey(eq("t1"), anyString()))
        .thenReturn(null);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<TriggerRequestEntity> triggerCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insertBatchDayCatchUpIfAbsent(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getRequestStatus()).isEqualTo("REJECTED");
    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
  }

  @Test
  @DisplayName("遗留的结算中批量日在实例全部成功时候直接落定为结算完成")
  void shouldFinalizeStuckSettlingFromPreviousRun() {
    BatchDayInstanceEntity stuck = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(3L, 0L, 3L, 0L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(stuck));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", stuck.bizDate())).thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", stuck.bizDate()))
        .thenReturn(stuck);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper, times(1)).updateWithCas(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("遗留的结算中批量日重新出现活跃实例时候回退为进行中")
  void shouldRevertStuckSettlingToInFlightWhenActiveCameBack() {
    BatchDayInstanceEntity stuck = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(4L, 1L, 3L, 0L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(stuck));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", stuck.bizDate())).thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", stuck.bizDate()))
        .thenReturn(stuck);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper, times(1)).updateWithCas(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("IN_FLIGHT");
  }

  private BatchDayInstanceEntity candidate(String status) {
    return new BatchDayInstanceEntity(
        1L,
        "t1",
        "CAL",
        LocalDate.of(2026, Month.MARCH, 27),
        status,
        Instant.parse("2026-03-27T00:00:00Z"),
        Instant.parse("2026-03-27T06:00:00Z"),
        null,
        Instant.parse("2026-03-27T08:00:00Z"),
        0,
        0,
        "UTC",
        0L,
        Instant.parse("2026-03-27T00:00:00Z"),
        Instant.parse("2026-03-27T06:00:00Z"));
  }

  private JobInstanceEntity jobInstance(String jobCode, Long id) {
    JobInstanceEntity entity = new JobInstanceEntity();
    entity.setId(id);
    entity.setTenantId("t1");
    entity.setJobCode(jobCode);
    entity.setJobDefinitionId(11L);
    return entity;
  }

  private BusinessCalendarEntity calendar(String catchUpPolicy) {
    return new BusinessCalendarEntity(
        1L,
        "t1",
        "CAL",
        "Calendar",
        "Asia/Shanghai",
        "SKIP",
        catchUpPolicy,
        30,
        LocalTime.of(6, 0),
        30,
        120,
        true);
  }

  private BatchDayInstanceMetrics metrics(
      Long totalCount, Long activeCount, Long successCount, Long failedCount) {
    BatchDayInstanceMetrics metrics = new BatchDayInstanceMetrics();
    metrics.setTotalCount(totalCount);
    metrics.setActiveCount(activeCount);
    metrics.setSuccessCount(successCount);
    metrics.setFailedCount(failedCount);
    return metrics;
  }
}
