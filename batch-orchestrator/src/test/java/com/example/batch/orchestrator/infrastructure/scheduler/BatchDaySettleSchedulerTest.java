package com.example.batch.orchestrator.infrastructure.scheduler;

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

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceMetrics;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class BatchDaySettleSchedulerTest {

  private BatchDayInstanceMapper batchDayInstanceMapper;
  private JobInstanceMapper jobInstanceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private OrchestratorConfigCacheService configCacheService;
  private LaunchService launchService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private BatchDaySettleScheduler scheduler;

  @BeforeEach
  void setUp() {
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    configCacheService = mock(OrchestratorConfigCacheService.class);
    launchService = mock(LaunchService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<BatchDaySettleScheduler> selfProvider = mock(ObjectProvider.class);
    scheduler =
        new BatchDaySettleScheduler(
            batchDayInstanceMapper,
            jobInstanceMapper,
            jobExecutionLogMapper,
            triggerRequestMapper,
            configCacheService,
            launchService,
            gracefulShutdown,
            selfProvider,
            new BatchDateTimeSupport(
                Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
    // self-proxy 在单测里直接指向 scheduler 自身，绕开 Spring AOP；REQUIRES_NEW 事务语义在单测里不跑也没事
    when(selfProvider.getObject()).thenReturn(scheduler);
  }

  @Test
  void shouldDoNothingWhenNoCandidates() {
    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of());

    scheduler.settle();

    verify(jobInstanceMapper, never()).selectBatchDayMetrics(anyString(), anyString(), any());
  }

  @Test
  void shouldPromoteToInFlightWhenActiveInstancesExist() {
    BatchDayInstanceEntity candidate = candidate("CUTOFF");
    BatchDayInstanceMetrics metrics = metrics(3L, 2L, 1L, 0L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
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
  void shouldSettleWhenAllInstancesSucceeded() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(4L, 0L, 4L, 0L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate()))
        .thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(settling);
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
  void shouldFailWhenAllInstancesTerminalAndAnyFailed() {
    BatchDayInstanceEntity candidate = candidate("IN_FLIGHT");
    BatchDayInstanceEntity settling = candidate("SETTLING");
    BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);

    when(batchDayInstanceMapper.selectByDayStatusIn(any())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.selectBatchDayMetrics(eq("t1"), eq("CAL"), any())).thenReturn(metrics);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "CAL", candidate.bizDate()))
        .thenReturn(settling);
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
        .thenReturn(settling);
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar);
    when(triggerRequestMapper.selectByTenantAndDedupKey(eq("t1"), anyString())).thenReturn(null);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);
    when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-001"));

    scheduler.settle();

    ArgumentCaptor<BatchDayInstanceEntity> batchCaptor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper, times(2)).updateWithCas(batchCaptor.capture());
    assertThat(batchCaptor.getAllValues().get(0).dayStatus()).isEqualTo("SETTLING");
    assertThat(batchCaptor.getAllValues().get(1).dayStatus()).isEqualTo("FAILED");
    verify(triggerRequestMapper).insert(any());
    ArgumentCaptor<LaunchRequest> launchCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(launchCaptor.capture());
    assertThat(launchCaptor.getValue().triggerType()).isEqualTo(TriggerType.CATCH_UP);
    assertThat(launchCaptor.getValue().jobCode()).isEqualTo("FAILED_JOB");
  }

  @Test
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
        .thenReturn(settling);
    when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
        .thenReturn(List.of(failedJob));
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL")).thenReturn(calendar);
    when(triggerRequestMapper.selectByTenantAndDedupKey(eq("t1"), anyString())).thenReturn(null);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    scheduler.settle();

    verify(batchDayInstanceMapper, atLeastOnce()).updateWithCas(any());
    verify(triggerRequestMapper).insert(any());
    verify(launchService, never()).launch(any());
  }

  @Test
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
        LocalDate.of(2026, 3, 27),
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
