package io.github.pinpols.batch.worker.dispatchs.infrastructure;

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
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchWorkerType;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchManifestRef;
import io.github.pinpols.batch.worker.dispatchs.stage.DispatchStageExecutor;
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
@DisplayName("分发步骤执行适配器:流水线描述、上下文构建、阶段委派、结果访问与成功响应产出字段")
class DispatchStepExecutionAdapterTest {

  @Mock
  private DispatchStageExecutor stageExecutor;

  @Mock
  private PlatformFileRecordRepository fileRecords;

  @Mock
  private PlatformPipelineDefinitionRepository pipelineDefinitions;

  @Mock
  private PlatformPipelineRunRepository pipelineRuns;

  private DispatchStepExecutionAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new DispatchStepExecutionAdapter(
        stageExecutor,
        new ObjectMapper(),
        pipelineDefinitions,
        pipelineRuns,
        fileRecords,
        emptyProvider(),
        emptyProvider());
  }

  @Test
  @DisplayName("适配器描述分发流水线:类型为分发,起始阶段为准备阶段")
  void shouldExposeDispatchPipelineDescriptor_whenQueried() {
    assertThat(adapter.pipelineType()).isEqualTo(DispatchWorkerType.DISPATCH);
    assertThat(adapter.initialStage()).isEqualTo(DispatchStage.PREPARE.name());
  }

  @Test
  @DisplayName("构建上下文时优先取任务号,并解析出业务日期与载荷中的文件号与渠道号")
  void shouldPreferTaskIdAndParsePayload_whenBuildingContext() throws Exception {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.TASK_ID, 88L);
    attributes.put("dispatchId", "legacy-id");
    attributes.put(PipelineRuntimeKeys.BIZ_DATE, "2026-09-11");
    attributes.put("payload", "{\"fileId\":\"42\",\"channelCode\":\"SFTP\"}");

    DispatchJobContext context = adapter.buildContext(request(attributes), attributes, 42L);

    assertThat(context.getDispatchId()).isEqualTo("88");
    assertThat(context.getBizDate()).isEqualTo("2026-09-11");
    assertThat(context.getAttributes().get(DispatchRuntimeKeys.DISPATCH_PAYLOAD))
        .isInstanceOfSatisfying(DispatchPayload.class, payload -> {
          assertThat(payload.fileId()).isEqualTo("42");
          assertThat(payload.channelCode()).isEqualTo("SFTP");
        });
  }

  @Test
  @DisplayName("执行阶段时原样委派给阶段执行器,并返回同一份阶段结果")
  void shouldDelegateStageExecution_whenExecutingStages() {
    DispatchJobContext context = new DispatchJobContext();
    List<DispatchStageResult> expected =
        List.of(DispatchStageResult.success(DispatchStage.PREPARE));
    when(stageExecutor.execute(context)).thenReturn(expected);

    assertThat(adapter.executeStages(context)).isSameAs(expected);
    verify(stageExecutor).execute(context);
  }

  @Test
  @DisplayName("结果访问器读出阶段名、失败码与失败原因,空结果同样判定为不成功")
  void shouldExposeStageResultFields_whenReadingResultAccessors() {
    DispatchStageResult result =
        DispatchStageResult.failure(DispatchStage.DISPATCH, "DELIVERY_FAILED", "delivery failed");

    assertThat(adapter.isSuccess(null)).isFalse();
    assertThat(adapter.isSuccess(result)).isFalse();
    assertThat(adapter.resultStage(result)).isEqualTo("DISPATCH");
    assertThat(adapter.resultCode(result)).isEqualTo("DELIVERY_FAILED");
    assertThat(adapter.resultMessage(result)).isEqualTo("delivery failed");
  }

  @Test
  @DisplayName("构建成功响应时把文件号、回执码、清单引用与渠道号写入节点输出")
  @SuppressWarnings("unchecked")
  void shouldPublishReceiptManifestAndChannel_whenBuildingSuccessResponse() {
    DispatchJobContext context = new DispatchJobContext();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_ID, 42L);
    attributes.put(DispatchRuntimeKeys.RECEIPT_CODE, "ACK-1");
    attributes.put(
        DispatchRuntimeKeys.DISPATCH_MANIFEST_REF,
        new DispatchManifestRef("daily.chk", "abc", 128L));
    attributes.put(
        DispatchRuntimeKeys.DISPATCH_PAYLOAD,
        new DispatchPayload("42", "daily", "SFTP", "/in", null, null, true, false, null, Map.of()));
    context.setAttributes(attributes);

    StepExecutionResponse response = adapter.buildSuccessResponse(context, List.of(), attributes);

    assertThat(response.success()).isTrue();
    assertThat((Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS))
        .containsEntry(PipelineRuntimeKeys.FILE_ID, 42L)
        .containsEntry(DispatchRuntimeKeys.RECEIPT_CODE, "ACK-1")
        .containsEntry("manifestRef", "daily.chk")
        .containsEntry("manifestChecksum", "abc")
        .containsEntry("manifestSizeBytes", 128L)
        .containsEntry(DispatchRuntimeKeys.CHANNEL_CODE, "SFTP");
  }

  private static StepExecutionRequest request(Map<String, Object> attributes) {
    return new StepExecutionRequest(
        "tenant-a", "job-dispatch", "DISPATCH_PREPARE", "worker-1", attributes);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> emptyProvider() {
    return mock(ObjectProvider.class);
  }
}
