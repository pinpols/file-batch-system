package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 单元测试：{@link DefaultCompensationService} 的校验与守卫条件。
 */
class DefaultCompensationServiceTest {

    private CompensationCommandMapper compensationCommandMapper;
    private JobInstanceMapper jobInstanceMapper;
    private JobPartitionMapper jobPartitionMapper;
    private JobStepInstanceMapper jobStepInstanceMapper;
    private JobTaskMapper jobTaskMapper;
    private TriggerRequestMapper triggerRequestMapper;
    private RetryGovernanceService retryGovernanceService;
    private FileGovernanceService fileGovernanceService;
    private LaunchService launchService;
    private TaskExecutionService taskExecutionService;
    private DefaultCompensationService service;

    @BeforeEach
    void setUp() {
        compensationCommandMapper = mock(CompensationCommandMapper.class);
        jobInstanceMapper = mock(JobInstanceMapper.class);
        jobPartitionMapper = mock(JobPartitionMapper.class);
        jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
        jobTaskMapper = mock(JobTaskMapper.class);
        triggerRequestMapper = mock(TriggerRequestMapper.class);
        retryGovernanceService = mock(RetryGovernanceService.class);
        fileGovernanceService = mock(FileGovernanceService.class);
        launchService = mock(LaunchService.class);
        taskExecutionService = mock(TaskExecutionService.class);

        service = new DefaultCompensationService(
                compensationCommandMapper,
                jobInstanceMapper,
                jobPartitionMapper,
                jobStepInstanceMapper,
                jobTaskMapper,
                triggerRequestMapper,
                retryGovernanceService,
                fileGovernanceService,
                launchService,
                taskExecutionService
        );
    }

    // ── validate() ────────────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenCommandIsNull() {
        assertThatThrownBy(() -> service.submit(null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenTenantIdIsBlank() {
        CompensationSubmitCommand cmd = command("", "JOB", 1L);
        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenCompensationTypeIsBlank() {
        CompensationSubmitCommand cmd = command("t1", "", 1L);
        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenCompensationTypeIsUnsupported() {
        // UNKNOWN type → execute() switch default → BizException
        CompensationSubmitCommand cmd = command("t1", "UNKNOWN_TYPE", 1L);
        // compensationCommandMapper.countRunningByTarget returns 0, then insert, then execute throws
        org.mockito.Mockito.when(compensationCommandMapper.countRunningByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenPartitionTargetIdIsNull() {
        CompensationSubmitCommand cmd = command("t1", "PARTITION", null);
        org.mockito.Mockito.when(compensationCommandMapper.countRunningByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenStepTargetIdIsNull() {
        CompensationSubmitCommand cmd = command("t1", "STEP", null);
        org.mockito.Mockito.when(compensationCommandMapper.countRunningByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenDlqTargetIdIsNull() {
        CompensationSubmitCommand cmd = command("t1", "DLQ", null);
        org.mockito.Mockito.when(compensationCommandMapper.countRunningByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenBatchJobCodeIsBlank() {
        CompensationSubmitCommand cmd = new CompensationSubmitCommand(
                "t1", "BATCH", null, null, null, LocalDate.now(),
                null, null, null, null, null, null, null, null);
        org.mockito.Mockito.when(compensationCommandMapper.countRunningByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenFileTargetIdAndRelatedFileIdAreNull() {
        CompensationSubmitCommand cmd = new CompensationSubmitCommand(
                "t1", "FILE", null, null, null, null,
                null, null, "CH1", null, null, null, null, null);
        org.mockito.Mockito.when(compensationCommandMapper.countRunningByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(BizException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static CompensationSubmitCommand command(String tenantId, String compensationType, Long targetId) {
        return new CompensationSubmitCommand(
                tenantId, compensationType, targetId, null, null, null,
                null, null, null, "test reason", "op-001", null, null, null);
    }
}
