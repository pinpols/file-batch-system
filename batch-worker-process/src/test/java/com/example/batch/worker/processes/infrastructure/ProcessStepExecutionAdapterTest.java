package com.example.batch.worker.processes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.stage.ProcessStageExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ProcessStepExecutionAdapterTest {

  @Mock private ProcessStageExecutor processStageExecutor;
  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @Test
  void execute_createsProcessPipelineAndPassesPluginCodeFromPayload() {
    ProcessStepExecutionAdapter adapter =
        new ProcessStepExecutionAdapter(
            processStageExecutor,
            new ObjectMapper(),
            runtimeRepository,
            (ObjectProvider) mock(ObjectProvider.class));
    when(runtimeRepository.ensurePipelineDefinition(
            eq("tenant-a"),
            eq("job-process"),
            eq("PROCESS"),
            eq("worker-process"),
            any(),
            anyList()))
        .thenReturn(10L);
    when(runtimeRepository.loadPipelineSteps(10L)).thenReturn(List.of(processStep()));
    when(runtimeRepository.createPipelineInstance(any())).thenReturn(20L);
    when(processStageExecutor.execute(any()))
        .thenReturn(List.of(ProcessStageResult.success(ProcessStage.PREPARE)));
    StepExecutionRequest request =
        new StepExecutionRequest(
            "tenant-a",
            "job-process",
            "PROCESS",
            "worker-1",
            Map.of("payload", "{\"processImplCode\":\"dailySummary\"}"));

    StepExecutionResponse response = adapter.execute(request);

    assertThat(response.success()).isTrue();
    assertThat(response.code()).isEqualTo("SUCCESS");
    ArgumentCaptor<ProcessJobContext> contextCaptor =
        ArgumentCaptor.forClass(ProcessJobContext.class);
    verify(processStageExecutor).execute(contextCaptor.capture());
    assertThat(contextCaptor.getValue().getAttributes())
        .containsEntry("processImplCode", "dailySummary");
    verify(runtimeRepository).markPipelineSuccess(20L, "PREPARE", "PREPARE");
  }

  @Test
  void execute_preservesLocalizedErrorFromFailedStageResult() {
    ObjectMapper objectMapper = new ObjectMapper();
    ProcessStepExecutionAdapter adapter =
        new ProcessStepExecutionAdapter(
            processStageExecutor,
            objectMapper,
            runtimeRepository,
            (ObjectProvider) mock(ObjectProvider.class));
    when(runtimeRepository.ensurePipelineDefinition(
            eq("tenant-a"),
            eq("job-process"),
            eq("PROCESS"),
            eq("worker-process"),
            any(),
            anyList()))
        .thenReturn(10L);
    when(runtimeRepository.loadPipelineSteps(10L)).thenReturn(List.of(processStep()));
    when(runtimeRepository.createPipelineInstance(any())).thenReturn(20L);
    when(processStageExecutor.execute(any()))
        .thenReturn(
            List.of(
                ProcessStageResult.failure(
                    ProcessStage.PREPARE,
                    "PROCESS_PREPARE_FAILED",
                    BizException.of(
                        ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument", "bad spec"),
                    objectMapper)));
    StepExecutionRequest request =
        new StepExecutionRequest(
            "tenant-a", "job-process", "PROCESS", "worker-1", Map.of("payload", "{}"));

    StepExecutionResponse response = adapter.execute(request);

    assertThat(response.success()).isFalse();
    assertThat(response.code()).isEqualTo("PROCESS_PREPARE_FAILED");
    assertThat(response.message()).isEqualTo("error.common.invalid_argument");
    assertThat(response.errorKey()).isEqualTo("error.common.invalid_argument");
    assertThat(response.errorArgs()).isEqualTo("[\"bad spec\"]");
  }

  private PipelineStepDefinition processStep() {
    return new PipelineStepDefinition(
        1L,
        10L,
        "PROCESS_PREPARE",
        "Process Prepare",
        ProcessStage.PREPARE.name(),
        1,
        "PROCESS_PREPARE",
        Map.of(),
        0,
        "NONE",
        0,
        true);
  }
}
