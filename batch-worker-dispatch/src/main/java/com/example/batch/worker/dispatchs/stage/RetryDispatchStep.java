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
import java.util.Map;
import org.springframework.stereotype.Component;

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
    Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(), "DISPATCH_RETRY_NO_PAYLOAD", "dispatch payload missing");
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
            stage(), "DISPATCH_RETRY_FAILED", "failed to mark failed");
      }
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.failure(
          stage(), "DISPATCH_RETRY_FAILED", dispatchResult.message());
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
          stage(), "DISPATCH_RETRY_FAILED", "failed to mark retry sent");
    }
    context.getAttributes().put("retryRecovered", Boolean.TRUE);
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.ACK.name());
    return DispatchStageResult.success(stage());
  }
}
