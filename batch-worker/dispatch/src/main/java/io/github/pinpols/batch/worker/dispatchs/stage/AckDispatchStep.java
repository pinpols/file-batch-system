package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 分发 ACK 阶段：处理回执确认，更新分发记录及文件状态为 DISPATCHED。 */
@Component
public class AckDispatchStep implements DispatchStageStep {

  private final FileDispatchRepository fileDispatchRepository;
  private final PlatformFileRecordRepository fileRecords;

  public AckDispatchStep(
      FileDispatchRepository fileDispatchRepository, PlatformFileRecordRepository fileRecords) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.fileRecords = fileRecords;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.ACK;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    // ADR-026: 演练模式不更新 file_dispatch_record，跳过 ack。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes())
        .isDryRun()) {
      return DispatchStageResult.success(stage());
    }
    Object payload = EmptyChecks.isNull(context)
        ? null
        : context.getAttributes().get(DispatchRuntimeKeys.DISPATCH_PAYLOAD);
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_ACK_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    Long fileId = PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    DispatchResult dispatchResult =
        attrs.get(DispatchRuntimeKeys.DISPATCH_RESULT) instanceof DispatchResult result
            ? result
            : null;
    String receiptCode = dispatchPayload.receiptCode();
    if ((receiptCode == null || receiptCode.isBlank()) && dispatchResult != null) {
      receiptCode = dispatchResult.receiptCode();
    }
    boolean acknowledged = dispatchResult != null && dispatchResult.acknowledged();
    boolean pending = dispatchResult != null && dispatchResult.receiptPending();
    if (acknowledged || (receiptCode != null && !receiptCode.isBlank())) {
      int updated = fileDispatchRepository.markAcked(
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode(),
          receiptCode == null ? "ACK-" + fileId : receiptCode);
      if (updated <= 0) {
        attrs.put(
            PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE,
            Boolean.TRUE.equals(attrs.get(DispatchRuntimeKeys.RETRY_REQUESTED))
                ? DispatchStage.RETRY.name()
                : DispatchStage.COMPENSATE.name());
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_ACK_FAILED",
            "error.dispatch.ack.failed",
            new Object[0],
            "failed to mark acked",
            ERROR_OBJECT_MAPPER);
      }
      fileRecords.updateFileStatus(
          fileId,
          FileStatus.DISPATCHED.code(),
          buildFileMetadata(dispatchPayload, context, receiptCode));
      attrs.put(DispatchRuntimeKeys.RECEIPT_STATUS, FileReceiptStatus.SUCCESS.code());
      return DispatchStageResult.success(stage());
    }
    if (pending || Boolean.TRUE.equals(dispatchPayload.ackRequired())) {
      attrs.put(DispatchRuntimeKeys.RECEIPT_STATUS, FileReceiptStatus.PENDING.code());
      return DispatchStageResult.success(stage());
    }
    fileRecords.updateFileStatus(
        fileId,
        FileStatus.DISPATCHED.code(),
        buildFileMetadata(dispatchPayload, context, receiptCode));
    return DispatchStageResult.success(stage());
  }

  private Map<String, Object> buildFileMetadata(
      DispatchPayload dispatchPayload, DispatchJobContext context, String receiptCode) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(DispatchRuntimeKeys.CHANNEL_CODE, dispatchPayload.channelCode());
    metadata.put(
        DispatchRuntimeKeys.EXTERNAL_REQUEST_ID,
        context.getAttributes().get(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID));
    metadata.put(DispatchRuntimeKeys.RECEIPT_CODE, receiptCode);
    return metadata;
  }
}
