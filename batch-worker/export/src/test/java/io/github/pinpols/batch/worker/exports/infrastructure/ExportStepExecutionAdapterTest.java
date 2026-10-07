package io.github.pinpols.batch.worker.exports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("导出步骤执行适配器单测:上下文构建,阶段委派与成功响应元数据语义")
class ExportStepExecutionAdapterTest {

  @Mock
  private ExportStageExecutor stageExecutor;

  @Mock
  private PlatformFileRecordRepository runtimeRepository;

  @Mock
  private PlatformPipelineDefinitionRepository pipelineDefinitions;

  @Mock
  private PlatformPipelineRunRepository pipelineRuns;

  private ExportStepExecutionAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new ExportStepExecutionAdapter(
        stageExecutor,
        new ObjectMapper(),
        pipelineDefinitions,
        pipelineRuns,
        runtimeRepository,
        emptyProvider(),
        emptyProvider());
  }

  @Test
  @DisplayName("适配器的管道类型与起始阶段同导出流程保持一致")
  void shouldMatchExportPipeline_whenDescribingAdapter() {
    assertThat(adapter.pipelineType()).isEqualTo(ExportWorkerType.EXPORT);
    assertThat(adapter.initialStage()).isEqualTo(ExportStage.PREPARE.name());
  }

  @Test
  @DisplayName("构建上下文时解析载荷,并回填业务日期与文件标识")
  void shouldParsePayloadIntoContext_whenBuildingContext() throws Exception {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.BIZ_DATE, "2026-09-11");
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
  @DisplayName("缺少管道实例时直接委派阶段执行,不额外加锁")
  void shouldDelegateStages_whenPipelineInstanceMissing() {
    ExportJobContext context = new ExportJobContext();
    context.setAttributes(new LinkedHashMap<>());
    List<ExportStageResult> expected = List.of(ExportStageResult.success(ExportStage.PREPARE));
    when(stageExecutor.execute(context)).thenReturn(expected);

    assertThat(adapter.executeStages(context)).isSameAs(expected);
    verify(stageExecutor).execute(context);
  }

  @Test
  @DisplayName("结果访问器如实反映阶段,错误码与消息,空结果判定为失败")
  void shouldPreserveStageResultContract_whenReadingAccessors() {
    ExportStageResult result =
        ExportStageResult.failure(ExportStage.GENERATE, "WRITE_FAILED", "write failed");

    assertThat(adapter.isSuccess(null)).isFalse();
    assertThat(adapter.isSuccess(result)).isFalse();
    assertThat(adapter.resultStage(result)).isEqualTo("GENERATE");
    assertThat(adapter.resultCode(result)).isEqualTo("WRITE_FAILED");
    assertThat(adapter.resultMessage(result)).isEqualTo("write failed");
  }

  @Test
  @DisplayName("成功响应把文件标识,记录数与文件大小写入节点输出")
  @SuppressWarnings("unchecked")
  void shouldPublishExportMetadata_whenBuildingSuccessResponse() {
    ExportJobContext context = new ExportJobContext();
    context.setBizDate("2026-09-11");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_ID, 42L);
    attributes.put(PipelineRuntimeKeys.OBJECT_NAME, "daily.csv");
    attributes.put(PipelineRuntimeKeys.RECORD_COUNT, 10L);
    attributes.put(PipelineRuntimeKeys.FILE_SIZE_BYTES, 128L);
    context.setAttributes(attributes);

    StepExecutionResponse response = adapter.buildSuccessResponse(context, List.of(), attributes);

    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo("daily.csv");
    assertThat((Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS))
        .containsEntry(PipelineRuntimeKeys.FILE_ID, 42L)
        .containsEntry(PipelineRuntimeKeys.RECORD_COUNT, 10L)
        .containsEntry("inputCount", 10L)
        .containsEntry("outputCount", 10L)
        .containsEntry(PipelineRuntimeKeys.FILE_SIZE_BYTES, 128L);
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
