package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.enums.SystemPipelineDefinition;
import io.github.pinpols.batch.common.enums.SystemPipelineTaskKeys;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("System Pipeline Worker 身份校验")
class AbstractPipelineStepExecutionAdapterDefinitionTest {

  private final PlatformPipelineRunRepository runs = mock(PlatformPipelineRunRepository.class);
  private final PlatformFileRecordRepository files = mock(PlatformFileRecordRepository.class);

  @Test
  @DisplayName("缺少固定 system Pipeline 身份时拒绝执行")
  void execute_rejectsTaskWithoutPinnedSystemPipelineIdentity() {
    StepExecutionResponse response = adapter().execute(request(Map.of()));

    assertThat(response.success()).isFalse();
    assertThat(response.errorKey()).isEqualTo("error.pipeline.system_definition_mismatch");
    verify(runs, never()).createPipelineInstance(any());
  }

  @Test
  @DisplayName("固定的 system Pipeline code/version 与 Worker 类型不一致时拒绝执行")
  void execute_rejectsTaskPinnedToDifferentSystemPipeline() {
    StepExecutionResponse response = adapter()
        .execute(request(
            Map.of(SystemPipelineTaskKeys.CODE, "PROCESS", SystemPipelineTaskKeys.VERSION, "1")));

    assertThat(response.success()).isFalse();
    assertThat(response.errorKey()).isEqualTo("error.pipeline.system_definition_mismatch");
    verify(runs, never()).createPipelineInstance(any());
  }

  @Test
  @DisplayName("从 Workflow task payload 接受 JSON 数值型 system Pipeline 版本")
  void execute_acceptsNumericVersionPinnedInTaskPayload() {
    StepExecutionResponse response = adapter()
        .execute(request(Map.of(
            PipelineRuntimeKeys.PAYLOAD,
            "{\"systemPipelineCode\":\"IMPORT\",\"systemPipelineVersion\":1}")));

    assertThat(response.errorKey()).isNotEqualTo("error.pipeline.system_definition_mismatch");
    verify(runs)
        .createPipelineInstance(argThat(param -> "IMPORT".equals(param.systemPipelineCode())
            && Integer.valueOf(1).equals(param.systemPipelineVersion())));
  }

  @Test
  @DisplayName("文件不存在或属于其他租户时,在创建 Pipeline 实例前拒绝")
  void execute_rejectsForeignTenantFileBeforeCreatingPipelineRun() {
    when(files.loadFileRecord("tenant-a", 77L)).thenReturn(Map.of());
    StepExecutionResponse response = adapter()
        .execute(request(Map.of(
            PipelineRuntimeKeys.FILE_ID,
            77L,
            PipelineRuntimeKeys.PAYLOAD,
            "{\"systemPipelineCode\":\"IMPORT\",\"systemPipelineVersion\":1}")));

    assertThat(response.success()).isFalse();
    assertThat(response.errorKey()).isEqualTo("error.file.record_not_found");
    verify(runs, never()).createPipelineInstance(any());
  }

  private static StepExecutionRequest request(Map<String, Object> context) {
    return new StepExecutionRequest("tenant-a", "job-a", "IMPORT", "worker-1", context);
  }

  private TestAdapter adapter() {
    return new TestAdapter(runs, files, this::defaultTemplates);
  }

  private List<PipelineStepTemplate> defaultTemplates() {
    SystemPipelineDefinition definition =
        SystemPipelineCatalog.forJobType("IMPORT").orElseThrow();
    return definition.stages().stream()
        .map(stage -> PipelineStepTemplate.builder()
            .stepCode(stage)
            .stepName(stage)
            .stageCode(stage)
            .stepOrder(definition.stages().indexOf(stage) + 1)
            .implCode(stage)
            .enabled(true)
            .build())
        .toList();
  }

  private static final class TestAdapter
      extends AbstractPipelineStepExecutionAdapter<ExecutionContext, String> {

    private TestAdapter(
        PlatformPipelineRunRepository runs,
        PlatformFileRecordRepository files,
        PipelineStepTemplateProvider templates) {
      super(runs, files, templates, mockProvider(), mockProvider());
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
      return "RECEIVE";
    }

    @Override
    protected String resultCode(String result) {
      return "FAILED";
    }

    @Override
    protected String resultMessage(String result) {
      return "failed";
    }

    @Override
    protected StepExecutionResponse buildSuccessResponse(
        ExecutionContext context, List<String> results, Map<String, Object> attributes) {
      return StepExecutionResponse.successResponse();
    }

    @Override
    protected String unexpectedErrorCode() {
      return "UNEXPECTED";
    }
  }
}
