package io.github.pinpols.batch.worker.exports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.domain.ExportWorkerType;
import io.github.pinpols.batch.worker.exports.stage.ExportStageExecutor;
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
class ExportStepExecutionAdapterTest {

  @Mock
  private ExportStageExecutor stageExecutor;

  @Mock
  private PlatformFileRuntimeRepository runtimeRepository;

  private ExportStepExecutionAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new ExportStepExecutionAdapter(
        stageExecutor, new ObjectMapper(), runtimeRepository, emptyProvider(), emptyProvider());
  }

  @Test
  void descriptorsMatchExportPipeline() {
    assertThat(adapter.pipelineType()).isEqualTo(ExportWorkerType.EXPORT);
    assertThat(adapter.initialStage()).isEqualTo(ExportStage.PREPARE.name());
  }

  @Test
  void buildContextParsesPayloadAndMapsExportFields() throws Exception {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("bizDate", "2026-09-11");
    attributes.put("payload", "{\"fileCode\":\"daily\",\"objectName\":\"daily.csv\"}");

    ExportJobContext context = adapter.buildContext(request(attributes), attributes, 42L);

    assertThat(context.getBizDate()).isEqualTo("2026-09-11");
    assertThat(context.getFileId()).isEqualTo("42");
    assertThat(context.getAttributes().get("exportPayload"))
        .isInstanceOfSatisfying(ExportPayload.class, payload -> {
          assertThat(payload.fileCode()).isEqualTo("daily");
          assertThat(payload.objectName()).isEqualTo("daily.csv");
        });
  }

  @Test
  void executeStagesDelegatesWithoutPipelineLockWhenInstanceIsMissing() {
    ExportJobContext context = new ExportJobContext();
    context.setAttributes(new LinkedHashMap<>());
    List<ExportStageResult> expected = List.of(ExportStageResult.success(ExportStage.PREPARE));
    when(stageExecutor.execute(context)).thenReturn(expected);

    assertThat(adapter.executeStages(context)).isSameAs(expected);
    verify(stageExecutor).execute(context);
  }

  @Test
  void resultAccessorsPreserveStageResultContract() {
    ExportStageResult result =
        ExportStageResult.failure(ExportStage.GENERATE, "WRITE_FAILED", "write failed");

    assertThat(adapter.isSuccess(null)).isFalse();
    assertThat(adapter.isSuccess(result)).isFalse();
    assertThat(adapter.resultStage(result)).isEqualTo("GENERATE");
    assertThat(adapter.resultCode(result)).isEqualTo("WRITE_FAILED");
    assertThat(adapter.resultMessage(result)).isEqualTo("write failed");
  }

  @Test
  @SuppressWarnings("unchecked")
  void successResponsePublishesExportMetadataForWorkflow() {
    ExportJobContext context = new ExportJobContext();
    context.setBizDate("2026-09-11");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_ID, 42L);
    attributes.put("objectName", "daily.csv");
    attributes.put("recordCount", 10L);
    attributes.put("fileSizeBytes", 128L);
    context.setAttributes(attributes);

    StepExecutionResponse response = adapter.buildSuccessResponse(context, List.of(), attributes);

    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo("daily.csv");
    assertThat((Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS))
        .containsEntry("fileId", 42L)
        .containsEntry("recordCount", 10L)
        .containsEntry("inputCount", 10L)
        .containsEntry("outputCount", 10L)
        .containsEntry("fileSizeBytes", 128L);
  }

  private static StepExecutionRequest request(Map<String, Object> attributes) {
    return new StepExecutionRequest(
        "tenant-a", "job-export", "EXPORT_PREPARE", "worker-1", attributes);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> emptyProvider() {
    return mock(ObjectProvider.class);
  }
}
