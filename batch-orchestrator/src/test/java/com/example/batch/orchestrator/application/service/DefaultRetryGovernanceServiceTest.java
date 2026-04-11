package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.RetryGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.RetryScheduleEntity;
import com.example.batch.orchestrator.mapper.DeadLetterTaskMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.RetryScheduleMapper;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultRetryGovernanceServiceTest {

    private RetryScheduleMapper retryScheduleMapper;
    private DeadLetterTaskMapper deadLetterTaskMapper;
    private JobDefinitionRepository jobDefinitionRepository;
    private JobTaskMapper jobTaskMapper;
    private JobPartitionMapper jobPartitionMapper;
    private JobInstanceMapper jobInstanceMapper;
    private JobStepInstanceMapper jobStepInstanceMapper;
    private TaskDispatchOutboxService taskDispatchOutboxService;
    private RetryGovernanceProperties properties;
    private BatchOrchestratorGovernanceProperties governance;
    private DefaultRetryGovernanceService service;

    @BeforeEach
    void setUp() {
        retryScheduleMapper = mock(RetryScheduleMapper.class);
        deadLetterTaskMapper = mock(DeadLetterTaskMapper.class);
        jobDefinitionRepository = mock(JobDefinitionRepository.class);
        jobTaskMapper = mock(JobTaskMapper.class);
        jobPartitionMapper = mock(JobPartitionMapper.class);
        jobInstanceMapper = mock(JobInstanceMapper.class);
        jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
        taskDispatchOutboxService = mock(TaskDispatchOutboxService.class);
        properties = new RetryGovernanceProperties();
        properties.setFixedDelaySeconds(60L);
        properties.setExponentialMultiplier(2L);
        properties.setMaxDelaySeconds(3600L);
        properties.setDefaultMaxRetryCount(3);
        governance = mock(BatchOrchestratorGovernanceProperties.class);
        when(governance.retry()).thenReturn(properties);

        service = new DefaultRetryGovernanceService(
                retryScheduleMapper, deadLetterTaskMapper, jobDefinitionRepository,
                jobTaskMapper, jobPartitionMapper, jobInstanceMapper, jobStepInstanceMapper,
                taskDispatchOutboxService, governance);
    }

    // ── scheduleRetryIfNecessary — null guards ────────────────────────────────

    @Test
    void shouldReturnFalseWhenTaskIsNull() {
        boolean result = service.scheduleRetryIfNecessary(null, partition(1L, 0), jobInstance(1L), "ERR", "err");
        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnFalseWhenPartitionIsNull() {
        boolean result = service.scheduleRetryIfNecessary(task("t1", 1L, 1L), null, jobInstance(1L), "ERR", "err");
        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnFalseWhenJobInstanceIsNull() {
        boolean result = service.scheduleRetryIfNecessary(task("t1", 1L, 1L), partition(1L, 0), null, "ERR", "err");
        assertThat(result).isFalse();
    }

    // ── scheduleRetryIfNecessary — NONE policy → dead letter ─────────────────

    @Test
    void shouldCreateDeadLetterWhenRetryPolicyIsNone() {
        when(jobDefinitionRepository.findById(1L))
                .thenReturn(Optional.of(jobDefinitionWithPolicy(1L, "NONE", 3)));

        boolean result = service.scheduleRetryIfNecessary(
                task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), "ERR", "none policy");

        assertThat(result).isFalse();
        verify(deadLetterTaskMapper).insert(any());
        verify(retryScheduleMapper, never()).insert(any());
    }

    @Test
    void shouldCreateDeadLetterWhenMaxRetryCountZero() {
        when(jobDefinitionRepository.findById(1L))
                .thenReturn(Optional.of(jobDefinitionWithPolicy(1L, "FIXED", 0)));

        boolean result = service.scheduleRetryIfNecessary(
                task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), "ERR", "max zero");

        assertThat(result).isFalse();
        verify(deadLetterTaskMapper).insert(any());
    }

    // ── scheduleRetryIfNecessary — retry count exhausted ─────────────────────

    @Test
    void shouldCreateDeadLetterWhenRetryCountExhausted() {
        when(jobDefinitionRepository.findById(1L))
                .thenReturn(Optional.of(jobDefinitionWithPolicy(1L, "FIXED", 2)));

        // partition already has 2 retries, max is 2 → exhausted
        boolean result = service.scheduleRetryIfNecessary(
                task("t1", 1L, 1L), partition(1L, 2), jobInstance(1L), "ERR", "exhausted");

        assertThat(result).isFalse();
        verify(deadLetterTaskMapper).insert(any());
        verify(retryScheduleMapper, never()).insert(any());
    }

    // ── scheduleRetryIfNecessary — schedule retry ─────────────────────────────

    @Test
    void shouldInsertRetryScheduleWithCorrectStatusAndDedup() {
        when(jobDefinitionRepository.findById(1L))
                .thenReturn(Optional.of(jobDefinitionWithPolicy(1L, "FIXED", 3)));

        boolean result = service.scheduleRetryIfNecessary(
                task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), "PARSE_ERR", "parse failed");

        assertThat(result).isTrue();
        ArgumentCaptor<RetryScheduleEntity> captor = ArgumentCaptor.forClass(RetryScheduleEntity.class);
        verify(retryScheduleMapper).insert(captor.capture());
        RetryScheduleEntity schedule = captor.getValue();
        assertThat(schedule.getRetryStatus()).isEqualTo(RetryScheduleStatus.WAITING.code());
        assertThat(schedule.getRetryCount()).isEqualTo(1);
        assertThat(schedule.getLastErrorCode()).isEqualTo("PARSE_ERR");
        assertThat(schedule.getNextRetryAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void shouldUseDefaultRetryPolicyWhenJobDefinitionNotFound() {
        when(jobDefinitionRepository.findById(anyLong())).thenReturn(Optional.empty());

        boolean result = service.scheduleRetryIfNecessary(
                task("t1", 1L, 999L), partition(1L, 0), jobInstance(999L), "ERR", "msg");

        assertThat(result).isTrue();
        verify(retryScheduleMapper).insert(any());
    }

    @Test
    void shouldUseDefaultRetryPolicyWhenJobDefinitionIdIsNull() {
        JobInstanceEntity jobInst = jobInstance(null);
        boolean result = service.scheduleRetryIfNecessary(
                task("t1", 1L, null), partition(1L, 0), jobInst, "ERR", "msg");

        assertThat(result).isTrue();
    }

    // ── replayDeadLetter — guard conditions ──────────────────────────────────

    @Test
    void shouldThrowWhenDeadLetterNotFound() {
        when(deadLetterTaskMapper.selectById("t1", 999L)).thenReturn(null);

        assertThatThrownBy(() -> service.replayDeadLetter("t1", 999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shouldThrowWhenDeadLetterNotReplayable() {
        DeadLetterTaskEntity dl = deadLetter(1L, "t1", "REPLAYING");
        when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);

        assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not replayable");
    }

    @Test
    void shouldThrowWhenDeadLetterReplayConcurrencyConflict() {
        DeadLetterTaskEntity dl = deadLetter(1L, "t1", "NEW");
        when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);
        when(deadLetterTaskMapper.markReplaying(anyString(), anyLong(), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflict");
    }

    @Test
    void shouldThrowWhenDeadLetterSourceTypeIsNotJobPartition() {
        DeadLetterTaskEntity dl = deadLetter(1L, "t1", "NEW");
        dl.setSourceType("JOB_TASK");
        when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);
        when(deadLetterTaskMapper.markReplaying(anyString(), anyLong(), anyString(), anyString())).thenReturn(1);

        assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L))
                .isInstanceOf(IllegalStateException.class);
        verify(deadLetterTaskMapper).markReplayFailure(anyString(), anyLong(), anyString(), anyInt(), any(), anyString());
    }

    // ── dispatchDueRetries — no retries ──────────────────────────────────────

    @Test
    void shouldDoNothingWhenNoRetrySchedulesDue() {
        when(retryScheduleMapper.selectByQuery(any())).thenReturn(List.of());

        service.dispatchDueRetries();

        verify(retryScheduleMapper).selectByQuery(any());
        verify(retryScheduleMapper, never()).markRunning(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldSkipRetryWhenMarkRunningFails() {
        RetryScheduleEntity schedule = new RetryScheduleEntity();
        schedule.setId(1L);
        schedule.setTenantId("t1");
        schedule.setRelatedId(100L);
        when(retryScheduleMapper.selectByQuery(any())).thenReturn(List.of(schedule));
        when(retryScheduleMapper.markRunning(1L, RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code())).thenReturn(0);

        service.dispatchDueRetries();

        verify(partitionMapper(), never()).selectById(anyString(), anyLong());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JobPartitionMapper partitionMapper() {
        return jobPartitionMapper;
    }

    private static JobTaskEntity task(String tenantId, Long taskId, Long jobDefinitionId) {
        JobTaskEntity t = new JobTaskEntity();
        t.setTenantId(tenantId);
        t.setId(taskId);
        t.setJobPartitionId(1L);
        return t;
    }

    private static JobPartitionEntity partition(Long partitionId, int retryCount) {
        JobPartitionEntity p = new JobPartitionEntity();
        p.setId(partitionId);
        p.setRetryCount(retryCount);
        p.setJobInstanceId(1L);
        return p;
    }

    private static JobInstanceEntity jobInstance(Long jobDefinitionId) {
        JobInstanceEntity j = new JobInstanceEntity();
        j.setId(1L);
        j.setJobDefinitionId(jobDefinitionId);
        j.setInstanceNo("INST-001");
        j.setTraceId("trace-001");
        return j;
    }

    private static JobDefinitionRecord jobDefinitionWithPolicy(Long id, String retryPolicy, int maxRetry) {
        return new JobDefinitionRecord(
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                retryPolicy,
                maxRetry,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static DeadLetterTaskEntity deadLetter(Long id, String tenantId, String replayStatus) {
        DeadLetterTaskEntity dl = new DeadLetterTaskEntity();
        dl.setId(id);
        dl.setTenantId(tenantId);
        dl.setReplayStatus(replayStatus);
        dl.setReplayCount(0);
        dl.setSourceType("JOB_PARTITION");
        dl.setSourceId(100L);
        return dl;
    }
}
