package io.github.pinpols.batch.worker.processes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.support.PipelineCompensationHook;
import io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider;
import io.github.pinpols.batch.worker.core.support.PipelineVerifierHook;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.stage.ProcessStageExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ProcessStepExecutionAdapterTest {

  @Mock private ProcessStageExecutor processStageExecutor;
  @Mock private PipelineStepTemplateProvider stepTemplateProvider;
  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @Test
  @SuppressWarnings("unchecked")
  void execute_createsProcessPipelineAndPassesPluginCodeFromPayload() {
    ProcessStepExecutionAdapter adapter =
        new ProcessStepExecutionAdapter(
            processStageExecutor,
            stepTemplateProvider,
            new ObjectMapper(),
            runtimeRepository,
            (ObjectProvider<PipelineVerifierHook>) mock(ObjectProvider.class),
            (ObjectProvider<PipelineCompensationHook>) mock(ObjectProvider.class));
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
  @SuppressWarnings("unchecked")
  void execute_preservesLocalizedErrorFromFailedStageResult() {
    ObjectMapper objectMapper = new ObjectMapper();
    ProcessStepExecutionAdapter adapter =
        new ProcessStepExecutionAdapter(
            processStageExecutor,
            stepTemplateProvider,
            objectMapper,
            runtimeRepository,
            (ObjectProvider<PipelineVerifierHook>) mock(ObjectProvider.class),
            (ObjectProvider<PipelineCompensationHook>) mock(ObjectProvider.class));
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
    // 安全增量补偿默认 off（无 compensation hook bean）：失败直接 FAILED，绝不经过 COMPENSATING。
    verify(runtimeRepository).markPipelineFailed(eq(20L), any(), any());
    verify(runtimeRepository, never()).markPipelineCompensating(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("ADR-041 Phase1.3:buildSuccessResponse 输出归一化 count 信封 inputCount/outputCount")
  void buildSuccessResponse_emitsNormalizedCountEnvelope() {
    ProcessStepExecutionAdapter adapter =
        new ProcessStepExecutionAdapter(
            processStageExecutor,
            stepTemplateProvider,
            new ObjectMapper(),
            runtimeRepository,
            (ObjectProvider<PipelineVerifierHook>) mock(ObjectProvider.class),
            (ObjectProvider<PipelineCompensationHook>) mock(ObjectProvider.class));
    ProcessJobContext context = new ProcessJobContext();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("processedCount", 1000L);
    attributes.put("publishedCount", 950L);

    adapter.buildSuccessResponse(context, List.of(), attributes);

    Map<String, Object> outputs =
        (Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS);
    assertThat(outputs).containsEntry("inputCount", 1000L).containsEntry("outputCount", 950L);
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("publishedCount 缺省时 outputCount 回落 stagedCount")
  void buildSuccessResponse_outputCountFallsBackToStaged() {
    ProcessStepExecutionAdapter adapter =
        new ProcessStepExecutionAdapter(
            processStageExecutor,
            stepTemplateProvider,
            new ObjectMapper(),
            runtimeRepository,
            (ObjectProvider<PipelineVerifierHook>) mock(ObjectProvider.class),
            (ObjectProvider<PipelineCompensationHook>) mock(ObjectProvider.class));
    ProcessJobContext context = new ProcessJobContext();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("processedCount", 1000L);
    attributes.put("stagedCount", 1000L);

    adapter.buildSuccessResponse(context, List.of(), attributes);

    Map<String, Object> outputs =
        (Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS);
    assertThat(outputs).containsEntry("outputCount", 1000L);
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
