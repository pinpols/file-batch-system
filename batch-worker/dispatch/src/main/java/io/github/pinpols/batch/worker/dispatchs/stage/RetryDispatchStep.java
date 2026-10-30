package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Dispatch pipeline 的 RETRY 阶段：对 {@link DeliverDispatchStep} 投递失败的情况执行一次重试。
 *
 * <p>仅当上下文中 {@code retryRequested=true} 时执行实际重试；否则直接跳转到 {@code COMPENSATE} 阶段。 重试成功时跳转到 {@code ACK}
 * 阶段（{@code retryRecovered=true}），已知失败时跳转到 {@code COMPENSATE} 阶段。每次外发前先通过状态 CAS 持久化新的
 * {@code CREATED} 尝试，避免 Worker 强杀后盲目重放外部副作用。
 */
@Component
public class RetryDispatchStep implements DispatchStageStep {

  private static final String ERROR_CODE_RETRY_FAILED = "DISPATCH_RETRY_FAILED";

  private final FileDispatchRepository fileDispatchRepository;
  private final DispatchChannelGateway dispatchChannelGateway;

  public RetryDispatchStep(
      FileDispatchRepository fileDispatchRepository,
      DispatchChannelGateway dispatchChannelGateway) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.dispatchChannelGateway = dispatchChannelGateway;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.RETRY;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不重试外部投递（无副作用 → 也不需要 retry）。
    Map<String, Object> dryRunAttributes =
        EmptyChecks.isNull(context) ? null : context.getAttributes();
    if (DryRunGuard.fromAttributes(dryRunAttributes).isDryRun()) {
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
    Object payload = attrs.get(DispatchRuntimeKeys.DISPATCH_PAYLOAD);
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
    if (!Boolean.TRUE.equals(attrs.get(DispatchRuntimeKeys.RETRY_REQUESTED))) {
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      return DispatchStageResult.success(stage());
    }
    Long fileId = PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    @SuppressWarnings("unchecked")
    Map<String, Object> fileRecord =
        (Map<String, Object>) attrs.get(PipelineRuntimeKeys.FILE_RECORD);
    @SuppressWarnings("unchecked")
    Map<String, Object> channelConfig =
        (Map<String, Object>) attrs.get(PipelineRuntimeKeys.CHANNEL_CONFIG);
    int attemptStarted = fileDispatchRepository.beginAttempt(
        context.getTenantId(), fileId, dispatchPayload.channelCode());
    if (attemptStarted <= 0) {
      attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
      attrs.remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_ATTEMPT_STATE_CONFLICT",
          "error.dispatch.retry.failed",
          new Object[] {"dispatch attempt state changed before retry"},
          "dispatch attempt state changed before retry",
          ERROR_OBJECT_MAPPER);
    }
    DispatchResult dispatchResult = DispatchInvocationSupport.invokeAndRecordIdentifiers(
        dispatchChannelGateway, context, fileRecord, channelConfig, dispatchPayload);
    if (!dispatchResult.success()) {
      if (dispatchResult.externalOutcomeUnknown()) {
        fileDispatchRepository.markAttemptFailed(
            context.getTenantId(),
            fileId,
            dispatchPayload.channelCode(),
            TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
            dispatchResult.message());
        attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
        attrs.remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
        String messageForArguments = dispatchResult.message();
        String fallbackMessage = dispatchResult.message();
        return DispatchStageResult.failure(
            stage(),
            TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
            "error.dispatch.retry.failed",
            new Object[] {messageForArguments},
            fallbackMessage,
            ERROR_OBJECT_MAPPER);
      }
      int updated = fileDispatchRepository.markAttemptFailed(
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode(),
          "DISPATCH_RETRY",
          dispatchResult.message());
      if (updated <= 0) {
        attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
        attrs.remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
        return DispatchStageResult.failure(
            stage(),
            TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
            "error.dispatch.retry.failed",
            new Object[] {"retry result could not be committed after the external call"},
            "retry result could not be committed after the external call",
            ERROR_OBJECT_MAPPER);
      }
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPENSATE.name());
      String messageForArguments = dispatchResult.message();
      String fallbackMessage = dispatchResult.message();
      return DispatchStageResult.failure(
          stage(),
          ERROR_CODE_RETRY_FAILED,
          "error.dispatch.retry.failed",
          new Object[] {messageForArguments},
          fallbackMessage,
          ERROR_OBJECT_MAPPER);
    }
    int updated = DispatchInvocationSupport.markSent(
        fileDispatchRepository, context, fileId, dispatchPayload, dispatchResult);
    if (updated <= 0) {
      fileDispatchRepository.markAttemptFailed(
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode(),
          TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
          "external delivery succeeded but the SENT state could not be committed");
      attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
      attrs.remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
      return DispatchStageResult.failure(
          stage(),
          TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
          "error.dispatch.retry.failed",
          new Object[] {"external delivery succeeded but the SENT state could not be committed"},
          "external delivery succeeded but the SENT state could not be committed",
          ERROR_OBJECT_MAPPER);
    }
    attrs.put(DispatchRuntimeKeys.RETRY_RECOVERED, Boolean.TRUE);
    attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.ACK.name());
    return DispatchStageResult.success(stage());
  }
}
