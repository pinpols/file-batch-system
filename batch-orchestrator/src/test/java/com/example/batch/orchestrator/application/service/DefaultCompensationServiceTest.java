package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/** 单元测试：{@link DefaultCompensationService} 的校验与守卫条件。 */
@SuppressWarnings("unchecked")
class DefaultCompensationServiceTest {

  private CompensationCommandMapper compensationCommandMapper;
  private OrchestratorJobMappers jobMappers;
  private RetryGovernanceService retryGovernanceService;
  private FileGovernanceService fileGovernanceService;
  private LaunchService launchService;
  private ObjectProvider<LaunchService> launchServiceProvider;
  private TaskExecutionService taskExecutionService;
  private DefaultCompensationService service;

  @BeforeEach
  void setUp() {
    compensationCommandMapper = mock(CompensationCommandMapper.class);
    jobMappers =
        new OrchestratorJobMappers(
            mock(JobInstanceMapper.class),
            mock(JobPartitionMapper.class),
            mock(JobTaskMapper.class),
            mock(JobStepInstanceMapper.class),
            mock(TriggerRequestMapper.class));
    retryGovernanceService = mock(RetryGovernanceService.class);
    fileGovernanceService = mock(FileGovernanceService.class);
    launchService = mock(LaunchService.class);
    launchServiceProvider = mock(ObjectProvider.class);
    taskExecutionService = mock(TaskExecutionService.class);

    service =
        new DefaultCompensationService(
            compensationCommandMapper,
            jobMappers,
            retryGovernanceService,
            fileGovernanceService,
            launchServiceProvider,
            taskExecutionService);
    Mockito.when(launchServiceProvider.getObject()).thenReturn(launchService);
  }

  // ── validate() ────────────────────────────────────────────────────────────

  @Test
  void shouldThrowWhenCommandIsNull() {
    assertThatThrownBy(() -> service.submit(null)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenTenantIdIsBlank() {
    CompensationSubmitCommand cmd = command("", "JOB", 1L);
    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenCompensationTypeIsBlank() {
    CompensationSubmitCommand cmd = command("t1", "", 1L);
    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenCompensationTypeIsUnsupported() {
    // UNKNOWN type → execute() switch default → BizException
    CompensationSubmitCommand cmd = command("t1", "UNKNOWN_TYPE", 1L);
    // compensationCommandMapper.countRunningByTarget returns 0, then insert, then execute throws
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenPartitionTargetIdIsNull() {
    CompensationSubmitCommand cmd = command("t1", "PARTITION", null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenStepTargetIdIsNull() {
    CompensationSubmitCommand cmd = command("t1", "STEP", null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenDlqTargetIdIsNull() {
    CompensationSubmitCommand cmd = command("t1", "DLQ", null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenBatchJobCodeIsBlank() {
    CompensationSubmitCommand cmd =
        new CompensationSubmitCommand(
            "t1",
            "BATCH",
            null,
            null,
            null,
            LocalDate.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrowWhenFileTargetIdAndRelatedFileIdAreNull() {
    CompensationSubmitCommand cmd =
        new CompensationSubmitCommand(
            "t1", "FILE", null, null, null, null, null, null, "CH1", null, null, null, null, null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.submit(cmd)).isInstanceOf(BizException.class);
  }

  // ── happy path ────────────────────────────────────────────────────────────
  // V5-P2-4: compensation 6 类 source_type happy-path 验证。
  // 本批覆盖 PARTITION / STEP / DLQ / FILE 4 类（路径相对独立、mock 量小）。
  // JOB / BATCH 涉及 LaunchService 全 trigger 流程，留 follow-up（TODO V5-P2-4-ext）。
  // submit() 返回 commandNo (String)；happy-path 通过 Mockito.verify() 校验侧效（确认 handler
  // 真正调到对应下游服务），不依赖返回值结构。

  @Test
  void shouldRetryPartitionSuccessfully_PARTITION_path() {
    CompensationSubmitCommand cmd = command("t1", "PARTITION", 10L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    Mockito.verify(retryGovernanceService)
        .retryPartition(Mockito.eq("t1"), Mockito.eq(10L), Mockito.anyString());
  }

  @Test
  void shouldReplayDeadLetterSuccessfully_DLQ_path() {
    CompensationSubmitCommand cmd = command("t1", "DLQ", 999L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    Mockito.verify(retryGovernanceService).replayDeadLetter("t1", 999L);
  }

  @Test
  void shouldRerunStepSuccessfully_STEP_path() {
    CompensationSubmitCommand cmd = command("t1", "STEP", 5L);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity step =
        new com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity();
    step.setId(5L);
    step.setJobInstanceId(100L);
    step.setJobTaskId(50L);
    when(jobMappers.jobStepInstanceMapper.selectById("t1", 5L)).thenReturn(step);

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    Mockito.verify(retryGovernanceService)
        .retryTask(Mockito.eq("t1"), Mockito.eq(50L), Mockito.anyString());
  }

  @Test
  void shouldReprocessFileSuccessfully_FILE_path() {
    CompensationSubmitCommand cmd =
        new CompensationSubmitCommand(
            "t1",
            "FILE",
            null,
            null,
            null,
            null,
            null,
            777L, // relatedFileId
            "CH-A",
            "reprocess test",
            "op-001",
            null,
            null,
            null);
    when(compensationCommandMapper.countRunningByTarget(
            anyString(), anyString(), any(), anyString()))
        .thenReturn(0);
    when(fileGovernanceService.redispatchFile(any())).thenReturn("file-redispatched-ok");

    String commandNo = service.submit(cmd);

    assertThat(commandNo).isNotBlank();
    Mockito.verify(fileGovernanceService).redispatchFile(any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static CompensationSubmitCommand command(
      String tenantId, String compensationType, Long targetId) {
    return new CompensationSubmitCommand(
        tenantId,
        compensationType,
        targetId,
        null,
        null,
        null,
        null,
        null,
        null,
        "test reason",
        "op-001",
        null,
        null,
        null);
  }
}
