package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("Pipeline 执行适配器的定义校验")
class AbstractPipelineStepExecutionAdapterDefinitionTest {

  private final PlatformPipelineDefinitionRepository definitions =
      mock(PlatformPipelineDefinitionRepository.class);
  private final PlatformPipelineRunRepository runs = mock(PlatformPipelineRunRepository.class);
  private final PlatformFileRecordRepository files = mock(PlatformFileRecordRepository.class);

  @Test
  @DisplayName("定义不存在时返回 not found，且不加载步骤")
  void execute_rejectsMissingPipelineDefinition() {
    when(definitions.findPipelineDefinition("tenant-a", "job-a")).thenReturn(null);

    StepExecutionResponse response = adapter().execute(request());

    assertThat(response.success()).isFalse();
    assertThat(response.errorKey()).isEqualTo("error.pipeline.definition_not_found");
    verify(definitions, never()).loadPipelineSteps(10L);
  }

  @Test
  @DisplayName("定义没有 ID 时返回 not found，且不加载步骤")
  void execute_rejectsPipelineDefinitionWithoutId() {
    when(definitions.findPipelineDefinition("tenant-a", "job-a"))
        .thenReturn(
            new PlatformPipelineDefinitionRepository.PipelineDefinitionReference(null, "IMPORT"));

    StepExecutionResponse response = adapter().execute(request());

    assertThat(response.success()).isFalse();
    assertThat(response.errorKey()).isEqualTo("error.pipeline.definition_not_found");
    verify(definitions, never()).loadPipelineSteps(10L);
  }

  @Test
  @DisplayName("定义类型与当前 Worker 不一致时拒绝执行且不创建实例")
  void execute_rejectsPipelineDefinitionOfDifferentType() {
    when(definitions.findPipelineDefinition("tenant-a", "job-a"))
        .thenReturn(
            new PlatformPipelineDefinitionRepository.PipelineDefinitionReference(10L, "PROCESS"));

    StepExecutionResponse response = adapter().execute(request());

    assertThat(response.success()).isFalse();
    assertThat(response.errorKey()).isEqualTo("error.pipeline.definition_type_mismatch");
    verify(definitions, never()).loadPipelineSteps(10L);
    verify(runs, never()).createPipelineInstance(any());
  }

  private StepExecutionRequest request() {
    return new StepExecutionRequest("tenant-a", "job-a", "IMPORT", "worker-1", Map.of());
  }

  private TestAdapter adapter() {
    return new TestAdapter(definitions, runs, files);
  }

  private static final class TestAdapter
      extends AbstractPipelineStepExecutionAdapter<ExecutionContext, String> {

    private TestAdapter(
        PlatformPipelineDefinitionRepository definitions,
        PlatformPipelineRunRepository runs,
        PlatformFileRecordRepository files) {
      super(definitions, runs, files, mockProvider(), mockProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider() {
      return mock(ObjectProvider.class);
    }

    @Override
    protected String pipelineType() {
      return "IMPORT";
    }

    @Override
    protected String initialStage() {
      return "RECEIVE";
    }

    @Override
    protected ExecutionContext buildContext(
        StepExecutionRequest request, Map<String, Object> attributes, Long fileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected List<String> executeStages(ExecutionContext context) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected boolean isSuccess(String result) {
      return true;
    }

    @Override
    protected String resultStage(String result) {
      return result;
    }

    @Override
    protected String resultCode(String result) {
      return result;
    }

    @Override
    protected String resultMessage(String result) {
      return result;
    }

    @Override
    protected StepExecutionResponse buildSuccessResponse(
        ExecutionContext context, List<String> results, Map<String, Object> attributes) {
      return StepExecutionResponse.successResponse();
    }
  }
}
