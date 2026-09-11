package io.github.pinpols.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.domain.ImportWorkerType;
import io.github.pinpols.batch.worker.imports.stage.ImportStageExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ImportStepExecutionAdapterTest {

  @Mock
  private ImportStageExecutor stageExecutor;

  @Mock
  private PlatformFileRuntimeRepository runtimeRepository;

  private ImportStepExecutionAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new ImportStepExecutionAdapter(
        stageExecutor, runtimeRepository, emptyProvider(), emptyProvider());
  }

  @Test
  void descriptorsMatchImportPipeline() {
    assertThat(adapter.pipelineType()).isEqualTo(ImportWorkerType.IMPORT);
    assertThat(adapter.initialStage()).isEqualTo(ImportStage.RECEIVE.name());
  }

  @Test
  void buildContextMapsCommonAndImportFields() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("bizDate", "2026-09-11");
    attributes.put("payload", "{}");

    ImportJobContext context = adapter.buildContext(request(attributes), attributes, 42L);

    assertThat(context.getTenantId()).isEqualTo("tenant-a");
    assertThat(context.getJobCode()).isEqualTo("job-import");
    assertThat(context.getWorkerId()).isEqualTo("worker-1");
    assertThat(context.getBizDate()).isEqualTo("2026-09-11");
    assertThat(context.getFileId()).isEqualTo("42");
    assertThat(context.getAttributes()).isSameAs(attributes);
  }

  @Test
  void executeStagesDelegatesToExecutor() {
    ImportJobContext context = new ImportJobContext();
    List<ImportStageResult> expected = List.of(ImportStageResult.success(ImportStage.RECEIVE));
    when(stageExecutor.execute(context)).thenReturn(expected);

    assertThat(adapter.executeStages(context)).isSameAs(expected);
    verify(stageExecutor).execute(context);
  }

  @Test
  void resultAccessorsPreserveStageResultContract() {
    ImportStageResult result =
        ImportStageResult.failure(ImportStage.VALIDATE, "INVALID_ROW", "invalid row");

    assertThat(adapter.isSuccess(null)).isFalse();
    assertThat(adapter.isSuccess(result)).isFalse();
    assertThat(adapter.resultStage(result)).isEqualTo("VALIDATE");
    assertThat(adapter.resultCode(result)).isEqualTo("INVALID_ROW");
    assertThat(adapter.resultMessage(result)).isEqualTo("invalid row");
  }

  @Test
  @SuppressWarnings("unchecked")
  void successResponsePublishesImportCountsForWorkflow() {
    ImportJobContext context = new ImportJobContext();
    context.setBizDate("2026-09-11");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_ID, 42L);
    attributes.put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 12L);
    attributes.put(PipelineRuntimeKeys.IMPORT_PARSED_COUNT, 12L);
    attributes.put(PipelineRuntimeKeys.IMPORT_LOADED_COUNT, 10L);
    context.setAttributes(attributes);

    StepExecutionResponse response = adapter.buildSuccessResponse(context, List.of(), attributes);

    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo("imported 10 row(s)");
    assertThat((Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS))
        .containsEntry("fileId", 42L)
        .containsEntry("inputCount", 12L)
        .containsEntry("outputCount", 10L)
        .containsEntry("bizDate", "2026-09-11");
  }

  private static StepExecutionRequest request(Map<String, Object> attributes) {
    return new StepExecutionRequest(
        "tenant-a", "job-import", "IMPORT_RECEIVE", "worker-1", attributes);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> emptyProvider() {
    return mock(ObjectProvider.class);
  }
}
