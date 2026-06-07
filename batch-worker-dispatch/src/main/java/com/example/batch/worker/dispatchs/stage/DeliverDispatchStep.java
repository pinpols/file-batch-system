package com.example.batch.worker.dispatchs.stage;

import static com.example.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import com.example.batch.common.service.DryRunGuard;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchManifestRef;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Dispatch pipeline 的 DISPATCH 阶段：通过 {@link DispatchChannelGateway} 执行实际文件投递。
 *
 * <p><b>幂等写入</b>：若 {@code file_dispatch_record} 尚不存在则插入，已存在则递增投递次数， 防止重复任务创建重复记录。
 *
 * <p><b>结果处理</b>：投递成功时调用 {@code markSent}，回执状态取决于渠道是否即时确认（ACKED/PENDING/NONE）； 投递失败时调用 {@code
 * markFailed}，将下一步跳转强制设为 {@code RETRY} 阶段并返回失败。 文件状态同步推进为 {@code DISPATCHING}。
 */
@Component
public class DeliverDispatchStep implements DispatchStageStep {

  private final FileDispatchRepository fileDispatchRepository;
  private final DispatchChannelGateway dispatchChannelGateway;
  private final PlatformFileRuntimeRepository runtimeRepository;

  public DeliverDispatchStep(
      FileDispatchRepository fileDispatchRepository,
      DispatchChannelGateway dispatchChannelGateway,
      PlatformFileRuntimeRepository runtimeRepository) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.dispatchChannelGateway = dispatchChannelGateway;
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.DISPATCH;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_LOAD_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    @SuppressWarnings("unchecked")
    Map<String, Object> fileRecord =
        (Map<String, Object>) context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD);
    @SuppressWarnings("unchecked")
    Map<String, Object> channelConfig =
        (Map<String, Object>) context.getAttributes().get(PipelineRuntimeKeys.CHANNEL_CONFIG);
    if (fileId == null || fileRecord == null || channelConfig == null) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_PREPARE_MISSING",
          "error.dispatch.deliver.context_missing",
          new Object[0],
          "file or channel context missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> latestRecord =
        fileDispatchRepository.loadLatestDispatchRecord(
            context.getTenantId(), fileId, dispatchPayload.channelCode());
    if (latestRecord.isEmpty()) {
      int inserted =
          fileDispatchRepository.insertDispatchRecord(
              new FileDispatchRepository.InsertDispatchParam(
                  context.getTenantId(),
                  fileId,
                  runtimeRepository.toLong(
                      context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
                  dispatchPayload.channelCode(),
                  dispatchPayload.dispatchTarget(),
                  dispatchPayload.receiptCode(),
                  "NONE",
                  dispatchPayload.externalRequestId()));
      if (inserted <= 0) {
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_INSERT_FAILED",
            "error.dispatch.deliver.insert_failed",
            new Object[0],
            "failed to create dispatch record",
            ERROR_OBJECT_MAPPER);
      }
    } else {
      fileDispatchRepository.incrementAttempt(
          context.getTenantId(), fileId, dispatchPayload.channelCode());
    }
    // ADR-026: 演练模式下不真发外部投递，伪造一个"成功 + dry-run"的 DispatchResult，
    // 让后续 markSent / file_dispatch_record 状态推进按演练通道走。
    DryRunGuard guard = DryRunGuard.fromAttributes(context.getAttributes());
    DispatchResult dispatchResult =
        guard.callOrSkip(
            "dispatch.deliver",
            () ->
                DispatchInvocationSupport.invokeAndRecordIdentifiers(
                    dispatchChannelGateway, context, fileRecord, channelConfig, dispatchPayload),
            DispatchResult.success(
                "DRY_RUN", "DRY_RUN_RECEIPT_" + dispatchPayload.channelCode(), false));
    // dry-run 分支直接拿到 fake 结果，没经过 helper 的 propagate，这里补一次（真实路径会重复设置但等价）。
    DispatchInvocationSupport.propagateIdentifiers(context, dispatchResult);
    Map<String, Object> fileMetadata = new LinkedHashMap<>();
    fileMetadata.put("channelCode", dispatchPayload.channelCode());
    if (dispatchResult.externalRequestId() != null) {
      fileMetadata.put("externalRequestId", dispatchResult.externalRequestId());
    }
    DispatchManifestRef manifestRef = dispatchResult.manifestRef();
    if (manifestRef != null) {
      manifestRef.putFileMetadata(fileMetadata);
    }
    runtimeRepository.updateFileStatus(fileId, "DISPATCHING", fileMetadata);
    if (!dispatchResult.success()) {
      context.getAttributes().put("retryRequested", Boolean.TRUE);
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.RETRY.name());
      fileDispatchRepository.markFailed(
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode(),
          "DISPATCH_DELIVERY_FAILED",
          dispatchResult.message());
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_SEND_FAILED",
          "error.dispatch.deliver.send_failed",
          new Object[] {dispatchResult.message()},
          dispatchResult.message(),
          ERROR_OBJECT_MAPPER);
    }
    int updated =
        DispatchInvocationSupport.markSent(
            fileDispatchRepository, context, fileId, dispatchPayload, dispatchResult);
    if (updated <= 0) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_SEND_FAILED",
          "error.dispatch.deliver.send_failed",
          new Object[] {"failed to mark sent"},
          "failed to mark sent",
          ERROR_OBJECT_MAPPER);
    }
    context.getAttributes().put("dispatchRecord", dispatchPayload);
    return DispatchStageResult.success(stage());
  }
}
