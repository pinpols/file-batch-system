package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.query.BatchDayInstanceMetrics;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.repository.BatchDayInstanceRepository;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchDaySettleSchedulerTest {

    private BatchDayInstanceRepository batchDayInstanceRepository;
    private JobInstanceMapper jobInstanceMapper;
    private JobExecutionLogMapper jobExecutionLogMapper;
    private TriggerRequestMapper triggerRequestMapper;
    private OrchestratorConfigCacheService configCacheService;
    private LaunchService launchService;
    private BatchDaySettleScheduler scheduler;

    @BeforeEach
    void setUp() {
        batchDayInstanceRepository = mock(BatchDayInstanceRepository.class);
        jobInstanceMapper = mock(JobInstanceMapper.class);
        jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
        triggerRequestMapper = mock(TriggerRequestMapper.class);
        configCacheService = mock(OrchestratorConfigCacheService.class);
        launchService = mock(LaunchService.class);
        scheduler = new BatchDaySettleScheduler(
                batchDayInstanceRepository,
                jobInstanceMapper,
                jobExecutionLogMapper,
                triggerRequestMapper,
                configCacheService,
                launchService
        );
    }

    @Test
    void shouldDoNothingWhenNoCandidates() {
        when(batchDayInstanceRepository.findByDayStatusIn(any())).thenReturn(List.of());

        scheduler.settle();

        verify(jobInstanceMapper, never()).selectBatchDayMetrics(anyString(), anyString(), any());
    }

    @Test
    void shouldPromoteToInFlightWhenActiveInstancesExist() {
        BatchDayInstanceRecord candidate = candidate("CUTOFF");
        BatchDayInstanceMetrics metrics = metrics(3L, 2L, 1L, 0L);

        when(batchDayInstanceRepository.findByDayStatusIn(any())).thenReturn(List.of(candidate));
        when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate())).thenReturn(metrics);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.settle();

        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        assertThat(captor.getValue().dayStatus()).isEqualTo("IN_FLIGHT");
        assertThat(captor.getValue().settledAt()).isNull();
    }

    @Test
    void shouldSettleWhenAllInstancesSucceeded() {
        BatchDayInstanceRecord candidate = candidate("IN_FLIGHT");
        BatchDayInstanceMetrics metrics = metrics(4L, 0L, 4L, 0L);

        when(batchDayInstanceRepository.findByDayStatusIn(any())).thenReturn(List.of(candidate));
        when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate())).thenReturn(metrics);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.settle();

        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        assertThat(captor.getValue().dayStatus()).isEqualTo("SETTLED");
        assertThat(captor.getValue().settledAt()).isNotNull();
    }

    @Test
    void shouldFailWhenAllInstancesTerminalAndAnyFailed() {
        BatchDayInstanceRecord candidate = candidate("IN_FLIGHT");
        BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);

        when(batchDayInstanceRepository.findByDayStatusIn(any())).thenReturn(List.of(candidate));
        when(jobInstanceMapper.selectBatchDayMetrics(eq("t1"), eq("CAL"), any())).thenReturn(metrics);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.settle();

        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        assertThat(captor.getValue().dayStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().settledAt()).isNotNull();
    }

    @Test
    void shouldLaunchAutoCatchUpWhenBatchDayFailed() {
        BatchDayInstanceRecord candidate = candidate("IN_FLIGHT");
        BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);
        JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 101L);
        BusinessCalendarRecord calendar = calendar("AUTO");

        when(batchDayInstanceRepository.findByDayStatusIn(any())).thenReturn(List.of(candidate));
        when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate())).thenReturn(metrics);
        when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
                .thenReturn(List.of(failedJob));
        when(configCacheService.findEnabledBusinessCalendar("t1", "CAL"))
                .thenReturn(calendar);
        when(triggerRequestMapper.selectByTenantAndDedupKey(eq("t1"), anyString())).thenReturn(null);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-001"));

        scheduler.settle();

        ArgumentCaptor<BatchDayInstanceRecord> batchCaptor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(batchCaptor.capture());
        assertThat(batchCaptor.getValue().dayStatus()).isEqualTo("FAILED");
        verify(triggerRequestMapper).insert(any());
        ArgumentCaptor<LaunchRequest> launchCaptor =
                ArgumentCaptor.forClass(LaunchRequest.class);
        verify(launchService).launch(launchCaptor.capture());
        assertThat(launchCaptor.getValue().triggerType()).isEqualTo(TriggerType.CATCH_UP);
        assertThat(launchCaptor.getValue().jobCode()).isEqualTo("FAILED_JOB");
    }

    @Test
    void shouldCreatePendingCatchUpWhenApprovalRequired() {
        BatchDayInstanceRecord candidate = candidate("IN_FLIGHT");
        BatchDayInstanceMetrics metrics = metrics(2L, 0L, 1L, 1L);
        JobInstanceEntity failedJob = jobInstance("FAILED_JOB", 102L);
        BusinessCalendarRecord calendar = calendar("MANUAL_APPROVAL");

        when(batchDayInstanceRepository.findByDayStatusIn(any())).thenReturn(List.of(candidate));
        when(jobInstanceMapper.selectBatchDayMetrics("t1", "CAL", candidate.bizDate())).thenReturn(metrics);
        when(jobInstanceMapper.selectBatchDayCatchUpCandidates("t1", "CAL", candidate.bizDate()))
                .thenReturn(List.of(failedJob));
        when(configCacheService.findEnabledBusinessCalendar("t1", "CAL"))
                .thenReturn(calendar);
        when(triggerRequestMapper.selectByTenantAndDedupKey(eq("t1"), anyString())).thenReturn(null);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.settle();

        verify(triggerRequestMapper).insert(any());
        verify(launchService, never()).launch(any());
    }

    private BatchDayInstanceRecord candidate(String status) {
        return new BatchDayInstanceRecord(
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
                Instant.parse("2026-03-27T00:00:00Z"),
                Instant.parse("2026-03-27T06:00:00Z")
        );
    }

    private JobInstanceEntity jobInstance(String jobCode, Long id) {
        JobInstanceEntity entity = new JobInstanceEntity();
        entity.setId(id);
        entity.setTenantId("t1");
        entity.setJobCode(jobCode);
        entity.setJobDefinitionId(11L);
        return entity;
    }

    private BusinessCalendarRecord calendar(String catchUpPolicy) {
        return new BusinessCalendarRecord(
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
                true
        );
    }

    private BatchDayInstanceMetrics metrics(Long totalCount, Long activeCount, Long successCount, Long failedCount) {
        BatchDayInstanceMetrics metrics = new BatchDayInstanceMetrics();
        metrics.setTotalCount(totalCount);
        metrics.setActiveCount(activeCount);
        metrics.setSuccessCount(successCount);
        metrics.setFailedCount(failedCount);
        return metrics;
    }
}
