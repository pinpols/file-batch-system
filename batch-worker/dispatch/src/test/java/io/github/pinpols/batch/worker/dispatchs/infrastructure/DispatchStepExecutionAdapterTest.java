package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DispatchStepExecutionAdapterTest {

  @Mock
  private DispatchStageExecutor stageExecutor;

  @Mock
  private PlatformFileRuntimeRepository runtimeRepository;

  private DispatchStepExecutionAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new DispatchStepExecutionAdapter(
        stageExecutor, new ObjectMapper(), runtimeRepository, emptyProvider(), emptyProvider());
  }

  @Test
  void descriptorsMatchDispatchPipeline() {
    assertThat(adapter.pipelineType()).isEqualTo(DispatchWorkerType.DISPATCH);
    assertThat(adapter.initialStage()).isEqualTo(DispatchStage.PREPARE.name());
  }

  @Test
  void buildContextParsesPayloadAndPrefersTaskId() throws Exception {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("taskId", 88L);
    attributes.put("dispatchId", "legacy-id");
    attributes.put("bizDate", "2026-09-11");
    attributes.put("payload", "{\"fileId\":\"42\",\"channelCode\":\"SFTP\"}");

    DispatchJobContext context = adapter.buildContext(request(attributes), attributes, 42L);

    assertThat(context.getDispatchId()).isEqualTo("88");
    assertThat(context.getBizDate()).isEqualTo("2026-09-11");
    assertThat(context.getAttributes().get("dispatchPayload"))
        .isInstanceOfSatisfying(DispatchPayload.class, payload -> {
          assertThat(payload.fileId()).isEqualTo("42");
          assertThat(payload.channelCode()).isEqualTo("SFTP");
        });
  }

  @Test
  void executeStagesDelegatesToExecutor() {
    DispatchJobContext context = new DispatchJobContext();
    List<DispatchStageResult> expected =
        List.of(DispatchStageResult.success(DispatchStage.PREPARE));
    when(stageExecutor.execute(context)).thenReturn(expected);

    assertThat(adapter.executeStages(context)).isSameAs(expected);
    verify(stageExecutor).execute(context);
  }

  @Test
  void resultAccessorsPreserveStageResultContract() {
    DispatchStageResult result =
        DispatchStageResult.failure(DispatchStage.DISPATCH, "DELIVERY_FAILED", "delivery failed");

    assertThat(adapter.isSuccess(null)).isFalse();
    assertThat(adapter.isSuccess(result)).isFalse();
    assertThat(adapter.resultStage(result)).isEqualTo("DISPATCH");
    assertThat(adapter.resultCode(result)).isEqualTo("DELIVERY_FAILED");
    assertThat(adapter.resultMessage(result)).isEqualTo("delivery failed");
  }

  @Test
  @SuppressWarnings("unchecked")
  void successResponsePublishesReceiptManifestAndChannelForWorkflow() {
    DispatchJobContext context = new DispatchJobContext();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_ID, 42L);
    attributes.put("receiptCode", "ACK-1");
    attributes.put("dispatchManifestRef", new DispatchManifestRef("daily.chk", "abc", 128L));
    attributes.put(
        "dispatchPayload",
        new DispatchPayload("42", "daily", "SFTP", "/in", null, null, true, false, null, Map.of()));
    context.setAttributes(attributes);

    StepExecutionResponse response = adapter.buildSuccessResponse(context, List.of(), attributes);

    assertThat(response.success()).isTrue();
    assertThat((Map<String, Object>) attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS))
        .containsEntry("fileId", 42L)
        .containsEntry("receiptCode", "ACK-1")
        .containsEntry("manifestRef", "daily.chk")
        .containsEntry("manifestChecksum", "abc")
        .containsEntry("manifestSizeBytes", 128L)
        .containsEntry("channelCode", "SFTP");
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
