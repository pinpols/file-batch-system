package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
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
@DisplayName("分发重试阶段:缺载荷与重试失败的路由,未请求重试的空操作,以及重试成功后的确认与计数")
class RetryDispatchStepTest {

  @Mock
  private FileDispatchRepository fileDispatchRepository;

  @Mock
  private DispatchChannelGateway dispatchChannelGateway;

  private RetryDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new RetryDispatchStep(fileDispatchRepository, dispatchChannelGateway);
  }

  @Test
  @DisplayName("阶段标识为分发重试阶段")
  void stage_returnsRetry() {
    assertThat(step.stage()).isEqualTo(DispatchStage.RETRY);
  }

  @Test
  @DisplayName("上下文缺少分发载荷时判定失败,给出缺载荷错误码并路由到补偿")
  void execute_failsAndRoutesToCompensateWhenNoPayload() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_RETRY_NO_PAYLOAD");
    assertThat(context.getAttributes())
        .containsEntry(
            PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
  }

  @Test
  @DisplayName("载荷未请求重试时空操作成功,并直接路由到补偿")
  void execute_succeedsWithNoOpWhenRetryNotRequested() {
    DispatchJobContext context = buildContext();
    context.getAttributes().put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(
            PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
  }

  @Test
  @DisplayName("重试投递与落库标记都成功时判定成功,路由到确认并标记重试已恢复且尝试次数加一")
  void execute_succeedsAndRoutesToAckWhenRetrySucceeds() {
    when(dispatchChannelGateway.dispatch(any()))
        .thenReturn(new DispatchResult(true, "ext-retry", "R-retry", true, false, "ok", null));
    when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    DispatchJobContext context = buildContextWithRetryRequested();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.ACK.name());
    assertThat(context.getAttributes())
        .containsEntry(DispatchRuntimeKeys.RETRY_RECOVERED, Boolean.TRUE);
    verify(fileDispatchRepository).incrementAttempt("t1", 10L, "CH1");
  }

  @Test
  @DisplayName("重试投递失败时判定失败,给出重试失败错误码并路由到补偿")
  void execute_failsAndRoutesToCompensateWhenRetryFails() {
    when(dispatchChannelGateway.dispatch(any()))
        .thenReturn(new DispatchResult(false, null, null, false, false, "network error", null));
    when(fileDispatchRepository.markFailed(any(), any(), any(), any(), any())).thenReturn(1);

    DispatchJobContext context = buildContextWithRetryRequested();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_RETRY_FAILED");
    assertThat(context.getAttributes())
        .containsEntry(
            PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
  }

  @Test
  @DisplayName("重试投递成功但落库标记影响行数为零时判定失败,并路由到补偿")
  void execute_failsWhenMarkSentAfterRetryReturnsZero() {
    when(dispatchChannelGateway.dispatch(any()))
        .thenReturn(new DispatchResult(true, "ext-retry", "R-retry", true, false, "ok", null));
    when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any()))
        .thenReturn(0);

    DispatchJobContext context = buildContextWithRetryRequested();
    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_RETRY_FAILED");
    assertThat(context.getAttributes())
        .containsEntry(
            PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
  }

  private DispatchJobContext buildContext() {
    DispatchPayload payload =
        new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of("id", 10L));
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.CHANNEL_CONFIG, Map.of("channel_type", "LOCAL"));
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
    return context;
  }

  private DispatchJobContext buildContextWithRetryRequested() {
    DispatchJobContext context = buildContext();
    context.getAttributes().put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE);
    return context;
  }
}
