package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发投递阶段:缺载荷与准备上下文缺失的错误码,记录新增或累加、失败路由重试、落库失败与试运行跳过")
class DeliverDispatchStepTest {

  @Mock
  private FileDispatchRepository fileDispatchRepository;

  @Mock
  private DispatchChannelGateway dispatchChannelGateway;

  @Mock
  private PlatformFileRecordRepository fileRecords;

  private DeliverDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new DeliverDispatchStep(fileDispatchRepository, dispatchChannelGateway, fileRecords);
  }

  @Test
  @DisplayName("阶段标识为分发投递阶段")
  void stage_returnsDispatch() {
    assertThat(step.stage()).isEqualTo(DispatchStage.DISPATCH);
  }

  @Test
  @DisplayName("上下文缺少分发载荷时判定失败,并给出缺载荷错误码")
  void execute_failsWhenNoPayloadInContext() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_LOAD_NO_PAYLOAD");
  }

  @Test
  @DisplayName("上下文为空时判定失败,并给出缺载荷错误码")
  void execute_failsWhenContextIsNull() {
    DispatchStageResult result = step.execute(null);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_LOAD_NO_PAYLOAD");
  }

  @Test
  @DisplayName("上下文缺少文件号时判定失败,并给出准备上下文缺失错误码")
  void execute_failsWhenFilePrepareContextMissing() {
    DispatchJobContext context = buildContext();
    context.getAttributes().remove(PipelineRuntimeKeys.FILE_ID);

    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_MISSING");
  }

  @Test
  @DisplayName("没有历史派发记录时新增一条派发记录,投递成功后判定成功")
  void execute_insertsNewDispatchRecordWhenNoneExists() {
    setupMocksForNewRecord();
    when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
    when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileDispatchRepository).insertDispatchRecord(any());
  }

  @Test
  @DisplayName("已有派发记录时改为尝试次数加一,不再新增派发记录")
  void execute_incrementsAttemptWhenRecordAlreadyExists() {
    Map<String, Object> fileRecord = Map.of("id", 10L);
    Map<String, Object> channelConfig = Map.of("channel_type", "LOCAL");
    when(fileDispatchRepository.existsLatestDispatchRecord("t1", 10L, "CH1")).thenReturn(true);
    when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
    when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext(fileRecord, channelConfig);
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(fileDispatchRepository).incrementAttempt("t1", 10L, "CH1");
    verify(fileDispatchRepository, never()).insertDispatchRecord(any());
  }

  @Test
  @DisplayName("投递失败时判定失败,置请求重试标记、把后续阶段路由到重试并记录失败")
  void execute_failsAndSetsRetryWhenDispatchFails() {
    setupMocksForNewRecord();
    DispatchResult failed =
        new DispatchResult(false, "ext-1", null, false, false, "connection refused", null);
    when(dispatchChannelGateway.dispatch(any())).thenReturn(failed);

    DispatchJobContext context = buildContext();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_SEND_FAILED");
    assertThat(context.getAttributes())
        .containsEntry(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE);
    assertThat(context.getAttributes())
        .containsEntry(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.RETRY.name());
    verify(fileDispatchRepository).markFailed(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("投递成功但落库标记影响行数为零时判定失败")
  void execute_failsWhenMarkSentReturnsZero() {
    setupMocksForNewRecord();
    when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
    when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any()))
        .thenReturn(0);

    DispatchJobContext context = buildContext();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_SEND_FAILED");
  }

  @Test
  @DisplayName("派发记录新增影响行数为零时判定失败,并给出记录写入失败错误码")
  void execute_failsWhenInsertReturnsZero() {
    Map<String, Object> fileRecord = Map.of("id", 10L);
    Map<String, Object> channelConfig = Map.of("channel_type", "LOCAL");
    when(fileDispatchRepository.existsLatestDispatchRecord(any(), any(), any())).thenReturn(false);
    when(fileDispatchRepository.insertDispatchRecord(any())).thenReturn(0);

    DispatchJobContext context = buildContext(fileRecord, channelConfig);
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_INSERT_FAILED");
  }

  @Test
  @DisplayName("投递成功时把文件状态更新为分发中")
  void execute_updatesFileStatusToDispatching() {
    setupMocksForNewRecord();
    when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
    when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContext();
    step.execute(context);

    verify(fileRecords).updateFileStatus(eq(10L), eq(FileStatus.DISPATCHING.code()), any());
  }

  @Test
  @DisplayName("试运行模式下投递成功但跳过全部外部副作用:不碰派发仓库与网关,不更新文件状态,并写入试运行占位回执")
  void execute_dryRunSkipsAllDispatchSideEffects() {
    DispatchJobContext context = buildContext();
    context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(PipelineRuntimeKeys.DRY_RUN_SKIPPED, "DISPATCH_EXTERNAL_DELIVERY")
        .containsEntry(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "DRY_RUN")
        .containsEntry(DispatchRuntimeKeys.RECEIPT_CODE, "DRY_RUN_RECEIPT_CH1");
    verifyNoInteractions(fileDispatchRepository, dispatchChannelGateway);
    verify(fileRecords, never()).updateFileStatus(any(), any(), any());
  }

  private void setupMocksForNewRecord() {
    when(fileDispatchRepository.existsLatestDispatchRecord(any(), any(), any())).thenReturn(false);
    when(fileDispatchRepository.insertDispatchRecord(any())).thenReturn(1);
  }

  private DispatchResult successResult() {
    return new DispatchResult(true, "ext-1", "R-001", true, false, "ok", null);
  }

  private DispatchJobContext buildContext() {
    return buildContext(Map.of("id", 10L), Map.of("channel_type", "LOCAL"));
  }

  private DispatchJobContext buildContext(
      Map<String, Object> fileRecord, Map<String, Object> channelConfig) {
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
    context.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, channelConfig);
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 100L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    return context;
  }
}
