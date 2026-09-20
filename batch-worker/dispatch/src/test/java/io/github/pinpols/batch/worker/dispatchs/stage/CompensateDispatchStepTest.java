package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompensateDispatchStepTest {

  @Mock
  private FileDispatchRepository fileDispatchRepository;

  @Mock
  private PlatformFileRecordRepository fileRecords;

  @Mock
  private PlatformFileAuditRepository fileAudits;

  private CompensateDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new CompensateDispatchStep(fileDispatchRepository, fileRecords, fileAudits);
  }

  @Test
  void stage_returnsCompensate() {
    assertThat(step.stage()).isEqualTo(DispatchStage.COMPENSATE);
  }

  @Test
  void execute_failsWhenNoPayload() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_COMPENSATE_NO_PAYLOAD");
  }

  @Test
  void execute_failsWhenMarkCompensatedReturnsZero() {
    when(fileDispatchRepository.markCompensated(any(), any(), any(), any(), any()))
        .thenReturn(0);

    DispatchJobContext context = buildContext();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_COMPENSATE_FAILED");
    verify(fileRecords, never()).updateFileStatus(any(), any(), any());
  }

  @Test
  void execute_succeedsAndUpdatesFileStatusToFailed() {
    when(fileDispatchRepository.markCompensated(any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileRecords).updateFileStatus(eq(10L), eq("FAILED"), any());
  }

  @Test
  void execute_succeedsWhenChannelCodeNull_withoutNpe() {
    // 回归:channelCode 为 null(DispatchPayload.channelCode 是可空 String,无 @NotBlank)时,
    // updateFileStatus 内构造 Map.of("channelCode", channelCode) 曾 NPE,把补偿冲正掩盖成 500。
    // 见 CompensateDispatchStep#execute。
    when(fileDispatchRepository.markCompensated(any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchPayload payload =
        new DispatchPayload("10", null, null, "target", null, null, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.getAttributes().put("dispatchPayload", payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileRecords).updateFileStatus(eq(10L), eq("FAILED"), any());
  }

  @Test
  void execute_writesAuditLog() {
    when(fileDispatchRepository.markCompensated(any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext();
    context.setWorkerId("w1");
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    context.getAttributes().put("externalRequestId", "ext-1");
    step.execute(context);

    verify(fileAudits).appendAudit(any());
  }

  private DispatchJobContext buildContext() {
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.getAttributes().put("dispatchPayload", payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    return context;
  }
}
