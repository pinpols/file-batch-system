package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发补偿阶段:缺载荷与冲正失败的错误码,冲正后的文件状态、空渠道号回归与审计落库")
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
  @DisplayName("阶段标识为分发补偿阶段")
  void stage_returnsCompensate() {
    assertThat(step.stage()).isEqualTo(DispatchStage.COMPENSATE);
  }

  @Test
  @DisplayName("上下文缺少分发载荷时判定失败,并给出缺载荷错误码")
  void execute_failsWhenNoPayload() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_COMPENSATE_NO_PAYLOAD");
  }

  @Test
  @DisplayName("冲正落库影响行数为零时判定失败,且不更新文件状态")
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
  @DisplayName("冲正落库成功后判定成功,并把文件状态更新为失败")
  void execute_succeedsAndUpdatesFileStatusToFailed() {
    when(fileDispatchRepository.markCompensated(any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileRecords).updateFileStatus(eq(10L), eq(FileStatus.FAILED.code()), any());
  }

  @Test
  @DisplayName("载荷渠道号为空时冲正仍成功,不再抛出空指针并照常更新文件状态")
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
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileRecords).updateFileStatus(eq(10L), eq(FileStatus.FAILED.code()), any());
  }

  @Test
  @DisplayName("冲正成功后写入一条文件审计记录")
  void execute_writesAuditLog() {
    when(fileDispatchRepository.markCompensated(any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext();
    context.setWorkerId("w1");
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    context.getAttributes().put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "ext-1");
    step.execute(context);

    verify(fileAudits).appendAudit(any());
  }

  private DispatchJobContext buildContext() {
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    return context;
  }
}
