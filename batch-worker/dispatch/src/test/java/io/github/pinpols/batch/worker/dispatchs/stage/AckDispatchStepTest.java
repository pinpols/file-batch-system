package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发确认阶段:缺载荷与落库失败时的分支路由,以及确认、回执待定与无回执三种结局")
class AckDispatchStepTest {

  @Mock
  private FileDispatchRepository fileDispatchRepository;

  @Mock
  private PlatformFileRecordRepository fileRecords;

  private AckDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new AckDispatchStep(fileDispatchRepository, fileRecords);
  }

  @Test
  @DisplayName("阶段标识为分发确认阶段")
  void stage_returnsAck() {
    assertThat(step.stage()).isEqualTo(DispatchStage.ACK);
  }

  @Test
  @DisplayName("上下文缺少分发载荷时判定失败,并给出缺载荷错误码")
  void execute_failsWhenNoPayloadInContext() {
    DispatchJobContext context = new DispatchJobContext();
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_ACK_NO_PAYLOAD");
  }

  @Test
  @DisplayName("投递结果已确认时确认成功:回执状态置成功,并联动文件状态为已分发")
  void execute_succeedsAndMarksAckedWhenAcknowledgedByDispatchResult() {
    when(fileDispatchRepository.markAcked(any(), any(), any(), any())).thenReturn(1);

    DispatchJobContext context = buildContextWithAckedResult("R-001");
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(DispatchRuntimeKeys.RECEIPT_STATUS, FileReceiptStatus.SUCCESS.code());
    verify(fileRecords).updateFileStatus(eq(10L), eq(FileStatus.DISPATCHED.code()), any());
  }

  @Test
  @DisplayName("落库确认影响行数为零时判定失败,并把后续阶段路由到补偿")
  void execute_routesToCompensateWhenMarkAckedFails() {
    when(fileDispatchRepository.markAcked(any(), any(), any(), any())).thenReturn(0);

    DispatchJobContext context = buildContextWithAckedResult("R-001");
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_ACK_FAILED");
    assertThat(context.getAttributes())
        .containsEntry(
            PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
  }

  @Test
  @DisplayName("落库确认失败且载荷要求重试时,判定失败并把后续阶段路由到重试")
  void execute_routesToRetryWhenMarkAckedFailsAndRetryRequested() {
    when(fileDispatchRepository.markAcked(any(), any(), any(), any())).thenReturn(0);

    DispatchJobContext context = buildContextWithAckedResult("R-001");
    context.getAttributes().put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE);
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(context.getAttributes())
        .containsEntry(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.RETRY.name());
  }

  @Test
  @DisplayName("投递结果回执待定时判定成功,回执状态置待定且不调用落库确认")
  void execute_setsPendingStatusWhenReceiptPending() {

    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", null, false, true, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_RESULT, dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(DispatchRuntimeKeys.RECEIPT_STATUS, FileReceiptStatus.PENDING.code());
    verify(fileDispatchRepository, never()).markAcked(any(), any(), any(), any());
  }

  @Test
  @DisplayName("载荷与投递结果都没有回执码时,回退用文件号拼出回执码完成确认")
  void execute_fallsBackToFileIdReceiptCodeWhenBothNull() {
    when(fileDispatchRepository.markAcked(any(), eq(10L), any(), eq("ACK-10"))).thenReturn(1);

    // acknowledged=true but receiptCode=null in both payload and result
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", null, true, false, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_RESULT, dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileDispatchRepository).markAcked("t1", 10L, "CH1", "ACK-10");
  }

  @Test
  @DisplayName("既未确认也无待定回执时判定成功,不调用落库确认但仍联动文件状态为已分发")
  void execute_succeedsWithoutAckWhenNeitherAcknowledgedNorPending() {

    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", null, false, false, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_RESULT, dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileDispatchRepository, never()).markAcked(any(), any(), any(), any());
    verify(fileRecords).updateFileStatus(eq(10L), eq(FileStatus.DISPATCHED.code()), any());
  }

  private DispatchJobContext buildContextWithAckedResult(String receiptCode) {
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", null, null, receiptCode, null, null, null, null);
    DispatchResult dispatchResult =
        new DispatchResult(true, "ext-1", receiptCode, true, false, "ok", null);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_RESULT, dispatchResult);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    return context;
  }
}
