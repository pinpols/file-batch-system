package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发完成阶段:缺载荷错误码,回执成功才联动文件状态,以及审计落库与空回执状态兜底")
class CompleteDispatchStepTest {

  @Mock
  private PlatformFileRecordRepository fileRecords;

  @Mock
  private PlatformFileAuditRepository fileAudits;

  private CompleteDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new CompleteDispatchStep(fileRecords, fileAudits);
  }

  @Test
  @DisplayName("阶段标识为分发完成阶段")
  void stage_returnsComplete() {
    assertThat(step.stage()).isEqualTo(DispatchStage.COMPLETE);
  }

  @Test
  @DisplayName("上下文缺少分发载荷时判定失败,并给出缺载荷错误码")
  void execute_failsWhenNoPayload() {
    DispatchJobContext context = new DispatchJobContext();
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_COMPLETE_NO_PAYLOAD");
  }

  @Test
  @DisplayName("回执状态为成功时判定成功,并把文件状态更新为已分发")
  void execute_updatesFileStatusToDispatchedWhenReceiptStatusIsSuccess() {

    DispatchJobContext context = buildContext("SUCCESS", "R-001");
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileRecords).updateFileStatus(eq(10L), eq(FileStatus.DISPATCHED.code()), any());
  }

  @Test
  @DisplayName("回执状态非成功时仍判定成功,但不更新文件状态")
  void execute_doesNotUpdateFileStatusWhenReceiptNotSuccess() {

    DispatchJobContext context = buildContext("PENDING", null);
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileRecords, never()).updateFileStatus(any(), any(), any());
  }

  @Test
  @DisplayName("回执状态为无回执时也写入一条文件审计记录")
  void execute_alwaysWritesAuditLog() {

    DispatchJobContext context = buildContext("NONE", null);
    step.execute(context);

    verify(fileAudits).appendAudit(any());
  }

  @Test
  @DisplayName("上下文带回调码时执行不报错,并写入一条文件审计记录")
  void execute_includesReceiptCodeInMetadataWhenPresent() {

    DispatchJobContext context = buildContext("SUCCESS", "R-001");
    context.getAttributes().put(DispatchRuntimeKeys.RECEIPT_CODE, "R-001");
    step.execute(context);

    // Just ensure no NPE and audit is written
    verify(fileAudits).appendAudit(any());
  }

  @Test
  @DisplayName("上下文缺少回执状态时按无回执处理,判定成功且不更新文件状态")
  void execute_handlesNullReceiptStatusGracefully() {

    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    // receiptStatus is absent — defaults to "NONE"

    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isTrue();
    verify(fileRecords, never()).updateFileStatus(any(), any(), any());
  }

  private DispatchJobContext buildContext(String receiptStatus, String receiptCode) {
    DispatchPayload payload = new DispatchPayload(
        "10", null, "CH1", "target", "ext-1", receiptCode, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    context.getAttributes().put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "ext-1");
    context.getAttributes().put(DispatchRuntimeKeys.RECEIPT_STATUS, receiptStatus);
    if (receiptCode != null) {
      context.getAttributes().put(DispatchRuntimeKeys.RECEIPT_CODE, receiptCode);
    }
    return context;
  }
}
