package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AckDispatchStepTest {

  @Mock private FileDispatchRepository fileDispatchRepository;
  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  private AckDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new AckDispatchStep(fileDispatchRepository, runtimeRepository);
  }

  @Test
  void stage_returnsAck() {
    assertThat(step.stage()).isEqualTo(DispatchStage.ACK);
  }

  @Test
  void execute_failsWhenNoPayloadInContext() {
    DispatchJobContext context = new DispatchJobContext();
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_ACK_NO_PAYLOAD");
  }

  @Test
  void execute_succeedsAndMarksAckedWhenAcknowledgedByDispatchResult() {
    when(runtimeRepository.toLong(any())).thenReturn(10L);
    when(fileDispatchRepository.markAcked(any(), any(), any(), any())).thenReturn(1);

    DispatchJobContext context = buildContextWithAckedResult("R-001");
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("receiptStatus")).isEqualTo("SUCCESS");
    verify(runtimeRepository).updateFileStatus(eq(10L), eq("DISPATCHED"), any());
  }

  @Test
  void execute_routesToCompensateWhenMarkAckedFails() {
    when(runtimeRepository.toLong(any())).thenReturn(10L);
    when(fileDispatchRepository.markAcked(any(), any(), any(), any())).thenReturn(0);

    DispatchJobContext context = buildContextWithAckedResult("R-001");
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_ACK_FAILED");
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
        .isEqualTo(DispatchStage.COMPENSATE.name());
  }

  @Test
  void execute_routesToRetryWhenMarkAckedFailsAndRetryRequested() {
    when(runtimeRepository.toLong(any())).thenReturn(10L);
    when(fileDispatchRepository.markAcked(any(), any(), any(), any())).thenReturn(0);

    DispatchJobContext context = buildContextWithAckedResult("R-001");
    context.getAttributes().put("retryRequested", Boolean.TRUE);
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
        .isEqualTo(DispatchStage.RETRY.name());
  }

  @Test
  void execute_setsPendingStatusWhenReceiptPending() {
    when(runtimeRepository.toLong(any())).thenReturn(10L);

    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", null, false, true, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put("dispatchPayload", payload);
    context.getAttributes().put("dispatchResult", dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("receiptStatus")).isEqualTo("PENDING");
    verify(fileDispatchRepository, never()).markAcked(any(), any(), any(), any());
  }

  @Test
  void execute_fallsBackToFileIdReceiptCodeWhenBothNull() {
    when(runtimeRepository.toLong(any())).thenReturn(10L);
    when(fileDispatchRepository.markAcked(any(), eq(10L), any(), eq("ACK-10"))).thenReturn(1);

    // acknowledged=true but receiptCode=null in both payload and result
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", null, true, false, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put("dispatchPayload", payload);
    context.getAttributes().put("dispatchResult", dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileDispatchRepository).markAcked("t1", 10L, "CH1", "ACK-10");
  }

  @Test
  void execute_succeedsWithoutAckWhenNeitherAcknowledgedNorPending() {
    when(runtimeRepository.toLong(any())).thenReturn(10L);

    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", null, false, false, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put("dispatchPayload", payload);
    context.getAttributes().put("dispatchResult", dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileDispatchRepository, never()).markAcked(any(), any(), any(), any());
    verify(runtimeRepository).updateFileStatus(eq(10L), eq("DISPATCHED"), any());
  }

  private DispatchJobContext buildContextWithAckedResult(String receiptCode) {
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, receiptCode, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", receiptCode, true, false, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put("dispatchPayload", payload);
    context.getAttributes().put("dispatchResult", dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    return context;
  }
}
