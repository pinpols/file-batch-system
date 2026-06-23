package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Dispatch pipeline 的 RETRY 阶段：对 {@link DeliverDispatchStep} 投递失败的情况执行一次重试。
 *
 * <p>仅当上下文中 {@code retryRequested=true} 时执行实际重试；否则直接跳转到 {@code COMPENSATE} 阶段。 重试成功时跳转到 {@code ACK}
 * 阶段（{@code retryRecovered=true}），失败时跳转到 {@code COMPENSATE} 阶段。 每次尝试通过 {@code incrementAttempt}
 * 递增投递计数。
 */
@Component
public class RetryDispatchStep implements DispatchStageStep {

  private final FileDispatchRepository fileDispatchRepository;
  private final DispatchChannelGateway dispatchChannelGateway;
  private final PlatformFileRuntimeRepository runtimeRepository;

  public RetryDispatchStep(
      FileDispatchRepository fileDispatchRepository,
      DispatchChannelGateway dispatchChannelGateway,
      PlatformFileRuntimeRepository runtimeRepository) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.dispatchChannelGateway = dispatchChannelGateway;
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.RETRY;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不重试外部投递（无副作用 → 也不需要 retry）。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return DispatchStageResult.success(stage());
    }
    if (context == null) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_NO_CONTEXT",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch context missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    Object payload = attrs.get("dispatchPayload");
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    if (!Boolean.TRUE.equals(attrs.get("retryRequested"))) {
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.success(stage());
    }
    Long fileId = runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    @SuppressWarnings("unchecked")
    Map<String, Object> fileRecord =
        (Map<String, Object>) attrs.get(PipelineRuntimeKeys.FILE_RECORD);
    @SuppressWarnings("unchecked")
    Map<String, Object> channelConfig =
        (Map<String, Object>) attrs.get(PipelineRuntimeKeys.CHANNEL_CONFIG);
    fileDispatchRepository.incrementAttempt(
        context.getTenantId(), fileId, dispatchPayload.channelCode());
    DispatchResult dispatchResult =
        DispatchInvocationSupport.invokeAndRecordIdentifiers(
            dispatchChannelGateway, context, fileRecord, channelConfig, dispatchPayload);
    if (!dispatchResult.success()) {
      int updated =
          fileDispatchRepository.markFailed(
              context.getTenantId(),
              fileId,
              dispatchPayload.channelCode(),
              "DISPATCH_RETRY",
              dispatchResult.message());
      if (updated <= 0) {
        attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_RETRY_FAILED",
            "error.dispatch.retry.failed",
            new Object[] {"failed to mark failed"},
            "failed to mark failed",
            ERROR_OBJECT_MAPPER);
      }
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_FAILED",
          "error.dispatch.retry.failed",
          new Object[] {dispatchResult.message()},
          dispatchResult.message(),
          ERROR_OBJECT_MAPPER);
    }
    int updated =
        DispatchInvocationSupport.markSent(
            fileDispatchRepository, context, fileId, dispatchPayload, dispatchResult);
    if (updated <= 0) {
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_FAILED",
          "error.dispatch.retry.failed",
          new Object[] {"failed to mark retry sent"},
          "failed to mark retry sent",
          ERROR_OBJECT_MAPPER);
    }
    attrs.put("retryRecovered", Boolean.TRUE);
    attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.ACK.name());
    return DispatchStageResult.success(stage());
  }
}
