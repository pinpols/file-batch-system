package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchCommand;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

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
    if (context == null) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_NO_CONTEXT",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch context missing",
          ERROR_OBJECT_MAPPER);
    }
    Object payload = context.getAttributes().get("dispatchPayload");
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    if (!Boolean.TRUE.equals(context.getAttributes().get("retryRequested"))) {
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.success(stage());
    }
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    @SuppressWarnings("unchecked")
    Map<String, Object> fileRecord =
        (Map<String, Object>) context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD);
    @SuppressWarnings("unchecked")
    Map<String, Object> channelConfig =
        (Map<String, Object>) context.getAttributes().get(PipelineRuntimeKeys.CHANNEL_CONFIG);
    fileDispatchRepository.incrementAttempt(
        context.getTenantId(), fileId, dispatchPayload.channelCode());
    DispatchResult dispatchResult =
        dispatchChannelGateway.dispatch(
            new DispatchCommand(
                context.getTenantId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                fileRecord,
                channelConfig,
                dispatchPayload));
    context.getAttributes().put("dispatchResult", dispatchResult);
    context.getAttributes().put("externalRequestId", dispatchResult.externalRequestId());
    context.getAttributes().put("receiptCode", dispatchResult.receiptCode());
    if (!dispatchResult.success()) {
      int updated =
          fileDispatchRepository.markFailed(
              context.getTenantId(),
              fileId,
              dispatchPayload.channelCode(),
              "DISPATCH_RETRY",
              dispatchResult.message());
      if (updated <= 0) {
        context
            .getAttributes()
            .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_RETRY_FAILED",
            "error.dispatch.retry.failed",
            new Object[] {"failed to mark failed"},
            "failed to mark failed",
            ERROR_OBJECT_MAPPER);
      }
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_FAILED",
          "error.dispatch.retry.failed",
          new Object[] {dispatchResult.message()},
          dispatchResult.message(),
          ERROR_OBJECT_MAPPER);
    }
    int updated =
        fileDispatchRepository.markSent(
            context.getTenantId(),
            fileId,
            dispatchPayload.channelCode(),
            dispatchResult.externalRequestId(),
            dispatchResult.receiptCode(),
            dispatchResult.acknowledged()
                ? "SUCCESS"
                : dispatchResult.receiptPending() ? "PENDING" : "NONE");
    if (updated <= 0) {
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_RETRY_FAILED",
          "error.dispatch.retry.failed",
          new Object[] {"failed to mark retry sent"},
          "failed to mark retry sent",
          ERROR_OBJECT_MAPPER);
    }
    context.getAttributes().put("retryRecovered", Boolean.TRUE);
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.ACK.name());
    return DispatchStageResult.success(stage());
  }
}
